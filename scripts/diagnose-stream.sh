#!/usr/bin/env bash
# IPCamera Pro — siyah görüntü zincir tanısı.
# Zincirin her halkasını ayrı ayrı ölçer: Android kamera → JPEG → ağ →
# Fedora ipcam-cli → PipeWire düğümü → uygulama (tarayıcı/Kamoso).
# Kullanım:
#   ./scripts/diagnose-stream.sh                      # yalnız Fedora tarafı
#   ./scripts/diagnose-stream.sh "http://TELEFON:8787/video?token=..."  # uçtan uca
# URL gizli girişle de verilebilir (argüman yoksa sorulur).
set -uo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PHONE_IP_DEFAULT="192.168.111.10"
URL="${1:-}"

say()  { printf '%s\n' "$*"; }
hdr()  { printf '\n=== %s ===\n' "$*"; }
ok()   { say "  [OK]   $*"; }
warn() { say "  [UYARI] $*"; }
bad()  { say "  [SORUN] $*"; }

hdr "1/5  Araçlar"
for t in curl ffmpeg python3; do
  command -v "$t" >/dev/null && ok "$t kurulu" || bad "$t eksik (sudo dnf install $t)"
done
if command -v gst-inspect-1.0 >/dev/null; then
  gst-inspect-1.0 pipewiresink >/dev/null 2>&1 \
    && ok "pipewiresink eklentisi kurulu (PipeWire kamera düğümü oluşturulabilir)" \
    || bad "pipewiresink YOK → PipeWire kamerası hiç oluşamaz. Kur: sudo dnf install pipewire-gstreamer gstreamer1-plugins-good"
else
  bad "gst-inspect-1.0 eksik → sudo dnf install gstreamer1-devel-tools (veya gstreamer1)"
fi
command -v pw-cli >/dev/null && ok "pw-cli kurulu" || warn "pw-cli eksik (pipewire paketi)"
command -v adb >/dev/null && ok "adb kurulu" || warn "adb eksik; telefon tarafı kontrolleri atlanacak"

hdr "2/5  Fedora kamera üreticisi (ipcam-cli / PipeWire / V4L2)"
if pgrep -af ipcam-cli | grep -v pgrep; then
  ok "ipcam-cli çalışıyor"
else
  bad "ipcam-cli ÇALIŞMIYOR → Fedora'da kare üreten hiçbir şey yok; tarayıcıdaki görüntü bu yüzden siyah/dondurulmuş olur."
  say "        Başlat: ./scripts/connect-phone.sh   (telefon yayını açıkken)"
fi
if command -v pw-cli >/dev/null; then
  if timeout 4s pw-cli ls Node 2>/dev/null | grep -q 'node.name = "ipcamera-pro"'; then
    ok "PipeWire 'ipcamera-pro' Video/Source düğümü mevcut"
  else
    bad "PipeWire 'ipcamera-pro' düğümü YOK → tarayıcı/Kamoso gerçek kare alamaz."
  fi
