#!/bin/bash
# Two-second scan of the example program with simulated tag inputs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/build-st.sh"
if rustup run stable cargo --version >/dev/null 2>&1; then
  CARGO=(rustup run stable cargo)
else
  CARGO=(cargo)
fi
"${CARGO[@]}" build --release --manifest-path "$ROOT/Cargo.toml" --locked
BIN="$ROOT/target/release/eric-plc"
rm -f /dev/shm/eric-plc-sealin
"${BIN[@]}" sim --config "$ROOT/examples/sealin/plc.toml" --duration-ms 2000 --expect-motor &
SIM=$!
"${BIN[@]}" run --config "$ROOT/examples/sealin/plc.toml" --duration-ms 2000
wait "$SIM"
"${BIN[@]}" stats --status "$ROOT/examples/sealin/build/status.json"
python3 - "$ROOT/examples/sealin/build/status.json" << 'PY'
import json, sys
status = json.load(open(sys.argv[1]))
if status["scans"] < 100:
    raise SystemExit(f"expected at least 100 scans, got {status['scans']}")
if status["watchdog_tripped"]:
    raise SystemExit("watchdog tripped during the smoke scan")
print("smoke scans", status["scans"])
PY
