#!/bin/bash
# Download RuSTy v1.0.8 and its standard library. The compiler is LGPLv3 and
# stays outside the ERIC tree. Nothing under .cache is committed.
set -euo pipefail
VERSION=1.0.8
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache/rusty-$VERSION"
mkdir -p "$CACHE"
# The PID tests and the process-library test can download at the same time.
exec 9>"$CACHE/.lock"
flock 9
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64)
    BIN_NAME=plc-linux-x86_64
    BIN_SHA=e4b086ae4eac8613b609a5862a161b9095f7f73d5b5db4967cf4571a8c080833
    TRIPLE=x86_64-unknown-linux-gnu
    ;;
  *)
    echo "RuSTy fetch supports x86_64 only in this preview (this machine is $ARCH)" >&2
    exit 1
    ;;
esac
STD_SHA=f0bf72ed4df3ef5cab0d5887f8e06c5b0fd5f43ca1a10cd6ade249d0decb0c4e
BASE="https://github.com/PLC-lang/rusty/releases/download/v$VERSION"
download() {
  local url="$1" dest="$2" sha="$3"
  if [[ -f "$dest" ]] && echo "$sha  $dest" | sha256sum -c - >/dev/null 2>&1; then
    return
  fi
  local partial="$dest.partial.$$"
  curl -fsSL -o "$partial" "$url"
  echo "$sha  $partial" | sha256sum -c - >/dev/null
  mv "$partial" "$dest"
}
download "$BASE/$BIN_NAME" "$CACHE/plc" "$BIN_SHA"
download "$BASE/stdlib-$VERSION.tar.gz" "$CACHE/stdlib.tar.gz" "$STD_SHA"
chmod +x "$CACHE/plc"
if [[ ! -f "$CACHE/stdlib/$TRIPLE/lib/libiec61131std.so" ]]; then
  tar -xzf "$CACHE/stdlib.tar.gz" -C "$CACHE"
fi
test -x "$CACHE/plc"
"$CACHE/plc" --version | grep -qx "$VERSION"
echo "$CACHE"
