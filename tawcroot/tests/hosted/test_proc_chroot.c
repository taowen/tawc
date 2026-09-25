/* Hosted handler-level tests for the /proc shadows (proc_shadow.c) and
 * chroot emulation (chroot.c). Both run their full production pipelines
 * in-process under ASan: the maps shadow's growable mmap read + rewrite
 * + memfd synthesis, and chroot's translate → reserve → bind-reanchor →
 * memo-rebuild sequence. See hosted.h. */

#include <cleat/test.h>

#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include "hosted.h"
#include "../integration/rootfs_helpers.h"

#include "errno_neg.h"
#include "fdtab.h"
#include "path.h"
#include "proc_shadow.h"
#include "sysnr.h"

/* --- /proc shadows through the openat handler --------------------------- */

test(hosted_proc_self_maps_shadow_synthesized)
{
	th_view v;
	th_setup(&v, "proc-maps");

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/self/maps",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);

	/* The shadow is a fully-materialized memfd: the whole rewritten
	 * maps content must be readable and look like a maps file. */
	char buf[8192];
	size_t total = 0;
	long n;
	while ((n = read((int)fd, buf, sizeof buf - 1)) > 0) {
		if (total == 0) {
			buf[n] = 0;
			/* First chunk: starts with a hex address range. */
			test_nonnull(strchr(buf, '-'));
			test_nonnull(strstr(buf, " r"));
		}
		total += (size_t)n;
	}
	test_int_eq(n, 0);
	test_true(total > 0);

	/* memfd, not the real procfs file: lseek back works and the size
	 * is stable (procfs maps reads are generated per-read). */
	test_int_eq(lseek((int)fd, 0, SEEK_SET), 0);

	test_int_eq(close((int)fd), 0);
	th_teardown(&v);
}

test(hosted_proc_thread_self_maps_shadow_synthesized)
{
	th_view v;
	th_setup(&v, "proc-tsmaps");

	/* /proc/thread-self/maps is per-mm — identical content class to
	 * /proc/self/maps — and must hit the same shadow. Untranslated,
	 * it leaks raw host paths the guest view doesn't contain. */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/thread-self/maps",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);

	/* Discriminate shadow from a passed-through procfs open: the
	 * shadow is a fully-materialized memfd with a real size, while
	 * procfs maps files stat as size 0. */
	struct stat st;
	test_int_eq(fstat((int)fd, &st), 0);
	test_true(st.st_size > 0);

	char buf[512] = {0};
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_nonnull(strchr(buf, '-'));

	test_int_eq(close((int)fd), 0);
	th_teardown(&v);
}

test(hosted_proc_overflowuid_shadow_content)
{
	th_view v;
	th_setup(&v, "proc-ovfl");

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD,
			 "/proc/sys/kernel/overflowuid", O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[16] = {0};
	test_int_eq(read((int)fd, buf, sizeof buf - 1), 6);
	test_str_eq(buf, "65534\n");
	test_int_eq(close((int)fd), 0);

	long gfd = th_sys(TAWC_SYS_openat, AT_FDCWD,
			  "/proc/sys/kernel/overflowgid", O_RDONLY, 0, 0, 0);
	test_true(gfd >= 0);
	memset(buf, 0, sizeof buf);
	test_int_eq(read((int)gfd, buf, sizeof buf - 1), 6);
	test_str_eq(buf, "65534\n");
	test_int_eq(close((int)gfd), 0);

	th_teardown(&v);
}

/* --- shadow metadata: stat / statx / access ----------------------------- */

/* Every shadowed absolute path, with the kind it must classify to. The
 * lockstep test below asserts this covers every kind the classifier
 * knows, so adding a shadow to one surface only fails CI. */
static const struct { const char *path; int kind; } shadow_paths[] = {
	{ "/proc/self/maps",                 TAWCROOT_PROC_SHADOW_MAPS },
	{ "/proc/sys/kernel/overflowuid",    TAWCROOT_PROC_SHADOW_OVERFLOWUID },
	{ "/proc/sys/kernel/overflowgid",    TAWCROOT_PROC_SHADOW_OVERFLOWGID },
	{ "/proc/bus/pci/devices",           TAWCROOT_PROC_SHADOW_PCI_DEVICES },
	{ "/proc/stat",                      TAWCROOT_PROC_SHADOW_STAT },
	{ "/proc/version",                   TAWCROOT_PROC_SHADOW_VERSION },
	{ "/proc/uptime",                    TAWCROOT_PROC_SHADOW_UPTIME },
	{ "/proc/loadavg",                   TAWCROOT_PROC_SHADOW_LOADAVG },
	{ "/proc/net/tcp",                   TAWCROOT_PROC_SHADOW_TCP },
	{ "/proc/self/net/tcp6",             TAWCROOT_PROC_SHADOW_TCP6 },
};
#define N_SHADOW_PATHS ((int)(sizeof shadow_paths / sizeof shadow_paths[0]))

