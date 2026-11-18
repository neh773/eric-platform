#!/bin/bash
# Compile the example Structured Text program with the external RuSTy binary.
# The standard library is linked dynamically and copied beside the program so
# $ORIGIN can find it. RuSTy source is not copied into this repository.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$("$ROOT/scripts/fetch-rusty.sh")"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) TRIPLE=x86_64-unknown-linux-gnu ;;
  *) echo "unsupported arch $ARCH" >&2; exit 1 ;;
esac
OUT="$ROOT/examples/sealin/build"
mkdir -p "$OUT"
LIB="$CACHE/stdlib/$TRIPLE/lib"
INC="$CACHE/stdlib/include"
# RuSTy's own link step forwards -Wl to ld. Link the object with cc so
# $ORIGIN is a run path and the standard library is found beside the program.
"$CACHE/plc" "$ROOT/examples/sealin/sealin.st" \
  -i "$INC/*.st" \
  -c --fpic \
  --hwmap-file="$OUT/hwmap.json" \
  -o "$OUT/sealin.o"
cc -shared -o "$OUT/libsealin.so" "$OUT/sealin.o" \
  -L "$LIB" -l iec61131std \
  -Wl,-rpath,'$ORIGIN'
cp -a "$LIB/libiec61131std.so" "$OUT/libiec61131std.so"
if ! ldd "$OUT/libsealin.so" | grep -q 'libiec61131std.so => .*/libiec61131std.so'; then
  echo "libsealin.so did not record a usable path to libiec61131std.so" >&2
  ldd "$OUT/libsealin.so" >&2 || true
  exit 1
fi
test -s "$OUT/hwmap.json"
echo "$OUT/libsealin.so"
