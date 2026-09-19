import cv2
import time
import json
import os
import threading
import numpy as np
import sys
from collections import defaultdict
from pathlib import Path

from behavior_health import BehaviorHealthEngine

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

# --- Global shared state ---
latest_frame = None
latest_jpeg_bytes = None
latest_status = {
    "active": False,
    "fps": 0.0,
    "person_count": 0,
    "hand_count": 0,
    "flock_count": 0,
    "track_count": 0,
    "health_index": 100,
    "detections": []
}
frame_lock = threading.Lock()

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
behavior_engine = BehaviorHealthEngine()


def _normalize_detect_classes(raw_value: str | None) -> list[int]:
    raw = raw_value or os.environ.get("POULTRYSENSE_DETECT_CLASSES", "14")
    ids = []
    for part in raw.split(","):
        part = part.strip()
        if part:
            try:
                ids.append(int(part))
            except ValueError:
                continue
    ids = list(dict.fromkeys(ids))
    return ids or [14]


# Prefer a custom poultry detector when available, but keep a safe fallback to the
# base YOLO model for generic bird detection and demo setups.
def resolve_detector_model(base_dir=None, env=None):
    base_dir = Path(base_dir) if base_dir is not None else Path(__file__).resolve().parent
    env = os.environ if env is None else env

    custom_candidates = []
    configured = (env.get("POULTRYSENSE_MODEL_PATH") or "").strip()
    if configured:
        custom_candidates.append(Path(configured).expanduser())

    for root in [
        base_dir,
        base_dir / "models",
        base_dir / "training" / "models",
    ]:
        for name in (
            "chicken_detector.pt",
            "poultry_detector.pt",
            "best.pt",
            "yolov8n-chicken.pt",
        ):
            custom_candidates.append(root / name)

    seen = set()
    for candidate in custom_candidates:
        path = str(candidate)
        if path in seen:
            continue
        seen.add(path)
        if candidate.exists():
            return {
                "model_path": str(candidate),
                "model_kind": "custom_poultry_detector",
                "detect_classes": [0, 14],
            }

    fallback = base_dir / "yolo11n.pt"
    fallback_path = str(fallback if fallback.exists() else "yolo11n.pt")
    return {
        "model_path": fallback_path,
        "model_kind": "fallback_yolo11n",
        "detect_classes": _normalize_detect_classes(env.get("POULTRYSENSE_DETECT_CLASSES")),
    }


# Detect COCO bird (14) by default; optionally include person (0) for desk demos.
# Override with env: POULTRYSENSE_DETECT_CLASSES=14,0
def _detect_classes():
    return resolve_detector_model()["detect_classes"]

# --- Flask Application ---
flask_app = Flask(__name__)

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
            # Keep clients from treating the stream as "offline" while camera starts
            placeholder = _placeholder_jpeg("Waiting for camera…")
            return Response(placeholder, mimetype='image/jpeg')
        return Response(latest_jpeg_bytes, mimetype='image/jpeg')

@flask_app.route('/status')
def status():
    with frame_lock:
        return jsonify(latest_status)

@flask_app.route('/reset_ids')
def reset_ids():
    reid_tracker.reset()
    behavior_engine.reset()
    return jsonify({"status": "success", "message": "Person IDs, Re-ID gallery, and behavior history have been reset."})

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

# --- Color palette for person IDs ---
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
    cv2.line(img, (x2, y1), (x2, y1 + line_len), color, thickness + 1)
    cv2.line(img, (x1, y2), (x1 + line_len, y2), color, thickness + 1)
    cv2.line(img, (x1, y2), (x1, y2 - line_len), color, thickness + 1)
    cv2.line(img, (x2, y2), (x2 - line_len, y2), color, thickness + 1)
    cv2.line(img, (x2, y2), (x2, y2 - line_len), color, thickness + 1)

# --- Main Camera Loop ---
def _placeholder_jpeg(message: str) -> bytes:
    """Return a dark JPEG with a status message (always 200 for clients)."""
    img = np.zeros((480, 640, 3), dtype=np.uint8)
    img[:] = (19, 36, 19)  # dark green-ish
    cv2.putText(img, "POULTRYSENSE AI", (160, 200), cv2.FONT_HERSHEY_DUPLEX, 0.9, (0, 230, 118), 2)
    cv2.putText(img, message, (40, 260), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (230, 230, 230), 1)
    if sys.platform == "darwin":
        cv2.putText(
            img,
            "macOS: allow Camera for Terminal/Python",
            (50, 310),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            (180, 180, 180),
            1,
        )
        cv2.putText(
            img,
            "System Settings > Privacy & Security > Camera",
            (55, 345),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (150, 150, 150),
            1,
        )
    ok, jpeg = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 80])
    return jpeg.tobytes() if ok else b""


