/* static_sigaltstack_open_argv1 with an altstack too small for the
 * SIGSYS frame: tawcroot must substitute its own (sigalt.h). */
#define ALT_SIZE 6144
#include "static_sigaltstack_open_argv1_x86_64.S"