test(hosted_proc_shadow_metadata_synthesized)
{
	th_view v;
	th_setup(&v, "proc-meta");

	for (int i = 0; i < N_SHADOW_PATHS; i++) {
		const char *path = shadow_paths[i].path;
		test_int_eq(tawcroot_proc_shadow_classify(path),
			    shadow_paths[i].kind);

		struct stat st;
		memset(&st, 0xff, sizeof st);
		test_int_eq(th_sys(TAWC_SYS_fstatat, AT_FDCWD, path,
				   &st, 0, 0, 0), 0);
		test_true(S_ISREG(st.st_mode));
		test_int_eq(st.st_uid, 0);
		test_int_eq(st.st_gid, 0);
		test_int_eq(st.st_nlink, 1);
		test_int_eq(st.st_size, 0);

		struct statx stx;
		memset(&stx, 0xff, sizeof stx);
		test_int_eq(th_sys(TAWC_SYS_statx, AT_FDCWD, path, 0,
				   STATX_BASIC_STATS, &stx, 0), 0);
		test_true(S_ISREG(stx.stx_mode));
		test_int_eq(stx.stx_uid, 0);
		test_int_eq(stx.stx_gid, 0);
		test_int_eq(stx.stx_nlink, 1);
		test_int_eq(stx.stx_size, 0);
		test_int_eq(stx.stx_mask & STATX_BASIC_STATS,
			    STATX_BASIC_STATS);

		/* 0444: readable and existing, never writable or
		 * executable. */
		test_int_eq(th_sys(TAWC_SYS_faccessat, AT_FDCWD, path,
				   F_OK, 0, 0, 0), 0);
		test_int_eq(th_sys(TAWC_SYS_faccessat, AT_FDCWD, path,
				   R_OK, 0, 0, 0), 0);
		test_int_eq(th_sys(TAWC_SYS_faccessat, AT_FDCWD, path,
				   W_OK, 0, 0, 0), TAWC_EACCES);
		test_int_eq(th_sys(TAWC_SYS_faccessat, AT_FDCWD, path,
				   X_OK, 0, 0, 0), TAWC_EACCES);
	}

	th_teardown(&v);
}

test(hosted_proc_shadow_metadata_fd_relative)
{
	th_view v;
	th_setup(&v, "proc-metarel");

	/* fstatat(<fd of /proc>, "version") must reach the shadow too —
	 * the composed form is what a guest that cached a /proc dirfd
	 * uses, and without the compose it hits the denied inode. */
	int pfd = open("/proc", O_PATH | O_DIRECTORY | O_CLOEXEC);
	test_true(pfd >= 0);

	struct stat st;
	memset(&st, 0xff, sizeof st);
	test_int_eq(th_sys(TAWC_SYS_fstatat, pfd, "version", &st, 0, 0, 0), 0);
	test_true(S_ISREG(st.st_mode));
	test_int_eq(st.st_size, 0);
	test_int_eq(st.st_uid, 0);

	struct statx stx;
	memset(&stx, 0xff, sizeof stx);
	test_int_eq(th_sys(TAWC_SYS_statx, pfd, "uptime", 0,
			   STATX_BASIC_STATS, &stx, 0), 0);
	test_true(S_ISREG(stx.stx_mode));
	test_int_eq(stx.stx_size, 0);

	test_int_eq(th_sys(TAWC_SYS_faccessat, pfd, "loadavg", R_OK,
			   0, 0, 0), 0);
	test_int_eq(th_sys(TAWC_SYS_faccessat, pfd, "loadavg", W_OK,
			   0, 0, 0), TAWC_EACCES);

	/* The open surface takes the same composed path. */
	long fd = th_sys(TAWC_SYS_openat, pfd, "version", O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);

	test_int_eq(close(pfd), 0);
	th_teardown(&v);
}

test(hosted_proc_shadow_open_stat_lockstep)
{
	th_view v;
	th_setup(&v, "proc-lockstep");

	/* Cover check: shadow_paths must name every kind the classifier
	 * knows. A new kind added to proc_shadow.h without a table entry
	 * (hence without both surfaces exercised) fails here. */
	for (int kind = 1; kind <= TAWCROOT_PROC_SHADOW_KIND_MAX; kind++) {
		int found = 0;
		for (int i = 0; i < N_SHADOW_PATHS; i++)
			if (shadow_paths[i].kind == kind) found = 1;
		test_int_eq(found, 1);
	}

	/* Every kind that opens must also stat, and vice versa: a path
	 * that stats fine but fails to open is a worse lie than one that
	 * fails both ways. */
	for (int i = 0; i < N_SHADOW_PATHS; i++) {
		const char *path = shadow_paths[i].path;
		long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, path,
				 O_RDONLY, 0, 0, 0);
		test_true(fd >= 0);
		test_int_eq(close((int)fd), 0);

		struct stat st;
		test_int_eq(th_sys(TAWC_SYS_fstatat, AT_FDCWD, path,
				   &st, 0, 0, 0), 0);
	}

	th_teardown(&v);
}

/* --- new content synthesizers ------------------------------------------ */

/* Read a shadow through the openat handler into `buf` (NUL-terminated).
 * Returns the byte count. */
static long read_shadow(TestCtx *test_ctx, const char *path,
			char *buf, size_t cap)
{
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, path, O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	long n = read((int)fd, buf, cap - 1);
	test_true(n > 0);
	buf[n] = 0;
	test_int_eq(close((int)fd), 0);
	return n;
}

