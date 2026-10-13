#include <stddef.h>
#include <stdint.h>
#include <linux/input.h>
#include "gamepad.h"
#include "errno_neg.h"
#include "io.h"
#include "path.h"
#include "raw_sys.h"
#include "sysnr.h"
#include "tawc_string.h"
#include "tawc_uapi.h"
#include "usercopy.h"

#define MAGIC 0x41525047u
#define AF_UNIX 1
#define SOCK_STREAM 1
#define SOCK_CLOEXEC 02000000
#define MSG_NOSIGNAL 0x4000
#define MSG_WAITALL 0x100
struct address { unsigned short family; char path[108]; };
struct snapshot { uint32_t magic, connected, buttons; int32_t axes[8]; };
static unsigned int sequence;
static const int keys[] = { BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR,
    BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR };
static const int axes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y };

static long connect_broker(int slot, int query)
{
    long fd = TAWC_RAW(TAWC_SYS_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, 0, 0, 0);
    if (fd < 0) return fd;
    struct address addr = { .family = AF_UNIX };
    size_t n = 1;
    tawc_str_append(addr.path, sizeof(addr.path), &n, "arlinux-pad-");
    tawc_str_append_dec(addr.path, sizeof(addr.path), &n, slot);
    tawc_str_append(addr.path, sizeof(addr.path), &n, "-");
    tawc_str_append_dec(addr.path, sizeof(addr.path), &n, TAWC_RAW(TAWC_SYS_getpid, 0, 0, 0, 0, 0, 0));
    tawc_str_append(addr.path, sizeof(addr.path), &n, "-");
    tawc_str_append_dec(addr.path, sizeof(addr.path), &n, __atomic_add_fetch(&sequence, 1, __ATOMIC_RELAXED));
    long rv = TAWC_RAW(TAWC_SYS_bind, fd, (long)&addr, sizeof(addr), 0, 0, 0);
    if (rv < 0) { tawc_close((int)fd); return rv; }
    memset(&addr, 0, sizeof(addr)); addr.family = AF_UNIX; n = 1;
    tawc_str_append(addr.path, sizeof(addr.path), &n, "arlinux-gamepad-");
    tawc_str_append_dec(addr.path, sizeof(addr.path), &n, TAWC_RAW(TAWC_SYS_getuid, 0, 0, 0, 0, 0, 0));
    tawc_str_append(addr.path, sizeof(addr.path), &n, "-0");
    rv = TAWC_RAW(TAWC_SYS_connect, fd, (long)&addr, sizeof(addr), 0, 0, 0);
    if (rv < 0) { tawc_close((int)fd); return -19; }
    uint32_t request[] = { MAGIC, (uint32_t)slot, (uint32_t)query };
    rv = TAWC_RAW(TAWC_SYS_sendto, fd, (long)request, sizeof(request), MSG_NOSIGNAL, 0, 0);
    if (rv != sizeof(request)) { tawc_close((int)fd); return TAWC_EIO; }
    return fd;
}

static long snapshot(int slot, struct snapshot *out)
{
    long fd = connect_broker(slot, 1);
    if (fd < 0) return fd;
    // Bound control requests so a stopped host cannot block a SIGSYS handler.
    struct { long seconds, microseconds; } timeout = { 1, 0 };
    TAWC_RAW(TAWC_SYS_setsockopt, fd, 1, 20, (long)&timeout, sizeof(timeout), 0);
    long rv = TAWC_RAW(TAWC_SYS_recvfrom, fd, (long)out, sizeof(*out), MSG_WAITALL, 0, 0);
    tawc_close((int)fd);
    if (rv != sizeof(*out) || out->magic != MAGIC) return TAWC_EIO;
    return out->connected ? 0 : -19;
}

int tawcroot_gamepad_path_slot(int dirfd, const char *path)
{
    int enabled = 0;
    for (size_t i = 0; i < tawcroot_n_binds; i++) {
        const struct tawcroot_bind *bind = &tawcroot_binds[i];
        if (bind->active && tawc_streq(bind->dst, "dev/input") && bind->src_len >= 14
                && tawc_streq(bind->src + bind->src_len - 14, "/runtime/input")) { enabled = 1; break; }
    }
    if (!enabled) return -1;
    const char *name = path;
    if (tawc_starts_with(path, "/dev/input/")) name += 11;
    else if (path[0] == '/' || dirfd == AT_FDCWD) return -1;
    if (!tawc_starts_with(name, "event") || name[5] < '0' || name[5] > '3' || name[6]) return -1;
    if (name == path) {
        char link[64], target[256]; size_t n = 0;
        tawc_str_append(link, sizeof(link), &n, "/proc/self/fd/");
        tawc_str_append_dec(link, sizeof(link), &n, dirfd);
        long length = TAWC_RAW(TAWC_SYS_readlinkat, AT_FDCWD, (long)link, (long)target, sizeof(target) - 1, 0, 0);
        if (length < 14) return -1;
        target[length] = 0;
        if (!tawc_streq(target + length - 14, "/runtime/input")) return -1;
    }
    return name[5] - '0';
}

