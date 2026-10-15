#!/usr/bin/env python3
"""Temporary private-LAN, one-shot IPCamera Pro Android URL handoff.

Run as the current user's transient process. Prints only the *final* camera URL
to stdout for capture by connect-phone.sh. All instructions go to stderr.
No persistent HTTP service, database, cookie, cloud or system privileges.
"""
import html
import ipaddress
import json
import re
import secrets
import socket
import subprocess
import sys
import time
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit
from phone_url import normalize_phone_url, PhoneUrlError

TIMEOUT = 240
MAX_BODY = 4096
PORTS = (1755, 1756, 1757, 1758, 1759, 1760)
PAGE = """<!doctype html>
<html lang="tr"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<meta name="referrer" content="no-referrer"/>
<title>IPCamera Pro - Telefon Bağlantısı</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0a101a;color:#f0f5ff;font:16px/1.5 system-ui,Arial}
main{max-width:560px;margin:34px auto;padding:24px}
section{background:#152133;border:1px solid #354963;padding:24px;border-radius:18px}
h1{font-size:27px;letter-spacing:-.6px;margin:0 0 12px}
p{color:#b8c8dd}
label{display:block;font-weight:650;margin:20px 0 6px}
input{display:block;width:100%;padding:15px;border:1px solid #56718d;border-radius:10px;background:#0b1725;color:#fff;font-size:17px}
input:focus{outline:2px solid #4b9dff}
button{width:100%;margin-top:22px;background:#287bdc;border:0;border-radius:11px;padding:15px;color:white;font-size:17px;font-weight:650}
aside{color:#a6b8ce;font-size:13px;margin-top:22px}
</style></head><body><main><section>
<h1>📷 IPCamera Pro</h1>
<p>Telefon kamerasını Fedora bilgisayarına bağla. APK'yi yeniden kurman gerekmiyor.</p>
<form method="post" action="/pair" autocomplete="off">
<label for="pin">Bilgisayar terminalindeki 6 haneli kod</label>
<input id="pin" name="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required placeholder="123456"/>
<label for="url">IPCamera Pro uygulamasındaki bağlantı adresi</label>
<input id="url" name="url" type="url" required autocapitalize="off" spellcheck="false"
 placeholder="http://192.168.x.x:8787/video?token=..."/>
<button type="submit">Kamerayı bilgisayara bağla</button>
</form>
<aside>Uygulamada Yayını Başlat → Bağlantı adresini kopyala. Buradaki URL alanına uzun basıp Yapıştır'ı seç. Bu sayfa sadece aynı Wi-Fi ağında, kısa süreliğine açıktır.</aside>
</section></main></body></html>"""


def wifi_address():
    try:
        records = json.loads(subprocess.check_output(
            ["ip", "-j", "-4", "address", "show", "up"], timeout=4))
    except (OSError, subprocess.CalledProcessError, ValueError, subprocess.TimeoutExpired):
        raise RuntimeError("Wi-Fi IPv4 adresi alınamadı")
    choices = []
    for dev in records:
        name = dev.get("ifname", "")
        if name.startswith(("lo", "docker", "veth", "tun", "tailscale")):
            continue
        for item in dev.get("addr_info", []):
            try:
                ip = ipaddress.ip_address(item["local"])
                if (isinstance(ip, ipaddress.IPv4Address)
                        and ip.is_private and not ip.is_loopback
                        and not ip.is_link_local):
                    network = ipaddress.ip_network(
                        f"{ip}/{item['prefixlen']}", strict=False)
                    # Prefer wlan/wlp; LAN ethernet is also acceptable.
                    choices.append((0 if name.startswith(("wl", "wlan")) else 1,
                                    str(ip), network))
            except (KeyError, ValueError):
                continue
    if not choices:
        raise RuntimeError("Wi-Fi / yerel ağ bağlantısı bulunamadı")
    choices.sort(key=lambda x: x[0])
    return choices[0][1], choices[0][2]


def check_phone_url(text, sender):
    # Return a normalized URL rather than silently accepting malformed tokens.
    # Raises a user-readable error without disclosing bearer credentials.
    return normalize_phone_url(text, sender_ip=sender)


