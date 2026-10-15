#!/usr/bin/env python3
"""IPCamera Pro: show the active Android MJPEG camera as a native Fedora window.

This is a fallback for Kamoso's GStreamer caps-negotiation bug. A dedicated
local FFplay window does NOT replace the existing PipeWire virtual webcam.
It doesn't create video files, screenshots or transmit video to any cloud.

Read the authenticated stream URL from this user's already-running IPCamera
Pro CLI process (never display or put the URL in a subprocess command line).
Extract the JPEG parts and pipe the raw MJPEG byte stream to FFplay.

Optional --self-test consumes a *synthetic* URL supplied on stdin and
decodes ten frames with FFmpeg, without displaying any images.
"""
import os
import pathlib
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from phone_url import normalize_phone_url, PhoneUrlError

MAGIC = b"\xff\xd8"
MAX_JPEG = 8 * 1024 * 1024
MAX_HEADER = 2048


def alert(message):
    # GUI error notification if started using the desktop shortcut.
    try:
        subprocess.run(
            ["/usr/bin/kdialog", "--title", "IPCamera Pro", "--error", message],
            timeout=12, check=False, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        print(message, file=sys.stderr)


def find_live_phone_url():
    proc = pathlib.Path("/proc")
    candidates = []
    for item in proc.iterdir():
        if not item.name.isdigit():
            continue
        try:
            args = (item / "cmdline").read_bytes().split(b"\0")
            if not args or not args[0].endswith(b"/build/ipcam-cli"):
                continue
            if b"--input" not in args:
                continue
            raw = args[args.index(b"--input") + 1].decode("ascii")
            url, _ = normalize_phone_url(raw)
            if not raw.startswith("http://"):
                continue
            candidates.append((int(item.name), url))
        except (OSError, IndexError, UnicodeDecodeError, ValueError, PhoneUrlError):
            continue
    if not candidates:
        raise RuntimeError(
            "Telefon yayını henüz bağlı değil. Önce Fedora masaüstünden "
            "'IPCamera Pro - Telefonu Bağla' kısayolunu aç ve 6 haneli kodla eşleştir.")
    return sorted(candidates)[-1][1]


def read_exact(response, size):
    result = bytearray()
    while len(result) < size:
        chunk = response.read(min(65536, size - len(result)))
        if not chunk:
            raise EOFError("Telefon video bağlantısı kapandı")
        result.extend(chunk)
    return result


def iter_mjpeg(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        url, headers={"User-Agent": "IPCameraPro-NativeViewer/0.1",
                      "Connection": "close"})
    with opener.open(request, timeout=10) as response:
        if response.status != 200:
            raise RuntimeError("Telefon yayın isteğini reddetti.")
        mime = response.headers.get("Content-Type", "").lower()
        if "multipart/x-mixed-replace" not in mime:
            raise RuntimeError("Telefon JPEG video yayını göndermiyor.")
        # Hard-limit frame/header sizes. Avoid accepting untrusted content as
        # command-line options and never invoke a shell.
        while True:
            boundary = response.readline(MAX_HEADER)
            if not boundary:
                return
            if not boundary.startswith(b"--"):
                continue
            length = None
            for _ in range(16):
                line = response.readline(MAX_HEADER)
                if not line or len(line) >= MAX_HEADER:
                    raise RuntimeError("Kamera akışı başlığı geçersiz.")
                if line in (b"\r\n", b"\n"):
                    break
                if line.lower().startswith(b"content-length:"):
                    try:
                        length = int(line.split(b":", 1)[1].strip())
                    except ValueError:
                        raise RuntimeError("JPEG boyutu okunamadı.")
            if length is None or length <= 0 or length > MAX_JPEG:
                raise RuntimeError("JPEG karesinin boyutu geçersiz.")
            jpeg = read_exact(response, length)
            if jpeg[:2] != MAGIC:
                raise RuntimeError("Bozuk JPEG karesi geldi.")
            yield jpeg
            # The next read iteration consumes the frame's trailing CRLF.


def play(url, test_mode=False):
    if test_mode:
        args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "mjpeg", "-r", "15", "-i", "pipe:0",
                "-frames:v", "10", "-f", "null", "-"]
    else:
        args = ["ffplay", "-hide_banner", "-loglevel", "warning",
                "-window_title", "IPCamera Pro - Telefon Kamerası",
                "-fflags", "nobuffer", "-flags", "low_delay",
                "-framedrop", "-f", "mjpeg", "-i", "pipe:0"]

    proc = subprocess.Popen(
        args, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL if not test_mode else None)
    sent = 0
    try:
        for jpeg in iter_mjpeg(url):
            if proc.poll() is not None:
                break
            proc.stdin.write(jpeg)
            proc.stdin.flush()
            sent += 1
            if test_mode and sent >= 14:
                break
    except (BrokenPipeError, ConnectionResetError):
        if proc.poll() is None:
            raise
    finally:
        if proc.stdin and not proc.stdin.closed:
            try:
                proc.stdin.close()
            except BrokenPipeError:
                pass
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
    if test_mode:
        if sent < 10 or proc.returncode != 0:
            raise RuntimeError(f"Native decoder self-test failed: {sent} frames / {proc.returncode}")
        print(f"PASS: {sent} JPEG frames delivered to native FFmpeg decoder")
    return 0


def main():
    try:
        url = (sys.stdin.readline().strip() if "--self-test" in sys.argv
               else find_live_phone_url())
        url, _ = normalize_phone_url(url)
        print("IPCamera Pro: Fedora yerel önizlemesi açılıyor...", flush=True)
        return play(url, test_mode=("--self-test" in sys.argv))
    except urllib.error.HTTPError as err:
        if err.code == 403:
            alert("Kamera erişim anahtarı değişmiş. Telefondaki yayını yeniden başlatıp PC'ye bağlan.")
        else:
            alert(f"Telefon kamera sunucusu HTTP {err.code} hatası verdi.")
        return 1
    except (OSError, ValueError, RuntimeError, PhoneUrlError) as err:
        alert(str(err))
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
