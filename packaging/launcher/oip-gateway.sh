#!/bin/sh
# Gateway launcher for a versioned install (EAM-011, DIFFERENCES 503).
#
#   OIP_INSTALL_DIR  install root; `current` names the active version, versions/<v>/bin/oip-gateway starts it
#   OIP_DATA_DIR     the gateway data directory (holds upgrade/pending.json while an upgrade is unproven)
#
# Runs the active version once and exits with its exit code, so the service manager decides about restarts.
# Exit code 75 means "restart me for an upgrade". When the version that an applied upgrade switched to dies before
# it became healthy (any exit other than 0 or 75 while pending.json is still there), this script points `current`
# back at the old version, records the rollback in upgrade/result.json, and exits 75 so it starts again on that one.
set -u
ROOT=${OIP_INSTALL_DIR:?OIP_INSTALL_DIR is required}
DATA=${OIP_DATA_DIR:?OIP_DATA_DIR is required}
export OIP_INSTALL_DIR OIP_DATA_DIR

version=$(tr -d '\r\n' < "$ROOT/current")
"$ROOT/versions/$version/bin/oip-gateway" "$@"
code=$?

pending="$DATA/upgrade/pending.json"
if [ "$code" -ne 0 ] && [ "$code" -ne 75 ] && [ -f "$pending" ]; then
  field() { sed -n "s/.*\"$1\" *: *\"\\([^\"]*\\)\".*/\\1/p" "$pending" | head -n 1; }
  from=$(field from)
  to=$(field to)
  if [ -n "$from" ] && [ "$to" = "$version" ]; then
    printf '%s\n' "$from" > "$ROOT/current.tmp" && mv "$ROOT/current.tmp" "$ROOT/current"
    printf '{"state":"rolledBack","version":"%s","restoredVersion":"%s","reason":"new version exited with code %s before it became healthy","atMillis":%s}\n' \
      "$to" "$from" "$code" "$(( $(date +%s) * 1000 ))" > "$DATA/upgrade/result.json"
    rm -f "$pending"
    exit 75
  fi
fi
exit "$code"
