/* Hosted tests for /dev/shm migrate-on-narrower-reopen (shm.h).
 *
 * The host allows open() of /proc/self/fd/<memfd>; Android SELinux
 * doesn't. deny_memfd_reopen reproduces the device: any openat whose
 * target reads back as a /memfd: magic link fails EACCES, so the
 * handler takes the migration path it takes on the phone. */

#include <cleat/test.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "hosted.h"

#include "errno_neg.h"
#include "path.h"
#include "shm.h"
#include "sysnr.h"

static int denied;
static bool deny_memfd_reopen(long nr, const long args[6], long *ret)
{
	if (nr != TAWC_SYS_openat) return false;
	char link[64];
	ssize_t n = readlinkat((int)args[0], (const char *)args[1], link,
			       sizeof link);
	if (n < 7 || memcmp(link, "/memfd:", 7) != 0) return false;
	denied++;
	*ret = TAWC_EACCES;
	return true;
}

static int is_memfd(int fd)
{
	char p[64], link[64];
	snprintf(p, sizeof p, "/proc/self/fd/%d", fd);
	ssize_t n = readlink(p, link, sizeof link);
	return n >= 7 && memcmp(link, "/memfd:", 7) == 0;
}

/* Guest view of the table's internal fd for `name`. */
static int internal_fd(const char *name)
{
	char names[TAWCROOT_SHM_MAX][TAWCROOT_SHM_NAME_MAX + 1];
	int fds[TAWCROOT_SHM_MAX];
	size_t n = tawcroot_shm_export_all(names, fds, TAWCROOT_SHM_MAX);
	for (size_t i = 0; i < n; i++)
		if (strcmp(names[i], name) == 0) return fds[i];
	return -1;
}

test(hosted_shm_ro_reopen_migrates_to_file)
{
	th_view v;
	th_setup(&v, "shm-mig");
	tawcroot_test_raw_hook = deny_memfd_reopen;

	long a = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/seg",
			O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600, 0, 0);
	test_true(a >= 0);
	test_true(is_memfd((int)a));
	test_int_eq(write((int)a, "hello", 5), 5);

	long b = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/seg",
			O_RDONLY | O_CLOEXEC, 0, 0, 0);
	test_true(b >= 0);
	test_true(denied > 0);
	test_int_eq(fcntl((int)b, F_GETFL) & O_ACCMODE, O_RDONLY);
	test_int_eq(write((int)b, "x", 1), -1);

	/* Guest fd swapped in place: same number, now the file, contents
	 * + offset + CLOEXEC kept. */
	test_false(is_memfd((int)a));
	test_int_eq(lseek((int)a, 0, SEEK_CUR), 5);
	test_true(fcntl((int)a, F_GETFD) & FD_CLOEXEC);
	char buf[8] = { 0 };
	test_int_eq(pread((int)b, buf, 5, 0), 5);
	test_str_eq(buf, "hello");
	test_int_eq(pwrite((int)a, "HE", 2, 0), 2);
	test_int_eq(pread((int)b, buf, 5, 0), 5);
	test_str_eq(buf, "HEllo");

	/* Internal fd swapped too, and still inheritable. */
	int in = internal_fd("seg");
	test_true(in >= 0);
	test_false(is_memfd(in));
	test_int_eq(fcntl(in, F_GETFD) & FD_CLOEXEC, 0);

	/* Later opens reopen the file directly — own descriptions. */
	denied = 0;
	long c = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/seg",
			O_RDONLY, 0, 0, 0);
	test_true(c >= 0);
	test_int_eq(denied, 0);
	test_int_eq(fcntl((int)c, F_GETFL) & O_ACCMODE, O_RDONLY);
	test_int_eq(lseek((int)c, 0, SEEK_CUR), 0);

	/* Seal emulation on the migrated file. */
	test_int_eq(th_sys(TAWC_SYS_fcntl, b, F_GET_SEALS, 0, 0, 0, 0), 0);
	test_int_eq(th_sys(TAWC_SYS_fcntl, a, F_ADD_SEALS,
			   F_SEAL_SHRINK | F_SEAL_GROW, 0, 0, 0), 0);
	test_int_eq(th_sys(TAWC_SYS_fcntl, b, F_GET_SEALS, 0, 0, 0, 0),
		    (F_SEAL_SHRINK | F_SEAL_GROW));
	test_int_eq(th_sys(TAWC_SYS_fcntl, b, F_ADD_SEALS, F_SEAL_WRITE,
			   0, 0, 0), TAWC_EPERM);
	test_int_eq(th_sys(TAWC_SYS_fcntl, a, F_ADD_SEALS, 0x1000, 0, 0, 0),
		    TAWC_EINVAL);
	test_int_eq(th_sys(TAWC_SYS_fcntl, a, F_ADD_SEALS, F_SEAL_SEAL,
			   0, 0, 0), 0);
	test_int_eq(th_sys(TAWC_SYS_fcntl, a, F_ADD_SEALS, F_SEAL_WRITE,
			   0, 0, 0), TAWC_EPERM);

	test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/dev/shm/seg",
			   0, 0, 0, 0), 0);
	test_int_eq(close((int)a), 0);
	test_int_eq(close((int)b), 0);
	test_int_eq(close((int)c), 0);
	th_teardown(&v);
}

