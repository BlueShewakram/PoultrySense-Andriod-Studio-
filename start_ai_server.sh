#!/usr/bin/env bash
# Stop any existing PoultrySense AI server, then start a fresh one in Terminal.app
# so macOS can show the Camera permission dialog.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
AI_DIR="$ROOT/poultrysense_ai"
PORT="${POULTRYSENSE_PORT:-5050}"
PID_FILE="$AI_DIR/server.pid"
PYTHON="$AI_DIR/venv/bin/python"

if [[ ! -x "$PYTHON" ]]; then
  echo "[PoultrySense AI] Missing venv at $PYTHON"
  exit 1
fi

# Stop previous instance
if [[ -f "$PID_FILE" ]]; then
  OLD_PID="$(cat "$PID_FILE" || true)"
  if [[ -n "${OLD_PID:-}" ]]; then
    kill "$OLD_PID" 2>/dev/null || true
    sleep 1
    kill -9 "$OLD_PID" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
fi

# Also kill anything still bound to the port
if command -v lsof >/dev/null 2>&1; then
  PIDS="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$PIDS" ]]; then
    echo "$PIDS" | xargs kill 2>/dev/null || true
    sleep 1
  fi
fi

LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"

# Launch in Terminal.app (GUI session) so Camera TCC prompt can appear
osascript <<EOF
tell application "Terminal"
  activate
  do script "cd '$AI_DIR' && export POULTRYSENSE_PORT=$PORT && echo '[PoultrySense AI] Starting on port $PORT...' && '$PYTHON' launch_server.py; echo; echo 'Server stopped. You can close this window.'; read -r _"
end tell
EOF

echo "[PoultrySense AI] Launched in Terminal."
echo "  1) If macOS asks for Camera access — click Allow"
echo "  2) Or enable: System Settings → Privacy & Security → Camera → Terminal"
echo "  3) Then open: http://127.0.0.1:$PORT/"
if [[ -n "$LAN_IP" ]]; then
  echo "  Phone IP: $LAN_IP   Emulator IP: 10.0.2.2"
fi

# Wait for server to come up
for i in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; then
    echo "[PoultrySense AI] Server is up."
    STATUS="$(curl -s "http://127.0.0.1:$PORT/status")"
    echo "  status: $STATUS"
    # Check that frame is a real JPEG now (or at least 200)
    CODE="$(curl -s -o /tmp/psense-frame.jpg -w '%{http_code}' "http://127.0.0.1:$PORT/frame.jpg")"
    echo "  /frame.jpg HTTP $CODE ($(file -b /tmp/psense-frame.jpg 2>/dev/null || true))"
    exit 0
  fi
  sleep 1
done

echo "[PoultrySense AI] Timed out waiting for server. Check the Terminal window for errors."
exit 1
