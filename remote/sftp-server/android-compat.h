/* Force-included into every OpenSSH translation unit (see build.sh).
 *
 * _GNU_SOURCE first: includes.h sets it, but this header reaches libc
 * before it does, and bionic decides __USE_BSD/__USE_GNU once. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/* crypt(3): bionic has none. Only openbsd-compat/xcrypt.c (sshd password
 * auth) calls it; sftp-server never references xcrypt.o, so the static
 * link doesn't pull it in and this declaration is never resolved. */
#include <stddef.h>
char *crypt(const char *key, const char *salt);

/* bionic's bzero is a function-like macro over an inline, so it has no
 * address and openbsd-compat can't define it. Drop the macro and let
 * openbsd-compat/bsd-misc.c provide a real function (configure finds no
 * libc symbol, so HAVE_BZERO stays unset). */
#include <strings.h>
#undef bzero
void bzero(void *b, size_t n);
