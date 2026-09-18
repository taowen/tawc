#!/usr/bin/env python3
"""Compare two APKs entry by entry.

The reproducibility check behind plans/reproducible-builds.md: F-Droid
rebuilds a tag and only ships the maintainer-signed APK if its rebuild
matches. This is the same comparison, run before tagging.

Entries are compared by CRC and size, so the APK signing block — which
lives between the entries and the central directory, not in an entry —
is ignored by construction. The v1 (JAR) signature files are skipped
explicitly for the same reason.

    scripts/fdroid/compare-apks.py A.apk B.apk [--all]

Exits 0 when every compared entry matches. `--all` lists matching
entries too; otherwise only the differences are printed.
"""
import argparse
import re
import sys
import zipfile

# v1 signature files: present only after signing, and by design not
# reproducible across signers.
SIGNATURE_RE = re.compile(r"^META-INF/(.*\.(RSA|DSA|EC|SF)|MANIFEST\.MF)$")


def entries(path):
    with zipfile.ZipFile(path) as z:
        return {
            i.filename: (i.CRC, i.file_size)
            for i in z.infolist()
            if not SIGNATURE_RE.match(i.filename)
        }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--all", action="store_true", help="list identical entries too")
    args = ap.parse_args()

    a, b = entries(args.a), entries(args.b)
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    shared = sorted(set(a) & set(b))
    differing = [n for n in shared if a[n] != b[n]]

    for name in only_a:
        print(f"only in {args.a}: {name}")
    for name in only_b:
        print(f"only in {args.b}: {name}")
    for name in differing:
        (crc_a, size_a), (crc_b, size_b) = a[name], b[name]
        size = f"{size_a} vs {size_b} bytes" if size_a != size_b else f"{size_a} bytes, same size"
        print(f"differs: {name} ({size}, crc {crc_a:08x} vs {crc_b:08x})")
    if args.all:
        for name in shared:
            if a[name] == b[name]:
                print(f"identical: {name}")

    bad = len(only_a) + len(only_b) + len(differing)
    print(f"\n{len(shared) - len(differing)} of {len(set(a) | set(b))} entries identical")
    if bad:
        print(f"{bad} differ — not reproducible")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
