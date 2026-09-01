import cv2
import time
import json
import os
import threading
import numpy as np
import sys
from collections import defaultdict

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

# --- Global shared state ---
latest_frame = None
latest_jpeg_bytes = None
latest_source_label = "Waiting for Camera..."
latest_status = {
    "active": False,
    "source": "Laptop Webcam",
    "fps": 0.0,
    "person_count": 0,
    "hand_count": 0,
    "flock_count": 0,
    "track_count": 0,
    "health_index": 100,
    "detections": []
}
frame_lock = threading.Lock()
last_phone_frame_time = 0.0

# --- Visual Appearance Re-Identification (Re-ID) Tracker ---
class PersonReIDTracker:
    def __init__(self, reid_thresh=0.62, ema_alpha=0.15, max_trail_len=35):
        self.next_id = 1
        self.active_tracks = {}
        self.gallery = {}
        self.reid_thresh = reid_thresh
        self.ema_alpha = ema_alpha
        self.max_trail_len = max_trail_len
        self.lock = threading.Lock()

    def extract_features(self, frame, bbox):
        x1, y1, x2, y2 = map(int, bbox)
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(frame.shape[1], x2), min(frame.shape[0], y2)
        crop = frame[y1:y2, x1:x2]
        if crop.size == 0 or crop.shape[0] < 12 or crop.shape[1] < 12:
            return None
        hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
        lab = cv2.cvtColor(crop, cv2.COLOR_BGR2Lab)
        h, w, _ = crop.shape
        h1, h2 = h // 3, (2 * h) // 3
        zones_hsv = [hsv[0:h1, :], hsv[h1:h2, :], hsv[h2:, :]]
        zones_lab = [lab[0:h1, :], lab[h1:h2, :], lab[h2:, :]]
        feat = []
        for zh, zl in zip(zones_hsv, zones_lab):
            if zh.size == 0 or zl.size == 0:
                feat.extend([0.0] * 48)
                continue
            h_hist = cv2.calcHist([zh], [0], None, [16], [0, 180])
            s_hist = cv2.calcHist([zh], [1], None, [8], [0, 256])
            v_hist = cv2.calcHist([zh], [2], None, [8], [0, 256])
            a_hist = cv2.calcHist([zl], [1], None, [8], [0, 256])
            b_hist = cv2.calcHist([zl], [2], None, [8], [0, 256])
            for hst in (h_hist, s_hist, v_hist, a_hist, b_hist):
                cv2.normalize(hst, hst)
                feat.extend(hst.flatten())
        feat = np.array(feat, dtype=np.float32)
        norm = np.linalg.norm(feat)
        if norm > 1e-6:
            feat /= norm
        return feat

    @staticmethod
    def iou(bb1, bb2):
        x_left = max(bb1[0], bb2[0])
        y_top = max(bb1[1], bb2[1])
        x_right = min(bb1[2], bb2[2])
        y_bottom = min(bb1[3], bb2[3])
        if x_right <= x_left or y_bottom <= y_top:
            return 0.0
        inter = (x_right - x_left) * (y_bottom - y_top)
        area1 = (bb1[2] - bb1[0]) * (bb1[3] - bb1[1])
        area2 = (bb2[2] - bb2[0]) * (bb2[3] - bb2[1])
        return inter / float(area1 + area2 - inter + 1e-6)

    def update(self, frame, raw_detections):
        with self.lock:
            curr_time = time.time()
            diag = (frame.shape[0]**2 + frame.shape[1]**2) ** 0.5
            valid_dets = []
            for bbox, conf in raw_detections:
                feat = self.extract_features(frame, bbox)
                if feat is not None:
                    cx = (bbox[0] + bbox[2]) / 2.0
                    cy = (bbox[1] + bbox[3]) / 2.0
                    valid_dets.append({'bbox': [int(b) for b in bbox], 'conf': float(conf), 'feat': feat, 'centroid': (int(cx), int(cy))})
            updated_tracks = {}
            unmatched_dets = list(range(len(valid_dets)))
            matched_tracks = set()
            active_ids = list(self.active_tracks.keys())
            cost_matrix = []
            for det_idx in unmatched_dets:
                det = valid_dets[det_idx]
                for tid in active_ids:
                    trk = self.active_tracks[tid]
                    iou_val = self.iou(det['bbox'], trk['bbox'])
                    trk_cx, trk_cy = trk['centroid']
                    dist = (((det['centroid'][0] - trk_cx)**2 + (det['centroid'][1] - trk_cy)**2)**0.5) / (diag + 1e-5)
                    sim = float(np.dot(det['feat'], trk['feat']))
                    score = 0.45 * iou_val + 0.35 * sim + 0.20 * max(0.0, 1.0 - dist * 3.0)
                    cost_matrix.append((score, det_idx, tid, sim, iou_val))
            cost_matrix.sort(key=lambda x: x[0], reverse=True)
            assigned_dets = set()
            for score, det_idx, tid, sim, iou_val in cost_matrix:
                if det_idx in assigned_dets or tid in matched_tracks:
                    continue
                if score > 0.40 or iou_val > 0.35 or sim > 0.75:
                    assigned_dets.add(det_idx)
                    matched_tracks.add(tid)
                    det = valid_dets[det_idx]
                    trk = self.active_tracks[tid]
                    smooth_bbox = [int(0.75 * det['bbox'][i] + 0.25 * trk['bbox'][i]) for i in range(4)]
                    updated_feat = (1 - self.ema_alpha) * trk['feat'] + self.ema_alpha * det['feat']
                    updated_feat /= (np.linalg.norm(updated_feat) + 1e-6)
                    trail = list(trk.get('trail', []))
                    trail.append(det['centroid'])
                    if len(trail) > self.max_trail_len:
                        trail.pop(0)
                    updated_tracks[tid] = {'bbox': smooth_bbox, 'conf': det['conf'], 'feat': updated_feat, 'centroid': det['centroid'], 'last_seen': curr_time, 'trail': trail}
                    if tid in self.gallery:
                        self.gallery[tid]['feat'] = updated_feat
                        self.gallery[tid]['last_seen'] = curr_time
                        self.gallery[tid]['sample_count'] += 1
                    else:
                        self.gallery[tid] = {'feat': updated_feat, 'last_seen': curr_time, 'first_seen': curr_time, 'sample_count': 1}
            unmatched_dets = [i for i in range(len(valid_dets)) if i not in assigned_dets]
            for det_idx in unmatched_dets:
                det = valid_dets[det_idx]
                best_tid = None
                best_sim = -1.0
                for tid, g_data in self.gallery.items():
                    if tid in updated_tracks:
                        continue
                    sim = float(np.dot(det['feat'], g_data['feat']))
                    if sim > best_sim:
                        best_sim = sim
                        best_tid = tid
                if best_tid is not None and best_sim >= self.reid_thresh:
                    tid = best_tid
                    prev = self.gallery[tid]
                    updated_feat = (1 - self.ema_alpha) * prev['feat'] + self.ema_alpha * det['feat']
                    updated_feat /= (np.linalg.norm(updated_feat) + 1e-6)
                    updated_tracks[tid] = {'bbox': det['bbox'], 'conf': det['conf'], 'feat': updated_feat, 'centroid': det['centroid'], 'last_seen': curr_time, 'trail': [det['centroid']]}
                    self.gallery[tid] = {'feat': updated_feat, 'last_seen': curr_time, 'first_seen': prev.get('first_seen', curr_time), 'sample_count': prev.get('sample_count', 0) + 1}
                else:
                    tid = self.next_id
                    self.next_id += 1
                    updated_tracks[tid] = {'bbox': det['bbox'], 'conf': det['conf'], 'feat': det['feat'], 'centroid': det['centroid'], 'last_seen': curr_time, 'trail': [det['centroid']]}
                    self.gallery[tid] = {'feat': det['feat'], 'last_seen': curr_time, 'first_seen': curr_time, 'sample_count': 1}
            self.active_tracks = updated_tracks
            return updated_tracks

    def reset(self):
        with self.lock:
            self.next_id = 1
            self.active_tracks.clear()
            self.gallery.clear()


