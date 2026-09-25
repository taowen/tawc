/* Bounded namespace startup model, not kernel isolation. No application names
 * or command-line detection. State follows the runtime's exec-state transport. */
#include <linux/sched.h>
#include <linux/capability.h>
#include <asm/unistd.h>
#include <sys/stat.h>
#include "namespace.h"
#include "dispatch.h"
#include "errno_neg.h"
#include "fdtab.h"
#include "io.h"
#include "raw_sys.h"
#include "tawc_string.h"
#include "tawc_uapi.h"
#include "usercopy.h"

#define NS_FLAGS (CLONE_NEWUSER | CLONE_NEWPID | CLONE_NEWNET)
struct tawc_namespace tawcroot_namespace;

int tawcroot_namespace_probe(const char *path)
{
    return tawc_streq(path, "/proc/self/ns/user") ||
        tawc_streq(path, "/proc/self/ns/pid") || tawc_streq(path, "/proc/self/ns/net");
}

static long new_user(void)
{
    struct tawc_namespace *ns = &tawcroot_namespace;
    /* Allocate before publishing user state. Descriptor allocation failures
     * must not appear to have successfully created a namespace. */
    int fds[3];
    for (int i = 0; i < 3; ++i) {
        long fd = tawc_memfd_create("tawcroot-id-map", 2);
        if (fd < 0) {
            while (i) tawc_close(fds[--i]);
            return fd;
        }
        fds[i] = (int)fd;
    }
    int reserved[3];
    for (int i = 0; i < 3; ++i) {
        long fd = tawcroot_fd_reserve(fds[i]);
        if (fd < 0) {
            for (int j = i; j < 3; ++j) tawc_close(fds[j]);
            for (int j = 0; j < i; ++j) {
                tawcroot_fd_forget_reserved(reserved[j]);
                tawc_close(reserved[j]);
            }
            return fd;
        }
        reserved[i] = (int)fd;
        (void)tawc_fcntl((int)fd, F_SETFD, 0);
    }
    if (ns->user)
        for (int i = 0; i < 3; ++i) {
            tawcroot_fd_forget_reserved(ns->maps[i]);
            tawc_close(ns->maps[i]);
        }
    memcpy(ns->maps, reserved, sizeof reserved);
    (void)tawc_write(ns->maps[2], "allow\n", 6);
    ns->user = 1;
    ns->capabilities[0] = ns->capabilities[1] = 0xffffffffu;
    ns->capabilities[3] = ns->capabilities[4] = 0x1ff;
    ns->capabilities[2] = ns->capabilities[5] = 0;
    return 0;
}

void tawcroot_namespace_restore(void)
{
    if (tawcroot_namespace.user)
        for (int i = 0; i < 3; ++i)
            (void)tawcroot_fd_record_reserved(tawcroot_namespace.maps[i]);
}

long tawcroot_namespace_open(const char *path, int flags)
{
    if (!tawcroot_namespace.user || !tawc_starts_with(path, "/proc/")) return TAWC_ENOSYS;
    const char *leaf = path + 6;
    if (tawc_starts_with(leaf, "self/")) leaf += 5;
    else {
        long pid = 0;
        while (*leaf >= '0' && *leaf <= '9') {
            pid = pid * 10 + *leaf++ - '0';
            if (pid > 0x7fffffff) return TAWC_ENOSYS;
        }
        if (*leaf != '/' || (pid != tawc_getpid() &&
            !(pid == 1 && tawcroot_namespace.init_pid == tawc_getpid()))) return TAWC_ENOSYS;
        ++leaf;
    }
    int kind = tawc_streq(leaf, "uid_map") ? 0 : tawc_streq(leaf, "gid_map") ? 1 :
        tawc_streq(leaf, "setgroups") ? 2 : -1;
    if (kind < 0) return TAWC_ENOSYS;
    if (flags & (O_CREAT | O_EXCL | O_DIRECTORY | O_PATH | O_TRUNC)) return TAWC_EINVAL;
    int fd = tawcroot_namespace.maps[kind];
    if ((flags & O_ACCMODE) == O_RDONLY) {
        char data[128];
        long n = TAWC_RAW(TAWC_SYS_pread64, fd, (long)data, sizeof data, 0, 0, 0);
        if (n < 0) return n;
        long copy = tawc_memfd_create("tawcroot-map-read", 2 | ((flags & O_CLOEXEC) ? 1 : 0));
        if (copy < 0) return copy;
        long written = tawc_write((int)copy, data, (size_t)n);
        if (written != n) { tawc_close((int)copy); return written < 0 ? written : TAWC_EIO; }
        (void)tawc_lseek((int)copy, 0, SEEK_SET);
        (void)tawc_fcntl((int)copy, 1033, 8); /* F_ADD_SEALS, F_SEAL_WRITE */
        return copy;
    }
    if ((flags & O_ACCMODE) != O_WRONLY) return TAWC_EINVAL;
    (void)tawc_lseek(fd, 0, SEEK_SET);
    return tawc_fcntl(fd, (flags & O_CLOEXEC) ? F_DUPFD_CLOEXEC : F_DUPFD, 0);
}

