#!/usr/bin/env bash
# failoverKeepsEveryTagInsideTheCutoffs
# Two gateway containers. Kill the master. The backup becomes active, keeps every
# tag, and stays inside the 50% CPU, 50% memory, and +/-10% clock-drift cutoffs
# while a write load runs. Memory is the backup container's RSS over its memory limit.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

image="${OIP_GATEWAY_IMAGE:-oip-gateway:local}"
if ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "gateway image $image is not present; build it before this script" >&2
  exit 1
fi

work="$(mktemp -d)"
chmod 700 "$work"
net="oip-failover-$$"
master="oip-failover-master-$$"
backup="oip-failover-backup-$$"
# Hex plus a fixed suffix meets the default password policy, except that a
# random run of 4 identical characters is refused, so draw again until none.
while true; do
  password="$(openssl rand -hex 18)aA1!"
  printf '%s' "$password" | grep -Eq '(.)\1\1\1' || break
done
count=16
limit_bytes=$((2 * 1024 * 1024 * 1024))

cleanup() {
  set +e
  docker rm -f "$master" "$backup" >/dev/null 2>&1
  docker network rm "$net" >/dev/null 2>&1
  # The gateway user owns files under the mount. Remove them as root in the image.
  if [[ -n "${work:-}" && -d "$work" ]]; then
    docker run --rm --user 0 --entrypoint rm -v "$work:/work" "$image" -rf /work >/dev/null 2>&1
    rm -rf "$work"
  fi
  return 0
}
on_err() {
  local code=$?
  echo "redundancy failover failed" >&2
  docker logs "$backup" >&2 || true
  docker logs "$master" >&2 || true
  exit "$code"
}
trap on_err ERR
trap cleanup EXIT

mkdir -p "$work/master" "$work/backup"
chmod 777 "$work/master" "$work/backup"

cat >"$work/master/gateway.yaml" <<'YAML'
profile: standard
opcua:
  enabled: false
gatewayNetwork:
  enabled: true
  name: master
  port: 8060
redundancy:
  mode: master
  startupAllowanceMillis: 0
  standbyActivityLevel: warm
  recoveryMode: automatic
  masterHost: master
  masterPort: 8060
  pingRateMillis: 200
  pingTimeoutMillis: 400
  pingMaxMissed: 3
  connectionName: redundancy
YAML

cat >"$work/backup/gateway.yaml" <<'YAML'
profile: standard
opcua:
  enabled: false
gatewayNetwork:
  enabled: true
  name: backup
  port: 8060
redundancy:
  mode: backup
  startupAllowanceMillis: 120000
  standbyActivityLevel: warm
  recoveryMode: automatic
  masterHost: master
  masterPort: 8060
  pingRateMillis: 200
  pingTimeoutMillis: 400
  pingMaxMissed: 3
  connectionName: redundancy
YAML
chmod 644 "$work/master/gateway.yaml" "$work/backup/gateway.yaml"

docker network create "$net" >/dev/null
run_gateway() {
  local name="$1" alias="$2" port="$3" dir="$4"
  docker run -d --name "$name" --network "$net" --network-alias "$alias" \
    --memory=2g --memory-swap=2g \
    -p "127.0.0.1:${port}:8088" \
    -e OIP_DATA_DIR=/data \
    -e OIP_PROFILE=standard \
    -e OIP_HTTP_HOST=0.0.0.0 \
    -e OIP_HTTP_PORT=8088 \
    -e OIP_ADMIN_PASSWORD="$password" \
    -v "$dir:/data" \
    "$image" >/dev/null
}

run_gateway "$master" master 18088 "$work/master"
run_gateway "$backup" backup 18089 "$work/backup"

wait_health() {
  local port="$1"
  local i
  for i in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:${port}/api/v1/health" | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  echo "health did not become UP on port $port" >&2
  return 1
}
wait_health 18088
wait_health 18089

