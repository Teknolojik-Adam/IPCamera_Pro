#!/usr/bin/env bash
# IPCamera Pro — controlled V4L2 / Secure Boot setup for the local Fedora host.
# Safe by default: no module unload, no Secure Boot disabling, no automatic reboot.
# Run from user's desktop terminal; pkexec requires visible local approval.
set -euo pipefail

find_device() {
  local dev info
  shopt -s nullglob
  for dev in /dev/video[0-9]*; do
    info="$(/usr/bin/v4l2-ctl --device="$dev" --all 2>/dev/null || true)"
    if [[ "$info" == *"v4l2 loopback"* && "$info" == *"IPCamera Pro"* ]]; then
      printf '%s\n' "$dev"
      return 0
    fi
  done
  return 1
}

status() {
  printf '=== IPCamera Pro — V4L2 / Secure Boot durumu ===\n'
  printf 'Çekirdek: %s\n' "$(uname -r)"
  /usr/bin/mokutil --sb-state || true
  printf 'Modülün imzalayanı: %s\n' "$(/usr/sbin/modinfo -F signer v4l2loopback 2>/dev/null || true)"
  if /usr/bin/lsmod | /usr/bin/grep -q '^v4l2loopback '; then
    echo 'v4l2loopback: YÜKLÜ'
  else
    echo 'v4l2loopback: yüklü değil'
  fi
  if device="$(find_device)"; then
    echo "IPCamera Pro kamera aygıtı: $device"
  else
    echo 'IPCamera Pro kamera aygıtı: HENÜZ YOK'
  fi
}

