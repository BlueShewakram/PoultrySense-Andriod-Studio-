"""
PoultrySense AI Vision Server
Real-time Chicken Detection, Multi-Object Tracking, Behavior Recognition,
and Health Telemetry Engine powered by YOLOv8 trained weights.
"""

import cv2
import time
import json
import os
import threading
import numpy as np
import sys
import socket
import queue
from collections import defaultdict, deque

# --- 1. Import Ultralytics YOLO ---
try:
    from ultralytics import YOLO
    HAS_YOLO = True
except ImportError:
    HAS_YOLO = False
    print("[WARN] Ultralytics not installed. Install with: pip install ultralytics")

# --- 2. Import Flask ---
try:
    from flask import Flask, Response, jsonify, request
except ImportError:
    print("[ERROR] Flask is required. Install it with: pip install flask")
    sys.exit(1)

# --- Paths and Models ---
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR = os.path.join(SCRIPT_DIR, "models")
TRACKER_YAML = os.path.join(SCRIPT_DIR, "pipeline", "bytetrack.yaml")
if not os.path.exists(TRACKER_YAML):
    TRACKER_YAML = "bytetrack.yaml"

DET_MODELS = [
    os.path.join(SCRIPT_DIR, "yolo11n.pt"),
    "yolo11n.pt",
    os.path.join(MODELS_DIR, "PoultrySense_YOLOv8_ChickenDet.pt"),
    os.path.join(SCRIPT_DIR, "PoultrySense_YOLOv8_ChickenDet.pt"),
    os.path.join(MODELS_DIR, "PoultrySense_YOLOv8_ChickenSeg.pt"),
    os.path.join(SCRIPT_DIR, "PoultrySense_YOLOv8_ChickenSeg.pt"),
]

BEHAVIOR_MODELS = [
    os.path.join(MODELS_DIR, "PoultrySense_BehaviorModel.pt"),
    os.path.join(SCRIPT_DIR, "PoultrySense_BehaviorModel.pt"),
]

HEALTH_MODELS = [
    os.path.join(MODELS_DIR, "PoultrySense_HealthClassifier.pt"),
    os.path.join(SCRIPT_DIR, "PoultrySense_HealthClassifier.pt"),
]

BEHAVIOR_NAMES = {0: "peck", 1: "stand", 2: "walk"}
HEALTH_NAMES = {0: "Healthy", 1: "Unhealthy"}

# --- Global shared state ---
latest_frame = None
latest_jpeg_bytes = None
latest_status = {
    "active": False,
    "fps": 0.0,
    "flock_count": 0,
    "person_count": 0,
    "hand_count": 0,
    "healthy_count": 0,
    "warning_count": 0,
    "unhealthy_count": 0,
    "isolated_count": 0,
    "health_index": 100,
    "track_count": 0,
    "temperature": 24.5,
    "humidity": 62.0,
    "ammonia": 12,
    "detections": []
}
latest_sensor_data = {
    "temperature": 24.5,
    "humidity": 62.0,
    "ammonia": 12,
    "last_updated": 0
}
frame_lock = threading.Lock()

# --- Motion & History State ---
track_histories = defaultdict(lambda: deque(maxlen=30))
track_display_ids = {}
track_missed_counts = {}
track_classifications = {}
track_last_positions = {}
inactive_tracks = {}
classification_queue = queue.Queue(maxsize=6)
classification_lock = threading.Lock()

# Limit active tracked subjects for ultra-smooth high FPS (supports 6 to 10 chickens)
MAX_TRACKED_CHICKENS = 10


def classification_worker(beh_model, hlth_model):
    """Background worker that continuously classifies chicken health and behavior
    on a secondary CPU core without blocking the 30 FPS video tracking loop."""
    while True:
        try:
            disp_id, crop = classification_queue.get(timeout=1.0)
        except Exception:
            continue

        b_label = "stand"
        unhealthy_prob = 0.0

        if beh_model is not None:
            try:
                b_res = beh_model.predict(crop, imgsz=160, verbose=False)[0]
                b_label = BEHAVIOR_NAMES.get(int(b_res.probs.top1), "stand")
            except Exception:
                pass

        if hlth_model is not None:
            try:
                h_res = hlth_model.predict(crop, imgsz=160, verbose=False)[0]
                # In PoultrySense_HealthClassifier: index 0 is Healthy, index 1 is Unhealthy
                if h_res.probs is not None and len(h_res.probs.data) >= 2:
                    unhealthy_prob = float(h_res.probs.data[1])
                else:
                    unhealthy_prob = 0.0
            except Exception:
                pass

        with classification_lock:
            track_classifications[disp_id] = {
                "behavior": b_label,
                "unhealthy_prob": unhealthy_prob,
                "time": time.time()
            }


