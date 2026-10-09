/* Private startup path views, not mount namespace isolation. */
#include <cleat/test.h>
#include <fcntl.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "hosted.h"
#include "errno_neg.h"
#include "sysnr.h"

static bool deny_reserve(long nr, const long args[6], long *result)
{
    if (nr != TAWC_SYS_fcntl || args[1] != F_DUPFD_CLOEXEC) return false;
    *result = TAWC_EMFILE;
    return true;
}

test(hosted_bind_view_reserve_failure_does_not_leak)
{
    th_view v;
    th_setup(&v, "bind-reserve");
    tawcroot_test_raw_hook = deny_reserve;
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/tmp", 0, MS_BIND, 0, 0), TAWC_EMFILE);
    tawcroot_test_raw_hook = NULL;
    th_teardown(&v);
}

test(hosted_bind_view_directory_and_chroot)
{
    th_view v;
    th_setup(&v, "bind-view");
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/tmp", 0, MS_BIND, 0, 0), 0);
    long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/tmp/probe", O_RDONLY, 0, 0, 0);
    test_true(fd >= 0);
    char data[32] = {0};
    test_true(read((int)fd, data, sizeof data - 1) > 0);
    test_str_eq(data, "from-rootfs\n");
    close((int)fd);
    test_int_eq(th_sys(TAWC_SYS_chroot, "/tmp", 0, 0, 0, 0, 0), 0);
    fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/probe", O_RDONLY, 0, 0, 0);
    test_true(fd >= 0);
    close((int)fd);
    th_teardown(&v);
}

test(hosted_bind_view_regular_file)
{
    th_view v;
    th_setup(&v, "bind-file");
    long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/tmp/target", O_CREAT | O_WRONLY, 0600, 0, 0);
    test_true(fd >= 0);
    close((int)fd);
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc/probe", "/tmp/target", 0, MS_BIND, 0, 0), 0);
    fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/tmp/target", O_RDONLY, 0, 0, 0);
    test_true(fd >= 0);
    char data[32] = {0};
    test_true(read((int)fd, data, sizeof data - 1) > 0);
    test_str_eq(data, "from-rootfs\n");
    close((int)fd);
    test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, "/tmp/target/child", O_RDONLY, 0, 0, 0), TAWC_ENOTDIR);
    test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/tmp/target", 0, 0, 0, 0), TAWC_EBUSY);
    th_teardown(&v);
}

test(hosted_bind_view_unix_socket)
{
    th_view v;
    th_setup(&v, "bind-socket");
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    test_true(server >= 0);
    struct sockaddr_un host = {.sun_family = AF_UNIX};
    test_true(strlen(v.root) + 17 < sizeof host.sun_path);
    strcpy(host.sun_path, v.root);
    strcat(host.sun_path, "/run/source.sock");
    test_int_eq(bind(server, (struct sockaddr *)&host, sizeof host), 0);
    test_int_eq(listen(server, 1), 0);
    long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/tmp/target", O_CREAT | O_WRONLY, 0600, 0, 0);
    test_true(fd >= 0);
    close((int)fd);
    test_int_eq(th_sys(TAWC_SYS_mount, "/run/source.sock", "/tmp/target", 0, MS_BIND, 0, 0), 0);
    int client = socket(AF_UNIX, SOCK_STREAM, 0);
    test_true(client >= 0);
    struct sockaddr_un guest = {.sun_family = AF_UNIX, .sun_path = "/tmp/target"};
    test_int_eq(th_sys(TAWC_SYS_connect, client, &guest, sizeof guest, 0, 0, 0), 0);
    int peer = accept(server, NULL, NULL);
    test_true(peer >= 0);
    test_int_eq(write(client, "ok", 2), 2);
    char data[3] = {0};
    test_int_eq(read(peer, data, 2), 2);
    test_str_eq(data, "ok");
    close(peer); close(client); close(server);
    th_teardown(&v);
}

test(hosted_bind_view_does_not_modify_parent_or_disk)
{
    th_view v;
    th_setup(&v, "bind-private");
    pid_t pid = fork();
    test_true(pid >= 0);
    if (!pid) {
        long r = th_sys(TAWC_SYS_mount, "/etc", "/tmp", 0, MS_BIND, 0, 0);
        _exit(r == 0 ? 0 : 1);
    }
    int status;
    test_int_eq(waitpid(pid, &status, 0), pid);
    test_int_eq(status, 0);
    test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, "/tmp/probe", O_RDONLY, 0, 0, 0), TAWC_ENOENT);
    th_teardown(&v);
}

test(hosted_bind_view_rejects_unsupported_and_missing_targets)
{
    th_view v;
    th_setup(&v, "bind-errors");
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/tmp", 0, MS_BIND | MS_REC, 0, 0), TAWC_EPERM);
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/missing", 0, MS_BIND, 0, 0), TAWC_ENOENT);
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/tmp", 0, MS_BIND, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_mount, "/usr", "/tmp", 0, MS_BIND, 0, 0), TAWC_EBUSY);
    th_teardown(&v);
}

test(hosted_bind_view_chroot_inside_an_existing_bind)
{
    th_view v;
    th_setup(&v, "bind-nested");
    th_add_bind(&v, "/tmp");
    test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/tmp/inner", 0700, 0, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/tmp/inner", 0, MS_BIND, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_chroot, "/tmp", 0, 0, 0, 0, 0), 0);
    long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/inner/probe", O_RDONLY, 0, 0, 0);
    test_true(fd >= 0);
    close((int)fd);
    th_teardown(&v);
}

test(hosted_bind_view_nested_aliases_survive_chroot)
{
    th_view v;
    th_setup(&v, "bind-aliases");
    th_add_bind(&v, "/run");
    test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/run/work", 0700, 0, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/tmp", AT_REMOVEDIR, 0, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_symlinkat, "/run/work", AT_FDCWD, "/tmp", 0, 0, 0), 0);
    const char *dirs[] = {"/tmp/root", "/tmp/root/etc", "/tmp/root/etc/sub", "/tmp/root/tmp"};
    for (unsigned i = 0; i < sizeof dirs / sizeof dirs[0]; ++i)
        test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, dirs[i], 0700, 0, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc/sub", "/tmp/root/etc/sub", 0, MS_BIND, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_mount, "/etc", "/tmp/root/etc", 0, MS_BIND, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_mount, "/tmp", "/tmp/root/tmp", 0, MS_BIND, 0, 0), 0);
    test_int_eq(th_sys(TAWC_SYS_chroot, "/tmp/root", 0, 0, 0, 0, 0), 0);
    long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe", O_RDONLY, 0, 0, 0);
    test_true(fd >= 0);
    close((int)fd);
    th_teardown(&v);
}