test(hosted_proc_version_shadow_content)
{
	th_view v;
	th_setup(&v, "proc-version");

	char buf[512];
	read_shadow(test_ctx, "/proc/version", buf, sizeof buf);

	/* Kernel shape, with our marker in the builder parenthetical —
	 * the host's real /proc/version IS readable, so the marker is
	 * what distinguishes the shadow from a passthrough. */
	test_true(strncmp(buf, "Linux version ", 14) == 0);
	test_nonnull(strstr(buf, "(tawcroot@android)"));
	test_int_eq(buf[strlen(buf) - 1], '\n');

	th_teardown(&v);
}

test(hosted_proc_uptime_shadow_content)
{
	th_view v;
	th_setup(&v, "proc-uptime");

	char buf[128];
	read_shadow(test_ctx, "/proc/uptime", buf, sizeof buf);

	/* "<up>.<cs> <idle>.<cs>\n", both non-negative and EQUAL — the
	 * idle figure must agree with the all-idle cpu line /proc/stat
	 * emits or procps prints negative CPU usage. */
	char *end = NULL;
	double up = strtod(buf, &end);
	test_true(end != buf);
	test_true(up >= 0.0);
	char *end2 = NULL;
	double idle = strtod(end, &end2);
	test_true(end2 != end);
	test_true(idle >= 0.0);
	test_true(up == idle);
	test_str_eq(end2, "\n");

	th_teardown(&v);
}

test(hosted_proc_loadavg_shadow_content)
{
	th_view v;
	th_setup(&v, "proc-loadavg");

	char buf[128];
	read_shadow(test_ctx, "/proc/loadavg", buf, sizeof buf);

	char want[64];
	snprintf(want, sizeof want, "0.00 0.00 0.00 1/1 %d\n", getpid());
	test_str_eq(buf, want);

	th_teardown(&v);
}

test(hosted_proc_bus_pci_devices_shadow_empty)
{
	th_view v;
	th_setup(&v, "proc-pci");

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/bus/pci/devices",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[8];
	test_int_eq(read((int)fd, buf, sizeof buf), 0);  /* empty file */
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}

test(hosted_proc_self_exe_classifier)
{
	th_view v;
	th_setup(&v, "proc-exe");

	test_int_eq(tawcroot_is_proc_self_exe("/proc/self/exe"), 1);
	char own[64];
	snprintf(own, sizeof own, "/proc/%d/exe", getpid());
	test_int_eq(tawcroot_is_proc_self_exe(own), 1);
	snprintf(own, sizeof own, "/proc/self/task/%d/exe", getpid());
	test_int_eq(tawcroot_is_proc_self_exe(own), 1);

	test_int_eq(tawcroot_is_proc_self_exe("/proc/self/exec"), 0);
	test_int_eq(tawcroot_is_proc_self_exe("/proc/self"), 0);
	test_int_eq(tawcroot_is_proc_self_exe("/proc/1/exe"), 0);
	test_int_eq(tawcroot_is_proc_self_exe("/etc/exe"), 0);

	/* exe classifier: another process's exe link is OTHER, not NONE —
	 * the readlink handler uses this to suppress the result-equality
	 * substitution for cross-process links. */
	test_int_eq(tawcroot_proc_exe_classify("/proc/self/exe"),
		    TAWCROOT_PROC_EXE_SELF);
	test_int_eq(tawcroot_proc_exe_classify("/proc/1/exe"),
		    TAWCROOT_PROC_EXE_OTHER);
	test_int_eq(tawcroot_proc_exe_classify("/proc/1/task/1/exe"),
		    TAWCROOT_PROC_EXE_OTHER);
	test_int_eq(tawcroot_proc_exe_classify("/proc/self/exec"),
		    TAWCROOT_PROC_EXE_NONE);
	test_int_eq(tawcroot_proc_exe_classify("/etc/exe"),
		    TAWCROOT_PROC_EXE_NONE);

	/* cwd classifier mirrors the exe one (self-only). */
	test_int_eq(tawcroot_is_proc_self_cwd("/proc/self/cwd"), 1);
	snprintf(own, sizeof own, "/proc/%d/cwd", getpid());
	test_int_eq(tawcroot_is_proc_self_cwd(own), 1);
	test_int_eq(tawcroot_is_proc_self_cwd("/proc/1/cwd"), 0);
	test_int_eq(tawcroot_is_proc_self_cwd("/proc/self/cwd2"), 0);
	test_int_eq(tawcroot_is_proc_self_cwd("/proc/self/root"), 0);

	th_teardown(&v);
}

