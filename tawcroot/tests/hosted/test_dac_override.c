/* Hosted coverage for the lazy CAP_DAC_OVERRIDE emulation (rescue.c).
 *
 * These run as the dev user against a real tmpdir rootfs, so DAC is
 * real: a 0555 directory genuinely refuses the app uid, which is
 * exactly the situation the rescue exists for. th_sys() dispatches
 * through tawcroot_dispatch_call(), so every case goes through the
 * rescue wrapper the SIGSYS handler uses.
 *
 * Skipped under real uid 0 (rooted adbd on the device suite): there
 * the kernel never refuses, so nothing would be under test. Same
 * stance as the smoke's dropped-identity steps — notes/tawcroot/
 * testing.md §"Device-environment sensitivities". */

#include <cleat/test.h>

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "hosted.h"
#include "linkstore_fixture.h"
#include "../integration/rootfs_helpers.h"

#include "dispatch.h"
#include "errno_neg.h"
#include "identity.h"
#include "path.h"
#include "sysnr.h"

/* Registered dynamically (see the bottom of the file), so the bodies
 * are plain functions rather than test() blocks. */
#define dac_test(name) \
	static void test_##name([[maybe_unused]] TestCtx *test_ctx, \
				[[maybe_unused]] void const *_null_test_data)

/* Host path of a guest path inside the view. */
static const char *hp(th_view *v, const char *guest)
{
	static char buf[4400];
	snprintf(buf, sizeof buf, "%s%s", v->root, guest);
	return buf;
}

static unsigned host_mode(TestCtx *test_ctx, th_view *v, const char *guest)
{
	struct stat st;
	test_int_eq(lstat(hp(v, guest), &st), 0);
	return st.st_mode & 07777u;
}

/* Count fchmodat calls the production code issues while installed —
 * the observable signature of a rescue attempt. */
static int g_chmods;
static bool count_chmod_hook(long nr, const long args[6], long *ret)
{
	(void)args;
	(void)ret;
	if (nr == TAWC_SYS_fchmodat) g_chmods++;
	return false;
}

/* --- directories the owner cannot write ----------------------------- */

dac_test(dac_mkdirat_inside_unwritable_dir)
{
	th_view v;
	th_setup(&v, "dac-mkdir");

	test_true(rh_mkdir_p(hp(&v, "/run/ro"), 0755));
	test_int_eq(chmod(hp(&v, "/run/ro"), 0555), 0);

	test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/run/ro/made",
			   0755, 0, 0, 0), 0);
	struct stat st;
	test_int_eq(stat(hp(&v, "/run/ro/made"), &st), 0);
	/* The guest keeps the mode it set: widen and restore, not widen. */
	test_int_eq((long)host_mode(test_ctx, &v, "/run/ro"), 0555);

	test_int_eq(chmod(hp(&v, "/run/ro"), 0755), 0);
	th_teardown(&v);
}

/* The rootfs root itself is the reported bug (`chmod 555 /; mkdir /x`):
 * the parent is the route's BASE FD, not a path component. */
dac_test(dac_mkdirat_at_unwritable_rootfs_root)
{
	th_view v;
	th_setup(&v, "dac-root");

	test_int_eq(chmod(v.root, 0555), 0);
	test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/probe",
			   0755, 0, 0, 0), 0);
	struct stat st;
	test_int_eq(stat(hp(&v, "/probe"), &st), 0);
	test_int_eq((long)(st.st_mode & 07777u), 0755);

	struct stat rst;
	test_int_eq(stat(v.root, &rst), 0);
	test_int_eq((long)(rst.st_mode & 07777u), 0555);

	test_int_eq(chmod(v.root, 0755), 0);
	th_teardown(&v);
}

/* `[ -w / ]` must answer yes for root. The W_OK route is (base_fd, "")
 * — no path components at all. */
dac_test(dac_faccessat_w_ok_on_unwritable_dir)
{
	th_view v;
	th_setup(&v, "dac-wok");

	test_int_eq(chmod(v.root, 0555), 0);
	test_int_eq(th_sys(TAWC_SYS_faccessat, AT_FDCWD, "/", 2 /*W_OK*/,
			   0, 0, 0), 0);
	struct stat rst;
	test_int_eq(stat(v.root, &rst), 0);
	test_int_eq((long)(rst.st_mode & 07777u), 0555);

	test_int_eq(chmod(v.root, 0755), 0);
	th_teardown(&v);
}

/* X_OK stays honest: CAP_DAC_OVERRIDE never grants exec on a file with
 * no execute bit, so the rescue never adds x to a regular file. */