static long clone_namespace(const tawcroot_syscall_args *a, ucontext_t *uc)
{
    unsigned long flags = (unsigned long)a->a;
    if (flags & (CLONE_VM | CLONE_THREAD)) return TAWC_EINVAL;
    if ((flags & CLONE_NEWUSER) && (flags & CLONE_FS)) return TAWC_EINVAL;
#if defined(__aarch64__)
    long result = TAWC_RAW(TAWC_SYS_clone, flags & ~NS_FLAGS, 0, a->c, a->d, a->e, 0);
    if (!result) {
        if (flags & CLONE_NEWPID) tawcroot_namespace.init_pid = (int)tawc_getpid();
        if ((flags & CLONE_NEWUSER) && new_user() < 0) tawc_exit_group(125);
        if (a->b) uc->uc_mcontext.sp = (unsigned long)a->b;
    }
    return result;
#else
    (void)uc;
    return TAWC_ENOSYS;
#endif
}

static long unshare_namespace(const tawcroot_syscall_args *a, ucontext_t *uc)
{
    (void)uc;
    unsigned long flags = (unsigned long)a->a;
    if (flags & ~(CLONE_NEWUSER | CLONE_NEWNET | CLONE_FS)) return TAWC_EINVAL;
    long result = (flags & CLONE_FS) ? TAWC_RAW(TAWC_SYS_unshare, CLONE_FS, 0, 0, 0, 0, 0) : 0;
    if (!result && (flags & CLONE_NEWUSER)) result = new_user();
    return result;
}

static long namespace_pid(const tawcroot_syscall_args *a, ucontext_t *uc)
{
    (void)uc;
    long pid = TAWC_RAW(a->nr, 0, 0, 0, 0, 0, 0);
    int init = tawcroot_namespace.init_pid;
    if (!init) return pid;
    if (a->nr == TAWC_SYS_getppid && tawc_getpid() == init) return 0;
    return pid == init ? 1 : pid;
}

static long namespace_cap(const tawcroot_syscall_args *a, ucontext_t *uc)
{
    (void)uc;
    if (!tawcroot_namespace.user) return TAWC_RAW(a->nr, a->a, a->b, 0, 0, 0, 0);
    struct __user_cap_header_struct header;
    if (tawc_copy_from_guest(&header, sizeof header, (void *)a->a) < 0) return TAWC_EFAULT;
    if (header.version != _LINUX_CAPABILITY_VERSION_3 && header.version != _LINUX_CAPABILITY_VERSION_2)
        return TAWC_EINVAL;
    if (header.pid && header.pid != tawc_getpid() &&
        !(header.pid == 1 && tawcroot_namespace.init_pid == tawc_getpid())) return TAWC_ESRCH;
    if (a->nr == __NR_capget)
        return tawc_copy_to_guest((void *)a->b, tawcroot_namespace.capabilities, sizeof tawcroot_namespace.capabilities);
    uint32_t next[6];
    if (tawc_copy_from_guest(next, sizeof next, (void *)a->b) < 0) return TAWC_EFAULT;
    for (int i = 0; i < 6; i += 3)
        if ((next[i] & ~next[i+1]) || (next[i+1] & ~tawcroot_namespace.capabilities[i+1]) ||
            (next[i+2] & ~(tawcroot_namespace.capabilities[i+2] | tawcroot_namespace.capabilities[i+1])))
            return TAWC_EPERM;
    memcpy(tawcroot_namespace.capabilities, next, sizeof next);
    return 0;
}

