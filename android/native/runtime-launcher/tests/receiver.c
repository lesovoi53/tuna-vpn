#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int check_file(const char *path, const unsigned char *expected, size_t size) {
    if (strncmp(path, "/proc/self/fd/", 14)) return 1;
    int inherited = atoi(path + 14);
    if (inherited < 3 || fcntl(inherited, F_GETFD) < 0 ||
        (fcntl(inherited, F_GETFD) & FD_CLOEXEC)) return 2;
    int seals = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    int actual_seals = fcntl(inherited, F_GET_SEALS);
    if (actual_seals < 0 || (actual_seals & seals) != seals) return 3;
    unsigned char buffer[1024];
    for (unsigned attempt = 0; attempt < 2; ++attempt) {
        int fd = open(path, O_RDONLY);
        if (fd < 0) return 4;
        ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count != (ssize_t)size || memcmp(buffer, expected, size)) return 5;
        if (lseek(fd, 0, SEEK_SET) != 0 || read(fd, buffer, sizeof(buffer)) != (ssize_t)size ||
            memcmp(buffer, expected, size)) return 6;
        close(fd);
    }
    errno = 0;
    if (write(inherited, "x", 1) != -1 || errno != EPERM) return 7;
    errno = 0;
    if (ftruncate(inherited, 0) != -1 || errno != EPERM) return 8;
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 5 || strcmp(argv[3], "literal;$(not-a-shell)") || strcmp(argv[4], "UTF8-\xc3\xa9")) return 20;
    char control[6]; size_t received = 0;
    while (received < sizeof(control)) {
        ssize_t count = read(STDIN_FILENO, control + received, sizeof(control) - received);
        if (count <= 0) return 21;
        received += (size_t)count;
    }
    if (memcmp(control, "PAUSE\n", sizeof(control))) return 22;
    const char *routing = getenv("TUNA_ROUTING_FILE"), *unchanged = getenv("TUNA_TEST_UNCHANGED");
    if (!routing || strcmp(routing, argv[2]) || !unchanged || strcmp(unchanged, "ordinary-value")) return 23;
    char primary[256];
    int length = snprintf(primary, sizeof(primary), "resolver=%s\nsecret=fixture-only\n", argv[2]);
    const unsigned char secondary[] = "fixture\0bytes\n";
    int error = check_file(argv[1], (const unsigned char *)primary, (size_t)length);
    if (!error) error = check_file(argv[2], secondary, sizeof(secondary) - 1);
    if (error) return 30 + error;
    puts("PASS");
    return 0;
}
