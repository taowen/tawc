/* Unit tests for guest sigaltstack virtualization (src/sigalt.c). */

#include <cleat/test.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>

#include "sigalt.h"

static char g_buf[65536];
static const stack_t DISABLED = { .ss_flags = SS_DISABLE };

static stack_t mk(void *sp, int flags, size_t size)
{
	return (stack_t){ .ss_sp = sp, .ss_flags = flags, .ss_size = size };
}

test(sigalt_big_stack_installed_verbatim)
{
	tawc_sigalt_reset();
	stack_t cur = DISABLED, old;
	stack_t ss = mk(g_buf, 0, TAWC_SIGALT_MIN);
	test_int_eq(tawc_sigalt_check(&cur, 1, &ss, &old), 0);
	test_int_eq(old.ss_flags, SS_DISABLE);
	tawc_sigalt_commit(&cur, &ss);
	test_true(cur.ss_sp == (void *)g_buf);
	test_int_eq(cur.ss_size, TAWC_SIGALT_MIN);
}

test(sigalt_small_stack_substituted_and_read_back)
{
	tawc_sigalt_reset();
	stack_t cur = DISABLED, old;
	stack_t ss = mk(g_buf, 0, TAWC_SIGALT_MIN - 1);
	tawc_sigalt_commit(&cur, &ss);
	test_true(tawc_sigalt_is_slab(cur.ss_sp));
	test_int_eq(cur.ss_size, TAWC_SIGALT_SLOT);

	test_int_eq(tawc_sigalt_check(&cur, 1, NULL, &old), 0);
	test_true(old.ss_sp == (void *)g_buf);
	test_int_eq(old.ss_size, TAWC_SIGALT_MIN - 1);
	test_int_eq(old.ss_flags, 0);

	/* Replacing a substituted stack reuses the slot. */
	void *slot = cur.ss_sp;
	ss = mk(g_buf + 8, 0, TAWC_SIGALT_KERN_MIN);
	tawc_sigalt_commit(&cur, &ss);
	test_true(cur.ss_sp == slot);
}

test(sigalt_validation_matches_kernel)
{
	tawc_sigalt_reset();
	stack_t cur = DISABLED;
	stack_t ss = mk(g_buf, 0, TAWC_SIGALT_KERN_MIN - 1);
	test_int_eq(tawc_sigalt_check(&cur, 1, &ss, NULL), -ENOMEM);
	ss = mk(g_buf, 4, 32768);
	test_int_eq(tawc_sigalt_check(&cur, 1, &ss, NULL), -EINVAL);
	/* SS_DISABLE ignores size. */
	ss = mk(NULL, SS_DISABLE, 0);
	test_int_eq(tawc_sigalt_check(&cur, 1, &ss, NULL), 0);
}

test(sigalt_on_stack_is_eperm_and_reported)
{
	tawc_sigalt_reset();
	stack_t cur = mk(g_buf, 0, 32768), old;
	stack_t ss = mk(NULL, SS_DISABLE, 0);
	uintptr_t sp = (uintptr_t)g_buf + 100;
	test_int_eq(tawc_sigalt_check(&cur, sp, &ss, &old), -EPERM);
	test_int_eq(tawc_sigalt_check(&cur, sp, NULL, &old), 0);
	test_int_eq(old.ss_flags, SS_ONSTACK);
}

test(sigalt_disable_and_exit_free_slots)
{
	tawc_sigalt_reset();
	stack_t small = mk(g_buf, 0, TAWC_SIGALT_KERN_MIN);
	stack_t cur[TAWC_SIGALT_SLOTS + 1];
	for (int i = 0; i < TAWC_SIGALT_SLOTS; i++) {
		cur[i] = DISABLED;
		tawc_sigalt_commit(&cur[i], &small);
		test_true(tawc_sigalt_is_slab(cur[i].ss_sp));
	}
	/* Exhausted: fall back to the guest's own stack. */
	cur[TAWC_SIGALT_SLOTS] = DISABLED;
	tawc_sigalt_commit(&cur[TAWC_SIGALT_SLOTS], &small);
	test_true(cur[TAWC_SIGALT_SLOTS].ss_sp == (void *)g_buf);

	stack_t dis = mk(NULL, SS_DISABLE, 0);
	tawc_sigalt_commit(&cur[0], &dis);
	test_int_eq(cur[0].ss_size, 0);
	tawc_sigalt_thread_exit(&cur[1]);

	stack_t a = DISABLED, b = DISABLED, c = DISABLED;
	tawc_sigalt_commit(&a, &small);
	tawc_sigalt_commit(&b, &small);
	tawc_sigalt_commit(&c, &small);
	test_true(tawc_sigalt_is_slab(a.ss_sp));
	test_true(tawc_sigalt_is_slab(b.ss_sp));
	test_true(a.ss_sp != b.ss_sp);
	test_true(c.ss_sp == (void *)g_buf);
}
