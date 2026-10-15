#!/usr/bin/env bash
# Rootless end-to-end verification. The kernel module must already be loaded.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/build/ipcam-cli"

if [[ ! -x "$CLI" ]]; then
  echo "Executable missing. Run ./scripts/build.sh first." >&2
  exit 1
fi
command -v v4l2-ctl >/dev/null || { echo "v4l2-ctl is required." >&2; exit 1; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is required." >&2; exit 1; }

find_ipcam_device() {
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

device="$(find_ipcam_device || true)"
if [[ -z "$device" ]]; then
  echo "FAIL: IPCamera Pro-labelled v4l2loopback device was not found." >&2
  echo "Run ./scripts/fedora-setup.sh --check in the Fedora host terminal." >&2
  exit 1
fi

echo "FOUND: $device"
v4l2-ctl --device="$device" --all

producer_pid=""
cleanup() {
  if [[ -n "$producer_pid" ]]; then
    kill -TERM "$producer_pid" 2>/dev/null || true
    wait "$producer_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# Explicit V4L2: default backend is PipeWire and is not proof of /dev/videoN.
"$CLI" --backend v4l2 --source pattern --device auto --width 320 --height 240 --fps 30 --frames 300 &
producer_pid=$!
sleep 1

echo "V4L2 stream check:"
timeout 12s v4l2-ctl --device="$device" --stream-mmap --stream-count=30 --stream-to=/dev/null
echo "PASS: v4l2-ctl read 30 frames"

echo "FFmpeg stream check:"
timeout 12s ffmpeg -hide_banner -loglevel error -f v4l2 \
  -video_size 320x240 -input_format yuyv422 -framerate 30 \
  -i "$device" -frames:v 30 -f null -
echo "PASS: FFmpeg read 30 frames"

wait "$producer_pid"
producer_pid=""
echo "PASS: producer stopped cleanly"
