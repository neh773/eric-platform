#!/usr/bin/env bash
# Build the gateway image and require /api/v1/health plus diagnostics.
# Diagnostics require gateway.read. This script seeds admin with an ephemeral
# OIP_ADMIN_PASSWORD (never written into the repo), proves the routes are still
# 401 without a session, then logs in and sends the oip_session cookie.
# See docs/ops/http-auth.md.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
export OIP_PUBLISH_PORT="${OIP_PUBLISH_PORT:-18088}"
project="${OIP_COMPOSE_PROJECT:-oip-smoke}"
work="$(mktemp -d)"
chmod 700 "$work"

cleanup() {
  docker compose -p "$project" down -v >/dev/null 2>&1 || true
  rm -rf "$work"
  unset OIP_ADMIN_PASSWORD || true
}
on_err() {
  local code=$?
  docker compose -p "$project" logs >&2 || true
  exit "$code"
}
trap on_err ERR
trap cleanup EXIT

# Default password policy: length >= 8, 3 of 4 character classes, no run of 4
# identical characters, and the value must not contain the username "admin"
# or a form of the word "password". Hex plus a fixed suffix meets that.
gen_password() {
  local candidate
  while true; do
    candidate="$(openssl rand -hex 18)aA1!"
    # bash's =~ (POSIX ERE) has no back-references, so grep does the run check.
    if ! printf '%s' "$candidate" | grep -Eq '(.)\1\1\1'; then
      printf '%s' "$candidate"
      return
    fi
  done
}
docker compose -p "$project" build
export OIP_ADMIN_PASSWORD="$(gen_password)"
docker compose -p "$project" up -d
base="http://127.0.0.1:${OIP_PUBLISH_PORT}"

require_status() {
  local expected="$1" url="$2"
  shift 2
  local code
  code="$(curl -sS -o "$work/body" -w '%{http_code}' "$@" "$url" || true)"
  if [[ "$code" != "$expected" ]]; then
    echo "expected HTTP $expected from $url, got ${code:-curl-failed}" >&2
    if [[ -f "$work/body" ]] && grep -Fq -- "$OIP_ADMIN_PASSWORD" "$work/body"; then
      echo "response body contained the admin password; not printing it" >&2
    else
      cat "$work/body" >&2 || true
      echo >&2
    fi
    return 1
  fi
}

for _ in $(seq 1 40); do
  if curl -fsS "$base/api/v1/health" | tee "$work/health.json" | grep -q '"status":"UP"'; then
    grep -q '"profile":"standard"' "$work/health.json"
    require_status 401 "$base/api/v1/diagnostics/memory"
    if ! grep -q 'urn:oip:problem:unauthorized' "$work/body"; then
      echo "diagnostics/memory 401 was not urn:oip:problem:unauthorized" >&2
      cat "$work/body" >&2 || true
      false
    fi
    require_status 401 "$base/api/v1/diagnostics/threads"
    if ! grep -q 'urn:oip:problem:unauthorized' "$work/body"; then
      echo "diagnostics/threads 401 was not urn:oip:problem:unauthorized" >&2
      cat "$work/body" >&2 || true
      false
    fi
    printf '{"username":"admin","password":"%s"}\n' "$OIP_ADMIN_PASSWORD" > "$work/login.json"
    require_status 200 "$base/api/v1/auth/login" \
      -c "$work/cookies" -H 'Content-Type: application/json' --data-binary @"$work/login.json"
    rm -f "$work/login.json"
    if ! grep -q 'oip_session' "$work/cookies"; then
      echo "login response did not set an oip_session cookie" >&2
      false
    fi
    curl -fsS -b "$work/cookies" "$base/api/v1/diagnostics/memory" | grep -q 'usedBytes'
    curl -fsS -b "$work/cookies" "$base/api/v1/diagnostics/threads" | grep -q '"threads"'
    echo "image smoke ok"
    cat "$work/health.json"
    echo
    exit 0
  fi
  sleep 2
done
echo "health did not become UP" >&2
docker compose -p "$project" logs >&2 || true
exit 1
