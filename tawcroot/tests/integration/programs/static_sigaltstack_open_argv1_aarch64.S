/* Aarch64 port of static_sigaltstack_open_argv1_x86_64.S. */

#ifndef ALT_SIZE
# define ALT_SIZE         32768
# define EXPECT_ALT_DIRTY 1
#endif
#define REGION    0x40000
#define ALT_OFF   0x8000
#define STACK_LO  0x20000
#define STACK_TOP 0x30000

	.text
	.globl _start
	.type  _start, %function
_start:
	ldr     x0, [sp]
	cmp     x0, #2
	b.lt    .Lfail_argc
	ldr     x19, [sp, #16]

	/* mmap(NULL, REGION, PROT_READ|PROT_WRITE,
	 *      MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) */
	mov     x0, #0
	mov     x1, #REGION
	mov     x2, #3
	mov     x3, #0x22
	mov     x4, #-1
	mov     x5, #0
	mov     x8, #222
	svc     #0
	cmn     x0, #4096
	b.hi    .Lfail_mmap
	mov     x20, x0

	/* sigaltstack({R+ALT_OFF, 0, ALT_SIZE}, NULL) — itself trapped, so
	 * it runs on the original stack, before the poisoning. */
	ldr     x0, =ss
	add     x9, x20, #ALT_OFF
	str     x9, [x0]
	str     xzr, [x0, #8]
	mov     x9, #ALT_SIZE
	str     x9, [x0, #16]
	mov     x1, #0
	mov     x8, #132
	svc     #0
	cbnz    x0, .Lfail_sas

	ldr     x11, =0x5a5a5a5a5a5a5a5a
	mov     x9, x20
	add     x10, x20, #REGION
1:	str     x11, [x9], #8
	cmp     x9, x10
	b.lo    1b

	add     x9, x20, #STACK_TOP
	mov     sp, x9

	/* the trapped, path-bearing syscall */
	mov     x0, #-100
	mov     x1, x19
	mov     x2, #(1 | 0100 | 01000)
	mov     x3, #0x180
	mov     x8, #56
	svc     #0
	cmp     x0, #0
	b.lt    .Lfail_open
	mov     x8, #57
	svc     #0

	/* sigaltstack(NULL, &oss) reads back what we set */
	mov     x0, #0
	ldr     x1, =oss
	mov     x8, #132
	svc     #0
	cbnz    x0, .Lfail_readback
	ldr     x1, =oss
	ldr     x9, [x1]
	add     x10, x20, #ALT_OFF
	cmp     x9, x10
	b.ne    .Lfail_readback
	ldr     w9, [x1, #8]
	cbnz    w9, .Lfail_readback
	ldr     x9, [x1, #16]
	mov     x10, #ALT_SIZE
	cmp     x9, x10
	b.ne    .Lfail_readback

	/* thread stack must be untouched */
	add     x9, x20, #STACK_LO
	add     x10, x20, #STACK_TOP
2:	ldr     x12, [x9], #8
	cmp     x12, x11
	b.ne    .Lfail_stack_dirty
	cmp     x9, x10
	b.lo    2b

	/* altstack zone: dirty iff the guest's own stack was installed */
	mov     x13, #0
	mov     x9, x20
	add     x10, x20, #STACK_LO
3:	ldr     x12, [x9], #8
	cmp     x12, x11
	b.eq    4f
	mov     x13, #1
4:	cmp     x9, x10
	b.lo    3b
#ifdef EXPECT_ALT_DIRTY
	cbz     x13, .Lfail_alt
#else
	cbnz    x13, .Lfail_alt
#endif
	mov     x0, #42
	b       .Lexit

.Lfail_stack_dirty:
	mov     x0, #50
	b       .Lexit
.Lfail_alt:
	mov     x0, #51
	b       .Lexit
.Lfail_readback:
	mov     x0, #52
	b       .Lexit
.Lfail_sas:
	mov     x0, #53
	b       .Lexit
.Lfail_open:
	mov     x0, #54
	b       .Lexit
.Lfail_argc:
	mov     x0, #73
	b       .Lexit
.Lfail_mmap:
	mov     x0, #74
.Lexit:
	mov     x8, #94
	svc     #0
	brk     #0

	.size _start, .-_start

	.bss
	.balign 8
ss:
	.skip   24
oss:
	.skip   24

	.section .note.GNU-stack,"",%progbits