test(hosted_readlink_proc_self_cwd_returns_guest_path)
{
	th_view v;
	th_setup(&v, "proc-rdcwd");

	char p[4200];
	snprintf(p, sizeof p, "%s/etc/sub", v.root);
	test_int_eq(chdir(p), 0);

	char buf[256] = {0};
	long n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/cwd",
			buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/etc/sub"));
	test_str_eq(buf, "/etc/sub");

	/* Numeric-pid form. */
	char own[64];
	snprintf(own, sizeof own, "/proc/%d/cwd", getpid());
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, own,
		   buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/etc/sub"));
	test_str_eq(buf, "/etc/sub");

	/* Fd-relative form: readlinkat(<fd of /proc/self>, "cwd"). */
	int pfd = open("/proc/self", O_PATH | O_DIRECTORY | O_CLOEXEC);
	test_true(pfd >= 0);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, pfd, "cwd", buf, sizeof buf - 1,
		   0, 0);
	test_int_eq(n, (long)strlen("/etc/sub"));
	test_str_eq(buf, "/etc/sub");
	test_int_eq(close(pfd), 0);

	/* Truncation contract matches readlink(2): short buffer gets a
	 * partial, non-NUL-padded copy. */
	char tiny[6];
	memset(tiny, 'Z', sizeof tiny);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/cwd",
		   tiny, 4, 0, 0);
	test_int_eq(n, 4);
	test_true(memcmp(tiny, "/etc", 4) == 0);
	test_true(tiny[4] == 'Z');  /* untouched past bufsiz */

	/* Kernel cwd outside the view: refuse (like getcwd), don't leak
	 * the host path. */
	test_int_eq(chdir("/"), 0);
	test_int_eq(th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/cwd",
			   buf, sizeof buf - 1, 0, 0), TAWC_ENOENT);

	th_teardown(&v);
}

/* /proc/<pid>/fd/<n> magic links resolve to HOST paths; in-view targets
 * must come back reverse-translated as guest paths. Bun's realpath
 * (Claude Code et al.) canonicalizes its cwd via open(dir) +
 * readlink(/proc/self/fd/<n>), then ENOENT'd on the leaked host path.
 * Outside-view targets ("pipe:[...]", app-private files) pass through
 * verbatim — fd links legitimately point outside the view. */
test(hosted_readlink_proc_fd_link_returns_guest_path)
{
	th_view v;
	th_setup(&v, "proc-rdfd");

	/* Classifier shapes. Any pid is accepted (sibling tawcroot
	 * processes share the view; the prefix walk is a no-op for
	 * foreign targets). */
	test_int_eq(tawcroot_is_proc_fd_link("/proc/self/fd/3"), 1);
	test_int_eq(tawcroot_is_proc_fd_link("/proc/1/fd/44"), 1);
	char own[64];
	snprintf(own, sizeof own, "/proc/%d/fd/0", getpid());
	test_int_eq(tawcroot_is_proc_fd_link(own), 1);
	test_int_eq(tawcroot_is_proc_fd_link("/proc/self/fd"), 0);
	test_int_eq(tawcroot_is_proc_fd_link("/proc/self/fd/"), 0);
	test_int_eq(tawcroot_is_proc_fd_link("/proc/self/fd/3x"), 0);
	test_int_eq(tawcroot_is_proc_fd_link("/proc/self/fdinfo/3"), 0);
	test_int_eq(tawcroot_is_proc_fd_link("/etc/fd/3"), 0);

	/* The single-kind helpers are wrappers over the one-pass
	 * classifier; spot-check its raw answers too. */
	test_int_eq(tawcroot_proc_link_classify("/proc/self/fd/3"),
		    TAWCROOT_PROC_LINK_FD);
	test_int_eq(tawcroot_proc_link_classify("/proc/self/cwd"),
		    TAWCROOT_PROC_LINK_CWD);
	test_int_eq(tawcroot_proc_link_classify("/proc/1/cwd"),
		    TAWCROOT_PROC_LINK_NONE);
	test_int_eq(tawcroot_proc_link_classify("/proc/self/exe"),
		    TAWCROOT_PROC_LINK_EXE_SELF);
	test_int_eq(tawcroot_proc_link_classify("/proc/1/exe"),
		    TAWCROOT_PROC_LINK_EXE_OTHER);
	test_int_eq(tawcroot_proc_link_classify("/proc/self/maps"),
		    TAWCROOT_PROC_LINK_NONE);

	/* Route guest /proc to the host's (in production /proc is a
	 * bind) so the translated readlink reaches the real magic link. */
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	/* In-view directory fd: link reads back as the GUEST path. */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/sub",
			 O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
	test_true(fd >= 0);
	char link[64];
	snprintf(link, sizeof link, "/proc/self/fd/%ld", fd);
	char buf[256] = {0};
	long n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
			buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/etc/sub"));
	test_str_eq(buf, "/etc/sub");

	/* Numeric-pid form. */
	snprintf(link, sizeof link, "/proc/%d/fd/%ld", getpid(), fd);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
		   buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/etc/sub"));
	test_str_eq(buf, "/etc/sub");

	/* Fd-relative form, as `ls -l /proc/self/fd` issues it:
	 * readlinkat(<fd of /proc/self/fd>, "<n>"). */
	int pfd = open("/proc/self/fd", O_PATH | O_DIRECTORY | O_CLOEXEC);
	test_true(pfd >= 0);
	char leaf[16];
	snprintf(leaf, sizeof leaf, "%ld", fd);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, pfd, leaf, buf, sizeof buf - 1,
		   0, 0);
	test_int_eq(n, (long)strlen("/etc/sub"));
	test_str_eq(buf, "/etc/sub");
	test_int_eq(close(pfd), 0);
	test_int_eq(close((int)fd), 0);

	/* Rootfs root itself reads back as "/". */
	long rfd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/",
			  O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0, 0);
	test_true(rfd >= 0);
	snprintf(link, sizeof link, "/proc/self/fd/%ld", rfd);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
		   buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, 1);
	test_str_eq(buf, "/");
	test_int_eq(close((int)rfd), 0);

	/* Bind-source fd: link reads back as the bind DST. */
	const char *bsrc = th_add_bind(&v, "/system");
	int bfd = open(bsrc, O_PATH | O_DIRECTORY | O_CLOEXEC);
	test_true(bfd >= 0);
	snprintf(link, sizeof link, "/proc/self/fd/%d", bfd);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
		   buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/system"));
	test_str_eq(buf, "/system");
	test_int_eq(close(bfd), 0);

	/* Outside-view target: kernel bytes pass through verbatim (a
	 * pipe's link text isn't a path at all). */
	int pp[2];
	test_int_eq(pipe(pp), 0);
	snprintf(link, sizeof link, "/proc/self/fd/%d", pp[0]);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
		   buf, sizeof buf - 1, 0, 0);
	test_true(n > 5);
	test_true(memcmp(buf, "pipe:", 5) == 0);
	test_int_eq(close(pp[0]), 0);
	test_int_eq(close(pp[1]), 0);

	th_teardown(&v);
}