def open_camera():
    """
    Open the webcam on the *current* thread.
    macOS requires the camera permission prompt on the main thread (AVFoundation).
    """
    if sys.platform == "darwin":
        # Avoid OpenCV trying (and failing) to show TCC UI from a worker thread later
        os.environ.setdefault("OPENCV_AVFOUNDATION_SKIP_AUTH", "0")
        backends = [getattr(cv2, "CAP_AVFOUNDATION", 1200), cv2.CAP_ANY]
    elif sys.platform.startswith("win"):
        backends = [cv2.CAP_DSHOW, cv2.CAP_ANY]
    else:
        backends = [cv2.CAP_V4L2, cv2.CAP_ANY]

    for index in (0, 1, 2):
        for backend in backends:
            print(f"[INFO] Trying camera index={index} backend={backend}…")
            cap = cv2.VideoCapture(index, backend)
            if not cap.isOpened():
                cap.release()
                continue
            # Warm up / verify we can actually read a frame
            ok = False
            frame = None
            for _ in range(8):
                ok, frame = cap.read()
                if ok and frame is not None:
                    break
                time.sleep(0.05)
            if ok and frame is not None:
                cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
                cap.set(cv2.CAP_PROP_FPS, 30)
                try:
                    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
                except Exception:
                    pass
                print(f"[INFO] Camera opened (index={index}, backend={backend}, shape={frame.shape})")
                return cap
            print(f"[WARN] Camera opened but produced no frames (index={index}, backend={backend})")
            cap.release()

    print("[ERROR] Could not open any camera. Grant Camera permission and restart the server.")
    return None


