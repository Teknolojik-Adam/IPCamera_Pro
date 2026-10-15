#!/usr/bin/env bash
# Black-frame detector regression: serve all-black MJPEG like a broken phone
# camera HAL and require ipcam-cli to warn about black frames explicitly.
set -u
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/build/ipcam-cli"
command -v python3 >/dev/null || { echo "SKIP: python3 missing"; exit 77; }
command -v ffmpeg >/dev/null || { echo "SKIP: ffmpeg missing"; exit 77; }
[[ -x "$CLI" ]] || { echo "FAIL: run scripts/build.sh first"; exit 1; }

tmp="$(mktemp -d)"
srv=""
cleanup() {
  [[ -n "$srv" ]] && kill "$srv" 2>/dev/null
  rm -rf "$tmp"
}
trap cleanup EXIT

# A real all-black JPEG produced by the same ffmpeg build the CLI uses.
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=black:s=320x240:d=1 \
  -frames:v 1 -q:v 5 "$tmp/black.jpg" || { echo "SKIP: cannot make black JPEG"; exit 77; }

cat > "$tmp/server.py" <<'PY'
import base64, os, sys, threading, time
from http.server import BaseHTTPRequestHandler, HTTPServer
TOKEN = base64.urlsafe_b64encode(os.urandom(24)).decode().rstrip("=")
JPG = open(sys.argv[1], "rb").read()
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path != f"/video?token={TOKEN}":
            self.send_response(403); self.end_headers(); return
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=ipcamframe")
        self.end_headers()
        for _ in range(300):
            try:
                self.wfile.write(b"--ipcamframe\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n" % len(JPG))
                self.wfile.write(JPG); self.wfile.write(b"\r\n"); self.wfile.flush()
            except OSError:
                return
            time.sleep(1/30)
threading.Thread(target=HTTPServer(("127.0.0.1", 18787), H).serve_forever, daemon=True).start()
print(f"http://127.0.0.1:18787/video?token={TOKEN}", flush=True)
time.sleep(60)
PY
python3 "$tmp/server.py" "$tmp/black.jpg" > "$tmp/url" 2>"$tmp/server.log" &
srv=$!
for _ in $(seq 1 30); do [[ -s "$tmp/url" ]] && break; sleep 0.2; done
[[ -s "$tmp/url" ]] || { echo "FAIL: mock phone server did not start"; cat "$tmp/server.log"; exit 1; }
URL="$(cat "$tmp/url")"

# 3 seconds of 10 fps black frames: the warning must fire well before frame 20.
timeout 30 "$CLI" --dry-run --input "$URL" --width 320 --height 240 --fps 10 \
  --frames 25 --max-retries 1 > "$tmp/out.log" 2>&1
rc=$?
cat "$tmp/out.log"
[[ $rc -eq 0 ]] || { echo "FAIL: ipcam-cli exited with $rc"; exit 1; }
grep -q "siyah kare" "$tmp/out.log" || { echo "FAIL: black-frame warning missing"; exit 1; }
echo "PASS: black phone feed produced an explicit black-frame warning"
