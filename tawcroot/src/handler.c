/* SIGSYS handler.
 *
 * Async-signal-safe by construction (notes/tawcroot/sigsys-handler.md "Why the handler
 * is async-signal-safe"): no malloc, no stdio, no libc calls with hidden
 * mutable state. Just reads ucontext, optionally inspects siginfo, and
 * writes a return value back into the saved register frame.
 *
 * The recorded `tawcroot_handler_obs` is a single-slot snapshot — one
 * writer per TRAP, plain stores, single-threaded testhost only (see the
 * caveat at its definition). The dispatch table handles production
 * traps; the observation slot stays around as a test/debug hatch.
 *
 * We use rt_sigaction directly because we link `-nostdlib`. The action
 * supplies the restorer required by x86_64, but normal completion resumes
 * the kernel frame directly through the allowlisted raw syscall stub.
 */

#include <stddef.h>
#include <stdint.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/syscall.h>

#include <linux/seccomp.h>

#include "arch.h"
#include "chroot.h"
#include "handler.h"
#include "io.h"
#include "raw_sys.h"
#include "dispatch.h"
#include "errno_neg.h"
#include "rescue.h"
#include "usercopy.h"

extern const char tawcroot_raw_syscall_ret[];

/* Match kernel `struct sigaction` layout — bionic's struct is the same
 * on both arches we care about, but we avoid <signal.h>'s sigaction
 * alias to be explicit about where each field comes from. */
struct kernel_sigaction {
	void (*k_sa_handler)(int, siginfo_t *, void *);
	unsigned long sa_flags;
	void (*sa_restorer)(void);
	uint64_t sa_mask;   /* sigsetsize=8 → exactly one u64 */
};

#ifndef SA_RESTORER
# define SA_RESTORER 0x04000000
#endif
#ifndef SA_ONSTACK
# define SA_ONSTACK 0x08000000
#endif

extern void tawcroot_sigreturn_trampoline(void);
extern __attribute__((noreturn)) void tawcroot_sigreturn_from(uintptr_t sp);

/* Resume through the allowlisted syscall instruction, without another trap
 * for our own signal return. AArch64's frame starts with 128-byte siginfo;
 * x86_64's restorer has already popped the return address, leaving SP at uc. */
static __attribute__((noreturn)) void resume_context(ucontext_t *uc)
{
    *(uint64_t *)&uc->uc_sigmask &= ~((uint64_t)1 << (SIGSYS - 1));
#if defined(__aarch64__)
    tawcroot_sigreturn_from((uintptr_t)uc - 128);
#else
    tawcroot_sigreturn_from((uintptr_t)uc);
#endif
}

static __attribute__((noreturn)) void resume_guest_signal(ucontext_t *trap)
{
    uintptr_t sp = tawcroot_arch_sp(trap);
#if defined(__aarch64__)
    uintptr_t frame_uc = sp + 128;
#else
    uintptr_t frame_uc = sp;
#endif
    void *mask_ptr = (void *)(frame_uc + offsetof(ucontext_t, uc_sigmask));
    uint64_t mask;
    /* Let the kernel diagnose malformed frames. Never restore a mask that
     * blocks syscall translation, including masks edited by a guest JIT. */
    if (tawc_copy_from_guest(&mask, sizeof mask, mask_ptr) == 0) {
        mask &= ~((uint64_t)1 << (SIGSYS - 1));
        if (tawc_copy_to_guest(mask_ptr, &mask, sizeof mask) < 0)
            tawc_exit_group(128 + SIGSEGV);
    }
    tawcroot_sigreturn_from(sp);
}

