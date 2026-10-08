/* A host-denied statx must return ENOSYS; the next guest exec must work. */
#define TEST_HOST_STATX_TRAP
#include "static_execve_exit42_x86_64.S"
