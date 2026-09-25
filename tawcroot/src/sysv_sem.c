/* App-private System V semaphores. Raw syscalls only: this runs in SIGSYS.
 * Each operation opens its own file description, so flock also serializes
 * forked processes. Shared state and undo records survive guest exec. */
#include <asm/unistd.h>
#include <linux/sem.h>
#include <asm/sembuf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include "dispatch.h"
#include "identity.h"
#include "path.h"
#include "raw_sys.h"
#include "tawc_string.h"
#include "usercopy.h"

#define SETS 128
#define SEMS 32
#define OWNERS 32
#define MAGIC 0x54535731
struct undo { long pid; unsigned long birth; int value[SEMS]; };
struct set {
    int id, used, key, count;
    struct semid64_ds ds;
    int value[SEMS], pid[SEMS], negative[SEMS], zero[SEMS];
    struct undo undo[OWNERS];
};
struct table { unsigned magic, next, sequence; struct set sets[SETS]; };
struct mapping { int fd; struct table *table; };
struct stamp { long sec, nsec; };

static long lock(int fd, int op) {
    return TAWC_RAW(__NR_flock, fd, op, 0, 0, 0, 0);
}
static void close_table(struct mapping *m) {
    lock(m->fd, 8);
    tawc_munmap(m->table, sizeof(*m->table));
    tawc_close(m->fd);
}
static long open_table(struct mapping *m) {
    long fd = tawc_openat(tawcroot_rootfs_fd, ".tawcroot-sem-v1",
                         O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return fd;
    long result = lock(fd, 2);
    if (!result) result = TAWC_RAW(__NR_ftruncate, fd, sizeof(struct table), 0, 0, 0, 0);
    if (result < 0) { tawc_close(fd); return result; }
    long address = tawc_mmap(0, sizeof(struct table), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (address < 0 && address >= -4095) { tawc_close(fd); return address; }
    m->fd = fd;
    m->table = (struct table *)address;
    if (m->table->magic && m->table->magic != MAGIC) { close_table(m); return -5; }
    if (!m->table->magic) {
        memset(m->table, 0, sizeof(*m->table));
        m->table->magic = MAGIC;
    }
    return 0;
}
static long now(int clock, struct stamp *out) {
    return TAWC_RAW(__NR_clock_gettime, clock, (long)out, 0, 0, 0, 0);
}
static void changed(struct mapping *m) {
    ++m->table->sequence;
    TAWC_RAW(__NR_futex, (long)&m->table->sequence, 1, 0x7fffffff, 0, 0, 0);
}
/* A zombie has already exited for SEM_UNDO. starttime prevents PID reuse. */
static unsigned long birth(long pid) {
    char path[64] = "/proc/", digits[24], buffer[512];
    unsigned n = 0, p = 6;
    do { digits[n++] = '0' + pid % 10; pid /= 10; } while (pid);
    while (n) path[p++] = digits[--n];
    memcpy(path + p, "/stat", 6);
    long fd = tawc_openat(AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return fd == -2 || fd == -3 ? 0 : ~0ul;
    long size = tawc_read(fd, buffer, sizeof buffer - 1);
    tawc_close(fd);
    if (size <= 0) return ~0ul;
    buffer[size] = 0;
    char *end = 0;
    for (long i = 0; i < size; ++i) if (buffer[i] == ')') end = buffer + i;
    if (!end || end[2] == 'Z' || end[2] == 'X') return 0;
    char *field = end + 2;
    for (int i = 3; i < 22; ++i) {
        while (*field && *field != ' ') ++field;
        while (*field == ' ') ++field;
    }
    unsigned long value = 0;
    while (*field >= '0' && *field <= '9') value = value * 10 + *field++ - '0';
    return value;
}
static void reap(struct mapping *m, struct set *s) {
    for (int i = 0; i < OWNERS; ++i) {
        struct undo *u = &s->undo[i];
        if (!u->pid) continue;
        unsigned long current = birth(u->pid);
        if (current == ~0ul || current == u->birth) continue;
        for (int j = 0; j < s->count; ++j) {
            long value = (long)s->value[j] + u->value[j];
            s->value[j] = value < 0 ? 0 : value > 32767 ? 32767 : value;
        }
        memset(u, 0, sizeof(*u));
        changed(m);
    }
}
static struct set *lookup(struct mapping *m, int id) {
    if (id < 0) return 0;
    struct set *s = &m->table->sets[id % SETS];
    return s->used && s->id == id ? s : 0;
}
static long access_set(struct set *s, int write) {
    tawc_identity identity;
    tawcroot_identity_get(&identity);
    if (!identity.euid) return 0;
    unsigned mode = s->ds.sem_perm.mode;
    if (identity.euid == s->ds.sem_perm.uid || identity.euid == s->ds.sem_perm.cuid) mode >>= 6;
    else if (identity.egid == s->ds.sem_perm.gid || identity.egid == s->ds.sem_perm.cgid) mode >>= 3;
    return (mode & (write ? 2 : 4)) ? 0 : -13;
}
static long semget_handler(const tawcroot_syscall_args *args, ucontext_t *uc) {
    (void)uc;
    int key = args->a, count = args->b, flags = args->c;
    if (count < 0 || count > SEMS) return -22;
    struct mapping m;
    long result = open_table(&m);
    if (result) return result;
    result = -2;
    for (int i = 0; key && i < SETS; ++i) {
        struct set *s = &m.table->sets[i];
        if (!s->used || s->key != key) continue;
        result = (flags & IPC_CREAT) && (flags & IPC_EXCL) ? -17 : count > s->count ? -22 : 0;
        if (!result && (flags & 0444)) result = access_set(s, 0);
        if (!result && (flags & 0222)) result = access_set(s, 1);
        if (!result) result = s->id;
        goto done;
    }
    if (key && !(flags & IPC_CREAT)) goto done;
    if (!count) { result = -22; goto done; }
    result = -28;
    for (int i = 0; i < SETS; ++i) {
        struct set *s = &m.table->sets[i];
        if (s->used) continue;
        memset(s, 0, sizeof(*s));
        s->used = 1; s->key = key; s->count = count;
        s->id = (int)((++m.table->next & 0x00ffffffu) * SETS + i);
        tawc_identity identity; tawcroot_identity_get(&identity);
        s->ds.sem_perm.uid = s->ds.sem_perm.cuid = identity.euid;
        s->ds.sem_perm.gid = s->ds.sem_perm.cgid = identity.egid;
        s->ds.sem_perm.mode = flags & 0777;
        s->ds.sem_perm.key = key;
        s->ds.sem_nsems = count;
        struct stamp time; now(0, &time); s->ds.sem_ctime = time.sec;
        result = s->id;
        break;
    }
done:
    close_table(&m); return result;
}
static long semctl_handler(const tawcroot_syscall_args *args, ucontext_t *uc) {
    (void)uc;
    int command = args->c & ~0x100, index = args->b;
    struct mapping m;
    long result = open_table(&m);
    if (result) return result;
    struct set *s = lookup(&m, args->a);
    if (!s) { result = -22; goto done; }
    reap(&m, s);
    result = command == IPC_RMID || command == IPC_SET ? 0 : access_set(s, command == SETVAL || command == SETALL);
    if (result) goto done;
    if (command == IPC_RMID || command == IPC_SET) {
        unsigned uid = tawcroot_identity_euid();
        if (uid && uid != s->ds.sem_perm.uid && uid != s->ds.sem_perm.cuid) { result = -1; goto done; }
    }
    if (command == IPC_RMID) { s->used = 0; changed(&m); goto done; }
    if (command == IPC_STAT) { result = tawc_copy_to_guest((void *)args->d, &s->ds, sizeof(s->ds)); goto done; }
    if (command == IPC_SET) {
        struct semid64_ds ds;
        result = tawc_copy_from_guest(&ds, sizeof(ds), (void *)args->d);
        if (!result) {
            s->ds.sem_perm.uid = ds.sem_perm.uid; s->ds.sem_perm.gid = ds.sem_perm.gid;
            s->ds.sem_perm.mode = ds.sem_perm.mode & 0777;
            struct stamp time; now(0, &time); s->ds.sem_ctime = time.sec;
        }
        goto done;
    }
    unsigned short values[SEMS];
    if (command == GETALL) {
        for (int i = 0; i < s->count; ++i) values[i] = s->value[i];
        result = tawc_copy_to_guest((void *)args->d, values, s->count * sizeof(*values)); goto done;
    }
    if (command == SETALL) {
        result = tawc_copy_from_guest(values, s->count * sizeof(*values), (void *)args->d);
        if (result) goto done;
        for (int i = 0; i < s->count; ++i) if (values[i] > 32767) { result = -34; goto done; }
        for (int i = 0; i < s->count; ++i) s->value[i] = values[i];
        for (int i = 0; i < s->count; ++i) s->pid[i] = tawc_getpid();
        { struct stamp time; now(0, &time); s->ds.sem_ctime = time.sec; }
        memset(s->undo, 0, sizeof(s->undo)); changed(&m); goto done;
    }
    if (index < 0 || index >= s->count) { result = -22; goto done; }
    switch (command) {
    case GETVAL: result = s->value[index]; break;
    case GETPID: result = s->pid[index]; break;
    case GETNCNT: result = s->negative[index]; break;
    case GETZCNT: result = s->zero[index]; break;
    case SETVAL:
        if ((int)args->d < 0 || args->d > 32767) { result = -34; break; }
        s->value[index] = args->d; s->pid[index] = tawc_getpid();
        for (int i = 0; i < OWNERS; ++i) s->undo[i].value[index] = 0;
        { struct stamp time; now(0, &time); s->ds.sem_ctime = time.sec; }
        changed(&m); break;
    default: result = -22;
    }
done:
    close_table(&m); return result;
}
static long operate(const tawcroot_syscall_args *args, int timed, ucontext_t *uc) {
    unsigned count = args->c;
    if (!count) return -22;
    if (args->c > 64) return -7;
    struct sembuf ops[64];
    long result = tawc_copy_from_guest(ops, count * sizeof(*ops), (void *)args->b);
    if (result) return result;
    struct stamp deadline = {0}, time;
    if (timed && args->d) {
        result = tawc_copy_from_guest(&deadline, sizeof(deadline), (void *)args->d);
        if (result) return result;
        if (deadline.sec < 0 || deadline.nsec < 0 || deadline.nsec >= 1000000000) return -22;
        now(1, &time);
        if (deadline.sec > 0x7fffffffffffffffL - time.sec - 1) deadline.sec = 0x7fffffffffffffffL - time.sec - 1;
        deadline.sec += time.sec; deadline.nsec += time.nsec;
        if (deadline.nsec >= 1000000000) { ++deadline.sec; deadline.nsec -= 1000000000; }
    }
    struct mapping m;
    result = open_table(&m);
    if (result) return result;
    int waited = 0;
    for (;;) {
        if (waited) {
            unsigned long pending = 0, original_mask = 0;
            if (uc) memcpy(&original_mask, &uc->uc_sigmask, sizeof(original_mask));
            TAWC_RAW(__NR_rt_sigpending, (long)&pending, sizeof(pending), 0, 0, 0, 0);
            pending &= ~original_mask;
            for (int signal = 1; signal <= 64; ++signal) {
                if (!(pending & (1ul << (signal - 1)))) continue;
                unsigned long action[4] = {0};
                TAWC_RAW(__NR_rt_sigaction, signal, 0, (long)action, 8, 0, 0);
                if (action[0] == 1 || (!action[0] && (signal == 17 || signal == 18 || signal == 23 || signal == 28)))
                    pending &= ~(1ul << (signal - 1));
            }
            if (pending) { result = -4; break; }
        }
        struct set *s = lookup(&m, args->a);
        if (!s) { result = waited ? -43 : -22; break; }
        reap(&m, s);
        int values[SEMS], adjustment[SEMS] = {0}, blocked = -1, want_undo = 0, write = 0;
        memcpy(values, s->value, sizeof(values));
        for (unsigned i = 0; i < count; ++i) {
            unsigned index = ops[i].sem_num;
            if (index >= (unsigned)s->count) { result = -27; goto done; }
            if (ops[i].sem_flg & ~(IPC_NOWAIT | SEM_UNDO)) { result = -22; goto done; }
            if (ops[i].sem_op) write = 1;
        }
        result = access_set(s, write);
        if (result) break;
        for (unsigned i = 0; i < count; ++i) {
            unsigned index = ops[i].sem_num;
            int value = values[index] + ops[i].sem_op;
            if ((!ops[i].sem_op && values[index]) || value < 0) { blocked = i; break; }
            if (value > 32767) { result = -34; goto done; }
            values[index] = value;
            if (ops[i].sem_flg & SEM_UNDO) { want_undo = 1; adjustment[index] -= ops[i].sem_op; }
        }
        result = access_set(s, write);
        if (result) break;
        if (blocked < 0) {
            struct undo *undo = 0;
            if (want_undo) {
                long pid = tawc_getpid(); unsigned long start = birth(pid);
                if (!start || start == ~0ul) { result = -13; break; }
                for (int i = 0; i < OWNERS; ++i) if (s->undo[i].pid == pid && s->undo[i].birth == start) undo = &s->undo[i];
                if (!undo) for (int i = 0; i < OWNERS; ++i) if (!s->undo[i].pid) { undo = &s->undo[i]; break; }
                if (!undo) { result = -28; break; }
                for (int i = 0; i < s->count; ++i) {
                    long total = (long)undo->value[i] + adjustment[i];
                    if (total < -32767 || total > 32767) { result = -34; goto done; }
                }
                undo->pid = pid; undo->birth = start;
                for (int i = 0; i < s->count; ++i) undo->value[i] += adjustment[i];
            }
            memcpy(s->value, values, sizeof(values));
            for (unsigned i = 0; i < count; ++i) s->pid[ops[i].sem_num] = tawc_getpid();
            now(0, &time); s->ds.sem_otime = time.sec;
            changed(&m); result = 0; break;
        }
        if (ops[blocked].sem_flg & IPC_NOWAIT) { result = -11; break; }
        struct stamp delay = {0, 50000000};
        if (timed && args->d) {
            now(1, &time);
            if (deadline.sec < time.sec || (deadline.sec == time.sec && deadline.nsec <= time.nsec)) { result = -11; break; }
            if (deadline.sec - time.sec <= 1) {
                long remaining = (deadline.sec - time.sec) * 1000000000 + deadline.nsec - time.nsec;
                if (remaining < delay.nsec) delay.nsec = remaining;
            }
        }
        int *waiters = ops[blocked].sem_op ? &s->negative[ops[blocked].sem_num] : &s->zero[ops[blocked].sem_num];
        ++*waiters;
        unsigned sequence = m.table->sequence;
        lock(m.fd, 8);
        long wait = TAWC_RAW(__NR_futex, (long)&m.table->sequence, 0, sequence, (long)&delay, 0, 0);
        long locked;
        do { locked = lock(m.fd, 2); } while (locked == -4);
        if (locked < 0) { result = locked; break; }
        if (lookup(&m, args->a)) --*waiters;
        waited = 1;
        if (wait == -4) { result = wait; break; }
    }
done:
    close_table(&m); return result;
}
static long semop_handler(const tawcroot_syscall_args *args, ucontext_t *uc) { return operate(args, 0, uc); }
static long semtimedop_handler(const tawcroot_syscall_args *args, ucontext_t *uc) { return operate(args, 1, uc); }
void tawcroot_sysv_sem_register(void) {
    tawcroot_dispatch_install(__NR_semget, semget_handler);
    tawcroot_dispatch_install(__NR_semctl, semctl_handler);
    tawcroot_dispatch_install(__NR_semop, semop_handler);
    tawcroot_dispatch_install(__NR_semtimedop, semtimedop_handler);
}