reid_tracker = PersonReIDTracker(reid_thresh=0.62)

# Load global YOLO model once
yolo_model = None
if HAS_YOLO:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(script_dir, "yolo11n.pt")
    if not os.path.exists(model_path):
        model_path = "yolo11n.pt"
    try:
        print(f"[INFO] Loading YOLO model from: {model_path}")
        yolo_model = YOLO(model_path)
        print("[INFO] YOLO11 Person Detection Engine initialized!")
    except Exception as e:
        print(f"[WARN] Could not load YOLO model: {e}")

# Color palette for person IDs
ID_COLORS = [
    (0, 230, 118), (255, 170, 0), (0, 215, 255), (235, 100, 255),
    (255, 120, 0), (100, 255, 200), (180, 255, 100), (255, 100, 100),
]

def get_id_color(track_id):
    return ID_COLORS[(track_id - 1) % len(ID_COLORS)]

def draw_hud_box(img, pt1, pt2, color, thickness=2, line_len=20):
    x1, y1 = int(pt1[0]), int(pt1[1])
    x2, y2 = int(pt2[0]), int(pt2[1])
    overlay = img.copy()
    cv2.rectangle(overlay, (x1, y1), (x2, y2), color, -1)
    cv2.addWeighted(overlay, 0.10, img, 0.90, 0, img)
    cv2.rectangle(img, (x1, y1), (x2, y2), color, 1)
    cv2.line(img, (x1, y1), (x1 + line_len, y1), color, thickness + 1)
    cv2.line(img, (x1, y1), (x1, y1 + line_len), color, thickness + 1)
    cv2.line(img, (x2, y1), (x2 - line_len, y1), color, thickness + 1)
    cv2.line(img, (x2, y1), (x2, y2 + line_len), color, thickness + 1)
    cv2.line(img, (x1, y2), (x1 + line_len, y2), color, thickness + 1)
    cv2.line(img, (x1, y2), (x1, y2 - line_len), color, thickness + 1)
    cv2.line(img, (x2, y2), (x2 - line_len, y2), color, thickness + 1)
    cv2.line(img, (x2, y2), (x2, y2 - line_len), color, thickness + 1)

