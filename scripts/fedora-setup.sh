#!/usr/bin/env bash
# Only changes system state after explicit --install or --load.
set -euo pipefail
mode="${1:---check}"
help() {
cat <<'TXT'
IPCamera Pro Fedora setup (optional; no OBS required)
  ./scripts/fedora-setup.sh --check    Show state; do not change PC
  ./scripts/fedora-setup.sh --install  Install dependencies (dnf; requires RPM Fusion for akmod)
  ./scripts/fedora-setup.sh --load     Load the module and create an auto-numbered IPCamera Pro device
  ./scripts/fedora-setup.sh --help     Show this help

Requirements: Fedora, matching kernel-devel, loaded v4l2loopback kernel module.
The script never disables Secure Boot and never replaces an existing video device.
No configuration is written to /etc automatically.
TXT
}
find_ipcam_device() {
  command -v v4l2-ctl >/dev/null 2>&1 || return 1
  local dev info
  shopt -s nullglob
  for dev in /dev/video[0-9]*; do
    info="$(v4l2-ctl --device="$dev" --all 2>/dev/null || true)"
    if grep -Eq 'Driver name[[:space:]]*: v4l2 loopback' <<<"$info" &&
       grep -Eq 'Card type[[:space:]]*:[[:space:]]*IPCamera Pro' <<<"$info"; then
      printf '%s\n' "$dev"
      return 0
    fi
  done
  return 1
}
check() {
  echo "== IPCamera Pro / Fedora system check =="
  echo "Kernel: $(uname -r)"
  for c in cmake g++ ffmpeg v4l2-ctl modprobe; do
    if command -v "$c" >/dev/null 2>&1; then
      echo "OK $c: $(command -v "$c")"
    else echo "MISSING $c"; fi
  done
  if lsmod | grep -q '^v4l2loopback '; then echo "Kernel module: loaded";
  else echo "Kernel module: not loaded"; fi
  if device="$(find_ipcam_device)"; then
    echo "IPCamera Pro device: $device"
    ls -l "$device"
  else
    echo "IPCamera Pro device: not found (video number is intentionally dynamic)"
  fi
  if command -v modinfo >/dev/null 2>&1; then
    echo "v4l2loopback module:"
    modinfo v4l2loopback 2>/dev/null | awk -F: '/^(filename|version|signer|sig_id)/ {gsub(/^[[:space:]]+/,"",$2); print "  " $1 ":" $2}' || true
  fi
  if command -v mokutil >/dev/null 2>&1; then mokutil --sb-state || true; fi
  if command -v v4l2-ctl >/dev/null 2>&1; then v4l2-ctl --list-devices || true; fi
}
case "$mode" in
  --check) check ;;
  --help|-h) help ;;
  --install)
    if ! command -v dnf >/dev/null; then echo "Fedora / dnf required" >&2; exit 1; fi
    echo "Will install with dnf (requires compatible RPM Fusion Free repository)."
    sudo dnf install cmake gcc-c++ make ffmpeg v4l-utils akmod-v4l2loopback "kernel-devel-$(uname -r)"
    echo "If akmod was built just now, wait until it finishes before using --load."
    ;;
  --load)
    if ! command -v modprobe >/dev/null 2>&1; then echo "modprobe not found" >&2; exit 1; fi
    if device="$(find_ipcam_device)"; then
      echo "IPCamera Pro already exists at $device; no changes made."
      exit 0
    fi
    if lsmod | grep -q '^v4l2loopback '; then
      echo "v4l2loopback is already loaded but no IPCamera Pro-labelled device was found." >&2
      echo "Refusing to unload or alter another application's module. Inspect: v4l2-ctl --list-devices" >&2
      exit 1
    fi
    sudo modprobe v4l2loopback devices=1 video_nr=-1 'card_label=IPCamera Pro' exclusive_caps=1
    for _ in {1..20}; do
      if device="$(find_ipcam_device)"; then
        echo "Module loaded. Camera created at $device"
        echo "Start feeding frames BEFORE looking for the camera in browsers."
        exit 0
      fi
      sleep 0.25
    done
    echo "Module loaded but IPCamera Pro device was not discoverable." >&2
    echo "Inspect: v4l2-ctl --list-devices; kernel log: journalctl -k -b --no-pager" >&2
    exit 1
    ;;
  *) help >&2; exit 2 ;;
esac