def main():
    address, network = wifi_address()
    code = f"{secrets.randbelow(1_000_000):06d}"
    result = {"url": None, "attempts": 0, "deadline": time.monotonic() + TIMEOUT}

    class Receiver(BaseHTTPRequestHandler):
        def log_message(self, *args):
            # Do not log URL/query/body: it includes the bearer token.
            pass

        def reply(self, status, body, typ="text/html; charset=utf-8"):
            payload = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", typ)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy",
                             "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
            self.end_headers()
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def inside_network(self):
            try:
                return ipaddress.ip_address(self.client_address[0]) in network
            except ValueError:
                return False

        def do_GET(self):
            if not self.inside_network():
                return self.reply(403, "Yerel ağ dışından erişim yok.", "text/plain")
            if self.path != "/":
                return self.reply(404, "Sayfa bulunamadı.", "text/plain")
            return self.reply(200, PAGE)

        def do_POST(self):
            if not self.inside_network():
                return self.reply(403, "Yerel ağ dışından erişim yok.", "text/plain")
            if self.path != "/pair" or result["url"] is not None:
                return self.reply(404, "Eşleştirme kapalı.", "text/plain")
            if result["attempts"] >= 5:
                return self.reply(429, "Çok fazla başarısız deneme.", "text/plain")
            size_text = self.headers.get("Content-Length", "")
            if (not size_text.isdecimal() or not 0 < int(size_text) <= MAX_BODY
                    or not self.headers.get("Content-Type", "").startswith(
                        "application/x-www-form-urlencoded")):
                return self.reply(413, "Geçersiz istek.", "text/plain")
            result["attempts"] += 1
            try:
                fields = parse_qs(self.rfile.read(int(size_text)).decode("utf-8"),
                                  keep_blank_values=True, strict_parsing=True)
                pin = fields.get("pin", [])
                links = fields.get("url", [])
                if len(pin) != 1 or not secrets.compare_digest(pin[0], code):
                    return self.reply(403,
                        "<h2>Eşleştirme kodu yanlış</h2>"
                        "<p>Fedora terminalindeki güncel 6 haneli kodu gir.</p>")
                if len(links) != 1:
                    return self.reply(422,
                        "<h2>Kamera URL'si eksik</h2>"
                        "<p>IPCamera Pro uygulamasında Yayını Başlat ve Bağlantı "
                        "adresini kopyala seçeneklerini kullan.</p>")
                try:
                    corrected, repaired = check_phone_url(
                        links[0], self.client_address[0])
                except PhoneUrlError as problem:
                    return self.reply(422,
                        "<h2>Kamera adresi geçersiz</h2><p>" +
                        html.escape(str(problem), quote=True) + "</p>"
                        "<p>Adresin tamamını uygulamadaki Kopyala düğmesinden al.</p>")
                result["url"] = corrected
            except (UnicodeDecodeError, ValueError):
                return self.reply(400, "Form okunamadı.", "text/plain")
            if repaired:
                print("IPCamera Pro: Yalnızca token içindeki fazladan boşluk temizlendi.",
                      file=sys.stderr, flush=True)
            self.reply(200,
                "<!doctype html><html lang='tr'><meta charset='utf-8'>"
                "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                "<body style='background:#0a101a;color:white;font:18px system-ui;padding:26px'>"
                "<h2>✅ Adres bilgisayara ulaştı!</h2>"
                "<p>Fedora terminaline dön. Kamera bağlantısı kontrol ediliyor.</p>"
                "<p>Bu sayfayı kapatabilirsin.</p></body></html>")

    receiver = None
    port = None
    for candidate in PORTS:
        try:
            receiver = HTTPServer((address, candidate), Receiver)
            port = candidate
            break
        except OSError:
            continue
    if receiver is None:
        raise RuntimeError("Kullanılabilir bağlantı noktası bulunamadı")

    receiver.timeout = 0.5

    # Android v0.2 can discover this ephemeral pairing server using a
    # local UDP broadcast and the 6-digit code. The discovery reply contains
    # ONLY the LAN address and HTTP port, never the PIN or camera token.
    # Use the same port as HTTP; UDP and TCP are independent sockets.
    discovery_done = threading.Event()
    discovery_socket = None
    discovery_thread = None
    try:
        discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        discovery_socket.bind(("0.0.0.0", port))
        discovery_socket.settimeout(0.5)

        def serve_discovery():
            greeting = f"IPCAM_PRO_PAIR_V1|{address}|{port}".encode("ascii")
            while not discovery_done.is_set():
                try:
                    data, peer = discovery_socket.recvfrom(256)
                    if (data == b"IPCAM_PRO_DISCOVER_V1"
                            and ipaddress.ip_address(peer[0]) in network
                            and result["url"] is None):
                        discovery_socket.sendto(greeting, peer)
                except socket.timeout:
                    pass
                except (OSError, ValueError):
                    if discovery_done.is_set():
                        break
        discovery_thread = threading.Thread(
            target=serve_discovery, name="ipcam-discovery", daemon=True)
        discovery_thread.start()
    except OSError:
        if discovery_socket is not None:
            discovery_socket.close()
            discovery_socket = None
        print("Otomatik keşif portu kullanılamıyor; tarayıcıdan eşleştirme açık.",
              file=sys.stderr, flush=True)

    print("", file=sys.stderr)
    print("📱 Telefonunda Chrome/Brave'den şu adresi aç:", file=sys.stderr)
    print(f"   http://{address}:{port}", file=sys.stderr)
    print("Eşleştirme kodu (6 hane): " + code, file=sys.stderr)
    print("Yeni Android uygulamasında: Yayını Başlat → PC'ye Bağlan → "
          "yalnızca 6 haneli kodu gir. URL yazma!", file=sys.stderr)
    print("Eski APK: telefondaki tarayıcıdan adresi açıp URL'yi forma yapıştır.",
          file=sys.stderr)
    print("İşlem 4 dakika içinde tamamlanmalı. Ctrl+C iptal eder.", file=sys.stderr)
    print("", file=sys.stderr, flush=True)

    try:
        while time.monotonic() < result["deadline"] and result["url"] is None:
            receiver.handle_request()
            if result["attempts"] >= 5:
                raise RuntimeError("Çok fazla başarısız eşleştirme denemesi")
        if result["url"] is None:
            raise RuntimeError("Süre doldu. ./scripts/connect-phone.sh --pair ile tekrar dene")
        # stdout intentionally contains only the secret URL to pipe into Bash.
        print(result["url"], flush=True)
    finally:
        discovery_done.set()
        if discovery_socket is not None:
            discovery_socket.close()
        if discovery_thread is not None:
            discovery_thread.join(timeout=1.0)
        receiver.server_close()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("Eşleştirme iptal edildi.", file=sys.stderr)
        raise SystemExit(130)
    except Exception as error:
        print("IPCamera Pro eşleştirme hatası: " + str(error), file=sys.stderr)
        raise SystemExit(1)
