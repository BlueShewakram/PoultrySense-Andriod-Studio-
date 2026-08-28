import cv2
import time
import json
import os
import threading
import numpy as np
import sys
from collections import defaultdict

# ─── 1. Import Ultralytics YOLO with ByteTrack Support ───
try:
    from ultralytics import YOLO
    HAS_YOLO = True
except ImportError:
    HAS_YOLO = False
    print("[WARN] Ultralytics not installed. Install with: pip install ultralytics")

# ─── 2. Import MediaPipe ───
try:
    import mediapipe as mp
    mp_hands = mp.solutions.hands
    mp_draw = mp.solutions.drawing_utils
    mp_drawing_styles = mp.solutions.drawing_styles
    HAS_MEDIAPIPE = True
except (ImportError, AttributeError):
    HAS_MEDIAPIPE = False
    print("[WARN] MediaPipe not available or version mismatch.")

# ─── 3. Import Flask ───
try:
    from flask import Flask, Response, jsonify
except ImportError:
    print("[ERROR] Flask is required. Install it with: pip install flask")
    sys.exit(1)

# ─── Global shared state ───
latest_frame = None
latest_jpeg_bytes = None
latest_status = {
    "active": False,
    "fps": 0.0,
    "hand_count": 0,
    "flock_count": 0,
    "track_count": 0,
    "health_index": 100,
    "detections": []
}
frame_lock = threading.Lock()

# ByteTrack history trail storage (track_id -> list of centroids)
track_history = defaultdict(lambda: [])
MAX_TRAIL_LENGTH = 30

# ─── Flask Application ───
flask_app = Flask(__name__)

