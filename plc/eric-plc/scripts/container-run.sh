#!/bin/bash
# Timing run for a container or a cloud VM. The report label stays
# "container-size, not reference" even if the kernel grants scheduling
# capabilities. Usage: container-run.sh [seconds]
set -euo pipefail
DURATION="${1:-600}"
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
# Attempt a pin so the status records whether the container allowed it.
# Paths stay absolute because this file does not live next to the example.
cat > "$ROOT/examples/sealin/build/container.toml" << EOF
period_ms = 10
fifo_priority = 50
cpu = 0
mlock = true
max_consecutive_overruns = 3
bridge_timeout_ms = 100
shm_path = "/dev/shm/eric-plc-sealin"
program = "$ROOT/examples/sealin/build/libsealin.so"
entry = "SealIn"
instance = "SealIn_instance"
hwmap = "$ROOT/examples/sealin/build/hwmap.json"
mapping = "$ROOT/examples/sealin/mapping.json"
status_path = "$ROOT/examples/sealin/build/status.json"
label = "container-size, not reference"
stop_safe = true
EOF
CFG="$ROOT/examples/sealin/build/container.toml"
python3 - "$DURATION" 0 << 'PY' &
import multiprocessing, os, sys, time
end = time.time() + float(sys.argv[1])
pin = int(sys.argv[2])
others = set(range(os.cpu_count() or 1)) - {pin}
def burn():
    if others:
        try:
            os.sched_setaffinity(0, others)
        except AttributeError:
            pass
        except OSError:
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
"${BIN[@]}" sim --config "$CFG" --duration-ms "$((DURATION * 1000))" --expect-motor &
SIM=$!
set +e
"${BIN[@]}" run --config "$CFG" --duration-ms "$((DURATION * 1000))" --label "container-size, not reference"
RUN=$?
set -e
wait "$SIM"
kill "$STRESS" 2>/dev/null || true
wait "$STRESS" 2>/dev/null || true
"${BIN[@]}" report --status "$ROOT/examples/sealin/build/status.json" --out "$ROOT/examples/sealin/build/report.md"
echo "report: $ROOT/examples/sealin/build/report.md"
exit "$RUN"
