#!/usr/bin/env python3
"""Fail if an installed web dependency is missing from THIRD_PARTY.md or is GPL/AGPL.

Walks the pnpm virtual store under web/node_modules/.pnpm. Workspace packages (@oip/*)
are the project itself and are not third-party. Run from anywhere; paths are absolute.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WEB = ROOT / "web"
STORE = WEB / "node_modules" / ".pnpm"
FORBIDDEN = re.compile(r"\b(A?GPL)(?![-\w]*classpath-exception)", re.I)
MARKER_START = "<!-- web-third-party:start -->"
MARKER_END = "<!-- web-third-party:end -->"


def table_rows() -> dict[tuple[str, str], tuple[str, str]]:
    rows: dict[tuple[str, str], tuple[str, str]] = {}
    for line in (ROOT / "THIRD_PARTY.md").read_text().splitlines():
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 4 or cells[0] == "Artifact" or set(cells[0]) <= set("- "):
            continue
        rows[(cells[0], cells[1])] = (cells[2], cells[3])
    return rows


def license_of(meta: dict) -> str:
    value = meta.get("license")
    if isinstance(value, str) and value.strip():
        return value.strip()
    if isinstance(value, dict) and isinstance(value.get("type"), str):
        return value["type"].strip()
    licenses = meta.get("licenses")
    if isinstance(licenses, list) and licenses:
        parts = []
        for entry in licenses:
            if isinstance(entry, str):
                parts.append(entry)
            elif isinstance(entry, dict) and isinstance(entry.get("type"), str):
                parts.append(entry["type"])
        if parts:
            return " OR ".join(parts)
    return ""


def production_names() -> set[str]:
    """Packages reachable from runtime dependencies, keyed by name.

    A missing prod graph is treated as empty so dev-only installs still record scope dev.
    """
    names: set[str] = set()
    for package_dir in (WEB / "hmi-runtime", WEB / "components", WEB / "designer", WEB / "admin", WEB):
        manifest = package_dir / "package.json"
        if not manifest.exists():
            continue
        meta = json.loads(manifest.read_text())
        for name in (meta.get("dependencies") or {}):
            names.add(name)
    # Expand one level of production dependencies from the virtual store so react's
    # runtime transitive packages are classified with it.
    changed = True
    while changed:
        changed = False
        for name, _version, lic, meta in installed():
            if name in names:
                for dep in (meta.get("dependencies") or {}):
                    if dep not in names:
                        names.add(dep)
                        changed = True
            _ = lic
    return names


def installed() -> list[tuple[str, str, str, dict]]:
    found: dict[tuple[str, str], tuple[str, dict]] = {}
    if not STORE.exists():
        return []
    for package_json in STORE.glob("*/node_modules/**/package.json"):
        # <pkg>@ver/node_modules/<name>/package.json or .../node_modules/@scope/<name>/package.json.
        # Skip dependencies nested inside a package.
        relative = package_json.relative_to(STORE)
        if relative.parts[1] != "node_modules" or "node_modules" in relative.parts[2:]:
            continue
        meta = json.loads(package_json.read_text())
        name = meta.get("name")
        version = meta.get("version")
        if not isinstance(name, str) or not isinstance(version, str):
            continue
        if name.startswith("@oip/"):
            continue
        found[(name, version)] = (license_of(meta), meta)
    return [(name, version, lic, meta) for (name, version), (lic, meta) in sorted(found.items())]


def check() -> list[str]:
    rows = table_rows()
    packages = installed()
    errors: list[str] = []
    if not packages:
        errors.append("no pnpm packages found; run pnpm install in web/")
    for name, version, lic, _meta in packages:
        if not lic:
            errors.append(f"{name}@{version} has no license field")
        elif FORBIDDEN.search(lic):
            errors.append(f"forbidden license: {name}@{version} ({lic})")
        if (name, version) not in rows:
            errors.append(f"{name}@{version} ({lic or 'unknown'}) missing from THIRD_PARTY.md")
    return errors


def write_section() -> None:
    prod = production_names()
    lines = [
        MARKER_START,
        "",
        "Direct and transitive packages installed under `web/node_modules`.",
        "Scope `runtime` is pulled in by the HMI bundle; `dev` is build or test tooling.",
        "",
        "| Artifact | Version | License | Scope | Used by |",
        "|---|---|---|---|---|",
    ]
    for name, version, lic, _meta in installed():
        scope = "runtime" if name in prod else "dev"
        lines.append(f"| {name} | {version} | {lic or 'UNKNOWN'} | {scope} | web |")
    lines.append("")
    lines.append(MARKER_END)
    block = "\n".join(lines) + "\n"
    path = ROOT / "THIRD_PARTY.md"
    text = path.read_text()
    if MARKER_START in text and MARKER_END in text:
        start = text.index(MARKER_START)
        end = text.index(MARKER_END) + len(MARKER_END)
        text = text[:start] + block.rstrip() + text[end:]
    else:
        text = text.rstrip() + "\n\n## Web workspace (pnpm)\n\n" + block
    path.write_text(text if text.endswith("\n") else text + "\n")


def main() -> int:
    if "--write" in sys.argv:
        write_section()
        print("wrote THIRD_PARTY.md web section")
        return 0
    errors = check()
    for error in errors:
        print("ERROR:", error)
    print(f"{len(installed())} web packages: {'FAIL' if errors else 'PASS'}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