fi
shopt -s nullglob
videos=(/dev/video[0-9]*)
if ((${#videos[@]})); then
  ok "V4L2 aygıtları: ${videos[*]}"
  for d in "${videos[@]}"; do
    v4l2-ctl --device="$d" --all 2>/dev/null | grep -m1 "Card type" | sed 's/^/        /'
  done
else
  warn "/dev/video* yok (PipeWire modunda normal; Chrome tabanlı tarayıcılar PipeWire kamerasını göremeyebilir — Firefox veya Kamoso ile test et)"
fi

hdr "3/5  Telefon servisi (ADB)"
if command -v adb >/dev/null && adb devices 2>/dev/null | grep -q 'device$'; then
  PHONE_STATE="$(adb shell 'dumpsys activity services com.ipcamerapro.mobile 2>/dev/null | grep -m1 -i foreground' | tr -d '\r')"
  if [[ "$PHONE_STATE" == *"foreground=true"* ]]; then
    ok "IPCameraService foreground çalışıyor"
  else
    bad "IPCameraService foreground DEĞİL → telefonda Yayını Başlat de."
  fi
  FRAMES="$(adb logcat -d -s IPCamService:I 2>/dev/null | grep 'Camera JPEG frames' | tail -3 | tr -d '\r')"
  if [[ -n "$FRAMES" ]]; then
    ok "Telefon JPEG üretiyor (logcat):"
    say "$FRAMES" | sed 's/^/        /'
    LAST_BYTES="$(say "$FRAMES" | tail -1 | grep -o 'bytes=[0-9]*' | cut -d= -f2)"
    if [[ -n "${LAST_BYTES:-}" && "$LAST_BYTES" -lt 25000 ]]; then
      warn "Son kare yalnızca ${LAST_BYTES} bayt — 1280×720 gerçek sahne genelde 40-150 KB. Çok küçük JPEG = telefon siyah kare üretiyor olabilir (4/5. adımdaki parlaklık ölçümüne bak)."
    fi
  else
    bad "logcat'te 'Camera JPEG frames' YOK → telefon kameradan hiç kare üretmemiş (izin, HAL veya başka uygulama kamerayı tutuyor olabilir)."
  fi
  ERRS="$(adb logcat -d 2>/dev/null | grep -iE 'IPCam|CameraDevice' | grep -iE 'error|fail|denied' | tail -5 | tr -d '\r')"
  [[ -n "$ERRS" ]] && { warn "Kamera hata izleri:"; say "$ERRS" | sed 's/^/        /'; }
else
  warn "ADB bağlı değil; telefon servis kontrolleri atlandı."
  say "        Bağlan: adb connect ${PHONE_IP_DEFAULT}:5555"
fi

hdr "4/5  Telefon MJPEG akışı ve parlaklık ölçümü"
if [[ -z "$URL" && -t 0 ]]; then
  say "Telefonda: Yayını Başlat → Bağlantı adresini kopyala."
  read -r -s -p "Telefon URL'sini yapıştır (gizli; boş = atla): " URL; echo
fi
if [[ -n "$URL" ]]; then
  say "$URL" | python3 "$ROOT/scripts/phone-preflight.py" && ok "HTTP + anahtar + JPEG SOI doğrulandı"
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  HOST="$(say "$URL" | sed -E 's#https?://([^:/]+).*#\1#')"
  say "  5 saniyelik akış alınıyor ($HOST)…"
  curl -sN --max-time 6 "$URL" -o "$TMP/cap.mjpeg" 2>/dev/null
  python3 - "$TMP/cap.mjpeg" "$TMP" <<'PY'
import sys, re
data = open(sys.argv[1], "rb").read()
out = sys.argv[2]
parts = re.split(rb"--ipcamframe\r?\n", data)
frames = []
for p in parts[1:]:
    m = re.search(rb"\r?\n\r?\n", p)
    if not m: continue
    body = p[m.end():]
    end = body.find(b"\xff\xd9")
    if end == -1: continue
    jpg = body[:end+2]
    if jpg.startswith(b"\xff\xd8"):
        frames.append(jpg)
for i, jpg in enumerate(frames[:3]):
    open(f"{out}/frame{i}.jpg", "wb").write(jpg)
print(f"  Ayıklanan JPEG kare: {len(frames)} (ilk 3 kaydedildi)")
PY
  shopt -s nullglob; jpgs=("$TMP"/frame*.jpg)
  if ((${#jpgs[@]})); then
    for j in "${jpgs[@]}"; do
      YAVG="$(ffmpeg -loglevel info -i "$j" -vf signalstats,metadata=mode=print -f null - 2>&1 \
              | grep -o 'YAVG=[0-9.]*' | head -1 | cut -d= -f2)"
      SIZE="$(stat -c%s "$j")"
      say "  Kare: $(basename "$j") · ${SIZE} bayt · ortalama parlaklık YAVG=${YAVG:-ölçülemedi}"
      if [[ -n "${YAVG:-}" ]]; then
        awk -v y="$YAVG" 'BEGIN{exit !(y<22)}' \
          && bad "BU KARE TAMAMEN SİYAH (YAVG≈16). Sorun telefon kamerasında: uygulama içi canlı önizleme siyahsa lens/HAL; Fedora ve ağ suçsuz." \
          || ok "Kare siyah değil → telefon + ağ sağlam; sorun Fedora arka ucu veya tarayıcı seçiminde (2/5 ve 5/5. adım)."
      fi
    done
  else
    bad "Akıştan JPEG ayıklanamadı → telefon HTTP 200 verse bile kare göndermiyor (kamera HAL'i kare üretmiyor)."
  fi
else
  warn "URL verilmedi; akış ölçümü atlandı."
fi

hdr "5/5  Uygulama / tarayıcı seçimi"
say "  • KDE Wayland'de Chrome/Brave PipeWire kamerasını varsayılan olarak GÖREMEZ;"
say "    görüyorsa WebRtcPipeWireCamera deneysel özelliği açıktır."
say "  • Kesin test: Kamoso (PipeWire'ı doğal destekler) veya Firefox."
say "  • Webcam test sitesi 1280×720@15 gösterip siyah veriyorsa: düğüm pazarlığı"
say "    yapılmış ama kare gelmiyor demektir → 2/5. adımdaki ipcam-cli/PipeWire"
say "    durumunu düzelt, yayını ./scripts/connect-phone.sh ile yeniden başlat."
say "  • Tarayıcıda birden çok kamera varsa 'IPCamera-Pro'yu açıkça seç."

hdr "Özet karar ağacı"
say "  ipcam-cli yok + pw düğümü yok      → Fedora üreticisi ölü: connect-phone.sh çıktısına bak"
say "  Telefon kare üretmiyor (logcat)    → Android kamera/izin/HAL sorunu"
say "  Telefon siyah JPEG üretiyor        → lens kapağı/karanlık değilse GSI HAL hatası;"
say "                                       uygulamada çözünürlüğü 640×480@10'a düşürüp dene"
say "  Kareler renkli ama pw düğümü yok   → pipewire-gstreamer eksik (1/5) veya ipcam-cli çökmüş"
say "  Her şey var, tarayıcı siyah        → tarayıcı başka kamerayı seçmiş ya da PipeWire desteklemiyor"