/* Each safety check keeps today's dup fallback (O_RDWR description,
 * segment stays a memfd). */
static void check_fallback(TestCtx *test_ctx, int a)
{
	long b = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/fb",
			O_RDONLY, 0, 0, 0);
	test_true(b >= 0);
	test_int_eq(fcntl((int)b, F_GETFL) & O_ACCMODE, O_RDWR);
	test_true(is_memfd((int)b));
	if (a >= 0) test_true(is_memfd(a));
	test_int_eq(close((int)b), 0);
	test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/dev/shm/fb",
			   0, 0, 0, 0), 0);
}

static long create_fb(TestCtx *test_ctx)
{
	long a = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/fb",
			O_RDWR | O_CREAT | O_EXCL, 0600, 0, 0);
	test_true(a >= 0);
	return a;
}

test(hosted_shm_ro_reopen_fallback_when_mapped)
{
	th_view v;
	th_setup(&v, "shm-fb-map");
	tawcroot_test_raw_hook = deny_memfd_reopen;
	long a = create_fb(test_ctx);
	test_int_eq(ftruncate((int)a, 4096), 0);
	void *m = mmap(NULL, 4096, PROT_READ, MAP_SHARED, (int)a, 0);
	test_true(m != MAP_FAILED);
	check_fallback(test_ctx, (int)a);
	test_int_eq(munmap(m, 4096), 0);
	test_int_eq(close((int)a), 0);
	th_teardown(&v);
}

test(hosted_shm_ro_reopen_fallback_when_duped)
{
	th_view v;
	th_setup(&v, "shm-fb-dup");
	tawcroot_test_raw_hook = deny_memfd_reopen;
	long a = create_fb(test_ctx);
	int d = dup((int)a);
	test_true(d >= 0);
	check_fallback(test_ctx, (int)a);
	test_true(is_memfd(d));
	test_int_eq(close(d), 0);
	test_int_eq(close((int)a), 0);
	th_teardown(&v);
}

test(hosted_shm_ro_reopen_fallback_when_guest_fd_closed)
{
	th_view v;
	th_setup(&v, "shm-fb-close");
	tawcroot_test_raw_hook = deny_memfd_reopen;
	long a = create_fb(test_ctx);
	test_int_eq(close((int)a), 0);
	check_fallback(test_ctx, -1);
	th_teardown(&v);
}

test(hosted_shm_ro_reopen_fallback_when_guest_fd_reused)
{
	th_view v;
	th_setup(&v, "shm-fb-reuse");
	tawcroot_test_raw_hook = deny_memfd_reopen;
	long a = create_fb(test_ctx);
	test_int_eq(close((int)a), 0);
	/* Same number, different object. */
	int o = open("/dev/null", O_RDONLY);
	test_int_eq(o, (int)a);
	check_fallback(test_ctx, -1);
	test_int_eq(close(o), 0);
	th_teardown(&v);
}

