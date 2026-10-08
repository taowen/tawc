/* Static x86_64 fixture: replace and then disable an altstack, the way
 * Firefox's per-thread altstacks do, and check the kernel followed.
 *
 * sigaltstack is trapped and its SIGSYS handler runs on the current
 * altstack, so the change must be applied for real (x86_64 drops
 * handler edits of uc_stack in that case). Install A, replace with B
 * (handler on A), then a trapped openat must dirty B and not A
 * (exit 60 / 61). Disable (handler on B), read back SS_DISABLE
 * (exit 62), munmap B and openat again: still pointing at B, the
 * SIGSYS frame can't be built and the kernel kills us with SIGSEGV.
 */

#define REGION 0x20000
#define A_OFF  0x8000
#define B_OFF  0x10000
#define ALT_SZ 0x8000

	.text
	.globl _start
	.type  _start, @function
_start:
	mov     (%rsp), %rax
	cmp     $2, %rax
	jl      .Lfail_argc
	mov     16(%rsp), %r12

	/* mmap(NULL, REGION, RW, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0) */
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

	lea     A_OFF(%r13), %rdi
	call    set_alt
	lea     B_OFF(%r13), %rdi
	call    set_alt

	movabs  $0x5a5a5a5a5a5a5a5a, %r14
	mov     %r13, %rdi
	lea     REGION(%r13), %rsi
1:	mov     %r14, (%rdi)
	add     $8, %rdi
	cmp     %rsi, %rdi
	jb      1b

	call    open_marker

	lea     B_OFF(%r13), %rdi
	call    zone_dirty
	test    %rax, %rax
	jz      .Lfail_b_clean
	lea     A_OFF(%r13), %rdi
	call    zone_dirty
	test    %rax, %rax
	jnz     .Lfail_a_dirty

	/* sigaltstack({0, SS_DISABLE, 0}, NULL) */
	movq    $0, ss(%rip)
	movl    $2, ss+8(%rip)
	movq    $0, ss+16(%rip)
	lea     ss(%rip), %rdi
	xor     %rsi, %rsi
	mov     $131, %rax
	syscall
	test    %rax, %rax
	jnz     .Lfail_sas

	xor     %rdi, %rdi
	lea     oss(%rip), %rsi
	mov     $131, %rax
	syscall
	test    %rax, %rax
	jnz     .Lfail_readback
	cmpl    $2, oss+8(%rip)
	jne     .Lfail_readback

	/* munmap(B) */
	lea     B_OFF(%r13), %rdi
	mov     $ALT_SZ, %rsi
	mov     $11, %rax
	syscall
	test    %rax, %rax
	jnz     .Lfail_mmap

	call    open_marker
	mov     $42, %rdi
	jmp     .Lexit

/* sigaltstack({rdi, 0, ALT_SZ}, NULL) */
set_alt:
	mov     %rdi, ss(%rip)
	movl    $0, ss+8(%rip)
	movq    $ALT_SZ, ss+16(%rip)
	lea     ss(%rip), %rdi
	xor     %rsi, %rsi
	mov     $131, %rax
	syscall
	test    %rax, %rax
	jnz     .Lfail_sas
	ret

/* openat-create argv[1] (trapped, path-bearing), then close it */
open_marker:
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
	ret

/* rax = 1 if any word of [rdi, rdi+ALT_SZ) lost the poison */
zone_dirty:
	lea     ALT_SZ(%rdi), %rsi
	xor     %rax, %rax
2:	cmp     %r14, (%rdi)
	je      3f
	mov     $1, %rax
3:	add     $8, %rdi
	cmp     %rsi, %rdi
	jb      2b
	ret

.Lfail_b_clean:
	mov     $60, %rdi
	jmp     .Lexit
.Lfail_a_dirty:
	mov     $61, %rdi
	jmp     .Lexit
.Lfail_readback:
	mov     $62, %rdi
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
