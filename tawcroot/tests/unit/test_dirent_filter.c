/* Unit tests for the getdents64 reserved-fd filter
 * (tawcroot/src/dirent_filter.c).
 *
 * Background: the in-handler getdents64 trap exists because glibc's
 * __closefrom_fallback opens /proc/self/fd, getdents64-iterates, and
 * close()s every fd >= start_fd, retrying via lseek(0)+getdents64
 * any pass that closed at least one fd. tawcroot's handle_close lies
 * about closing reserved fds (1000+) so they survive the guest's
 * closefrom — but if the getdents64 stream still mentions them, the
 * "close, retry, close, retry" loop never terminates. The filter
 * compacts those entries out of the dirent buffer when the dirfd
 * resolves to /proc/self/fd or /proc/<pid>/fd. Pacman/gpgme under
 * the in-app installer hangs at 100% CPU without this filter.
 *
 * Pure-function helpers tested here:
 *   - tawcroot_dirent_filter_is_proc_fd_link: gate predicate
 *   - tawcroot_dirent_filter_dname_to_fd: d_name -> fd number
 *   - tawcroot_dirent_filter_dname_is_reserved: per-entry predicate
 *   - tawcroot_dirent_filter_compact: in-place buffer compaction
 *
 * The handler-side glue (readlinkat probe, real getdents64 issue)
 * lives in syscalls_fd.c and isn't tested here — it's just the
 * connect-the-pure-functions layer.
 */

#include <cleat/test.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "dirent_filter.h"

/* --- is_proc_fd_link ------------------------------------------------ */

test(is_proc_fd_link_self_fd_matches)
{
	const char s[] = "/proc/self/fd";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 1);
}

test(is_proc_fd_link_pid_fd_matches)
{
	const char s[] = "/proc/12345/fd";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 1);
}

test(is_proc_fd_link_single_digit_pid_matches)
{
	const char s[] = "/proc/1/fd";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 1);
}

test(is_proc_fd_link_self_alone_does_not_match)
{
	const char s[] = "/proc/self";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_self_fd_extra_path_does_not_match)
{
	/* /proc/self/fd/3 names a specific fd, not the directory. */
	const char s[] = "/proc/self/fd/3";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_pid_fdinfo_does_not_match)
{
	const char s[] = "/proc/123/fdinfo";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_self_status_does_not_match)
{
	const char s[] = "/proc/self/status";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_non_proc_does_not_match)
{
	const char s[] = "/etc/passwd";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_proc_alone_does_not_match)
{
	const char s[] = "/proc";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_truncated_does_not_match)
{
	const char s[] = "/proc/self/f";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

test(is_proc_fd_link_zero_length_does_not_match)
{
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link("", 0), 0);
}

test(is_proc_fd_link_pid_with_letters_does_not_match)
{
	/* Pids are pure digits; /proc/12a3/fd shouldn't match. */
	const char s[] = "/proc/12a3/fd";
	test_int_eq(tawcroot_dirent_filter_is_proc_fd_link(s, sizeof s - 1), 0);
}

/* --- dname_to_fd ---------------------------------------------------- */
/* The close_range emulation walks /proc/self/fd and turns each d_name
 * back into an fd number with this. */

test(dname_to_fd_basics)
{
	int fd = -1;
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("0", &fd), 1);
	test_int_eq(fd, 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("1007", &fd), 1);
	test_int_eq(fd, 1007);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("2147483647", &fd), 1);
	test_int_eq(fd, 2147483647);
}

test(dname_to_fd_rejects_non_numeric)
{
	int fd = -1;
	test_int_eq(tawcroot_dirent_filter_dname_to_fd(".", &fd), 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("..", &fd), 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("", &fd), 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("12a", &fd), 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("+3", &fd), 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("-3", &fd), 0);
	test_int_eq(fd, -1);  /* untouched on rejection */
}

test(dname_to_fd_rejects_past_int_max)
{
	int fd = -1;
	test_int_eq(tawcroot_dirent_filter_dname_to_fd("2147483648", &fd), 0);
	test_int_eq(tawcroot_dirent_filter_dname_to_fd(
		"999999999999999999999", &fd), 0);
	test_int_eq(fd, -1);
}

/* --- dname_is_reserved ---------------------------------------------- */

test(dname_is_reserved_match)
{
	int reserved[] = {1000, 1001, 1002};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("1001", reserved, 3), 1);
}