/* Cross-process exe links must NOT be substituted with the caller's
 * guest exe path. Every tawcroot guest's kernel exe is the same
 * libtawcroot.so, so the handler's readlink-result equality check alone
 * matches for another guest's /proc/<pid>/exe too; the request-path
 * classification has to suppress it (observed in the field: `ls`
 * reading bash's exe link got "/usr/bin/ls"). Simulated here with a
 * forked child (same kernel exe as us) and tawcroot_self_host_path set
 * to our real exe. */
test(hosted_readlink_other_process_exe_not_substituted)
{
	th_view v;
	th_setup(&v, "proc-oexe");

	/* Route guest /proc to the host's so /proc/<pid>/exe is reachable
	 * through translation (in production /proc is a bind). */
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	char real_exe[4096];
	long rn = readlink("/proc/self/exe", real_exe, sizeof real_exe - 1);
	test_true(rn > 0);
	real_exe[rn] = 0;
	memcpy(tawcroot_self_host_path, real_exe, (size_t)rn + 1);
	tawcroot_self_host_path_len = (size_t)rn;
	tawcroot_set_guest_exe_path("/usr/bin/guest-prog");

	pid_t child = fork();
	test_true(child >= 0);
	if (child == 0) {
		pause();
		_exit(0);
	}

	/* Another process's exe link: kernel bytes pass through verbatim
	 * (consistent with cross-process /proc in general), no
	 * substitution. */
	char link[64];
	snprintf(link, sizeof link, "/proc/%d/exe", (int)child);
	char buf[4096] = {0};
	long n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
			buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, rn);
	test_str_eq(buf, real_exe);

	/* ... while a non-/proc symlink whose target happens to be our
	 * exe still gets the substitution (that's the surface protecting
	 * readlink("/proc/self/fd/<n>") and O_PATH empty-path reads). */
	char lp[4300];
	snprintf(lp, sizeof lp, "%s/etc/exelink", v.root);
	test_int_eq(symlink(real_exe, lp), 0);
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/etc/exelink",
		   buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/usr/bin/guest-prog"));
	test_str_eq(buf, "/usr/bin/guest-prog");

	/* And our own exe link still synthesizes from the stash. */
	memset(buf, 0, sizeof buf);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/exe",
		   buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/usr/bin/guest-prog"));
	test_str_eq(buf, "/usr/bin/guest-prog");

	test_int_eq(kill(child, SIGKILL), 0);
	test_int_eq(waitpid(child, NULL, 0), child);

	tawcroot_self_host_path[0]  = 0;
	tawcroot_self_host_path_len = 0;
	th_teardown(&v);
}

test(hosted_readlink_proc_self_exe_returns_guest_path)
{
	th_view v;
	th_setup(&v, "proc-rdexe");

	tawcroot_set_guest_exe_path("/usr/bin/guest-prog");

	char buf[128] = {0};
	long n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/exe",
			buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, (long)strlen("/usr/bin/guest-prog"));
	test_str_eq(buf, "/usr/bin/guest-prog");

	/* Truncation contract matches readlink(2): short buffer gets a
	 * partial, non-NUL-padded copy. */
	char tiny[6];
	memset(tiny, 'Z', sizeof tiny);
	n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/exe",
		   tiny, 4, 0, 0);
	test_int_eq(n, 4);
	test_true(memcmp(tiny, "/usr", 4) == 0);
	test_true(tiny[4] == 'Z');  /* untouched past bufsiz */

	tawcroot_set_guest_exe_path(NULL);
	th_teardown(&v);
}

/* --- /proc magic-link containment ---------------------------------------- */

/* `/proc` is bound RW into every rootfs and a path matching a bind takes
 * the early return in tawcroot_path_translate_with_ctx, so the suffix
 * reaches the kernel unresolved — and the kernel's `/proc/<pid>/root` is
 * the real HOST root (tawcroot never chroots). These tests pin the three
 * outcomes: own-root is rewritten to the guest's root, everything else
 * that resolves out of view is ENOENT, and our own fd links keep
 * working. See contain_proc_magic_link / the rewrite loop in path.c. */

