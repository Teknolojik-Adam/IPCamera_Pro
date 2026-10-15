#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -x "$ROOT/build/ipcam-cli" ]]; then
  echo "Executable missing. Run ./scripts/build.sh first" >&2
  exit 1
fi
exec "$ROOT/build/ipcam-cli" "$@"
