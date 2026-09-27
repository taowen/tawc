/* SIGSYS dispatch handlers for `execve` / `execveat`.
 *
 * Wires the guest's exec syscalls into exec_handler.h's prepare
 * (build an exec_state memfd, under exec_lock) + commit (re-exec
 * tawcroot with --exec-child, after unlock) pair.
 *
 * The handlers themselves are small adapters: they pull pointers and
 * argv/envp arrays out of the guest's saved registers, copy strings
 * via usercopy (so a wild guest pointer returns -EFAULT instead of
 * faulting the supervisor), and forward to the perform routine.
 *
 * Strings share Linux's stack-derived argument budget. The serialized
 * format still bounds the number of entries by MAX_ARGS / MAX_ENV.
 *
 */

#include <stddef.h>
#include <stdint.h>

#include "arch.h"
#include "dispatch.h"
#include "errno_neg.h"
#include "exec_handler.h"
#include "exec_state.h"
#include "io.h"
#include "loader_exec.h"
#include "loader_map.h"
#include "path.h"
#include "raw_sys.h"
#include "rescue.h"
#include "syscalls_exec.h"
#include "tawc_uapi.h"
#include "usercopy.h"

/* Keep the collection caps in lockstep with the exec_state
 * serialization caps — collecting more than the writer can serialize
 * would E2BIG after the fact. */
#define MAX_ARGS     TAWCROOT_EXEC_STATE_MAX_ARGS
#define MAX_ENV      TAWCROOT_EXEC_STATE_MAX_ENV
#define EXEC_PATH_CAP (16 * 1024)

/* Walk a guest pointer-array (argv or envp), copying each pointed-to
 * NUL-terminated string into a packed buffer and returning a parallel
 * array of pointers into that buffer.
 *
 *   guest_arr:    guest pointer to array of (char *) pointers
 *   strings:      caller-owned packed-string buffer (sized strings_cap)
 *   ptrs:         caller-owned array of (const char *) pointers
 *                 (sized cap+1 for NULL terminator)
 *   cap:          max number of array entries we'll accept
 *   strings_cap:  total bytes available in `strings`
 *
 * Returns the entry count (excluding NULL terminator) on success, or
 * -errno on failure (-E2BIG if too many entries or the packed buffer
 * is exhausted or a string exceeds MAX_ARG_STRLEN, -EFAULT
 * for bad guest pointers). A NULL guest array is treated as an empty
 * list, matching the kernel (Linux permits execve(path, NULL, NULL)). */
static long collect_array(char *const *guest_arr,
                          char *strings, size_t strings_cap,
                          const char **ptrs, int cap,
                          size_t max_string, size_t *used)
{
	if (!guest_arr) {
		/* Kernel-compatible: a NULL argv/envp is an empty list. */
		ptrs[0] = (const char *)0;
		*used = 0;
		return 0;
	}

	size_t off = 0;
	int i = 0;
	for (;;) {
		/* Copy the pointer at guest_arr[i] into local p. */
		uintptr_t p = 0;
		long rc = tawc_copy_from_guest(&p, sizeof p,
		                               (const void *)&guest_arr[i]);
		if (rc < 0) return rc;
		if (p == 0) {
			ptrs[i] = (const char *)0;
			*used = off;
			return i;
		}
		/* `ptrs` holds cap+1 slots: exactly `cap` entries plus the
		 * NULL terminator fit; only a non-NULL entry at index cap
		 * overflows. */
		if (i >= cap) return TAWC_E2BIG;

		/* Copy the string at p. */
		size_t avail = strings_cap > off ? strings_cap - off : 0;
		if (avail == 0) return TAWC_E2BIG;
		size_t want = avail > max_string ? max_string : avail;
		long n = tawc_copy_string_from_guest(strings + off, want,
		                                     (const char *)p);
		if (n < 0) {
			/* A single oversized argv/env string is -E2BIG to the
			 * kernel (MAX_ARG_STRLEN), not the -ENAMETOOLONG our
			 * string copy returns for a too-long path. Reshape it
			 * so the guest sees the errno execve(2) really gives. */
			if (n == TAWC_ENAMETOOLONG) return TAWC_E2BIG;
			return n;
		}
		ptrs[i] = strings + off;
		off += (size_t)n + 1;  /* +1 for the NUL */
		i++;
	}
}