test(dname_is_reserved_no_match)
{
	int reserved[] = {1000, 1001, 1002};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("3", reserved, 3), 0);
}

test(dname_is_reserved_empty_name)
{
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("", reserved, 1), 0);
}

test(dname_is_reserved_non_digit_rejected)
{
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved(".", reserved, 1), 0);
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("..", reserved, 1), 0);
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("1000a", reserved, 1), 0);
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("a1000", reserved, 1), 0);
}

test(dname_is_reserved_no_leading_sign)
{
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("+1000", reserved, 1), 0);
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("-1000", reserved, 1), 0);
}

test(dname_is_reserved_empty_set)
{
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved("1000", NULL, 0), 0);
}

test(dname_is_reserved_overflow_safe)
{
	/* Astronomical numeric name shouldn't crash; should return 0.
	 * Regression for signed-overflow UB in the accumulator: the
	 * walk must cap before any *10 wraps. */
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved(
	    "999999999999999999999", reserved, 1), 0);
}

test(dname_is_reserved_just_above_int_max)
{
	/* INT_MAX = 2147483647. "2147483648" is one past — must not
	 * match, must not crash, and the multiply leading up to it
	 * (214748364 * 10) must not have wrapped int. */
	int reserved[] = {2147483647};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved(
	    "2147483648", reserved, 1), 0);
}

test(dname_is_reserved_int_max_matches)
{
	int reserved[] = {0x7fffffff};
	test_int_eq(tawcroot_dirent_filter_dname_is_reserved(
	    "2147483647", reserved, 1), 1);
}

/* --- compact: build a synthetic linux_dirent64 buffer and verify ---- */

/* Match the layout in dirent_filter.c. */
#define DIRENT64_NAME_OFF    19

/* Reclen must be a multiple of 8 (kernel guarantees). Emit a single
 * dirent into `buf+off`, return new offset. */
static long emit_dirent(unsigned char *buf, long off, const char *name)
{
	size_t name_len = strlen(name);
	/* d_ino(8) + d_off(8) + d_reclen(2) + d_type(1) + name + NUL,
	 * rounded up to multiple of 8. */
	size_t needed = DIRENT64_NAME_OFF + name_len + 1;
	size_t reclen = (needed + 7) & ~(size_t)7;
	memset(buf + off, 0, reclen);
	uint64_t ino = 1; uint64_t doff = (uint64_t)off + reclen;
	memcpy(buf + off + 0, &ino, 8);
	memcpy(buf + off + 8, &doff, 8);
	uint16_t r = (uint16_t)reclen;
	memcpy(buf + off + 16, &r, 2);
	buf[off + 18] = 4 /*DT_DIR*/;
	memcpy(buf + off + DIRENT64_NAME_OFF, name, name_len);
	/* terminating NUL already from memset */
	return off + (long)reclen;
}

/* Walk a linux_dirent64 buffer of length n, copy d_names into out
 * (NUL-separated, double-NUL terminated). Caller-allocated. */
static void collect_names(const unsigned char *buf, long n, char *out)
{
	long i = 0;
	long o = 0;
	while (i < n) {
		uint16_t reclen;
		memcpy(&reclen, buf + i + 16, 2);
		const char *name = (const char *)(buf + i + DIRENT64_NAME_OFF);
		size_t len = strlen(name);
		memcpy(out + o, name, len + 1);
		o += (long)len + 1;
		i += reclen;
	}
	out[o] = 0;
}

test(compact_drops_reserved_keeps_others)
{
	unsigned char buf[512];
	long n = 0;
	n = emit_dirent(buf, n, ".");
	n = emit_dirent(buf, n, "..");
	n = emit_dirent(buf, n, "0");
	n = emit_dirent(buf, n, "1000"); /* drop */
	n = emit_dirent(buf, n, "5");
	n = emit_dirent(buf, n, "1003"); /* drop */

	int reserved[] = {1000, 1001, 1002, 1003};
	long out_n = tawcroot_dirent_filter_compact(buf, n, reserved, 4);
	test_true(out_n < n);

	char names[256];
	collect_names(buf, out_n, names);

	/* Expect: ., .., 0, 5 (1000 and 1003 gone). */
	test_str_eq(names + 0, ".");
	test_str_eq(names + 2, "..");
	test_str_eq(names + 5, "0");
	test_str_eq(names + 7, "5");
}

