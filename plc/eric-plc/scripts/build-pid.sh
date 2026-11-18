#!/bin/bash
# Compile ERIC_PID and the level-loop example. RuSTy is external.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$("$ROOT/scripts/fetch-rusty.sh")"
case "$(uname -m)" in
  x86_64) TRIPLE=x86_64-unknown-linux-gnu ;;
  *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
OUT="$ROOT/build"
mkdir -p "$OUT"
LIB="$CACHE/stdlib/$TRIPLE/lib"
INC="$CACHE/stdlib/include"
"$CACHE/plc" "$ROOT/st/eric_pid.st" -i "$INC/*.st" -c --fpic -o "$OUT/eric_pid.o"
cc -shared -o "$OUT/liberic_pid.so" "$OUT/eric_pid.o" \
  -L "$LIB" -l iec61131std -Wl,-rpath,'$ORIGIN'
"$CACHE/plc" "$ROOT/st/eric_pid.st" "$ROOT/examples/level/level.st" \
  -i "$INC/*.st" -c --fpic \
  --hwmap-file="$OUT/level-hwmap.json" \
  -o "$OUT/level.o"
cc -shared -o "$OUT/liblevel.so" "$OUT/level.o" \
  -L "$LIB" -l iec61131std -Wl,-rpath,'$ORIGIN'
cp -a "$LIB/libiec61131std.so" "$OUT/libiec61131std.so"
test -s "$OUT/level-hwmap.json"
