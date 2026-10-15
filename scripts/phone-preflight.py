#!/usr/bin/env python3
"""Check IPCamera Pro Android connectivity *before* allocating a PipeWire camera.

URL is supplied through stdin, not argv or shell history. Never log the
bearer token or the full URL. Exit codes: 0=live JPEG; 2=invalid URL,
3=expired/wrong token; 4=network/server failure; 5=camera not producing.
"""
import ipaddress
import re
import socket
import sys
import time
from urllib.parse import parse_qs, urlsplit


def fail(message, status):
    print("IPCamera Pro: " + message, file=sys.stderr, flush=True)
    raise SystemExit(status)


def validate(url):
    try:
        if len(url) > 512 or url != url.strip() or any(c in url for c in "\r\n\x00"):
            raise ValueError("invalid URL length/characters")
        info = urlsplit(url)
        host = ipaddress.ip_address(info.hostname)
        allowed = tuple(ipaddress.ip_network(net) for net in
                        ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
        if not isinstance(host, ipaddress.IPv4Address) or not any(host in block for block in allowed):
            raise ValueError("not a private IPv4 address")
        if (info.scheme != "http" or info.port != 8787 or info.path != "/video"
                or info.username or info.password or info.fragment):
            raise ValueError("unexpected phone URL")
        fields = parse_qs(info.query, keep_blank_values=True, strict_parsing=True)
        if list(fields) != ["token"] or len(fields["token"]) != 1:
            raise ValueError("token not present exactly once")
        token = fields["token"][0]
        if not re.fullmatch(r"[A-Za-z0-9_-]{32}", token):
            raise ValueError("invalid token format")
        # Normalize header, but never re-encode or print the secret URL.
        return info.hostname, f"{info.path}?{info.query}"
    except (ValueError, TypeError, KeyError, AssertionError):
        fail("Bağlantı adresi geçersiz. Telefonda Yayını Başlat → yeni adresi kopyala.", 2)


def check(url):
    host, target = validate(url)
    print("IPCamera Pro: Telefon bağlantısı ve anahtar kontrol ediliyor…", flush=True)
    try:
        with socket.create_connection((host, 8787), timeout=4) as sock:
            sock.settimeout(4)
            request = (
                f"GET {target} HTTP/1.1\r\n"
                f"Host: {host}:8787\r\n"
                "User-Agent: IPCameraPro-Linux/0.2\r\n"
                "Connection: close\r\n\r\n"
            )
            sock.sendall(request.encode("ascii"))
            buffer = b""
            while b"\r\n\r\n" not in buffer:
                part = sock.recv(4096)
                if not part:
                    fail("Telefon bağlantıyı görüntü göndermeden kapattı.", 4)
                buffer += part
                if len(buffer) > 16384:
                    fail("Telefon beklenmeyen HTTP başlıkları gönderdi.", 4)
            header, payload = buffer.split(b"\r\n\r\n", 1)
            status_line = header.split(b"\r\n", 1)[0]
            if status_line.startswith(b"HTTP/1.1 403 ") or status_line.startswith(b"HTTP/1.0 403 "):
                fail("403 Forbidden: erişim anahtarı eski veya yanlış. "
                     "Telefonda Durdur → Yayını Başlat yap, YENİ adresi kopyala.", 3)
            if status_line.startswith(b"HTTP/1.1 503 ") or status_line.startswith(b"HTTP/1.0 503 "):
                fail("Telefonun kamera alıcıları dolu. Açık diğer yayın bağlantılarını kapat.", 4)
            if not (status_line.startswith(b"HTTP/1.1 200 ") or status_line.startswith(b"HTTP/1.0 200 ")):
                code = status_line.decode("ascii", "replace").split()
                fail("Telefon beklenmeyen HTTP durumu verdi: "
                     + (code[1] if len(code) > 1 else "?"), 4)
            if b"multipart/x-mixed-replace" not in header.lower():
                fail("Telefon MJPEG akışı sunmuyor. Uygulamanın yayın durumunu kontrol et.", 4)
            deadline = time.monotonic() + 9
            # Wait for an actual JPEG SOI, not merely HTTP 200.
            while time.monotonic() < deadline:
                if b"Content-Type: image/jpeg" in payload and b"\xff\xd8" in payload:
                    print("IPCamera Pro: Bağlantı doğrulandı, kamera JPEG kareleri geliyor. ✓",
                          flush=True)
                    return
                try:
                    sock.settimeout(min(1.5, max(0.1, deadline - time.monotonic())))
                    block = sock.recv(65536)
                except socket.timeout:
                    continue
                if not block:
                    fail("Telefon bağlantıyı JPEG görüntü göndermeden kapattı.", 5)
                payload = (payload + block)[-262144:]
            fail("Anahtar doğru ama 9 saniyede kamera karesi gelmedi. "
                 "Telefonda canlı önizleme ve kare sayacını kontrol et.", 5)
    except (socket.timeout, ConnectionRefusedError, ConnectionResetError, OSError) as exc:
        if isinstance(exc, TimeoutError):
            explanation = "Telefona zamanında ulaşılamadı."
        elif isinstance(exc, ConnectionRefusedError):
            explanation = "Telefon 8787 bağlantısını reddetti; yayını başlat."
        else:
            explanation = "Telefonun kamera bağlantısı kesildi veya açılamadı."
        fail(explanation + " Telefon ve bilgisayar aynı Wi-Fi'de olmalı.", 4)


if __name__ == "__main__":
    raw = sys.stdin.read(1024).rstrip("\r\n")
    check(raw)
