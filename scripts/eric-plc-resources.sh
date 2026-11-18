#!/bin/bash
# Apply or revert the PLC / ERIC resource split.
#
# The gateway does not run as root. Installing this helper and allowing it
# through sudo or polkit is the privileged step:
#
#   install -m 0755 scripts/eric-plc-resources.sh /usr/lib/eric/eric-plc-resources
#   printf '%s\n' 'eric ALL=(root) NOPASSWD: /usr/lib/eric/eric-plc-resources apply --stage *, /usr/lib/eric/eric-plc-resources revert --root /' > /etc/sudoers.d/eric-plc
#   chmod 0440 /etc/sudoers.d/eric-plc
#
# Applying onto / requires root. A different --root is for a rehearsal directory.
# Usage:
#   eric-plc-resources apply --stage DIR [--root /]
#   eric-plc-resources revert [--root /]
set -euo pipefail

usage() {
  echo "usage: eric-plc-resources apply --stage DIR [--root /] | revert [--root /]" >&2
  exit 2
}

cmd="${1:-}"
shift || usage
stage=""
root="/"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --stage) stage="${2:-}"; shift 2 ;;
    --root) root="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

if [[ "$root" == "/" && "$(id -u)" -ne 0 ]]; then
  echo "applying to / needs root. Run this helper with sudo, not the gateway." >&2
  exit 1
fi

backup_root="$root/var/lib/eric/plc-resources/backup"
state="$root/var/lib/eric/plc-resources/state.tsv"
mkdir -p "$root/var/lib/eric/plc-resources"

reject() {
  case "$1" in
    ""|..|../*|*/../*|*/..) echo "refusing path $1" >&2; exit 1 ;;
  esac
}

apply() {
  [[ -n "$stage" && -f "$stage/manifest" ]] || { echo "stage manifest is missing" >&2; exit 1; }
  local newstate
  newstate="$(mktemp)"
  while IFS= read -r rel || [[ -n "$rel" ]]; do
    [[ -z "$rel" ]] && continue
    reject "$rel"
    local src="$stage/files/$rel"
    local dest="$root/$rel"
    local backup="$backup_root/$rel"
    [[ -f "$src" ]] || { echo "missing $src" >&2; exit 1; }
    local managed=0
    if [[ -f "$dest" ]] && grep -q "Managed by ERIC resource split." "$dest"; then
      managed=1
    fi
    if [[ -e "$dest" && ! -e "$backup" && "$managed" -eq 0 ]]; then
      mkdir -p "$(dirname "$backup")"
      cp -a "$dest" "$backup"
      printf 'replaced\t%s\n' "$rel" >> "$newstate"
    elif [[ -e "$backup" ]]; then
      printf 'replaced\t%s\n' "$rel" >> "$newstate"
    else
      printf 'created\t%s\n' "$rel" >> "$newstate"
    fi
    mkdir -p "$(dirname "$dest")"
    local tmp="$dest.tmp.$$"
    cp -a "$src" "$tmp"
    mv -f "$tmp" "$dest"
  done < "$stage/manifest"
  mv -f "$newstate" "$state"
  if [[ "$root" == "/" ]] && command -v update-grub >/dev/null 2>&1; then
    update-grub
  fi
  echo "Reboot required"
}

revert() {
  [[ -f "$state" ]] || { echo "nothing to revert" >&2; exit 1; }
  while IFS=$'\t' read -r kind rel; do
    [[ -z "${rel:-}" ]] && continue
    reject "$rel"
    local dest="$root/$rel"
    local backup="$backup_root/$rel"
    if [[ "$kind" == "replaced" && -e "$backup" ]]; then
      mkdir -p "$(dirname "$dest")"
      mv -f "$backup" "$dest"
    else
      rm -f "$dest"
    fi
  done < "$state"
  rm -f "$state"
  echo "Reboot required"
}

case "$cmd" in
  apply) apply ;;
  revert) revert ;;
  *) usage ;;
esac