login() {
  local port="$1" jar="$2"
  python3 - "$password" "$work/login.json" <<'PY'
import json, sys
open(sys.argv[2], "w").write(json.dumps({"username": "admin", "password": sys.argv[1]}))
PY
  curl -fsS -c "$jar" -H 'Content-Type: application/json' --data-binary @"$work/login.json" \
    "http://127.0.0.1:${port}/api/v1/auth/login" >"$work/session.json"
  rm -f "$work/login.json"
  python3 - "$work/session.json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1]))["csrfToken"])
PY
}

master_csrf="$(login 18088 "$work/master.cookies")"
backup_csrf="$(login 18089 "$work/backup.cookies")"

api() {
  local port="$1" jar="$2" csrf="$3" method="$4" path="$5"
  shift 5
  curl -fsS -b "$jar" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -X "$method" "$@" "http://127.0.0.1:${port}${path}"
}

echo "waiting for the redundancy connection"
pending=0
for _ in $(seq 1 40); do
  if api 18089 "$work/backup.cookies" "$backup_csrf" GET /api/v1/gateway-network | python3 -c 'import json,sys; d=json.load(sys.stdin); rows=d.get("outgoing") or []; raise SystemExit(0 if rows and (rows[0].get("certificateState")=="pending" or rows[0].get("status")=="pending") else 1)'; then
    pending=1
    break
  fi
  sleep 1
done
if [[ "$pending" != 1 ]]; then
  echo "backup did not present a pending redundancy connection" >&2
  exit 1
fi
api 18089 "$work/backup.cookies" "$backup_csrf" POST /api/v1/gateway-network/outgoing/redundancy/approve -d '{}' >/dev/null
fingerprint=""
for _ in $(seq 1 40); do
  fingerprint="$(api 18088 "$work/master.cookies" "$master_csrf" GET /api/v1/gateway-network | python3 -c 'import json,sys; rows=(json.load(sys.stdin).get("incoming") or []); print(rows[0]["fingerprint"] if rows else "")')"
  if [[ -n "$fingerprint" ]]; then
    break
  fi
  sleep 1
done
if [[ -z "$fingerprint" ]]; then
  echo "master saw no incoming redundancy connection" >&2
  exit 1
fi
api 18088 "$work/master.cookies" "$master_csrf" POST "/api/v1/gateway-network/incoming/${fingerprint}/approve" -d '{}' >/dev/null

echo "waiting until the backup is warm"
warm=0
for _ in $(seq 1 40); do
  if api 18089 "$work/backup.cookies" "$backup_csrf" GET /api/v1/redundancy | python3 -c 'import json,sys; d=json.load(sys.stdin); raise SystemExit(0 if d.get("activityLevel")=="warm" else 1)'; then
    warm=1
    break
  fi
  sleep 1
done
if [[ "$warm" != 1 ]]; then
  echo "backup did not become warm" >&2
  api 18089 "$work/backup.cookies" "$backup_csrf" GET /api/v1/redundancy >&2 || true
  exit 1
fi

echo "creating ${count} tags on the master"
for i in $(seq 0 $((count - 1))); do
  python3 - "$i" "$work/tag.json" <<'PY'
import json, sys
i = int(sys.argv[1])
doc = {
  "apiVersion": "oip/v1",
  "kind": "Tag",
  "metadata": {"name": f"t{i}", "module": "tags", "version": 0},
  "spec": {"path": f"line/t{i}", "type": "memory", "dataType": "Double", "value": float(i)},
}
open(sys.argv[2], "w").write(json.dumps(doc))
PY
  api 18088 "$work/master.cookies" "$master_csrf" PUT "/api/v1/config/tags/gateway/Tag/t${i}" --data-binary @"$work/tag.json" >/dev/null
done

echo "waiting until the backup has every tag"
synced=0
body="$(python3 - "$count" <<'PY'
import json, sys
n = int(sys.argv[1])
print(json.dumps({"paths": [f"[default]line/t{i}" for i in range(n)]}))
PY
)"
for _ in $(seq 1 45); do
  if api 18089 "$work/backup.cookies" "$backup_csrf" POST /api/v1/tags/read --data-binary "$body" | python3 -c 'import json,sys; rows=json.load(sys.stdin)["values"]; n='"$count"'; raise SystemExit(0 if len(rows)==n and all(r.get("quality")=="Good" and r.get("value")==i for i,r in enumerate(rows)) else 1)'; then
    synced=1
    break
  fi
  sleep 1