/* Path buffers, pointer arrays, and prepare's metadata staging remain
 * static to fit the handler stack; string storage is mapped per exec.
 * SIGSYS handlers run concurrently on
 * multiple guest threads (sa_mask only masks the trapping thread), and
 * a CLONE_VM child exec'ing while a sibling execs shares this address
 * space, so two concurrent execs could interleave writes into these
 * buffers. Serialize the collect→serialize phase with a spinlock.
 *
 * The lock MUST be released before the execveat commit point — hence
 * the prepare/commit split in exec_handler.h. An earlier revision held
 * it across the execveat ("the winner never unlocks, the address space
 * is replaced anyway"), which is wrong for posix_spawn children:
 * CLONE_VM|CLONE_VFORK shares the parent's memory, so the lock the
 * exec'ing child left set persisted in the parent — and in every later
 * fork snapshot — wedging the family's next exec in this spin forever
 * (Firefox: glxtest posix_spawn leaked the lock, the startup-crash
 * relaunch fork+execve then hung). commit() touches no shared statics,
 * so running it unlocked is safe. */
static volatile int g_exec_lock;

static void exec_lock(void)
{
	while (__atomic_test_and_set(&g_exec_lock, __ATOMIC_ACQUIRE)) {
		/* spin — exec is rare and terminal */
	}
}

static void exec_unlock(void)
{
	__atomic_clear(&g_exec_lock, __ATOMIC_RELEASE);
}

/* Common path: parse arg0 = path pointer, arg1 = argv, arg2 = envp;
 * collect strings; call prepare. Returns the serialized exec_state
 * memfd (>= 0) for the caller to commit after unlocking, or -errno
 * to write back to the guest as the syscall return. */
static long do_exec_path(const char *path,
                         char *const *guest_argv, char *const *guest_envp)
{
	if (!path) return TAWC_EFAULT;

	/* Linux bprm_stack_limits: min(soft stack / 4, 6 MiB), with a
	 * 32-page floor. Query the kernel without libc from SIGSYS. */
	uint64_t stack_limit[2];
	long rc = TAWC_RAW(TAWC_SYS_prlimit64, 0, 3 /* RLIMIT_STACK */,
	                    0, (long)stack_limit, 0, 0);
	if (rc < 0) return rc;
	size_t max_string = 32 * tawcroot_loader_page_size();
	uint64_t limit = stack_limit[0] / 4;
	if (limit > 6 * 1024 * 1024) limit = 6 * 1024 * 1024;
	if (limit < max_string) limit = max_string;
	size_t budget = (size_t)limit;
	long mapping = tawc_mmap(0, budget,
		TAWC_MM_PROT_READ | TAWC_MM_PROT_WRITE,
		TAWC_MM_MAP_PRIVATE | TAWC_MM_MAP_ANON, -1, 0);
	if (tawc_loader_mmap_is_err((uintptr_t)mapping)) return mapping;
	char *strings = (char *)(uintptr_t)mapping;
	size_t argv_bytes = 0, env_bytes = 0;
	static const char *argv_ptrs[MAX_ARGS + 1];
	static const char *envp_ptrs[MAX_ENV + 1];
	long argc = collect_array(guest_argv, strings, budget,
	                          argv_ptrs, MAX_ARGS, max_string, &argv_bytes);
	if (argc < 0) { rc = argc; goto done; }
	long envc = collect_array(guest_envp, strings + argv_bytes,
	                          budget - argv_bytes, envp_ptrs, MAX_ENV,
	                          max_string, &env_bytes);
	if (envc < 0) { rc = envc; goto done; }
	/* Include the pointer charge and AT_EXECFN string, as Linux does. */
	size_t pointer_bytes = ((size_t)(argc ? argc : 1) + (size_t)envc)
	                       * sizeof(char *);
	if (argv_bytes + env_bytes + pointer_bytes + tawc_strlen(path) + 1 > budget) {
		rc = TAWC_E2BIG;
		goto done;
	}
	rc = tawcroot_exec_handler_prepare(path, (int)argc, argv_ptrs, envp_ptrs);
done:
	(void)tawc_munmap(strings, budget);
	return rc;
}