test(compact_no_reserved_present_is_identity)
{
	unsigned char buf[256];
	long n = 0;
	n = emit_dirent(buf, n, ".");
	n = emit_dirent(buf, n, "..");
	n = emit_dirent(buf, n, "3");
	n = emit_dirent(buf, n, "9");
	long n_before = n;

	int reserved[] = {1000, 1001};
	long out_n = tawcroot_dirent_filter_compact(buf, n, reserved, 2);
	test_int_eq(out_n, n_before);
}

test(compact_all_reserved_returns_zero)
{
	unsigned char buf[256];
	long n = 0;
	n = emit_dirent(buf, n, "1000");
	n = emit_dirent(buf, n, "1001");

	int reserved[] = {1000, 1001};
	long out_n = tawcroot_dirent_filter_compact(buf, n, reserved, 2);
	test_int_eq(out_n, 0);
}

test(compact_empty_buffer_returns_zero)
{
	unsigned char buf[16];
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_compact(buf, 0, reserved, 1), 0);
}

test(compact_null_buffer_is_identity)
{
	/* NULL buffer with positive length is unsafe to walk; the
	 * function bails out with `n` unchanged. Defensive — the handler
	 * never calls compact() with NULL since real getdents64 returns
	 * the user's buffer. */
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_compact(NULL, 100, reserved, 1), 100);
}

test(compact_no_reserved_set_is_identity)
{
	unsigned char buf[256];
	long n = 0;
	n = emit_dirent(buf, n, "1000");
	n = emit_dirent(buf, n, "1001");
	long n_before = n;

	long out_n = tawcroot_dirent_filter_compact(buf, n, NULL, 0);
	test_int_eq(out_n, n_before);
}

test(compact_malformed_reclen_zero_bails)
{
	/* Zero reclen → infinite loop trap; function should bail and
	 * return original length unchanged. */
	unsigned char buf[64];
	memset(buf, 0, sizeof buf);
	/* d_reclen at offset 16 = 0 (already from memset). */
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_compact(buf, 32, reserved, 1), 32);
}

test(compact_malformed_reclen_overshoots_bails)
{
	unsigned char buf[64];
	memset(buf, 0, sizeof buf);
	uint16_t huge = 200;  /* > buffer length */
	memcpy(buf + 16, &huge, 2);
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_compact(buf, 32, reserved, 1), 32);
}

test(compact_malformed_reclen_too_small_bails)
{
	/* reclen in (0, header+1): the name pointer would land outside
	 * the record (and possibly the buffer). Must bail, not read. */
	unsigned char buf[64];
	memset(buf, 0, sizeof buf);
	uint16_t tiny = 8;
	memcpy(buf + 16, &tiny, 2);
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_compact(buf, 32, reserved, 1), 32);
}

test(compact_name_without_nul_bails)
{
	/* A record whose d_name has no NUL before reclen ends would let
	 * the digit scan run past the record (the buffer is guest memory,
	 * so the kernel's NUL guarantee can be raced away). */
	unsigned char buf[64];
	long n = emit_dirent(buf, 0, "12345");
	memset(buf + DIRENT64_NAME_OFF, '9', (size_t)n - DIRENT64_NAME_OFF);
	int reserved[] = {1000};
	test_int_eq(tawcroot_dirent_filter_compact(buf, n, reserved, 1), n);
}

test(compact_malformed_after_drop_returns_compacted_prefix)
{
	/* Once entries have been dropped, [out, in) holds stale bytes; a
	 * malformed tail must not make the filter hand back the original
	 * length over a half-compacted buffer. It returns the compacted
	 * prefix and drops the malformed tail. */
	unsigned char buf[256];
	long n = 0;
	n = emit_dirent(buf, n, "1000"); /* dropped */
	n = emit_dirent(buf, n, "5");    /* kept, memmoved left */
	long good_end = n;
	uint16_t zero = 0;               /* malformed third record */
	memset(buf + n, 0, 24);
	memcpy(buf + n + 16, &zero, 2);
	n += 24;

	int reserved[] = {1000};
	long out_n = tawcroot_dirent_filter_compact(buf, n, reserved, 1);
	test_int_eq(out_n, good_end - 24); /* just the "5" record */

	char names[64];
	collect_names(buf, out_n, names);
	test_str_eq(names, "5");
}

/* --- repack_legacy: linux_dirent64 → legacy linux_dirent in place ---- */