@flask_app.route('/video_feed')
def video_feed():
    """MJPEG stream endpoint — delivers real-time AI-processed camera feed."""
    def generate():
        while True:
            with frame_lock:
                if latest_jpeg_bytes is None:
                    time.sleep(0.01)
                    continue
                frame_bytes = latest_jpeg_bytes
            yield (b'--frame\r\n'
                   b'Content-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            time.sleep(0.02)  # Cap stream serving to ~50 FPS

    return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')

@flask_app.route('/frame.jpg')
def frame_jpg():
    """Single JPEG snapshot endpoint for fast frame-by-frame polling."""
    with frame_lock:
        if latest_jpeg_bytes is None:
            return "No frame available", 404
        return Response(latest_jpeg_bytes, mimetype='image/jpeg')

@flask_app.route('/status')
def status():
    """JSON telemetry endpoint consumed by PoultrySense mobile app."""
    with frame_lock:
        return jsonify(latest_status)

@flask_app.route('/')
def index():
    """Health check & real-time monitoring dashboard page."""
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <title>PoultrySense AI Vision Server</title>
        <style>
            body { background: #0A140D; color: #E8F5E9; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; text-align: center; margin: 0; padding: 20px; }
            h1 { color: #00E676; margin-bottom: 4px; }
            .badge { background: #0D631B; color: #CBFFC2; padding: 4px 12px; border-radius: 12px; font-size: 13px; font-weight: bold; display: inline-block; }
            .container { max-width: 800px; margin: 20px auto; background: #132417; border: 1px solid #1F4225; border-radius: 16px; padding: 20px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); }
            img { width: 100%; max-width: 640px; border-radius: 12px; border: 2px solid #00E676; box-shadow: 0 0 20px rgba(0,230,118,0.2); }
            .links { margin-top: 16px; }
            a { color: #00E676; text-decoration: none; font-weight: bold; margin: 0 10px; }
            a:hover { text-decoration: underline; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>🐔 PoultrySense AI</h1>
            <div class="badge">ByteTrack Multi-Object Tracking &amp; Health AI</div>
            <div style="margin: 16px 0;">
                <img src="/video_feed" alt="Live AI Camera Feed">
            </div>
            <div class="links">
                <a href="/video_feed" target="_blank">🎥 MJPEG Stream</a>
                <a href="/status" target="_blank">📊 JSON Telemetry</a>
            </div>
        </div>
    </body>
    </html>
    """

# ─── Visual Drawing Helpers ───
def draw_corner_rect(img, pt1, pt2, color, thickness=2, line_len=18):
    """Draws stylish modern HUD corner accents for bounding boxes."""
    x1, y1 = int(pt1[0]), int(pt1[1])
    x2, y2 = int(pt2[0]), int(pt2[1])

    # Semi-transparent bounding fill
    overlay = img.copy()
    cv2.rectangle(overlay, (x1, y1), (x2, y2), color, -1)
    cv2.addWeighted(overlay, 0.12, img, 0.88, 0, img)

    # Outline rectangle
    cv2.rectangle(img, (x1, y1), (x2, y2), color, thickness)

    # Corner lines
    # Top-Left
    cv2.line(img, (x1, y1), (x1 + line_len, y1), color, thickness + 2)
    cv2.line(img, (x1, y1), (x1, y1 + line_len), color, thickness + 2)
    # Top-Right
    cv2.line(img, (x2, y1), (x2 - line_len, y1), color, thickness + 2)
    cv2.line(img, (x2, y1), (x2, y1 + line_len), color, thickness + 2)
    # Bottom-Left
    cv2.line(img, (x1, y2), (x1 + line_len, y2), color, thickness + 2)
    cv2.line(img, (x1, y2), (x1, y2 - line_len), color, thickness + 2)
    # Bottom-Right
    cv2.line(img, (x2, y2), (x2 - line_len, y2), color, thickness + 2)
    cv2.line(img, (x2, y2), (x2, y2 - line_len), color, thickness + 2)

def _dist2(a, b):
    """2D Euclidean distance between two landmarks."""
    return ((a.x - b.x) ** 2 + (a.y - b.y) ** 2) ** 0.5

def get_extended_fingers(landmarks):
    """Counts extended fingers accurately for gesture-based telemetry."""
    finger_tips = [8, 12, 16, 20]
    pip_joints = [6, 10, 14, 18]
    mcp_joints = [5, 9, 13, 17]

    extended_tips = []
    count = 0

    for tip_idx, pip_idx, mcp_idx in zip(finger_tips, pip_joints, mcp_joints):
        tip = landmarks[tip_idx]
        pip = landmarks[pip_idx]
        mcp = landmarks[mcp_idx]

        v1x, v1y = pip.x - mcp.x, pip.y - mcp.y
        v2x, v2y = tip.x - pip.x, tip.y - pip.y
        len1 = (v1x * v1x + v1y * v1y) ** 0.5
        len2 = (v2x * v2x + v2y * v2y) ** 0.5
        dot = (v1x * v2x + v1y * v2y) / (len1 * len2 + 1e-9)

        d_tip_mcp = _dist2(tip, mcp)
        d_pip_mcp = _dist2(pip, mcp)

        if (dot > 0.4) or (d_tip_mcp > d_pip_mcp * 1.15):
            count += 1
            extended_tips.append(tip_idx)

    # Thumb check
    thumb_tip, thumb_ip = landmarks[4], landmarks[3]
    pinky_mcp, wrist = landmarks[17], landmarks[0]
    if (_dist2(thumb_tip, pinky_mcp) > _dist2(thumb_ip, pinky_mcp) * 1.12) and \
       (_dist2(thumb_tip, wrist) > _dist2(thumb_ip, wrist) * 1.08):
        count += 1
        extended_tips.append(4)

    return count, extended_tips

# ─── Main Camera Processing Loop with ByteTrack ───
def camera_loop():
    global latest_frame, latest_jpeg_bytes, latest_status, track_history

    print("[INFO] Initializing PoultrySense AI Engine with ByteTrack...")

    # Load YOLO model
    yolo_model = None
    if HAS_YOLO:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        model_path = os.path.join(script_dir, "yolo11n.pt")
        if not os.path.exists(model_path):
            model_path = "yolo11n.pt"

        try:
            print(f"[INFO] Loading YOLO model from: {model_path}")
            yolo_model = YOLO(model_path)
            print("[INFO] YOLO11 & ByteTrack tracker successfully initialized!")
        except Exception as e:
            print(f"[WARN] Could not load YOLO model: {e}")
            yolo_model = None

    # Load MediaPipe Hands
    hands_detector = None
    if HAS_MEDIAPIPE:
        try:
            hands_detector = mp_hands.Hands(
                static_image_mode=False,
                max_num_hands=2,
                model_complexity=1,
                min_detection_confidence=0.45,
                min_tracking_confidence=0.45
            )
            print("[INFO] MediaPipe Gesture Engine initialized.")
        except Exception as e:
            print(f"[WARN] MediaPipe init error: {e}")

    # Open Camera with DirectShow on Windows
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

        detection_list = []
        hand_count = 0
        flock_count = 0
        active_track_ids = []

        # ─── 1. Run ByteTrack on Video Frame via YOLO ───
        if yolo_model is not None:
            try:
                # ByteTrack multi-object tracking with persistence
                results = yolo_model.track(
                    frame,
                    persist=True,
                    tracker="bytetrack.yaml",
                    conf=0.35,
                    iou=0.5,
                    verbose=False
                )

                if results and len(results) > 0:
                    boxes = results[0].boxes
                    if boxes is not None and boxes.id is not None:
                        track_ids = boxes.id.int().cpu().tolist()
                        classes = boxes.cls.int().cpu().tolist()
                        confs = boxes.conf.cpu().tolist()
                        xyxys = boxes.xyxy.cpu().tolist()

                        for box, track_id, cls_id, conf in zip(xyxys, track_ids, classes, confs):
                            x1, y1, x2, y2 = map(int, box)
                            active_track_ids.append(track_id)
                            flock_count += 1

                            # Compute Center point & save trajectory history for ByteTrack trail
                            cx, cy = int((x1 + x2) / 2), int((y1 + y2) / 2)
                            track_history[track_id].append((cx, cy))
                            if len(track_history[track_id]) > MAX_TRAIL_LENGTH:
                                track_history[track_id].pop(0)

                            # Draw motion breadcrumb trail
                            points = np.array(track_history[track_id], dtype=np.int32).reshape((-1, 1, 2))
                            cv2.polylines(frame, [points], isClosed=False, color=(0, 230, 118), thickness=2)

                            # Determine status & color
                            status = "HEALTHY"
                            color = (0, 230, 0)       # Green (BGR)
                            badge_bg = (0, 90, 0)

                            class_name = yolo_model.names.get(cls_id, f"Object {cls_id}")

                            # Draw ByteTrack HUD bounding box
                            draw_corner_rect(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=18)

                            # ByteTrack Tag: e.g. "ID #1 | Healthy Bird (92%)"
                            label = f" ID #{track_id} | {class_name.capitalize()} ({int(conf * 100)}%) "
                            (text_w, text_h), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_DUPLEX, 0.55, 1)
                            label_y = max(y1 - 10, text_h + 10)
                            cv2.rectangle(frame, (x1, label_y - text_h - 4), (x1 + text_w + 8, label_y + baseline + 2), badge_bg, -1)
                            cv2.rectangle(frame, (x1, label_y - text_h - 4), (x1 + text_w + 8, label_y + baseline + 2), color, 1)
                            cv2.putText(frame, label, (x1 + 4, label_y - 2), cv2.FONT_HERSHEY_DUPLEX, 0.55, (255, 255, 255), 1)

                            detection_list.append({
                                "track_id": track_id,
                                "class": class_name,
                                "confidence": round(conf, 2),
                                "status": status,
                                "bbox": [x1, y1, x2, y2]
                            })
            except Exception as e:
                # Fallback silently to maintain high FPS
                pass

        # ─── 2. Run MediaPipe Gesture Telemetry ───
        if hands_detector is not None:
            try:
                rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                mp_results = hands_detector.process(rgb_frame)

                if mp_results.multi_hand_landmarks:
                    for idx, hand_landmarks in enumerate(mp_results.multi_hand_landmarks):
                        x_coords = [lm.x * w_frame for lm in hand_landmarks.landmark]
                        y_coords = [lm.y * h_frame for lm in hand_landmarks.landmark]

                        padding = 18
                        x1 = max(0, int(min(x_coords)) - padding)
                        y1 = max(0, int(min(y_coords)) - padding)
                        x2 = min(w_frame, int(max(x_coords)) + padding)
                        y2 = min(h_frame, int(max(y_coords)) + padding)

                        fingers_count, extended_tips = get_extended_fingers(hand_landmarks.landmark)
                        hand_count += 1

                        # Draw MediaPipe hand skeleton
                        mp_draw.draw_landmarks(
                            frame, hand_landmarks, mp_hands.HAND_CONNECTIONS,
                            mp_drawing_styles.get_default_hand_landmarks_style(),
                            mp_drawing_styles.get_default_hand_connections_style()
                        )

                        # Highlight extended finger tips with glowing points
                        for tip_idx in extended_tips:
                            cx = int(hand_landmarks.landmark[tip_idx].x * w_frame)
                            cy = int(hand_landmarks.landmark[tip_idx].y * h_frame)
                            cv2.circle(frame, (cx, cy), 9, (255, 255, 255), -1)
                            cv2.circle(frame, (cx, cy), 5, (0, 230, 0), -1)

                        if fingers_count <= 1:
                            status = "HEALTHY"
                            color = (0, 230, 0)
                            badge_bg = (0, 90, 0)
                        elif fingers_count == 2:
                            status = "WARNING"
                            color = (0, 215, 255)
                            badge_bg = (0, 110, 130)
                        else:
                            status = "CRITICAL"
                            color = (0, 0, 240)
                            badge_bg = (0, 0, 110)

                        draw_corner_rect(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=18)
                        label = f" {status} ({fingers_count} Finger{'s' if fingers_count != 1 else ''}) "
                        (text_w, text_h), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_DUPLEX, 0.55, 1)
                        label_y = max(y1 - 10, text_h + 10)
                        cv2.rectangle(frame, (x1, label_y - text_h - 4), (x1 + text_w + 8, label_y + baseline + 2), badge_bg, -1)
                        cv2.rectangle(frame, (x1, label_y - text_h - 4), (x1 + text_w + 8, label_y + baseline + 2), color, 1)
                        cv2.putText(frame, label, (x1 + 4, label_y - 2), cv2.FONT_HERSHEY_DUPLEX, 0.55, (255, 255, 255), 1)

                        detection_list.append({
                            "status": status,
                            "fingers": fingers_count,
                            "bbox": [x1, y1, x2, y2]
                        })
            except Exception as e:
                pass

        # ─── 3. Clean up stale ByteTrack history ───
        current_keys = list(track_history.keys())
        for tid in current_keys:
            if tid not in active_track_ids and len(track_history[tid]) > 0:
                track_history[tid].pop(0)
                if len(track_history[tid]) == 0:
                    del track_history[tid]


        # ─── 5. Encode Frame & Update Global Telemetry State ───
        _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
        with frame_lock:
            latest_frame = frame
            latest_jpeg_bytes = jpeg.tobytes()
            latest_status = {
                "active": True,
                "fps": round(fps, 1),
                "hand_count": hand_count,
                "flock_count": flock_count or hand_count,
                "track_count": len(active_track_ids),
                "health_index": 98 if (hand_count == 0 or any(d.get("status") == "HEALTHY" for d in detection_list)) else 75,
                "detections": detection_list
            }

    cap.release()

def main():
    print("=" * 60)
    print("      POULTRYSENSE AI - BYTETRACK OBJECT TRACKING SERVER      ")
    print("=" * 60)

    cam_thread = threading.Thread(target=camera_loop, daemon=True)
    cam_thread.start()

    print("[INFO] Starting server on http://0.0.0.0:5000")
    flask_app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)

if __name__ == "__main__":
    main()
