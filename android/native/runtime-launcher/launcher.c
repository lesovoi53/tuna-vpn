#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "memory_file.h"

#define MAX_ARGS 64U
#define MAX_FILES 32U
#define MAX_BYTES TUNA_MAX_BYTES
#define MAX_TOKEN 96U
#define TOKEN_PREFIX "__TUNA_MEM_"

struct blob { unsigned char *data; size_t length; };
struct memory_file { struct blob token, content; int fd; char path[48]; };
static struct memory_file files[MAX_FILES];
static uint32_t file_count;
static size_t input_bytes, output_bytes;
static const char *failure_stage = "frame";

static void erase(void *memory, size_t size) {
    volatile unsigned char *p = memory;
    while (size--) *p++ = 0;
}

static void free_blob(struct blob *value) {
    if (value->data) { erase(value->data, value->length); free(value->data); }
    value->data = NULL; value->length = 0;
}

static int read_exact(void *data, size_t size) {
    if (size > MAX_BYTES - input_bytes) return -1;
    input_bytes += size;
    unsigned char *p = data;
    while (size) {
        ssize_t count = read(STDIN_FILENO, p, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        p += count; size -= (size_t)count;
    }
    return 0;
}

static int read_u32(uint32_t *value) {
    unsigned char bytes[4];
    if (read_exact(bytes, sizeof(bytes))) return -1;
    *value = ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16) |
             ((uint32_t)bytes[2] << 8) | bytes[3];
    return 0;
}

