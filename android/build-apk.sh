set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
PLATFORM="${ANDROID_SDK_ROOT}/platforms/android-35/android.jar"
TOOLS="${ANDROID_SDK_ROOT}/build-tools/36.0.0"
if [[ -z "${JAVA_HOME:-}" ]]; then
  CANDIDATE=/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr
  if [[ -x "$CANDIDATE/bin/javac" ]]; then JAVA_HOME="$CANDIDATE"; fi
fi
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17+ (Android Studio JBR works)}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
for tool in java javac keytool zip; do
  command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }
done
[[ -f "$PLATFORM" ]] || { echo "Missing $PLATFORM" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner; do
  [[ -x "$TOOLS/$tool" ]] || { echo "Missing $TOOLS/$tool" >&2; exit 1; }
done

OUT="$ROOT/.build"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/compile"
find "$OUT/classes" "$OUT/dex" -type f -delete
echo "[1/6] Java sources..."
mapfile -d '' SOURCES < <(find "$ROOT/app/src/main/java" -name '*.java' -print0)
[[ ${#SOURCES[@]} -gt 0 ]] || { echo "No Android sources" >&2; exit 1; }
javac --release 17 -encoding UTF-8 -classpath "$PLATFORM" \
  -d "$OUT/classes" "${SOURCES[@]}"

echo "[2/6] Android resources..."

python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" <<'PYMANIFEST'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
assert 'package="' not in source
source=source.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.ipcamerapro.mobile">')
Path(sys.argv[2]).write_text(source)
PYMANIFEST
"$TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/compile/resources.zip"
"$TOOLS/aapt2" link \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 5 --version-name 0.3.2 \
    -I "$PLATFORM" -o "$OUT/package-unaligned.apk" \
    -R "$OUT/compile/resources.zip" --auto-add-overlay

echo "[3/6] DEX bytecode..."
mapfile -d '' CLASS_FILES < <(find "$OUT/classes" -name '*.class' -print0)
"$TOOLS/d8" --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" "${CLASS_FILES[@]}"

echo "[4/6] Package / align..."
zip -q -j "$OUT/package-unaligned.apk" "$OUT/dex/classes.dex"
"$TOOLS/zipalign" -f -p 4 "$OUT/package-unaligned.apk" "$OUT/package-aligned.apk"

echo "[5/6] Sign debug APK..."
KEYSTORE="$OUT/ipcamera-debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 3072 -validity 3650 \
    -dname "CN=Android Debug,O=IPCamera Pro,C=TR" -noprompt >/dev/null 2>&1
fi
APK="$ROOT/IPCameraPro-Android.apk"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/package-aligned.apk"

echo "[6/6] Verify..."
"$TOOLS/apksigner" verify --verbose "$APK" | head -12
"$TOOLS/aapt2" dump badging "$APK" | grep -E '^package:|^sdkVersion:|^targetSdkVersion:|^launchable-activity:' | head -8
ls -lh "$APK"
echo "Debug APK built: $APK"