/* Foundation-smoke debug observation slot. Per review finding D2 the production
 * handler should not write to a mutable process-wide global on every
 * TRAP — that's contention for multi-threaded guests and violates the
 * "no mutable handler state without snapshot rules" principle from
 * notes/tawcroot/sigsys-handler.md "Threading and `vfork` invariants". Gate it behind
 * TAWCROOT_TESTHOST so production stays clean; testhost binaries keep
 * the slot for the smoke driver.
 *
 * Single-threaded-only: writes to g_obs are *not* race-free under a
 * multi-threaded guest. Two threads trapping concurrently will
 * interleave the field stores below, and tawcroot_handler_observe
 * may snapshot a half-updated record. This is acceptable because the
 * existing testhost smoke (smoke.c, child.c, rootfs_smoke.c) is
 * single-threaded by construction. The day someone writes a
 * multi-threaded handler test, this slot needs the same seqlock
 * treatment as the SIGSYS-shadow state in signal_shadow.c — until
 * then the simpler plain-store form keeps the test driver code
 * ergonomic. */
#ifdef TAWCROOT_TESTHOST
static volatile tawcroot_handler_obs g_obs;
#endif

#ifndef SYS_SECCOMP
# define SYS_SECCOMP 1
#endif

static void sigsys_handler(int sig, siginfo_t *info, void *ucontext)
{
	(void)sig;

	/* Only seccomp-delivered SIGSYS carries a syscall frame. A
	 * user-raised SIGSYS (kill/tgkill/raise — not trapped) would have
	 * us read garbage "args" from an interrupted-code ucontext and,
	 * worse, clobber a live register via write_return on resume. The
	 * kernel default for SIGSYS is process death; match it loudly
	 * (notes/tawcroot/sigsys-handler.md "SIGSYS handler": abort if not seccomp). */
	if (info->si_code != SYS_SECCOMP) {
		tawc_io_str("tawcroot: non-seccomp SIGSYS (si_code=");
		tawc_io_dec(info->si_code);
		tawc_io_str("); dying\n");
		tawc_exit_group(128 + SIGSYS);
	}

	ucontext_t *uc = (ucontext_t *)ucontext;
	/* Our filter allows this stub. A trap here comes from an inherited
	 * host policy: report the unavailable syscall without redispatching
	 * into the same host call (which would recurse indefinitely). */
	if (tawcroot_arch_resume_pc(uc) == (uintptr_t)tawcroot_raw_syscall_ret) {
		tawcroot_arch_write_return(uc, TAWC_ENOSYS);
		resume_context(uc);
	}
	tawcroot_syscall_args args;
	tawcroot_arch_read_args(uc, &args);
	/* Do this before claiming any dispatch/rescue scratch: sigreturn does
	 * not return through C and must not leave a live per-thread slot. */
	if (args.nr == TAWC_SYS_rt_sigreturn) resume_guest_signal(uc);

#ifdef TAWCROOT_TESTHOST
	/* siginfo_t fields populated by the kernel for SIGSYS:
	 *   si_signo, si_errno, si_code (== SYS_SECCOMP for our case),
	 *   si_call_addr, si_syscall, si_arch.
	 * Bionic exposes these as macros that expand to
	 * `_sifields._sigsys._call_addr` etc., so don't reuse the names
	 * for locals — the macro hides the field in unpredictable ways. */
	int   sc_code     = info->si_code;
	void *sc_addr     = info->si_call_addr;
	int   sc_syscall  = info->si_syscall;
	int   sc_arch     = info->si_arch;
	(void)sc_syscall;

	/* Update the snapshot. Plain stores; see the single-threaded-only
	 * caveat above — under a multi-threaded testhost driver these
	 * fields would race across CPUs. The volatile qualifier prevents
	 * the compiler from caching reads in the test driver. */
	g_obs.last_nr         = args.nr;
	g_obs.last_arg0       = args.a;
	g_obs.last_call_addr  = (uintptr_t)sc_addr;
	g_obs.last_resume_pc  = tawcroot_arch_resume_pc(uc);
	g_obs.last_si_code    = sc_code;
	g_obs.last_si_arch    = sc_arch;
	g_obs.calls          += 1;
#endif

	/* Dispatch. Empty slots fall through to -ENOSYS — see comment in
	 * include/dispatch.h about Android's stacked filter potentially
	 * delivering TRAPs we didn't ask for. The call goes through the
	 * rescue wrapper (rescue.h), which owns the lazy DAC-override
	 * retry; on everything but an -EACCES at virtual root it is a
	 * plain table lookup plus a slot claim. */
	long rv = tawcroot_fs_sync();
	if (!rv) rv = tawcroot_dispatch_call(&args, uc);
#ifdef TAWCROOT_TRACE
	{
		/* Build the trace line into a stack buffer and emit in one
		 * write so fd 1 / fd 2 don't interleave with our debug output.
		 * Format: "[t] pid=<pid> nr=<nr> a=<a> rv=<rv>\n". */
		char line[96];
		size_t li = 0;
		long pid = TAWC_RAW(TAWC_SYS_getpid, 0, 0, 0, 0, 0, 0);
		(void)tawc_str_append(line, sizeof line, &li, "[t] pid=");
		(void)tawc_str_append_dec(line, sizeof line, &li, pid);
		(void)tawc_str_append(line, sizeof line, &li, " nr=");
		(void)tawc_str_append_dec(line, sizeof line, &li, args.nr);
		(void)tawc_str_append(line, sizeof line, &li, " a=");
		(void)tawc_str_append_dec(line, sizeof line, &li, args.a);
		(void)tawc_str_append(line, sizeof line, &li, " rv=");
		(void)tawc_str_append_dec(line, sizeof line, &li, rv);
		(void)tawc_str_append(line, sizeof line, &li, "\n");
		TAWC_RAW(TAWC_SYS_write, 2, (long)line, (long)li, 0, 0, 0);
	}
#endif
	tawcroot_arch_write_return(uc, rv);
	resume_context(uc);
}

