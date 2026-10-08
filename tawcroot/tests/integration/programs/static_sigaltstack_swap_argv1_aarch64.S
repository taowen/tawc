/* Aarch64 port of static_sigaltstack_swap_argv1_x86_64.S. */

#define REGION 0x20000
#define A_OFF  0x8000
#define B_OFF  0x10000
#define ALT_SZ 0x8000

	.text
	.globl _start
	.type  _start, %function
_start:
	ldr     x0, [sp]
	cmp     x0, #2
	b.lt    .Lfail_argc
	ldr     x19, [sp, #16]

	/* mmap(NULL, REGION, RW, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) */
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

	add     x0, x20, #A_OFF
	bl      set_alt
	add     x0, x20, #B_OFF
	bl      set_alt

	ldr     x21, =0x5a5a5a5a5a5a5a5a
	mov     x9, x20
	add     x10, x20, #REGION
1:	str     x21, [x9], #8
	cmp     x9, x10
	b.lo    1b

	bl      open_marker

	add     x0, x20, #B_OFF
	bl      zone_dirty
	cbz     x0, .Lfail_b_clean
	add     x0, x20, #A_OFF
	bl      zone_dirty
	cbnz    x0, .Lfail_a_dirty

	/* sigaltstack({0, SS_DISABLE, 0}, NULL) */
	ldr     x0, =ss
	str     xzr, [x0]
	mov     x9, #2
	str     x9, [x0, #8]
	str     xzr, [x0, #16]
	mov     x1, #0
	mov     x8, #132
	svc     #0
	cbnz    x0, .Lfail_sas

	mov     x0, #0
	ldr     x1, =oss
	mov     x8, #132
	svc     #0
	cbnz    x0, .Lfail_readback
	ldr     x1, =oss
	ldr     w9, [x1, #8]
	cmp     w9, #2
	b.ne    .Lfail_readback

	/* munmap(B) */
	add     x0, x20, #B_OFF
	mov     x1, #ALT_SZ
	mov     x8, #215
	svc     #0
	cbnz    x0, .Lfail_mmap

	bl      open_marker
	mov     x0, #42
	b       .Lexit

/* sigaltstack({x0, 0, ALT_SZ}, NULL) */
set_alt:
	ldr     x1, =ss
	str     x0, [x1]
	str     xzr, [x1, #8]
	mov     x9, #ALT_SZ
	str     x9, [x1, #16]
	mov     x0, x1
	mov     x1, #0
	mov     x8, #132
	svc     #0
	cbnz    x0, .Lfail_sas
	ret

/* openat-create argv[1] (trapped, path-bearing), then close it */
open_marker:
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
	ret

/* x0 = 1 if any word of [x0, x0+ALT_SZ) lost the poison */
zone_dirty:
	add     x10, x0, #ALT_SZ
	mov     x9, x0
	mov     x0, #0
2:	ldr     x11, [x9], #8
	cmp     x11, x21
	b.eq    3f
	mov     x0, #1
3:	cmp     x9, x10
	b.lo    2b
	ret

.Lfail_b_clean:
	mov     x0, #60
	b       .Lexit
.Lfail_a_dirty:
	mov     x0, #61
	b       .Lexit
.Lfail_readback:
	mov     x0, #62
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