def camera_loop(cap=None):
    global latest_frame, latest_jpeg_bytes, latest_status
    print("[INFO] Initializing PoultrySense AI (poultry detect + behavior/posture health)...")
    yolo_model = None
    detector_cfg = resolve_detector_model()
    detect_classes = detector_cfg["detect_classes"]
    model_path = detector_cfg["model_path"]
    if HAS_YOLO:
        try:
            print(f"[INFO] Loading detector model from: {model_path} ({detector_cfg['model_kind']})")
            yolo_model = YOLO(model_path)
            print(f"[INFO] Detection classes={detect_classes} (14=bird, 0=person)")
            print("[INFO] Health = behavior/posture anomalies (not fecal)")
        except Exception as e:
            print(f"[WARN] Could not load detector model: {e}")
            # keep the original generic fallback if the custom model fails to open
            if detector_cfg["model_kind"] == "custom_poultry_detector":
                fallback_model = os.path.join(os.path.dirname(os.path.abspath(__file__)), "yolo11n.pt")
                if not os.path.exists(fallback_model):
                    fallback_model = "yolo11n.pt"
                try:
                    print(f"[INFO] Falling back to YOLO detector: {fallback_model}")
                    yolo_model = YOLO(fallback_model)
                    detect_classes = _normalize_detect_classes(os.environ.get("POULTRYSENSE_DETECT_CLASSES", "14"))
                except Exception:
                    pass

    owns_cap = False
    if cap is None:
        cap = open_camera()
        owns_cap = True

    if cap is None or not cap.isOpened():
        # Keep serving a visible placeholder so the Android app isn't blank/offline forever
        placeholder = _placeholder_jpeg("Camera unavailable")
        with frame_lock:
            latest_jpeg_bytes = placeholder
            latest_status = {
                "active": False,
                "fps": 0.0,
                "person_count": 0,
                "hand_count": 0,
                "flock_count": 0,
                "track_count": 0,
                "health_index": 0,
                "healthy_count": 0,
                "ill_count": 0,
                "detections": [],
                "error": "camera_unavailable",
            }
        while True:
            time.sleep(2.0)
            retry = open_camera()
            if retry is not None and retry.isOpened():
                cap = retry
                owns_cap = True
                print("[INFO] Camera recovered after retry")
                break

    prev_time = time.time()
    while True:
        ret, frame = cap.read()
        if not ret or frame is None:
            time.sleep(0.03)
            continue
        frame = cv2.flip(frame, 1)
        h_frame, w_frame, _ = frame.shape
        curr_time = time.time()
        fps = 1.0 / (curr_time - prev_time + 1e-5)
        prev_time = curr_time
        detection_list = []
        raw_detections = []
        if yolo_model is not None:
            try:
                results = yolo_model(
                    frame,
                    classes=detect_classes,
                    conf=0.35,
                    iou=0.45,
                    verbose=False,
                )
                if results and len(results) > 0:
                    boxes = results[0].boxes
                    if boxes is not None and len(boxes) > 0:
                        confs = boxes.conf.cpu().tolist()
                        xyxys = boxes.xyxy.cpu().tolist()
                        for box, conf in zip(xyxys, confs):
                            raw_detections.append((box, conf))
            except Exception:
                pass
        active_tracks = reid_tracker.update(frame, raw_detections)
        flock_count = len(active_tracks)
        healthy_count = 0
        ill_count = 0
        for track_id, trk in active_tracks.items():
            x1, y1, x2, y2 = trk['bbox']
            conf = trk['conf']
            trail = trk.get('trail', [])
            health = behavior_engine.observe(track_id, trk['bbox'], frame.shape, now=curr_time)
            status = health["status"]
            behavior = health["behavior"]
            if status == "HEALTHY":
                healthy_count += 1
                color = get_id_color(track_id)
            else:
                ill_count += 1
                # Flagged birds in amber/red
                color = (0, 165, 255) if behavior == "lethargic" else (0, 80, 255)
            badge_bg = (int(color[0] * 0.25), int(color[1] * 0.25), int(color[2] * 0.25))
            if len(trail) > 1:
                pts = np.array(trail, dtype=np.int32).reshape((-1, 1, 2))
                cv2.polylines(frame, [pts], isClosed=False, color=color, thickness=2)
                cv2.circle(frame, trail[-1], 4, color, -1)
            draw_hud_box(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=20)
            label = f" Bird #{track_id} {behavior.upper()} ({int(conf * 100)}%) "
            (text_w, text_h), baseline = cv2.getTextSize(label, cv2.FONT_HERSHEY_DUPLEX, 0.48, 1)
            label_y = max(y1 - 10, text_h + 10)
            cv2.rectangle(frame, (x1, label_y - text_h - 6), (x1 + text_w + 8, label_y + baseline + 2), badge_bg, -1)
            cv2.rectangle(frame, (x1, label_y - text_h - 6), (x1 + text_w + 8, label_y + baseline + 2), color, 1)
            cv2.putText(frame, label, (x1 + 4, label_y - 2), cv2.FONT_HERSHEY_DUPLEX, 0.48, (255, 255, 255), 1)
            detection_list.append({
                "track_id": track_id,
                "class": "chicken",
                "confidence": round(conf, 2),
                "status": status,
                "behavior": behavior,
                "behavior_confidence": health["confidence"],
                "speed": health["speed"],
                "aspect": health["aspect"],
                "bbox": [x1, y1, x2, y2],
            })
        health_index = behavior_engine.flock_health_index(detection_list)
        cv2.putText(
            frame,
            "POULTRYSENSE AI  |  BEHAVIOR / POSTURE HEALTH",
            (16, 26),
            cv2.FONT_HERSHEY_DUPLEX,
            0.45,
            (0, 230, 118),
            1,
        )
        stats_text = (
            f"FPS: {fps:.1f}  |  Birds: {flock_count}  |  "
            f"Healthy: {healthy_count}  Ill: {ill_count}  |  Index: {health_index}%"
        )
        cv2.putText(frame, stats_text, (16, h_frame - 14), cv2.FONT_HERSHEY_DUPLEX, 0.45, (200, 200, 200), 1)
        _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
        with frame_lock:
            latest_frame = frame
            latest_jpeg_bytes = jpeg.tobytes()
            latest_status = {
                "active": True,
                "fps": round(fps, 1),
                "person_count": flock_count,  # legacy Android field
                "hand_count": flock_count,    # legacy Android field
                "flock_count": flock_count,
                "track_count": flock_count,
                "healthy_count": healthy_count,
                "ill_count": ill_count,
                "health_index": health_index,
                "detections": detection_list,
            }
    if owns_cap and cap is not None:
        cap.release()


def main():
    print("=" * 65)
    print("   POULTRYSENSE AI - BEHAVIOR / POSTURE HEALTH MONITORING")
    print("=" * 65)
    # Open camera on the main thread first (macOS TCC / AVFoundation requirement)
    cap = open_camera()
    cam_thread = threading.Thread(target=camera_loop, kwargs={"cap": cap}, daemon=True)
    cam_thread.start()
    # Port 5050 avoids macOS AirPlay Receiver which monopolizes :5000
    port = int(os.environ.get('POULTRYSENSE_PORT', '5050'))
    print(f"[INFO] Starting server on http://0.0.0.0:{port}")
    flask_app.run(host='0.0.0.0', port=port, debug=False, threaded=True)

if __name__ == "__main__":
    main()
