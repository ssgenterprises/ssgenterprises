#!/usr/bin/env bash
# Install Radio Replay on Raspberry Pi OS (Bookworm or later) and start it at boot.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
USER_NAME="${SUDO_USER:-$USER}"

sudo apt-get update
sudo apt-get install -y python3-venv python3-numpy python3-gpiozero python3-lgpio libportaudio2

# --system-site-packages lets the venv use the Pi's apt-installed numpy/gpiozero/lgpio
python3 -m venv --system-site-packages "$DIR/.venv"
"$DIR/.venv/bin/pip" install sounddevice

sudo usermod -aG audio,gpio "$USER_NAME"

sed -e "s|@DIR@|$DIR|g" -e "s|@USER@|$USER_NAME|g" "$DIR/systemd/radio-replay.service" \
  | sudo tee /etc/systemd/system/radio-replay.service >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now radio-replay.service
echo "Installed. Logs: journalctl -u radio-replay -f"
