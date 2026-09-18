/* Static x86_64 fixture: move SP into a poisoned region, install a
 * sigaltstack, openat-create argv[1] (trapped, full path resolution),
 * then check where the SIGSYS frame landed.
 *
 * Pins SA_ONSTACK on tawcroot's SIGSYS handler: the thread stack below
 * SP must stay untouched (exit 50 otherwise) — that is what keeps Go's
 * 2 KiB goroutine stacks alive. The default 32 KiB altstack must be
 * written to (exit 51); static_sigaltstack_small_open_argv1 includes
 * this file with an undersized ALT_SIZE, which tawcroot must swap for
 * its own stack, leaving the guest buffer clean. Either way
 * sigaltstack(NULL, &old) reads back the guest's values (exit 52).
 */

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
	.type  _start, @function
_start:
	mov     (%rsp), %rax
	cmp     $2, %rax
	jl      .Lfail_argc
	mov     16(%rsp), %r12

	/* mmap(NULL, REGION, PROT_READ|PROT_WRITE,
	 *      MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) */
	xor     %rdi, %rdi
	mov     $REGION, %rsi
	mov     $3, %rdx
	mov     $0x22, %r10
	mov     $-1, %r8
	xor     %r9, %r9
	mov     $9, %rax
	syscall
	cmp     $-4096, %rax
	ja      .Lfail_mmap
	mov     %rax, %r13

	/* sigaltstack({R+ALT_OFF, 0, ALT_SIZE}, NULL) — itself trapped, so
	 * it runs on the original stack, before the poisoning. */
	lea     ALT_OFF(%r13), %rax
	mov     %rax, ss(%rip)
	movq    $0, ss+8(%rip)
	movq    $ALT_SIZE, ss+16(%rip)
	lea     ss(%rip), %rdi
	xor     %rsi, %rsi
	mov     $131, %rax
	syscall
	test    %rax, %rax
	jnz     .Lfail_sas

	movabs  $0x5a5a5a5a5a5a5a5a, %r14
	mov     %r13, %rdi
	lea     REGION(%r13), %rsi
1:	mov     %r14, (%rdi)
	add     $8, %rdi
	cmp     %rsi, %rdi
	jb      1b

	lea     STACK_TOP(%r13), %rsp

	/* the trapped, path-bearing syscall */
	mov     $-100, %rdi
	mov     %r12, %rsi
	mov     $(1 | 0100 | 01000), %rdx
	mov     $0x180, %r10
	mov     $257, %rax
	syscall
	test    %rax, %rax
	js      .Lfail_open
	mov     %rax, %rdi
	mov     $3, %rax
	syscall

	/* sigaltstack(NULL, &oss) reads back what we set */
	xor     %rdi, %rdi
	lea     oss(%rip), %rsi
	mov     $131, %rax
	syscall
	test    %rax, %rax
	jnz     .Lfail_readback
	lea     ALT_OFF(%r13), %rax
	cmp     %rax, oss(%rip)
	jne     .Lfail_readback
	cmpl    $0, oss+8(%rip)
	jne     .Lfail_readback
	cmpq    $ALT_SIZE, oss+16(%rip)
	jne     .Lfail_readback

	/* thread stack must be untouched */
	lea     STACK_LO(%r13), %rdi
	lea     STACK_TOP(%r13), %rsi
2:	cmp     %r14, (%rdi)
	jne     .Lfail_stack_dirty
	add     $8, %rdi
	cmp     %rsi, %rdi
	jb      2b

	/* altstack zone: dirty iff the guest's own stack was installed */
	xor     %rbx, %rbx
	mov     %r13, %rdi
	lea     STACK_LO(%r13), %rsi
3:	cmp     %r14, (%rdi)
	je      4f
	mov     $1, %rbx
4:	add     $8, %rdi
	cmp     %rsi, %rdi
	jb      3b
	test    %rbx, %rbx
#ifdef EXPECT_ALT_DIRTY
	jz      .Lfail_alt
#else
	jnz     .Lfail_alt
#endif
	mov     $42, %rdi
	jmp     .Lexit

.Lfail_stack_dirty:
	mov     $50, %rdi
	jmp     .Lexit
.Lfail_alt:
	mov     $51, %rdi
	jmp     .Lexit
.Lfail_readback:
	mov     $52, %rdi
	jmp     .Lexit
.Lfail_sas:
	mov     $53, %rdi
	jmp     .Lexit
.Lfail_open:
	mov     $54, %rdi
	jmp     .Lexit
.Lfail_argc:
	mov     $73, %rdi
	jmp     .Lexit
.Lfail_mmap:
	mov     $74, %rdi
.Lexit:
	mov     $231, %rax
	syscall
	hlt

	.size _start, .-_start

	.bss
	.balign 8
ss:
	.skip   24
oss:
	.skip   24

	.section .note.GNU-stack,"",%progbits
