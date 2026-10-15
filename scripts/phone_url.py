#!/usr/bin/env python3
"""Validate/normalize a phone's LAN MJPEG URL without printing its bearer secret.

Android generates a strict 32-character URL-safe base64 token. Certain paste
workflows insert a space inside the token, often percent-encoded as %20.
Only strip whitespace *inside the token*, and only if exactly 32 safe
characters remain. Never guess or synthesize token characters.
"""
import ipaddress
import re
import sys
from urllib.parse import parse_qs, urlsplit


class PhoneUrlError(ValueError):
    pass


def normalize_phone_url(text, sender_ip=None):
    if not isinstance(text, str) or not 0 < len(text) <= 512:
        raise PhoneUrlError("Adres boş veya çok uzun. Uygulamadan yeniden kopyala.")
    if text != text.strip() or any(ch in text for ch in "\r\n\x00"):
        raise PhoneUrlError("URL'nin başında veya sonunda boşluk var. Yeniden kopyala.")
    try:
        parts = urlsplit(text)
        host = ipaddress.ip_address(parts.hostname)
        allowed = tuple(ipaddress.ip_network(item) for item in
                        ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
        if not isinstance(host, ipaddress.IPv4Address) or not any(host in net for net in allowed):
            raise PhoneUrlError("Telefonun Wi-Fi IPv4 adresi geçerli değil.")
        if sender_ip is not None and str(host) != sender_ip:
            raise PhoneUrlError(
                "Tarayıcının IP'si ile kamera adresi farklı. Eşleştirme sayfasını "
                "yayın yapan TELEFONDAN aç; VPN veya mobil veri bağlantısını kontrol et.")
        if (parts.scheme != "http" or parts.port != 8787 or parts.path != "/video"
                or parts.username or parts.password or parts.fragment):
            raise PhoneUrlError(
                "Adres IPCamera Pro formatında değil. Uygulamadan Bağlantı adresini kopyala.")
        fields = parse_qs(parts.query, keep_blank_values=True, strict_parsing=True)
        if len(fields) != 1 or set(fields) != {"token"} or len(fields["token"]) != 1:
            raise PhoneUrlError("URL'de bir adet token bulunmalı.")
        value = fields["token"][0]
        repaired = value != value.translate({ord(ch): None for ch in value if ch.isspace()})
        clean = "".join(char for char in value if not char.isspace())
        if not re.fullmatch(r"[A-Za-z0-9_-]{32}", clean):
            raise PhoneUrlError(
                "Anahtar 32 karakter değil veya geçersiz karakter içeriyor. "
                "Boşluk eklemeden uygulamanın KOPYALA düğmesini kullan.")
        return f"http://{host}:8787/video?token={clean}", repaired
    except PhoneUrlError:
        raise
    except (AttributeError, TypeError, ValueError):
        raise PhoneUrlError(
            "Bağlantı adresi okunamadı. IPCamera Pro uygulamasından yeniden kopyala.")


if __name__ == "__main__":
    try:
        url, repaired = normalize_phone_url(sys.stdin.read(1024).rstrip("\r\n"))
        if repaired:
            print("IPCamera Pro: URL'deki fazladan boşluk / %20 otomatik temizlendi.",
                  file=sys.stderr)
        # Secret URL goes to stdout only for command substitution in Bash.
        print(url)
    except PhoneUrlError as exc:
        print("IPCamera Pro: " + str(exc), file=sys.stderr)
        raise SystemExit(2)