# Timers for FPS calculation per source
fps_records = defaultdict(lambda: {"prev_time": time.time(), "fps": 30.0})

def process_ai_frame(frame, source_label="Phone Camera HD"):
    """
    Core AI Processing function:
    - Runs YOLO11 person detection (class 0)
    - Updates persistent Re-ID tracker
    - Draws HUD overlays, ID tags, motion trails
    - Updates global telemetry and encoded JPEG bytes
    """
    global latest_frame, latest_jpeg_bytes, latest_status, latest_source_label

    curr_time = time.time()
    rec = fps_records[source_label]
    dt = curr_time - rec["prev_time"]
    rec["prev_time"] = curr_time
    if dt > 0:
        rec["fps"] = 0.85 * rec["fps"] + 0.15 * (1.0 / dt)
    fps = rec["fps"]

    h_frame, w_frame, _ = frame.shape
    detection_list = []
    raw_person_detections = []

    if yolo_model is not None:
        try:
            results = yolo_model(frame, classes=[0], conf=0.40, iou=0.45, verbose=False)
            if results and len(results) > 0:
                boxes = results[0].boxes
                if boxes is not None and len(boxes) > 0:
                    confs = boxes.conf.cpu().tolist()
                    xyxys = boxes.xyxy.cpu().tolist()
                    for box, conf in zip(xyxys, confs):
                        raw_person_detections.append((box, conf))
        except Exception:
            pass

    active_tracks = reid_tracker.update(frame, raw_person_detections)
    person_count = len(active_tracks)

    for track_id, trk in active_tracks.items():
        x1, y1, x2, y2 = trk['bbox']
        conf = trk['conf']
        trail = trk.get('trail', [])
        color = get_id_color(track_id)
        badge_bg = (int(color[0] * 0.25), int(color[1] * 0.25), int(color[2] * 0.25))

        if len(trail) > 1:
            pts = np.array(trail, dtype=np.int32).reshape((-1, 1, 2))
            cv2.polylines(frame, [pts], isClosed=False, color=color, thickness=2)
            cv2.circle(frame, trail[-1], 4, color, -1)

        draw_hud_box(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=20)
        label = f" Person #{track_id} ({int(conf * 100)}%) "
        (text_w, text_h), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_DUPLEX, 0.52, 1)
        label_y = max(y1 - 10, text_h + 10)
        cv2.rectangle(frame, (x1, label_y - text_h - 6), (x1 + text_w + 8, label_y + baseline + 2), badge_bg, -1)
        cv2.rectangle(frame, (x1, label_y - text_h - 6), (x1 + text_w + 8, label_y + baseline + 2), color, 1)
        cv2.putText(frame, label, (x1 + 4, label_y - 2), cv2.FONT_HERSHEY_DUPLEX, 0.52, (255, 255, 255), 1)

        detection_list.append({
            "track_id": track_id,
            "class": "person",
            "confidence": round(conf, 2),
            "status": "HEALTHY",
            "bbox": [x1, y1, x2, y2]
        })

    # Top HUD Banner
    cv2.putText(frame, f"POULTRYSENSE AI  |  {source_label.upper()}", (16, 26), cv2.FONT_HERSHEY_DUPLEX, 0.45, (0, 230, 118), 1)
    # Bottom HUD Status
    stats_text = f"FPS: {fps:.1f}  |  Active Persons: {person_count}  |  Gallery IDs: {len(reid_tracker.gallery)}"
    cv2.putText(frame, stats_text, (16, h_frame - 14), cv2.FONT_HERSHEY_DUPLEX, 0.45, (200, 200, 200), 1)

    _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
    jpeg_bytes = jpeg.tobytes()

    with frame_lock:
        latest_frame = frame
        latest_jpeg_bytes = jpeg_bytes
        latest_source_label = source_label
        latest_status = {
            "active": True,
            "source": source_label,
            "fps": round(fps, 1),
            "person_count": person_count,
            "hand_count": person_count,
            "flock_count": person_count,
            "track_count": person_count,
            "health_index": 100 if person_count > 0 else 95,
            "detections": detection_list
        }

    return frame, jpeg_bytes, active_tracks, fps


