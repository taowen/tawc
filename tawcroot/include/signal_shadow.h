/* Process-global guest SIGSYS disposition, backed by lock-free atomics.
 * SIGSYS is reserved for the runtime; signal masks use kernel state only. */

#ifndef TAWCROOT_SIGNAL_SHADOW_H
#define TAWCROOT_SIGNAL_SHADOW_H

#include <stddef.h>

/* Size of the kernel struct sigaction at sigsetsize=8 — must match
 * what handle_rt_sigaction copies between guest and shadow. Both
 * x86_64 and aarch64 define SA_RESTORER, so the kernel layout is
 * handler (8) + flags (8) + restorer (8) + mask (8) = 32 on both. */
#if defined(__x86_64__) || defined(__aarch64__)
# define TAWC_KERN_SIGACTION_SIZE 32
#else
# error "unsupported arch"
#endif

/* Process-global sigaction shadow. _get fills `out` with exactly
 * TAWC_KERN_SIGACTION_SIZE bytes from the most recent _set, or all
 * zeros if no guest sigaction(SIGSYS) has ever happened (BSS-zero
 * default — also matches a kernel SIG_DFL action, which is what the
 * guest expects to read back as the pre-call disposition). _set
 * publishes a new TAWC_KERN_SIGACTION_SIZE-byte snapshot. */
void tawc_sigshadow_action_get(unsigned char *out);
void tawc_sigshadow_action_set(const unsigned char *in);

/* Reset the action to SIG_DFL; tests only. */
void tawc_sigshadow_reset(void);

#endif
