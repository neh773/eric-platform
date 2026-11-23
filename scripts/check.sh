#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
# Copyright (C) 2026 Nick Hepburn
#
# The full local check. Required before every push and every release tag (CONTRIBUTING.md, manual release-process).
#
#   scripts/check.sh
#
# 1. Gradle build and every JVM test          5. Linux app image, then the fresh-install test on it
# 2. Repository checks (terms, names, licenses) 6. The new-user UI suite (web/e2e-ui) on that same image:
# 3. eric-plc tests                              every feature through the admin page and Control Canvas forms,
# 4. Web lint, typecheck, unit tests, e2e        plus restart, kill, backup restore and reinstall
#
# Needs JDK 21, Rust (cargo), Node 22 with corepack, Python 3, and network access for the pinned
# Structured Text compiler download. Ports: FRESH_PORT (8196) and ERIC_UI_PORT (8291) must be free.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"
log="$(mktemp -d "${TMPDIR:-/tmp}/eric-check-XXXXXX")"
results=()
step() {
  local name="$1"; shift
  local out="$log/$(echo "$name" | tr ' /' '__').log"
  local started=$SECONDS
  printf '==> %s\n' "$name"
  if "$@" >"$out" 2>&1; then
    results+=("PASS  $name ($((SECONDS - started)) s)")
  else
    results+=("FAIL  $name ($((SECONDS - started)) s), log: $out")
    tail -40 "$out"
    printf '\n%s\n' "${results[@]}"
    echo "check FAILED at: $name"
    exit 1
  fi
}
pnpm() { (cd "$repo/web" && corepack pnpm "$@"); }

step "gradle build and tests" ./gradlew build --no-daemon
step "banned terms" python3 scripts/check_banned_terms.py
step "case collisions" python3 scripts/check_case_collisions.py
step "licenses" python3 scripts/check_licenses.py
step "eric-plc tests" cargo test --manifest-path plc/eric-plc/Cargo.toml --locked
step "web install" pnpm install --frozen-lockfile
step "web lint" pnpm lint
step "web typecheck" pnpm typecheck
step "web unit tests" pnpm test
step "web bundles" bash -c "cd web && corepack pnpm --filter @oip/admin build && corepack pnpm --filter @oip/designer build"
step "gateway installDist" ./gradlew :gateway:server:installDist --no-daemon
step "playwright browser" pnpm exec playwright install chromium
step "web e2e" pnpm test:e2e
step "Linux app image" ./gradlew :packaging:gatewayAppImage :packaging:designerAppImage --no-daemon
step "fresh-install test" env FRESH_PORT="${FRESH_PORT:-8196}" FRESH_GAN_PORT=0 packaging/linux/fresh-install-test.sh
step "new-user UI suite" env ERIC_UI_GATEWAY="$repo/packaging/build/image/ERIC Gateway/bin/ERIC Gateway" bash -c "cd web && corepack pnpm test:ui"

printf '\n%s\n' "${results[@]}"
echo "check PASSED. Logs: $log"
echo "Next: the hands-on new-user pass in docs/testing/release-test-plan.md (fresh Linux and Windows installs) before a release."