dac_test(dac_faccessat_x_ok_on_non_executable_stays_eacces)
{
	th_view v;
	th_setup(&v, "dac-xok");

	test_true(rh_write_text(hp(&v, "/run/plain"), "data\n"));
	test_int_eq(chmod(hp(&v, "/run/plain"), 0644), 0);

	test_int_eq(th_sys(TAWC_SYS_faccessat, AT_FDCWD, "/run/plain",
			   1 /*X_OK*/, 0, 0, 0), TAWC_EACCES);
	test_int_eq((long)host_mode(test_ctx, &v, "/run/plain"), 0644);

	th_teardown(&v);
}

/* --- leaf access ----------------------------------------------------- */

dac_test(dac_open_wronly_on_unwritable_file)
{
	th_view v;
	th_setup(&v, "dac-leaf");

	test_true(rh_write_text(hp(&v, "/run/ro.txt"), "old\n"));
	test_int_eq(chmod(hp(&v, "/run/ro.txt"), 0444), 0);

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/run/ro.txt",
			 O_WRONLY | O_TRUNC, 0, 0, 0);
	test_true(fd >= 0);
	test_true(write((int)fd, "new\n", 4) == 4);
	test_int_eq(close((int)fd), 0);
	/* Mode restored even though the fd outlives the rescue — the
	 * kernel checked permission once, at open. */
	test_int_eq((long)host_mode(test_ctx, &v, "/run/ro.txt"), 0444);

	th_teardown(&v);
}

/* Parent-only syscalls leave the leaf alone. Unlinking a 0444 file out
 * of a 0555 directory needs write on the DIRECTORY; the file's own
 * mode is irrelevant and must not be touched (there would be nothing
 * left to restore it on). */
dac_test(dac_unlinkat_file_in_unwritable_dir)
{
	th_view v;
	th_setup(&v, "dac-unlink");

	test_true(rh_mkdir_p(hp(&v, "/run/ro"), 0755));
	test_true(rh_write_text(hp(&v, "/run/ro/victim"), "x\n"));
	test_int_eq(chmod(hp(&v, "/run/ro/victim"), 0444), 0);
	test_int_eq(chmod(hp(&v, "/run/ro"), 0555), 0);

	test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/run/ro/victim",
			   0, 0, 0, 0), 0);
	test_int_eq((long)host_mode(test_ctx, &v, "/run/ro"), 0555);

	test_int_eq(chmod(hp(&v, "/run/ro"), 0755), 0);
	th_teardown(&v);
}

/* --- symlink safety --------------------------------------------------- */

/* fchmodat has no AT_SYMLINK_NOFOLLOW: chmod-ing a symlink leaf would
 * hit its TARGET, and under NOFOLLOW ops the leaf is routinely a
 * symlink. The rescue stats NOFOLLOW and skips anything that is not a
 * directory or a regular file, so the 0400 target keeps its mode. */
dac_test(dac_unlinkat_symlink_leaves_target_mode)
{
	th_view v;
	th_setup(&v, "dac-symlink");

	test_true(rh_mkdir_p(hp(&v, "/run/ro"), 0755));
	test_true(rh_write_text(hp(&v, "/run/target"), "keep\n"));
	test_int_eq(chmod(hp(&v, "/run/target"), 0400), 0);
	test_int_eq(symlink("../target", hp(&v, "/run/ro/lnk")), 0);
	test_int_eq(chmod(hp(&v, "/run/ro"), 0555), 0);

	test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/run/ro/lnk",
			   0, 0, 0, 0), 0);
	test_int_eq((long)host_mode(test_ctx, &v, "/run/target"), 0400);
	test_int_eq((long)host_mode(test_ctx, &v, "/run/ro"), 0555);

	test_int_eq(chmod(hp(&v, "/run/ro"), 0755), 0);
	th_teardown(&v);
}

/* Fail host linkat with EPERM (the Android SELinux denial) so the
 * hardlink emulation engages. */
static bool eperm_linkat_hook(long nr, const long args[6], long *ret)
{
	(void)args;
	if (nr != TAWC_SYS_linkat) return false;
	*ret = TAWC_EPERM;
	return true;
}

/* Every emulated hardlink name is a `tawcroot:link:<token>` symlink;
 * the data lives in the store. Unlinking one out of an unwritable
 * directory is a multi-step store mutation, so this also covers
 * re-running a handler that hit EACCES partway through. */
dac_test(dac_unlink_emulated_hardlink_in_unwritable_dir)
{
	th_view v;
	th_setup(&v, "dac-link");
	store_setup(&v);

	test_true(rh_mkdir_p(hp(&v, "/run/ro"), 0755));
	test_true(rh_write_text(hp(&v, "/run/ro/f"), "payload\n"));

	tawcroot_test_raw_hook = eperm_linkat_hook;
	test_int_eq(th_sys(TAWC_SYS_linkat, AT_FDCWD, "/run/ro/f",
			   AT_FDCWD, "/run/ro/l1", 0, 0), 0);
	tawcroot_test_raw_hook = NULL;

	test_int_eq(chmod(hp(&v, "/run/ro"), 0555), 0);

	test_int_eq(th_sys(TAWC_SYS_unlinkat, AT_FDCWD, "/run/ro/l1",
			   0, 0, 0, 0), 0);
	test_int_eq((long)host_mode(test_ctx, &v, "/run/ro"), 0555);

	/* The surviving name still reads the cluster's data. */
	test_int_eq(chmod(hp(&v, "/run/ro"), 0755), 0);
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/run/ro/f",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[32] = {0};
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_str_eq(buf, "payload\n");
	test_int_eq(close((int)fd), 0);

	store_teardown();
	th_teardown(&v);
}