static int valid_extent(const char *text)
{
    uint64_t values[3];
    for (int i = 0; i < 3; ++i) {
        while (*text == ' ' || *text == '\t' || *text == '\n') ++text;
        if (*text < '0' || *text > '9') return 0;
        uint64_t value = 0;
        while (*text >= '0' && *text <= '9') {
            value = value * 10 + *text++ - '0';
            if (value > UINT32_MAX) return 0;
        }
        values[i] = value;
    }
    while (*text == ' ' || *text == '\t' || *text == '\n') ++text;
    return !*text && values[2] && values[0] + values[2] <= UINT32_MAX &&
        values[1] + values[2] <= UINT32_MAX;
}

static long namespace_write(const tawcroot_syscall_args *a, ucontext_t *uc)
{
    (void)uc;
    if (tawcroot_namespace.user) {
        struct stat st;
        if (!TAWC_RAW(TAWC_SYS_fstat, a->a, (long)&st, 0, 0, 0, 0))
            for (int i = 0; i < 3; ++i) {
                struct stat other;
                if (TAWC_RAW(TAWC_SYS_fstat, tawcroot_namespace.maps[i], (long)&other, 0, 0, 0, 0) ||
                    st.st_dev != other.st_dev || st.st_ino != other.st_ino) continue;
                char text[128];
                if (a->c <= 0 || a->c >= (long)sizeof text) return TAWC_EINVAL;
                if (tawc_copy_from_guest(text, (size_t)a->c, (void *)a->b) < 0) return TAWC_EFAULT;
                text[a->c] = 0;
                if (strlen(text) != (size_t)a->c) return TAWC_EINVAL;
                if (i < 2) {
                    if (other.st_size) return TAWC_EPERM;
                    if (!valid_extent(text)) return TAWC_EINVAL;
                } else if (!tawc_streq(text, "deny") && !tawc_streq(text, "deny\n")) return TAWC_EINVAL;
                long result = tawc_write((int)a->a, text, (size_t)a->c);
                if (result > 0) (void)tawc_fcntl(tawcroot_namespace.maps[i], 1033, 8);
                return result;
            }
    }
    return TAWC_RAW(TAWC_SYS_write, a->a, a->b, a->c, 0, 0, 0);
}

static long namespace_signal(const tawcroot_syscall_args *a, ucontext_t *uc)
{
    (void)uc;
    long pid = a->a;
    if (pid == 1 && tawcroot_namespace.init_pid) pid = tawcroot_namespace.init_pid;
    return TAWC_RAW(a->nr, pid, a->b, a->c, 0, 0, 0);
}

void tawcroot_namespace_register(void)
{
    tawcroot_dispatch_install(TAWC_SYS_clone, clone_namespace);
    tawcroot_dispatch_install(TAWC_SYS_unshare, unshare_namespace);
    tawcroot_dispatch_install(TAWC_SYS_getpid, namespace_pid);
    tawcroot_dispatch_install(TAWC_SYS_getppid, namespace_pid);
    tawcroot_dispatch_install(__NR_capget, namespace_cap);
    tawcroot_dispatch_install(__NR_capset, namespace_cap);
    tawcroot_dispatch_install(TAWC_SYS_write, namespace_write);
    tawcroot_dispatch_install(__NR_kill, namespace_signal);
    tawcroot_dispatch_install(__NR_tgkill, namespace_signal);
}
