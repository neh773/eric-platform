#!/usr/bin/env bash
# Fresh-install test for the Linux app-image archive.
#
#   packaging/linux/fresh-install-test.sh [ERIC-<version>-linux-x64-app-images.tar.gz]
#
# Extracts the archive into an empty folder, starts "ERIC Gateway" from its launcher with a clean environment
# (no ERIC_*/OIP_* variables, an empty HOME and data folder), then runs web/fresh-install/fresh-install.spec.ts:
# create the administrator in the first-run form, sign out and in, load /admin, /designer and /docs, and deploy a
# Structured Text program with the bundled compiler and scan engine (needs a C linker such as gcc on the machine).
# Without an argument it packs packaging/build/image (run :packaging:gatewayAppImage first).
#
# FRESH_PORT (default 8090) and FRESH_GAN_PORT only matter on a machine that already runs another gateway.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
port="${FRESH_PORT:-8090}"
work="$(mktemp -d "${TMPDIR:-/tmp}/eric-fresh-XXXXXX")"
archive="${1:-}"
if [[ -z "$archive" ]]; then
  archive="$work/linux-app-images.tar.gz"
  tar -C "$repo/packaging/build" -czf "$archive" image
fi
mkdir -p "$work/extract" "$work/home"
tar -xzf "$archive" -C "$work/extract"
launcher="$work/extract/image/ERIC Gateway/bin/ERIC Gateway"
[[ -x "$launcher" ]] || { echo "FAIL: no launcher at $launcher"; exit 1; }
for d in admin web docs; do
  [[ -n "$(ls -A "$work/extract/image/ERIC Gateway/lib/app/$d" 2>/dev/null)" ]] \
    || { echo "FAIL: lib/app/$d is missing from the archive"; exit 1; }
done
for f in plc/eric-plc plc/rusty/plc; do
  [[ -x "$work/extract/image/ERIC Gateway/lib/app/$f" ]] \
    || { echo "FAIL: lib/app/$f is missing or not executable"; exit 1; }
done
if [[ -n "${FRESH_GAN_PORT:-}" ]]; then
  # Only to dodge a port held by another gateway on this machine. A real install has no such file.
  mkdir -p "$work/home/.local/share/eric/data"
  printf 'gatewayNetwork:\n  port: %s\n' "$FRESH_GAN_PORT" > "$work/home/.local/share/eric/data/gateway.yaml"
fi
extra=()
if [[ "$port" != "8090" ]]; then
  extra=(ERIC_HTTP_PORT="$port" ERIC_ADMIN_URL="http://127.0.0.1:$port/admin" ERIC_OPCUA_ENABLED=false)
fi
if curl -s -o /dev/null "http://127.0.0.1:$port/api/v1/health"; then
  echo "FAIL: something already answers on port $port; stop it or set FRESH_PORT"; exit 1
fi
env -i PATH=/usr/bin:/bin HOME="$work/home" "${extra[@]}" "$launcher" >"$work/gateway.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; pkill -f "$work/extract" 2>/dev/null || true' EXIT
for _ in $(seq 1 90); do
  curl -sf "http://127.0.0.1:$port/api/v1/health" >/dev/null && break
  sleep 1
done
curl -sf "http://127.0.0.1:$port/api/v1/health" >/dev/null || { echo "FAIL: gateway did not start"; cat "$work/gateway.log"; exit 1; }
grep -q "Data directory: $work/home/" "$work/gateway.log" || { echo "FAIL: the gateway did not use the fresh data folder"; cat "$work/gateway.log"; exit 1; }
for path in /admin/ /designer/ /docs/; do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port$path")"
  echo "GET $path -> $code"
  [[ "$code" == 200 ]] || { echo "FAIL: $path"; exit 1; }
done
cd "$repo/web"
FRESH_URL="http://127.0.0.1:$port" FRESH_OUT="$work/results" corepack pnpm exec playwright test -c fresh-install/playwright.config.ts
echo "PASS: fresh install ($work)"
