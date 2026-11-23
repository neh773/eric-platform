#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
# Copyright (C) 2026 Nick Hepburn
"""Copy docs/manual into docs-site/manual-generated for the public manual site.

docs/manual stays the single source. This script:
- orders pages the way docs/manual/index.md lists them,
- groups folders (gateway, studio, plc, runtime, examples) into sidebar categories,
- rewrites links that point outside docs/manual to the file on GitHub.

Run from anywhere: python3 scripts/build_manual_site.py
"""
import json
import pathlib
import re
import shutil

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "docs" / "manual"
DST = REPO / "docs-site" / "manual-generated"
GITHUB = "https://github.com/neh773/eric-platform/blob/main/"
SKIP = {"README.md"}  # notes for whoever regenerates screenshots
CATEGORY_LABELS = {
    "gateway": "Gateway admin",
    "studio": "Control Canvas",
    "plc": "PLC",
    "runtime": "Runtime",
    "examples": "Examples",
}
LINK = re.compile(r"(!?)\[([^\]]*)\]\(([^)\s]+)\)")


def page_order() -> list[str]:
    idx = (SRC / "index.md").read_text(encoding="utf-8")
    order: list[str] = []
    for m in re.finditer(r"\]\(([^)#]+\.md)\)", idx):
        if m.group(1) not in order:
            order.append(m.group(1))
    for f in sorted(SRC.rglob("*.md")):
        rel = f.relative_to(SRC).as_posix()
        if rel not in order and rel not in SKIP and rel != "index.md":
            order.append(rel)
    return order


def rewrite_links(text: str, page: pathlib.Path) -> str:
    def fix(m: re.Match) -> str:
        bang, label, target = m.groups()
        if re.match(r"^[a-z]+:|^#|^/", target):
            return m.group(0)
        path, _, anchor = target.partition("#")
        resolved = (page.parent / path).resolve()
        try:
            resolved.relative_to(SRC)
            return m.group(0)  # inside the manual, Docusaurus resolves it
        except ValueError:
            rel = resolved.relative_to(REPO).as_posix()
            url = GITHUB + rel + (f"#{anchor}" if anchor else "")
            return f"{bang}[{label}]({url})"

    # Leave fenced code blocks alone.
    parts = re.split(r"(```.*?```)", text, flags=re.S)
    return "".join(p if p.startswith("```") else LINK.sub(fix, p) for p in parts)


def main() -> None:
    if DST.exists():
        shutil.rmtree(DST)
    DST.mkdir(parents=True)
    shutil.copytree(SRC / "img", DST / "img")
    order = page_order()
    positions = {rel: i + 1 for i, rel in enumerate(order)}
    folders: dict[str, int] = {}
    for rel in order:
        if "/" in rel:
            folders.setdefault(rel.split("/")[0], positions[rel])

    for f in sorted(SRC.rglob("*.md")):
        rel = f.relative_to(SRC).as_posix()
        if rel in SKIP:
            continue
        body = rewrite_links(f.read_text(encoding="utf-8"), f)
        if rel == "index.md":
            front = "---\nslug: /\nsidebar_position: 0\nsidebar_label: Introduction\n---\n\n"
        else:
            front = f"---\nsidebar_position: {positions.get(rel, 999)}\n---\n\n"
        out = DST / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(front + body, encoding="utf-8")

    for folder, pos in folders.items():
        (DST / folder / "_category_.json").write_text(
            json.dumps({"label": CATEGORY_LABELS.get(folder, folder.title()), "position": pos}, indent=2) + "\n",
            encoding="utf-8",
        )
    print(f"manual site: {len(order) + 1} pages -> {DST.relative_to(REPO)}")


if __name__ == "__main__":
    main()
