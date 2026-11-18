#!/usr/bin/env python3
"""Fail if a commercial product name reappears in the tree.

Protocol and PLC names in scripts/banned-terms-allowlist.txt are allowed.
The match is a whole word, so a longer identifier is not a hit.
The term list is stored encoded so the names are not plain text in this file.
"""
import base64
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ALLOW = ROOT / "scripts" / "banned-terms-allowlist.txt"
SKIP = {".git", "build", "node_modules", ".gradle", "dist", ".docusaurus", "__pycache__", "test-results", "playwright-report"}
# Encoded whole words. Do not decode them into this source file.
_ENCODED = (
    "aWduaXRpb24=",
    "aW5kdWN0aXZl",
    "cGVyc3BlY3RpdmU=",
    "ZmFjdG9yeXRhbGs=",
    "d2luY2M=",
    "d29uZGVyd2FyZQ==",
    "YXZldmE=",
    "a2Vwd2FyZQ==",
    "dmlzaW9u",
)


def banned() -> tuple[str, ...]:
    return tuple(base64.b64decode(item).decode("ascii") for item in _ENCODED)


def allowed() -> set[str]:
    words = set()
    for line in ALLOW.read_text(encoding="utf-8").splitlines():
        word = line.split("#", 1)[0].strip().lower()
        if word:
            words.add(word)
    return words


def main() -> int:
    skip_words = allowed()
    terms = [word for word in banned() if word not in skip_words]
    pattern = re.compile(r"\b(" + "|".join(re.escape(word) for word in terms) + r")\b", re.I)
    hits = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(part in SKIP for part in path.parts):
            continue
        if path.name in {"check_banned_terms.py", "banned-terms-allowlist.txt"}:
            continue
        if path.suffix.lower() in {".png", ".jpg", ".jpeg", ".gif", ".webp", ".woff", ".woff2", ".jar", ".zip", ".pdf"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if pattern.search(line):
                hits.append(f"{path.relative_to(ROOT)}:{number}:{line.strip()[:160]}")
                if len(hits) >= 40:
                    break
        if len(hits) >= 40:
            break
    if hits:
        print("banned product terms:")
        print("\n".join(hits))
        return 1
    print("banned terms: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
