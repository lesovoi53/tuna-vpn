#define _GNU_SOURCE
#include <jni.h>
#include "memory_file.h"

static void fail(JNIEnv *env) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, "Cannot create or close sealed runtime memory file");
}

JNIEXPORT jint JNICALL Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeCreate(
        JNIEnv *env, jobject self, jbyteArray bytes) {
    (void)self;
    if (!bytes) { fail(env); return -1; }
    jsize size = (*env)->GetArrayLength(env, bytes);
    if (size < 0 || (unsigned)size > TUNA_MAX_BYTES) { fail(env); return -1; }
    int fd = tuna_memory_create();
    if (fd < 0) { fail(env); return -1; }
    jbyte buffer[8192];
    for (jsize offset = 0; offset < size;) {
        jsize count = size - offset;
        if (count > (jsize)sizeof(buffer)) count = sizeof(buffer);
        (*env)->GetByteArrayRegion(env, bytes, offset, count, buffer);
        if ((*env)->ExceptionCheck(env)) { close(fd); return -1; }
        jsize written = 0;
        while (written < count) {
            ssize_t part = write(fd, buffer + written, (size_t)(count - written));
            if (part < 0 && errno == EINTR) continue;
            if (part <= 0) { close(fd); fail(env); return -1; }
            written += (jsize)part;
        }
        offset += count;
    }
    /* Stay CLOEXEC: HEV consumes this FD in the same app process. */
    if (tuna_memory_seal(fd)) { close(fd); fail(env); return -1; }
    return fd;
}

JNIEXPORT void JNICALL Java_com_wireturn_app_kernel_MemoryFileDescriptor_nativeClose(
        JNIEnv *env, jobject self, jint fd) {
    (void)self;
    /* Ownership belongs to the Kotlin wrapper; it must close each result once only. */
    if (fd < 3 || close(fd) < 0) fail(env);
}
