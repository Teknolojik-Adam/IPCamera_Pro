#!/usr/bin/env bash
# A per-user systemd service that keeps the synthetic camera feed active.
# This script doesn't install any kernel module and doesn't require sudo.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
SERVICE="$SERVICE_DIR/ipcam-pro.service"
MODE="${1:---help}"
case "$MODE" in
  --help|-h)
    cat <<'TXT'
IPCamera Pro auto-feed service (no root needed):
  ./scripts/enable-auto-camera.sh --enable    Auto-start test camera at login
  ./scripts/enable-auto-camera.sh --status    Show current service status
  ./scripts/enable-auto-camera.sh --disable   Stop and remove our service
Default PipeWire mode uses no kernel module. PipeWire user service must be available.
The ZIP/project folder must stay in place for ExecStart to work.
TXT
    ;;
  --enable)
    command -v systemctl >/dev/null || { echo "systemctl not found" >&2; exit 1; }
    [[ -x "$ROOT/build/ipcam-cli" ]] || { echo "Run ./scripts/build.sh first" >&2; exit 1; }
    mkdir -p "$SERVICE_DIR"
    if [[ -e "$SERVICE" ]] && ! grep -q '^# IPCamera Pro managed user service$' "$SERVICE"; then
      echo "Refusing to replace an unrelated user service at $SERVICE" >&2
      exit 1
    fi
    # systemd requires doubled percent characters in literal paths.
    command_path="${ROOT//%/%%}/build/ipcam-cli"
    cat > "$SERVICE" <<UNIT
# IPCamera Pro managed user service
[Unit]
Description=IPCamera Pro user virtual-camera feed
After=pipewire.service wireplumber.service
Wants=pipewire.socket

[Service]
Type=simple
ExecStart="$command_path" --backend pipewire --source pattern
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
UNIT
    systemctl --user daemon-reload
    systemctl --user enable --now ipcam-pro.service
    echo "Enabled user auto-camera service. Check: systemctl --user status ipcam-pro.service"
    ;;
  --status)
    systemctl --user status ipcam-pro.service --no-pager || true
    ;;
  --disable)
    if [[ ! -e "$SERVICE" ]] || ! grep -q '^# IPCamera Pro managed user service$' "$SERVICE"; then
      echo "Our managed service was not found; no changes made."; exit 0
    fi
    systemctl --user disable --now ipcam-pro.service || true
    rm -- "$SERVICE"
    systemctl --user daemon-reload
    echo "Removed our user auto-camera service. PipeWire system configuration is unchanged."
    ;;
  *) echo "Unknown command: $MODE" >&2; exit 2 ;;
esac
