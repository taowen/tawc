/* Hosted handler-level tests for syscalls_control.c. */

#include <cleat/test.h>

#include <signal.h>
#include <stdint.h>
#include <time.h>
#include <unistd.h>

#include "hosted.h"

#include "errno_neg.h"
#include "sysnr.h"

#if defined(__x86_64__)
/* Legacy getpgrp routes to getpgid(0) — Android's untrusted_app filter
 * RET_TRAPs the x86_64-only getpgrp number, and the -ENOSYS
 * fallthrough used to break bash's job-control init on the in-app
 * terminal's pty. */
test(hosted_getpgrp_routes_to_getpgid)
{
	th_view v;
	th_setup(&v, "ctl-getpgrp");

	test_int_eq(th_sys(TAWC_SYS_getpgrp, 0, 0, 0, 0, 0, 0),
		    (long)getpgid(0));

	th_teardown(&v);
}

/* Legacy time(2) → clock_gettime(CLOCK_REALTIME). Both RET_TRAPped by
 * the real emulator filter (empirical audit: notes/tawcroot/status.md);
 * clock_gettime is allowlisted. Return value and the *tloc write-back
 * must both match wall clock. */
test(hosted_legacy_time_routes_to_clock_gettime)
{
	th_view v;
	th_setup(&v, "ctl-time");

	time_t ref = time(NULL);
	long rv = th_sys(TAWC_SYS_time, 0, 0, 0, 0, 0, 0);
	test_true(rv >= (long)ref && rv <= (long)ref + 2);

	/* With a tloc pointer the same value is copied back to the guest. */
	long tloc = 0;
	long rv2 = th_sys(TAWC_SYS_time, (long)&tloc, 0, 0, 0, 0, 0);
	test_int_eq(rv2, tloc);
	test_true(tloc >= (long)ref);

	th_teardown(&v);
}

/* Legacy alarm(2) → setitimer(ITIMER_REAL). Returns the whole seconds
 * left on the previous timer (partial second rounded up). Ignore
 * SIGALRM so the armed timer can't kill the test if it ever fires. */
test(hosted_legacy_alarm_routes_to_setitimer)
{
	th_view v;
	th_setup(&v, "ctl-alarm");

	struct sigaction old_sa, ign = { 0 };
	ign.sa_handler = SIG_IGN;
	sigaction(SIGALRM, &ign, &old_sa);

	/* No prior timer → 0. Arm a large value so the disarm can't race. */
	test_int_eq(th_sys(TAWC_SYS_alarm, 1000, 0, 0, 0, 0, 0), 0);
	/* seconds is unsigned int in the kernel ABI: garbage in the high
	 * register bits with a low half of 0 must disarm, not arm a
	 * 2^32-second timer. The ~999.99s remainder rounds up to 1000. */
	test_int_eq(th_sys(TAWC_SYS_alarm, 1L << 32, 0, 0, 0, 0, 0), 1000);
	/* Confirm the disarm: no timer left. */
	test_int_eq(th_sys(TAWC_SYS_alarm, 0, 0, 0, 0, 0, 0), 0);

	sigaction(SIGALRM, &old_sa, NULL);
	th_teardown(&v);
}
#endif

/* Unsupported filters must not appear to have installed successfully. */
test(hosted_seccomp_reports_unsupported)
{
	th_view v;
	th_setup(&v, "ctl-seccomp-unsupported");
	for (int op = 0; op < 4; ++op)
		for (int flags = 0; flags < 16; ++flags)
			test_int_eq(th_sys(TAWC_SYS_seccomp, op, flags, NULL, 0, 0, 0),
			            TAWC_ENOSYS);
	for (int mode = 0; mode < 4; ++mode)
		test_int_eq(th_sys(TAWC_SYS_prctl, 22, mode, NULL, 0, 0, 0), TAWC_EINVAL);
	th_teardown(&v);
}
