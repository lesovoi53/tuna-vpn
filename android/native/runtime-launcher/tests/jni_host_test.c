#define _GNU_SOURCE
#include <jni.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern jint Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(JNIEnv *, jobject, jbyteArray);
extern void Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeClose(JNIEnv *, jobject, jint);
struct test_array { const unsigned char *bytes; jsize size; };
static int exception, inject_region_failure;
static jsize size_of(JNIEnv *env, jarray array) { (void)env; return ((struct test_array *)array)->size; }
static void region(JNIEnv *env, jbyteArray array, jsize start, jsize length, jbyte *output) {
    (void)env;
    if (inject_region_failure) { exception = 1; return; }
    memcpy(output, ((struct test_array *)array)->bytes + start, (size_t)length);
}
static jboolean pending(JNIEnv *env) { (void)env; return (jboolean)exception; }
static jclass find_class(JNIEnv *env, const char *name) {
    (void)env; assert(!strcmp(name, "java/io/IOException")); return (jclass)1;
}
static jint throw_new(JNIEnv *env, jclass type, const char *message) {
    (void)env; (void)type; assert(!strstr(message, "fixture")); exception = 1; return 0;
}
int main(void) {
    struct JNINativeInterface table = {0};
    table.GetArrayLength = size_of; table.GetByteArrayRegion = region;
    table.ExceptionCheck = pending; table.FindClass = find_class; table.ThrowNew = throw_new;
    JNIEnv env = &table;
    unsigned char bytes[20000], result[20000];
    for (size_t i = 0; i < sizeof(bytes); ++i) bytes[i] = (unsigned char)i;
    struct test_array array = {bytes, sizeof(bytes)};
    int fd = Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(&env, NULL, (jbyteArray)&array);
    assert(fd >= 3 && !exception && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
    char path[48]; snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    int read_fd = open(path, O_RDONLY); assert(read_fd >= 0);
    assert(read(read_fd, result, sizeof(result)) == sizeof(result) && !memcmp(bytes, result, sizeof(bytes)));
    assert(lseek(read_fd, 0, SEEK_SET) == 0 && read(read_fd, result, sizeof(result)) == sizeof(result));
    close(read_fd);
    errno = 0; assert(write(fd, "x", 1) == -1 && errno == EPERM);
    errno = 0; assert(ftruncate(fd, 0) == -1 && errno == EPERM);
    Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeClose(&env, NULL, fd);
    assert(!exception && fcntl(fd, F_GETFD) == -1 && errno == EBADF);
    assert(Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(&env, NULL, NULL) == -1 && exception);
    exception = 0; array.size = 16 * 1024 * 1024 + 1;
    assert(Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(&env, NULL, (jbyteArray)&array) == -1 && exception);
    exception = 0; array.size = sizeof(bytes); inject_region_failure = 1;
    assert(Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(&env, NULL, (jbyteArray)&array) == -1 && exception);
    exception = 0; inject_region_failure = 0; array.size = 0;
    fd = Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(&env, NULL, (jbyteArray)&array);
    assert(fd >= 3 && !exception && read(fd, result, sizeof(result)) == 0);
    Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeClose(&env, NULL, fd);
    assert(!exception);
    Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeClose(&env, NULL, 0);
    assert(exception && fcntl(STDIN_FILENO, F_GETFD) >= 0);
    puts("JNI PASS: bytes, reopen, seek, seals, close, invalid inputs, injected JNI failure");
    return 0;
}
