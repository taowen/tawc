/* Concurrent tests for the process-global SIGSYS disposition shadow. */
#include <cleat/test.h>
#include <pthread.h>
#include <string.h>
#include "signal_shadow.h"

test(action_default_is_zero)
{
	tawc_sigshadow_reset();
	unsigned char buf[TAWC_KERN_SIGACTION_SIZE];
	memset(buf, 0xab, sizeof buf);
	tawc_sigshadow_action_get(buf);
	for (size_t i = 0; i < sizeof buf; i++)
		test_int_eq(buf[i], 0);
}

test(action_set_then_get_round_trip)
{
	tawc_sigshadow_reset();
	unsigned char in[TAWC_KERN_SIGACTION_SIZE];
	for (size_t i = 0; i < sizeof in; i++) in[i] = (unsigned char)(i + 1);

	tawc_sigshadow_action_set(in);

	unsigned char out[TAWC_KERN_SIGACTION_SIZE];
	tawc_sigshadow_action_get(out);
	for (size_t i = 0; i < sizeof in; i++)
		test_int_eq(out[i], in[i]);
}

test(action_overwrite_replaces_previous)
{
	tawc_sigshadow_reset();
	unsigned char a[TAWC_KERN_SIGACTION_SIZE];
	unsigned char b[TAWC_KERN_SIGACTION_SIZE];
	memset(a, 0x11, sizeof a);
	memset(b, 0x22, sizeof b);

	tawc_sigshadow_action_set(a);
	tawc_sigshadow_action_set(b);

	unsigned char out[TAWC_KERN_SIGACTION_SIZE];
	tawc_sigshadow_action_get(out);
	for (size_t i = 0; i < sizeof out; i++)
		test_int_eq(out[i], 0x22);
}

#define ACTION_WRITERS  4
#define ACTION_READERS  4
#define ACTION_ITERS    50000

static volatile int g_action_stop;

/* Each writer fills the action with bytes whose value is its writer
 * index XORed with a counter, so any torn read combining bytes from
 * two writers will be detectable (bytes won't agree on a single
 * counter modulo writer-id pattern). */
struct writer_arg {
	int idx;
};

static void *action_writer(void *p)
{
	struct writer_arg *a = p;
	unsigned char buf[TAWC_KERN_SIGACTION_SIZE];
	for (int i = 0; i < ACTION_ITERS && !g_action_stop; i++) {
		unsigned char v = (unsigned char)((i & 0x0f) | (a->idx << 4));
		for (size_t k = 0; k < sizeof buf; k++) buf[k] = v;
		tawc_sigshadow_action_set(buf);
	}
	return NULL;
}

struct reader_result {
	int torn_reads;
	int total_reads;
};

static void *action_reader(void *p)
{
	struct reader_result *r = p;
	while (!g_action_stop) {
		unsigned char buf[TAWC_KERN_SIGACTION_SIZE];
		(void)tawc_sigshadow_action_get(buf);
		r->total_reads++;
		/* Every byte must be identical — writers always store a
		 * single-byte fill. If any byte differs from buf[0], the
		 * read tore between two writers' publishes. */
		for (size_t k = 1; k < sizeof buf; k++) {
			if (buf[k] != buf[0]) {
				r->torn_reads++;
				break;
			}
		}
	}
	return NULL;
}

test(action_multithread_seqlock_is_torn_free)
{
	tawc_sigshadow_reset();
	g_action_stop = 0;

	/* Prime the action so readers don't see "unset → zeroed" as a
	 * legitimate all-zero read (which would be torn-free but not
	 * what we want to measure here). */
	unsigned char primer[TAWC_KERN_SIGACTION_SIZE];
	memset(primer, 0x77, sizeof primer);
	tawc_sigshadow_action_set(primer);

	pthread_t            wt[ACTION_WRITERS];
	pthread_t            rt[ACTION_READERS];
	struct writer_arg    wargs[ACTION_WRITERS];
	struct reader_result rresults[ACTION_READERS];

	for (int i = 0; i < ACTION_WRITERS; i++) {
		wargs[i].idx = i;
		int rc = pthread_create(&wt[i], NULL, action_writer, &wargs[i]);
		test_int_eq(rc, 0);
	}
	for (int i = 0; i < ACTION_READERS; i++) {
		rresults[i].torn_reads = 0;
		rresults[i].total_reads = 0;
		int rc = pthread_create(&rt[i], NULL, action_reader, &rresults[i]);
		test_int_eq(rc, 0);
	}

	for (int i = 0; i < ACTION_WRITERS; i++) pthread_join(wt[i], NULL);
	g_action_stop = 1;
	for (int i = 0; i < ACTION_READERS; i++) pthread_join(rt[i], NULL);

	int total = 0, torn = 0;
	for (int i = 0; i < ACTION_READERS; i++) {
		total += rresults[i].total_reads;
		torn  += rresults[i].torn_reads;
	}
	/* Sanity: readers actually ran. */
	test_int_eq(total > 0, 1);
	/* Zero torn reads is the seqlock contract. */
	test_int_eq(torn, 0);
}