# --- Camera Configuration ---
# Camera index: 1 = external USB camera ("HD Camera")
#               0 = built-in laptop camera (fallback)
EXTERNAL_CAMERA_INDEX = 1
FALLBACK_CAMERA_INDEX = 0


# --- Threaded Camera Reader for 30 FPS capture ---
class ThreadedCamera:
    """Reads frames from the camera in a background thread so capture
    is decoupled from the (slower) YOLO inference loop."""

    def __init__(self, camera_index=EXTERNAL_CAMERA_INDEX):
        self.cap = None
        self._frame = None
        self._ret = False
        self._lock = threading.Lock()
        self._running = False
        self._thread = None
        self._open(camera_index)

    def _open(self, camera_index):
        # Try external camera first with DirectShow
        self.cap = cv2.VideoCapture(camera_index, cv2.CAP_DSHOW)
        if not self.cap.isOpened():
            self.cap = cv2.VideoCapture(camera_index)

        # Fallback to built-in laptop camera
        if not self.cap.isOpened() and camera_index != FALLBACK_CAMERA_INDEX:
            print(f"[WARN] External camera {camera_index} not available, falling back to camera {FALLBACK_CAMERA_INDEX}")
            self.cap = cv2.VideoCapture(FALLBACK_CAMERA_INDEX, cv2.CAP_DSHOW)
            if not self.cap.isOpened():
                self.cap = cv2.VideoCapture(FALLBACK_CAMERA_INDEX)

        if self.cap.isOpened():
            # Use MJPG codec for high-clarity & fast 30 FPS transfer
            self.cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
            self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
            self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
            self.cap.set(cv2.CAP_PROP_FPS, 30)
            self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            
            # Enable natural auto-exposure and optimal brightness for clear, vivid display
            self.cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.75)
            self.cap.set(cv2.CAP_PROP_BRIGHTNESS, 128)
            self.cap.set(cv2.CAP_PROP_CONTRAST, 40)
            actual_w = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            actual_h = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            print(f"[INFO] Camera opened: index={camera_index}, res={actual_w}x{actual_h}, codec=MJPG, Auto-Exposure=ON")
        else:
            print("[ERROR] No camera could be opened!")

    def start(self):
        if self._running:
            return self
        self._running = True
        self._thread = threading.Thread(target=self._reader_loop, daemon=True)
        self._thread.start()
        return self

    def _reader_loop(self):
        while self._running:
            ret, frame = self.cap.read()
            with self._lock:
                self._ret = ret
                self._frame = frame

    def read(self):
        with self._lock:
            return self._ret, self._frame.copy() if self._frame is not None else None

    def isOpened(self):
        return self.cap is not None and self.cap.isOpened()

    def release(self):
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2.0)
        if self.cap is not None:
            self.cap.release()


def get_model_path(candidates):
    for path in candidates:
        if os.path.exists(path):
            return path
    return None


# --- Persistent Chicken ID Allocator with Spatial Re-Identification ---
def assign_display_id(raw_id, cx=None, cy=None, box=None):
    """Guarantees consistent Chicken IDs (#1 to #10). If tracking momentarily blips or drops
    due to pecking/occlusion, re-identifies the chicken at its previous coordinates rather than
    assigning a new ID."""
    curr_time = time.time()

    # 1. If this raw tracker ID is already mapped, update position and return
    if raw_id in track_display_ids:
        track_missed_counts[raw_id] = 0
        if cx is not None and cy is not None:
            track_last_positions[raw_id] = (cx, cy, curr_time)
        return track_display_ids[raw_id]

    # 2. Spatial Re-Identification:
    # Check if a chicken disappeared within the last 4.0 seconds close to (cx, cy)
    best_match_id = None
    best_dist = 95.0  # Maximum pixel radius for spatial match in 640x480 frame

    if cx is not None and cy is not None:
        for disp_id, info in list(inactive_tracks.items()):
            if curr_time - info["time"] < 4.0:
                dist = float(np.hypot(cx - info["cx"], cy - info["cy"]))
                if dist < best_dist:
                    best_dist = dist
                    best_match_id = disp_id

    if best_match_id is not None:
        # Reclaim the same chicken's persistent identity!
        del inactive_tracks[best_match_id]
        track_display_ids[raw_id] = best_match_id
        track_missed_counts[raw_id] = 0
        if cx is not None and cy is not None:
            track_last_positions[raw_id] = (cx, cy, curr_time)
        return best_match_id

    # 3. New Chicken: Allocate the lowest available ID between 1 and MAX_TRACKED_CHICKENS
    active_display_ids = set(track_display_ids.values())
    cand = 1
    while cand <= MAX_TRACKED_CHICKENS:
        if cand not in active_display_ids:
            break
        cand += 1
    else:
        cand = 1

    track_display_ids[raw_id] = cand
    track_missed_counts[raw_id] = 0
    if cx is not None and cy is not None:
        track_last_positions[raw_id] = (cx, cy, curr_time)
    return cand


