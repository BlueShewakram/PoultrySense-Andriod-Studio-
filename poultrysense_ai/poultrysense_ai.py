"""
PoultrySense AI Vision Server
Real-time Chicken Detection, Multi-Object Tracking, Behavior Recognition,
and Health Telemetry Engine powered by YOLOv8 trained weights.

Health Classification grounded in:
  - Kestin et al. (1992) Bristol Gait Score
  - Dawkins et al. (2009) Optical-flow calibration
  - Zhuang & Zhang (2019) Lethargy markers
  - Febrer et al. (2006) Spatial isolation
  - Aydin (2017) 3-tier activity classification
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
import base64

from collections import defaultdict, deque

# --- 1. Import Requests & Google Gemini ---
try:
    import requests
except ImportError:
    pass

try:
    from google import genai
    from google.genai import types
    HAS_GEMINI = True
except ImportError:
    HAS_GEMINI = False

# --- 2. Import Ultralytics YOLO ---
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
    os.path.join(SCRIPT_DIR, "yolo11n.pt"),
    "yolo11n.pt",

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
    "observing_count": 0,
    "healthy_count": 0,
    "warning_count": 0,
    "unhealthy_count": 0,
    "isolated_count": 0,
    "health_index": 100,
    "track_count": 0,
    "temperature": 24.5,
    "humidity": 62.0,
    "detections": []
}
latest_sensor_data = {
    "temperature": 24.5,
    "humidity": 62.0,
    "last_updated": 0
}
frame_lock = threading.Lock()

# --- Motion & History State ---
track_histories = defaultdict(lambda: deque(maxlen=30))
track_motion_windows = defaultdict(lambda: deque(maxlen=90))  # 3-second rolling window at 30 FPS

track_first_seen = {}              # display_id -> first appearance timestamp
track_stationary_duration = {}     # display_id -> continuous seconds stationary (motion < 1.6)
track_display_ids = {}
track_missed_counts = {}
track_classifications = {}
track_last_positions = {}
inactive_tracks = {}
classification_queue = queue.Queue(maxsize=6)
classification_lock = threading.Lock()

# Temporal Observation Window (seconds before declaring baseline health)
OBSERVATION_WINDOW = 3.0


# Limit active tracked subjects for ultra-smooth high FPS (supports 6 to 10 chickens)
MAX_TRACKED_CHICKENS = 10

# --- Camera Configuration ---
# Camera index: 1 = external USB camera ("HD Camera")
#               0 = built-in laptop camera (fallback)
EXTERNAL_CAMERA_INDEX = 1
FALLBACK_CAMERA_INDEX = 0


# =====================================================================
#   POULTRYSENSE MULTI-STAGE BEHAVIOR & GEMINI 2.5 ESCALATION ENGINE
# =====================================================================

class ChickenBehaviorHistory:
    """
    Maintains time-series behavior history and normal baseline per tracked chicken ID.
    Calculates walking, feeding/pecking, resting/inactivity, drinking percentages,
    continuous inactivity duration, average optical motion, and spatial isolation frequency.
    """
    def __init__(self, window_sec=180.0):
        self.window_sec = window_sec
        self.records = defaultdict(lambda: deque())
        self.baselines = {}

    def record(self, track_id, timestamp, behavior, motion, is_isolated, center_pt):
        q = self.records[track_id]
        q.append((timestamp, behavior, motion, is_isolated, center_pt))
        cutoff = timestamp - self.window_sec
        while q and q[0][0] < cutoff:
            q.popleft()

    def get_stats(self, track_id, current_time):
        q = self.records[track_id]
        if not q:
            return {
                "observed_sec": 0.0,
                "walk_pct": 50.0,
                "feed_pct": 25.0,
                "drink_pct": 5.0,
                "rest_pct": 20.0,
                "avg_motion": 2.5,
                "isolation_pct": 0.0,
                "continuous_inactivity_sec": 0.0,
                "sample_count": 0
            }

        total = len(q)
        observed_sec = max(0.1, current_time - q[0][0])
        walk_cnt = sum(1 for _, b, _, _, _ in q if b == "walk")
        feed_cnt = sum(1 for _, b, _, _, _ in q if b == "peck")
        drink_cnt = sum(1 for _, b, _, _, _ in q if b == "drink")
        rest_cnt = sum(1 for _, b, _, _, _ in q if b in ("stand", "sit", "rest", "sleep"))
        iso_cnt = sum(1 for _, _, _, iso, _ in q if iso)
        avg_motion = float(np.mean([m for _, _, m, _, _ in q]))

        # Calculate continuous inactivity duration from recent samples
        inact_sec = 0.0
        for ts, _, m, _, _ in reversed(q):
            if m < 1.6:
                inact_sec = current_time - ts
            else:
                break

        stats = {
            "observed_sec": round(observed_sec, 1),
            "walk_pct": round((walk_cnt / total) * 100.0, 1),
            "feed_pct": round((feed_cnt / total) * 100.0, 1),
            "drink_pct": round((drink_cnt / total) * 100.0, 1),
            "rest_pct": round((rest_cnt / total) * 100.0, 1),
            "avg_motion": round(avg_motion, 2),
            "isolation_pct": round((iso_cnt / total) * 100.0, 1),
            "continuous_inactivity_sec": round(inact_sec, 1),
            "sample_count": total
        }

        # Initialize rolling baseline once chicken has been observed for >= 10 seconds
        if observed_sec >= 10.0 and track_id not in self.baselines:
            self.baselines[track_id] = {
                "walk_pct": max(20.0, stats["walk_pct"]),
                "feed_pct": max(15.0, stats["feed_pct"]),
                "drink_pct": max(5.0, stats["drink_pct"]),
                "rest_pct": min(40.0, stats["rest_pct"]),
                "avg_motion": max(1.5, stats["avg_motion"])
            }

        return stats

    def get_baseline(self, track_id):
        return self.baselines.get(track_id, {
            "walk_pct": 40.0,
            "feed_pct": 25.0,
            "drink_pct": 8.0,
            "rest_pct": 27.0,
            "avg_motion": 2.2
        })


class AnomalyDetectionEngine:
    """
    Computes 5-component weighted Anomaly Score (0–100) per chicken:
      - Activity anomaly: 35%
      - Feeding anomaly:  25%
      - Movement anomaly: 20%
      - Isolation:        10%
      - Posture anomaly:  10%

    Tiers:
      0–29:   Normal
      30–59:  Monitor
      60–79:  Suspicious  (Triggers Gemini 2.5 Escalation)
      80–100: High Concern (Immediate Critical Alert)
    """
    @staticmethod
    def compute_anomaly(stats, baseline, unhealthy_prob, is_isolated, stationary_sec):
        # 1. Activity sub-score (0-100, weight 0.35)
        if stationary_sec >= 12.0:
            act_score = 100.0
        elif stationary_sec >= 6.0:
            act_score = 65.0 + (stationary_sec - 6.0) * 5.8
        elif stats["rest_pct"] > 75.0:
            act_score = min(85.0, stats["rest_pct"])
        elif stats["walk_pct"] < 15.0 and stats["observed_sec"] >= 10.0:
            act_score = 45.0
        else:
            base_walk = baseline.get("walk_pct", 40.0)
            act_score = max(0.0, (1.0 - (stats["walk_pct"] / max(1.0, base_walk))) * 35.0)

        # 2. Feeding sub-score (0-100, weight 0.25)
        base_feed = baseline.get("feed_pct", 25.0)
        feed_drop = max(0.0, base_feed - stats["feed_pct"])
        feed_score = min(100.0, (feed_drop / max(1.0, base_feed)) * 100.0) if base_feed > 5.0 else 0.0
        if stats["feed_pct"] == 0.0 and stats["observed_sec"] >= 25.0:
            feed_score = max(feed_score, 65.0)

        # 3. Movement / Optical-flow sub-score (0-100, weight 0.20)
        if stats["avg_motion"] < 0.6:
            motion_score = 100.0
        elif stats["avg_motion"] < 1.2:
            motion_score = 65.0
        elif stats["avg_motion"] < 2.0:
            motion_score = 30.0
        else:
            motion_score = 0.0

        # 4. Spatial Isolation sub-score (0-100, weight 0.10)
        iso_score = 100.0 if (is_isolated and stats["isolation_pct"] >= 50.0) else (60.0 if is_isolated else min(100.0, stats["isolation_pct"]))

        # 5. Posture / Visual anomaly sub-score (0-100, weight 0.10)
        posture_score = min(100.0, unhealthy_prob * 100.0)

        # 5-Component Weighted Total
        raw_score = (
            0.35 * act_score +
            0.25 * feed_score +
            0.20 * motion_score +
            0.10 * iso_score +
            0.10 * posture_score
        )
        total_score = int(round(min(100.0, max(0.0, raw_score))))

        if total_score < 30:
            tier = "Normal"
            status = "Healthy"
        elif total_score < 60:
            tier = "Monitor"
            status = "Monitor"
        elif total_score < 80:
            tier = "Suspicious"
            status = "Needs Attention"
        else:
            tier = "High Concern"
            status = "High Concern"

        return {
            "anomaly_score": total_score,
            "anomaly_tier": tier,
            "status": status,
            "sub_scores": {
                "activity": round(act_score, 1),
                "feeding": round(feed_score, 1),
                "movement": round(motion_score, 1),
                "isolation": round(iso_score, 1),
                "posture": round(posture_score, 1)
            }
        }


class GeminiEscalationWorker:
    """
    Asynchronous, non-blocking high-level veterinary behavioral assessment agent.
    Escalates persistent anomalies (Score >= 60) to Google Gemini 2.5 Flash.
    """
    def __init__(self, cooldown_sec=300.0):
        self.cooldown_sec = cooldown_sec
        self.last_escalated = {}    # track_id -> timestamp
        self.active_alerts = {}     # track_id -> alert dict
        self.chicken_summaries = {} # track_id -> 1-sentence gemini interpretation
        self.api_key = self._find_api_key()
        self.client = None
        self._init_client()

    def _find_api_key(self):
        # 1. Environment variable
        key = os.environ.get("GEMINI_API_KEY", "").strip()
        if key:
            return key
        # 2. Local config json
        cfg_path = os.path.join(SCRIPT_DIR, "gemini_config.json")
        if os.path.exists(cfg_path):
            try:
                with open(cfg_path, "r") as f:
                    data = json.load(f)
                    k = data.get("gemini_api_key", "").strip()
                    if k and k != "YOUR_GEMINI_API_KEY_HERE":
                        return k
            except Exception:
                pass
        # 3. Local .env file
        env_path = os.path.join(SCRIPT_DIR, ".env")
        if os.path.exists(env_path):
            try:
                with open(env_path, "r") as f:
                    for line in f:
                        if line.strip().startswith("GEMINI_API_KEY="):
                            k = line.strip().split("=", 1)[1].strip().strip('"').strip("'")
                            if k:
                                return k
            except Exception:
                pass
        return ""

    def _init_client(self):
        if self.api_key and HAS_GEMINI:
            try:
                self.client = genai.Client(api_key=self.api_key)
                print("[Gemini 2.5] Initialized Gemini 2.5 Flash client successfully.")
            except Exception as e:
                print(f"[Gemini 2.5] Client init notice: {e}")
        else:
            if not self.api_key:
                print("[Gemini 2.5] Notice: GEMINI_API_KEY not found. Operating in local heuristic anomaly mode.")
                print("             Add key to poultrysense_ai/gemini_config.json to enable Gemini 2.5 Flash.")

    def trigger_escalation_async(self, track_id, stats, baseline, anomaly_info, crop, curr_time):
        last_t = self.last_escalated.get(track_id, 0.0)
        if curr_time - last_t < self.cooldown_sec:
            return  # In cooldown window to prevent repeated API calls

        self.last_escalated[track_id] = curr_time
        threading.Thread(
            target=self._worker_task,
            args=(track_id, stats, baseline, anomaly_info, crop.copy() if crop is not None else None, curr_time),
            daemon=True
        ).start()

    def _worker_task(self, track_id, stats, baseline, anomaly_info, crop, curr_time):
        score = anomaly_info["anomaly_score"]
        tier = anomaly_info["anomaly_tier"]
        sub = anomaly_info["sub_scores"]

        # Default local heuristic fallback alert (guaranteed instantaneous reliability)
        severity = "High" if score >= 80 else "Moderate"
        reason = f"Activity deficit ({stats['continuous_inactivity_sec']:.0f}s immobile) with {stats['isolation_pct']:.0f}% flock isolation"
        recommendation = f"Continue monitoring and physically inspect Chicken #{track_id} if inactivity persists."
        gemini_evaluated = False

        if self.client is not None and crop is not None and crop.size > 0:
            try:
                # Encode crop to JPEG base64
                _, buf = cv2.imencode('.jpg', crop, [cv2.IMWRITE_JPEG_QUALITY, 80])
                img_bytes = buf.tobytes()

                prompt = (
                    f"You are PoultrySense AI's high-level veterinary behavioral assessment model.\n"
                    f"Analyze the following evidence for Chicken #{track_id}:\n"
                    f"- Observation Window: {stats['observed_sec']:.1f}s\n"
                    f"- Behavior Distribution: Walking: {stats['walk_pct']}%, Feeding: {stats['feed_pct']}%, Resting: {stats['rest_pct']}%\n"
                    f"- Continuous Inactivity: {stats['continuous_inactivity_sec']:.1f}s\n"
                    f"- Movement (Optical Flow): {stats['avg_motion']} (Normal: >= 2.0, Lethargic: < 0.8)\n"
                    f"- Flock Isolation: {stats['isolation_pct']}% time isolated\n"
                    f"- Anomaly Score: {score}/100 ({tier})\n"
                    f"- Sub-Scores: Activity={sub['activity']}, Feeding={sub['feeding']}, Movement={sub['movement']}, Isolation={sub['isolation']}, Posture={sub['posture']}\n\n"
                    f"CRITICAL ETHICAL & RELIABILITY RULES:\n"
                    f"1. Strictly poultry welfare and behavioral assessment.\n"
                    f"2. DO NOT state a definitive disease diagnosis from vision alone.\n"
                    f"3. Return concise JSON only with keys:\n"
                    f"   {{\"status\": \"Needs Attention\" or \"High Concern\", \"severity\": \"Moderate\" or \"Severe\", \"reason\": \"<1 sentence explanation>\", \"recommendation\": \"<farmer action>\"}}"
                )

                response = self.client.models.generate_content(
                    model='gemini-2.5-flash',
                    contents=[
                        types.Part.from_bytes(data=img_bytes, mime_type='image/jpeg'),
                        prompt
                    ]
                )

                resp_text = response.text.strip()
                # Strip markdown codeblocks if returned
                if resp_text.startswith("```"):
                    resp_text = resp_text.split("```")[1]
                    if resp_text.startswith("json"):
                        resp_text = resp_text[4:]
                resp_text = resp_text.strip()

                parsed = json.loads(resp_text)
                if isinstance(parsed, dict):
                    reason = parsed.get("reason", reason)
                    recommendation = parsed.get("recommendation", recommendation)
                    severity = parsed.get("severity", severity)
                    gemini_evaluated = True
                    print(f"[Gemini 2.5] Evaluated Chicken #{track_id}: {reason}")
            except Exception as ex:
                print(f"[Gemini 2.5] API call notice for Chicken #{track_id}: {ex}")

        # Store active alert
        alert_obj = {
            "chicken_id": track_id,
            "status": anomaly_info["status"],
            "anomaly_score": score,
            "anomaly_tier": tier,
            "behavior": f"Resting {stats['rest_pct']}% • Walking {stats['walk_pct']}%",
            "duration_sec": stats["observed_sec"],
            "severity": severity,
            "reason": reason,
            "recommendation": recommendation,
            "gemini_evaluated": gemini_evaluated,
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
        }
        self.active_alerts[track_id] = alert_obj
        self.chicken_summaries[track_id] = reason

    def get_active_alerts(self):
        return list(self.active_alerts.values())

    def get_chicken_summary(self, track_id):
        return self.chicken_summaries.get(track_id, "")


# Global Multi-Stage Architecture Instances
behavior_history = ChickenBehaviorHistory(window_sec=180.0)
anomaly_engine = AnomalyDetectionEngine()
gemini_escalator = GeminiEscalationWorker(cooldown_sec=300.0)



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
        info = inactive_tracks.pop(best_match_id, {})
        track_display_ids[raw_id] = best_match_id
        track_missed_counts[raw_id] = 0
        if cx is not None and cy is not None:
            track_last_positions[raw_id] = (cx, cy, curr_time)
        if best_match_id not in track_first_seen:
            track_first_seen[best_match_id] = info.get("first_seen", curr_time - OBSERVATION_WINDOW)
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
    if cand not in track_first_seen:
        track_first_seen[cand] = curr_time
        track_stationary_duration[cand] = 0.0
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
                        "time": curr_time,
                        "first_seen": track_first_seen.get(disp_id, curr_time)
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
            if disp_id in track_motion_windows:
                del track_motion_windows[disp_id]

            if disp_id in track_first_seen:
                del track_first_seen[disp_id]
            if disp_id in track_stationary_duration:
                del track_stationary_duration[disp_id]
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


def draw_hud_box(img, pt1, pt2, color, thickness=2, line_len=16):
    x1, y1 = max(0, int(pt1[0])), max(0, int(pt1[1]))
    x2, y2 = min(img.shape[1], int(pt2[0])), min(img.shape[0], int(pt2[1]))

    # Clean corner-only brackets (no full rectangle, no tint)
    cv2.line(img, (x1, y1), (x1 + line_len, y1), color, thickness)
    cv2.line(img, (x1, y1), (x1, y1 + line_len), color, thickness)
    cv2.line(img, (x2, y1), (x2 - line_len, y1), color, thickness)
    cv2.line(img, (x2, y1), (x2, y1 + line_len), color, thickness)
    cv2.line(img, (x1, y2), (x1 + line_len, y2), color, thickness)
    cv2.line(img, (x1, y2), (x1, y2 - line_len), color, thickness)
    cv2.line(img, (x2, y2), (x2 - line_len, y2), color, thickness)
    cv2.line(img, (x2, y2), (x2, y2 - line_len), color, thickness)




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


@flask_app.route('/api/alerts')
def api_alerts():
    """Returns active chicken health alerts generated by the anomaly engine and Gemini 2.5."""
    with frame_lock:
        return jsonify({
            "status": "success",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "alerts": gemini_escalator.get_active_alerts()
        })


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
                    latest_sensor_data["last_updated"] = time.time()
                print(f"[ESP32] Live Sensor: Temp={latest_sensor_data['temperature']:.1f}°C, "
                      f"Hum={latest_sensor_data['humidity']:.1f}%")
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
    track_display_ids.clear()
    track_missed_counts.clear()
    track_histories.clear()
    track_first_seen.clear()
    track_stationary_duration.clear()
    track_classifications.clear()
    track_last_positions.clear()
    track_motion_windows.clear()
    inactive_tracks.clear()
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
        dt = max(0.001, min(0.1, curr_time - prev_time))
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
        observing_count = 0

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

            # Record rolling temporal motion history
            track_motion_windows[display_id].append(motion)

            # Track continuous stationary duration (seconds with motion < 1.6)
            if motion < 1.6:
                track_stationary_duration[display_id] = track_stationary_duration.get(display_id, 0.0) + dt
            else:
                track_stationary_duration[display_id] = max(0.0, track_stationary_duration.get(display_id, 0.0) - (dt * 2.0))

            first_seen = track_first_seen.get(display_id, curr_time)
            elapsed_obs = curr_time - first_seen
            stationary_sec = track_stationary_duration.get(display_id, 0.0)

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

            # 1. Update ChickenBehaviorHistory
            cx = int((x1 + x2) / 2)
            cy = int((y1 + y2) / 2)
            behavior_history.record(display_id, curr_time, behavior_label, motion, is_isolated, (cx, cy))
            stats = behavior_history.get_stats(display_id, curr_time)
            baseline = behavior_history.get_baseline(display_id)

            # 2. Calibration vs 5-Component Weighted Anomaly Evaluation
            if elapsed_obs < OBSERVATION_WINDOW:
                health_label = "Calibrating"
                anomaly_score = 0
                anomaly_tier = "Normal"
                gemini_summary = ""
                health_detail = f"Baseline Calibration {elapsed_obs:.1f}/{OBSERVATION_WINDOW:.0f}s"
                color = (255, 215, 0)  # Gold — neutral calibration phase
                observing_count += 1
            else:
                # 3. Compute 5-Component Weighted Anomaly Score
                anomaly_info = anomaly_engine.compute_anomaly(
                    stats=stats,
                    baseline=baseline,
                    unhealthy_prob=unhealthy_prob,
                    is_isolated=is_isolated,
                    stationary_sec=stationary_sec
                )
                anomaly_score = anomaly_info["anomaly_score"]
                anomaly_tier = anomaly_info["anomaly_tier"]

                # 4. Map to 4-tier decision hierarchy
                if anomaly_tier == "Normal":
                    health_label = "Healthy"
                    if behavior_label == "walk":
                        health_detail = "Normal Locomotion (Gait Score 0-1)"
                    elif behavior_label == "peck":
                        health_detail = "Active Foraging Behavior"
                    else:
                        health_detail = "Adequate Movement Detected"
                    color = get_id_color(display_id)
                    healthy_count += 1
                elif anomaly_tier == "Monitor":
                    health_label = "Monitor"
                    if is_isolated:
                        health_detail = "Flock Edge / Moderate Separation"
                    elif stationary_sec >= 3.0:
                        health_detail = f"Stationary Pause ({stationary_sec:.0f}s)"
                    else:
                        health_detail = "Mild Activity Deviation"
                    color = (0, 215, 255)  # Amber-Yellow
                    observing_count += 1
                elif anomaly_tier == "Suspicious":
                    health_label = "Needs Attention"
                    if is_isolated and stats["isolation_pct"] >= 50.0:
                        health_detail = "Persistent Isolation (Febrer 2006)"
                    elif stationary_sec >= 5.0:
                        health_detail = f"Reduced Locomotion ({stationary_sec:.0f}s)"
                    else:
                        health_detail = f"Suspicious Activity ({anomaly_score}%)"
                    color = (0, 140, 255)  # Bright Amber-Orange
                    warning_count += 1
                    # Trigger asynchronous non-blocking Gemini 2.5 escalation
                    gemini_escalator.trigger_escalation_async(
                        track_id=display_id,
                        stats=stats,
                        baseline=baseline,
                        anomaly_info=anomaly_info,
                        crop=crop,
                        curr_time=curr_time
                    )
                else:  # High Concern (>= 80)
                    health_label = "High Concern"
                    health_detail = f"Severe Lethargy Marker ({stationary_sec:.0f}s immobile)"
                    color = (70, 70, 255)  # Vivid Alert Red
                    unhealthy_count += 1
                    # Trigger asynchronous non-blocking Gemini 2.5 escalation
                    gemini_escalator.trigger_escalation_async(
                        track_id=display_id,
                        stats=stats,
                        baseline=baseline,
                        anomaly_info=anomaly_info,
                        crop=crop,
                        curr_time=curr_time
                    )

                # Check if Gemini 2.5 has returned an active assessment for this chicken
                gemini_summary = gemini_escalator.get_chicken_summary(display_id)
                if gemini_summary and anomaly_tier in ("Suspicious", "High Concern"):
                    health_detail = gemini_summary


            # Draw HUD Box
            draw_hud_box(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=16)

            # Clean minimal badge: "#3 NORMAL" or "#3 ALERT 72%"
            if elapsed_obs < OBSERVATION_WINDOW:
                badge_text = f"#{display_id}"
            elif anomaly_tier == "Normal":
                badge_text = f"#{display_id} {behavior_label.upper()}"
            elif anomaly_tier == "Monitor":
                badge_text = f"#{display_id} WATCH"
            else:
                badge_text = f"#{display_id} {anomaly_tier.upper()} {anomaly_score}%"

            font = cv2.FONT_HERSHEY_SIMPLEX
            font_scale = 0.45
            (text_w, text_h), baseline_y = cv2.getTextSize(badge_text, font, font_scale, 1)
            label_y = max(y1 - 6, text_h + 6)

            # Small semi-transparent pill background
            pad_x, pad_y = 6, 4
            bg_x1 = max(0, x1)
            bg_y1 = max(0, label_y - text_h - pad_y)
            bg_x2 = min(frame.shape[1], x1 + text_w + pad_x * 2)
            bg_y2 = min(frame.shape[0], label_y + pad_y)

            if bg_x2 > bg_x1 and bg_y2 > bg_y1:
                roi = frame[bg_y1:bg_y2, bg_x1:bg_x2]
                dark = (roi * 0.4).astype(np.uint8)
                frame[bg_y1:bg_y2, bg_x1:bg_x2] = dark
            cv2.putText(frame, badge_text, (x1 + pad_x, label_y - 1), font, font_scale, color, 1, cv2.LINE_AA)


            detection_list.append({
                "track_id": display_id,
                "class": "chicken",
                "confidence": round(conf, 2),
                "behavior": behavior_label,
                "health": health_label,
                "health_detail": health_detail,
                "anomaly_score": anomaly_score,
                "anomaly_tier": anomaly_tier,
                "gemini_summary": gemini_summary,

                "isolated": is_isolated,
                "motion": round(motion, 1),
                "observed_sec": round(elapsed_obs, 1),
                "stationary_sec": round(stationary_sec, 1),
                "bbox": [x1, y1, x2, y2]
            })

        flock_count = len(detection_list)

        # Dynamic Health Index
        if flock_count > 0:
            health_index = max(10, int(100 - (unhealthy_count * 25) - (warning_count * 10) - (isolated_count * 5)))
        else:
            health_index = 100


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
                "observing_count": observing_count,
                "healthy_count": healthy_count,
                "warning_count": warning_count,
                "unhealthy_count": unhealthy_count,
                "isolated_count": isolated_count,
                "health_index": health_index,
                "track_count": flock_count,
                "temperature": latest_sensor_data["temperature"],
                "humidity": latest_sensor_data["humidity"],
                "ammonia": latest_sensor_data["ammonia"],
                "detections": detection_list,
                "alerts": gemini_escalator.get_active_alerts()

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
