#!/usr/bin/env bash
set -euo pipefail

# Watch training run for best.pt, deploy and restart server when found.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="$ROOT/training/runs/detect/poultry_detector_v1/weights"
MODEL_SRC="$RUN_DIR/best.pt"
DEST="$ROOT/training/models/chicken_detector.pt"
SERVER_PY="$ROOT/poultrysense_ai.py"
PYTHON="$ROOT/venv/bin/python"
LOG="/tmp/poultrysense_watch.log"

echo "[watch] starting, looking for $MODEL_SRC" >> "$LOG"

mkdir -p "$ROOT/training/models"

while true; do
  if [ -f "$MODEL_SRC" ]; then
    cp -f "$MODEL_SRC" "$DEST"
    echo "[watch] copied $MODEL_SRC to $DEST" >> "$LOG"
    # restart server
    pkill -f poultrysense_ai.py || true
    sleep 1
    nohup "$PYTHON" "$SERVER_PY" > /tmp/poultrysense_server.log 2>&1 &
    echo "[watch] restarted server; logs: /tmp/poultrysense_server.log" >> "$LOG"
    exit 0
  fi
  sleep 10
done
