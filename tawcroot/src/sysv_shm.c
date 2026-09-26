/* System V shared mappings in the app-private rootfs. Kernel VMAs, rather
 * than process-local counters, determine attachment lifetime across fork,
 * exec and abnormal exit. No guest library or application interposition. */
#include <asm/unistd.h>
#include <linux/ipc.h>
#include <asm/shmbuf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include "dispatch.h"
#include "identity.h"
#include "path.h"
#include "raw_sys.h"
#include "tawc_string.h"
#include "usercopy.h"

#define SETS 128
#define MAGIC 0x54534831
#define SHM_DEST 01000
#define SHM_HUGETLB 04000
#define SHM_RDONLY 010000
struct segment { int used, id; unsigned long inode; struct shmid64_ds ds; };
struct table { unsigned magic, next; struct segment segments[SETS]; };
struct mapping { int fd; struct table *table; };
struct dent { unsigned long ino; long off; unsigned short size; unsigned char type; char name[]; };
/* Access is serialized by the table's per-open-description flock. */
static char entries[4096], maps[4096], line[2048];
static unsigned long number(char **text, int radix) {
    unsigned long result = 0;
    for (;;) {
        int c = (unsigned char)**text;
        int d = c >= '0' && c <= '9' ? c-'0' : c >= 'a' && c <= 'f' ? c-'a'+10 : radix;
        if (d >= radix) return result;
        result = result * radix + d; ++*text;
    }
}
static void path_id(char *out, const char *prefix, unsigned long id, const char *suffix) {
    char digits[24]; unsigned n=0, p=0;
    while (*prefix) out[p++]=*prefix++;
    do { digits[n++]='0'+id%10; id/=10; } while (id);
    while (n) out[p++]=digits[--n];
    while (*suffix) out[p++]=*suffix++;
    out[p]=0;
}
static long lock(int fd, int op) { return TAWC_RAW(__NR_flock,fd,op,0,0,0,0); }
static void close_table(struct mapping *m) {
    tawc_munmap(m->table,sizeof(*m->table)); lock(m->fd,8); tawc_close(m->fd);
}
static long open_table(struct mapping *m) {
    long fd=tawc_openat(tawcroot_rootfs_fd,".tawcroot-shm-v1",O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW,0600);
    if (fd<0) return fd;
    long r=lock(fd,2);
    if (!r) r=TAWC_RAW(__NR_ftruncate,fd,sizeof(struct table),0,0,0,0);
    if (r<0) { tawc_close(fd); return r; }
    long ptr=tawc_mmap(0,sizeof(struct table),PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    if (ptr<0 && ptr>=-4095) { tawc_close(fd); return ptr; }
    m->fd=fd; m->table=(void *)ptr;
    if (m->table->magic && m->table->magic!=MAGIC) { close_table(m); return -5; }
    m->table->magic=MAGIC;
    return 0;
}
static long now(void) {
    long stamp[2];
    return TAWC_RAW(__NR_clock_gettime,0,(long)stamp,0,0,0,0)<0 ? 0 : stamp[0];
}
static struct segment *lookup(struct mapping *m,int id) {
    if (id<0) return 0;
    struct segment *s=&m->table->segments[id%SETS];
    return s->used && s->id==id ? s : 0;
}
/* Only mappings of our unique backing inode qualify. Paths are checked too
 * to avoid collisions with unrelated files on other filesystems. */
static int map_line(unsigned long inode, const char *suffix, unsigned long address,
                    unsigned long *length) {
    char *p=line;
    unsigned long start=number(&p,16);
    if (*p++!='-') return 0;
    unsigned long end=number(&p,16);
    for (int field=0; field<3; ++field) {
        while (*p==' ') ++p;
        while (*p && *p!=' ') ++p;
    }
    while (*p==' ') ++p;
    if (number(&p,10)!=inode) return 0;
    while (*p==' ') ++p;
    unsigned len=strlen(p), tail=strlen(suffix);
    if (len<tail || memcmp(p+len-tail,suffix,tail)) return 0;
    if (address && address!=start) return 0;
    *length=end-start; return 1;
}
static long scan_maps(long pid,struct segment *s,unsigned long address,unsigned long *length) {
    char path[64],suffix[64];
    path_id(path,"/proc/",pid,"/maps");
    path_id(suffix,"/.tawcroot-shm-data-",s->id,"");
    long fd=tawc_openat(AT_FDCWD,path,O_RDONLY|O_CLOEXEC,0);
    if (fd<0) return fd;
    long count=0,n; unsigned pos=0; int overflow=0;
    while ((n=tawc_read(fd,maps,sizeof maps))>0) {
        for (long i=0;i<n;++i) {
            if (maps[i]=='\n') {
                line[pos]=0;
                if (!overflow) count+=map_line(s->inode,suffix,address,length);
                pos=0; overflow=0;
            } else if (pos+1<sizeof line) line[pos++]=maps[i];
            else overflow=1;
        }
    }
    tawc_close(fd); return n<0 ? n : count;
}
static long attachments(struct segment *s) {
    long dir=tawc_openat(AT_FDCWD,"/proc",O_RDONLY|O_DIRECTORY|O_CLOEXEC,0);
    if (dir<0) return dir;
    long total=0,n; unsigned long length=0;
    while ((n=TAWC_RAW(__NR_getdents64,dir,(long)entries,sizeof entries,0,0,0))>0) {
        for (long off=0;off<n;) {
            struct dent *d=(void *)(entries+off);
            if (!d->size || off+d->size>n) { total=-5; goto done; }
            off+=d->size;
            char *p=d->name; unsigned long pid=number(&p,10);
            if (!pid || *p) continue;
            long count=scan_maps(pid,s,0,&length);
            /* Never mistake an unreadable same-UID process for zero
             * attachments (e.g. a guest that disabled dumpability). */
            if (count==-13 || count==-1) {
                char path[64]; struct stat st;
                path_id(path,"/proc/",pid,"");
                long statrc=TAWC_RAW(TAWC_SYS_fstatat,AT_FDCWD,(long)path,(long)&st,0,0,0);
                if (statrc==0 && st.st_uid==(unsigned)tawc_getuid()) { total=count; goto done; }
                /* Non-dumpable tasks have root-owned proc directories;
                 * their status still exposes the real UID. */
                path_id(path,"/proc/",pid,"/status");
                long status=tawc_openat(AT_FDCWD,path,O_RDONLY|O_CLOEXEC,0);
                if (status>=0) {
                    long size=tawc_read(status,maps,sizeof maps-1); tawc_close(status);
                    if (size>0) {
                        maps[size]=0;
                        for (long j=0;j+5<size;++j) {
                            if (memcmp(maps+j,"\nUid:",5)) continue;
                            char *uid=maps+j+5;
                            while (*uid==' ' || *uid=='\t') ++uid;
                            if (number(&uid,10)==(unsigned)tawc_getuid()) { total=count; goto done; }
                            break;
                        }
                    }
                }
            }
            /* Unrelated Android UIDs cannot map these 0600 files. */
            if (count>=0) total+=count;
            else if (count!=-2 && count!=-3 && count!=-13 && count!=-1) { total=count; goto done; }
        }
    }
    if (n<0) total=n;
done:
    tawc_close(dir); return total;
}
static long refresh(struct segment *s) {
    long count=attachments(s);
    if (count<0) return count;
    s->ds.shm_nattch=count;
    if (!count && (s->ds.shm_perm.mode&SHM_DEST)) {
        char path[64]; path_id(path,".tawcroot-shm-data-",s->id,"");
        long r=TAWC_RAW(__NR_unlinkat,tawcroot_rootfs_fd,(long)path,0,0,0,0);
        if (r<0 && r!=-2) return r;
        s->used=0;
    }
    return 0;
}
static long access_segment(struct segment *s,int write) {
    tawc_identity id; tawcroot_identity_get(&id);
    if (!id.euid) return 0;
    unsigned mode=s->ds.shm_perm.mode;
    if (id.euid==s->ds.shm_perm.uid || id.euid==s->ds.shm_perm.cuid) mode>>=6;
    else if (id.egid==s->ds.shm_perm.gid || id.egid==s->ds.shm_perm.cgid) mode>>=3;
    return (mode&(write?2:4)) ? 0 : -13;
}
static long shmget_handler(const tawcroot_syscall_args *a,ucontext_t *uc) {
    (void)uc;
    if (a->b<0 || a->b>1073741824 || (a->c&SHM_HUGETLB)) return -22;
    struct mapping m; long r=open_table(&m); if (r<0) return r;
    for (int i=0;i<SETS;++i) {
        struct segment *s=&m.table->segments[i];
        if (s->used && (s->ds.shm_perm.mode&SHM_DEST)) { r=refresh(s); if (r<0) goto done; }
        if (!s->used || !a->a || (s->ds.shm_perm.mode&SHM_DEST) || s->ds.shm_perm.key!=a->a) continue;
        r=(a->c&IPC_CREAT) && (a->c&IPC_EXCL) ? -17 : (unsigned long)a->b>s->ds.shm_segsz ? -22 : 0;
        if (!r && (a->c&0444)) r=access_segment(s,0);
        if (!r && (a->c&0222)) r=access_segment(s,1);
        if (!r) r=s->id;
        goto done;
    }
    if (a->a && !(a->c&IPC_CREAT)) { r=-2; goto done; }
    if (!a->b) { r=-22; goto done; }
    r=-28;
    for (int i=0;i<SETS;++i) {
        struct segment *s=&m.table->segments[i]; if (s->used) continue;
        if (m.table->next>=0x00fffffe) break;
        int id=++m.table->next*SETS+i;
        char path[64]; path_id(path,".tawcroot-shm-data-",id,"");
        long fd=tawc_openat(tawcroot_rootfs_fd,path,O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
        if (fd<0) { r=fd; break; }
        struct stat st;
        r=TAWC_RAW(__NR_ftruncate,fd,a->b,0,0,0,0);
        if (!r) r=TAWC_RAW(__NR_fstat,fd,(long)&st,0,0,0,0);
        tawc_close(fd);
        if (r<0) { TAWC_RAW(__NR_unlinkat,tawcroot_rootfs_fd,(long)path,0,0,0,0); break; }
        memset(s,0,sizeof(*s)); s->used=1; s->id=id; s->inode=st.st_ino;
        tawc_identity identity; tawcroot_identity_get(&identity);
        s->ds.shm_perm.uid=s->ds.shm_perm.cuid=identity.euid;
        s->ds.shm_perm.gid=s->ds.shm_perm.cgid=identity.egid;
        s->ds.shm_perm.key=a->a; s->ds.shm_perm.mode=a->c&0777;
        s->ds.shm_segsz=a->b; s->ds.shm_cpid=tawc_getpid(); s->ds.shm_ctime=now();
        r=id; break;
    }
done: close_table(&m); return r;
}
static long shmat_handler(const tawcroot_syscall_args *a,ucontext_t *uc) {
    (void)uc;
    /* Address replacement and executable segments are not implemented. */
    if (a->b || (a->c&~SHM_RDONLY)) return -22;
    struct mapping m; long r=open_table(&m); if (r<0) return r;
    struct segment *s=lookup(&m,a->a);
    if (!s) { r=-22; goto done; }
    r=refresh(s); if (r<0) goto done;
    if (!s->used) { r=-22; goto done; }
    r=access_segment(s,0);
    if (!r && !(a->c&SHM_RDONLY)) r=access_segment(s,1);
    if (r<0) goto done;
    char path[64]; path_id(path,".tawcroot-shm-data-",s->id,"");
    long fd=tawc_openat(tawcroot_rootfs_fd,path,(a->c&SHM_RDONLY?O_RDONLY:O_RDWR)|O_CLOEXEC|O_NOFOLLOW,0);
    if (fd<0) { r=fd; goto done; }
    r=tawc_mmap(0,s->ds.shm_segsz,PROT_READ|(a->c&SHM_RDONLY?0:PROT_WRITE),MAP_SHARED,fd,0);
    tawc_close(fd);
    if (r>=0 || r<-4095) { s->ds.shm_atime=now(); s->ds.shm_lpid=tawc_getpid(); }
done: close_table(&m); return r;
}
static long shmdt_handler(const tawcroot_syscall_args *a,ucontext_t *uc) {
    (void)uc;
    struct mapping m; long r=open_table(&m); if (r<0) return r;
    r=-22;
    if (!a->a) goto done;
    for (int i=0;i<SETS;++i) {
        struct segment *s=&m.table->segments[i]; if (!s->used) continue;
        unsigned long length=0;
        if (scan_maps(tawc_getpid(),s,a->a,&length)!=1) continue;
        r=tawc_munmap((void *)a->a,s->ds.shm_segsz);
        if (!r) { s->ds.shm_dtime=now(); s->ds.shm_lpid=tawc_getpid(); r=refresh(s); }
        break;
    }
done: close_table(&m); return r;
}
static long shmctl_handler(const tawcroot_syscall_args *a,ucontext_t *uc) {
    (void)uc;
    struct mapping m; long r=open_table(&m); if (r<0) return r;
    struct segment *s=lookup(&m,a->a); int command=a->b&~0x100;
    if (!s) { r=-22; goto done; }
    r=refresh(s); if (r<0) goto done;
    if (!s->used) { r=-22; goto done; }
    if (command==IPC_STAT) {
        r=access_segment(s,0);
        if (!r) r=tawc_copy_to_guest((void *)a->c,&s->ds,sizeof(s->ds));
    } else if (command==IPC_RMID || command==IPC_SET) {
        unsigned uid=tawcroot_identity_euid();
        if (uid && uid!=s->ds.shm_perm.uid && uid!=s->ds.shm_perm.cuid) { r=-1; goto done; }
        if (command==IPC_RMID) { s->ds.shm_perm.mode|=SHM_DEST; r=refresh(s); }
        else {
            struct shmid64_ds ds;
            r=tawc_copy_from_guest(&ds,sizeof(ds),(void *)a->c);
            if (!r) {
                s->ds.shm_perm.uid=ds.shm_perm.uid; s->ds.shm_perm.gid=ds.shm_perm.gid;
                s->ds.shm_perm.mode=(s->ds.shm_perm.mode&~0777)|(ds.shm_perm.mode&0777);
                s->ds.shm_ctime=now();
            }
        }
    } else r=-22;
done: close_table(&m); return r;
}
void tawcroot_sysv_shm_register(void) {
    tawcroot_dispatch_install(__NR_shmget,shmget_handler);
    tawcroot_dispatch_install(__NR_shmat,shmat_handler);
    tawcroot_dispatch_install(__NR_shmdt,shmdt_handler);
    tawcroot_dispatch_install(__NR_shmctl,shmctl_handler);
}
