#ifndef TUNA_MEMORY_FILE_H
#define TUNA_MEMORY_FILE_H
#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 1U
#define MFD_ALLOW_SEALING 2U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_GET_SEALS 1034
#define F_SEAL_SEAL 1
#define F_SEAL_SHRINK 2
#define F_SEAL_GROW 4
#define F_SEAL_WRITE 8
#endif
#define TUNA_MAX_BYTES (16U * 1024U * 1024U)
static int tuna_memory_create(void) {
#ifdef SYS_memfd_create
    int fd = (int)syscall(SYS_memfd_create, "tuna-runtime", MFD_CLOEXEC | MFD_ALLOW_SEALING);
#else
    errno = ENOSYS; int fd = -1;
#endif
    if (fd >= 0 && fd < 3) {
        int high = fcntl(fd, F_DUPFD_CLOEXEC, 3);
        close(fd); fd = high;
    }
    return fd;
}
static int tuna_memory_seal(int fd) {
    int seals = F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK | F_SEAL_SEAL;
    if (lseek(fd, 0, SEEK_SET) != 0 || fcntl(fd, F_ADD_SEALS, seals) < 0) return -1;
    int actual = fcntl(fd, F_GET_SEALS);
    return actual >= 0 && (actual & seals) == seals ? 0 : -1;
}
#endif
