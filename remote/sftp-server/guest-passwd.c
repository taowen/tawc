/* User/group lookups from the guest's /etc/passwd and /etc/group.
 *
 * sftp-server runs inside a rootfs (under tawcroot), but bionic's
 * getpw* and getgr* never read /etc/passwd: they synthesize Android IDs
 * (uid 1000 would come back as "system", root's home as "/"). These
 * replace them in the static link; build.sh checks that bionic's
 * versions stay out. sftp-server is single-threaded, so the static
 * result buffers are fine.
 */

#include <grp.h>
#include <limits.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#define MAX_LINE 4096
#define MAX_MEMBERS 64

static char pw_line[MAX_LINE];
static struct passwd pw;
static char gr_line[MAX_LINE];
static struct group gr;
static char *gr_members[MAX_MEMBERS + 1];

/* Split `line` in place on ':' into at most `n` fields. */
static int split(char *line, char **f, int n)
{
	int i = 0;
	line[strcspn(line, "\n")] = '\0';
	f[i++] = line;
	for (char *p = line; *p && i < n; p++) {
		if (*p == ':') {
			*p = '\0';
			f[i++] = p + 1;
		}
	}
	return i;
}

static int parse_id(const char *s, unsigned long *out)
{
	char *end;
	if (*s == '\0')
		return 0;
	*out = strtoul(s, &end, 10);
	return *end == '\0';
}

static struct passwd *find_pw(const char *name, uid_t uid)
{
	FILE *f = fopen("/etc/passwd", "re");
	if (f == NULL)
		return NULL;
	struct passwd *found = NULL;
	while (fgets(pw_line, sizeof(pw_line), f) != NULL) {
		char *fl[7];
		unsigned long u, g;
		if (split(pw_line, fl, 7) != 7 || !parse_id(fl[2], &u) || !parse_id(fl[3], &g))
			continue;
		if (name != NULL ? strcmp(fl[0], name) != 0 : u != uid)
			continue;
		memset(&pw, 0, sizeof(pw));
		pw.pw_name = fl[0];
		pw.pw_passwd = fl[1];
		pw.pw_uid = (uid_t)u;
		pw.pw_gid = (gid_t)g;
		pw.pw_gecos = fl[4];
		pw.pw_dir = fl[5];
		pw.pw_shell = fl[6];
		found = &pw;
		break;
	}
	fclose(f);
	return found;
}

static struct group *find_gr(const char *name, gid_t gid)
{
	FILE *f = fopen("/etc/group", "re");
	if (f == NULL)
		return NULL;
	struct group *found = NULL;
	while (fgets(gr_line, sizeof(gr_line), f) != NULL) {
		char *fl[4];
		unsigned long g;
		if (split(gr_line, fl, 4) != 4 || !parse_id(fl[2], &g))
			continue;
		if (name != NULL ? strcmp(fl[0], name) != 0 : g != gid)
			continue;
		int n = 0;
		for (char *m = strtok(fl[3], ","); m != NULL && n < MAX_MEMBERS; m = strtok(NULL, ","))
			gr_members[n++] = m;
		gr_members[n] = NULL;
		gr.gr_name = fl[0];
		gr.gr_passwd = fl[1];
		gr.gr_gid = (gid_t)g;
		gr.gr_mem = gr_members;
		found = &gr;
		break;
	}
	fclose(f);
	return found;
}

struct passwd *getpwuid(uid_t uid) { return find_pw(NULL, uid); }
struct passwd *getpwnam(const char *name) { return find_pw(name, 0); }
struct group *getgrgid(gid_t gid) { return find_gr(NULL, gid); }
struct group *getgrnam(const char *name) { return find_gr(name, 0); }

/* libssh's misc.o references initgroups (unused by sftp-server); ours
 * keeps bionic's grp_pwd.o, and with it the Android-ID lookups, out of
 * the link. Same semantics: `group` plus every /etc/group listing `user`. */
int initgroups(const char *user, gid_t group)
{
	gid_t groups[NGROUPS_MAX];
	int n = 0;
	groups[n++] = group;
	FILE *f = fopen("/etc/group", "re");
	if (f != NULL) {
		char line[MAX_LINE];
		while (n < NGROUPS_MAX && fgets(line, sizeof(line), f) != NULL) {
			char *fl[4];
			unsigned long g;
			if (split(line, fl, 4) != 4 || !parse_id(fl[2], &g) || (gid_t)g == group)
				continue;
			for (char *m = strtok(fl[3], ","); m != NULL; m = strtok(NULL, ",")) {
				if (strcmp(m, user) == 0) {
					groups[n++] = (gid_t)g;
					break;
				}
			}
		}
		fclose(f);
	}
	return setgroups(n, groups);
}
