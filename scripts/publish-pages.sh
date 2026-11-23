#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
# Copyright (C) 2026 Nick Hepburn
#
# Maintainer only. Build the manual site from docs/manual and publish it to the
# gh-pages branch, which GitHub Pages serves at https://neh773.github.io/eric-platform/.
# No workflow runs on push; run this by hand after the manual changes.
#
#   scripts/publish-pages.sh path/to/ERIC-Manual-<version>.pdf [--dry-run]
#
# The PDF is copied to /downloads/ on the site. With --dry-run the site is built
# into docs-site/build and nothing is pushed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PDF="${1:?usage: scripts/publish-pages.sh <manual.pdf> [--dry-run]}"
DRY="${2:-}"
[ -f "$PDF" ] || { echo "no such PDF: $PDF" >&2; exit 1; }

cd "$ROOT/docs-site"
mkdir -p static/downloads
cp "$PDF" static/downloads/ERIC-Manual-0.1.0-beta.pdf
npm ci --no-audit --no-fund
npm run build
touch build/.nojekyll
[ "$DRY" = "--dry-run" ] && { echo "built docs-site/build (not published)"; exit 0; }

SRC_COMMIT="$(git -C "$ROOT" rev-parse --short HEAD)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp -a build/. "$TMP/"
cd "$TMP"
git init -q -b gh-pages
git add -A
git -c user.name="$(git -C "$ROOT" config user.name)" -c user.email="$(git -C "$ROOT" config user.email)" \
  commit -q -m "Publish manual site from $SRC_COMMIT"
git push -f "$(git -C "$ROOT" remote get-url origin)" gh-pages:gh-pages
echo "published gh-pages from $SRC_COMMIT"