def cleanup_stale_ids(active_raw_ids, max_missed=90):
    """Keeps tracks alive through brief occlusions (90 frames = 3 seconds) and archives
    inactive chickens for seamless spatial re-identification."""
    curr_time = time.time()

    for raw_id in list(track_display_ids.keys()):
        if raw_id not in active_raw_ids:
            cnt = track_missed_counts.get(raw_id, 0) + 1
            track_missed_counts[raw_id] = cnt
            if cnt > max_missed:
                disp_id = track_display_ids.get(raw_id)
                last_pos = track_last_positions.get(raw_id)
                if disp_id is not None and last_pos is not None:
                    inactive_tracks[disp_id] = {
                        "cx": last_pos[0],
                        "cy": last_pos[1],
                        "time": curr_time
                    }
                if raw_id in track_display_ids:
                    del track_display_ids[raw_id]
                if raw_id in track_missed_counts:
                    del track_missed_counts[raw_id]
                if raw_id in track_last_positions:
                    del track_last_positions[raw_id]

    # Clean up inactive tracks older than 6.0 seconds
    for disp_id in list(inactive_tracks.keys()):
        if curr_time - inactive_tracks[disp_id]["time"] > 6.0:
            del inactive_tracks[disp_id]
            if disp_id in track_histories:
                del track_histories[disp_id]
            if disp_id in track_classifications:
                del track_classifications[disp_id]


# --- Color Palette for Chicken HUD Badges ---
ID_COLORS = [
    (0, 230, 118),   # Vibrant Emerald Green
    (0, 215, 255),   # Bright Cyan
    (255, 170, 0),   # Amber
    (235, 100, 255), # Magenta/Purple
    (255, 120, 0),   # Deep Orange
    (100, 255, 200), # Mint
    (180, 255, 100), # Lime
    (255, 100, 100), # Coral Red
]


def get_id_color(track_id):
    return ID_COLORS[(track_id - 1) % len(ID_COLORS)]


def draw_hud_box(img, pt1, pt2, color, thickness=2, line_len=20):
    x1, y1 = max(0, int(pt1[0])), max(0, int(pt1[1]))
    x2, y2 = min(img.shape[1], int(pt2[0])), min(img.shape[0], int(pt2[1]))
    if x2 > x1 and y2 > y1:
        roi = img[y1:y2, x1:x2]
        tint = np.full_like(roi, color)
        cv2.addWeighted(tint, 0.08, roi, 0.92, 0, roi)
    cv2.rectangle(img, (x1, y1), (x2, y2), color, 1)
    
    # Corner brackets for futuristic HUD aesthetic
    cv2.line(img, (x1, y1), (x1 + line_len, y1), color, thickness + 1)
    cv2.line(img, (x1, y1), (x1, y1 + line_len), color, thickness + 1)
    cv2.line(img, (x2, y1), (x2 - line_len, y1), color, thickness + 1)
    cv2.line(img, (x2, y1), (x2, y1 + line_len), color, thickness + 1)
    cv2.line(img, (x1, y2), (x1 + line_len, y2), color, thickness + 1)
    cv2.line(img, (x1, y2), (x1, y2 - line_len), color, thickness + 1)
    cv2.line(img, (x2, y2), (x2 - line_len, y2), color, thickness + 1)
    cv2.line(img, (x2, y2), (x2, y2 - line_len), color, thickness + 1)


# --- Flask Application ---
flask_app = Flask(__name__)


@flask_app.route('/video_feed')
def video_feed():
    """Multipart MJPEG video stream for Android app & web browser."""
    def generate():
        while True:
            with frame_lock:
                if latest_jpeg_bytes is None:
                    time.sleep(0.01)
                    continue
                frame_bytes = latest_jpeg_bytes
            yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            time.sleep(0.02)
    return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')


@flask_app.route('/frame.jpg')
def frame_jpg():
    """Single JPEG snapshot endpoint."""
    with frame_lock:
        if latest_jpeg_bytes is None:
            return "No frame available", 404
        return Response(latest_jpeg_bytes, mimetype='image/jpeg')


