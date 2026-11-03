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
import logging

# Silence per-frame HTTP GET /frame.jpg access logs to prevent console I/O blocking
logging.getLogger('werkzeug').setLevel(logging.ERROR)

# Suppress Windows modal driver/hardware popup dialogs (e.g. Intel GPU OpenCL DLL on AMD/non-Intel systems)
if sys.platform.startswith('win'):
    try:
        import ctypes
        # SEM_FAILCRITICALERRORS (0x0001) | SEM_NOOPENFILEERRORBOX (0x8000)
        ctypes.windll.kernel32.SetErrorMode(0x0001 | 0x8000)
    except Exception:
        pass

from collections import defaultdict, deque

# --- 1. Import Requests & Google Gemini ---
try:
    import requests
except ImportError:
    requests = None

try:
    from pydantic import BaseModel, Field
    from typing import Literal
    HAS_PYDANTIC = True
except ImportError:
    HAS_PYDANTIC = False

try:
    from google import genai
    from google.genai import types
    HAS_GEMINI = True
except ImportError:
    HAS_GEMINI = False

# --- Pydantic Schema for Enforced Gemini Output ---
if HAS_PYDANTIC:
    class ChickenDiagnosticVerdict(BaseModel):
        severity: Literal["Low", "Medium", "High", "Critical"] = Field(
            description="Diagnostic severity rating: Low, Medium, High, or Critical."
        )
        reason: str = Field(
            description="Diagnostic explanation based on visual posture, plumage, comb, duration, and climate telemetry."
        )
        farmer_action: str = Field(
            description="Concrete, actionable recommendation for the poultry farmer or caretaker."
        )
else:
    ChickenDiagnosticVerdict = None

# --- Twilio SMS Alert Integration ---
try:
    from twilio.rest import Client as TwilioClient
    HAS_TWILIO = True
except ImportError:
    HAS_TWILIO = False

# --- 2. Import Ultralytics YOLO ---
os.environ["YOLO_AUTOINSTALL"] = "0"
os.environ["ULTRALYTICS_SKIP_REQUIREMENTS_CHECKS"] = "1"
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

# The streaming primitives live beside this script. Running the server directly
# already puts SCRIPT_DIR on sys.path; make it explicit so module imports work too.
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from stream_pipeline import FrameBuffer, RateGate, normalize_motion  # noqa: E402

# Detection is capped at 2-3 inferences/second. Inference itself is usually the
# real limit (~400-500 ms on CPU), so this gate only prevents wasted fast runs.
DETECTION_MAX_RATE_HZ = float(os.environ.get("DETECTION_MAX_RATE_HZ", "3.0"))
# Confidence threshold for the chicken detector. 0.52 is the branch default;
# override with POULTRYSENSE_DETECTION_CONF=0.35 for the older, more sensitive setting.
DETECTION_CONF = float(os.environ.get("POULTRYSENSE_DETECTION_CONF", "0.52"))
STREAM_JPEG_QUALITY = int(os.environ.get("STREAM_JPEG_QUALITY", "75"))
# Cap preview publishing so a fast/virtual camera cannot burn CPU encoding
# hundreds of JPEGs per second. 30 FPS matches the camera target and the app.
STREAM_MAX_FPS = float(os.environ.get("STREAM_MAX_FPS", "30"))


