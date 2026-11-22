"""
PoultrySense AI - PC Background Service & Auto-Launcher
Listens to mobile app toggle commands via Firebase Realtime Database.
- When 'on': Automatically launches start_ai_server.bat in a visible console window on this PC!
- When 'off': Stops/pauses the AI server on this PC and releases the camera.
"""
import os
import sys
import time
import socket
import subprocess
import requests

REPO_DIR = os.path.dirname(os.path.abspath(__file__))
START_BAT = os.path.join(REPO_DIR, "start_ai_server.bat")
STOP_BAT = os.path.join(REPO_DIR, "stop_ai_server.bat")
FIREBASE_URL = os.environ.get(
    "FIREBASE_DATABASE_URL",
    "https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app"
).rstrip("/")


def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 1))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip


def update_cloud_ip_registry(status="idle"):
    try:
        local_ip = get_local_ip()
        requests.put(
            f"{FIREBASE_URL}/system_control/ai_server_ip.json",
            json={
                "ip": local_ip,
                "port": 5000,
                "status": status,
                "timestamp": int(time.time() * 1000)
            },
            timeout=3
        )
    except Exception:
        pass


def is_server_listening(port=5000, timeout=0.6):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(timeout)
            return s.connect_ex(('127.0.0.1', port)) == 0
    except Exception:
        return False


def start_ai_server():
    if is_server_listening(5000):
        print("[WATCHER] AI server is already running on port 5000. Sending resume signal...", flush=True)
        try:
            requests.get("http://127.0.0.1:5000/server/resume", timeout=2)
        except Exception:
            pass
        return

    print(f"[WATCHER] >>> Launching start_ai_server.bat on this PC...", flush=True)
    try:
        # Launch in a brand-new visible Command Prompt window on Windows desktop
        subprocess.Popen(
            ["cmd.exe", "/c", START_BAT],
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            cwd=REPO_DIR
        )
        print("[WATCHER] ✓ Successfully launched start_ai_server.bat in new console!", flush=True)
    except Exception as e:
        print(f"[WATCHER] Error launching start_ai_server.bat: {e}", flush=True)


def stop_ai_server():
    if not is_server_listening(5000):
        print("[WATCHER] AI server is already stopped.", flush=True)
        return

    print("[WATCHER] >>> Stopping AI detection server and releasing camera...", flush=True)
    try:
        # 1. Graceful pause/stop HTTP signal
        try:
            requests.get("http://127.0.0.1:5000/server/pause", timeout=2)
        except Exception:
            pass

        # 2. Terminate the server process to close console window and free camera
        subprocess.run(["cmd.exe", "/c", STOP_BAT], cwd=REPO_DIR, timeout=8)
        print("[WATCHER] ✓ AI server process terminated.", flush=True)
    except Exception as e:
        print(f"[WATCHER] Error stopping server: {e}", flush=True)


def main():
    local_ip = get_local_ip()
    print("=" * 65, flush=True)
    print("  POULTRYSENSE AI - BACKGROUND PC WATCHER & AUTO-LAUNCHER  ", flush=True)
    print(f"  Edge PC LAN IP: {local_ip}", flush=True)
    print(f"  Watching: {FIREBASE_URL}/system_control/ai_server.json", flush=True)
    print("  Ready to automatically launch start_ai_server.bat from the App!", flush=True)
    print("=" * 65, flush=True)

    update_cloud_ip_registry("idle")

    last_state = None
    last_launch_attempt = 0.0
    last_ip_sync = time.time()

    while True:
        try:
            # Sync IP every 30 seconds
            if time.time() - last_ip_sync > 30.0:
                is_active = is_server_listening(5000)
                update_cloud_ip_registry("online" if is_active else "idle")
                last_ip_sync = time.time()
            url = f"{FIREBASE_URL}/system_control/ai_server.json"
            resp = requests.get(url, timeout=3)
            if resp.status_code == 200:
                raw_val = resp.json()
                if isinstance(raw_val, str):
                    state = raw_val.strip().lower()

                    if state == "on":
                        # If server is not running on port 5000, launch start_ai_server.bat
                        if not is_server_listening(5000) and (time.time() - last_launch_attempt > 10.0):
                            print(f"[WATCHER] Received 'ON' command from App! Launching start_ai_server.bat...", flush=True)
                            last_launch_attempt = time.time()
                            start_ai_server()
                        elif is_server_listening(5000) and last_state == "off":
                            try:
                                requests.get("http://127.0.0.1:5000/server/resume", timeout=2)
                            except Exception:
                                pass
                    elif state == "off":
                        if is_server_listening(5000) and last_state != "off":
                            print(f"[WATCHER] Received 'OFF' command from App! Stopping AI Server...", flush=True)
                            stop_ai_server()

                    last_state = state
        except Exception:
            pass

        time.sleep(1.5)


if __name__ == "__main__":
    main()