/* Reject invalid, overlong UTF-8, surrogate codepoints and out-of-range values. */
static int valid_utf8(const unsigned char *data, size_t length) {
    for (size_t i = 0; i < length;) {
        uint32_t value = data[i++], minimum; unsigned remaining;
        if (value < 0x80) continue;
        if (value >= 0xc2 && value <= 0xdf) { value &= 0x1f; remaining = 1; minimum = 0x80; }
        else if (value >= 0xe0 && value <= 0xef) { value &= 0x0f; remaining = 2; minimum = 0x800; }
        else if (value >= 0xf0 && value <= 0xf4) { value &= 7; remaining = 3; minimum = 0x10000; }
        else return 0;
        if (remaining > length - i) return 0;
        while (remaining--) {
            unsigned char next = data[i++];
            if ((next & 0xc0) != 0x80) return 0;
            value = (value << 6) | (next & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return 0;
    }
    return 1;
}

static int read_blob(struct blob *value, int forbid_nul, size_t limit) {
    uint32_t length;
    if (read_u32(&length) || length > limit || length > MAX_BYTES - input_bytes) return -1;
    value->data = malloc((size_t)length + 1);
    if (!value->data) return -1;
    value->length = length;
    if (read_exact(value->data, length)) return -1;
    value->data[length] = 0;
    if ((forbid_nul && memchr(value->data, 0, length)) || !valid_utf8(value->data, length)) return -1;
    return 0;
}

static int valid_token(const struct blob *token) {
    size_t prefix = sizeof(TOKEN_PREFIX) - 1;
    if (token->length < prefix + 3 || memcmp(token->data, TOKEN_PREFIX, prefix) ||
        token->data[token->length - 1] != '_' || token->data[token->length - 2] != '_') return 0;
    for (size_t i = prefix; i < token->length - 2; ++i) {
        unsigned char c = token->data[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return 0;
    }
    return 1;
}

static int valid_executable(const struct blob *value) {
    const char *path = (const char *)value->data;
    if (value->length < 5 || path[0] != '/' || strstr(path, "/../") || strstr(path, "/./") ||
        strstr(path, "//") || strstr(path, TOKEN_PREFIX)) return 0;
    const char *base = strrchr(path, '/');
    return base && strlen(base + 1) > 3 && !strcmp(path + value->length - 3, ".so");
}

static int allocate_file(struct memory_file *file) {
    file->fd = tuna_memory_create();
    if (file->fd < 0) return -1;
    int size = snprintf(file->path, sizeof(file->path), "/proc/self/fd/%d", file->fd);
    return size > 0 && (size_t)size < sizeof(file->path) ? 0 : -1;
}

/* Two passes avoid unbounded replacement growth; tokens are opaque and non-nested. */
static int replace_tokens(const struct blob *source, struct blob *output) {
    for (unsigned pass = 0; pass < 2; ++pass) {
        size_t used = 0;
        for (size_t i = 0; i < source->length;) {
            const unsigned char *piece = source->data + i;
            size_t take = 1, advance = 1;
            if (source->length - i >= sizeof(TOKEN_PREFIX) - 1 &&
                !memcmp(piece, TOKEN_PREFIX, sizeof(TOKEN_PREFIX) - 1)) {
                uint32_t match;
                for (match = 0; match < file_count; ++match) {
                    if (source->length - i >= files[match].token.length &&
                        !memcmp(piece, files[match].token.data, files[match].token.length)) break;
                }
                if (match == file_count) return -1;
                advance = files[match].token.length;
                piece = (const unsigned char *)files[match].path;
                take = strlen(files[match].path);
            }
            if (take > MAX_BYTES - used) return -1;
            if (pass) memcpy(output->data + used, piece, take);
            used += take; i += advance;
        }
        if (!pass) {
            if (used > MAX_BYTES - output_bytes) return -1;
            output_bytes += used;
            output->data = malloc(used + 1);
            if (!output->data) return -1;
            output->length = used;
        } else output->data[used] = 0;
    }
    return 0;
}

static int write_file(struct memory_file *file, const struct blob *content) {
    size_t offset = 0;
    while (offset < content->length) {
        ssize_t count = write(file->fd, content->data + offset, content->length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        offset += (size_t)count;
    }
    return tuna_memory_seal(file->fd);
}

int main(void) {
    struct blob arguments[MAX_ARGS] = {{0}}, replaced = {0};
    char *child_argv[MAX_ARGS + 1] = {0};
    uint32_t argument_count = 0;
    unsigned char magic[8];
    int result = 125;
    for (uint32_t i = 0; i < MAX_FILES; ++i) files[i].fd = -1;
    struct rlimit no_core = {0, 0};
    if (setrlimit(RLIMIT_CORE, &no_core)) { failure_stage = "core-limit"; goto done; }
    if (read_exact(magic, sizeof(magic)) || memcmp(magic, "TUNAMEM1", 8) ||
        read_u32(&argument_count) || !argument_count || argument_count > MAX_ARGS) goto done;
    for (uint32_t i = 0; i < argument_count; ++i)
        if (read_blob(&arguments[i], 1, MAX_BYTES)) goto done;
    if (!valid_executable(&arguments[0]) || read_u32(&file_count) || file_count > MAX_FILES) goto done;
    for (uint32_t i = 0; i < file_count; ++i) {
        if (read_blob(&files[i].token, 1, MAX_TOKEN) || !valid_token(&files[i].token) ||
            read_blob(&files[i].content, 0, MAX_BYTES)) goto done;
        for (uint32_t j = 0; j < i; ++j)
            if (files[j].token.length == files[i].token.length &&
                !memcmp(files[j].token.data, files[i].token.data, files[i].token.length)) goto done;
    }
    /* No read-ahead/EOF check: remaining stdin is the native core's control stream. */
    failure_stage = "memory-file";
    for (uint32_t i = 0; i < file_count; ++i) if (allocate_file(&files[i])) goto done;
    failure_stage = "replacement";
    for (uint32_t i = 0; i < argument_count; ++i) {
        if (replace_tokens(&arguments[i], &replaced)) goto done;
        free_blob(&arguments[i]); arguments[i] = replaced;
        replaced.data = NULL; replaced.length = 0;
        child_argv[i] = (char *)arguments[i].data;
    }
    const char *routing = getenv("TUNA_ROUTING_FILE");
    if (routing) {
        struct blob environment = {(unsigned char *)routing, strlen(routing)};
        if (environment.length > MAX_BYTES || replace_tokens(&environment, &replaced) ||
            setenv("TUNA_ROUTING_FILE", (char *)replaced.data, 1)) goto done;
        free_blob(&replaced);
    }
    for (uint32_t i = 0; i < file_count; ++i) {
        failure_stage = "replacement";
        if (replace_tokens(&files[i].content, &replaced)) goto done;
        failure_stage = "memory-file";
        if (write_file(&files[i], &replaced)) goto done;
        free_blob(&replaced); free_blob(&files[i].content);
    }
    failure_stage = "descriptor";
    for (uint32_t i = 0; i < file_count; ++i) {
        int flags = fcntl(files[i].fd, F_GETFD);
        if (flags < 0 || fcntl(files[i].fd, F_SETFD, flags & ~FD_CLOEXEC) < 0) goto done;
    }
    failure_stage = "exec";
    execv(child_argv[0], child_argv);
done:
    /* Never emit paths, arguments, file contents, tokens, or OS error strings. */
    fprintf(stderr, "TUNA memory launcher failed: %s\n", failure_stage);
    free_blob(&replaced);
    for (uint32_t i = 0; i < MAX_ARGS; ++i) free_blob(&arguments[i]);
    for (uint32_t i = 0; i < MAX_FILES; ++i) {
        free_blob(&files[i].token); free_blob(&files[i].content);
        if (files[i].fd >= 0) close(files[i].fd);
    }
    return result;
}
