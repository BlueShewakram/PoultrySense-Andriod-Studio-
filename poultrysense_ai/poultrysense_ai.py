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
    from flask import Flask, Response, jsonify
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
    os.path.join(MODELS_DIR, "PoultrySense_YOLOv8_ChickenDet.pt"),
    os.path.join(SCRIPT_DIR, "PoultrySense_YOLOv8_ChickenDet.pt"),
    os.path.join(MODELS_DIR, "PoultrySense_YOLOv8_ChickenSeg.pt"),
    os.path.join(SCRIPT_DIR, "PoultrySense_YOLOv8_ChickenSeg.pt"),
    os.path.join(SCRIPT_DIR, "yolo11n.pt"),
    "yolo11n.pt"
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
    "unhealthy_count": 0,
    "isolated_count": 0,
    "health_index": 100,
    "track_count": 0,
    "detections": []
}
frame_lock = threading.Lock()

# --- Motion & History State ---
track_histories = defaultdict(lambda: deque(maxlen=30))
track_display_ids = {}
track_missed_counts = {}
next_auto_id = 1


def get_model_path(candidates):
    for path in candidates:
        if os.path.exists(path):
            return path
    return None


# --- Persistent ID Allocator ---
def assign_display_id(raw_id):
    global next_auto_id
    if raw_id in track_display_ids:
        track_missed_counts[raw_id] = 0
        return track_display_ids[raw_id]
    
    # Check for reusable ID or allocate next
    used = set(track_display_ids.values())
    cand = 1
    while cand in used:
        cand += 1
    track_display_ids[raw_id] = cand
    track_missed_counts[raw_id] = 0
    return cand


