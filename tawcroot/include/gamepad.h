#pragma once
#include <sys/stat.h>
/* Optional Android gamepad broker. ENOSYS means this is an ordinary fd/path. */
long tawcroot_gamepad_open(int dirfd, const char *path, int flags);
long tawcroot_gamepad_ioctl(int fd, unsigned int command, long argument);
int tawcroot_gamepad_path_slot(int dirfd, const char *path);
int tawcroot_gamepad_fd_slot(int fd);
void tawcroot_gamepad_stat(struct stat *st, int slot);