#ifdef TAWCROOT_TESTHOST
void tawcroot_handler_observe(tawcroot_handler_obs *out)
{
	/* Single-reader snapshot. Volatile reads + a compiler barrier are
	 * enough on x86_64 / aarch64 for our purpose: the handler is the
	 * only writer and runs synchronously on the trapping thread. */
	tawcroot_handler_obs s;
	s.calls          = g_obs.calls;
	s.last_nr        = g_obs.last_nr;
	s.last_arg0      = g_obs.last_arg0;
	s.last_call_addr = g_obs.last_call_addr;
	s.last_resume_pc = g_obs.last_resume_pc;
	s.last_si_code   = g_obs.last_si_code;
	s.last_si_arch   = g_obs.last_si_arch;
	__asm__ __volatile__("" ::: "memory");
	*out = s;
}
#endif

long tawcroot_install_handler(void)
{
	struct kernel_sigaction sa;
	sa.k_sa_handler = sigsys_handler;
	/* SA_ONSTACK: land the ~6 KiB signal frame + handler chain on the
	 * guest thread's sigaltstack when it has one; the kernel silently
	 * falls back to the current stack when it doesn't. This is what
	 * lets small-stack runtimes work at all — Go issues syscalls from
	 * 2 KiB goroutine stacks and installs a 32 KiB altstack per M.
	 * AT_MINSIGSTKSZ advertises our additional handler budget. */
	sa.sa_flags     = SA_SIGINFO | SA_RESTORER | SA_ONSTACK | SA_NODEFER;
	sa.sa_restorer  = tawcroot_sigreturn_trampoline;
	/* Allow inherited host-policy traps from the raw syscall stub to
	 * return ENOSYS. Keep other signals blocked: a guest SIGCHLD handler,
	 * for example, must not re-enter translation while it is in progress. */
	sa.sa_mask      = ~(uint64_t)0 & ~((uint64_t)1 << (SIGSYS - 1));

	/* sigsetsize = 8 (size of kernel sigset_t on lp64). */
	return tawc_rt_sigaction(SIGSYS, &sa, NULL, 8);
}