test(hosted_proc_magic_link_classify)
{
	th_view v;
	th_setup(&v, "proc-magicls");

	int k;
	char own[64];

	/* Own root: rewritten, not refused. Both spellings and the
	 * resolve-through form. */
	test_int_eq((long)tawcroot_proc_magic_link_classify("self/root", &k), 9);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_ROOT_OWN);
	test_int_eq((long)tawcroot_proc_magic_link_classify(
			    "self/root/etc/probe", &k), 9);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_ROOT_OWN);
	snprintf(own, sizeof own, "%d/root", getpid());
	tawcroot_proc_magic_link_classify(own, &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_ROOT_OWN);

	/* Another process's root, and any cwd: contained. */
	tawcroot_proc_magic_link_classify("1/root", &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_CONTAIN);
	tawcroot_proc_magic_link_classify("self/cwd", &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_CONTAIN);

	/* Our own fd / map_files entries: exempt (we already hold the fd).
	 * Another process's are not. */
	tawcroot_proc_magic_link_classify("self/fd/3", &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_FD_OWN);
	tawcroot_proc_magic_link_classify("self/map_files/400000-401000", &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_FD_OWN);
	snprintf(own, sizeof own, "self/task/%d/fd/3", getpid());
	tawcroot_proc_magic_link_classify(own, &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_FD_OWN);
	tawcroot_proc_magic_link_classify("1/fd/3", &k);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_CONTAIN);

	/* Non-links keep answering 0/NONE, and the prefix-only wrapper
	 * (the RO check's entry point) agrees on every length. */
	test_int_eq((long)tawcroot_proc_magic_link_classify("self/maps", &k), 0);
	test_int_eq(k, TAWCROOT_PROC_MAGIC_NONE);
	test_int_eq((long)tawcroot_proc_magic_link_classify("self/fd", &k), 0);
	test_int_eq((long)tawcroot_proc_magic_link_prefix("self/root"), 9);
	test_int_eq((long)tawcroot_proc_magic_link_prefix("self/cwd"), 8);
	test_int_eq((long)tawcroot_proc_magic_link_prefix("self/fd/3"), 9);
	test_int_eq((long)tawcroot_proc_magic_link_prefix("self/maps"), 0);

	th_teardown(&v);
}

test(hosted_proc_self_root_routes_to_guest_root)
{
	th_view v;
	th_setup(&v, "proc-selfroot");
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	/* Traversal answers what a real chroot would: the GUEST root. */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/self/root/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[32] = {0};
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_str_eq(buf, "from-rootfs\n");
	test_int_eq(close((int)fd), 0);

	/* Nested spellings unwind one link per pass. */
	fd = th_sys(TAWC_SYS_openat, AT_FDCWD,
		    "/proc/self/root/proc/self/root/etc/probe", O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);

	/* The escape. Target a host file that is NOT the rootfs or any
	 * bind — a sibling of the rootfs dir, so the prefix walk can't
	 * component-match it. Before the rewrite this read the host file;
	 * now the name resolves inside the guest's own root and misses. */
	char host_out[4300];
	snprintf(host_out, sizeof host_out, "%s-outside", v.root);
	test_true(rh_write_text(host_out, "from-host\n"));
	test_int_eq(access(host_out, F_OK), 0);  /* the probe is meaningful */

	char esc[4400];
	snprintf(esc, sizeof esc, "/proc/self/root%s", host_out);
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, esc, O_RDONLY, 0, 0, 0),
		    TAWC_ENOENT);

	/* Same for a write. The create is aimed at a directory that exists
	 * on the host and not in the guest, so the rewritten name has
	 * nowhere to land and nothing appears on the host. */
	char host_dir[4300], host_esc[4400];
	snprintf(host_dir, sizeof host_dir, "%s-outdir", v.root);
	test_true(rh_mkdir_p(host_dir, 0755));
	snprintf(host_esc, sizeof host_esc, "%s/escaped", host_dir);
	snprintf(esc, sizeof esc, "/proc/self/root%s", host_esc);
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, esc,
			   O_WRONLY | O_CREAT, 0644, 0, 0), TAWC_ENOENT);
	test_int_eq(access(host_esc, F_OK), -1);

	test_int_eq(unlink(host_out), 0);
	rh_rmrf(host_dir);

	/* A write THROUGH the link into the view still works — containment
	 * is not a refusal of the shape. */
	fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/self/root/tmp/inside",
		    O_WRONLY | O_CREAT, 0644, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);
	char inside[4300];
	snprintf(inside, sizeof inside, "%s/tmp/inside", v.root);
	test_int_eq(access(inside, F_OK), 0);

	th_teardown(&v);
}

/* The link OBJECT is not the thing it points at: NOFOLLOW operations on
 * a bare root link stay kernel-faithful (readlink answers "/", which is
 * what a chroot'd process sees), while FOLLOW lands on the guest root. */