/* --- when the rescue must NOT fire ------------------------------------ */

/* A guest that genuinely dropped privileges gets the real EACCES —
 * same gate as the fchmodat/fchownat fakes. */
dac_test(dac_dropped_identity_gets_real_eacces)
{
	th_view v;
	th_setup(&v, "dac-dropped");

	test_true(rh_mkdir_p(hp(&v, "/run/ro"), 0755));
	test_int_eq(chmod(hp(&v, "/run/ro"), 0555), 0);

	test_int_eq(th_sys(TAWC_SYS_setresuid, 994, 994, 994, 0, 0, 0), 0);
	g_chmods = 0;
	tawcroot_test_raw_hook = count_chmod_hook;
	test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/run/ro/nope",
			   0755, 0, 0, 0), TAWC_EACCES);
	tawcroot_test_raw_hook = NULL;
	test_int_eq(g_chmods, 0);
	tawcroot_identity_reset();

	/* Back at virtual root the same call succeeds. */
	test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/run/ro/yes",
			   0755, 0, 0, 0), 0);

	test_int_eq(chmod(hp(&v, "/run/ro"), 0755), 0);
	th_teardown(&v);
}

/* Find a directory we do NOT own where a plain mkdir is refused with
 * EACCES specifically — not EROFS, which the host root gives on
 * Android (`/` is a read-only rootfs there) and which would never
 * reach the rescue in the first place. Returns NULL when the
 * environment has no such directory. Leaves nothing behind. */
static const char *unowned_dir(void)
{
	static const char *const candidates[] = { "/data", "/", "/etc" };
	static char probe[256];
	for (size_t i = 0; i < sizeof candidates / sizeof candidates[0]; i++) {
		struct stat st;
		if (stat(candidates[i], &st) != 0) continue;
		if (!S_ISDIR(st.st_mode)) continue;
		if (st.st_uid == geteuid()) continue;
		snprintf(probe, sizeof probe, "%s/tawcroot-dacprobe-%d",
			 candidates[i], getpid());
		if (mkdir(probe, 0755) == 0) { rmdir(probe); continue; }
		if (errno != EACCES) continue;
		return candidates[i];
	}
	return NULL;
}

/* A denial we cannot own returns the original EACCES with no widening
 * attempted and no second handler run — the same shape as a real
 * SELinux denial or a host-/dev bind on device. */
dac_test(dac_not_owned_route_is_not_rescued)
{
	const char *unowned = unowned_dir();
	if (!unowned) {
		printf("    skipping (no unowned EACCES directory here)\n");
		return;
	}

	th_view v;
	th_setup(&v, "dac-notowned");

	/* Not th_add_bind: that makes an app-owned src. */
	test_int_eq(tawcroot_path_add_bind(unowned, "/notmine", 0), 0);

	g_chmods = 0;
	tawcroot_test_raw_hook = count_chmod_hook;
	test_int_eq(th_sys(TAWC_SYS_mkdirat, AT_FDCWD, "/notmine/probe",
			   0755, 0, 0, 0), TAWC_EACCES);
	tawcroot_test_raw_hook = NULL;
	test_int_eq(g_chmods, 0);

	th_teardown(&v);
}

/* Registered only where DAC actually binds: as real root every one of
 * these succeeds without the rescue and proves nothing. */
register_dynamic_tests
{
	if (geteuid() == 0) return;
	csview mod = test_module_from_file(__FILE__);
#define REG(n) register_test(mod, c_sv(#n), test_##n, nullptr, nullptr)
	REG(dac_mkdirat_inside_unwritable_dir);
	REG(dac_mkdirat_at_unwritable_rootfs_root);
	REG(dac_faccessat_w_ok_on_unwritable_dir);
	REG(dac_faccessat_x_ok_on_non_executable_stays_eacces);
	REG(dac_open_wronly_on_unwritable_file);
	REG(dac_unlinkat_file_in_unwritable_dir);
	REG(dac_unlinkat_symlink_leaves_target_mode);
	REG(dac_unlink_emulated_hardlink_in_unwritable_dir);
	REG(dac_dropped_identity_gets_real_eacces);
	REG(dac_not_owned_route_is_not_rescued);
#undef REG
}
