#!/usr/bin/env python3
"""License gate: every resolved Gradle dependency must be in THIRD_PARTY.md, and no runtime
(core) dependency may be GPL/AGPL. Build-tool rows (scope `build`) are checked against the versions
actually pinned in the build (gradle-wrapper.properties, build.gradle.kts plugin, jvmToolchain). Usage: check_licenses.py [--deps FILE]  (default: runs ./gradlew listDependencies)."""
import re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FORBIDDEN = re.compile(r"\b(A?GPL)(?![-\w]*classpath-exception)", re.I)  # LGPL does not match \bGPL

def table():
    rows = {}
    for line in (ROOT / "THIRD_PARTY.md").read_text().splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 4 and line.startswith("|") and not set(cells[0]) <= set("-") and cells[0] != "Artifact":
            rows[(cells[0], cells[1])] = (cells[2], cells[3])
    return rows

def resolved(args):
    if "--deps" in args:
        out = Path(args[args.index("--deps") + 1]).read_text()
    else:
        out = subprocess.run([str(ROOT / "gradlew"), "-q", "listDependencies"], cwd=ROOT, check=True,
                             capture_output=True, text=True).stdout
    deps = set()
    for line in out.splitlines():
        m = re.match(r"DEP (\w+) ([^:]+):([^:]+):(\S+)", line)
        if m: deps.add((m[1], f"{m[2]}:{m[3]}", m[4]))
    return deps

def build_tools():
    """Map THIRD_PARTY build-row artifact -> version pinned in the build files."""
    tools = {}
    props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    m = re.search(r"gradle-([\d.]+)-(?:bin|all)\.zip", props)
    tools["Gradle (wrapper)"] = m[1] if m else None
    kts = (ROOT / "build.gradle.kts").read_text()
    m = re.search(r'kotlin\("jvm"\)\s+version\s+"([^"]+)"', kts)
    tools["org.jetbrains.kotlin.jvm (Gradle plugin)"] = m[1] if m else None
    m = re.search(r"jvmToolchain\((\d+)\)", kts)
    tools["Eclipse Temurin JDK"] = m[1] if m else None
    return tools

def check_build_rows(rows, tools):
    errors = []
    listed = {art: (ver, scope) for (art, ver), (_, scope) in rows.items() if scope == "build"}
    for art, ver in tools.items():
        if ver is None: errors.append(f"could not determine pinned version of build tool {art}")
        elif art not in listed: errors.append(f"build tool {art} {ver} missing from THIRD_PARTY.md")
        elif listed[art][0] != ver: errors.append(f"build tool {art}: THIRD_PARTY.md lists {listed[art][0]}, build pins {ver}")
    for (art, ver), (_, scope) in rows.items():
        if art in tools and scope != "build": errors.append(f"build tool {art} must have scope build, not {scope}")
    for art in listed:
        if art not in tools: errors.append(f"build row {art} is not a known build tool (add a version check or remove the row)")
    return errors

def main(args):
    rows, errors = table(), []
    for (art, ver), (lic, scope) in rows.items():
        if scope == "runtime" and FORBIDDEN.search(lic):
            errors.append(f"forbidden license in core: {art} {ver} ({lic})")
    errors += check_build_rows(rows, build_tools())
    deps = resolved(args)
    if not deps: errors.append("no dependencies resolved; listDependencies output empty?")
    for scope, art, ver in sorted(deps):
        entry = rows.get((art, ver))
        if entry is None:
            errors.append(f"{art}:{ver} ({scope}) missing from THIRD_PARTY.md"); continue
        lic, listed_scope = entry
        if scope == "runtime" and listed_scope != "runtime":
            errors.append(f"{art} is a runtime dependency but listed as {listed_scope}")
        if scope == "runtime" and FORBIDDEN.search(lic):
            errors.append(f"forbidden license in core: {art} ({lic})")
    for e in errors: print("ERROR:", e)
    print(f"{len(deps)} resolved deps, {len(build_tools())} build tools, {len(rows)} listed: {'FAIL' if errors else 'PASS'}")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