/* Legacy layout: d_ino(8) d_off(8) d_reclen(2) d_name[]... d_type in
 * the record's LAST byte. Same reclen as the 64 record for the same
 * name — that identity is what makes in-place repack legal, so the
 * tests assert it stays true. */
#define DIRENT_LEGACY_NAME_OFF 18

test(repack_legacy_moves_name_and_type)
{
	unsigned char buf[512];
	long n = 0;
	n = emit_dirent(buf, n, ".");
	n = emit_dirent(buf, n, "some-longer-name.txt");
	n = emit_dirent(buf, n, "5");
	/* Distinct types per record to catch offset mixups. */
	buf[0 + 18] = 4;   /* DT_DIR */
	unsigned char rec1_off = 24;  /* "." → ALIGN(20+1,8) = 24 */
	buf[rec1_off + 18] = 8;  /* DT_REG */
	long rec2_off = rec1_off + ((20 + 20 + 7) & ~7); /* 20-char name */
	buf[rec2_off + 18] = 10; /* DT_LNK */

	uint64_t ino_before, off_before;
	memcpy(&ino_before, buf + rec2_off + 0, 8);
	memcpy(&off_before, buf + rec2_off + 8, 8);

	long out_n = tawcroot_dirent_filter_repack_legacy(buf, n);
	test_int_eq(out_n, n);

	/* Walk as legacy records. */
	long in = 0;
	const char *want_names[] = { ".", "some-longer-name.txt", "5" };
	unsigned char want_types[] = { 4, 8, 10 };
	for (int i = 0; i < 3; i++) {
		test_true(in < n);
		uint16_t reclen;
		memcpy(&reclen, buf + in + 16, 2);
		test_str_eq((const char *)(buf + in + DIRENT_LEGACY_NAME_OFF),
		            want_names[i]);
		test_int_eq(buf[in + reclen - 1], want_types[i]);
		in += reclen;
	}
	test_int_eq(in, n);

	/* d_ino/d_off/d_reclen untouched. */
	uint64_t ino_after, off_after;
	memcpy(&ino_after, buf + rec2_off + 0, 8);
	memcpy(&off_after, buf + rec2_off + 8, 8);
	test_true(ino_before == ino_after);
	test_true(off_before == off_after);
}

test(repack_legacy_exact_fit_name_keeps_nul)
{
	/* namelen 4: 64-record reclen = ALIGN(19+4+1,8) = 24, exactly
	 * packed. After the shift the NUL sits at 18+4=22 and d_type at
	 * 23 — the type byte must not clobber the terminator. */
	unsigned char buf[64];
	long n = emit_dirent(buf, 0, "abcd");
	test_int_eq(n, 24);
	buf[18] = 8; /* DT_REG */

	test_int_eq(tawcroot_dirent_filter_repack_legacy(buf, n), n);
	test_str_eq((const char *)(buf + DIRENT_LEGACY_NAME_OFF), "abcd");
	test_int_eq(buf[22], 0);
	test_int_eq(buf[23], 8);
}

test(repack_legacy_null_and_empty_are_identity)
{
	test_int_eq(tawcroot_dirent_filter_repack_legacy(NULL, 100), 100);
	unsigned char buf[8];
	test_int_eq(tawcroot_dirent_filter_repack_legacy(buf, 0), 0);
}

test(repack_legacy_malformed_first_record_returns_zero)
{
	/* Zero reclen up front: nothing converted, nothing usable —
	 * the caller gets an empty (but well-formed) legacy buffer. */
	unsigned char buf[64];
	memset(buf, 0, sizeof buf);
	test_int_eq(tawcroot_dirent_filter_repack_legacy(buf, 32), 0);
}

test(repack_legacy_malformed_tail_returns_converted_prefix)
{
	/* One good record then garbage: the good record must come back
	 * converted, the 64-layout tail dropped (it would misparse as
	 * legacy records). */
	unsigned char buf[128];
	long good = emit_dirent(buf, 0, "keep");
	buf[18] = 8; /* DT_REG */
	long n = good;
	memset(buf + n, 0, 24);
	uint16_t huge = 500;
	memcpy(buf + n + 16, &huge, 2);
	n += 24;

	long out_n = tawcroot_dirent_filter_repack_legacy(buf, n);
	test_int_eq(out_n, good);
	test_str_eq((const char *)(buf + DIRENT_LEGACY_NAME_OFF), "keep");
	test_int_eq(buf[good - 1], 8);
}
