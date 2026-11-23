#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
# Copyright (C) 2026 Nick Hepburn
#
# Run the same build, lint, and tests that CI runs, on your own machine.
# Paste the summary at the end into your pull request.
#
#   scripts/check.sh               # everything below
#   scripts/check.sh web docs      # only some groups
#
# Groups:
#   gradle  ./gradlew build (compile + unit tests for gateway, modules, tools)
#   repo    banned terms, filename collisions, script tests, license check
#   web     pnpm install, npm licenses, lint, typecheck, unit tests
#   plc     cargo test for the PLC engine and the two-second scan smoke
#   docs    the manual site build (docs-site)
#
# Needs: JDK 21, Docker (some gateway and MQTT tests start containers), Python 3.10+
# with pyyaml, Node 22+ with pnpm 10, Rust (stable), and a C compiler.
# A group whose tool is missing is reported as SKIPPED, not passed.
# Not run here (CI runs them on release tags): container images, Playwright
# end-to-end tests, simulator comms, and the load bench.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# pnpm comes from corepack; keep its cache in the user's home, not a system folder.
if [ -z "${COREPACK_HOME:-}" ] || ! { mkdir -p "$COREPACK_HOME" && [ -w "$COREPACK_HOME" ]; } 2>/dev/null; then
  export COREPACK_HOME="$HOME/.cache/node/corepack"
fi

ALL=(gradle repo web plc docs)
if [ "$#" -gt 0 ]; then GROUPS_TO_RUN=("$@"); else GROUPS_TO_RUN=("${ALL[@]}"); fi

declare -a SUMMARY=()
FAILED=0
LOG_DIR="$ROOT/build/check-logs"
mkdir -p "$LOG_DIR"

have() { command -v "$1" >/dev/null 2>&1; }

# step <name> <command...>: run one step, log it, record the result and time.
step() {
  local name="$1"; shift
  local log="$LOG_DIR/${name// /-}.log"
  local start=$SECONDS
  printf '\n==> %s\n' "$name"
  if "$@" >"$log" 2>&1; then
    SUMMARY+=("PASS  $(printf '%4ss' $((SECONDS - start)))  $name")
  else
    tail -n 40 "$log"
    SUMMARY+=("FAIL  $(printf '%4ss' $((SECONDS - start)))  $name   (log: ${log#"$ROOT"/})")
    FAILED=1
  fi
}

skip() { SUMMARY+=("SKIP          $1   ($2)"); }

run_gradle() {
  if ! have java; then skip "gradle build" "install JDK 21"; return; fi
  if ! docker info >/dev/null 2>&1; then
    echo "warning: Docker is not running; tests that start containers will fail" >&2
  fi
  # --continue reports every failing test, not just the first module.
  step "gradle build" ./gradlew build --no-daemon --continue
}

run_repo() {
  if ! have python3; then skip "repo checks" "install Python 3"; return; fi
  step "banned terms" python3 scripts/check_banned_terms.py
  step "filename case collisions" python3 scripts/check_case_collisions.py
  if python3 -c "import yaml" 2>/dev/null; then
    step "script tests" python3 -m unittest discover -s scripts/tests
    step "differences index" python3 scripts/gen_differences_index.py --check
  else
    skip "script tests" "pip install pyyaml==6.0.2"
  fi
  step "third-party licenses" python3 scripts/check_licenses.py
}

run_web() {
  if ! have pnpm; then skip "web" "install Node 22 and pnpm 10"; return; fi
  step "web install" bash -c "cd web && pnpm install --frozen-lockfile"
  step "web dependency licenses" bash -c "cd web && python3 scripts/check_npm_licenses.py"
  step "web lint" bash -c "cd web && pnpm lint"
  step "web typecheck" bash -c "cd web && pnpm typecheck"
  step "web unit tests" bash -c "cd web && pnpm test"
}

run_plc() {
  if ! have cargo; then skip "plc" "install Rust (rustup) and build-essential"; return; fi
  step "plc engine tests" cargo test --manifest-path plc/eric-plc/Cargo.toml --locked
  step "plc scan smoke" bash plc/eric-plc/scripts/smoke.sh
}

run_docs() {
  if ! have npm; then skip "docs site" "install Node 22"; return; fi
  step "docs site build" bash -c "cd docs-site && npm ci --no-audit --no-fund && npm run build"
}

TOTAL_START=$SECONDS
for g in "${GROUPS_TO_RUN[@]}"; do
  case "$g" in
    gradle|repo|web|plc|docs) "run_$g" ;;
    *) echo "unknown group: $g (use: ${ALL[*]})" >&2; exit 2 ;;
  esac
done

printf '\n===== ERIC local check =====\n'
printf '%s\n' "${SUMMARY[@]}"
printf 'Total: %ss on %s, commit %s\n' $((SECONDS - TOTAL_START)) "$(uname -s)" "$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [ "$FAILED" -ne 0 ]; then echo "RESULT: FAIL"; exit 1; fi
echo "RESULT: PASS"
