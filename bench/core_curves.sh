#!/usr/bin/env bash
# Core-curve run of our gateway (container-size). One ramp on a fresh container.
#
#   bench/core_curves.sh <cpus> <memory> <heap> <profile> <out-dir> <password-file>
#
# Starts oip-gateway:local the way `bench scale` does (dockerbox.oip_gateway_command),
# plus --memory-swap equal to --memory so the container cannot swap. The admin
# password goes in through a 0600 env file, never on a command line. Then runs
# `python3 -m bench ramp` against the bench simulator on the Docker bridge, and
# removes the container and its /data volume.
set -euo pipefail
cpus="$1"; memory="$2"; heap="$3"; profile="$4"; out="$5"; password_file="$6"
name=oip-core-gw
here="$(cd "$(dirname "$0")" && pwd)"
envdir="$(mktemp -d)"
trap 'rm -rf "$envdir"; docker rm -v -f "$name" >/dev/null 2>&1 || true' EXIT
cd "$here"
python3 - "$password_file" "$envdir/gw.env" <<'PY'
import os, sys
sys.path.insert(0, os.getcwd())
from bench.secrets import read_admin_password, password_env_text
pw = read_admin_password(open(sys.argv[1]).read())
fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.write(fd, password_env_text(pw).encode())
os.close(fd)
PY
docker rm -v -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" \
  --cpus "$cpus" --memory "$memory" --memory-swap "$memory" \
  -p 8090:8088 -p 4840:4840 \
  --add-host host.docker.internal:host-gateway \
  -e OIP_DATA_DIR=/data -e OIP_PROFILE=standard \
  -e OIP_HTTP_HOST=0.0.0.0 -e OIP_HTTP_PORT=8088 -e OIP_OPCUA_BIND=0.0.0.0 \
  -e "JAVA_OPTS=-Xmx$heap -Xlog:gc*:file=/data/gc.log:time,uptime:filecount=3,filesize=20m" \
  --env-file "$envdir/gw.env" oip-gateway:local >/dev/null
for _ in $(seq 1 90); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo none)"
  [ "$status" = healthy ] && break
  sleep 2
done
[ "$status" = healthy ] || { echo "gateway not healthy: $status" >&2; docker logs --tail 40 "$name" >&2; exit 1; }
python3 -m bench ramp --profile "$profile" --target oip \
  --gateway http://127.0.0.1:8090 --container "$name" \
  --oip-password-file "$password_file" --read-via-opcua \
  --start-sim --out "$out"