# Compare the Akmods certificate against ACTUAL enrolled MOK certificates.
# mokutil --test-key alone is not proof that the cert was imported into
# MokListRT: it may also check other kernel/firmware trust paths.
# Module signature certificate CN alone is not proof of the right key.
# Compare DER bytes for already-enrolled MOK and signer certificate serial.
mok_cert_enrolled() {
  local expected="$1" temp entry known actual
  local found=1
  temp="$(mktemp -d /tmp/ipcam-mok-verify.XXXXXXXX)" || return 2
  chmod 0700 "$temp"
  if ! (cd "$temp" && /usr/bin/mokutil --export >/dev/null 2>&1); then
    rm -rf -- "$temp"
    return 2
  fi
  expected="$(/usr/bin/openssl x509 -inform DER -in "$expected" -outform DER 2>/dev/null |
    /usr/bin/sha256sum | /usr/bin/cut -d' ' -f1)"
  for entry in "$temp"/*.der; do
    [[ -f "$entry" ]] || continue
    if ! known="$(/usr/bin/openssl x509 -inform DER -in "$entry" -outform DER 2>/dev/null |
                     /usr/bin/sha256sum | /usr/bin/cut -d' ' -f1)"; then
      rm -rf -- "$temp"
      return 2
    fi
    if [[ "$expected" == "$known" ]]; then
      found=0
      break
    fi
  done
  rm -rf -- "$temp"
  return "$found"
}

root_setup() {
  if [[ "$EUID" -ne 0 ]]; then
    echo 'Yönetici yetkisi gerekli.' >&2
    exit 1
  fi
  local cert='/etc/pki/akmods/certs/public_key.der'
  local kernel signer subject cert_cn enrolled_status=0 module_key cert_serial
  kernel="$(uname -r)"
  status

  if device="$(find_device)"; then
    echo "Kamera aygıtı zaten hazır: $device"
    echo 'Artık telefonun 6 haneli kodla bağlantısını başlatabilirsin.'
    exit 0
  fi
  if /usr/bin/lsmod | /usr/bin/grep -q '^v4l2loopback '; then
    echo 'v4l2loopback başka ayarlarla zaten yüklü. Başka programı bozmamak için değiştirmiyorum.' >&2
    exit 3
  fi
  if ! /usr/sbin/modinfo -n v4l2loopback >/dev/null 2>&1; then
    echo "Bu çekirdek ($kernel) için v4l2loopback modülü bulunamadı." >&2
    echo 'Fedora kernel paketleriyle uyumlu akmod-v4l2loopback gerekli.' >&2
    exit 4
  fi
  if [[ ! -s "$cert" ]]; then
    echo "Akmods genel sertifikası bulunamadı: $cert" >&2
    echo 'Sertifika ve modülün imzası tutarlı şekilde oluşturulmalı.' >&2
    exit 5
  fi

  signer="$(/usr/sbin/modinfo -F signer v4l2loopback)"
  subject="$(/usr/bin/openssl x509 -inform DER -in "$cert" -noout -subject -nameopt RFC2253)"
  cert_cn="$(printf '%s\n' "$subject" | /usr/bin/sed -n 's/.*CN=\([^,]*\).*/\1/p')"
  echo "Mevcut imzalı modül: $signer"
  echo "Sertifika adı: $cert_cn"
  if [[ -z "$signer" || -z "$cert_cn" ]]; then
    echo 'Sertifika veya modül imzası geçerli biçimde okunamadı; değişiklik yapmıyorum.' >&2
    exit 6
  fi
  if [[ "$signer" != "$cert_cn" ]]; then
    echo 'UYARI: Modülü imzalayan anahtar ile Akmods sertifikası farklı.' >&2
    echo 'Yanlış sertifikayı MOK sistemine eklemeyeceğim.' >&2
    echo "Modül, mevcut sertifikayla yeniden imzalanmalı. Önce raporla: signer=$signer, cert=$cert_cn" >&2
    exit 7
  fi
  # The Linux kernel module modinfo 'sig_key' is the PKCS#7 signer's
  # X.509 *certificate serial number*, NOT X509v3 Subject Key Identifier.
  # Verified with a temporary sign-file signed test module (no system writes).
  # Guard against unrelated certificates with identical subject CNs.
  module_key="$(/usr/sbin/modinfo -F sig_key v4l2loopback |
      /usr/bin/tr -d ':' | /usr/bin/tr '[:lower:]' '[:upper:]')"
  cert_serial="$(/usr/bin/openssl x509 -inform DER -in "$cert" -noout -serial |
      /usr/bin/sed 's/^serial=//' | /usr/bin/tr '[:lower:]' '[:upper:]')"
  if [[ -z "$module_key" || -z "$cert_serial" ]]; then
    echo 'HATA: Modül imza kimliği veya sertifika seri numarası okunamadı.' >&2
    exit 7
  fi
  echo "Modül imza sertifikası seri numarası: $module_key"
  echo "Akmods sertifikası seri numarası:       $cert_serial"
  if [[ "$cert_serial" != "$module_key" ]]; then
    echo 'HATA: Modülü imzalayan sertifika ile mevcut Akmods sertifikası farklı.' >&2
    echo 'Bu sertifikayı MOK kaydına göndermiyorum; modülün yeniden imzalanması gerekebilir.' >&2
    exit 7
  fi
  echo 'PASS: Modülün imzası ile mevcut sertifikanın seri numarası eşleşiyor.'

  mok_cert_enrolled "$cert" || enrolled_status=$?
  if [[ "$enrolled_status" -gt 1 ]]; then
    echo 'HATA: Kayıtlı MOK sertifikalarının kimlikleri okunamadı.' >&2
    echo 'Güvenlik için yeni sertifika kaydı veya modül yüklemesi yapmıyorum.' >&2
    exit 8
  fi
  if [[ "$enrolled_status" -eq 0 ]]; then
    echo 'Akmods sertifikası GERÇEKTEN MOK listesinde kayıtlı.'
    echo 'Modülü yüklemeyi deniyorum...'
    if ! /usr/sbin/modprobe v4l2loopback devices=1 video_nr=-1 \
          'card_label=IPCamera Pro' exclusive_caps=1; then
      echo 'HATA: MOK listesindeki sertifikaya rağmen çekirdek modül imzasını reddetti.' >&2
      echo 'Çekirdeğin güven zinciri veya modül imzasının anahtar kimliği farklı olabilir.' >&2
      echo 'Yeni anahtar üretme, Secure Boot kapatma ve MOK silme yapmıyorum.' >&2
      echo 'Günlük: journalctl -k -n 60 | grep -Ei "module|key|signature"' >&2
      exit 9
    fi
    /usr/bin/udevadm settle --timeout=5 || true
    for i in $(seq 1 25); do
      if device="$(find_device)"; then
        printf '\nBAŞARILI: IPCamera Pro V4L2 sanal aygıtı: %s\n' "$device"
        echo 'Şimdi Fedora IPCamera Pro - Donanım Kamerasına Bağlan kısayolunu aç.'
        exit 0
      fi
      sleep 0.2
    done
    echo 'Modül yüklendi ama IPCamera Pro aygıtı bulunamadı.' >&2
    /usr/bin/v4l2-ctl --list-devices || true
    exit 10
  fi

  echo
  echo 'Doğrulandı: Akmods anahtarı kayıtlı MOK sertifikaları arasında YOK.'
  echo 'Yalnızca Fedora resmî CA anahtarının görünmesi buna yeterli değildir.'
  echo 'Bu adım yalnızca AÇIK ANAHTAR sertifikasını MOK onay listesine gönderir.'
  echo 'Secure Boot KAPATILMAZ. Modül yüklenmez ve bilgisayar otomatik yeniden başlamaz.'
  echo
  echo 'Şimdi senden yalnızca bu bilgisayarda kullanacağın GEÇİCİ MOK şifresi istenecek.'
  echo 'Şifreyi buraya veya ChatGPT sohbetine yazma!'
  echo 'MOK kaydı başlatılıyor...'
  # Skip *kernel builtin keyring* shortcut and put the exact verified
  # certificate in MokListRT. Previously --test-key gave a misleading true
  # while MokListRT held only fedoraca and modprobe was rejected by kernel.
  /usr/bin/mokutil --ignore-keyring --import "$cert"
  echo 'Bekleyen MOK sertifikaları:'
  # The import command can return success while --list-new reports nothing.
  # Do not promise an enrollment request in that situation.
  pending="$(/usr/bin/mokutil --list-new --short 2>&1)" || {
    echo 'UYARI: Bekleyen MOK kayıtları okunamadı; kayıt doğrulanamadı.' >&2
    echo 'Yeniden başlatmadan önce yerel terminalde sudo mokutil --list-new çalıştır.' >&2
    exit 11
  }
  printf '%s\n' "$pending"
  if [[ -z "${pending//[[:space:]]/}" ]]; then
    echo 'UYARI: mokutil --import başarılı döndü ancak --list-new BOŞ.' >&2
    echo 'MOK kaydı doğrulanamadı. Yeniden başlatmada mavi ekran geleceği garanti değil.' >&2
    echo 'Önce yerel terminalde sudo mokutil --list-new ile kontrol et.' >&2
    exit 11
  fi
  echo
  echo 'MOK KAYDI TALEBİ DOĞRULANDI (henüz sisteme kayıtlı değil).'
  echo 'Bilgisayarı kendin yeniden başlat.'
  echo 'Açılışta mavi MOK ekranı: Enroll MOK -> Continue -> Yes.'
  echo 'Biraz önce seçtiğin MOK şifresini GİR. Klavye US/QWERTY olabilir.'
  echo 'Onay sonrası bilgisayar tekrar açılacak.'
  echo 'Aynı masaüstü kısayolunu TEKRAR açınca modül yüklenip /dev/videoN oluşacak.'
}

case "${1:---status}" in
  --status) status ;;
  --root-setup) root_setup ;;
  --setup)
    status
    printf '\nSanal V4L2 aygıtını kurmak için yönetici izni istenecek.\n'
    printf 'Secure Boot kapatılmayacak ve otomatik yeniden başlatma yapılmayacak.\n'
    if command -v pkexec >/dev/null 2>&1; then
      exec /usr/bin/pkexec /usr/bin/bash "${BASH_SOURCE[0]}" --root-setup
    else
      echo 'pkexec yok. Yerel Fedora terminalinden sudo ile çalıştırılmalı.' >&2
      exit 2
    fi
    ;;
  --help|-h)
    echo 'Kullanım: ./scripts/setup-hardware-camera.sh [--status|--setup]'
    echo '--status: sadece oku; --setup: yerel yetki iste, imza kaydı/modül yüklemeye geç'
    ;;
  *) echo 'Bilinmeyen komut.' >&2; exit 2 ;;
esac
