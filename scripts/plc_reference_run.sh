#!/bin/bash
# Reference timing run for a Protectli-class machine.
#
# The kernel should be PREEMPT_RT, with one isolated core (isolcpus, nohz_full)
# and the engine pinned to that core. This script does not change boot
# parameters. It records what it found and labels the report "reference" only
# when the kernel is PREEMPT_RT and the command line isolates at least one core.
#
# Usage: scripts/plc_reference_run.sh [cpu] [seconds]
#   cpu      core to pin. When omitted, the resource-split file names the PLC cores.
#   seconds  default 600.
set -euo pipefail
RESOURCES="${ERIC_PLC_RESOURCES:-/etc/eric/plc-resources.json}"
if [[ -n "${1:-}" ]]; then
  CPU="$1"
elif [[ -f "$RESOURCES" ]]; then
  CPU="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["plcCpus"][0])' "$RESOURCES")"
  export ERIC_PLC_RESOURCES="$RESOURCES"
else
  CPU="1"
fi
DURATION="${2:-600}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLC="$ROOT/plc/eric-plc"
if [[ ! "$CPU" =~ ^[0-9]+$ ]]; then
  echo "cpu must be a number" >&2
  exit 1
fi
LABEL="not reference"
KERNEL="$(uname -srm)"
CMDLINE="$(cat /proc/cmdline 2>/dev/null || true)"
if printf '%s\n' "$KERNEL" | grep -q 'PREEMPT_RT\|preempt-rt'; then
  if printf '%s\n' "$CMDLINE" | grep -q 'isolcpus='; then
    LABEL="reference"
  else
    LABEL="PREEMPT_RT without an isolated core"
  fi
else
  LABEL="kernel is not PREEMPT_RT"
fi
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
cat > "$WORKDIR/run.toml" << EOF
period_ms = 10
fifo_priority = 50
$(if [[ -n "${ERIC_PLC_RESOURCES:-}" && -f "${ERIC_PLC_RESOURCES}" ]]; then echo "# cpu list comes from ${ERIC_PLC_RESOURCES}"; else echo "cpu = $CPU"; fi)
mlock = true
max_consecutive_overruns = 3
bridge_timeout_ms = 100
shm_path = "/dev/shm/eric-plc-sealin"
program = "$PLC/examples/sealin/build/libsealin.so"
entry = "SealIn"
instance = "SealIn_instance"
hwmap = "$PLC/examples/sealin/build/hwmap.json"
mapping = "$PLC/examples/sealin/mapping.json"
status_path = "$WORKDIR/status.json"
label = "$LABEL"
stop_safe = true
EOF
"$PLC/scripts/build-st.sh"
if rustup run stable cargo --version >/dev/null 2>&1; then
  CARGO=(rustup run stable cargo)
else
  CARGO=(cargo)
fi
"${CARGO[@]}" build --release --manifest-path "$PLC/Cargo.toml" --locked
BIN="$PLC/target/release/eric-plc"
rm -f /dev/shm/eric-plc-sealin
if [[ -n "${ERIC_PLC_RESOURCES:-}" && -f "$ERIC_PLC_RESOURCES" ]]; then
  AVOID="$(python3 -c 'import json,sys; print(",".join(str(x) for x in json.load(open(sys.argv[1]))["plcCpus"]))' "$ERIC_PLC_RESOURCES")"
else
  AVOID="$CPU"
fi
python3 - "$DURATION" "$AVOID" << 'PY' &
import multiprocessing, os, sys, time
end = time.time() + float(sys.argv[1])
pin = {int(item) for item in sys.argv[2].split(",") if item}
others = set(range(os.cpu_count() or 1)) - pin
def burn():
    if others:
        try:
            os.sched_setaffinity(0, others)
        except (AttributeError, OSError):
            pass
    x = 1
    while time.time() < end:
        x = (x * 1664525 + 1013904223) & 0xFFFFFFFF
    return x
workers = max(1, len(others) or 1)
procs = [multiprocessing.Process(target=burn) for _ in range(workers)]
for proc in procs:
    proc.start()
for proc in procs:
    proc.join()
PY
STRESS=$!
"$BIN" sim --config "$WORKDIR/run.toml" --duration-ms "$((DURATION * 1000))" --expect-motor &
SIM=$!
set +e
"$BIN" run --config "$WORKDIR/run.toml" --duration-ms "$((DURATION * 1000))"
RUN=$?
set -e
wait "$SIM"
kill "$STRESS" 2>/dev/null || true
wait "$STRESS" 2>/dev/null || true
"$BIN" report --status "$WORKDIR/status.json" --out "$WORKDIR/report.md"
cp "$WORKDIR/status.json" "$PLC/examples/sealin/build/reference-status.json"
cp "$WORKDIR/report.md" "$PLC/examples/sealin/build/reference-report.md"
echo "label: $LABEL"
echo "kernel: $KERNEL"
echo "report: $PLC/examples/sealin/build/reference-report.md"
exit "$RUN"