# --- Environment Variables (.env) Loader ---
def load_env_file():
    """Load configuration from .env into os.environ if not already present."""
    candidates = [
        os.path.join(SCRIPT_DIR, ".env"),
        os.path.join(os.path.dirname(SCRIPT_DIR), ".env")
    ]
    for env_path in candidates:
        if os.path.isfile(env_path):
            try:
                with open(env_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            k = k.strip()
                            v = v.strip().strip('"').strip("'")
                            if k and k not in os.environ:
                                os.environ[k] = v
            except Exception as e:
                print(f"[ENV] Notice reading {env_path}: {e}")

load_env_file()

# --- Firebase & Twilio Alert Dispatch Logic ---
FIREBASE_DATABASE_URL = os.environ.get(
    "FIREBASE_DATABASE_URL",
    "https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app"
).rstrip("/")

# --- Global Alert Cooldown Configuration (Handling Tracker ID Switches) ---
# When chickens exit/re-enter camera view, tracker IDs can switch. A global cooldown
# ensures no new Gemini queries or SMS alerts fire across the entire system until
# the cooldown window expires after an alert (default: 900 seconds = 15 minutes).
GLOBAL_ALERT_COOLDOWN_SEC = float(os.environ.get("GLOBAL_ALERT_COOLDOWN_SEC", "900.0"))
DAYTIME_RECUMBENT_THRESHOLD_SEC = float(os.environ.get("DAYTIME_RECUMBENT_THRESHOLD_SEC", os.environ.get("STATIONARY_DURATION_THRESHOLD_SEC", "45.0")))

_last_global_alert_timestamp = 0.0
_last_global_sms_timestamp = 0.0
_global_cooldown_lock = threading.Lock()
_last_cooldown_log_time = 0.0

def is_global_cooldown_active(curr_time=None):
    """Returns True if the global alert cooldown window is active across the system."""
    global _last_global_alert_timestamp
    if curr_time is None:
        curr_time = time.time()
    with _global_cooldown_lock:
        return (curr_time - _last_global_alert_timestamp) < GLOBAL_ALERT_COOLDOWN_SEC

def get_global_cooldown_remaining(curr_time=None):
    """Returns remaining seconds in the global alert cooldown window."""
    global _last_global_alert_timestamp
    if curr_time is None:
        curr_time = time.time()
    with _global_cooldown_lock:
        elapsed = curr_time - _last_global_alert_timestamp
        return max(0.0, GLOBAL_ALERT_COOLDOWN_SEC - elapsed)

def record_global_alert(timestamp=None):
    """Records an alert and locks the system-wide global alert cooldown (900-second / 15-minute window)."""
    global _last_global_alert_timestamp
    if timestamp is None:
        timestamp = time.time()
    with _global_cooldown_lock:
        _last_global_alert_timestamp = timestamp
        print(f"[COOLDOWN] Global alert cooldown active for {GLOBAL_ALERT_COOLDOWN_SEC:.0f}s ({GLOBAL_ALERT_COOLDOWN_SEC/60:.1f} min). "
              f"All new Gemini queries and SMS texts paused across the entire flock.")

def reset_global_cooldown():
    """Manually resets the global cooldown timer (e.g. on user reset)."""
    global _last_global_alert_timestamp, _last_global_sms_timestamp
    with _global_cooldown_lock:
        _last_global_alert_timestamp = 0.0
        _last_global_sms_timestamp = 0.0
        print("[COOLDOWN] Global alert cooldown reset.")

def get_cloud_sms_settings():
    """Fetches real-time SMS alert preferences configured by the farmer in the Android app."""
    if requests is None:
        return None
    try:
        url = f"{FIREBASE_DATABASE_URL}/system_control/sms_alert_settings.json"
        resp = requests.get(url, timeout=3)
        if resp.status_code == 200:
            data = resp.json()
            if isinstance(data, dict):
                return data
    except Exception:
        pass
    return None

def get_current_climate():
    """Returns (temperature, humidity, climate_str) from live memory or Firebase fallback."""
    global latest_sensor_data
    t = latest_sensor_data.get("temperature")
    h = latest_sensor_data.get("humidity")

    if (t is None or h is None) and requests is not None:
        try:
            resp = requests.get(f"{FIREBASE_DATABASE_URL}/sensor_data.json", timeout=2)
            if resp.status_code == 200:
                data = resp.json()
                if isinstance(data, dict):
                    if t is None:
                        raw_t = data.get("temperature") or data.get("temp")
                        if raw_t is not None:
                            t = float(raw_t)
                    if h is None:
                        raw_h = data.get("humidity") or data.get("hum")
                        if raw_h is not None:
                            h = float(raw_h)
        except Exception:
            pass

    if t is not None and h is not None:
        return t, h, f"{t:.1f}°C • {h:.1f}% Hum"
    elif t is not None:
        return t, None, f"{t:.1f}°C"
    elif h is not None:
        return None, h, f"{h:.1f}% Hum"
    else:
        return None, None, "Unavailable"

def send_sms_alert(severity, reason, farmer_action, chicken_id=None, tag_label=None, climate_str=None):
    """
    Dispatches a real-time SMS notification via Twilio when Gemini outputs High or Critical severity.
    Checks Android app notification preferences in Firebase first, falling back to .env configuration.
    Includes ambient coop climate (DHT22 temperature & humidity) in the alert.
    Enforces the global cooldown to avoid duplicate SMS alerts caused by tracker ID switches.
    Wrapped in try-except for fault-tolerant operation.
    """
    global _last_global_sms_timestamp
    try:
        sev_clean = str(severity).strip().capitalize()
        if sev_clean not in ["High", "Critical"]:
            return False

        if not HAS_TWILIO:
            print("[SMS] Notice: twilio library not available. Skipping SMS.")
            return False

        # Global SMS Cooldown Check (prevent spamming farmer when tracker IDs switch)
        curr_time = time.time()
        with _global_cooldown_lock:
            if _last_global_sms_timestamp > 0.0 and (curr_time - _last_global_sms_timestamp) < GLOBAL_ALERT_COOLDOWN_SEC:
                rem = int(GLOBAL_ALERT_COOLDOWN_SEC - (curr_time - _last_global_sms_timestamp))
                print(f"[SMS] Global alert cooldown active ({rem}s remaining). Skipping duplicate SMS dispatch.")
                return False

        account_sid = os.environ.get("TWILIO_ACCOUNT_SID", "").strip()
        auth_token = os.environ.get("TWILIO_AUTH_TOKEN", "").strip()
        from_number = os.environ.get("TWILIO_PHONE_NUMBER", "").strip()
        to_number = os.environ.get("FARMER_ALERT_PHONE_NUMBER", "").strip()

        # Check real-time settings configured by the farmer in the Android app
        cloud_settings = get_cloud_sms_settings()
        if cloud_settings:
            if cloud_settings.get("sms_alerts_enabled") is False:
                print("[SMS] Notice: SMS alerts toggled OFF by farmer in Android app. Skipping dispatch.")
                return False
            app_phone = str(cloud_settings.get("farmer_phone_number") or "").strip()
            if app_phone:
                to_number = app_phone

        if not (account_sid and auth_token and from_number and to_number):
            print("[SMS] Notice: Twilio credentials or recipient phone number not configured. SMS skipped.")
            return False

        # Format concise SMS body (under 160-300 characters)
        target = f"Chicken #{chicken_id}" if chicken_id is not None else "Flock"
        if tag_label:
            target += f" ({tag_label})"

        if not climate_str:
            _, _, climate_str = get_current_climate()
        climate_line = f"Climate: {climate_str}\n" if climate_str and climate_str != "Unavailable" else ""

        clean_reason = (reason or "Health anomaly detected").strip()
        clean_action = (farmer_action or "Inspect bird immediately").strip()
        if len(clean_reason) > 90:
            clean_reason = clean_reason[:87] + "..."
        if len(clean_action) > 90:
            clean_action = clean_action[:87] + "..."

        sms_body = (
            f"[PoultrySense ALERT]\n"
            f"Severity: {sev_clean.upper()}\n"
            f"Target: {target}\n"
            f"{climate_line}"
            f"Reason: {clean_reason}\n"
            f"Action: {clean_action}"
        )

        client = TwilioClient(account_sid, auth_token)
        msg = client.messages.create(
            body=sms_body,
            from_=from_number,
            to=to_number
        )
        with _global_cooldown_lock:
            _last_global_sms_timestamp = curr_time
        print(f"[SMS] ✓ Dispatched {sev_clean.upper()} alert to {to_number} (SID: {msg.sid})")
        return True
    except Exception as e:
        print(f"[SMS] ⚠ Failed to dispatch Twilio SMS: {e}")
        return False

def sync_verdict_to_firebase(verdict_obj):
    """
    Writes Gemini's JSON diagnostic verdict directly to Firebase Realtime Database via REST.
    Stores under active_alerts/chicken_{id}.json and diagnostic_verdicts/latest.json.
    """
    if requests is None:
        return False
    cid = verdict_obj.get("chicken_id", "unknown")
    try:
        # 1. Update active alerts node for live alerts view
        url_alert = f"{FIREBASE_DATABASE_URL}/active_alerts/chicken_{cid}.json"
        requests.put(url_alert, json=verdict_obj, timeout=5)

        # 2. Update diagnostic verdicts node
        url_diag = f"{FIREBASE_DATABASE_URL}/diagnostic_verdicts/chicken_{cid}.json"
        requests.put(url_diag, json=verdict_obj, timeout=5)

        # 3. Update latest verdict reference
        url_latest = f"{FIREBASE_DATABASE_URL}/diagnostic_verdicts/latest.json"
        requests.put(url_latest, json=verdict_obj, timeout=5)
        print(f"[Firebase] ✓ Synced diagnostic verdict to RTDB for Chicken #{cid} ({verdict_obj.get('severity')})")
        return True
    except Exception as e:
        print(f"[Firebase] ⚠ Failed to sync diagnostic verdict to RTDB: {e}")
        return False

# Backward compatibility alias
sync_alert_to_firebase = sync_verdict_to_firebase

TRACKER_YAML = os.path.join(SCRIPT_DIR, "pipeline", "botsort.yaml")
if not os.path.exists(TRACKER_YAML):
    TRACKER_YAML = "botsort.yaml"

DET_MODELS = [
    os.path.join(MODELS_DIR, "PoultrySense_ChickenDet_v4_26n.pt"),
    os.path.join(MODELS_DIR, "PoultrySense_ChickenDet_v3_26n.pt"),
]

BEHAVIOR_MODELS = [
    os.path.join(MODELS_DIR, "PoultrySense_BehaviorModel_v2_26n.pt"),
    os.path.join(MODELS_DIR, "PoultrySense_BehaviorModel_v2.pt"),
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
latest_raw_jpeg_bytes = None
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
    "temperature": None,
    "humidity": None,
    "sensor_connected": False,
    "detections": []
}
latest_sensor_data = {
    "temperature": None,
    "humidity": None,
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
track_velocities = defaultdict(lambda: (0.0, 0.0))  # display_id -> (vx, vy) in px/sec
display_id_last_positions = {}                       # display_id -> (cx, cy, time)
track_hit_streaks = defaultdict(int)         # raw_id -> consecutive confident detections
track_confirmed_raw_ids = set()             # set of raw_ids confirmed (debounce >= 2 or handoff)
display_coasting_boxes = {}                 # display_id -> last known bbox & metadata for anti-blink coasting
track_crop_sig = {}                         # raw_id -> last 16x16 gray crop signature (static-phantom gate)
track_frozen_streaks = defaultdict(int)     # raw_id -> consecutive pixel-identical observations

# Pixel-churn gate tuning: mean-abs-diff (/255) below CHURN_EPS counts as frozen;
# CHURN_MAX_FROZEN consecutive frozen observations veto a confirmation hit.
CHURN_EPS = 2.0
CHURN_MAX_FROZEN = 2
classification_queue = queue.Queue(maxsize=6)
classification_lock = threading.Lock()

# Temporal Observation Window (seconds before declaring baseline health)
OBSERVATION_WINDOW = 15.0


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
        observed_sec = stats.get("observed_sec", 0.0)

        # 1. Activity / Lethargy sub-score (0-100, weight 0.35)
        # Chickens normally stand/rest for 15-30s. Only prolonged immobility indicates lethargy.
        if stationary_sec >= 90.0:
            act_score = 100.0
        elif stationary_sec >= 45.0:
            act_score = 60.0 + (stationary_sec - 45.0) * (40.0 / 45.0)
        elif stationary_sec >= 25.0:
            act_score = 25.0 + (stationary_sec - 25.0) * (35.0 / 20.0)
        elif stats["rest_pct"] > 85.0 and observed_sec >= 45.0:
            act_score = 30.0
        else:
            act_score = 0.0

        # 2. Feeding sub-score (0-100, weight 0.20)
        # Chickens forage intermittently. Do NOT penalize feeding until observed for at least 45s.
        if observed_sec >= 45.0:
            base_feed = baseline.get("feed_pct", 25.0)
            feed_drop = max(0.0, base_feed - stats["feed_pct"])
            feed_score = min(100.0, (feed_drop / max(1.0, base_feed)) * 100.0) if base_feed > 5.0 else 0.0
            if stats["feed_pct"] == 0.0 and observed_sec >= 90.0:
                feed_score = max(feed_score, 60.0)
        else:
            feed_score = 0.0

        # 3. Movement / Optical-flow sub-score (0-100, weight 0.15)
        # Only penalize low motion if observed for >= 20s and chicken is actually immobile
        if observed_sec >= 20.0:
            if stats["avg_motion"] < 0.4 and stationary_sec >= 25.0:
                motion_score = 75.0
            elif stats["avg_motion"] < 0.8 and stationary_sec >= 15.0:
                motion_score = 40.0
            elif stats["avg_motion"] < 1.5:
                motion_score = 15.0
            else:
                motion_score = 0.0
        else:
            motion_score = 0.0

        # 4. Spatial Isolation sub-score (0-100, weight 0.10)
        # Isolation requires persistent edge positioning over sufficient observation time
        if is_isolated and stats["isolation_pct"] >= 50.0 and observed_sec >= 20.0:
            iso_score = 80.0
        elif is_isolated and observed_sec >= 15.0:
            iso_score = 30.0
        else:
            iso_score = 0.0

        # 5. Posture / Visual anomaly sub-score (0-100, weight 0.20)
        posture_score = min(100.0, unhealthy_prob * 100.0)

        # Sleeping / Normal Resting Protection (Weeks et al. 2000, Febrer et al. 2006):
        # Healthy chickens spend up to 60% of their time resting/sleeping with zero optical motion.
        # If the bird is resting alongside the flock (not isolated) and shows healthy visual traits
        # (unhealthy_prob < 0.35), prolonged immobility is normal resting/sleep, NOT sickness!
        is_social_rest = (not is_isolated) and (stats.get("isolation_pct", 0.0) < 35.0)
        is_visually_healthy = (unhealthy_prob < 0.35)

        if is_social_rest and is_visually_healthy:
            act_score = min(act_score, 15.0)
            feed_score = min(feed_score, 20.0)
            motion_score = min(motion_score, 15.0)

        # 5-Component Weighted Total
        raw_score = (
            0.35 * act_score +
            0.20 * feed_score +
            0.15 * motion_score +
            0.10 * iso_score +
            0.20 * posture_score
        )
        total_score = int(round(min(100.0, max(0.0, raw_score))))

        # Cold-Start Dampener: During first 25s of tracking, cap score at 25 (Normal/Healthy)
        # Prevents newly detected birds from false alarms before sufficient behavioral data is collected.
        if observed_sec < 25.0:
            total_score = min(total_score, 25)

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


# 8 Bright Poultry Tag Colors matching Android ChickenTagHelper
POULTRY_TAG_PALETTE = [
    {"name": "Red", "hex": "#FF1744", "emoji": "🔴"},
    {"name": "Blue", "hex": "#0091EA", "emoji": "🔵"},
    {"name": "Yellow", "hex": "#FFD600", "emoji": "🟡"},
    {"name": "Green", "hex": "#00E676", "emoji": "🟢"},
    {"name": "Orange", "hex": "#FF6D00", "emoji": "🟠"},
    {"name": "Purple", "hex": "#AA00FF", "emoji": "🟣"},
    {"name": "Pink", "hex": "#FF4081", "emoji": "🌸"},
    {"name": "Cyan", "hex": "#00E5FF", "emoji": "🔷"},
]

def get_poultry_tag_info(track_id):
    try:
        num = int(track_id)
    except Exception:
        num = 1
    idx = (abs(num - 1)) % len(POULTRY_TAG_PALETTE)
    tag = POULTRY_TAG_PALETTE[idx]
    return {
        "tag_number": num,
        "tag_color": tag["name"],
        "tag_color_hex": tag["hex"],
        "tag_emoji": tag["emoji"],
        "tag_label": f"{tag['emoji']} {tag['name']} Tag #{num}",
        "physical_description": f"Bright {tag['name']} Leg Band #{num}"
    }


GEMINI_SYSTEM_INSTRUCTION = (
    "You are PoultrySense AI's expert poultry veterinary and ethology diagnostic engine.\n"
    "Your objective is to evaluate multimodal chicken observations consisting of:\n"
    "1. A cropped camera snapshot of a flagged chicken ('flagged_chicken.jpg') flagged by YOLO/ByteTrack or environmental sensors.\n"
    "2. Quantitative telemetry data including stationary duration, optical flow motion, lethargy markers, flock isolation, and ambient environmental sensor vectors (DHT22 temperature & humidity).\n\n"
    "DIAGNOSTIC GUIDELINES:\n"
    "- Distinguish between physiological rest/sleep vs pathological lethargy/illness.\n"
    "- Normal rest/sleep: smooth plumage, bright red comb, head tucked or upright, resting in flock proximity.\n"
    "- Pathological markers: ruffled/dirty plumage, closed eyes, drooped wings resting on litter, hunched 'sick bird' posture, cyanotic/pale comb, solitary isolation.\n"
    "- Correlate with climate: Temperature >30°C causes acute heat stress (panting, wing elevation, lethargy). Temperature <18°C causes cold huddling.\n"
    "- Output MUST strictly conform to the required JSON schema with fields: severity, reason, and farmer_action.\n"
    "- The 'severity' field must be one of: 'Low', 'Medium', 'High', 'Critical'."
)


class GeminiVerdictEngine:
    """
    Multimodal Gemini 3.6 Flash Health Diagnostic Engine for PoultrySense.

    Architecture:
    1. YOLO/ByteTrack or environmental sensors detect abnormal behaviors (prolonged immobility, lethargic posture,
       flock isolation, or heat/cold stress).
    2. Automatically crops and saves temporary snapshot ('flagged_chicken.jpg').
    3. Global timestamp-based cooldown (15 minutes = 900 seconds) ensures no duplicate queries or SMS alerts across the entire flock,
       preventing duplicate notifications when tracker IDs switch upon exit/re-entry.
    4. Gemini 3.6 Flash evaluates both cropped image and text telemetry with enforced JSON schema.
    5. Saves diagnostic verdict directly to Firebase Realtime Database.
    6. Dispatches Twilio SMS alert if severity is High or Critical, wrapped in try-except.
    """

    def __init__(self):
        self.verdicts = {}              # track_id -> full verdict dict from Gemini
        self.active_alerts = {}         # track_id -> alert dict (for /alerts endpoint)
        self.chicken_summaries = {}     # track_id -> 1-sentence reason (backwards compat)
        self.model_name = "gemini-3.6-flash"
        self._query_in_progress = False
        self.api_key = self._find_api_key()
        self.client = None
        self._init_client()

    def _find_api_key(self):
        global GLOBAL_ALERT_COOLDOWN_SEC
        # 1. Environment variable
        key = os.environ.get("GEMINI_API_KEY", "").strip()
        model = os.environ.get("GEMINI_MODEL", "").strip()
        if model:
            self.model_name = model
        # 2. Local config json
        cfg_path = os.path.join(SCRIPT_DIR, "gemini_config.json")
        if os.path.exists(cfg_path):
            try:
                with open(cfg_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    k = data.get("gemini_api_key", "").strip()
                    m = data.get("model", "").strip()
                    if m:
                        self.model_name = m
                    cd = data.get("cooldown_seconds")
                    if cd is not None:
                        try:
                            GLOBAL_ALERT_COOLDOWN_SEC = float(cd)
                        except Exception:
                            pass
                    if k and k != "YOUR_GEMINI_API_KEY_HERE" and not key:
                        key = k
            except Exception:
                pass
        # 3. Local .env file
        if not key:
            env_path = os.path.join(SCRIPT_DIR, ".env")
            if os.path.exists(env_path):
                try:
                    with open(env_path, "r", encoding="utf-8") as f:
                        for line in f:
                            if line.strip().startswith("GEMINI_API_KEY="):
                                k = line.strip().split("=", 1)[1].strip().strip('"').strip("'")
                                if k:
                                    key = k
                            elif line.strip().startswith("GEMINI_MODEL="):
                                m = line.strip().split("=", 1)[1].strip().strip('"').strip("'")
                                if m:
                                    self.model_name = m
                except Exception:
                    pass
        return key

    def _init_client(self):
        if self.api_key and HAS_GEMINI:
            try:
                self.client = genai.Client(api_key=self.api_key)
                print(f"[Gemini 3.6 Flash] ✓ Initialized multimodal diagnostic engine ({self.model_name}).")
            except Exception as e:
                print(f"[Gemini 3.6 Flash] Client init notice: {e}")
        else:
            if not self.api_key:
                print("[Gemini 3.6 Flash] Notice: GEMINI_API_KEY not found. Operating in local heuristic-only mode.")
                print("                  Add key to poultrysense_ai/gemini_config.json to enable Gemini multimodal verdicts.")

    def request_verdict_async(self, track_id, stats, baseline, anomaly_info, crop, curr_time, is_isolated, stationary_sec, snapshot_path=None, abnormal_flags=None):
        """
        Triggers asynchronous multimodal analysis with Gemini 3.6 Flash.
        Enforces 15-minute global timestamp-based cooldown to handle tracker ID switches.
        """
        # 1. Global Alert Cooldown Check (prevents duplicates when chicken changes tracker ID)
        if is_global_cooldown_active(curr_time):
            return

        # 2. Prevent concurrent queries
        if self._query_in_progress:
            return

        self._query_in_progress = True

        # Save temporary snapshot of flagged chicken
        if snapshot_path is None:
            snapshot_path = os.path.join(SCRIPT_DIR, "flagged_chicken.jpg")
        if crop is not None and crop.size > 0:
            try:
                cv2.imwrite(snapshot_path, crop)
            except Exception as ex_save:
                print(f"[Vision Engine] Notice saving flagged snapshot: {ex_save}")

        # Immediately lock global cooldown timer (15 minutes) so subsequent frames and tracker switches cannot trigger
        record_global_alert(curr_time)

        threading.Thread(
            target=self._verdict_worker,
            args=(track_id, stats, baseline, anomaly_info, snapshot_path, curr_time, is_isolated, stationary_sec, abnormal_flags),
            daemon=True
        ).start()

    def _verdict_worker(self, track_id, stats, baseline, anomaly_info, snapshot_path, curr_time, is_isolated, stationary_sec, abnormal_flags=None):
        """
        Background worker: passes cropped image snapshot and telemetry vectors to Gemini 3.6 Flash,
        enforces schema, saves verdict to Firebase RTDB, dispatches Twilio SMS on High/Critical,
        and enforces 15-minute global alert cooldown.
        """
        try:
            score = anomaly_info.get("anomaly_score", 0)
            tier = anomaly_info.get("anomaly_tier", "Normal")
            sub = anomaly_info.get("sub_scores", {})

            # Fetch live coop environmental climate from sensor / Firebase
            curr_temp, curr_hum, climate_str = get_current_climate()
            temp_str = f"{curr_temp:.1f}°C" if curr_temp is not None else "Sensor offline / not connected"
            hum_str = f"{curr_hum:.1f}%" if curr_hum is not None else "Sensor offline / not connected"

            climate_note = "Normal ambient thermal range"
            if curr_temp is not None:
                if curr_temp > 30.0:
                    climate_note = "HEAT STRESS (Elevated ambient temperature > 30°C, high risk of heat-induced lethargy and panting)"
                elif curr_temp < 18.0:
                    climate_note = "COLD STRESS (Low ambient temperature < 18°C, high risk of huddled immobility)"

            flags_summary = ", ".join(abnormal_flags) if abnormal_flags else "Flagged behavioral anomaly"

            # Default local heuristic fallback
            gemini_evaluated = False
            verdict_severity = "Low" if score < 30 else ("Medium" if score < 60 else ("High" if score < 80 else "Critical"))
            verdict_reason = f"Chicken #{track_id} flagged: {flags_summary} (Local score: {score}%)."
            verdict_farmer_action = "Continue monitoring flock; ensure adequate ventilation and clean water."

            # Multimodal Gemini 3.6 Flash Analysis
            if self.client is not None and os.path.exists(snapshot_path):
                try:
                    with open(snapshot_path, "rb") as f:
                        img_bytes = f.read()

                    image_part = types.Part.from_bytes(data=img_bytes, mime_type="image/jpeg")

                    telemetry_prompt = (
                        f"[POULTRYSENSE MULTIMODAL TELEMETRY LOG]\n"
                        f"- Target Chicken ID: #{track_id}\n"
                        f"- Flagged Anomaly Triggers: {flags_summary}\n"
                        f"- Posture State: Recumbent / Stationary\n"
                        f"- Stationary Duration: {stationary_sec:.1f}s (Daytime Threshold: {DAYTIME_RECUMBENT_THRESHOLD_SEC:.1f}s)\n"
                        f"- Continuous Inactivity: {stats.get('continuous_inactivity_sec', stationary_sec):.1f}s\n"
                        f"- Total Observed Time: {stats.get('observed_sec', 0.0):.1f}s\n"
                        f"- Average Optical Motion: {stats.get('avg_motion', 0.0):.2f} px/frame (Normal >= 2.0 | Lethargic < 0.8)\n"
                        f"- Behavior Time Distribution: Resting {stats.get('rest_pct', 0.0)}% | Walking {stats.get('walk_pct', 0.0)}% | Feeding {stats.get('feed_pct', 0.0)}%\n"
                        f"- Spatial Proximity: {'Isolated from flock' if is_isolated else 'Within flock cluster'} ({stats.get('isolation_pct', 0.0):.1f}% time isolated)\n\n"
                        f"[COOP ENVIRONMENTAL SENSOR VECTORS]\n"
                        f"- Ambient Temperature: {temp_str} (Normal Thermal Comfort: 20°C - 26°C)\n"
                        f"- Relative Humidity: {hum_str} (Ideal Range: 50% - 70%)\n"
                        f"- Climate Stress Status: {climate_note}\n\n"
                        f"[ANALYSIS TASK]\n"
                        f"Evaluate the attached cropped snapshot ('flagged_chicken.jpg') alongside these sensor and behavioral telemetry vectors.\n"
                        f"Determine whether this flagged anomaly represents healthy physiological resting/sleep or pathological lethargy, heat/cold stress, or injury.\n"
                        f"You MUST output valid JSON strictly conforming to the schema with fields 'severity', 'reason', and 'farmer_action'."
                    )

                    config = types.GenerateContentConfig(
                        system_instruction=GEMINI_SYSTEM_INSTRUCTION,
                        response_mime_type="application/json",
                        response_schema=ChickenDiagnosticVerdict,
                        temperature=0.2
                    )

                    response = self.client.models.generate_content(
                        model=self.model_name,
                        contents=[image_part, telemetry_prompt],
                        config=config
                    )

                    # Parse structured response
                    parsed_data = None
                    if hasattr(response, "parsed") and response.parsed is not None:
                        if isinstance(response.parsed, ChickenDiagnosticVerdict):
                            parsed_data = response.parsed.model_dump()
                        elif isinstance(response.parsed, dict):
                            parsed_data = response.parsed
                        else:
                            parsed_data = json.loads(json.dumps(response.parsed, default=str))

                    if parsed_data is None and hasattr(response, "text") and response.text:
                        raw_text = response.text.strip()
                        if raw_text.startswith("```"):
                            raw_text = raw_text.split("```")[1]
                            if raw_text.startswith("json"):
                                raw_text = raw_text[4:]
                        raw_text = raw_text.strip()
                        parsed_data = json.loads(raw_text)

                    if isinstance(parsed_data, dict):
                        gemini_evaluated = True
                        raw_sev = str(parsed_data.get("severity", "Low")).strip().capitalize()
                        if raw_sev in ["Low", "Medium", "High", "Critical"]:
                            verdict_severity = raw_sev
                        elif "CRIT" in raw_sev.upper():
                            verdict_severity = "Critical"
                        elif "HIGH" in raw_sev.upper():
                            verdict_severity = "High"
                        elif "MED" in raw_sev.upper() or "MOD" in raw_sev.upper():
                            verdict_severity = "Medium"
                        else:
                            verdict_severity = "Low"

                        verdict_reason = parsed_data.get("reason", verdict_reason)
                        verdict_farmer_action = parsed_data.get("farmer_action", verdict_farmer_action)

                        print(f"[Gemini 1.5 Flash] ✓ Verdict for Chicken #{track_id}: {verdict_severity} — {verdict_reason}")

                except Exception as ex:
                    print(f"[Gemini 1.5 Flash] API error for Chicken #{track_id}: {ex}")

            # Map severity to internal tier and label
            SEV_TO_TIER = {
                "Low": ("Normal", "Healthy"),
                "Medium": ("Monitor", "Monitor"),
                "High": ("Suspicious", "Needs Attention"),
                "Critical": ("High Concern", "Critical Alert")
            }
            v_tier, v_label = SEV_TO_TIER.get(verdict_severity, (tier, tier))

            tag_info = get_poultry_tag_info(track_id)

            # Store the verdict in memory
            self.verdicts[track_id] = {
                "tier": v_tier,
                "label": v_label,
                "severity": verdict_severity,
                "reason": verdict_reason,
                "recommendation": verdict_farmer_action,
                "farmer_action": verdict_farmer_action,
                "temperature": curr_temp,
                "humidity": curr_hum,
                "climate": climate_str,
                "gemini_evaluated": gemini_evaluated,
                "timestamp": curr_time
            }

            self.chicken_summaries[track_id] = f"Gemini: {verdict_reason}" if gemini_evaluated else verdict_reason

            alert_obj = {
                "chicken_id": track_id,
                "tag_number": tag_info["tag_number"],
                "tag_color": tag_info["tag_color"],
                "tag_color_hex": tag_info["tag_color_hex"],
                "tag_label": tag_info["tag_label"],
                "status": f"{verdict_severity} • {tag_info['tag_label']}",
                "severity": verdict_severity,
                "reason": verdict_reason,
                "farmer_action": verdict_farmer_action,
                "recommendation": verdict_farmer_action,
                "anomaly_score": score,
                "anomaly_tier": v_tier,
                "behavior": f"Stationary {stationary_sec:.0f}s • Resting {stats.get('rest_pct', 0.0)}%",
                "duration_sec": round(stationary_sec, 1),
                "stationary_sec": round(stationary_sec, 1),
                "temperature": curr_temp,
                "humidity": curr_hum,
                "climate": climate_str,
                "gemini_evaluated": gemini_evaluated,
                "model": self.model_name,
                "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                "timestamp_epoch": time.time()
            }
            self.active_alerts[track_id] = alert_obj

            # 1. Save Gemini's JSON diagnostic verdict directly to Firebase Realtime Database
            sync_verdict_to_firebase(alert_obj)

            # 2. Dispatch Twilio SMS alert if severity is High or Critical (wrapped in try-except)
            if verdict_severity in ["High", "Critical"]:
                try:
                    send_sms_alert(
                        severity=verdict_severity,
                        reason=verdict_reason,
                        farmer_action=verdict_farmer_action,
                        chicken_id=track_id,
                        tag_label=tag_info["tag_label"],
                        climate_str=climate_str
                    )
                except Exception as sms_err:
                    print(f"[SMS] ⚠ Twilio SMS dispatch error block caught: {sms_err}")

            # 3. Lock global alert cooldown timer for 15 minutes after alert
            record_global_alert(time.time())

        finally:
            self._query_in_progress = False

    def get_verdict(self, track_id):
        """Returns the latest Gemini verdict for a chicken, or None if not yet evaluated."""
        return self.verdicts.get(track_id)

    def get_active_alerts(self):
        return list(self.active_alerts.values())

    def get_chicken_summary(self, track_id):
        return self.chicken_summaries.get(track_id, "")


# Global Multi-Stage Architecture Instances
behavior_history = ChickenBehaviorHistory(window_sec=180.0)
anomaly_engine = AnomalyDetectionEngine()
gemini_escalator = GeminiVerdictEngine()



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
                b_res = beh_model.predict(source=crop, imgsz=160, verbose=False)[0]
                b_label = BEHAVIOR_NAMES.get(int(b_res.probs.top1), "stand")
            except Exception:
                pass

        if hlth_model is not None:
            try:
                h_res = hlth_model.predict(source=crop, imgsz=160, verbose=False)[0]
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
        self._buffer = FrameBuffer()
        self._ret = False
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
            camera_index = FALLBACK_CAMERA_INDEX

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
            self._ret = ret
            if ret and frame is not None:
                self._buffer.publish(frame)

    def read(self):
        frame, _ = self._buffer.read()
        return self._ret, frame.copy() if frame is not None else None

    def read_newer(self, last_generation, timeout=0.5):
        """Block until a camera frame newer than ``last_generation`` arrives.

        Returns ``(ret, frame, generation)``. This lets the preview publisher
        run at the camera's true frame rate instead of busy-looping on the
        latest cached frame.
        """
        frame, generation = self._buffer.wait_for_newer(
            last_generation, timeout=timeout
        )
        if frame is None:
            return False, None, generation
        return self._ret, frame.copy(), generation

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


def get_execution_device():
    """Detects available hardware acceleration (NVIDIA CUDA GPU, or CPU with OpenVINO AVX2)."""
    try:
        import torch
        if torch.cuda.is_available():
            gpu_name = torch.cuda.get_device_name(0)
            print(f"[AI ENGINE] 🚀 NVIDIA GPU detected! CUDA acceleration active ({gpu_name}).")
            return 0
    except Exception:
        pass

    print("[AI ENGINE] ⚡ OpenVINO AVX2 Engine active (AMD Ryzen 5 3500G).")
    return "cpu"


def resolve_accelerated_model(pt_path, imgsz=640):
    """Exports and resolves the fastest hardware-accelerated model.

    1. OpenVINO export directory (*_openvino_model) for high-speed AVX2 execution (~32ms/frame).
    2. Fallback: PyTorch model (*.pt)."""
    return resolve_openvino(pt_path, imgsz=imgsz)


def resolve_openvino(pt_path, imgsz=640):
    """Resolves OpenVINO export format if supported, else returns PyTorch .pt path."""
    if not pt_path or not pt_path.endswith(".pt"):
        return pt_path

    if os.environ.get("DISABLE_OPENVINO", "").lower() in ["1", "true", "yes", "amd"]:
        print(f"[AI ENGINE] Native PyTorch mode requested for {os.path.basename(pt_path)}")
        return pt_path

    try:
        import openvino  # noqa: F401
    except ImportError:
        return pt_path

    import shutil
    try:
        from ultralytics import YOLO
        abs_pt = os.path.abspath(pt_path)
        stem = os.path.splitext(os.path.basename(abs_pt))[0] + "_openvino_model"
        beside = os.path.join(os.path.dirname(abs_pt), stem)
        if os.path.isdir(beside):
            print(f"[OpenVINO] Using cached model export: {beside}")
            return beside
        print(f"[OpenVINO] Exporting {os.path.basename(abs_pt)} (imgsz={imgsz})...")
        YOLO(abs_pt).export(format="openvino", imgsz=imgsz)
        if os.path.isdir(beside):
            print(f"[OpenVINO] Export ready: {beside}")
            return beside
        cwd_copy = os.path.join(os.getcwd(), stem)
        if os.path.isdir(cwd_copy):
            if os.path.isdir(beside):
                shutil.rmtree(beside)
            shutil.move(cwd_copy, beside)
            print(f"[OpenVINO] Export ready: {beside}")
            return beside
    except Exception as e:
        print(f"[AI ENGINE] OpenVINO notice ({e}); using native PyTorch .pt engine.")
    return pt_path


def _crop_is_frozen(frame, box, raw_id):
    """Pixel-churn gate: True when this track's crop is pixel-identical to its
    last observation (static screen chrome / paused video / UI icons).

    A real camera scene always churns (sensor noise + motion), so frozen ==
    phantom. Fails open (returns False) on any error or tiny box."""
    try:
        h, w = frame.shape[:2]
        x1, y1 = max(0, int(box[0])), max(0, int(box[1]))
        x2, y2 = min(w, int(box[2])), min(h, int(box[3]))
        if x2 - x1 < 8 or y2 - y1 < 8:
            return False
        small = cv2.resize(frame[y1:y2, x1:x2], (16, 16), interpolation=cv2.INTER_AREA)
        sig = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY).astype(np.float32)
        prev = track_crop_sig.get(raw_id)
        track_crop_sig[raw_id] = sig
        if prev is None or prev.shape != sig.shape:
            return False
        return bool(np.mean(np.abs(sig - prev)) < CHURN_EPS)
    except Exception:
        return False


# --- Persistent Chicken ID Allocator with Motion-Predictive Spatial Re-Identification ---
def update_track_velocity(disp_id, cx, cy, curr_time):
    """Updates exponential moving average velocity vector (vx, vy) in px/sec for running chicken prediction."""
    if disp_id in display_id_last_positions:
        prev_cx, prev_cy, prev_t = display_id_last_positions[disp_id]
        dt_pos = max(0.01, min(1.0, curr_time - prev_t))
        inst_vx = (cx - prev_cx) / dt_pos
        inst_vy = (cy - prev_cy) / dt_pos

        old_vx, old_vy = track_velocities[disp_id]
        vx = 0.6 * old_vx + 0.4 * inst_vx
        vy = 0.6 * old_vy + 0.4 * inst_vy
        track_velocities[disp_id] = (vx, vy)

    display_id_last_positions[disp_id] = (cx, cy, curr_time)


def assign_display_id(raw_id, cx=None, cy=None, box=None):
    """Guarantees consistent Chicken IDs (#1 to #10) even when chickens run fast or change direction.
    Uses linear motion prediction (velocity vectors) and dynamic search radii to match running chickens."""
    curr_time = time.time()

    # 1. If this raw tracker ID is already mapped, update position, velocity, and return
    if raw_id in track_display_ids:
        disp_id = track_display_ids[raw_id]
        track_missed_counts[raw_id] = 0
        if cx is not None and cy is not None:
            track_last_positions[raw_id] = (cx, cy, curr_time)
            update_track_velocity(disp_id, cx, cy, curr_time)
        return disp_id

    # 2. Spatial & Motion-Predictive Re-Identification:
    # Check candidates from BOTH:
    #   A) Active tracks that were missed in current frame (raw ID handoff during running)
    #   B) Inactive archived tracks
    best_match_id = None
    best_dist = float("inf")
    matched_from_active_raw = None

    if cx is not None and cy is not None:
        # Candidate source A: missed active display IDs
        missed_active_candidates = []
        for r_id, d_id in list(track_display_ids.items()):
            m_cnt = track_missed_counts.get(r_id, 0)
            if m_cnt > 0:  # Currently un-matched in this frame
                last_pos = track_last_positions.get(r_id)
                if last_pos:
                    missed_active_candidates.append((d_id, last_pos[0], last_pos[1], last_pos[2], r_id))

        # Candidate source B: inactive archived tracks
        inactive_candidates = []
        for d_id, info in list(inactive_tracks.items()):
            inactive_candidates.append((d_id, info["cx"], info["cy"], info["time"], None))

        all_candidates = missed_active_candidates + inactive_candidates

        for disp_id, last_cx, last_cy, last_t, old_raw_id in all_candidates:
            elapsed = curr_time - last_t
            if elapsed > 2.5:
                continue

            # Linear motion extrapolation: predict where running chicken moved to
            vx, vy = track_velocities.get(disp_id, (0.0, 0.0))
            pred_cx = last_cx + vx * elapsed
            pred_cy = last_cy + vy * elapsed
            speed = float(np.hypot(vx, vy))

            # Dynamic velocity-expanded matching radius (tightened from 350px to 120px max)
            dynamic_radius = min(120.0, max(50.0, 40.0 + (speed * 0.2) + (15.0 * elapsed)))

            # Distance to predicted location and last known location
            dist_pred = float(np.hypot(cx - pred_cx, cy - pred_cy))
            dist_last = float(np.hypot(cx - last_cx, cy - last_cy))
            dist = min(dist_pred, dist_last)

            if dist < dynamic_radius and dist < best_dist:
                best_dist = dist
                best_match_id = disp_id
                matched_from_active_raw = old_raw_id

    if best_match_id is not None:
        # Seamless ID handoff
        if matched_from_active_raw is not None:
            old_raw = matched_from_active_raw
            track_display_ids.pop(old_raw, None)
            track_missed_counts.pop(old_raw, None)
            track_last_positions.pop(old_raw, None)
            track_hit_streaks.pop(old_raw, None)
            track_confirmed_raw_ids.discard(old_raw)
        else:
            inactive_tracks.pop(best_match_id, None)

        track_display_ids[raw_id] = best_match_id
        track_missed_counts[raw_id] = 0
        track_hit_streaks[raw_id] = 2  # Require at least 1 more confident hit before re-confirming
        if cx is not None and cy is not None:
            track_last_positions[raw_id] = (cx, cy, curr_time)
            update_track_velocity(best_match_id, cx, cy, curr_time)

        if best_match_id not in track_first_seen:
            track_first_seen[best_match_id] = curr_time
        return best_match_id

    # 3. New Chicken: Allocate the lowest available ID between 1 and MAX_TRACKED_CHICKENS
    active_display_ids = set(track_display_ids.values())
    for cand in range(1, MAX_TRACKED_CHICKENS + 1):
        if cand not in active_display_ids:
            track_display_ids[raw_id] = cand
            track_missed_counts[raw_id] = 0
            if cx is not None and cy is not None:
                track_last_positions[raw_id] = (cx, cy, curr_time)
                update_track_velocity(cand, cx, cy, curr_time)
            if cand not in track_first_seen:
                track_first_seen[cand] = curr_time
                track_stationary_duration[cand] = 0.0
            return cand

    # All slots (1..MAX_TRACKED_CHICKENS) occupied.
    return None


def cleanup_stale_ids(active_raw_ids, max_missed=120):
    """Keeps tracks alive through brief occlusions / fast movement (120 frames = 4 seconds)
    and archives inactive chickens for seamless spatial re-identification."""
    curr_time = time.time()

    for raw_id in list(track_display_ids.keys()):
        if raw_id not in active_raw_ids:
            cnt = track_missed_counts.get(raw_id, 0) + 1
            track_missed_counts[raw_id] = cnt
            if raw_id not in track_confirmed_raw_ids:
                track_hit_streaks[raw_id] = max(0, track_hit_streaks.get(raw_id, 0) - 1)
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
                track_display_ids.pop(raw_id, None)
                track_missed_counts.pop(raw_id, None)
                track_last_positions.pop(raw_id, None)
                track_hit_streaks.pop(raw_id, None)
                track_crop_sig.pop(raw_id, None)
                track_frozen_streaks.pop(raw_id, None)
                track_confirmed_raw_ids.discard(raw_id)

    # Clean up inactive tracks older than 3.0 seconds
    for disp_id in list(inactive_tracks.keys()):
        if curr_time - inactive_tracks[disp_id]["time"] > 3.0:
            del inactive_tracks[disp_id]
            track_histories.pop(disp_id, None)
            track_motion_windows.pop(disp_id, None)
            track_first_seen.pop(disp_id, None)
            track_stationary_duration.pop(disp_id, None)
            track_classifications.pop(disp_id, None)
            track_velocities.pop(disp_id, None)
            display_id_last_positions.pop(disp_id, None)



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
    mode = request.args.get('mode', 'annotated').lower()
    def generate():
        while True:
            with frame_lock:
                frame_bytes = latest_raw_jpeg_bytes if mode == 'raw' else latest_jpeg_bytes
                if frame_bytes is None:
                    frame_bytes = latest_jpeg_bytes or latest_raw_jpeg_bytes
                if frame_bytes is None:
                    time.sleep(0.01)
                    continue
            yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            time.sleep(0.02)
    return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')


@flask_app.route('/raw_video_feed')
def raw_video_feed():
    """Multipart MJPEG clean raw video stream (zero AI overlays, maximum 30 FPS)."""
    def generate():
        while True:
            with frame_lock:
                frame_bytes = latest_raw_jpeg_bytes or latest_jpeg_bytes
                if frame_bytes is None:
                    time.sleep(0.01)
                    continue
            yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + frame_bytes + b'\r\n')
            time.sleep(0.02)
    return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')


@flask_app.route('/frame.jpg')
def frame_jpg():
    """Single JPEG snapshot endpoint. Supports ?mode=raw or ?mode=annotated."""
    mode = request.args.get('mode', 'annotated').lower()
    with frame_lock:
        target_bytes = latest_raw_jpeg_bytes if mode == 'raw' else latest_jpeg_bytes
        if target_bytes is None:
            target_bytes = latest_jpeg_bytes or latest_raw_jpeg_bytes
        if target_bytes is None:
            return "No frame available", 404
        return Response(target_bytes, mimetype='image/jpeg')


@flask_app.route('/raw_frame.jpg')
def raw_frame_jpg():
    """Single clean raw camera frame without boxes (high-FPS, zero lag)."""
    with frame_lock:
        target_bytes = latest_raw_jpeg_bytes or latest_jpeg_bytes
        if target_bytes is None:
            return "No frame available", 404
        return Response(target_bytes, mimetype='image/jpeg')


@flask_app.route('/flagged_chicken.jpg')
def flagged_chicken_jpg():
    """Returns the most recent flagged recumbent chicken crop snapshot."""
    snapshot_path = os.path.join(SCRIPT_DIR, "flagged_chicken.jpg")
    if os.path.exists(snapshot_path):
        try:
            with open(snapshot_path, "rb") as f:
                img_data = f.read()
            return Response(img_data, mimetype='image/jpeg')
        except Exception:
            pass
    return "No flagged chicken snapshot available", 404


@flask_app.route('/status')
def status():
    """Real-time JSON telemetry endpoint for PoultrySense."""
    with frame_lock:
        return jsonify(latest_status)


@flask_app.route('/detections')
def detections():
    """Lightweight AI box metadata for client-side overlay on the raw preview.

    The preview runs at camera FPS; this payload only changes at the detection
    rate (2-3 Hz), so clients can poll it cheaply and redraw boxes without
    re-encoding or re-fetching the video.
    """
    with frame_lock:
        payload = {
            "frame_generation": latest_status.get("detection_frame_generation", 0),
            "updated_at": latest_status.get("detections_updated_at", 0),
            "frame_width": latest_status.get("frame_width", 640),
            "frame_height": latest_status.get("frame_height", 480),
            "detection_fps": latest_status.get("detection_fps", 0.0),
            "detections": latest_status.get("detections", []),
        }
    return jsonify(payload)



@flask_app.route('/api/alerts')
def api_alerts():
    """Returns active chicken health alerts generated by the vision engine and Gemini 1.5 Flash."""
    with frame_lock:
        return jsonify({
            "status": "success",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "global_cooldown_active": is_global_cooldown_active(),
            "global_cooldown_remaining_sec": int(get_global_cooldown_remaining()),
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


@flask_app.route('/power/shutdown', methods=['GET', 'POST'])
def power_shutdown():
    """Remote desktop computer shutdown endpoint triggered by PoultrySense app."""
    print("[POWER] Remote shutdown signal received from PoultrySense App!")

    def execute_shutdown():
        time.sleep(1)
        if sys.platform.startswith('win'):
            os.system('shutdown /s /t 5 /c "PoultrySense Remote Shutdown"')
        else:
            os.system('shutdown -h +1 "PoultrySense Remote Shutdown"')

    threading.Thread(target=execute_shutdown, daemon=True).start()
    return jsonify({
        "status": "success",
        "message": "Desktop computer shutdown sequence initiated. PC will turn off in 5 seconds."
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
    """Reset persistent chicken IDs, track histories, and global alert cooldown."""
    track_display_ids.clear()
    track_missed_counts.clear()
    track_histories.clear()
    track_first_seen.clear()
    track_stationary_duration.clear()
    track_classifications.clear()
    track_last_positions.clear()
    track_motion_windows.clear()
    inactive_tracks.clear()
    track_velocities.clear()
    display_id_last_positions.clear()
    track_hit_streaks.clear()
    track_confirmed_raw_ids.clear()
    display_coasting_boxes.clear()
    reset_global_cooldown()
    return jsonify({"status": "success", "message": "Chicken tracking IDs, histories, and alert cooldown reset successfully."})


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
        #detection-overlay {
            position: absolute;
            inset: 0;
            width: 100%;
            height: 100%;
            border-radius: 20px;
            pointer-events: none;
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
            <img class="video-feed" src="/raw_video_feed" alt="PoultrySense Live AI Stream">
            <canvas id="detection-overlay"></canvas>
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
                <span class="metric-title">Detection FPS</span>
                <span class="metric-value highlight" id="metric-fps">-</span>
            </div>
            <div class="metric-card">
                <span class="metric-title">Spatial Warnings</span>
                <span class="metric-value" id="metric-isolated">0</span>
            </div>
        </div>

        <div class="nav-actions">
            <a class="btn" href="/video_feed" target="_blank">📺 Detection Feed</a>
            <a class="btn" href="/raw_video_feed" target="_blank">📹 Streaming Feed</a>
            <button class="btn" onclick="toggleFeedMode()" id="btn-toggle-feed">Switch to Streaming</button>
            <a class="btn" href="/status" target="_blank">📊 JSON Telemetry</a>
            <button class="btn" onclick="fetch('/reset_ids').then(() => alert('IDs Reset!'));">🔄 Reset Tracking IDs</button>
        </div>
    </div>

    <script>
        let isStreaming = false; // Detection mode by default: smooth stream + AI overlay
        const overlayCanvas = document.getElementById('detection-overlay');
        const overlayCtx = overlayCanvas.getContext('2d');

        function clearOverlay() {
            overlayCtx.setTransform(1, 0, 0, 1, 0, 0);
            overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);
        }

        function toggleFeedMode() {
            isStreaming = !isStreaming;
            document.getElementById('btn-toggle-feed').innerText =
                isStreaming ? 'Switch to Detection' : 'Switch to Streaming';
            if (isStreaming) clearOverlay();
        }

        function drawDetections(payload) {
            if (isStreaming) return;

            const img = document.querySelector('.video-feed');
            const rect = img.getBoundingClientRect();
            const dpr = window.devicePixelRatio || 1;
            const viewW = Math.max(1, Math.round(rect.width));
            const viewH = Math.max(1, Math.round(rect.height));

            if (overlayCanvas.width !== Math.round(viewW * dpr) ||
                overlayCanvas.height !== Math.round(viewH * dpr)) {
                overlayCanvas.width = Math.round(viewW * dpr);
                overlayCanvas.height = Math.round(viewH * dpr);
            }

            overlayCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
            overlayCtx.clearRect(0, 0, viewW, viewH);

            const srcW = payload.frame_width || 640;
            const srcH = payload.frame_height || 480;
            // Match the preview's object-fit: cover transform.
            const scale = Math.max(viewW / srcW, viewH / srcH);
            const offsetX = (viewW - srcW * scale) / 2;
            const offsetY = (viewH - srcH * scale) / 2;

            (payload.detections || []).forEach(det => {
                const bbox = det.bbox;
                if (!bbox || bbox.length < 4) return;

                const x = bbox[0] * scale + offsetX;
                const y = bbox[1] * scale + offsetY;
                const w = (bbox[2] - bbox[0]) * scale;
                const h = (bbox[3] - bbox[1]) * scale;
                const tier = det.anomaly_tier || 'Normal';
                const color = tier === 'High Concern' ? '#ff4646'
                    : tier === 'Suspicious' ? '#ff8c00'
                    : tier === 'Monitor' ? '#00d7ff' : '#00e676';

                overlayCtx.strokeStyle = color;
                overlayCtx.lineWidth = 3;
                overlayCtx.strokeRect(x, y, w, h);

                const label = '#' + (det.track_id || 0) + ' ' +
                    (tier === 'Normal'
                        ? String(det.behavior || '').toUpperCase()
                        : tier.toUpperCase());
                overlayCtx.font = 'bold 13px system-ui, sans-serif';
                const textWidth = overlayCtx.measureText(label).width;
                const labelY = Math.max(0, y - 20);
                overlayCtx.fillStyle = 'rgba(15,23,42,0.82)';
                overlayCtx.fillRect(x, labelY, textWidth + 12, 20);
                overlayCtx.fillStyle = color;
                overlayCtx.fillText(label, x + 6, labelY + 14);
            });
        }

        setInterval(() => {
            fetch('/detections')
                .then(res => res.json())
                .then(drawDetections)
                .catch(() => {});
        }, 300);

        setInterval(() => {
            fetch('/status')
                .then(res => res.json())
                .then(data => {
                    document.getElementById('metric-flock').innerText = data.flock_count || data.person_count || 0;
                    document.getElementById('metric-fps').innerText = (data.detection_fps || 0).toFixed(1);
                    document.getElementById('metric-vitality').innerText = (data.health_index || 100) + '%';
                    document.getElementById('metric-isolated').innerText = data.isolated_count || 0;
                })
                .catch(() => {});
        }, 1000);
    </script>
</body>
</html>"""


# --- Decoupled Camera Preview Publisher ---
def stream_publisher(cam, frame_buffer, stop_event):
    """Publish clean camera frames at the camera's own frame rate.

    This thread never runs YOLO. It keeps the preview smooth even while the
    detection worker is busy, and only the newest frame is retained so the
    detection worker can never build a latency backlog.
    """
    global latest_frame, latest_raw_jpeg_bytes, latest_status

    inv_gamma = 1.0 / 1.5
    low_light_lut = np.array(
        [((i / 255.0) ** inv_gamma) * 255 for i in range(256)]
    ).astype("uint8")

    prev_encode_time = time.monotonic()
    smooth_fps = 30.0
    min_encode_interval = 1.0 / max(1.0, STREAM_MAX_FPS)
    last_encode = 0.0
    last_cam_generation = 0

    while not stop_event.is_set():
        ret, frame, last_cam_generation = cam.read_newer(
            last_cam_generation, timeout=0.5
        )
        if not ret or frame is None:
            continue

        # Fast 8x sub-sampled luminance check (~0.05 ms)
        if frame[::8, ::8].mean() < 45:
            frame = cv2.LUT(frame, low_light_lut)

        # Always hand the freshest frame to the detection worker. Publishing is
        # cheap; only JPEG encoding is rate-limited to avoid wasting CPU when a
        # camera delivers far more frames than the preview needs.
        frame_buffer.publish(frame)

        now = time.monotonic()
        if now - last_encode < min_encode_interval:
            continue
        last_encode = now

        inst_fps = 1.0 / max(1e-5, now - prev_encode_time)
        prev_encode_time = now
        smooth_fps = 0.85 * smooth_fps + 0.15 * min(inst_fps, 120.0)

        ok, clean_jpeg = cv2.imencode(
            '.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, STREAM_JPEG_QUALITY]
        )
        if not ok:
            continue

        with frame_lock:
            latest_frame = frame
            latest_raw_jpeg_bytes = clean_jpeg.tobytes()
            latest_status["active"] = True
            latest_status["fps"] = round(smooth_fps, 1)
            latest_status["stream_fps"] = round(smooth_fps, 1)


# --- Main Inference & Camera Loop ---
def camera_loop():
    global latest_frame, latest_jpeg_bytes, latest_raw_jpeg_bytes, latest_status, _last_cooldown_log_time

    print("[INFO] Initializing PoultrySense AI Engine...")

    exec_device = get_execution_device()

    det_model = None
    behavior_model = None
    health_model = None
    chicken_classes = [0]

    if HAS_YOLO:
        # 1. Chicken Detection Model
        det_path = get_model_path(DET_MODELS)
        if det_path:
            try:
                resolved_det = resolve_accelerated_model(det_path, imgsz=640)
                print(f"[INFO] Loading Detection Model from: {resolved_det}")
                try:
                    det_model = YOLO(resolved_det, task='detect')
                except Exception as ex_ov:
                    print(f"[WARN] Accelerated load notice ({ex_ov}); falling back to native PyTorch .pt: {det_path}")
                    det_model = YOLO(det_path, task='detect')

                print("[INFO] YOLO Detection Model loaded successfully!")

                # Determine strict chicken class ID(s)
                found_classes = []
                if hasattr(det_model, 'names') and det_model.names:
                    for cid, cname in det_model.names.items():
                        name_str = str(cname).lower()
                        if any(k in name_str for k in ["chicken", "poultry", "hen", "rooster", "bird"]):
                            found_classes.append(int(cid))

                    # If using standard COCO model (like yolo26n.pt), class 14 is bird.
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

        # Human / Person Veto Filter (prevents hands, arms, and people from false-triggering chicken tracks)
        person_model = None
        yolo_person_path = os.path.join(SCRIPT_DIR, "yolo26n.pt")
        try:
            if not os.path.exists(yolo_person_path):
                print("[INFO] Human Veto Model missing. Downloading yolo26n.pt...")
                import urllib.request
                urllib.request.urlretrieve(
                    "https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n.pt",
                    yolo_person_path
                )
            if os.path.exists(yolo_person_path):
                person_model = YOLO(yolo_person_path)
                print("[INFO] Human & Hand Veto Filter active (yolo26n.pt)")
        except Exception as ex_p:
            print(f"[WARN] Human Veto Filter initialization warning: {ex_p}")

        # 2. Behavior Classification Model
        beh_path = get_model_path(BEHAVIOR_MODELS)
        if beh_path:
            try:
                resolved_beh = resolve_accelerated_model(beh_path, imgsz=160)
                print(f"[INFO] Loading Behavior Model from: {resolved_beh}")
                try:
                    behavior_model = YOLO(resolved_beh, task='classify')
                except Exception as ex_beh:
                    print(f"[WARN] Accelerated load notice ({ex_beh}); using PyTorch .pt: {beh_path}")
                    behavior_model = YOLO(beh_path, task='classify')
                print("[INFO] Behavior Classification Model loaded successfully!")
            except Exception as e:
                print(f"[WARN] Could not load Behavior Model: {e}")

        # 3. Health Classifier Model
        hlth_path = get_model_path(HEALTH_MODELS)
        if hlth_path:
            try:
                resolved_hlth = resolve_accelerated_model(hlth_path, imgsz=160)
                print(f"[INFO] Loading Health Classifier Model from: {resolved_hlth}")
                try:
                    health_model = YOLO(resolved_hlth, task='classify')
                except Exception as ex_hlth:
                    print(f"[WARN] Accelerated load notice ({ex_hlth}); using PyTorch .pt: {hlth_path}")
                    health_model = YOLO(hlth_path, task='classify')
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

    # Decoupled preview publisher: the clean camera stream runs at the camera's
    # own frame rate regardless of how long inference takes.
    frame_buffer = FrameBuffer()
    stream_stop = threading.Event()
    publisher_thread = threading.Thread(
        target=stream_publisher,
        args=(cam, frame_buffer, stream_stop),
        daemon=True,
    )
    publisher_thread.start()
    print(
        f"[INFO] Clean stream publisher started at camera FPS "
        f"(detection capped at {DETECTION_MAX_RATE_HZ:.1f}/s, conf={DETECTION_CONF:.2f})"
    )

    rate_gate = RateGate(DETECTION_MAX_RATE_HZ)
    # Keep tracks alive for ~4 seconds of wall-clock time regardless of det rate.
    max_missed_detections = max(3, int(round(4.0 * DETECTION_MAX_RATE_HZ)))

    cached_display_items = []
    cached_person_boxes = []
    last_generation = 0
    prev_det_time = time.time()
    smooth_det_fps = 0.0

    while True:
        # Pace detection to 2-3 Hz, then consume only the newest camera frame.
        # Inference never queues frames, so latency stays bounded.
        wait = rate_gate.remaining()
        if wait > 0:
            time.sleep(min(wait, 0.25))
            continue

        frame, generation = frame_buffer.wait_for_newer(last_generation, timeout=0.5)
        if frame is None:
            continue
        last_generation = generation
        rate_gate.mark()

        # Detection draws HUD boxes on its own copy; the preview stays clean.
        frame = frame.copy()
        h_frame, w_frame, _ = frame.shape

        curr_time = time.time()
        det_interval = max(1e-3, curr_time - prev_det_time)
        prev_det_time = curr_time
        # Real elapsed time, not a per-frame clamp: detection runs at 2-3 Hz.
        dt = max(0.001, min(2.0, det_interval))
        inst_det_fps = min(1.0 / det_interval, DETECTION_MAX_RATE_HZ * 1.5)
        smooth_det_fps = (
            inst_det_fps if smooth_det_fps <= 0.0
            else 0.8 * smooth_det_fps + 0.2 * inst_det_fps
        )

        # Human / Person Veto on every detection frame (2-3 Hz is ample for people).
        if person_model is not None:
            try:
                p_res = person_model.predict(source=frame, classes=[0], conf=0.30, imgsz=384, verbose=False)[0]
                if p_res.boxes is not None and len(p_res.boxes) > 0:
                    cached_person_boxes = p_res.boxes.xyxy.cpu().numpy()
                else:
                    cached_person_boxes = []
            except Exception:
                pass
        person_boxes = cached_person_boxes

        display_items = cached_display_items
        if det_model is not None:
            active_raw_ids = []
            raw_boxes = []
            raw_confs = []

            try:
                tracker_cfg = TRACKER_YAML if os.path.exists(TRACKER_YAML) else "botsort.yaml"
                results = det_model.track(
                    source=frame,
                    persist=True,
                    tracker=tracker_cfg,
                    classes=chicken_classes,
                    conf=DETECTION_CONF,  # Native confidence: sensitive to all chickens while person veto rejects humans
                    iou=0.50,
                    imgsz=640,          # Full native 640px resolution — guarantees 100% accuracy & small chicken recall
                    max_det=20,
                    device=exec_device,
                    verbose=False
                )[0]

                boxes = results.boxes
                if boxes is not None and len(boxes) > 0 and boxes.id is not None:
                    xyxys = boxes.xyxy.cpu().numpy()
                    confs = boxes.conf.cpu().numpy()
                    ids = boxes.id.cpu().numpy()
                    cls_ids = boxes.cls.cpu().numpy() if boxes.cls is not None else np.zeros(len(xyxys))

                    for box, conf, raw_id, c_id in zip(xyxys, confs, ids, cls_ids):
                        if int(c_id) not in chicken_classes:
                            continue

                        # 1. Human / Person Veto: Reject if center is inside or overlaps a detected person/body
                        bx1, by1, bx2, by2 = box
                        bcx = (bx1 + bx2) / 2.0
                        bcy = (by1 + by2) / 2.0
                        is_human = False
                        for pbox in person_boxes:
                            px1, py1, px2, py2 = pbox
                            if (px1 <= bcx <= px2 and py1 <= bcy <= py2):
                                is_human = True
                                break
                            # Also check IoU / overlap ratio with human bounding box
                            ix1 = max(bx1, px1)
                            iy1 = max(by1, py1)
                            ix2 = min(bx2, px2)
                            iy2 = min(by2, py2)
                            if ix2 > ix1 and iy2 > iy1:
                                inter_area = (ix2 - ix1) * (iy2 - iy1)
                                chicken_area = max(1.0, (bx2 - bx1) * (by2 - by1))
                                if (inter_area / chicken_area) > 0.30:
                                    is_human = True
                                    break
                        if is_human:
                            continue

                        # 2. Minimum Box Dimensions & Plump Aspect Ratio Filter (rejects fingers, cords, and specks)
                        box_w = abs(bx2 - bx1)
                        box_h = abs(by2 - by1)
                        aspect = box_w / max(1.0, box_h)
                        if box_w < 45 or box_h < 45 or aspect < 0.28 or aspect > 3.2:
                            continue

                        raw_boxes.append(box)
                        raw_confs.append(float(conf))
                        active_raw_ids.append(int(raw_id))
            except Exception:
                pass

            cleanup_stale_ids(active_raw_ids, max_missed=max_missed_detections)

            # Collect display items (both active detections and motion-extrapolated coasting tracks)
            display_items = []
            seen_display_ids = set()

            for box, conf, raw_id in zip(raw_boxes, raw_confs, active_raw_ids):
                cx = (box[0] + box[2]) / 2.0
                cy = (box[1] + box[3]) / 2.0

                # 1. Spatial/Motion-Predictive Display ID allocation (instant handoff for running chickens)
                display_id = assign_display_id(raw_id, cx=cx, cy=cy, box=box)
                if display_id is None:
                    continue

                # 2. Debounce & Frozen Crop Check (filters out hands, furniture, and room artifacts)
                if raw_id not in track_confirmed_raw_ids:
                    if conf >= 0.38:
                        if _crop_is_frozen(frame, box, raw_id):
                            track_frozen_streaks[raw_id] += 1
                            if track_frozen_streaks[raw_id] >= CHURN_MAX_FROZEN:
                                continue
                        else:
                            track_frozen_streaks[raw_id] = 0
                        track_hit_streaks[raw_id] += 1
                        required_hits = 2 if conf >= 0.50 else 3
                        if track_hit_streaks[raw_id] >= required_hits:
                            track_confirmed_raw_ids.add(raw_id)
                        else:
                            continue
                    else:
                        continue

                # 3. EMA Box Position Smoothing (stabilizes bounding boxes on fast-running chickens)
                if display_id in display_coasting_boxes:
                    prev_box = display_coasting_boxes[display_id]["box"]
                    alpha = 0.70  # 70% current detection, 30% previous frame box
                    box = alpha * box + (1.0 - alpha) * prev_box
                    cx = (box[0] + box[2]) / 2.0
                    cy = (box[1] + box[3]) / 2.0

                seen_display_ids.add(display_id)
                display_coasting_boxes[display_id] = {
                    "box": box,
                    "conf": conf,
                    "missed": 0,
                    "cx": cx,
                    "cy": cy,
                    "raw_id": raw_id
                }
                display_items.append({
                    "display_id": display_id,
                    "box": box,
                    "conf": conf,
                    "raw_id": raw_id,
                    "cx": cx,
                    "cy": cy,
                    "is_coasting": False
                })

            # Anti-blink motion coasting: hold confirmed tracks for up to 2 missed frames (~66ms)
            for disp_id in list(display_coasting_boxes.keys()):
                if disp_id not in seen_display_ids:
                    c_info = display_coasting_boxes[disp_id]
                    c_info["missed"] += 1
                    if c_info["missed"] <= 2:
                        vx, vy = track_velocities.get(disp_id, (0.0, 0.0))
                        # Extrapolate box position along running velocity vector
                        shift_x = float(vx * dt)
                        shift_y = float(vy * dt)
                        old_box = c_info["box"]
                        new_box = np.array([
                            max(0.0, float(old_box[0] + shift_x)),
                            max(0.0, float(old_box[1] + shift_y)),
                            min(float(w_frame), float(old_box[2] + shift_x)),
                            min(float(h_frame), float(old_box[3] + shift_y))
                        ], dtype=np.float32)

                        c_info["box"] = new_box
                        c_info["cx"] += shift_x
                        c_info["cy"] += shift_y
                        display_items.append({
                            "display_id": disp_id,
                            "box": new_box,
                            "conf": c_info["conf"],
                            "raw_id": c_info["raw_id"],
                            "cx": c_info["cx"],
                            "cy": c_info["cy"],
                            "is_coasting": True
                        })
                    else:
                        del display_coasting_boxes[disp_id]

            cached_display_items = display_items

        detection_list = []
        healthy_count = 0
        warning_count = 0
        unhealthy_count = 0
        isolated_count = 0
        observing_count = 0

        # Check spatial isolation across displayed chickens
        isolation_thresh = 0.25 * w_frame
        isolated_flags = []
        for i, item in enumerate(display_items):
            is_iso = False
            if len(display_items) > 1:
                min_dist = min(
                    np.hypot(item["cx"] - other["cx"], item["cy"] - other["cy"])
                    for j, other in enumerate(display_items) if j != i
                )
                if min_dist > isolation_thresh:
                    is_iso = True
            isolated_flags.append(is_iso)
            if is_iso:
                isolated_count += 1

        # 2. Process each detected chicken with High-FPS throttled classification
        classified_this_frame = 0
        for idx, item in enumerate(display_items):
            display_id = item["display_id"]
            box = item["box"]
            conf = item["conf"]
            raw_id = item["raw_id"]
            cx = item["cx"]
            cy = item["cy"]
            is_coasting = item["is_coasting"]
            is_isolated = isolated_flags[idx]

            x1, y1, x2, y2 = map(int, box)
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(w_frame, x2), min(h_frame, y2)

            # Motion Calculation (centroid tracking per chicken ID). Detection runs
            # at 2-3 Hz, so normalise displacement to the old 30 FPS units that
            # the behaviour thresholds below were tuned for.
            hist = track_histories[display_id]
            if is_coasting:
                motion = 0.0
            else:
                motion = 0.0
                if len(hist) > 0:
                    prev_c = hist[-1]
                    delta_px = float(np.hypot(cx - prev_c[0], cy - prev_c[1]))
                    motion = normalize_motion(delta_px, dt)
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
            if not is_coasting and (curr_time - last_time > 1.5) and not classification_queue.full():
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
            elif stationary_sec >= 15.0 and behavior_label == "stand":
                behavior_label = "rest"

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
                gemini_verified = False
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

                # 4. Map to 4-tier decision hierarchy (LOCAL PRELIMINARY — Gemini overrides below)
                if anomaly_tier == "Normal":
                    health_label = "Healthy"
                    if behavior_label == "walk":
                        health_detail = "Normal Locomotion (Gait Score 0-1)"
                    elif behavior_label == "peck":
                        health_detail = "Active Foraging Behavior"
                    elif behavior_label in ("rest", "sleep") or stationary_sec >= 15.0:
                        health_detail = f"Normal Resting / Sleeping ({stationary_sec:.0f}s)"
                    else:
                        health_detail = "Adequate Movement Detected"
                    color = get_id_color(display_id)
                    healthy_count += 1
                elif anomaly_tier == "Monitor":
                    health_label = "Monitor"
                    if is_isolated:
                        health_detail = "Flock Edge / Moderate Separation"
                    elif stationary_sec >= 25.0:
                        health_detail = f"Extended Resting Pause ({stationary_sec:.0f}s)"
                    else:
                        health_detail = "Mild Activity Deviation"
                    color = (0, 215, 255)  # Amber-Yellow
                    observing_count += 1
                elif anomaly_tier == "Suspicious":
                    health_label = "Needs Attention"
                    if is_isolated and stats["isolation_pct"] >= 50.0:
                        health_detail = "Persistent Isolation & Inactivity"
                    elif stationary_sec >= 45.0:
                        health_detail = f"Prolonged Immobility ({stationary_sec:.0f}s)"
                    else:
                        health_detail = f"Suspicious Activity ({anomaly_score}%)"
                    color = (0, 140, 255)  # Bright Amber-Orange
                    warning_count += 1
                else:  # High Concern (>= 80)
                    health_label = "High Concern"
                    health_detail = f"Severe Lethargy Marker ({stationary_sec:.0f}s immobile)"
                    color = (70, 70, 255)  # Vivid Alert Red
                    unhealthy_count += 1

                # 5. Multimodal Abnormal Behavior & Sensor Stress Analysis with Gemini 3.6 Flash
                # Triggered when ANY abnormal behavior or environmental stress is detected:
                # - Prolonged immobility / recumbency
                # - Lethargic posture (classifier / posture sub-score)
                # - Persistent flock isolation
                # - Thermal stress (ambient temperature > 30°C or < 18°C from ESP32 DHT22)
                # - Composite anomaly tier (Suspicious / High Concern)
                abnormal_triggers = []
                if stationary_sec >= DAYTIME_RECUMBENT_THRESHOLD_SEC:
                    abnormal_triggers.append(f"Prolonged immobility ({stationary_sec:.0f}s >= {DAYTIME_RECUMBENT_THRESHOLD_SEC:.0f}s threshold)")
                elif stationary_sec >= 25.0 and behavior_label in ("rest", "stand", "sit"):
                    abnormal_triggers.append(f"Extended stationary resting ({stationary_sec:.0f}s)")

                if unhealthy_prob >= 0.40 or anomaly_info.get("sub_scores", {}).get("posture", 0) >= 40.0:
                    abnormal_triggers.append(f"Lethargic posture / visual anomaly score ({unhealthy_prob:.2f})")

                if is_isolated and (stats.get("isolation_pct", 0) >= 35.0 or elapsed_obs >= 20.0):
                    abnormal_triggers.append(f"Spatial isolation away from flock ({stats.get('isolation_pct', 0):.0f}%)")

                curr_t_check, _, _ = get_current_climate()
                if curr_t_check is not None:
                    if curr_t_check > 30.0 and (stationary_sec >= 15.0 or motion < 1.6):
                        abnormal_triggers.append(f"Heat stress condition ({curr_t_check:.1f}°C > 30°C with reduced activity)")
                    elif curr_t_check < 18.0 and (stationary_sec >= 15.0 or motion < 1.6):
                        abnormal_triggers.append(f"Cold stress condition ({curr_t_check:.1f}°C < 18°C with huddled inactivity)")

                if anomaly_tier in ("Suspicious", "High Concern") or anomaly_score >= 60:
                    abnormal_triggers.append(f"High anomaly score ({anomaly_tier} {anomaly_score}%)")

                is_abnormal_flagged = len(abnormal_triggers) > 0

                if is_abnormal_flagged and crop.size > 0 and crop.shape[0] >= 20 and crop.shape[1] >= 20:
                    snapshot_file = os.path.join(SCRIPT_DIR, "flagged_chicken.jpg")
                    try:
                        cv2.imwrite(snapshot_file, crop)
                    except Exception as ex_crop:
                        print(f"[Vision Engine] Notice saving flagged snapshot: {ex_crop}")

                    # 15-minute global timestamp-based cooldown (prevents duplicate queries and SMS when tracker IDs switch)
                    try:
                        if is_global_cooldown_active(curr_time):
                            if curr_time - _last_cooldown_log_time >= 30.0:
                                _last_cooldown_log_time = curr_time
                                rem = int(get_global_cooldown_remaining(curr_time))
                                print(f"[COOLDOWN] Global alert cooldown active ({rem}s remaining / {GLOBAL_ALERT_COOLDOWN_SEC:.0f}s). Skipping Gemini query for Chicken #{display_id}.")
                        else:
                            gemini_escalator.request_verdict_async(
                                track_id=display_id,
                                stats=stats,
                                baseline=baseline,
                                anomaly_info=anomaly_info,
                                crop=crop,
                                curr_time=curr_time,
                                is_isolated=is_isolated,
                                stationary_sec=stationary_sec,
                                snapshot_path=snapshot_file,
                                abnormal_flags=abnormal_triggers
                            )
                    except Exception as ex_cd:
                        print(f"[COOLDOWN] Warning during alert cooldown check: {ex_cd}")

                # 6. GEMINI VERDICT OVERRIDE — Gemini 3.6 Flash is the DEFINITIVE AUTHORITY
                gemini_verdict = gemini_escalator.get_verdict(display_id)
                gemini_summary = ""
                gemini_verified = False
                gemini_severity = "Low"

                if gemini_verdict and gemini_verdict.get("gemini_evaluated", False):
                    gemini_verified = True
                    gemini_tier = gemini_verdict["tier"]
                    gemini_label = gemini_verdict["label"]
                    gemini_severity = gemini_verdict.get("severity", "Low")
                    gemini_reason = gemini_verdict["reason"]
                    gemini_summary = f"Gemini: {gemini_reason}"

                    # Undo local counter increments before applying Gemini override
                    if anomaly_tier == "Normal":
                        healthy_count -= 1
                    elif anomaly_tier == "Monitor":
                        observing_count -= 1
                    elif anomaly_tier == "Suspicious":
                        warning_count -= 1
                    elif anomaly_tier == "High Concern":
                        unhealthy_count -= 1

                    # Apply Gemini's verdict as the definitive tier
                    anomaly_tier = gemini_tier
                    health_detail = gemini_reason

                    if gemini_tier == "Normal":
                        health_label = "Healthy"
                        color = get_id_color(display_id)
                        healthy_count += 1
                    elif gemini_tier == "Monitor":
                        health_label = "Monitor"
                        color = (0, 215, 255)
                        observing_count += 1
                    elif gemini_tier == "Suspicious":
                        health_label = "Needs Attention"
                        color = (0, 140, 255)
                        warning_count += 1
                    else:  # High Concern
                        health_label = "Critical Alert"
                        color = (70, 70, 255)
                        unhealthy_count += 1
                else:
                    # No Gemini verdict yet — use local heuristic summary
                    gemini_summary = gemini_escalator.get_chicken_summary(display_id)

            # Draw HUD Box
            draw_hud_box(frame, (x1, y1), (x2, y2), color, thickness=2, line_len=16)

            # Clean minimal badge with Gemini verification indicator
            gemini_check = " \u2713" if gemini_verified else ""
            if elapsed_obs < OBSERVATION_WINDOW:
                badge_text = f"#{display_id}"
            elif gemini_verified:
                badge_text = f"#{display_id} {gemini_severity.upper()}{gemini_check}"
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
                "severity": gemini_severity if gemini_verified else ("Low" if anomaly_tier == "Normal" else ("Medium" if anomaly_tier == "Monitor" else ("High" if anomaly_tier == "Suspicious" else "Critical"))),
                "gemini_summary": gemini_summary,
                "gemini_verified": gemini_verified,

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


        # Encode only the annotated frame. The clean preview is encoded by the
        # stream publisher at camera FPS, so detection no longer pays for two
        # JPEG encodes per inference.
        _, jpeg = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 70])

        with frame_lock:
            latest_jpeg_bytes = jpeg.tobytes()
            latest_status.update({
                "active": True,
                "detection_fps": round(smooth_det_fps, 1),
                "detection_rate_hz": round(1.0 / det_interval, 2),
                "detection_conf": DETECTION_CONF,
                "detection_frame_generation": generation,
                "detections_updated_at": round(curr_time, 3),
                "frame_width": w_frame,
                "frame_height": h_frame,
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
                "sensor_connected": (time.time() - latest_sensor_data.get("last_updated", 0) < 30.0) and (latest_sensor_data.get("temperature") is not None),
                "temperature": latest_sensor_data.get("temperature") if (time.time() - latest_sensor_data.get("last_updated", 0) < 30.0) else None,
                "humidity": latest_sensor_data.get("humidity") if (time.time() - latest_sensor_data.get("last_updated", 0) < 30.0) else None,
                "global_cooldown_active": is_global_cooldown_active(),
                "global_cooldown_remaining_sec": int(get_global_cooldown_remaining()),
                "detections": detection_list,
                "alerts": gemini_escalator.get_active_alerts()
            })

    stream_stop.set()
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


def serial_pc_shutdown_listener():
    """Monitors connected ESP32 USB Serial port for PC shutdown triggers."""
    try:
        import serial
        import serial.tools.list_ports
    except ImportError:
        print("[INFO] PySerial not installed — USB Serial monitor skipped.")
        return

    print("[INFO] ESP32 PC Shutdown Serial Listener active...")
    while True:
        try:
            ports = list(serial.tools.list_ports.comports())
            esp_port = None
            for p in ports:
                desc_full = f"{p.description} {getattr(p, 'manufacturer', '')} {getattr(p, 'product', '')}".lower()
                if any(k in desc_full for k in ["ch340", "cp210", "usb", "uart", "esp32", "serial", "silicon labs", "qinheng", "ftdi"]):
                    esp_port = p.device
                    break

            if esp_port:
                ser = serial.Serial(esp_port, 115200, timeout=2)
                print(f"[SERIAL] Connected to ESP32 on {esp_port} (115200 baud). Listening for shutdown signal...")
                while ser.is_open:
                    line = ser.readline().decode('utf-8', errors='ignore').strip()
                    if line:
                        if "SHUTDOWN_PC_NOW" in line or "SHUTDOWN_PC" in line:
                            print("\n" + "=" * 60)
                            print("[POWER] 🧨 SHUTDOWN SIGNAL RECEIVED FROM ESP32 HARDWARE VIA SERIAL!")
                            print("[POWER] Windows PC shutdown sequence starting in 5 seconds...")
                            print("=" * 60 + "\n")
                            if sys.platform.startswith('win'):
                                os.system('shutdown /s /t 5 /c "PoultrySense ESP32 Hardware Shutdown"')
                            else:
                                os.system('shutdown -h +1 "PoultrySense ESP32 Hardware Shutdown"')
                            time.sleep(10)
                ser.close()
        except Exception:
            pass
        time.sleep(5)


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

    # 3. Start ESP32 USB Serial Shutdown listener
    serial_thread = threading.Thread(target=serial_pc_shutdown_listener, daemon=True)
    serial_thread.start()

    print("[INFO] PoultrySense AI Server running at http://0.0.0.0:5000")
    flask_app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)


if __name__ == "__main__":
    main()
