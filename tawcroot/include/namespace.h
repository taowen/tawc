/* Startup compatibility only; Android UID/SELinux remain the security boundary. */
#pragma once
#include <stdint.h>
struct tawc_namespace {
    int32_t init_pid;
    uint32_t user;
    uint32_t capabilities[6];
    int32_t maps[3];
};
extern struct tawc_namespace tawcroot_namespace;
void tawcroot_namespace_register(void);
void tawcroot_namespace_restore(void);
long tawcroot_namespace_open(const char *path, int flags);
int tawcroot_namespace_probe(const char *path);