int tawcroot_gamepad_fd_slot(int fd)
{
    struct address addr = {0}; unsigned int length = sizeof(addr);
    long rv = TAWC_RAW(TAWC_SYS_getsockname, fd, (long)&addr, (long)&length, 0, 0, 0);
    if (rv || addr.family != AF_UNIX || addr.path[0] || !tawc_starts_with(addr.path + 1, "arlinux-pad-")) return -1;
    int slot = addr.path[13] - '0';
    return slot >= 0 && slot < 4 && addr.path[14] == '-' ? slot : -1;
}

long tawcroot_gamepad_open(int dirfd, const char *path, int flags)
{
    int slot = tawcroot_gamepad_path_slot(dirfd, path);
    if (slot < 0) return TAWC_ENOSYS;
    // Only virtual nodes in the explicit input overlay have a running broker.
    struct snapshot state;
    long rv = snapshot(slot, &state);
    if (rv < 0) return rv;
    long fd = connect_broker(slot, 0);
    if (fd < 0) return fd;
    long fdflags = (flags & O_CLOEXEC) ? FD_CLOEXEC : 0;
    TAWC_RAW(TAWC_SYS_fcntl, fd, F_SETFD, fdflags, 0, 0, 0);
    if (flags & O_NONBLOCK) TAWC_RAW(TAWC_SYS_fcntl, fd, F_SETFL, O_NONBLOCK, 0, 0, 0);
    return fd;
}

void tawcroot_gamepad_stat(struct stat *st, int slot)
{
    st->st_mode = S_IFCHR | 0600;
    // Keep udev from classifying this fd using Android's physical event0.
    st->st_rdev = TAWC_MKDEV(13, 192 + slot);
    st->st_ino = 0x41520000u + slot;
}

static void setbit(unsigned char *bits, unsigned int bit) { bits[bit / 8] |= 1u << (bit % 8); }

long tawcroot_gamepad_ioctl(int fd, unsigned int command, long argument)
{
    if (_IOC_TYPE(command) != 'E') return TAWC_ENOSYS;
    int slot = tawcroot_gamepad_fd_slot(fd);
    if (slot < 0) return TAWC_ENOSYS;
    struct snapshot state;
    long rv = snapshot(slot, &state);
    if (rv < 0) return rv;
    unsigned int nr = _IOC_NR(command), size = _IOC_SIZE(command);
    unsigned char data[256] = {0};
    unsigned int count = 0;
    if (command == EVIOCGVERSION) { int version = 0x10001; memcpy(data, &version, 4); count = 4; }
    else if (command == EVIOCGID) {
        struct input_id id = { BUS_USB, 0x045e, 0x028e, 0x0114 };
        memcpy(data, &id, sizeof(id)); count = sizeof(id);
    } else if (nr == 0x06 || nr == 0x07 || nr == 0x08) {
        const char *name = nr == 6 ? "ARLinux Xbox 360 Controller" : nr == 7 ? "arlinux/gamepad" : "arlinux";
        count = (unsigned int)tawc_strlen(name) + 1; memcpy(data, name, count);
        if (count > size) count = size;
    } else if (nr >= 0x20 && nr <= 0x3f) {
        unsigned int type = nr - 0x20;
        if (!type) { setbit(data, EV_SYN); setbit(data, EV_KEY); setbit(data, EV_ABS); }
        else if (type == EV_KEY) for (int i = 0; i < 11; i++) setbit(data, keys[i]);
        else if (type == EV_ABS) for (int i = 0; i < 8; i++) setbit(data, axes[i]);
        count = size < sizeof(data) ? size : sizeof(data);
    } else if (nr == 0x18) {
        for (int i = 0; i < 11; i++) if (state.buttons & (1u << i)) setbit(data, keys[i]);
        count = size < sizeof(data) ? size : sizeof(data);
    } else if (nr >= 0x40 && nr < 0x80) {
        int axis = (int)nr - 0x40, index;
        for (index = 0; index < 8 && axes[index] != axis; index++) {}
        if (index == 8) return TAWC_EINVAL;
        struct input_absinfo info = { .value = state.axes[index], .maximum = index >= 6 ? 1 : 32767,
            .minimum = index == 2 || index == 5 ? 0 : index >= 6 ? -1 : -32767 };
        memcpy(data, &info, sizeof(info)); count = sizeof(info);
    } else if (nr == 0x0a || nr == 0x19 || nr == 0x1a || nr == 0x1b) {
        count = size < sizeof(data) ? size : sizeof(data);
    } else if (command == EVIOCGRAB || command == EVIOCSCLOCKID) return 0;
    else return -25;
    rv = tawc_copy_to_guest((void *)(uintptr_t)argument, data, count);
    return rv < 0 ? rv : (nr == 6 || nr == 7 || nr == 8 || (nr >= 0x20 && nr <= 0x3f) || nr == 0x18 ? (long)count : 0);
}
