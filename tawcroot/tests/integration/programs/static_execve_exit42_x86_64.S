/* Static x86_64 fixture: execve("/bin/static_exit42", ["static_exit42"], {})
 *
 * Exercises the SIGSYS-handler-driven execve dance under rootfs mode:
 *   - this binary is loaded into the guest by tawcroot's manual loader
 *   - it issues execve(2) directly (not through the stub) so the filter
 *     traps it
 *   - the SIGSYS handler builds a memfd, execveats into /proc/self/exe
 *     with --exec-child <fd>
 *   - the new tawcroot process re-establishes rootfs+handler from
 *     exec_state, then manual-loads /bin/static_exit42 (which lives
 *     inside the rootfs)
 *   - that binary exit_groups(42)
 *
 * If execve returns to us (handler refused, or path didn't translate),
 * we exit with the actual returned errno encoded as 50 + |-errno|. The
 * integration test asserts exit 42 (success), distinct from any of
 * tawcroot's own loader exit codes (60..89) and from the supervised
 * exit codes 90..150 we encode here for failure.
 */

	.text
	.globl _start
	.type  _start, @function
_start:
#ifdef TEST_HOST_STATX_TRAP
	mov $-100, %rdi
	movabs $path, %rsi
	xor %edx, %edx
	mov $0x7ff, %r10
	sub $256, %rsp
	mov %rsp, %r8
	mov $332, %eax
	syscall
	add $256, %rsp
	cmp $-38, %rax
	jne .Lpolicy_fail
#endif
	movabs  $path, %rdi
	movabs  $argv, %rsi
	movabs  $envp, %rdx
	mov     $59, %rax              /* SYS_execve  */
	syscall

	/* execve returned -errno (it shouldn't if the handler completed
	 * a successful re-exec). Encode error: rc = 90..150 = 90 + min(rax_neg, 60). */
	neg     %rax                    /* rax = errno (positive) */
	cmp     $60, %rax
	jbe     .Lcap_ok
	mov     $60, %rax
.Lcap_ok:
	add     $90, %rax
	mov     %rax, %rdi
	mov     $231, %rax              /* exit_group */
	syscall
	ud2
#ifdef TEST_HOST_STATX_TRAP
.Lpolicy_fail:
	mov $99, %edi
	mov $231, %eax
	syscall
	ud2
#endif

	.section .rodata
	/* argv is { path, NULL }. envp is { NULL }. We use a non-NULL
	 * argv[0] so the handler's collect_array doesn't bail early. */
path:
	.asciz  "/bin/static_exit42"
argv:
	.quad   path
	.quad   0
envp:
	.quad   0
	.section .note.GNU-stack,"",@progbits