@flask_app.route('/status')
def status():
    """Real-time JSON telemetry endpoint for PoultrySense."""
    with frame_lock:
        return jsonify(latest_status)


@flask_app.route('/api/discover')
def api_discover():
    """Lightweight endpoint for app auto-discovery."""
    return jsonify({
        "service": "poultrysense_ai",
        "name": "PoultrySense AI Vision Hub",
        "version": "2.0",
        "status": "online"
    })


@flask_app.route('/api/sensor', methods=['GET', 'POST'])
def handle_sensor():
    """Receive or retrieve live environmental sensor data from ESP32."""
    global latest_sensor_data
    if request.method == 'POST':
        try:
            data = request.get_json(force=True)
            if data:
                with frame_lock:
                    if "temperature" in data:
                        latest_sensor_data["temperature"] = float(data["temperature"])
                        latest_status["temperature"] = latest_sensor_data["temperature"]
                    if "humidity" in data:
                        latest_sensor_data["humidity"] = float(data["humidity"])
                        latest_status["humidity"] = latest_sensor_data["humidity"]
                    if "ammonia" in data:
                        latest_sensor_data["ammonia"] = int(data["ammonia"])
                        latest_status["ammonia"] = latest_sensor_data["ammonia"]
                    latest_sensor_data["last_updated"] = time.time()
                print(f"[ESP32] Live Sensor: Temp={latest_sensor_data['temperature']:.1f}°C, "
                      f"Hum={latest_sensor_data['humidity']:.1f}%, Ammonia={latest_sensor_data['ammonia']}ppm")
                return jsonify({"status": "success", "received": latest_sensor_data}), 200
        except Exception as e:
            return jsonify({"status": "error", "message": str(e)}), 400
        return jsonify({"status": "error", "message": "Invalid JSON"}), 400
    else:
        with frame_lock:
            return jsonify(latest_sensor_data), 200


@flask_app.route('/reset_ids')
def reset_ids():
    """Reset persistent chicken IDs and track histories."""
    global next_auto_id
    track_display_ids.clear()
    track_missed_counts.clear()
    track_histories.clear()
    next_auto_id = 1
    return jsonify({"status": "success", "message": "Chicken tracking IDs and histories reset successfully."})


