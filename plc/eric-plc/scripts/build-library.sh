#!/bin/bash
# Compile the process library with the external RuSTy binary.
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
"$CACHE/plc" "$ROOT/st/eric_library.st" -i "$INC/*.st" -c --fpic -o "$OUT/eric_library.o"
cc -shared -o "$OUT/liberic_library.so" "$OUT/eric_library.o" \
  -L "$LIB" -l iec61131std -Wl,-rpath,'$ORIGIN'
cp -a "$LIB/libiec61131std.so" "$OUT/libiec61131std.so"
test -s "$OUT/liberic_library.so"