done
if [[ "$synced" != 1 ]]; then
  echo "backup did not receive every tag" >&2
  exit 1
fi
echo "backup holds every tag before the kill"

echo "killing the master"
docker kill "$master" >/dev/null

echo "waiting until the backup is active"
active=0
for _ in $(seq 1 40); do
  if api 18089 "$work/backup.cookies" "$backup_csrf" GET /api/v1/redundancy | python3 -c 'import json,sys; raise SystemExit(0 if json.load(sys.stdin).get("activityLevel")=="active" else 1)'; then
    active=1
    break
  fi
  sleep 1
done
if [[ "$active" != 1 ]]; then
  echo "backup did not become active" >&2
  exit 1
fi

api 18089 "$work/backup.cookies" "$backup_csrf" POST /api/v1/tags/read \
  --data-binary "$(python3 - "$count" <<'PY'
import json, sys
n = int(sys.argv[1])
print(json.dumps({"paths": [f"[default]line/t{i}" for i in range(n)]}))
PY
)" >"$work/tags.json"
python3 - "$work/tags.json" "$count" <<'PY'
import json, sys
rows = json.load(open(sys.argv[1]))["values"]
count = int(sys.argv[2])
if len(rows) != count:
    raise SystemExit(f"expected {count} tags, got {len(rows)}")
for i, row in enumerate(rows):
    if row.get("quality") != "Good" or row.get("value") != i:
        raise SystemExit(f"tag t{i} lost: {row}")
print("backup kept every tag")
PY

# Steady writes on the surviving gateway while the cutoffs are sampled.
writer() {
  local n=0
  while true; do
    n=$((n + 1))
    api 18089 "$work/backup.cookies" "$backup_csrf" POST /api/v1/tags/write \
      --data-binary "{\"writes\":[{\"path\":\"[default]line/t0\",\"value\":${n}}]}" >/dev/null || true
    sleep 0.2
  done
}
writer &
writer_pid=$!

java_pid() {
  docker exec "$backup" sh -c 'for d in /proc/[0-9]*; do tr "\0" " " < "$d/cmdline" 2>/dev/null | grep -q MainKt && basename "$d" && exit 0; done; exit 1'
}

echo "sampling the backup for 60s"
sample() {
  local pid="$1"
  docker exec "$backup" sh -c "awk '{print \$14+\$15}' /proc/${pid}/stat; awk '/VmRSS/ {print \$2}' /proc/${pid}/status"
}
for window in 1 2 3 4 5 6; do
  pid="$(java_pid)"
  mapfile -t before < <(sample "$pid")
  sleep 10
  mapfile -t after < <(sample "$pid")
  ticks1="${before[0]}"
  ticks2="${after[0]}"
  rss2="${after[1]}"
  drift="$(api 18089 "$work/backup.cookies" "$backup_csrf" GET /api/v1/diagnostics/performance | python3 -c 'import json,sys; print(json.load(sys.stdin)["clockDrift"]["drift"])')"
  python3 - "$ticks1" "$ticks2" "$rss2" "$limit_bytes" "$drift" "$window" <<'PY'
import os, sys
ticks1, ticks2 = int(sys.argv[1]), int(sys.argv[2])
rss_kb = int(sys.argv[3])
limit = int(sys.argv[4])
drift = float(sys.argv[5])
window = sys.argv[6]
elapsed = 10.0
ticks = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
try:
    ticks = os.sysconf("SC_CLK_TCK")
except Exception:
    ticks = 100
cores = os.cpu_count() or 1
cpu = (ticks2 - ticks1) / ticks / elapsed / cores
mem = (rss_kb * 1024) / limit
print(f"window {window}: cpu {cpu:.4f} mem {mem:.4f} drift {drift:.4f} cores {cores}")
if cpu > 0.50 or mem > 0.50 or abs(drift) > 0.10:
    raise SystemExit(f"cutoff crossed in window {window}")
PY
done

kill "$writer_pid" >/dev/null 2>&1 || true
echo "redundancy failover ok"