@flask_app.route('/')
def index():
    return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PoultrySense AI — Chicken Health & Behavior Stream</title>
    <link href="https://fonts.googleapis.com/css2?family=Outfit:wght@400;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #070D08;
            --surface: #0E1A11;
            --surface-elevated: #162B1D;
            --border: #1E3B27;
            --primary: #00E676;
            --primary-glow: rgba(0, 230, 118, 0.25);
            --text-main: #F0FDF4;
            --text-muted: #86A789;
            --warn: #FFB300;
            --danger: #FF5252;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: var(--bg);
            color: var(--text-main);
            font-family: 'Outfit', -apple-system, sans-serif;
            min-height: 100vh;
            padding: 24px 16px;
            display: flex;
            flex-direction: column;
            align-items: center;
        }
        .container {
            width: 100%;
            max-width: 960px;
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-wrap: wrap;
            gap: 12px;
            border-bottom: 1px solid var(--border);
            padding-bottom: 16px;
        }
        .logo-group {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .logo-icon {
            font-size: 28px;
            background: var(--surface-elevated);
            padding: 8px 12px;
            border-radius: 12px;
            border: 1px solid var(--border);
        }
        h1 {
            font-size: 24px;
            font-weight: 800;
            letter-spacing: -0.5px;
            color: var(--text-main);
        }
        .status-pill {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: rgba(0, 230, 118, 0.12);
            color: var(--primary);
            border: 1px solid rgba(0, 230, 118, 0.3);
            padding: 6px 14px;
            border-radius: 999px;
            font-size: 13px;
            font-weight: 700;
        }
        .pulse-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: var(--primary);
            box-shadow: 0 0 10px var(--primary);
            animation: pulse 1.8s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(0.85); }
        }
        .video-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 20px;
            overflow: hidden;
            box-shadow: 0 12px 36px rgba(0,0,0,0.5);
            position: relative;
        }
        .video-feed {
            width: 100%;
            display: block;
            border-radius: 20px;
            min-height: 380px;
            background: #000;
            object-fit: cover;
        }
        .metrics-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
            gap: 14px;
        }
        .metric-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 4px;
        }
        .metric-title {
            font-size: 13px;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.5px;
            font-weight: 600;
        }
        .metric-value {
            font-size: 26px;
            font-weight: 800;
            color: var(--text-main);
            font-family: 'JetBrains Mono', monospace;
        }
        .metric-value.highlight {
            color: var(--primary);
        }
        .nav-actions {
            display: flex;
            gap: 12px;
            flex-wrap: wrap;
        }
        .btn {
            background: var(--surface-elevated);
            color: var(--text-main);
            border: 1px solid var(--border);
            padding: 10px 18px;
            border-radius: 12px;
            font-weight: 700;
            font-size: 14px;
            text-decoration: none;
            cursor: pointer;
            transition: all 0.2s ease;
            display: inline-flex;
            align-items: center;
            gap: 8px;
        }
        .btn:hover {
            border-color: var(--primary);
            color: var(--primary);
            box-shadow: 0 0 12px var(--primary-glow);
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="logo-group">
                <div class="logo-icon">🐔</div>
                <div>
                    <h1>PoultrySense AI Vision</h1>
                    <div style="font-size: 13px; color: var(--text-muted);">YOLOv8 Chicken Health & Behavior Engine</div>
                </div>
            </div>
            <div class="status-pill">
                <div class="pulse-dot"></div>
                <span id="live-indicator">AI ENGINE ACTIVE</span>
            </div>
        </header>

        <div class="video-card">
            <img class="video-feed" src="/video_feed" alt="PoultrySense Live AI Stream">
        </div>

        <div class="metrics-grid">
            <div class="metric-card">
                <span class="metric-title">Flock Count</span>
                <span class="metric-value highlight" id="metric-flock">-</span>
            </div>
            <div class="metric-card">
                <span class="metric-title">Health Vitality</span>
                <span class="metric-value" id="metric-vitality">100%</span>
            </div>
            <div class="metric-card">
                <span class="metric-title">Inference FPS</span>
                <span class="metric-value highlight" id="metric-fps">-</span>
            </div>
            <div class="metric-card">
                <span class="metric-title">Spatial Warnings</span>
                <span class="metric-value" id="metric-isolated">0</span>
            </div>
        </div>

        <div class="nav-actions">
            <a class="btn" href="/video_feed" target="_blank">📺 MJPEG Stream</a>
            <a class="btn" href="/status" target="_blank">📊 JSON Telemetry</a>
            <button class="btn" onclick="fetch('/reset_ids').then(() => alert('IDs Reset!'));">🔄 Reset Tracking IDs</button>
        </div>
    </div>

    <script>
        setInterval(() => {
            fetch('/status')
                .then(res => res.json())
                .then(data => {
                    document.getElementById('metric-flock').innerText = data.flock_count || data.person_count || 0;
                    document.getElementById('metric-fps').innerText = (data.fps || 0).toFixed(1);
                    document.getElementById('metric-vitality').innerText = (data.health_index || 100) + '%';
                    document.getElementById('metric-isolated').innerText = data.isolated_count || 0;
                })
                .catch(() => {});
        }, 1000);
    </script>
</body>
</html>"""


# --- Main Inference & Camera Loop ---
def camera_loop():
    global latest_frame, latest_jpeg_bytes, latest_status

    print("[INFO] Initializing PoultrySense AI Engine...")

    det_model = None
    behavior_model = None
    health_model = None
    chicken_classes = [0]

    if HAS_YOLO:
        # 1. Chicken Detection Model
        det_path = get_model_path(DET_MODELS)
        if det_path:
            try:
                print(f"[INFO] Loading Detection Model from: {det_path}")
                det_model = YOLO(det_path)
                print("[INFO] YOLO Detection Model loaded successfully!")

                # Determine strict chicken class ID(s)
                found_classes = []
                if hasattr(det_model, 'names') and det_model.names:
                    for cid, cname in det_model.names.items():
                        name_str = str(cname).lower()
                        if any(k in name_str for k in ["chicken", "poultry", "hen", "rooster", "bird"]):
                            found_classes.append(int(cid))

                    # If using standard COCO model (like yolo11n.pt), class 14 is bird.
                    if not found_classes and 14 in det_model.names:
                        found_classes = [14]
                    elif not found_classes and len(det_model.names) == 1:
                        found_classes = [0]

                if found_classes:
                    chicken_classes = found_classes

                class_labels = [det_model.names.get(c, 'chicken') for c in chicken_classes]
                print(f"[INFO] STRICT CHICKEN FILTER: Tracking only class IDs {chicken_classes} -> {class_labels}")
            except Exception as e:
                print(f"[WARN] Failed to load Detection Model: {e}")

        # 2. Behavior Classification Model
        beh_path = get_model_path(BEHAVIOR_MODELS)
        if beh_path:
            try:
                print(f"[INFO] Loading Behavior Model from: {beh_path}")
                behavior_model = YOLO(beh_path)
                print("[INFO] Behavior Classification Model loaded successfully!")
            except Exception as e:
                print(f"[WARN] Could not load Behavior Model: {e}")

        # 3. Health Classifier Model
        hlth_path = get_model_path(HEALTH_MODELS)
        if hlth_path:
            try:
                print(f"[INFO] Loading Health Classifier Model from: {hlth_path}")
                health_model = YOLO(hlth_path)
                print("[INFO] Health Classifier Model loaded successfully!")
            except Exception as e:
                print(f"[WARN] Could not load Health Model: {e}")

        # Start asynchronous classification worker thread (0 latency impact on video)
        if behavior_model is not None or health_model is not None:
            c_thread = threading.Thread(
                target=classification_worker,
                args=(behavior_model, health_model),
                daemon=True
            )
            c_thread.start()
            print("[INFO] Background AI Health & Behavior Worker started on dedicated core.")

    # Initialize Threaded Camera Capture (external USB camera, index 1)
    cam = ThreadedCamera(EXTERNAL_CAMERA_INDEX)
    if not cam.isOpened():
        print("[ERROR] Cannot open any camera. Exiting camera loop.")
        return
    cam.start()
    print("[INFO] Threaded camera reader started at 30 FPS target")

    prev_time = time.time()

    while True:
        ret, frame = cam.read()
        if not ret or frame is None:
            time.sleep(0.01)
            continue

        # No flip needed for external USB camera (not mirrored like laptop cam)
        h_frame, w_frame, _ = frame.shape
        curr_time = time.time()
        fps = 1.0 / (curr_time - prev_time + 1e-5)
        prev_time = curr_time

        # Adaptive Low-Light Auto-Enhancement (keeps video crisp & visible in any room lighting)
        mean_lum = frame.mean()
        if mean_lum < 45:
            inv_gamma = 1.0 / 1.5
            lut = np.array([((i / 255.0) ** inv_gamma) * 255 for i in range(256)]).astype("uint8")
            frame = cv2.LUT(frame, lut)

        active_raw_ids = []
        raw_boxes = []
        raw_confs = []

        # 1. Run YOLOv8 Tracking with STRICT Chicken Filtering
        if det_model is not None:
            try:
                # Use ByteTrack tracking restricted ONLY to chicken class IDs
                tracker_cfg = TRACKER_YAML if os.path.exists(TRACKER_YAML) else "bytetrack.yaml"
                results = det_model.track(
                    frame,
                    persist=True,
                    tracker=tracker_cfg,
                    classes=chicken_classes,
                    conf=0.25,
                    iou=0.45,
                    imgsz=640,
                    max_det=MAX_TRACKED_CHICKENS,
                    verbose=False
                )[0]

                boxes = results.boxes
                if boxes is not None and len(boxes) > 0:
                    xyxys = boxes.xyxy.cpu().numpy()
                    confs = boxes.conf.cpu().numpy()
                    ids = boxes.id.cpu().numpy() if boxes.id is not None else np.arange(len(xyxys))
                    cls_ids = boxes.cls.cpu().numpy() if boxes.cls is not None else np.zeros(len(xyxys))

                    for box, conf, raw_id, c_id in zip(xyxys, confs, ids, cls_ids):
                        if int(c_id) not in chicken_classes:
                            continue
                        raw_boxes.append(box)
                        raw_confs.append(float(conf))
                        active_raw_ids.append(int(raw_id))

                    # Keep up to 10 most prominent chickens (prevents tracking overload)
                    if len(raw_boxes) > MAX_TRACKED_CHICKENS:
                        ranked = sorted(
                            zip(raw_boxes, raw_confs, active_raw_ids),
                            key=lambda x: (x[0][2] - x[0][0]) * (x[0][3] - x[0][1]),
                            reverse=True
                        )[:MAX_TRACKED_CHICKENS]
                        raw_boxes = [r[0] for r in ranked]
                        raw_confs = [r[1] for r in ranked]
                        active_raw_ids = [r[2] for r in ranked]
            except Exception as e:
                # Fallback to standard detect strictly filtered for chicken classes
                try:
                    results = det_model(
                        frame,
                        classes=chicken_classes,
                        conf=0.25,
                        iou=0.45,
                        imgsz=640,
                        verbose=False
                    )[0]
                    boxes = results.boxes
                    if boxes is not None and len(boxes) > 0:
                        cls_ids = boxes.cls.cpu().numpy() if boxes.cls is not None else np.zeros(len(boxes))
                        for i, (b, c, c_id) in enumerate(zip(boxes.xyxy.cpu().numpy(), boxes.conf.cpu().numpy(), cls_ids)):
                            if int(c_id) not in chicken_classes:
                                continue
                            raw_boxes.append(b)
                            raw_confs.append(float(c))
                            active_raw_ids.append(i + 1)
                except Exception:
                    pass

        cleanup_stale_ids(active_raw_ids)

        detection_list = []
        centroids = []
        healthy_count = 0
        warning_count = 0
        unhealthy_count = 0
        isolated_count = 0

        # Calculate centroids for spatial isolation analysis
        for box in raw_boxes:
            cx = (box[0] + box[2]) / 2.0
            cy = (box[1] + box[3]) / 2.0
            centroids.append((cx, cy))

        # Check spatial isolation: nearest neighbor > 25% frame width
        isolation_thresh = 0.25 * w_frame
        isolated_flags = []
        for i, (cx, cy) in enumerate(centroids):
            is_iso = False
            if len(centroids) > 1:
                min_dist = min(
                    np.hypot(cx - ocx, cy - ocy)
                    for j, (ocx, ocy) in enumerate(centroids) if j != i
                )
                if min_dist > isolation_thresh:
                    is_iso = True
            isolated_flags.append(is_iso)
            if is_iso:
                isolated_count += 1

        # 2. Process each detected chicken with High-FPS throttled classification
        classified_this_frame = 0
        for idx, (box, conf, raw_id) in enumerate(zip(raw_boxes, raw_confs, active_raw_ids)):
            cx, cy = centroids[idx]
            display_id = assign_display_id(raw_id, cx=cx, cy=cy, box=box)
            x1, y1, x2, y2 = map(int, box)
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(w_frame, x2), min(h_frame, y2)
            is_isolated = isolated_flags[idx]

            # Motion Calculation (instant 30 FPS centroid tracking per chicken ID)
            hist = track_histories[display_id]
            motion = 0.0
            if len(hist) > 0:
                prev_c = hist[-1]
                motion = float(np.hypot(cx - prev_c[0], cy - prev_c[1]))
            hist.append((int(cx), int(cy)))

            # Crop for Behavior & Health Classification
            crop = frame[y1:y2, x1:x2]
            
            # Read non-blocking classification from background worker thread (0ms latency on video!)
            with classification_lock:
                cached = track_classifications.get(display_id)
                if cached:
                    behavior_label = cached.get("behavior", "stand")
                    unhealthy_prob = cached.get("unhealthy_prob", 0.0)
                    last_time = cached.get("time", 0)
                else:
                    behavior_label = "stand"
                    unhealthy_prob = 0.0
                    last_time = 0

            # Queue crop to background worker if expired (>1.5s old) without blocking video stream
            if (curr_time - last_time > 1.5) and not classification_queue.full():
                if crop.size > 0 and crop.shape[0] >= 24 and crop.shape[1] >= 24:
                    try:
                        classification_queue.put_nowait((display_id, crop.copy()))
                    except Exception:
                        pass

            # Real-time Motion fusion heuristic (instant 30 FPS response on main thread!)
            if motion > 4.5:
                behavior_label = "walk"
            elif motion > 2.5 and behavior_label == "peck":
                behavior_label = "peck"

            # --- Proven 3-Tier Multi-Factor Health Classification ---
            # Classes: "Healthy", "Warning", "Unhealthy"
            # Biological foundation: Active mobility is the #1 indicator of poultry health.
            # If a chicken is actively walking, it CANNOT be severely sick!
            if behavior_label == "walk" or motion > 2.8:
                health_label = "Healthy"
            elif is_isolated:
                if unhealthy_prob > 0.75:
                    health_label = "Unhealthy"
                else:
                    health_label = "Warning"
            elif unhealthy_prob >= 0.75 and motion < 1.8:
                # Confirmed sick: immobile/stationary AND high visual defect confidence
                health_label = "Unhealthy"
            elif unhealthy_prob >= 0.40 or motion < 0.6:
                # Low motion or stationary bird -> Warning
                health_label = "Warning"
            else:
                health_label = "Healthy"

            if health_label == "Healthy":
                healthy_count += 1
            elif health_label == "Warning":
                warning_count += 1
            else:
                unhealthy_count += 1

            # Determine Display Colors & HUD Badges
            color = get_id_color(display_id)
            if health_label == "Unhealthy":
                color = (70, 70, 255) # Vivid Red/Coral for confirmed unhealthy alert
            elif health_label == "Warning":
                color = (0, 180, 255) # Amber/Yellow warning for isolated / stationary
            else:
                color = get_id_color(display_id) # Vibrant ID color for healthy

            # Draw Motion Trail
            if len(hist) > 1:
                pts = np.array(list(hist), dtype=np.int32).reshape((-1, 1, 2))
                cv2.polylines(frame, [pts], isClosed=False, color=color, thickness=2)
                cv2.circle(frame, (int(cx), int(cy)), 4, color, -1)

            # Draw HUD Box
            draw_hud_box(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=18)

            # Draw Info Badge
            iso_tag = " • ISOLATED" if is_isolated else ""
            badge_text = f" Chicken #{display_id} | {behavior_label.upper()} | {health_label.upper()}{iso_tag} "
            (text_w, text_h), baseline = cv2.getTextSize(badge_text, cv2.FONT_HERSHEY_DUPLEX, 0.45, 1)
            label_y = max(y1 - 10, text_h + 10)
            badge_bg = (int(color[0] * 0.25), int(color[1] * 0.25), int(color[2] * 0.25))

            cv2.rectangle(frame, (x1, label_y - text_h - 6), (x1 + text_w + 8, label_y + baseline + 2), badge_bg, -1)
            cv2.rectangle(frame, (x1, label_y - text_h - 6), (x1 + text_w + 8, label_y + baseline + 2), color, 1)
            cv2.putText(frame, badge_text, (x1 + 4, label_y - 2), cv2.FONT_HERSHEY_DUPLEX, 0.45, (255, 255, 255), 1)

            detection_list.append({
                "track_id": display_id,
                "class": "chicken",
                "confidence": round(conf, 2),
                "behavior": behavior_label,
                "health": health_label,
                "isolated": is_isolated,
                "motion": round(motion, 1),
                "bbox": [x1, y1, x2, y2]
            })

        flock_count = len(detection_list)

        # Dynamic Health Index
        if flock_count > 0:
            health_index = max(10, int(100 - (unhealthy_count * 25) - (warning_count * 10) - (isolated_count * 5)))
        else:
            health_index = 100

        # Clean frame without text overlays

        # Encode Frame with high visual clarity
        _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 85])

        with frame_lock:
            latest_frame = frame
            latest_jpeg_bytes = jpeg.tobytes()
            latest_status = {
                "active": True,
                "fps": round(fps, 1),
                "flock_count": flock_count,
                "person_count": flock_count,  # Backwards compatibility
                "hand_count": flock_count,    # Backwards compatibility
                "healthy_count": healthy_count,
                "warning_count": warning_count,
                "unhealthy_count": unhealthy_count,
                "isolated_count": isolated_count,
                "health_index": health_index,
                "track_count": flock_count,
                "temperature": latest_sensor_data["temperature"],
                "humidity": latest_sensor_data["humidity"],
                "ammonia": latest_sensor_data["ammonia"],
                "detections": detection_list
            }

    cam.release()


def udp_discovery_loop():
    """Listens for UDP discovery broadcasts from PoultrySense Android app and replies with server info."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('0.0.0.0', 5005))
        print("[INFO] PoultrySense Auto-Discovery listening on UDP port 5005")
        while True:
            data, addr = sock.recvfrom(1024)
            message = data.decode('utf-8', errors='ignore').strip()
            if "POULTRYSENSE_DISCOVER" in message:
                response = json.dumps({
                    "service": "poultrysense_ai",
                    "name": "PoultrySense AI Vision Hub",
                    "port": 5000,
                    "version": "2.0"
                })
                sock.sendto(response.encode('utf-8'), addr)
                print(f"[DISCOVERY] Discovered by PoultrySense app at {addr[0]}")
    except Exception as e:
        print(f"[DISCOVERY] UDP notice: {e}")


def main():
    print("=" * 65)
    print("   POULTRYSENSE AI - CHICKEN HEALTH & BEHAVIOR MONITORING SERVER   ")
    print("=" * 65)
    
    # 1. Start AI Camera loop
    cam_thread = threading.Thread(target=camera_loop, daemon=True)
    cam_thread.start()

    # 2. Start One-Tap Auto-Discovery broadcast responder
    disc_thread = threading.Thread(target=udp_discovery_loop, daemon=True)
    disc_thread.start()

    print("[INFO] PoultrySense AI Server running at http://0.0.0.0:5000")
    flask_app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)


if __name__ == "__main__":
    main()
