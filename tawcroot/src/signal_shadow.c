/* Process-global guest sigaction shadow. SIGSYS masks are not shadowed:
 * SIGSYS is reserved for syscall translation and remains unblocked.
 * Lock-free atomic byte copies and a seqlock keep snapshots consistent. */

#include <stddef.h>
#include <stdint.h>
#include "signal_shadow.h"

_Static_assert(__atomic_always_lock_free(sizeof(uint32_t), 0),
               "sequence atomics must be lock-free");
_Static_assert(__atomic_always_lock_free(1, 0),
               "byte atomics must be lock-free");

static uint32_t      g_action_seq;                        /* even=stable, odd=writing */
static unsigned char g_action[TAWC_KERN_SIGACTION_SIZE];  /* protected by seq */

/* Per-byte relaxed atomic copy. The seqlock guards us from torn
 * VALUES, but each individual load/store must still be a well-defined
 * atomic op or the C memory model considers it a data race. RELAXED
 * is enough — ordering across the buffer is established by the
 * acquire/release pair on g_action_seq. */
static void copy_bytes_atomic_load(unsigned char *dst, const unsigned char *src,
				   size_t n)
{
	for (size_t i = 0; i < n; i++)
		dst[i] = __atomic_load_n(&src[i], __ATOMIC_RELAXED);
}

static void copy_bytes_atomic_store(unsigned char *dst, const unsigned char *src,
				    size_t n)
{
	for (size_t i = 0; i < n; i++)
		__atomic_store_n(&dst[i], src[i], __ATOMIC_RELAXED);
}

static void zero_bytes_atomic(unsigned char *dst, size_t n)
{
	for (size_t i = 0; i < n; i++)
		__atomic_store_n(&dst[i], 0, __ATOMIC_RELAXED);
}

void tawc_sigshadow_action_get(unsigned char *out)
{
	for (;;) {
		uint32_t s1 = __atomic_load_n(&g_action_seq, __ATOMIC_ACQUIRE);
		if (s1 & 1) continue;  /* writer in progress, retry */
		copy_bytes_atomic_load(out, g_action, TAWC_KERN_SIGACTION_SIZE);
		__atomic_thread_fence(__ATOMIC_ACQUIRE);
		uint32_t s2 = __atomic_load_n(&g_action_seq, __ATOMIC_RELAXED);
		if (s1 == s2) return;
		/* writer landed mid-copy, retry */
	}
}

static void action_writer_acquire(uint32_t *out_s)
{
	uint32_t s;
	for (;;) {
		s = __atomic_load_n(&g_action_seq, __ATOMIC_RELAXED);
		if (s & 1) continue;  /* another writer holds the lock */
		uint32_t expected = s;
		if (__atomic_compare_exchange_n(&g_action_seq, &expected,
						s + 1, 0,
						__ATOMIC_ACQUIRE,
						__ATOMIC_RELAXED))
			break;
	}
	/* Order the odd-seq claim store before the caller's data stores
	 * (the seqlock write barrier — see identity.c's
	 * ident_writer_release). Without it a reader can observe fresh
	 * action bytes while both its seq reads still return the stale
	 * even value, accepting a torn copy. x86_64 TSO hides this;
	 * aarch64 does not. */
	__atomic_thread_fence(__ATOMIC_RELEASE);
	*out_s = s;
}

void tawc_sigshadow_action_set(const unsigned char *in)
{
	uint32_t s;
	action_writer_acquire(&s);
	copy_bytes_atomic_store(g_action, in, TAWC_KERN_SIGACTION_SIZE);
	__atomic_store_n(&g_action_seq, s + 2, __ATOMIC_RELEASE);
}

void tawc_sigshadow_reset(void)
{
	uint32_t s;
	action_writer_acquire(&s);
	zero_bytes_atomic(g_action, TAWC_KERN_SIGACTION_SIZE);
	__atomic_store_n(&g_action_seq, s + 2, __ATOMIC_RELEASE);
}