test(hosted_proc_self_root_bare_link)
{
	th_view v;
	th_setup(&v, "proc-rootbare");
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	char buf[64] = {0};
	long n = th_sys(TAWC_SYS_readlinkat, AT_FDCWD, "/proc/self/root",
			buf, sizeof buf - 1, 0, 0);
	test_int_eq(n, 1);
	test_str_eq(buf, "/");

	/* FOLLOW: open/stat resolve to the guest root, not the host's. */
	struct stat via_link, guest_root;
	test_int_eq(th_sys(TAWC_SYS_fstatat, AT_FDCWD, "/proc/self/root",
			   &via_link, 0, 0, 0), 0);
	test_int_eq(stat(v.root, &guest_root), 0);
	test_true(via_link.st_dev == guest_root.st_dev);
	test_true(via_link.st_ino == guest_root.st_ino);

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/self/root",
			 O_RDONLY | O_DIRECTORY, 0, 0, 0);
	test_true(fd >= 0);
	struct stat opened;
	test_int_eq(fstat((int)fd, &opened), 0);
	test_true(opened.st_ino == guest_root.st_ino);
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}

test(hosted_proc_other_pid_root_contained)
{
	th_view v;
	th_setup(&v, "proc-otherroot");
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	pid_t child = fork();
	test_true(child >= 0);
	if (child == 0) {
		pause();
		_exit(0);
	}

	/* The child's root is the HOST root, and it is not ours, so the
	 * link is resolved and contained rather than rewritten. Naming a
	 * host file outside the view through it must miss. */
	char host_out[4300];
	snprintf(host_out, sizeof host_out, "%s-outside", v.root);
	test_true(rh_write_text(host_out, "from-host\n"));

	char esc[4400];
	snprintf(esc, sizeof esc, "/proc/%d/root%s", (int)child, host_out);
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, esc, O_RDONLY, 0, 0, 0),
		    TAWC_ENOENT);
	test_int_eq(unlink(host_out), 0);

	/* But a host path that IS in view stays reachable, deliberately:
	 * containment asks "can the guest name this?", and the rootfs's own
	 * host path names the guest's /etc/probe. It is an alias for an
	 * inode the guest already has, not an escape. */
	snprintf(esc, sizeof esc, "/proc/%d/root%s/etc/probe", (int)child,
		 v.root);
	long alias = th_sys(TAWC_SYS_openat, AT_FDCWD, esc, O_RDONLY, 0, 0, 0);
	test_true(alias >= 0);
	test_int_eq(close((int)alias), 0);

	/* Reading the bare link is unchanged — cross-process /proc stays
	 * kernel-verbatim (notes/tawcroot/status.md "Known gaps"). */
	char link[64], buf[64] = {0};
	snprintf(link, sizeof link, "/proc/%d/root", (int)child);
	test_int_eq(th_sys(TAWC_SYS_readlinkat, AT_FDCWD, link,
			   buf, sizeof buf - 1, 0, 0), 1);
	test_str_eq(buf, "/");

	test_int_eq(kill(child, SIGKILL), 0);
	test_int_eq(waitpid(child, NULL, 0), child);
	th_teardown(&v);
}

test(hosted_proc_cwd_link_contained)
{
	th_view v;
	th_setup(&v, "proc-cwdlink");
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	/* cwd inside the view: the kernel's own resolution is already
	 * right, so traversal works. */
	char p[4300];
	snprintf(p, sizeof p, "%s/etc", v.root);
	test_int_eq(chdir(p), 0);
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/self/cwd/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);

	/* cwd outside the view: refuse rather than hand over the host tree
	 * (same stance as readlink of the cwd link, and as getcwd). */
	test_int_eq(chdir("/"), 0);
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, "/proc/self/cwd/etc",
			   O_RDONLY | O_DIRECTORY, 0, 0, 0), TAWC_ENOENT);

	th_teardown(&v);
}

/* Our own fd links are deliberately NOT contained: the guest already
 * holds the fd, so the link grants nothing new (the same line dirfd
 * resolution draws for out-of-view dirfds). This is also what keeps
 * /dev/stdin, /dev/fd/<n> and process substitution working — a pipe's
 * link text isn't a path at all. */
test(hosted_proc_own_fd_links_not_contained)
{
	th_view v;
	th_setup(&v, "proc-ownfd");
	test_int_eq(tawcroot_path_add_bind("/proc", "/proc", 0), 0);

	char link[64];
	char buf[32] = {0};

	/* In-view directory fd, resolving through the link. */
	long dfd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc",
			  O_RDONLY | O_DIRECTORY, 0, 0, 0);
	test_true(dfd >= 0);
	snprintf(link, sizeof link, "/proc/self/fd/%ld/probe", dfd);
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, link, O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_str_eq(buf, "from-rootfs\n");
	test_int_eq(close((int)fd), 0);
	test_int_eq(close((int)dfd), 0);

	/* A pipe: not a path, must not be refused. */
	int pp[2];
	test_int_eq(pipe(pp), 0);
	test_int_eq(write(pp[1], "hi\n", 3), 3);
	snprintf(link, sizeof link, "/proc/self/fd/%d", pp[0]);
	fd = th_sys(TAWC_SYS_openat, AT_FDCWD, link, O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	memset(buf, 0, sizeof buf);
	test_int_eq(read((int)fd, buf, sizeof buf - 1), 3);
	test_str_eq(buf, "hi\n");
	test_int_eq(close((int)fd), 0);
	test_int_eq(close(pp[0]), 0);
	test_int_eq(close(pp[1]), 0);

	/* An fd we hold on an OUT-of-view host file still re-opens through
	 * its own link — the carve-out, stated as a test so a future
	 * tightening has to argue with it. */
	char host[4300];
	snprintf(host, sizeof host, "%s/etc/probe", v.root);
	int hfd = open(host, O_RDONLY | O_CLOEXEC);
	test_true(hfd >= 0);
	snprintf(link, sizeof link, "/proc/self/fd/%d", hfd);
	fd = th_sys(TAWC_SYS_openat, AT_FDCWD, link, O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);
	test_int_eq(close(hfd), 0);

	th_teardown(&v);
}

