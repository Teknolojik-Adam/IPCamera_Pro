#!/usr/bin/env bash
# IPCamera Pro Linux: connect to the Android app without any localhost web UI.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$ROOT/build/ipcam-cli"
if [[ ! -x "$CLI" ]]; then
  echo "Linux engine missing. Run ./scripts/build.sh first." >&2
  exit 1
fi
MODE="run"
ENTRY="auto"
BACKEND="auto"
URL=""
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --check) MODE="check" ;;
    --pair) ENTRY="pair" ;;
    --manual) ENTRY="manual" ;;
    --v4l2) BACKEND="v4l2" ;;
    --pipewire) BACKEND="pipewire" ;;
    --help|-h)
      echo "Kullanım: ./scripts/connect-phone.sh [--pair|--manual|--check|--v4l2|--pipewire]"
      echo "Varsayılan: kısa 6 haneli kodla Wi-Fi eşleştirme."
      echo "V4L2 aygıtı hazırsa gerçek /dev/videoN arayüzünü otomatik tercih eder."
      echo "--v4l2 yalnız V4L2 aygıtı varsa başlatır; PipeWire'a gizlice geçmez."
      echo "--pipewire eski sanal PipeWire kaynağını zorlar."
      echo "--manual URL'yi terminalden alır; --check yalnızca telefon yayını test eder."
      exit 0
      ;;
    http://*) URL="$1"; ENTRY="argument" ;;
    *) echo "Bilinmeyen seçenek. Yardım: --help" >&2; exit 2 ;;
  esac
  shift
done

# Find only our own labelled v4l2loopback device, not an arbitrary camera.
find_hw_device() {
  local dev info
  shopt -s nullglob
  for dev in /dev/video[0-9]*; do
    info="$(v4l2-ctl --device="$dev" --all 2>/dev/null || true)"
    if [[ "$info" == *"v4l2 loopback"* && "$info" == *"IPCamera Pro"* ]]; then
      echo "$dev"
      return 0
    fi
  done
  return 1
}

V4L2_DEVICE=""
if command -v v4l2-ctl >/dev/null 2>&1; then
  V4L2_DEVICE="$(find_hw_device || true)"
fi
if [[ "$BACKEND" == "auto" ]]; then
  if [[ -n "$V4L2_DEVICE" ]]; then BACKEND="v4l2"; else BACKEND="pipewire"; fi
fi

# Fail fast when hardware mode is explicitly requested; do not ask the user
# for a pairing PIN if /dev/videoN is not ready yet.
if [[ "$BACKEND" == "v4l2" && -z "$V4L2_DEVICE" && "$MODE" == "run" ]]; then
  echo "HATA: Henüz IPCamera Pro /dev/videoN V4L2 aygıtı yok." >&2
  echo "Önce masaüstündeki IPCamera Pro - Donanım Kamerasını Kur kısayolunu aç." >&2
  exit 3
fi

if [[ "$ENTRY" == "auto" ]]; then
  if [[ "$MODE" == "check" ]]; then
    ENTRY="manual"
  elif [[ -t 0 ]]; then
    ENTRY="pair"
  else
    ENTRY="manual"
  fi
fi

if [[ "$ENTRY" == "pair" ]]; then
  # Python writes only the successful secret URL to stdout.
  # The temporary local web pairing address and PIN go to the terminal.
  URL="$(python3 "$ROOT/scripts/phone-handoff.py")"
elif [[ "$ENTRY" == "manual" ]]; then
  echo "Android: Yayını Başlat → Bağlantı adresini kopyala."
  echo "Telefon ve Fedora bilgisayar aynı Wi-Fi'de olmalı."
  if [[ -t 0 ]]; then
    read -r -s -p "Telefon URL'sini yapıştır (gizli giriş; Ctrl+Shift+V): " URL
    echo
  else
    IFS= read -r URL || [[ -n "$URL" ]]
  fi
fi

URL="$(printf '%s' "$URL" | python3 "$ROOT/scripts/phone_url.py")"

# Preflight HTTP auth AND actual JPEG frames *before* PipeWire startup.
# Stdin hides the token from process argument listings during preflight.
printf '%s' "$URL" | python3 "$ROOT/scripts/phone-preflight.py"
if [[ "$MODE" == "check" ]]; then
  echo "IPCamera Pro: Bağlantı kontrolü başarılı; sanal kamera başlatılmadı."
  exit 0
fi


if [[ "$BACKEND" == "v4l2" ]]; then
  if [[ -z "$V4L2_DEVICE" ]]; then
    echo "HATA: /dev/videoN V4L2 sanal kamera aygıtı henüz oluşturulmadı." >&2
    echo "Masaüstü: IPCamera Pro - Donanım Kamerasını Kur kısayolunu kullan." >&2
    echo "Secure Boot/MOK tamamlanmadan donanım kamerası varmış gibi gösterilmeyecek." >&2
    exit 3
  fi
  echo "IPCamera Pro: V4L2 video aygıtı: $V4L2_DEVICE"
  echo "Kamoso, Brave ve V4L2 kamera kullanan programlarda IPCamera Pro'yu seç."
  echo "Aygıt yalnızca yayın devam ederken CAPTURE kamerası olarak görünebilir."
  echo "Telefon görüntüsü aktarılıyor. Çıkış: Ctrl+C."
  exec "$CLI" --backend v4l2 --device "$V4L2_DEVICE" --input "$URL" --width 1280 --height 720 --fps 15 --max-retries 3

fi

if command -v pw-cli >/dev/null; then
  if timeout 4s pw-cli ls Node 2>/dev/null | grep -q 'node.name = "ipcamera-pro"'; then
    echo "IPCamera-Pro PipeWire kaynağı zaten başka işlemde açık." >&2
    echo "Eski işlemi kapat veya V4L2 aygıtı için --v4l2 kullan." >&2
    exit 3
  fi
fi

echo "IPCamera Pro: V4L2 /dev/videoN aygıtı yok, PipeWire uyumluluk moduyla açılıyor."
echo "Bu mod gerçek V4L2 kamera aygıtı oluşturmaz."
echo "Masaüstü: IPCamera Pro - Donanım Kamerasını Kur kısayoluyla V4L2'yi etkinleştir."
echo "Durdurmak için Ctrl+C."
exec "$CLI" --backend pipewire --input "$URL" --width 1280 --height 720 --fps 15 --max-retries 3
