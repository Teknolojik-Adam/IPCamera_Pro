#!/usr/bin/env bash
# Rootless IPCamera Pro PipeWire smoke test. No kernel or system configuration changes.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/build/ipcam-cli"
command -v pw-cli >/dev/null || { echo "SKIP: pw-cli missing"; exit 77; }
command -v gst-inspect-1.0 >/dev/null || { echo "SKIP: GStreamer missing"; exit 77; }
gst-inspect-1.0 pipewiresink >/dev/null 2>&1 || { echo "SKIP: pipewiresink missing"; exit 77; }
[[ -x "$CLI" ]] || { echo "FAIL: run scripts/build.sh first"; exit 1; }
# Remote shells (MCP/SSH) often lack XDG_RUNTIME_DIR even while the user's
# graphical PipeWire session is healthy. Reuse only this same UID's live socket.
if [[ -z "${XDG_RUNTIME_DIR:-}" ]]; then
  runtime="/run/user/$(id -u)"
  if [[ -d "$runtime" && -O "$runtime" && -S "$runtime/pipewire-0" ]]; then
    export XDG_RUNTIME_DIR="$runtime"
  fi
fi
[[ -n "${XDG_RUNTIME_DIR:-}" && -S "$XDG_RUNTIME_DIR/pipewire-0" ]] || {
  echo "SKIP: no accessible PipeWire user session"; exit 77;
}
# A socket file alone is not proof that the server accepts connections.
if ! timeout 4s pw-cli ls Node >/dev/null 2>&1; then
  echo "SKIP: PipeWire user session cannot be reached"; exit 77
fi
if timeout 3s pw-cli ls Node | grep -q 'node.name = "ipcamera-pro"'; then
  echo "SKIP: IPCamera Pro camera already running; leave it undisturbed"
  exit 77
fi
log="$(mktemp)"
pid=""
cleanup() {
  if [[ -n "$pid" ]]; then
    kill -TERM "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
  rm -f "$log"
}
trap cleanup EXIT
"$CLI" --backend pipewire --source pattern --width 320 --height 240 --fps 10 --frames 65 >"$log" 2>&1 &
pid=$!
found=0
for _ in $(seq 1 30); do
  if timeout 3s pw-cli ls Node 2>/dev/null | grep -q 'node.name = "ipcamera-pro"'; then
    found=1; break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then break; fi
  sleep 0.2
done
if [[ $found -ne 1 ]]; then
  echo "FAIL: PipeWire Video/Source node not found"
  cat "$log"
  exit 1
fi
echo "PASS: IPCamera Pro PipeWire Video/Source enumerated"
wait "$pid"
pid=""
grep -q 'Stopped after 65 frames' "$log" || { echo "FAIL: native pattern did not complete"; cat "$log"; exit 1; }
echo "PASS: native YUYV producer sent 65 frames without an attached camera client"
echo "NOTE: This does not prove browser/Discord capture; check in a real camera application."
