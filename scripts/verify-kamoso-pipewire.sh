#!/usr/bin/env bash
# Interactive, opt-in regression for IPCamera Pro <-> Kamoso / PipeWire.
# Requires an active GUI session. Never stops an existing user's camera.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/build/ipcam-cli"
command -v kamoso >/dev/null || { echo "Kamoso not installed" >&2; exit 2; }
command -v pw-link >/dev/null || { echo "pw-link is required" >&2; exit 2; }
[[ -x "$CLI" ]] || { echo "Build the Linux app first (cmake --build build)" >&2; exit 2; }
if pgrep -x kamoso >/dev/null; then
  echo "Kamoso is already running; close it before running this standalone test." >&2
  exit 2
fi
if timeout 3s pw-cli ls Node | grep -q 'node.name = "ipcamera-pro"'; then
  echo "IPCamera-Pro is already running. Do not interrupt the live camera." >&2
  exit 2
fi
LOGDIR="$(mktemp -d /tmp/ipcam-kamoso-smoke-XXXXXX)"
producer=0
camera=0
cleanup() {
  if [[ "$camera" -gt 0 ]]; then
    kill -TERM "$camera" 2>/dev/null || true
    wait "$camera" 2>/dev/null || true
  fi
  if [[ "$producer" -gt 0 ]]; then
    kill -TERM "$producer" 2>/dev/null || true
    wait "$producer" 2>/dev/null || true
  fi
}
trap cleanup EXIT
echo "Starting temporary IPCamera-Pro test pattern and Kamoso..."
timeout -s TERM 16s "$CLI" --backend pipewire --source pattern \
  --width 640 --height 360 --fps 20 --frames 180 \
  >"$LOGDIR/sender.log" 2>&1 &
producer=$!
sleep 1
timeout -s TERM 6s kamoso >"$LOGDIR/kamoso.log" 2>&1 &
camera=$!
linked=false
for i in {1..28}; do
  if pw-link -l 2>/dev/null | grep -q 'ipcamera-pro:capture_1'; then
    if pw-link -l 2>/dev/null | grep -q 'kamoso:input_1'; then
      linked=true
      break
    fi
  fi
  sleep 0.2
done
wait "$camera" || true
camera=0
if wait "$producer"; then
  producer=0
else
  echo "FAIL: virtual camera publisher crashed; logs: $LOGDIR" >&2
  tail -18 "$LOGDIR/sender.log" >&2
  exit 1
fi
if [[ "$linked" != true ]] || ! grep -q 'Stopped after 180 frames' "$LOGDIR/sender.log"; then
  echo "FAIL: Kamoso link/frames not verified; logs: $LOGDIR" >&2
  exit 1
fi
if grep -q -E 'all buffers have been removed|Broken pipe' "$LOGDIR/sender.log"; then
  echo "FAIL: PipeWire sink broke; logs: $LOGDIR" >&2
  exit 1
fi
echo "PASS: Kamoso linked to PipeWire IPCamera-Pro, 180/180 frames, no Broken pipe"
echo "Logs: $LOGDIR"
