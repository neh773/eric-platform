#!/usr/bin/env python3
"""Validate docs/agent/FEATURES.md structure.

A `done` row must link at least one test, and every link in its Test(s) cell must
resolve to a test file in this repository. A link is a repo-relative path to a test
file, optionally followed by `#name` (a test name that must appear in that file).
The older bare forms `ClassName` and `ClassName.method` (used by the PRF rows) are
resolved to `ClassName.kt` or `ClassName.java` anywhere in the repository.
"""
import os, re, sys, pathlib
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent.parent
PATH = ROOT / "docs/agent/FEATURES.md"
SKIP_DIRS = {".git", ".gradle", "build", "node_modules", "dist", "test-results", "playwright-report"}
TEST_FILE = re.compile(
    r"(Test|Tests|Contract)\.(kt|java)$"
    r"|\.(test|spec)\.tsx?$"
    r"|(^|/)test_[^/]+\.py$"
    r"|^scripts/[^/]+\.sh$"
)
_sources: dict[str, list[pathlib.Path]] | None = None


def _source_index() -> dict[str, list[pathlib.Path]]:
    """Kotlin and Java files by base name, for the bare `ClassName` link form."""
    global _sources
    if _sources is None:
        _sources = {}
        for dirpath, dirnames, filenames in os.walk(ROOT):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for f in filenames:
                if f.endswith((".kt", ".java")):
                    _sources.setdefault(f.rsplit(".", 1)[0], []).append(pathlib.Path(dirpath, f))
    return _sources


def link_errors(tests: str) -> list[str]:
    """Problems with the test links of a done row; empty when every link resolves."""
    errors = []
    links = [t.strip() for t in tests.split(",") if t.strip()]
    if not links:
        return ["done row links no test"]
    for link in links:
        if "/" in link:
            rel, _, name = link.partition("#")
            path = ROOT / rel
            if not TEST_FILE.search(rel):
                errors.append(f"{rel} is not a test file")
                continue
            if ".." in pathlib.PurePosixPath(rel).parts or not path.is_file():
                errors.append(f"test file {rel} does not exist")
                continue
        else:
            cls, _, name = link.partition(".")
            if not re.fullmatch(r"[A-Z]\w*", cls):
                errors.append(f"{link!r} is neither a test path nor a test class")
                continue
            hits = _source_index().get(cls, [])
            if len(hits) != 1:
                errors.append(f"test class {cls} matches {len(hits)} files")
                continue
            path = hits[0]
        if name and name not in path.read_text(encoding="utf-8", errors="replace"):
            errors.append(f"test {name} not found in {path.relative_to(ROOT)}")
    return errors

MIN_ROWS = 300

def main() -> int:
    if not PATH.is_file():
        print("feature checklist skipped: internal notes are not in this tree")
        return 0
    errors, ids, rows = [], set(), 0
    header_seen = False
    for n, line in enumerate(PATH.read_text(encoding="utf-8").splitlines(), 1):
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if cells[0] == "ID":
            header_seen = True
            if len(cells) != 8:
                errors.append(f"line {n}: header must have 8 columns")
            continue
        if set(cells[0]) <= set("-: "):
            continue
        rows += 1
        if len(cells) != 8:
            errors.append(f"line {n}: expected 8 columns, got {len(cells)}"); continue
        fid, area, feat, src, prio, phase, tests, status = cells
        if not re.fullmatch(r"[A-Z]{3}-\d{3}", fid): errors.append(f"line {n}: bad ID {fid!r}")
        if fid in ids: errors.append(f"line {n}: duplicate ID {fid}")
        ids.add(fid)
        if not area or not feat: errors.append(f"line {n}: empty area/feature")
        if src != "-":
            errors.append(f"line {n}: doc source for {fid} must be '-', not an external manual")
        if prio not in ("P0", "P1", "P2", "P3"): errors.append(f"line {n}: bad priority {prio}")
        if phase not in [str(i) for i in range(9)]: errors.append(f"line {n}: bad phase {phase}")
        if not tests: errors.append(f"line {n}: empty Test(s)")
        if status not in ("todo", "in-progress", "done"): errors.append(f"line {n}: bad status {status}")
        if status == "done" and tests == "-": errors.append(f"line {n}: done without linked tests")
        elif status == "done":
            errors.extend(f"line {n}: {fid}: {e}" for e in link_errors(tests))
    if not header_seen: errors.append("table header not found")
    if rows < MIN_ROWS: errors.append(f"only {rows} rows; need >= {MIN_ROWS}")
    for e in errors: print("ERROR:", e)
    print(f"{rows} rows, {len(ids)} unique IDs: {'FAIL' if errors else 'PASS'}")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main())
