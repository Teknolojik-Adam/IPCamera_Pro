#!/usr/bin/env bash
# Android MJPEG wire-protocol checks on the Fedora host; no phone required.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${JAVA_HOME:-}" ]]; then
  CANDIDATE=/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr
  if [[ -x "$CANDIDATE/bin/javac" ]]; then JAVA_HOME="$CANDIDATE"; fi
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
elif command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  JAVAC="$(command -v javac)"
  JAVA="$(command -v java)"
else
  echo "SKIP: JDK 17+ not installed" >&2
  exit 77
fi
OUT="$ROOT/.build/host-test-classes"
mkdir -p "$OUT"
"$JAVAC" --release 17 -encoding UTF-8 -d "$OUT" \
  "$ROOT/app/src/main/java/com/ipcamerapro/mobile/MjpegServer.java" \
  "$ROOT/tests/com/ipcamerapro/mobile/MjpegServerSmoke.java" \
  "$ROOT/app/src/main/java/com/ipcamerapro/mobile/WifiSessionGuard.java" \
  "$ROOT/tests/com/ipcamerapro/mobile/WifiSessionGuardSmoke.java"
"$JAVA" -cp "$OUT" com.ipcamerapro.mobile.MjpegServerSmoke
"$JAVA" -cp "$OUT" com.ipcamerapro.mobile.WifiSessionGuardSmoke