test(hosted_shm_migrated_segment_survives_reregister)
{
	th_view v;
	th_setup(&v, "shm-ferry");
	tawcroot_test_raw_hook = deny_memfd_reopen;
	long a = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/fe",
			O_RDWR | O_CREAT | O_EXCL, 0600, 0, 0);
	test_true(a >= 0);
	test_int_eq(write((int)a, "abc", 3), 3);
	long b = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/fe",
			O_RDONLY, 0, 0, 0);
	test_true(b >= 0);
	test_int_eq(close((int)b), 0);

	/* What --exec-child does with the exec_state ferry. */
	int in = internal_fd("fe");
	test_true(in >= 0);
	tawcroot_shm_reset();
	test_int_eq(tawcroot_shm_register("fe", in), 0);

	b = th_sys(TAWC_SYS_openat, AT_FDCWD, "/dev/shm/fe", O_RDONLY,
		   0, 0, 0);
	test_true(b >= 0);
	test_int_eq(fcntl((int)b, F_GETFL) & O_ACCMODE, O_RDONLY);
	char buf[4] = { 0 };
	test_int_eq(pread((int)b, buf, 3, 0), 3);
	test_str_eq(buf, "abc");
	struct stat st;
	test_int_eq(th_sys(TAWC_SYS_fstatat, AT_FDCWD, "/dev/shm/fe", &st,
			   0, 0, 0), 0);
	test_int_eq(st.st_size, 3);

	test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/dev/shm/fe",
			   0, 0, 0, 0), 0);
	test_int_eq(close((int)a), 0);
	test_int_eq(close((int)b), 0);
	th_teardown(&v);
}

/* Guest memfds reopened via /proc/self/fd/<n> (Firefox's HaveMemfd
 * probe and DupReadOnly). Needs /proc in the view. */
test(hosted_proc_fd_ro_reopen_of_guest_memfd_migrates)
{
	th_view v;
	th_setup(&v, "shm-procfd");
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);
	tawcroot_test_raw_hook = deny_memfd_reopen;

	int m = memfd_create("guest", MFD_ALLOW_SEALING);
	test_true(m >= 0);
	test_int_eq(write(m, "xyz", 3), 3);
	test_int_eq(fcntl(m, F_ADD_SEALS, F_SEAL_SHRINK), 0);
	char p[64];
	snprintf(p, sizeof p, "/proc/self/fd/%d", m);
	long r = th_sys(TAWC_SYS_openat, AT_FDCWD, p, O_RDONLY | O_CLOEXEC,
			0, 0, 0);
	test_true(r >= 0);
	test_false(is_memfd(m));
	test_int_eq(fcntl((int)r, F_GETFL) & O_ACCMODE, O_RDONLY);
	char buf[4] = { 0 };
	test_int_eq(pread((int)r, buf, 3, 0), 3);
	test_str_eq(buf, "xyz");
	/* Seals carried over, readable from the RO fd (Firefox's
	 * IsSafeToMap in a child). */
	test_int_eq(th_sys(TAWC_SYS_fcntl, r, F_GET_SEALS, 0, 0, 0, 0),
		    F_SEAL_SHRINK);
	test_int_eq(close((int)r), 0);
	test_int_eq(close(m), 0);

	/* Without MFD_ALLOW_SEALING the memfd starts F_SEAL_SEAL'd. */
	m = memfd_create("noseal", 0);
	test_true(m >= 0);
	snprintf(p, sizeof p, "/proc/self/fd/%d", m);
	r = th_sys(TAWC_SYS_openat, AT_FDCWD, p, O_RDONLY, 0, 0, 0);
	test_true(r >= 0);
	test_int_eq(th_sys(TAWC_SYS_fcntl, m, F_GET_SEALS, 0, 0, 0, 0),
		    F_SEAL_SEAL);
	test_int_eq(close((int)r), 0);
	test_int_eq(close(m), 0);

	/* Mapped: stays a memfd, the denial reaches the guest. */
	m = memfd_create("mapped", MFD_ALLOW_SEALING);
	test_true(m >= 0);
	test_int_eq(ftruncate(m, 4096), 0);
	void *map = mmap(NULL, 4096, PROT_READ, MAP_SHARED, m, 0);
	test_true(map != MAP_FAILED);
	snprintf(p, sizeof p, "/proc/self/fd/%d", m);
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, p, O_RDONLY, 0, 0, 0),
		    TAWC_EACCES);
	test_true(is_memfd(m));
	test_int_eq(munmap(map, 4096), 0);
	test_int_eq(close(m), 0);

	/* Plain files: seal fcntls pass through to the kernel. */
	int f = open("/dev/null", O_RDONLY);
	test_true(f >= 0);
	test_int_eq(th_sys(TAWC_SYS_fcntl, f, F_GET_SEALS, 0, 0, 0, 0),
		    TAWC_EINVAL);
	test_int_eq(close(f), 0);
	th_teardown(&v);
}
