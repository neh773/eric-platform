#!/usr/bin/env python3
"""Fail when two tracked paths collide on a case-insensitive filesystem.

A Windows checkout cannot hold both Foo/a and foo/b. A bundler that tries
extensions also treats palette.ts and Palette.tsx as the same module, so an
import of ./Palette can load palette.ts. Stems of those module files are
compared without case. Other extensions collide only when the whole path does.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Extensions a bundler appends to an extensionless import.
MODULE_EXTENSIONS = {
    ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".json", ".vue", ".svelte",
}


def collisions(paths):
    """Return human-readable collisions for repo-relative paths."""
    hits = []
    seen = []
    spellings = {}
    for raw in paths:
        path = raw.replace("\\", "/").strip("/")
        if not path:
            continue
        parts = path.split("/")
        acc = []
        for part in parts:
            acc.append(part)
            actual = "/".join(acc)
            key = actual.casefold()
            previous = spellings.get(key)
            if previous is None:
                spellings[key] = actual
            elif previous != actual:
                pair = tuple(sorted((previous, actual)))
                if pair not in seen:
                    seen.append(pair)
                    hits.append(f"{pair[0]}  and  {pair[1]}")
    groups = {}
    for raw in paths:
        path = raw.replace("\\", "/").strip("/")
        directory, _, name = path.rpartition("/")
        stem, ext = os.path.splitext(name)
        if ext.casefold() not in MODULE_EXTENSIONS or not stem:
            continue
        groups.setdefault((directory.casefold(), stem.casefold()), []).append(path)
    for group in groups.values():
        stems = {os.path.splitext(os.path.basename(path))[0] for path in group}
        if len(stems) < 2:
            continue
        names = "  and  ".join(sorted(group))
        if names not in hits:
            hits.append(names)
    return hits


def tracked_paths():
    out = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [item.decode() for item in out.stdout.split(b"\0") if item]


def main():
    found = collisions(tracked_paths())
    if not found:
        print("case collisions: none")
        return 0
    print("case-only filename collision:", file=sys.stderr)
    for hit in found:
        print(f"  {hit}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