static long do_exec(const void *guest_path,
                    char *const *guest_argv, char *const *guest_envp)
{
	if (!guest_path) return TAWC_EFAULT;

	static char path_buf[EXEC_PATH_CAP];
	long pn = tawc_copy_string_from_guest(path_buf, sizeof path_buf,
	                                      (const char *)guest_path);
	if (pn < 0) return pn;
	return do_exec_path(path_buf, guest_argv, guest_envp);
}

/* execve(path, argv, envp). Both x86_64 (NR 59) and aarch64 (NR 221)
 * have this syscall. The earlier "aarch64 has no execve, glibc uses
 * execveat" claim was wrong — aarch64 does have execve(2), and glibc
 * uses it for plain execve() without dirfd. Validated empirically on
 * Android 14 / kernel 5.4: bash's `exec /bin/true` goes through NR 221
 * (execve), not NR 281 (execveat), so untrapped execve was getting
 * killed by Android's stacked filter while we sat there waiting for
 * an execveat trap that would never come. */
static long handle_execve(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	exec_lock();
	long r = do_exec((const void *)args->a, (char *const *)args->b,
	                 (char *const *)args->c);
	exec_unlock();
	if (r < 0) return r;
	/* commit() returns only on failure; on success it execveats away —
	 * past the rescue wrapper, which would then never put back a mode
	 * it widened to get the binary open (a 4111 sudo). Restore first;
	 * no-op when nothing was widened. */
	tawcroot_rescue_restore();
	return tawcroot_exec_handler_commit((int)r);
}

/* execveat(dirfd, path, argv, envp, flags). Handles the common fexecve(3)
 * shape: execveat(fd, "", argv, envp, AT_EMPTY_PATH), plus dirfd-relative
 * non-empty paths that can be reverse-translated into the rootfs view. */
static long execveat_path(const char *path, const tawcroot_syscall_args *args)
{
	if ((int)args->e & AT_SYMLINK_NOFOLLOW) {
		static char suffix[EXEC_PATH_CAP];
		tawcroot_path_result r = tawcroot_path_translate(path, suffix,
			sizeof suffix, TAWCROOT_PATH_NOFOLLOW, TAWCROOT_PATH_INTENT_READ);
		if (r.err) return r.err;
		char target;
		long n = tawc_readlinkat(r.base_fd, suffix[0] ? suffix : ".", &target, 1);
		if (n >= 0) return TAWC_ELOOP;
		if (n != TAWC_EINVAL) return n;
	}
	return do_exec_path(path, (char *const *)args->c, (char *const *)args->d);
}

static long execveat_locked(const tawcroot_syscall_args *args)
{
	int dirfd = (int)args->a;
	int flags = (int)args->e;

	if (flags & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return TAWC_EINVAL;

	static char guest_path[EXEC_PATH_CAP];
	long pn = tawc_copy_string_from_guest(guest_path, sizeof guest_path,
	                                      (const char *)args->b);
	if (pn < 0) return pn;

	if (guest_path[0] == '/' || (dirfd == AT_FDCWD && guest_path[0])) {
		return execveat_path(guest_path, args);
	}

	if (guest_path[0] == 0 && !(flags & AT_EMPTY_PATH))
		return TAWC_ENOENT;

	static char resolved[EXEC_PATH_CAP];
	long rn = tawcroot_fd_to_guest_abs(dirfd, resolved, sizeof resolved);
	if (rn < 0) return rn;

	if (guest_path[0] != 0) {
		size_t len = (size_t)rn;
		long ar = 0;
		if (len == 0) return TAWC_EINVAL;
		if (resolved[len - 1] != '/')
			ar = tawc_str_append(resolved, sizeof resolved,
			                     &len, "/");
		if (!ar) ar = tawc_str_append(resolved, sizeof resolved,
		                              &len, guest_path);
		if (ar < 0) return ar;
	}

	return execveat_path(resolved, args);
}

static long handle_execveat(const tawcroot_syscall_args *args, ucontext_t *uc)
{
	(void)uc;
	exec_lock();
	long r = execveat_locked(args);
	exec_unlock();
	if (r < 0) return r;
	/* See handle_execve. */
	tawcroot_rescue_restore();
	return tawcroot_exec_handler_commit((int)r);
}

void tawcroot_exec_register(void)
{
	tawcroot_dispatch_install(TAWC_SYS_execve,   handle_execve);
	tawcroot_dispatch_install(TAWC_SYS_execveat, handle_execveat);
}
