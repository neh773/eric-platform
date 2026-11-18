#!/usr/bin/env bash
# Smoke check for the container run recipes in packaging/compose (doc source: FEATURES GAT-009):
#   1. persistent: state written through the API survives removing and recreating the container;
#   2. ephemeral: the same state is NOT there after the container is recreated;
#   3. restore: a fresh volume restores a sealed backup of recipe 1 on first boot, once.
# Uses throwaway admin and backup passphrases generated here (never written into the repo). Needs docker and curl.
# Run after scripts/image_smoke.sh style builds, or alone: bash scripts/container_recipes_smoke.sh
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
export OIP_PUBLISH_PORT="${OIP_PUBLISH_PORT:-18090}"
project="${OIP_COMPOSE_PROJECT:-oip-recipes}"
work="$(mktemp -d)"
chmod 700 "$work"
base="http://127.0.0.1:${OIP_PUBLISH_PORT}"
current=""

compose() { docker compose -p "$project" -f "packaging/compose/$current.yaml" "$@"; }
cleanup() {
  for f in persistent ephemeral restore; do
    OIP_RESTORE_DIR="$work/restore" docker compose -p "$project" -f "packaging/compose/$f.yaml" down -v >/dev/null 2>&1 || true
  done
  rm -rf "$work"
  unset OIP_ADMIN_PASSWORD || true
}
on_err() {
  local code=$? line="$1"
  echo "container recipes smoke failed at line $line (recipe $current, exit $code): $(sed -n "${line}p" "$0")" >&2
  [ -f "$work/login-response.json" ] && echo "last login response: $(sed 's/"csrfToken":"[^"]*"/"csrfToken":"<hidden>"/' "$work/login-response.json")" >&2
  curl -sS -b "$work/cookies" "$base/api/v1/db/connections" >&2 2>/dev/null || true
  echo >&2
  compose logs >&2 || true
  compose exec -T gateway sh -c 'ls -la /data /restore; cat /data/restored-from-backup.json 2>/dev/null; tail -n 40 /data/logs/*.log 2>/dev/null' >&2 || true
  exit "$code"
}
trap 'on_err $LINENO' ERR
trap cleanup EXIT

gen_password() {
  local candidate
  while true; do
    candidate="$(openssl rand -hex 18)aA1!"
    if ! printf '%s' "$candidate" | grep -Eq '(.)\1\1\1'; then printf '%s' "$candidate"; return; fi
  done
}
export OIP_ADMIN_PASSWORD="$(gen_password)"
passphrase="$(openssl rand -hex 16)"

wait_up() {
  for _ in $(seq 1 60); do
    if curl -fsS "$base/api/v1/health" 2>/dev/null | grep -q '"status":"UP"'; then return 0; fi
    sleep 2
  done
  echo "health did not become UP" >&2
  return 1
}
login() {
  rm -f "$work/cookies"
  printf '{"username":"admin","password":"%s"}\n' "$OIP_ADMIN_PASSWORD" > "$work/login.json"
  curl -fsS -c "$work/cookies" -H 'Content-Type: application/json' --data-binary @"$work/login.json" \
    "$base/api/v1/auth/login" > "$work/login-response.json"
  rm -f "$work/login.json"
  csrf="$(sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p' "$work/login-response.json")"
  test -n "$csrf"
}
connections() { curl -fsS -b "$work/cookies" "$base/api/v1/db/connections"; }
add_connection() {
  curl -fsS -b "$work/cookies" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    --data '{"name":"recipe-marker","driver":"sqlite","url":"jdbc:sqlite::memory:","username":"","description":"recipe smoke"}' \
    "$base/api/v1/db/connections" >/dev/null
}

echo "== persistent recipe"
current=persistent
compose build
compose up -d
wait_up; login; add_connection
connections | grep -q 'recipe-marker'
printf '{"passphrase":"%s"}' "$passphrase" > "$work/pass.json"
curl -fsS -b "$work/cookies" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data-binary @"$work/pass.json" -o "$work/seed.oipbk" "$base/api/v1/backups"
rm -f "$work/pass.json"
test -s "$work/seed.oipbk"
compose down
compose up -d
wait_up; login
connections | grep -q 'recipe-marker'
compose down -v

echo "== ephemeral recipe"
current=ephemeral
compose up -d
wait_up; login; add_connection
connections | grep -q 'recipe-marker'
compose down
compose up -d
wait_up; login
if connections | grep -q 'recipe-marker'; then
  echo "ephemeral gateway kept state across a recreate" >&2
  false
fi
compose down

echo "== restore on first boot"
current=restore
mkdir -p "$work/restore"
cp "$work/seed.oipbk" "$work/restore/gateway.oipbk"
printf '%s\n' "$passphrase" > "$work/restore/passphrase"
chmod 755 "$work/restore"
chmod 644 "$work/restore/gateway.oipbk" "$work/restore/passphrase"
export OIP_RESTORE_DIR="$work/restore"
compose up -d
wait_up; login
connections | grep -q 'recipe-marker'
# A restart with the same mounted file must keep working state and not restore over it.
compose restart gateway
wait_up; login
connections | grep -q 'recipe-marker'
compose down -v
echo "container recipes smoke ok"