/* --- chroot emulation ---------------------------------------------------- */

test(hosted_chroot_swaps_root_view)
{
	th_view v;
	th_setup(&v, "chroot");

	size_t reserved_before = tawcroot_n_reserved_fds;
	int old_root = tawcroot_rootfs_fd;

	test_int_eq(th_sys(TAWC_SYS_chroot, "/usr", 0, 0, 0, 0, 0), 0);

	/* New view: /usr is the root, so /lib/probe.so is now the real
	 * directory <rootfs>/usr/lib (not the memoized symlink). */
	test_true(tawcroot_rootfs_fd != old_root);
	test_int_eq((long)tawcroot_n_reserved_fds, (long)reserved_before + 1);

	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/lib/probe.so",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[16] = {0};
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_str_eq(buf, "fake-lib\n");
	test_int_eq(close((int)fd), 0);

	/* The old view's /etc is gone. */
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			   O_RDONLY, 0, 0, 0), TAWC_ENOENT);

	th_teardown(&v);
}

test(hosted_chroot_rebuilds_memo_for_new_root)
{
	th_view v;
	th_setup(&v, "chroot-memo");

	/* Give the new root (/usr) its own well-known symlink:
	 * <rootfs>/usr/lib64 -> lib. Created host-side, pre-chroot. */
	char p[4200];
	snprintf(p, sizeof p, "%s/usr/lib64", v.root);
	test_int_eq(symlink("lib", p), 0);

	test_int_eq(th_sys(TAWC_SYS_chroot, "/usr", 0, 0, 0, 0, 0), 0);

	/* /lib64/probe.so must route through the rebuilt memo to
	 * /lib/probe.so inside the new root. */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/lib64/probe.so",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[16] = {0};
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_str_eq(buf, "fake-lib\n");
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}

test(hosted_chroot_reanchors_binds)
{
	th_view v;
	th_setup(&v, "chroot-bind");
	th_add_bind(&v, "/usr/host");

	/* Pre-chroot: routed under /usr/host. */
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/usr/host/probe.txt",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);

	test_int_eq(th_sys(TAWC_SYS_chroot, "/usr", 0, 0, 0, 0, 0), 0);

	/* Post-chroot the bind dst is re-anchored to /host. */
	fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/host/probe.txt",
		    O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	char buf[16] = {0};
	test_true(read((int)fd, buf, sizeof buf - 1) > 0);
	test_str_eq(buf, "from-bind\n");
	test_int_eq(close((int)fd), 0);

	/* The old dst path no longer routes anywhere. */
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, "/usr/host/probe.txt",
			   O_RDONLY, 0, 0, 0), TAWC_ENOENT);

	th_teardown(&v);
}

test(hosted_chroot_bind_outside_new_root_deactivated)
{
	th_view v;
	th_setup(&v, "chroot-bind2");
	th_add_bind(&v, "/mnt/outside");  /* not under /usr */

	test_int_eq(th_sys(TAWC_SYS_chroot, "/usr", 0, 0, 0, 0, 0), 0);

	/* The bind sat outside the new view: deactivated, not routed. */
	test_int_eq(th_sys(TAWC_SYS_openat, AT_FDCWD, "/mnt/outside/probe.txt",
			   O_RDONLY, 0, 0, 0), TAWC_ENOENT);
	test_int_eq(tawcroot_binds[0].active, 0);

	th_teardown(&v);
}

test(hosted_chroot_failure_leaves_view_untouched)
{
	th_view v;
	th_setup(&v, "chroot-fail");

	int old_root = tawcroot_rootfs_fd;
	size_t reserved_before = tawcroot_n_reserved_fds;

	test_int_eq(th_sys(TAWC_SYS_chroot, "/no/such/dir", 0, 0, 0, 0, 0),
		    TAWC_ENOENT);
	test_int_eq(th_sys(TAWC_SYS_chroot, "/etc/probe", 0, 0, 0, 0, 0),
		    TAWC_ENOTDIR);
	test_int_eq(th_sys(TAWC_SYS_chroot, 0x1000L, 0, 0, 0, 0, 0),
		    TAWC_EFAULT);

	test_int_eq(tawcroot_rootfs_fd, old_root);
	test_int_eq((long)tawcroot_n_reserved_fds, (long)reserved_before);
	long fd = th_sys(TAWC_SYS_openat, AT_FDCWD, "/etc/probe",
			 O_RDONLY, 0, 0, 0);
	test_true(fd >= 0);
	test_int_eq(close((int)fd), 0);

	th_teardown(&v);
}