def cleanup_stale_ids(active_raw_ids, max_missed=30):
    for raw_id in list(track_display_ids.keys()):
        if raw_id not in active_raw_ids:
            track_missed_counts[raw_id] = track_missed_counts.get(raw_id, 0) + 1
            if track_missed_counts[raw_id] > max_missed:
                del track_display_ids[raw_id]
                if raw_id in track_missed_counts:
                    del track_missed_counts[raw_id]
                if raw_id in track_histories:
                    del track_histories[raw_id]


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
    x1, y1 = int(pt1[0]), int(pt1[1])
    x2, y2 = int(pt2[0]), int(pt2[1])
    overlay = img.copy()
    cv2.rectangle(overlay, (x1, y1), (x2, y2), color, -1)
    cv2.addWeighted(overlay, 0.08, img, 0.92, 0, img)
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

    if HAS_YOLO:
        # 1. Chicken Detection Model
        det_path = get_model_path(DET_MODELS)
        if det_path:
            try:
                print(f"[INFO] Loading Detection Model from: {det_path}")
                det_model = YOLO(det_path)
                print("[INFO] YOLO Detection Model loaded successfully!")
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

    # Initialize Camera Capture
    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap = cv2.VideoCapture(0)
    
    if cap.isOpened():
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_FPS, 30)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    prev_time = time.time()

    while True:
        ret, frame = cap.read()
        if not ret:
            time.sleep(0.03)
            continue

        frame = cv2.flip(frame, 1)
        h_frame, w_frame, _ = frame.shape
        curr_time = time.time()
        fps = 1.0 / (curr_time - prev_time + 1e-5)
        prev_time = curr_time

        active_raw_ids = []
        raw_boxes = []
        raw_confs = []

        # 1. Run YOLOv8 Tracking
        if det_model is not None:
            try:
                # Use ByteTrack tracking if available, otherwise regular inference
                if os.path.exists(TRACKER_YAML):
                    results = det_model.track(
                        frame, persist=True, tracker=TRACKER_YAML,
                        conf=0.35, iou=0.45, imgsz=640, verbose=False
                    )[0]
                else:
                    results = det_model.track(
                        frame, persist=True, conf=0.35, iou=0.45, imgsz=640, verbose=False
                    )[0]

                boxes = results.boxes
                if boxes is not None and len(boxes) > 0:
                    xyxys = boxes.xyxy.cpu().numpy()
                    confs = boxes.conf.cpu().numpy()
                    ids = boxes.id.cpu().numpy() if boxes.id is not None else np.arange(len(xyxys))

                    for box, conf, raw_id in zip(xyxys, confs, ids):
                        raw_boxes.append(box)
                        raw_confs.append(float(conf))
                        active_raw_ids.append(int(raw_id))
            except Exception as e:
                # Fallback to standard detect
                try:
                    results = det_model(frame, conf=0.35, iou=0.45, verbose=False)[0]
                    boxes = results.boxes
                    if boxes is not None and len(boxes) > 0:
                        for i, (b, c) in enumerate(zip(boxes.xyxy.cpu().numpy(), boxes.conf.cpu().numpy())):
                            raw_boxes.append(b)
                            raw_confs.append(float(c))
                            active_raw_ids.append(i + 1)
                except Exception:
                    pass

        cleanup_stale_ids(active_raw_ids)

        detection_list = []
        centroids = []
        healthy_count = 0
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

        # 2. Process each detected chicken
        for idx, (box, conf, raw_id) in enumerate(zip(raw_boxes, raw_confs, active_raw_ids)):
            display_id = assign_display_id(raw_id)
            x1, y1, x2, y2 = map(int, box)
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(w_frame, x2), min(h_frame, y2)
            cx, cy = centroids[idx]
            is_isolated = isolated_flags[idx]

            # Motion Calculation
            hist = track_histories[raw_id]
            motion = 0.0
            if len(hist) > 0:
                prev_c = hist[-1]
                motion = float(np.hypot(cx - prev_c[0], cy - prev_c[1]))
            hist.append((int(cx), int(cy)))

            # Crop for Behavior & Health Classification
            crop = frame[y1:y2, x1:x2]
            behavior_label = "stand"
            health_label = "Healthy"

            if crop.size > 0 and crop.shape[0] >= 20 and crop.shape[1] >= 20:
                # Behavior Classification
                if behavior_model is not None:
                    try:
                        b_res = behavior_model.predict(crop, imgsz=224, verbose=False)[0]
                        top_b = int(b_res.probs.top1)
                        behavior_label = BEHAVIOR_NAMES.get(top_b, "stand")
                    except Exception:
                        pass
                
                # Motion fusion heuristic
                if motion > 5.0:
                    behavior_label = "walk"
                elif motion > 3.0 and behavior_label == "peck":
                    behavior_label = "peck"

                # Health Classification
                if health_model is not None:
                    try:
                        h_res = health_model.predict(crop, imgsz=224, verbose=False)[0]
                        top_h = int(h_res.probs.top1)
                        health_label = HEALTH_NAMES.get(top_h, "Healthy")
                    except Exception:
                        pass

            if health_label == "Healthy":
                healthy_count += 1
            else:
                unhealthy_count += 1

            # Determine Display Colors & HUD Badges
            color = get_id_color(display_id)
            if health_label == "Unhealthy":
                color = (70, 70, 255) # Red/Coral for unhealthy alert
            elif is_isolated:
                color = (0, 180, 255) # Amber warning for isolated

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
            health_index = max(10, int(100 - (unhealthy_count * 25) - (isolated_count * 10)))
        else:
            health_index = 100

        # Clean frame without text overlays

        # Encode Frame
        _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])

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
                "unhealthy_count": unhealthy_count,
                "isolated_count": isolated_count,
                "health_index": health_index,
                "track_count": flock_count,
                "detections": detection_list
            }

    cap.release()


def main():
    print("=" * 65)
    print("   POULTRYSENSE AI - CHICKEN HEALTH & BEHAVIOR MONITORING SERVER   ")
    print("=" * 65)
    cam_thread = threading.Thread(target=camera_loop, daemon=True)
    cam_thread.start()
    print("[INFO] PoultrySense AI Server running at http://0.0.0.0:5000")
    flask_app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)


if __name__ == "__main__":
    main()
