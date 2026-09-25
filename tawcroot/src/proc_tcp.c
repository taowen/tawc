/* Endpoint snapshots for app-owned sockets when Android denies proc/net.
 * Ownership is never inferred from a port: lsof still joins kernel socket
 * inodes with real /proc/PID/fd links. No traffic is intercepted. */
#include <asm/unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include "raw_sys.h"
#include "path.h"
#include "identity.h"
#include "io.h"
#include "tawc_string.h"
#include "proc_tcp.h"

#define COUNT 2048
#define MAGIC 0x54435031
struct address { unsigned short family, port; unsigned char data[24]; };
struct endpoint {
    unsigned long inode;
    struct address local, peer;
    unsigned uid, state;
};
struct registry { unsigned magic, overflow; struct endpoint entries[COUNT]; unsigned char live[COUNT]; };
struct dent { unsigned long ino; long off; unsigned short size; unsigned char type; char name[]; };

static int enabled(void) {
    static int cached;
    int value = __atomic_load_n(&cached, __ATOMIC_RELAXED);
    if (value) return value > 0;
    long fd = tawc_openat(-100, "/proc/net/tcp", O_RDONLY | O_CLOEXEC, 0);
    if (fd >= 0) tawc_close(fd);
    value = fd == -13 || fd == -1 ? 1 : -1;
    __atomic_store_n(&cached, value, __ATOMIC_RELAXED);
    return value > 0;
}
static long registry_open(struct registry **out) {
    long fd = tawc_openat(tawcroot_rootfs_fd, ".tawcroot-tcp-v1",
                         O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return fd;
    long e = TAWC_RAW(__NR_flock, fd, 2, 0, 0, 0, 0);
    if (!e) e = TAWC_RAW(__NR_ftruncate, fd, sizeof(struct registry), 0, 0, 0, 0);
    if (e < 0) { tawc_close(fd); return e; }
    long p = tawc_mmap(0, sizeof(struct registry), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (p < 0 && p >= -4095) { tawc_close(fd); return p; }
    *out = (struct registry *)p;
    if ((*out)->magic && (*out)->magic != MAGIC) {
        tawc_munmap(*out, sizeof(**out)); tawc_close(fd); return -5;
    }
    (*out)->magic = MAGIC;
    return fd;
}
static void registry_close(int fd, struct registry *r) {
    tawc_munmap(r, sizeof(*r));
    tawc_close(fd);
}
/* Scan only descriptors the kernel permits us to inspect. This also covers
 * dup, fork, exec and SCM_RIGHTS without keeping another descriptor alive. */
static long live_entries(struct registry *r, unsigned char *live) {
    long proc = tawc_openat(-100, "/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (proc < 0) return proc;
    long mem = tawc_mmap(0, 8192, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem < 0 && mem >= -4095) { tawc_close(proc); return mem; }
    char *pbuf = (char *)mem, *fbuf = pbuf + 4096;
    char path[96], link[96];
    long n;
    while ((n = tawc_getdents64(proc, pbuf, 4096)) > 0) {
        for (long pos = 0; pos < n;) {
            struct dent *d = (struct dent *)(pbuf + pos);
            if (!d->size || pos + d->size > n) { n = -5; goto done; }
            pos += d->size;
            if (d->name[0] < '0' || d->name[0] > '9') continue;
            size_t len = 0;
            tawc_str_append(path, sizeof(path), &len, d->name);
            tawc_str_append(path, sizeof(path), &len, "/fd");
            long dir = tawc_openat(proc, path, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
            if (dir < 0) continue;
            long count;
            while ((count = tawc_getdents64(dir, fbuf, 4096)) > 0) {
                for (long off = 0; off < count;) {
                    struct dent *f = (struct dent *)(fbuf + off);
                    if (!f->size || off + f->size > count) break;
                    off += f->size;
                    long k = TAWC_RAW(__NR_readlinkat, dir, (long)f->name,
                                     (long)link, sizeof(link)-1, 0, 0);
                    if (k <= 9) continue;
                    link[k] = 0;
                    if (!tawc_starts_with(link, "socket:[")) continue;
                    unsigned long ino = 0;
                    const char *s = link + 8;
                    while (*s >= '0' && *s <= '9') ino = ino * 10 + *s++ - '0';
                    if (*s != ']') continue;
                    for (unsigned j = 0; j < COUNT; j++)
                        if (r->entries[j].inode == ino) live[j] = 1;
                }
            }
            tawc_close(dir);
        }
    }
done:
    tawc_munmap((void *)mem, 8192);
    tawc_close(proc);
    return n;
}
void tawcroot_tcp_record(int socket) {
    if (!enabled()) return;
    struct endpoint e = {0};
    unsigned len = sizeof(e.local);
    if (TAWC_RAW(__NR_getsockname, socket, (long)&e.local, (long)&len, 0, 0, 0) < 0 ||
        (e.local.family != 2 && e.local.family != 10)) return;
    unsigned char info[256] = {0};
    len = sizeof(info);
    if (TAWC_RAW(__NR_getsockopt, socket, 6, 11, (long)info, (long)&len, 0) < 0) return;
    len = sizeof(e.peer);
    if (TAWC_RAW(__NR_getpeername, socket, (long)&e.peer, (long)&len, 0, 0, 0) < 0)
        e.peer.family = e.local.family;
    struct stat st;
    if (TAWC_RAW(__NR_fstat, socket, (long)&st, 0, 0, 0, 0) < 0) return;
    e.inode = st.st_ino;
    e.uid = tawcroot_identity_euid();
    e.state = info[0];
    struct registry *r;
    long fd = registry_open(&r);
    if (fd < 0) return;
    int slot = -1;
    for (unsigned j = 0; j < COUNT; j++) {
        if (r->entries[j].inode == e.inode) { slot = j; break; }
        if (!r->entries[j].inode && slot < 0) slot = j;
    }
    if (slot < 0) {
        memset(r->live, 0, COUNT);
        if (live_entries(r, r->live) >= 0)
            for (unsigned j = 0; j < COUNT; j++)
                if (!r->live[j]) { slot = j; break; }
    }
    if (slot >= 0) r->entries[slot] = e;
    else r->overflow = 1;
    registry_close(fd, r);
}
static void hex(char *s, size_t *len, unsigned long n, unsigned digits) {
    const char *alphabet = "0123456789ABCDEF";
    for (unsigned i = digits; i; i--) s[(*len)++] = alphabet[(n >> ((i-1)*4)) & 15];
    s[*len] = 0;
}
static void address(char *s, size_t *len, struct address *a, int ipv6) {
    unsigned words[4] = {0};
    memcpy(words, a->data + (ipv6 ? 4 : 0), ipv6 ? 16 : 4);
    for (unsigned i = 0; i < (ipv6 ? 4u : 1u); i++) hex(s, len, words[i], 8);
    s[(*len)++] = ':';
    hex(s, len, ((a->port & 255) << 8) | (a->port >> 8), 4);
}
long tawcroot_tcp_open(int ipv6) {
    long real = tawc_openat(-100, ipv6 ? "/proc/net/tcp6" : "/proc/net/tcp", O_RDONLY | O_CLOEXEC, 0);
    if (real >= 0 || (real != -13 && real != -1)) return real;
    struct registry *r;
    long fd = registry_open(&r);
    if (fd < 0) return fd;
    memset(r->live, 0, COUNT);
    long result = live_entries(r, r->live);
    if (result < 0 || r->overflow) {
        registry_close(fd, r); return result < 0 ? result : -75;
    }
    long out = tawc_memfd_create("tawcroot-tcp", 1);
    if (out >= 0) {
        const char *header = ipv6
            ? "  sl  local_address remote_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
            : "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n";
        result = tawc_write(out, header, tawc_strlen(header));
        for (unsigned j = 0; j < COUNT && result >= 0; j++) {
            struct endpoint *e = &r->entries[j];
            if (!r->live[j]) { e->inode = 0; continue; }
            if (e->local.family != (ipv6 ? 10 : 2)) continue;
            char line[320]; size_t len = 0;
            tawc_str_append_dec(line, sizeof(line), &len, j);
            tawc_str_append(line, sizeof(line), &len, ": ");
            address(line, &len, &e->local, ipv6);
            tawc_str_append(line, sizeof(line), &len, " ");
            address(line, &len, &e->peer, ipv6);
            tawc_str_append(line, sizeof(line), &len, " ");
            hex(line, &len, e->state, 2);
            tawc_str_append(line, sizeof(line), &len, " 00000000:00000000 00:00000000 00000000 ");
            tawc_str_append_dec(line, sizeof(line), &len, e->uid);
            tawc_str_append(line, sizeof(line), &len, " 0 ");
            tawc_str_append_dec(line, sizeof(line), &len, e->inode);
            tawc_str_append(line, sizeof(line), &len, " 1 0000000000000000\n");
            result = tawc_write(out, line, len);
        }
        if (result < 0) { tawc_close(out); out = result; }
        else tawc_lseek(out, 0, 0);
    }
    registry_close(fd, r);
    return out;
}
