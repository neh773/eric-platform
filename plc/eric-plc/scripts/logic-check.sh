#!/bin/bash
# Stand-in compiler for the Logic editor tests. The gateway uses the pinned
# RuSTy binary when ERIC_PLC_COMPILER points at it. This script only rejects
# the token not_a_keyword, in the same diagnostic shape RuSTy prints.
set -euo pipefail
# The program is the first .st argument; `-i <glob>` include paths (the process library) are skipped.
file=""
skip=0
for arg in "$@"; do
  if [[ $skip -eq 1 ]]; then skip=0; continue; fi
  if [[ "$arg" == "-i" ]]; then skip=1; continue; fi
  if [[ -z "$file" && "$arg" == *.st ]]; then file="$arg"; fi
done
if [[ -n "$file" ]] && grep -n 'not_a_keyword' "$file" >/dev/null; then
  line="$(grep -n 'not_a_keyword' "$file" | head -1 | cut -d: -f1)"
  echo "error[E048]: Could not resolve reference to not_a_keyword" >&2
  echo "   ┌─ ${file}:${line}:5" >&2
  exit 1
fi
exit 0