# --- Flask Application ---
flask_app = Flask(__name__)

@flask_app.route('/upload_frame', methods=['POST'])
def upload_frame():
    """
    Receives high-clarity camera frames from the Android Phone CameraX,
    runs YOLO11 Person Detection + Persistent Re-ID, and returns the
    AI-annotated frame back to the phone in real time!
    """
    global last_phone_frame_time

    raw_bytes = None
    if request.files and 'frame' in request.files:
        raw_bytes = request.files['frame'].read()
    else:
        raw_bytes = request.get_data()

    if not raw_bytes:
        return "No image data provided", 400

    np_arr = np.frombuffer(raw_bytes, np.uint8)
    frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
    if frame is None:
        return "Failed to decode image", 400

    last_phone_frame_time = time.time()
    _, processed_jpeg, active_tracks, fps = process_ai_frame(frame, source_label="Phone Camera HD")

    resp = Response(processed_jpeg, mimetype='image/jpeg')
    resp.headers['X-Person-Count'] = str(len(active_tracks))
    resp.headers['X-FPS'] = f"{fps:.1f}"
    resp.headers['X-Gallery-Count'] = str(len(reid_tracker.gallery))
    return resp

@flask_app.route('/video_feed')
def video_feed():
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
    with frame_lock:
        if latest_jpeg_bytes is None:
            return "No frame available", 404
        return Response(latest_jpeg_bytes, mimetype='image/jpeg')

@flask_app.route('/status')
def status():
    with frame_lock:
        return jsonify(latest_status)

@flask_app.route('/reset_ids')
def reset_ids():
    reid_tracker.reset()
    return jsonify({"status": "success", "message": "Person IDs and Re-ID gallery have been reset."})

@flask_app.route('/')
def index():
    return """<!DOCTYPE html>
<html><head><title>PoultrySense AI - Person Tracking</title>
<style>body{background:#0A140D;color:#E8F5E9;font-family:-apple-system,sans-serif;text-align:center;margin:0;padding:20px}
h1{color:#00E676}.badge{background:#0D631B;color:#CBFFC2;padding:4px 12px;border-radius:12px;font-size:13px;font-weight:bold;display:inline-block}
.container{max-width:800px;margin:20px auto;background:#132417;border:1px solid #1F4225;border-radius:16px;padding:20px}
img{width:100%;max-width:640px;border-radius:12px;border:2px solid #00E676}
a{color:#00E676;text-decoration:none;font-weight:bold;margin:0 10px}</style></head>
<body><div class="container"><h1>PoultrySense AI Vision</h1>
<div class="badge">Persistent Person Detection & Re-Identification</div>
<div style="margin:16px 0"><img src="/video_feed" alt="Live AI Camera Feed"></div>
<div><a href="/video_feed" target="_blank">MJPEG Stream</a>
<a href="/status" target="_blank">JSON Telemetry</a>
<a href="/reset_ids" onclick="fetch('/reset_ids');alert('IDs Reset!');return false;">Reset IDs</a></div>
</div></body></html>"""

# --- Laptop Camera Loop ---
def camera_loop():
    global last_phone_frame_time
    print("[INFO] Starting Laptop Camera Monitor...")
    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap = cv2.VideoCapture(0)
    if cap.isOpened():
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        cap.set(cv2.CAP_PROP_FPS, 30)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    while True:
        # If phone is streaming frames actively, pause PC webcam AI inference to save CPU
        if (time.time() - last_phone_frame_time) < 2.5:
            time.sleep(0.05)
            continue

        if not cap.isOpened():
            time.sleep(0.1)
            continue

        ret, frame = cap.read()
        if not ret:
            time.sleep(0.03)
            continue

        frame = cv2.flip(frame, 1)
        process_ai_frame(frame, source_label="Laptop Webcam")

    cap.release()

def main():
    print("=" * 65)
    print("   POULTRYSENSE AI - PERSISTENT PERSON RE-ID & TRACKING SERVER   ")
    print("=" * 65)
    cam_thread = threading.Thread(target=camera_loop, daemon=True)
    cam_thread.start()
    print("[INFO] Starting server on http://0.0.0.0:5000")
    flask_app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)

if __name__ == "__main__":
    main()
