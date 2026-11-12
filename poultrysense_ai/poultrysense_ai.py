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

# Detection is the expensive stage. The preview remains smooth on its own
# thread, while boxes update at a sustainable production cadence.
DETECTION_MAX_RATE_HZ = float(os.environ.get("DETECTION_MAX_RATE_HZ", "10.0"))

# Two thresholds, deliberately different:
#
# DETECTOR_CONF is what the detector reports. It must stay LOW so BoT-SORT's
# second association stage can still see a blurred/fast chicken. Ultralytics
# drops everything below this before the tracker runs, so raising it silently
# disables track_low_thresh.
# DETECTION_CONF is what may create a new track or a new box on screen.
DETECTOR_CONF = float(os.environ.get("POULTRYSENSE_DETECTOR_CONF", "0.15"))
DETECTOR_IMGSZ = int(os.environ.get("POULTRYSENSE_DETECTOR_IMGSZ", "320"))
# Detector precision. INT8 is OPT-IN: it needs a calibration dataset, and an
# uncalibrated INT8 model can lose far more accuracy than the speed is worth.
#   POULTRYSENSE_INT8=1          enable INT8 for the detector only
#   POULTRYSENSE_CALIB_DATA=...  path to a YOLO data.yaml (recommended)
#   POULTRYSENSE_CALIB_DIR=...   folder of representative images
# With neither set, the bundled chicken dataset is used. Classifiers always
# stay FP32: a 2-class head is too easy to flip with quantization.
INT8_ENABLED = os.environ.get("POULTRYSENSE_INT8", "").strip().lower() in (
    "1", "true", "yes", "on",
)
DETECTOR_PRECISION = "int8" if INT8_ENABLED else "fp32"
CALIB_DATA = os.environ.get("POULTRYSENSE_CALIB_DATA", "").strip()
CALIB_DIR = os.environ.get("POULTRYSENSE_CALIB_DIR", "").strip()
DETECTION_CONF = float(os.environ.get("POULTRYSENSE_DETECTION_CONF", "0.45"))
MAX_DISPLAY_BOXES = int(os.environ.get("POULTRYSENSE_MAX_DISPLAY_BOXES", "0"))
STREAM_JPEG_QUALITY = int(os.environ.get("STREAM_JPEG_QUALITY", "70"))
# Cap preview publishing so a fast/virtual camera cannot burn CPU encoding
# hundreds of JPEGs per second. 20 FPS is smooth enough for monitoring while
# leaving CPU/GPU time for detection.
STREAM_MAX_FPS = float(os.environ.get("STREAM_MAX_FPS", "20"))

# --- Firebase & Twilio Alert Dispatch Logic ---
FIREBASE_DATABASE_URL = os.environ.get(
    "FIREBASE_DATABASE_URL",
    "https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app"
).rstrip("/")

# --- Gemini Alert Cooldown Configuration ---
# Each chicken owns its own cooldown window (default 900 s = 15 min), so one
# flagged bird can no longer silence Gemini for the rest of the flock. A short
# system-wide gap (default 20 s) still keeps calls from firing simultaneously.
# SMS is scan-scoped and unaffected: the farmer gets one summary when the scan ends.
GLOBAL_ALERT_COOLDOWN_SEC = float(os.environ.get("GLOBAL_ALERT_COOLDOWN_SEC", "900.0"))
GEMINI_MIN_QUERY_GAP_SEC = float(os.environ.get("GEMINI_MIN_QUERY_GAP_SEC", "20.0"))
DAYTIME_RECUMBENT_THRESHOLD_SEC = float(os.environ.get("DAYTIME_RECUMBENT_THRESHOLD_SEC", os.environ.get("STATIONARY_DURATION_THRESHOLD_SEC", "60.0")))
GEMINI_TRIGGER_STATIONARY_SEC = float(os.environ.get("POULTRYSENSE_STATIONARY_TRIGGER_SEC", "45.0"))
OBSERVATION_WINDOW = float(os.environ.get("POULTRYSENSE_OBSERVATION_WINDOW", "45.0"))

_last_global_alert_timestamp = 0.0
_last_global_sms_timestamp = 0.0
_last_alert_timestamp_by_id = {}   # display_id -> timestamp of that bird's last Gemini query
_global_cooldown_lock = threading.Lock()
_last_cooldown_log_time = 0.0

def is_global_cooldown_active(curr_time=None):
    """Returns True while the short system-wide gap between Gemini calls is running."""
    global _last_global_alert_timestamp
    if curr_time is None:
        curr_time = time.time()
    with _global_cooldown_lock:
        return (curr_time - _last_global_alert_timestamp) < GEMINI_MIN_QUERY_GAP_SEC

def get_global_cooldown_remaining(curr_time=None):
    """Returns remaining seconds in the short system-wide gap between Gemini calls."""
    global _last_global_alert_timestamp
    if curr_time is None:
        curr_time = time.time()
    with _global_cooldown_lock:
        elapsed = curr_time - _last_global_alert_timestamp
        return max(0.0, GEMINI_MIN_QUERY_GAP_SEC - elapsed)

def is_track_cooldown_active(track_id, curr_time=None):
    """Returns True while this specific chicken is still inside its own cooldown window."""
    if curr_time is None:
        curr_time = time.time()
    with _global_cooldown_lock:
        last = _last_alert_timestamp_by_id.get(track_id, 0.0)
        return (curr_time - last) < GLOBAL_ALERT_COOLDOWN_SEC

def get_track_cooldown_remaining(track_id, curr_time=None):
    """Returns seconds left before this chicken may be sent to Gemini again."""
    if curr_time is None:
        curr_time = time.time()
    with _global_cooldown_lock:
        last = _last_alert_timestamp_by_id.get(track_id, 0.0)
        return max(0.0, GLOBAL_ALERT_COOLDOWN_SEC - (curr_time - last))

def record_global_alert(timestamp=None, track_id=None):
    """Stamps the system-wide gap and, when given, this chicken's cooldown window."""
    global _last_global_alert_timestamp
    if timestamp is None:
        timestamp = time.time()
    with _global_cooldown_lock:
        _last_global_alert_timestamp = timestamp
        if track_id is not None:
            _last_alert_timestamp_by_id[track_id] = timestamp
            print(f"[COOLDOWN] Chicken #{track_id} cooling down for {GLOBAL_ALERT_COOLDOWN_SEC:.0f}s "
                  f"({GLOBAL_ALERT_COOLDOWN_SEC/60:.1f} min). Other chickens are unaffected.")

def record_track_alert(track_id, timestamp=None):
    """Records a Gemini query for one chicken without touching the other birds."""
    record_global_alert(timestamp=timestamp, track_id=track_id)

def reset_global_cooldown():
    """Clears every cooldown: the system-wide gap and all per-chicken windows."""
    global _last_global_alert_timestamp, _last_global_sms_timestamp
    with _global_cooldown_lock:
        _last_global_alert_timestamp = 0.0
        _last_global_sms_timestamp = 0.0
        _last_alert_timestamp_by_id.clear()
        print("[COOLDOWN] All Gemini cooldowns reset.")

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

def _dispatch_sms(sms_body, enforce_cooldown=True):
    """Send a single SMS through Twilio.

    Honours the farmer's in-app SMS switch and phone number, falling back to the
    .env configuration. Returns True only when Twilio accepted the message.
    """
    global _last_global_sms_timestamp
    try:
        if not HAS_TWILIO:
            print("[SMS] Notice: twilio library not available. Skipping SMS.")
            return False

        curr_time = time.time()
        if enforce_cooldown:
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

        client = TwilioClient(account_sid, auth_token)
        msg = client.messages.create(
            body=sms_body,
            from_=from_number,
            to=to_number
        )
        with _global_cooldown_lock:
            _last_global_sms_timestamp = curr_time
        print(f"[SMS] ✓ Dispatched to {to_number} (SID: {msg.sid})")
        return True
    except Exception as e:
        print(f"[SMS] ⚠ Failed to dispatch Twilio SMS: {e}")
        return False


# ─── Scan session summary ────────────────────────────────────────────────────
# One SMS per completed scan. Per-bird messages are no longer sent mid-scan; the
# farmer gets a single tally when the app reports the scan finished.
_scan_lock = threading.Lock()
_scan_state = {
    "active": False,
    "started_at": 0.0,
    "seen_ids": set(),
    "warning_ids": set(),
    "alert_ids": set(),
    "chickens": {},  # {track_id: {chicken_id, anomaly_score, anomaly_tier, behavior, gemini_summary}}
}

# Observation clock for the current scan. Kept separate from track_first_seen so
# display IDs survive a scan while every bird still gets a fresh 15 s calibration.
_scan_observation_epoch = 0.0


def get_observed_seconds(display_id, curr_time):
    """Seconds this bird has been observed in the current scan window."""
    first_seen = track_first_seen.get(display_id, curr_time)
    return max(0.0, curr_time - max(first_seen, _scan_observation_epoch))


def scan_start():
    """Begin a scan window. Clears the previous tally, bird evidence, and resets active_alerts in DB."""
    with _scan_lock:
        _scan_state["active"] = True
        _scan_state["started_at"] = time.time()
        _scan_state["seen_ids"] = set()
        _scan_state["warning_ids"] = set()
        _scan_state["alert_ids"] = set()
        _scan_state["chickens"] = {}

    # Fresh evidence for the new scan, but keep each chicken's display ID so
    # #3 stays #3. Completed scans in scan_history are never touched.
    global _scan_observation_epoch
    _scan_observation_epoch = time.time()

    behavior_history.records.clear()
    behavior_history.baselines.clear()
    track_histories.clear()
    track_motion_windows.clear()
    track_stationary_duration.clear()
    track_classifications.clear()
    track_last_hist_time.clear()

    # Reset active_alerts in Firebase Realtime Database for the new scan session
    if requests is not None:
        try:
            requests.delete(f"{FIREBASE_DATABASE_URL}/active_alerts.json", timeout=3)
            print("[Firebase] ✓ Cleared active_alerts node for fresh scan.")
        except Exception as e:
            print(f"[Firebase] Notice clearing active_alerts: {e}")

    print("[SCAN] Session started — per-bird evidence cleared; cumulative details active.")


def scan_record(det):
    """Track unique birds seen, and maintain cumulative details for each bird during this scan."""
    with _scan_lock:
        if not _scan_state["active"]:
            return
        track_id = det["track_id"]
        tier = det.get("anomaly_tier", "Normal")
        curr_score = det.get("anomaly_score", 0)
        curr_summary = det.get("gemini_summary", "")
        curr_behavior = det.get("behavior", "")
        gemini_verified = det.get("gemini_verified", False)

        _scan_state["seen_ids"].add(track_id)

        # Gemini is the definitive authority: if Gemini verified Normal, clear from warnings/alerts
        if gemini_verified and tier == "Normal":
            _scan_state["warning_ids"].discard(track_id)
            _scan_state["alert_ids"].discard(track_id)
        elif tier == "Suspicious":
            _scan_state["warning_ids"].add(track_id)
            _scan_state["alert_ids"].discard(track_id)
        elif tier == "High Concern":
            _scan_state["alert_ids"].add(track_id)
            _scan_state["warning_ids"].discard(track_id)

        tier_weights = {"Normal": 0, "Monitor": 1, "Suspicious": 2, "High Concern": 3}
        existing = _scan_state["chickens"].get(track_id)

        if existing is None:
            _scan_state["chickens"][track_id] = {
                "chicken_id": track_id,
                "anomaly_score": curr_score,
                "anomaly_tier": tier,
                "behavior": curr_behavior,
                "gemini_summary": curr_summary
            }
        else:
            # If Gemini has rendered an authoritative verdict, override prior heuristic scores
            if gemini_verified:
                existing["anomaly_tier"] = tier
                existing["anomaly_score"] = curr_score
            else:
                if curr_score > existing.get("anomaly_score", 0):
                    existing["anomaly_score"] = curr_score
                if tier_weights.get(tier, 0) > tier_weights.get(existing.get("anomaly_tier", "Normal"), 0):
                    existing["anomaly_tier"] = tier
            if curr_summary:
                existing["gemini_summary"] = curr_summary
            if curr_behavior:
                existing["behavior"] = curr_behavior


def send_scan_summary_sms(barn_name, total, warnings, alerts, duration_sec=0.0):
    """Single end-of-scan SMS with warning and alert counts."""
    _, _, climate_str = get_current_climate()
    climate_line = f"Climate: {climate_str}\n" if climate_str and climate_str != "Unavailable" else ""

    barn = (barn_name or "Barn").strip()
    mins = int(round(duration_sec / 60.0)) if duration_sec else 0
    duration_line = f"Duration: {mins} min\n" if mins > 0 else ""

    if alerts > 0:
        action = "Inspect alert birds now"
    elif warnings > 0:
        action = "Review warning birds"
    else:
        action = "No action needed"

    sms_body = (
        f"[PoultrySense SCAN COMPLETE]\n"
        f"Barn: {barn}\n"
        f"Birds scanned: {total}\n"
        f"Warnings: {warnings}\n"
        f"Alerts: {alerts}\n"
        f"{duration_line}"
        f"{climate_line}"
        f"Action: {action}"
    )
    # Scan summaries are user-initiated and bounded, so the 15-minute alert
    # cooldown does not apply — every completed scan gets its own report.
    return _dispatch_sms(sms_body, enforce_cooldown=False)


def scan_stop(barn_name=""):
    """End the scan window, send one summary SMS, and return the tally with per-chicken details."""
    with _scan_lock:
        was_active = _scan_state["active"]
        total = len(_scan_state["seen_ids"])
        warnings = len(_scan_state["warning_ids"])
        alerts = len(_scan_state["alert_ids"])
        started = _scan_state["started_at"]
        chickens_copy = list(_scan_state["chickens"].values())
        _scan_state["active"] = False
        _scan_state["started_at"] = 0.0
        _scan_state["seen_ids"] = set()
        _scan_state["warning_ids"] = set()
        _scan_state["alert_ids"] = set()
        _scan_state["chickens"] = {}

    if not was_active:
        # No /scan/start was seen (older app build) — fall back to the live frame.
        with frame_lock:
            total = latest_status.get("flock_count", 0)
            warnings = latest_status.get("warning_count", 0)
            alerts = latest_status.get("unhealthy_count", 0)
            chickens_copy = [
                {
                    "chicken_id": d["track_id"],
                    "anomaly_score": d.get("anomaly_score", 0),
                    "anomaly_tier": d.get("anomaly_tier", "Normal"),
                    "behavior": d.get("behavior", ""),
                    "gemini_summary": d.get("gemini_summary", "")
                }
                for d in latest_status.get("detections", [])
            ]

    duration = max(0.0, time.time() - started) if started else 0.0

    # Dispatch summary SMS in a background thread so the HTTP /scan/stop response is instantaneous
    def _async_sms():
        try:
            send_scan_summary_sms(barn_name, total, warnings, alerts, duration)
        except Exception as e:
            print(f"[SMS] Notice in async summary SMS: {e}")

    threading.Thread(target=_async_sms, daemon=True).start()

    ill_count = warnings + alerts
    healthy_count = max(0, total - ill_count)
    summary = {
        "total": total,
        "warnings": warnings,
        "alerts": alerts,
        "healthy": healthy_count,
        "illCount": ill_count,
        "duration_sec": round(duration, 1),
        "sms_sent": True,
        "chicken_details": chickens_copy
    }
    print(f"[SCAN] Complete — {total} birds, {warnings} warnings, {alerts} alerts, "
          f"{len(chickens_copy)} detailed bird records")
    return summary


def sync_verdict_to_firebase(verdict_obj):
    """
    Writes JSON diagnostic alert directly to Firebase Realtime Database via REST.
    Stores under active_alerts/chicken_{id}.json and diagnostic_verdicts/latest.json.
    Ensures both 'tier'/'anomaly_tier' and 'reason'/'gemini_summary' are populated for app compatibility.
    """
    if requests is None:
        return False
    payload = dict(verdict_obj)
    cid = payload.get("chicken_id", "unknown")

    # Bridge field names between server and app
    if "anomaly_tier" in payload and "tier" not in payload:
        payload["tier"] = payload["anomaly_tier"]
    elif "tier" in payload and "anomaly_tier" not in payload:
        payload["anomaly_tier"] = payload["tier"]

    if "reason" in payload and "gemini_summary" not in payload:
        payload["gemini_summary"] = payload["reason"]
    elif "gemini_summary" in payload and "reason" not in payload:
        payload["reason"] = payload["gemini_summary"]

    tier = payload.get("tier", "Normal")
    severity = payload.get("severity", "Low")
    is_normal = tier in ["Normal", "Healthy"] or severity in ["Low", "Normal", "Healthy"]

    try:
        # 1. Update active alerts node for live alerts view:
        # If Gemini confirms the chicken is Normal/Healthy, REMOVE it from active_alerts!
        url_alert = f"{FIREBASE_DATABASE_URL}/active_alerts/chicken_{cid}.json"
        if is_normal:
            requests.delete(url_alert, timeout=5)
            print(f"[Firebase] Chicken #{cid} verified Healthy/Normal by Gemini — cleared from active_alerts.")
        else:
            requests.put(url_alert, json=payload, timeout=5)

        # 2. Update diagnostic verdicts node (keep the full record for clinical history)
        url_diag = f"{FIREBASE_DATABASE_URL}/diagnostic_verdicts/chicken_{cid}.json"
        requests.put(url_diag, json=payload, timeout=5)

        # 3. Update latest verdict reference
        url_latest = f"{FIREBASE_DATABASE_URL}/diagnostic_verdicts/latest.json"
        requests.put(url_latest, json=payload, timeout=5)
        is_gemini = payload.get("gemini_evaluated", False) or "Gemini:" in str(payload.get("gemini_summary", ""))
        source_label = "Gemini Cloud Verdict" if is_gemini else "Local Edge Tracker"
        print(f"[Firebase] ✓ Synced active alert ({source_label}) for Chicken #{cid} [{payload.get('anomaly_tier', payload.get('severity'))}]")
        return True
    except Exception as e:
        print(f"[Firebase] ⚠ Failed to sync alert to RTDB: {e}")
        return False

# Backward compatibility alias
sync_alert_to_firebase = sync_verdict_to_firebase


_last_live_alert_sync = {}
def sync_live_alert_debounced(cid, payload):
    """Syncs live suspicious/alert chickens to Firebase active_alerts at most once every 5 seconds per chicken."""
    now = time.time()
    last_sync = _last_live_alert_sync.get(cid, 0.0)
    if now - last_sync >= 5.0:
        _last_live_alert_sync[cid] = now
        def _bg():
            sync_verdict_to_firebase(payload)
        threading.Thread(target=_bg, daemon=True).start()

TRACKER_NAME = os.environ.get("POULTRYSENSE_TRACKER", "botsort").strip().lower()
TRACKER_YAML = os.path.join(SCRIPT_DIR, "pipeline", TRACKER_NAME + ".yaml")
if not os.path.exists(TRACKER_YAML):
    TRACKER_YAML = os.path.join(SCRIPT_DIR, "pipeline", "botsort.yaml")

DET_MODELS = [
    os.path.join(MODELS_DIR, "PoultrySense_ChickenDet_v5_26n.pt"),
    os.path.join(MODELS_DIR, "PoultrySense_ChickenDet_v4_26n.pt"),
    os.path.join(MODELS_DIR, "healthyChicken detector V5.pt"),
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
# Human-readable posture wording sent to Gemini. "rest" is assigned by the
# motion heuristic when a bird has been still for 15 s or more.
POSTURE_LABELS = {
    "peck": "Feeding / pecking",
    "stand": "Standing",
    "walk": "Walking",
    "rest": "Resting / recumbent",
    "recumbent": "Recumbent / down / unable to stand",
}
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
track_last_hist_time = {}   # display_id -> timestamp of last non-coasting position update

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
# Four consecutive frozen observations instead of two: a genuinely still
# chicken (resting, dust-bathing, broody) must not be mistaken for a frozen poster.
CHURN_EPS = 2.0
CHURN_MAX_FROZEN = 4
# Hold the last known box for ~0.4 s of missed detections so a brief detector
# dropout does not blank a chicken's box. Time-based, so changing the detection
# rate does not silently shorten it. The box is frozen, never extrapolated.
COAST_MAX_FRAMES = max(3, int(round(0.4 * DETECTION_MAX_RATE_HZ)))
# Minimum box side as a fraction of frame width. Resolution-independent: 45 px
# on 640 px wide, 90 px on 1280 px, instead of a fixed 45 px everywhere.
MIN_BOX_FRACTION = float(os.environ.get("POULTRYSENSE_MIN_BOX_FRACTION", "0.07"))
MIN_BOX_PX_FLOOR = int(os.environ.get("POULTRYSENSE_MIN_BOX_PX", "24"))
# A classification older than this is ignored instead of being reported forever.
CLASSIFICATION_MAX_AGE_SEC = float(os.environ.get("POULTRYSENSE_CLASSIFICATION_MAX_AGE_SEC", "5.0"))
HEALTH_CLASSIFICATION_MAX_AGE_SEC = float(os.environ.get("POULTRYSENSE_HEALTH_CLASSIFICATION_MAX_AGE_SEC", "20.0"))
# Classifier input sizes in ONE place each. Changing them must also re-export the
# OpenVINO/ONNX model, which is now keyed by size (see resolve_openvino).
CLASSIFIER_IMGSZ = int(os.environ.get("POULTRYSENSE_CLASSIFIER_IMGSZ", "224"))
# Health classifier is trained/exported/inferred at 320 per project spec.
HEALTH_CLASSIFIER_IMGSZ = int(os.environ.get("POULTRYSENSE_HEALTH_CLASSIFIER_IMGSZ", "320"))
# Human/face veto cadence. This is a secondary safety model, so keep it low
# enough that it does not compete with the chicken detector.
VETO_HZ = float(os.environ.get("POULTRYSENSE_VETO_HZ", "2"))
# Appearance (ReID) encoder device. Measured on this machine: CPU 1.8 ms/crop,
# OpenVINO GPU 3.0 ms/crop + 15s compile + 9s first inference — GPU is far
# slower for this small model, so CPU is the default. Set
# POULTRYSENSE_REID_DEVICE=gpu to force OpenVINO GPU (opt-in).
REID_DEVICE = os.environ.get("POULTRYSENSE_REID_DEVICE", "cpu").strip().lower()
DEBUG_TRACK = os.environ.get("POULTRYSENSE_DEBUG_TRACK", "").strip().lower() in {"1", "true", "yes"}
# Secondary classifiers are intentionally bounded. New evidence replaces stale
# work instead of allowing a backlog to consume all available compute.
CLASSIFIER_INTERVAL_SEC = float(os.environ.get("POULTRYSENSE_CLASSIFIER_INTERVAL_SEC", "4.0"))
HEALTH_CLASSIFIER_INTERVAL_SEC = float(os.environ.get("POULTRYSENSE_HEALTH_CLASSIFIER_INTERVAL_SEC", "10.0"))
BEHAVIOR_HISTORY_SAMPLE_SEC = float(os.environ.get("POULTRYSENSE_HISTORY_SAMPLE_SEC", "0.5"))
classification_queue = queue.Queue(maxsize=2)
classification_lock = threading.Lock()
behavior_history_sample_times = {}
behavior_stats_cache = {}
behavior_baseline_cache = {}

# Limit active tracked subjects for ultra-smooth high FPS (supports 6 to 10 chickens)
MAX_TRACKED_CHICKENS = int(os.environ.get("POULTRYSENSE_MAX_TRACKED_CHICKENS", "10"))

# --- Camera Configuration ---
# Camera index: 1 = external USB camera ("HD Camera")
#               0 = built-in laptop camera (fallback)
EXTERNAL_CAMERA_INDEX = int(os.environ.get("POULTRYSENSE_CAM_INDEX", "1"))
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
        rest_cnt = sum(1 for _, b, _, _, _ in q if b in ("stand", "sit", "rest", "sleep", "recumbent"))
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
        # BUT: if stationary_sec >= DAYTIME_RECUMBENT_THRESHOLD_SEC (60s), the immobility is too
        # prolonged — even social resting should not clamp the score, so Gemini can evaluate.
        is_social_rest = (
            (not is_isolated)
            and (stats.get("isolation_pct", 0.0) < 35.0)
            and (stationary_sec < DAYTIME_RECUMBENT_THRESHOLD_SEC)
        )
        is_visually_healthy = (unhealthy_prob < 0.40)

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

        # Ethological Locomotion Reality Check (Weeks et al. 2000, Dawkins et al. 2012):
        # A chicken that is actively moving / foraging (avg_motion >= 2.5 or stationary_sec < 5.0)
        # cannot be in pathological recumbency or paralyzed distress.
        # Clinical Visual Override applies when the chicken is stationary / low-activity (stationary_sec >= 10.0 or motion < 2.0).
        is_actively_moving = (stationary_sec < 5.0 and stats.get("avg_motion", 0.0) >= 2.0)
        if not is_actively_moving:
            if unhealthy_prob >= 0.65:
                raw_score = max(raw_score, 70.0 + (unhealthy_prob - 0.65) * 80.0)
            elif unhealthy_prob >= 0.45:
                raw_score = max(raw_score, 45.0 + (unhealthy_prob - 0.45) * 60.0)
        else:
            # Active foraging / locomotion dampens visual noise from unfamiliar outdoor backdrops
            raw_score = min(raw_score, 25.0)

        total_score = int(round(min(100.0, max(0.0, raw_score))))

        # Cold-Start Dampener: During first 25s of tracking, cap score at 25 ONLY for visually healthy birds.
        # A bird that is visibly paralyzed or down must NEVER be clamped to "Healthy"!
        if observed_sec < 25.0 and unhealthy_prob < 0.40:
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
    Multimodal Gemini 2.5 Flash Health Diagnostic Engine for PoultrySense.

    Architecture:
    1. YOLO/ByteTrack or environmental sensors detect abnormal behaviors (prolonged immobility, lethargic posture,
       flock isolation, or heat/cold stress).
    2. Automatically crops and saves temporary snapshot ('flagged_chicken.jpg').
    3. Global timestamp-based cooldown (15 minutes = 900 seconds) ensures no duplicate queries or SMS alerts across the entire flock,
       preventing duplicate notifications when tracker IDs switch upon exit/re-entry.
    4. Gemini 2.5 Flash evaluates both cropped image and text telemetry with enforced JSON schema.
    5. Saves diagnostic verdict directly to Firebase Realtime Database.
    6. Dispatches Twilio SMS alert if severity is High or Critical, wrapped in try-except.
    """

    def __init__(self):
        self.verdicts = {}              # track_id -> full verdict dict from Gemini
        self.active_alerts = {}         # track_id -> alert dict (for /alerts endpoint)
        self.chicken_summaries = {}     # track_id -> 1-sentence reason (backwards compat)
        # Gemini is an escalation layer, never part of the live frame path.
        # An environment variable or local configuration can still override this.
        self.model_name = "gemini-3.6-flash"
        self.api_key = self._find_api_key()
        self.client = None
        self._init_client()

        # Sequential verdict queue: one Gemini query at a time, no main-loop blocking,
        # no artificial 20 s gap, no global _query_in_progress flag. Every flagged
        # bird gets checked in order; per-bird 15-min cooldown still prevents dupes.
        self._verdict_queue = queue.Queue()
        self._worker_thread = threading.Thread(target=self._verdict_queue_worker, daemon=True)
        self._worker_thread.start()

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
                print(f"[Gemini] ✓ Initialized multimodal diagnostic engine ({self.model_name}).")
            except Exception as e:
                print(f"[Gemini] Client init notice: {e}")
        else:
            if not self.api_key:
                print("[Gemini] Notice: GEMINI_API_KEY not found. Operating in local heuristic-only mode.")
                print("                  Add key to poultrysense_ai/gemini_config.json to enable Gemini multimodal verdicts.")

    def request_verdict_async(self, track_id, stats, baseline, anomaly_info, crop, curr_time, is_isolated, stationary_sec, snapshot_path=None, abnormal_flags=None, behavior_label="stand"):
        """
        Triggers asynchronous multimodal analysis with Gemini.
        Each chicken has its own cooldown; a short system-wide gap keeps calls
        from firing at the same instant.
        """
        # 1. Per-chicken cooldown — one bird's alert never silences the flock.
        if is_track_cooldown_active(track_id, curr_time):
            return

        # 2. Stamp this bird's window now, so the cooldown starts when we ask,
        #    not when Gemini answers.
        record_track_alert(track_id, curr_time)

        # The crop is copied into the background queue. Disk encoding happens in
        # that worker so a flagged event never pauses live detection.
        if snapshot_path is None:
            snapshot_path = os.path.join(SCRIPT_DIR, f"flagged_chicken_{track_id}.jpg")
        snapshot_crop = crop.copy() if crop is not None and crop.size > 0 else None

        # 3. Enqueue for the sequential background worker (non-blocking).
        self._verdict_queue.put((
            track_id, stats, baseline, anomaly_info, snapshot_path, snapshot_crop,
            curr_time, is_isolated, stationary_sec, abnormal_flags, behavior_label
        ))

    def _verdict_queue_worker(self):
        """Single background worker: drains the verdict queue sequentially so every
        flagged bird gets checked without blocking the detection loop."""
        while True:
            try:
                item = self._verdict_queue.get(timeout=1.0)
            except Exception:
                continue
            try:
                (track_id, stats, baseline, anomaly_info, snapshot_path, snapshot_crop,
                 curr_time, is_isolated, stationary_sec, abnormal_flags, behavior_label) = item
                if snapshot_crop is not None:
                    try:
                        cv2.imwrite(snapshot_path, snapshot_crop)
                    except Exception as ex_save:
                        print(f"[Vision Engine] Notice saving flagged snapshot: {ex_save}")
                self._verdict_worker(
                    track_id, stats, baseline, anomaly_info, snapshot_path,
                    curr_time, is_isolated, stationary_sec, abnormal_flags, behavior_label
                )
            except Exception as ex:
                print(f"[Gemini] Verdict worker error: {ex}")
            finally:
                self._verdict_queue.task_done()
            # small polite gap between Gemini calls
            time.sleep(1.0)

    def _verdict_worker(self, track_id, stats, baseline, anomaly_info, snapshot_path, curr_time, is_isolated, stationary_sec, abnormal_flags=None, behavior_label="stand"):
        """
        Background worker: passes cropped image snapshot and telemetry vectors to Gemini,
        enforces schema and saves the verdict to Firebase RTDB. Per-bird SMS is not
        sent here; the farmer gets one summary when the scan completes.
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

            # Multimodal Gemini analysis
            if self.client is not None and os.path.exists(snapshot_path):
                try:
                    with open(snapshot_path, "rb") as f:
                        img_bytes = f.read()

                    image_part = types.Part.from_bytes(data=img_bytes, mime_type="image/jpeg")

                    telemetry_prompt = (
                        f"[POULTRYSENSE MULTIMODAL TELEMETRY LOG]\n"
                        f"- Target Chicken ID: #{track_id}\n"
                        f"- Flagged Anomaly Triggers: {flags_summary}\n"
                        f"- Posture State: {POSTURE_LABELS.get(behavior_label, behavior_label)} "
                        f"(held {stationary_sec:.1f}s)\n"
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

                        print(f"[Gemini] ✓ Verdict for Chicken #{track_id}: {verdict_severity} — {verdict_reason}")

                except Exception as ex:
                    print(f"[Gemini] API error for Chicken #{track_id}: {ex}")

            # Map severity to internal tier and label
            SEV_TO_TIER = {
                "Low": ("Normal", "Healthy"),
                "Medium": ("Monitor", "Monitor"),
                "High": ("Suspicious", "Needs Attention"),
                "Critical": ("High Concern", "Critical Alert")
            }
            v_tier, v_label = SEV_TO_TIER.get(verdict_severity, (tier, tier))

            # Calibrate anomaly score to match Gemini's expert diagnostic verdict
            if verdict_severity == "Low" or v_tier == "Normal":
                calibrated_score = min(score, 15)  # Confirmed healthy/normal
            elif verdict_severity == "Medium" or v_tier == "Monitor":
                calibrated_score = 40  # Monitor tier (30-59)
            elif verdict_severity == "High" or v_tier == "Suspicious":
                calibrated_score = max(65, min(score, 79))  # Suspicious tier (60-79)
            else:  # Critical / High Concern
                calibrated_score = max(85, score)  # High Concern tier (80-100)

            tag_info = get_poultry_tag_info(track_id)

            # Store the verdict in memory
            self.verdicts[track_id] = {
                "tier": v_tier,
                "label": v_label,
                "severity": verdict_severity,
                "anomaly_score": calibrated_score,
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
                "status": f"{v_label} • {tag_info['tag_label']}",
                "severity": verdict_severity,
                "reason": verdict_reason,
                "farmer_action": verdict_farmer_action,
                "recommendation": verdict_farmer_action,
                "anomaly_score": calibrated_score,
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
            if v_tier == "Normal" or verdict_severity == "Low":
                self.active_alerts.pop(track_id, None)
            else:
                self.active_alerts[track_id] = alert_obj

            # 1. Save Gemini's JSON diagnostic verdict directly to Firebase Realtime Database
            sync_verdict_to_firebase(alert_obj)

            # 2. No per-bird SMS here. The farmer gets one summary SMS when the
            #    scan completes (see scan_stop), so a noisy minute cannot spam them.
            # 3. The cooldown was stamped when the query launched, so the window
            #    starts at request time rather than at response time.

        finally:
            pass

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



def classification_worker(beh_model, hlth_model, beh_imgsz=None, hlth_imgsz=None):
    """Background worker that continuously classifies chicken health and behavior
    on a secondary core without blocking the video tracking loop."""
    if beh_imgsz is None:
        beh_imgsz = CLASSIFIER_IMGSZ
    if hlth_imgsz is None:
        hlth_imgsz = HEALTH_CLASSIFIER_IMGSZ

    while True:
        try:
            disp_id, crop, run_behavior, run_health = classification_queue.get(timeout=1.0)
        except Exception:
            continue

        b_label = None
        unhealthy_prob = None

        if run_behavior and beh_model is not None:
            try:
                b_res = beh_model.predict(source=crop, imgsz=beh_imgsz, verbose=False)[0]
                b_label = BEHAVIOR_NAMES.get(int(b_res.probs.top1), "stand")
            except Exception as ex_beh:
                print(f"[CLASSIFY] Behavior inference failed: {ex_beh}")

        if run_health and hlth_model is not None:
            try:
                h_res = hlth_model.predict(source=crop, imgsz=hlth_imgsz, verbose=False)[0]
                # In PoultrySense_HealthClassifier: index 0 is Healthy, index 1 is Unhealthy
                if h_res.probs is not None and len(h_res.probs.data) >= 2:
                    unhealthy_prob = float(h_res.probs.data[1])
            except Exception as ex_hlth:
                print(f"[CLASSIFY] Health inference failed: {ex_hlth}")

        # Never write a fabricated "healthy" result on failure: leave the
        # previous entry in place and let CLASSIFICATION_MAX_AGE_SEC expire it.
        if b_label is None and unhealthy_prob is None:
            continue

        with classification_lock:
            previous = track_classifications.get(disp_id, {})
            updated_at = time.time()
            track_classifications[disp_id] = {
                "behavior": b_label if b_label is not None else previous.get("behavior", "stand"),
                "unhealthy_prob": (
                    unhealthy_prob if unhealthy_prob is not None
                    else previous.get("unhealthy_prob", 0.0)
                ),
                "behavior_time": updated_at if b_label is not None else previous.get("behavior_time", 0.0),
                "health_time": updated_at if unhealthy_prob is not None else previous.get("health_time", 0.0),
                "time": updated_at
            }



# --- Threaded Camera Reader for 30 FPS capture ---
# Camera backends in order of preference.
#
# MSMF first because it is the only Windows backend that implements
# CAP_PROP_BUFFERSIZE, and a buffer of 1 is what stops the driver handing back
# frames that are already several frames old. DSHOW never implemented that
# property (get() returns -1, set() returns False), so it falls back to a
# driver-managed queue that we can only flush at open time.
# Camera backends in order of preference. MSMF first (buffer control), DSHOW
# second (higher resolutions on some cameras). Override with
# POULTRYSENSE_CAMERA_BACKEND=DSHOW (or MSMF/ANY) to force a backend.
_forced_backend = os.environ.get("POULTRYSENSE_CAMERA_BACKEND", "").strip().upper()
if _forced_backend in ("MSMF", "DSHOW", "ANY"):
    CAMERA_BACKEND_PREFERENCE = ((_forced_backend, "CAP_" + _forced_backend),)
else:
    CAMERA_BACKEND_PREFERENCE = (
        ("MSMF", "CAP_MSMF"),
        ("DSHOW", "CAP_DSHOW"),
        ("ANY", "CAP_ANY"),
    )


def fourcc_to_str(value):
    """Decode an OpenCV FOURCC integer into its four-character code.

    Returns an empty string when the backend does not report a codec.
    """
    try:
        value = int(value)
    except (TypeError, ValueError):
        return ""
    if value <= 0:
        return ""
    code = "".join(chr((value >> (8 * i)) & 0xFF) for i in range(4))
    return code if code.isprintable() and code.strip() else ""


def next_backoff(current, maximum=5.0):
    """Exponential backoff with a ceiling so a dead camera cannot spin."""
    return min(max(0.05, float(current) * 2.0), float(maximum))


def configure_capture(cap, width, height, fps, want_mjpg=True, flush_frames=3):
    """Apply the requested capture mode and report what the driver accepted.

    ``VideoCapture.set()`` on Windows returns ``True`` for any *known*
    property even when the driver rejects the value, so every request is
    verified by reading the property back.

    ``flush_frames`` discards frames the driver queued during negotiation.
    It is skipped on reconnect, where a half-initialised device can block
    indefinitely inside ``grab()``.
    """
    def _get(prop, default=0.0):
        try:
            value = cap.get(prop)
            return default if value is None else float(value)
        except Exception:
            return default

    def _set(prop, value):
        try:
            return bool(cap.set(prop, value))
        except Exception:
            return False

    # FOURCC must be negotiated before width/height: UVC drivers lock the
    # supported format list once a resolution has been selected.
    if want_mjpg:
        _set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))

    _set(cv2.CAP_PROP_FRAME_WIDTH, width)
    _set(cv2.CAP_PROP_FRAME_HEIGHT, height)
    _set(cv2.CAP_PROP_FPS, fps)

    # Buffer control: -1 means the backend has no such property at all.
    buffer_ok = _get(cv2.CAP_PROP_BUFFERSIZE, -1.0) == 1.0
    if not buffer_ok:
        _set(cv2.CAP_PROP_BUFFERSIZE, 1)
        buffer_ok = _get(cv2.CAP_PROP_BUFFERSIZE, -1.0) == 1.0

    # Auto-exposure: 0.25 = automatic, 0.75 = manual. Only touch it when the
    # driver actually reports the property, otherwise the request is a no-op
    # that used to be logged as if it had worked.
    auto_exposure = _get(cv2.CAP_PROP_AUTO_EXPOSURE, -1.0)
    exposure_set = None
    if auto_exposure >= 0.0:
        _set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.25)
        exposure_set = _get(cv2.CAP_PROP_AUTO_EXPOSURE, -1.0)

    _set(cv2.CAP_PROP_BRIGHTNESS, 128)
    _set(cv2.CAP_PROP_CONTRAST, 40)

    # Flush whatever the driver queued while the format was being negotiated.
    for _ in range(max(0, int(flush_frames))):
        try:
            cap.grab()
        except Exception:
            break

    codec = fourcc_to_str(_get(cv2.CAP_PROP_FOURCC, 0.0))
    return {
        "codec": codec,
        "mjpg_requested": bool(want_mjpg),
        "mjpg_applied": codec.upper() == "MJPG",
        "width": int(_get(cv2.CAP_PROP_FRAME_WIDTH, 0.0)),
        "height": int(_get(cv2.CAP_PROP_FRAME_HEIGHT, 0.0)),
        "fps": _get(cv2.CAP_PROP_FPS, 0.0),
        "buffer_control": buffer_ok,
        "auto_exposure": exposure_set,
    }


class ThreadedCamera:
    """Reads frames from the camera in a background thread so capture
    is decoupled from the (slower) YOLO inference loop.

    The reader keeps only the newest frame, reopens the device after a run of
    failed reads instead of spinning at 100% CPU, and reports the mode the
    driver actually negotiated rather than the one that was requested.
    """

    # Consecutive failed reads before the device is considered dead.
    RECONNECT_AFTER = 5
    MAX_BACKOFF_SEC = 5.0
    # Give the driver time to tear the old stream down before reopening.
    RECONNECT_SETTLE_SEC = 0.5

    def __init__(self, camera_index=EXTERNAL_CAMERA_INDEX,
                 width=None, height=None, fps=None):
        self._buffer = FrameBuffer()
        self._ret = False
        self._running = False
        self._thread = None
        self._cap_lock = threading.Lock()
        self._last_frame_time = 0.0
        self._stats = {"frames": 0, "read_failures": 0, "reconnects": 0}

        self._preferred_index = camera_index
        self.index = camera_index
        self.width = int(width if width is not None
                         else os.environ.get("POULTRYSENSE_CAM_WIDTH", "640"))
        self.height = int(height if height is not None
                          else os.environ.get("POULTRYSENSE_CAM_HEIGHT", "480"))
        self.fps = float(fps if fps is not None
                         else os.environ.get("POULTRYSENSE_CAM_FPS", "30"))
        self.want_mjpg = os.environ.get(
            "POULTRYSENSE_CAM_CODEC", "MJPG"
        ).strip().upper() != "NONE"

        self.cap = None
        self.backend_name = "?"
        self.mode = {}
        self._open(camera_index)

    def _index_candidates(self, camera_index):
        candidates = [camera_index]
        if camera_index != FALLBACK_CAMERA_INDEX:
            candidates.append(FALLBACK_CAMERA_INDEX)
        return candidates

    def _try_open(self, index):
        """Open ``index`` on the first backend that yields a working capture."""
        for backend_name, attr in CAMERA_BACKEND_PREFERENCE:
            api = getattr(cv2, attr, None)
            if api is None:
                continue
            cap = cv2.VideoCapture(index, api)
            if not cap.isOpened():
                cap.release()
                continue
            mode = configure_capture(cap, self.width, self.height, self.fps, self.want_mjpg)
            self.cap = cap
            self.index = index
            self.backend_name = backend_name
            self.mode = mode
            self._log_opened(index, backend_name, mode)
            return True
        return False

    def _open(self, camera_index):
        for index in self._index_candidates(camera_index):
            if self._try_open(index):
                return True
        print("[ERROR] No camera could be opened!")
        return False

    def _log_opened(self, index, backend_name, mode):
        codec = mode.get("codec") or "unreported"
        if mode.get("mjpg_applied"):
            codec_note = f"codec={codec} (MJPG confirmed)"
        elif mode.get("codec"):
            codec_note = f"codec={codec} (driver refused MJPG; using native format)"
        elif mode.get("mjpg_requested"):
            codec_note = "codec=unreported (backend does not expose FOURCC)"
        else:
            codec_note = "codec=unreported"

        if mode.get("buffer_control"):
            buffer_note = "buffer=1 (no stale frames)"
        else:
            buffer_note = "buffer=driver-managed (flushed at open)"

        exposure = mode.get("auto_exposure")
        exposure_note = (
            "auto-exposure=on" if exposure is not None and exposure <= 0.5
            else "auto-exposure=manual" if exposure is not None
            else "auto-exposure=unsupported"
        )

        print(
            f"[INFO] Camera opened: index={index}, backend={backend_name}, "
            f"{codec_note}, res={mode.get('width')}x{mode.get('height')}, "
            f"fps={float(mode.get('fps') or 0.0):.0f}, {buffer_note}, {exposure_note}"
        )

        if index != self._preferred_index:
            print(
                f"[WARN] Preferred camera {self._preferred_index} unavailable; "
                f"using camera {index}. Reconnect the coop camera and restart to switch back."
            )

    def start(self):
        if self._running:
            return self
        self._running = True
        self._thread = threading.Thread(
            target=self._reader_loop, name="camera-reader", daemon=True
        )
        self._thread.start()
        return self

    def _reader_loop(self):
        failures = 0
        backoff = 0.25

        while self._running:
            cap = self.cap
            ret, frame = (False, None) if cap is None else cap.read()

            if ret and frame is not None:
                failures = 0
                backoff = 0.25
                self._ret = True
                self._last_frame_time = time.monotonic()
                self._stats["frames"] += 1
                self._buffer.publish(frame)
                continue

            # A failed read must never spin the CPU: back off, then reopen.
            failures += 1
            self._ret = False
            self._stats["read_failures"] += 1
            if failures >= self.RECONNECT_AFTER:
                failures = 0
                if self._reconnect():
                    # Device is back: read again immediately instead of
                    # sleeping out the backoff we accumulated while it was dead.
                    backoff = 0.25
                    continue
            time.sleep(backoff)
            backoff = next_backoff(backoff, self.MAX_BACKOFF_SEC)

    def _reconnect(self):
        with self._cap_lock:
            old = self.cap
            self.cap = None
        if old is not None:
            try:
                old.release()
            except Exception:
                pass

        self._stats["reconnects"] += 1
        print(
            f"[WARN] Camera {self.index} stopped delivering frames; "
            f"reopening (attempt {self._stats['reconnects']})."
        )
        time.sleep(self.RECONNECT_SETTLE_SEC)

        for backend_name, attr in CAMERA_BACKEND_PREFERENCE:
            api = getattr(cv2, attr, None)
            if api is None:
                continue
            cap = cv2.VideoCapture(self.index, api)
            if not cap.isOpened():
                cap.release()
                continue
            mode = configure_capture(
                cap, self.width, self.height, self.fps, self.want_mjpg,
                flush_frames=0,
            )
            with self._cap_lock:
                self.cap = cap
            self.backend_name = backend_name
            self.mode = mode
            self._log_opened(self.index, backend_name, mode)
            return True
        return False

    def info(self):
        """Report the negotiated mode and reader health for /status."""
        since_frame = None
        if self._last_frame_time:
            since_frame = round(time.monotonic() - self._last_frame_time, 2)
        return {
            "index": self.index,
            "backend": self.backend_name,
            "codec": self.mode.get("codec", ""),
            "mjpg_applied": bool(self.mode.get("mjpg_applied")),
            "width": self.mode.get("width", 0),
            "height": self.mode.get("height", 0),
            "fps": round(float(self.mode.get("fps") or 0.0), 1),
            "buffer_control": bool(self.mode.get("buffer_control")),
            "auto_exposure": self.mode.get("auto_exposure"),
            "frames": self._stats["frames"],
            "read_failures": self._stats["read_failures"],
            "reconnects": self._stats["reconnects"],
            "seconds_since_frame": since_frame,
            "reader_alive": bool(self._thread and self._thread.is_alive()),
        }

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
        # A buffered frame is valid even if the most recent read failed, so
        # report success on the frame rather than on the last device read.
        return True, frame.copy(), generation

    def isOpened(self):
        with self._cap_lock:
            return self.cap is not None and self.cap.isOpened()

    def release(self):
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None
        with self._cap_lock:
            cap = self.cap
            self.cap = None
        if cap is not None:
            try:
                cap.release()
            except Exception:
                pass


def get_model_path(candidates):
    for path in candidates:
        if os.path.exists(path):
            return path
    return None


_GPU_NAMES_CACHE = None


def get_gpu_names():
    """Return display-adapter names on Windows (best effort, cached, never fatal)."""
    global _GPU_NAMES_CACHE
    if _GPU_NAMES_CACHE is not None:
        return _GPU_NAMES_CACHE

    names = []
    if os.name == "nt":
        try:
            import subprocess
            result = subprocess.run(
                [
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name",
                ],
                capture_output=True,
                text=True,
                timeout=8,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            names = [line.strip() for line in result.stdout.splitlines() if line.strip()]
        except Exception:
            names = []

    _GPU_NAMES_CACHE = names
    return names


def has_directml():
    """True when the installed ONNX Runtime build exposes the DirectML provider."""
    try:
        import onnxruntime as ort
        return "DmlExecutionProvider" in ort.get_available_providers()
    except Exception:
        return False


def get_inference_backend():
    """Pick the best detector backend automatically.

    Order: NVIDIA CUDA -> AMD DirectML -> Intel OpenVINO GPU -> DirectML -> CPU.
    Set POULTRYSENSE_BACKEND to force one of: cuda, directml, openvino, cpu.

    Returns:
        (backend, device, model_format)
    """
    forced = os.environ.get("POULTRYSENSE_BACKEND", "").strip().lower()
    if forced in {"cuda", "nvidia"}:
        return "cuda", 0, "pt"
    if forced in {"directml", "dml", "onnx", "amd"}:
        return "directml", "cpu", "onnx"
    if forced in {"openvino", "intel"}:
        return "openvino", "intel:gpu", "openvino"
    if forced in {"cpu"}:
        return "cpu", "cpu", "openvino"

    # 1. NVIDIA CUDA (PyTorch)
    try:
        import torch
        if torch.cuda.is_available():
            gpu_name = torch.cuda.get_device_name(0)
            print(f"[AI ENGINE] NVIDIA GPU detected! CUDA acceleration active ({gpu_name}).")
            return "cuda", 0, "pt"
    except Exception:
        pass

    # 2. AMD Radeon via DirectML (Windows, ONNX Runtime DirectML build)
    dml = has_directml()
    if dml:
        names = get_gpu_names()
        amd = any(("amd" in n.lower() or "radeon" in n.lower()) for n in names)
        if amd:
            print(f"[AI ENGINE] AMD GPU detected ({', '.join(names)})! DirectML active.")
            return "directml", "cpu", "onnx"

    # 3. Intel GPU via OpenVINO (Intel UHD / Iris / Arc)
    try:
        import openvino as ov
        core = ov.Core()
        if "GPU" in core.available_devices:
            print("[AI ENGINE] Intel GPU detected! OpenVINO GPU active.")
            return "openvino", "intel:gpu", "openvino"
    except Exception:
        pass

    # 4. Any other DirectX 12 GPU
    if dml:
        print("[AI ENGINE] DirectML GPU active.")
        return "directml", "cpu", "onnx"

    print("[AI ENGINE] OpenVINO AVX2 Engine active (CPU fallback).")
    return "cpu", "cpu", "openvino"


def get_execution_device():
    """Backward-compatible wrapper: returns only the device for the chosen backend."""
    return get_inference_backend()[1]


def enable_directml_providers():
    """Teach Ultralytics' ONNX backend to run on the DirectML execution provider.

    Ultralytics only selects CUDA/CoreML/CPU, so without this the RX 580 would
    silently run the ONNX detector on the CPU. Safe no-op when DirectML is absent.
    """
    try:
        import onnxruntime as ort
        from ultralytics.nn.autobackend import ONNXBackend
    except Exception as e:
        print(f"[AI ENGINE] DirectML unavailable ({e}); ONNX will run on CPU.")
        return False

    if "DmlExecutionProvider" not in ort.get_available_providers():
        print("[AI ENGINE] DirectML provider not installed; ONNX will run on CPU.")
        return False
    if getattr(ONNXBackend, "_poultrysense_dml", False):
        return True

    original_load_model = ONNXBackend.load_model

    def load_model_with_dml(self, weight):
        original_load_model(self, weight)
        try:
            self.session = ort.InferenceSession(
                str(weight),
                providers=["DmlExecutionProvider", "CPUExecutionProvider"],
            )
            self.output_names = [x.name for x in self.session.get_outputs()]
            print("[AI ENGINE] DirectML provider active for ONNX inference.")
        except Exception as e:
            print(f"[AI ENGINE] DirectML session failed ({e}); using CPU.")

    ONNXBackend.load_model = load_model_with_dml
    ONNXBackend._poultrysense_dml = True
    return True


def resolve_accelerated_model(pt_path, imgsz=640, precision="fp32"):
    """Exports and resolves the fastest hardware-accelerated model.

    1. OpenVINO export directory (*_openvino_model) for high-speed AVX2 execution (~32ms/frame).
    2. Fallback: PyTorch model (*.pt)."""
    return resolve_openvino(pt_path, imgsz=imgsz, precision=precision)


def _imgsz_marker_path(export_path):
    """Sidecar file recording the input size and precision an export was built for."""
    return export_path.rstrip("\\/") + ".poultysense_imgsz"


def _read_export_marker(export_path):
    """Return ``(imgsz, precision)`` from the sidecar, or ``(None, None)``.

    Legacy markers contain only the size (``"640"``); those were always FP32.
    """
    try:
        with open(_imgsz_marker_path(export_path), "r", encoding="utf-8") as fh:
            raw = fh.read().strip()
    except Exception:
        return None, None
    size_s, _, prec = raw.partition(":")
    try:
        size = int(size_s)
    except ValueError:
        return None, None
    return size, (prec.strip().lower() or "fp32")


def _export_imgsz_matches(export_path, imgsz):
    """True only when the export was built for exactly this input size.

    Without this check a cached 224 export is silently reused for a 320
    request, which is how a model resize can appear to work and change nothing.
    """
    size, _ = _read_export_marker(export_path)
    return size == int(imgsz)


def _export_matches(export_path, imgsz, precision="fp32"):
    """True only when the export matches BOTH input size and precision.

    Size alone is not enough: an FP32 export silently reused for an INT8
    request (or the reverse) would make a benchmark measure the wrong model.
    """
    size, prec = _read_export_marker(export_path)
    return size == int(imgsz) and prec == precision.lower()


def _mark_export(export_path, imgsz, precision="fp32"):
    try:
        with open(_imgsz_marker_path(export_path), "w", encoding="utf-8") as fh:
            fh.write(f"{int(imgsz)}:{precision.lower()}")
    except Exception as exc:
        print(f"[OpenVINO] Notice writing export marker: {exc}")


def _mark_export_imgsz(export_path, imgsz):
    """Backwards-compatible wrapper that marks an FP32 export."""
    _mark_export(export_path, imgsz, "fp32")


def _write_calib_yaml(image_dir):
    """Wrap a folder of images in a minimal YOLO data.yaml for calibration.

    Unlabelled images are fine: INT8 calibration only needs input activations,
    and Ultralytics tolerates missing label files in a non-augmented split.
    """
    import tempfile
    abs_dir = os.path.abspath(image_dir).replace("\\", "/")
    fd, path = tempfile.mkstemp(prefix="poultysense_calib_", suffix=".yaml")
    os.close(fd)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(f'path: "{abs_dir}"\ntrain: .\nval: .\nnc: 1\nnames: [\'chicken\']\n')
    return path


def _resolve_calibration_data(calib_data=None, calib_dir=None, bundled=None):
    """Return a YOLO dataset YAML usable for INT8 calibration, or None.

    Resolution order:
      1. ``POULTRYSENSE_CALIB_DATA`` — an explicit YOLO data.yaml (recommended).
      2. ``POULTRYSENSE_CALIB_DIR``  — a folder of representative images.
      3. The bundled chicken dataset, which ships with real labels.
    """
    if calib_data is None:
        calib_data = CALIB_DATA
    if calib_dir is None:
        calib_dir = CALIB_DIR
    if bundled is None:
        bundled = os.path.join(SCRIPT_DIR, "datasets", "chicken_det", "data.yaml")

    if calib_data:
        return calib_data if os.path.isfile(calib_data) else None
    if calib_dir and os.path.isdir(calib_dir):
        return _write_calib_yaml(calib_dir)
    if bundled and os.path.isfile(bundled):
        return bundled
    return None


def _supports_quantize_kwarg():
    """True when the installed Ultralytics uses ``quantize=`` instead of ``int8=``."""
    try:
        from ultralytics.cfg import DEFAULT_CFG_DICT
        return "quantize" in DEFAULT_CFG_DICT
    except Exception:
        return False


def _nncf_available():
    """True when NNCF is installed; Ultralytics needs it for OpenVINO INT8.

    Without NNCF the export raises, and a raised export used to leave the app
    with no OpenVINO model at all. Check first so INT8 can degrade to FP32
    instead of failing.
    """
    try:
        import importlib.util
        return importlib.util.find_spec("nncf") is not None
    except Exception:
        return False


def _openvino_export_kwargs(imgsz, precision, calib_data, nncf_ok=None):
    """Build ``YOLO.export()`` kwargs for the requested precision.

    Returns ``(kwargs, effective_precision)``. INT8 degrades to FP32 when no
    calibration dataset is available or NNCF (the quantizer Ultralytics uses)
    is missing, rather than shipping an uncalibrated model or crashing.
    """
    if nncf_ok is None:
        nncf_ok = _nncf_available()
    kwargs = {"format": "openvino", "imgsz": imgsz}
    if precision != "int8" or not calib_data or not nncf_ok:
        return kwargs, "fp32"
    kwargs["data"] = calib_data
    if _supports_quantize_kwarg():
        kwargs["quantize"] = 8
    else:
        kwargs["int8"] = True
    return kwargs, "int8"


def _park_export(export_path):
    """Move a stale export aside instead of deleting it, then return nothing.

    Old IRs are kept (renamed) so they can be restored, per project policy.
    Returns the parked path (or None) so a failed rebuild can restore it.
    """
    import shutil
    n = 1
    while os.path.exists(f"{export_path}_backup{n}"):
        n += 1
    target = f"{export_path}_backup{n}"
    try:
        shutil.move(export_path, target)
        print(f"[OpenVINO] Parked stale export as {os.path.basename(target)} (kept, not deleted)")
        return target
    except Exception as exc:
        print(f"[OpenVINO] Notice parking stale export: {exc}")
        return None


def _restore_export(parked_path, export_path):
    """Put a parked export back after a failed rebuild.

    Without this, one bad export (e.g. INT8 without NNCF) leaves the app with
    no accelerated model and it silently drops to PyTorch.
    """
    import shutil
    if not parked_path or not os.path.isdir(parked_path):
        return False
    if os.path.exists(export_path):
        # A half-written export can be left behind by the failed attempt.
        failed = f"{export_path}_failed"
        try:
            shutil.move(export_path, failed)
            print(f"[OpenVINO] Kept failed partial export as {os.path.basename(failed)}")
        except Exception as exc:
            print(f"[OpenVINO] Notice removing partial export: {exc}")
            return False
    try:
        shutil.move(parked_path, export_path)
        print(f"[OpenVINO] Restored previous export: {export_path}")
        return True
    except Exception as exc:
        print(f"[OpenVINO] Notice restoring previous export: {exc}")
        return False


def resolve_openvino(pt_path, imgsz=640, precision="fp32"):
    """Resolves OpenVINO export format if supported, else returns PyTorch .pt path.

    The export cache is keyed by input size AND precision, so asking for INT8
    never silently returns a cached FP32 graph (or vice versa).
    """
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
    calib_path = _resolve_calibration_data() if precision == "int8" else None
    # Decide the precision BEFORE touching the cache. A missing NNCF or
    # calibration set must degrade to FP32 quietly, not park a working export.
    export_kwargs, effective_precision = _openvino_export_kwargs(
        imgsz, precision, calib_path
    )
    if precision == "int8" and effective_precision != "int8":
        if not _nncf_available():
            print(
                "[OpenVINO] INT8 requested but 'nncf' is not installed; using FP32 "
                'instead. Install it with: venv\\Scripts\\pip install "nncf>=2.14.0,<3.0.0"'
            )
        else:
            print(
                "[OpenVINO] INT8 requested but no calibration dataset was found; "
                "using FP32 instead. Set POULTRYSENSE_CALIB_DATA or "
                "POULTRYSENSE_CALIB_DIR."
            )
    parked = None
    try:
        from ultralytics import YOLO
        abs_pt = os.path.abspath(pt_path)
        stem = os.path.splitext(os.path.basename(abs_pt))[0] + "_openvino_model"
        beside = os.path.join(os.path.dirname(abs_pt), stem)
        if os.path.isdir(beside):
            if _export_matches(beside, imgsz, effective_precision):
                print(
                    f"[OpenVINO] Using cached model export: {beside} "
                    f"(imgsz={imgsz}, precision={effective_precision})"
                )
                return beside
            print(
                f"[OpenVINO] Cached export {os.path.basename(beside)} was NOT built for "
                f"imgsz={imgsz} precision={effective_precision}; rebuilding."
            )
            parked = _park_export(beside)

        print(
            f"[OpenVINO] Exporting {os.path.basename(abs_pt)} "
            f"(imgsz={imgsz}, precision={effective_precision})..."
        )
        produced = YOLO(abs_pt).export(**export_kwargs)
        # Ultralytics names quantized exports <stem>_int8_openvino_model, so the
        # canonical path is not the only place the result can land. Trust the
        # returned path, then normalise whatever we find to `beside`.
        int8_stem = stem.replace("_openvino_model", "_int8_openvino_model")
        produced_dir = str(produced).rstrip("\\/") if produced else ""
        candidates = [
            beside,
            produced_dir,
            os.path.join(os.path.dirname(abs_pt), int8_stem),
            os.path.join(os.getcwd(), stem),
            os.path.join(os.getcwd(), int8_stem),
        ]
        for candidate in candidates:
            if candidate and os.path.isdir(candidate):
                if os.path.normpath(candidate) != os.path.normpath(beside):
                    if os.path.isdir(beside):
                        shutil.rmtree(beside)
                    shutil.move(candidate, beside)
                _mark_export(beside, imgsz, effective_precision)
                print(f"[OpenVINO] Export ready: {beside}")
                return beside
        raise RuntimeError(
            "export finished but produced no OpenVINO directory for "
            f"{os.path.basename(abs_pt)}"
        )
    except Exception as e:
        print(f"[AI ENGINE] OpenVINO notice ({e}); using native PyTorch .pt engine.")
        # Never leave the app worse off than before the attempt.
        if parked:
            _restore_export(parked, beside)
    finally:
        # A calibration YAML generated for a folder of images is throwaway.
        if calib_path and os.path.basename(calib_path).startswith("poultysense_calib_"):
            try:
                os.remove(calib_path)
            except OSError:
                pass
    return pt_path


def resolve_onnx(pt_path, imgsz=640):
    """Resolve an ONNX detector beside the .pt weights, exporting once if missing.

    DirectML needs an ONNX graph; PyTorch has no AMD GPU path on Windows.
    The export is keyed by input size for the same reason as OpenVINO.
    """
    if not pt_path or not pt_path.endswith(".pt"):
        return pt_path

    onnx_path = os.path.splitext(pt_path)[0] + ".onnx"
    if os.path.exists(onnx_path):
        if _export_imgsz_matches(onnx_path, imgsz):
            return onnx_path
        print(f"[ONNX] Cached graph was NOT built for imgsz={imgsz}; rebuilding.")
        _park_export(onnx_path)

    try:
        from ultralytics import YOLO
        print(f"[ONNX] Exporting {os.path.basename(pt_path)} (imgsz={imgsz})...")
        exported = YOLO(pt_path).export(
            format="onnx", imgsz=imgsz, dynamic=False, simplify=True, opset=12
        )
        exported = str(exported)
        if os.path.exists(exported):
            _mark_export_imgsz(exported, imgsz)
            print(f"[ONNX] Export ready: {exported}")
            return exported
    except Exception as e:
        print(f"[AI ENGINE] ONNX export notice ({e}); using native PyTorch .pt engine.")
    return pt_path


class OpenVINOReIDEncoder:
    """Appearance encoder on the Intel iGPU via OpenVINO.

    Drop-in replacement for ultralytics' ReID: same (img, dets_xywh) signature
    and the same RGB /255 / 224 bilinear preprocessing, so switching device
    cannot change association behaviour. Used when POULTRYSENSE_REID_DEVICE=gpu.
    """

    def __init__(self, model_path, imgsz=224, device="GPU", batch_size=16):
        import openvino as ov
        import torch
        from ultralytics.utils.ops import xywh2xyxy

        self.imgsz = int(imgsz)
        self.batch_size = int(batch_size)
        self._xywh2xyxy = xywh2xyxy
        self.device = device

        core = ov.Core()
        model = core.read_model(str(model_path))
        try:
            model.reshape([-1, 3, self.imgsz, self.imgsz])
        except Exception:
            pass
        self.compiled = core.compile_model(model, device)
        self.output = self.compiled.output(0)
        print(f"[ReID] OpenVINO encoder ready on {device} (imgsz={self.imgsz})")

    def __call__(self, img, dets):
        import torch
        import numpy as np
        import cv2

        dets = np.asarray(dets)
        if dets.size == 0:
            return []
        boxes = dets[:, :4]
        xyxy = self._xywh2xyxy(torch.from_numpy(boxes.astype(np.float32))).cpu()
        h, w = img.shape[:2]

        crops = []
        keep = []
        for b in xyxy:
            x1, y1, x2, y2 = [float(v) for v in b.tolist()]
            x1 = max(0, min(w - 1, int(round(x1))))
            y1 = max(0, min(h - 1, int(round(y1))))
            x2 = max(x1 + 1, min(w, int(round(x2))))
            y2 = max(y1 + 1, min(h, int(round(y2))))
            if x2 <= x1 or y2 <= y1:
                keep.append(False)
                crops.append(None)
                continue
            crop = img[y1:y2, x1:x2]
            if crop.size == 0:
                keep.append(False)
                crops.append(None)
                continue
            keep.append(True)
            crops.append(cv2.resize(crop, (self.imgsz, self.imgsz),
                                    interpolation=cv2.INTER_LINEAR))

        valid = [c for c, k in zip(crops, keep) if k]
        if not valid:
            return [None] * len(boxes)

        batch = np.zeros((len(valid), 3, self.imgsz, self.imgsz), dtype=np.float32)
        for i, c in enumerate(valid):
            rgb = cv2.cvtColor(c, cv2.COLOR_BGR2RGB)
            t = rgb.transpose(2, 0, 1).astype(np.float32) / 255.0
            batch[i] = t

        out = self.compiled(batch)[self.output]
        out = np.asarray(out)
        feats = iter(out)
        result = []
        for k in keep:
            if k:
                result.append(next(feats).astype(np.float32))
            else:
                result.append(None)
        return result


def install_reid_device(device, model_path=None):
    """Point BoT-SORT's appearance encoder at the requested device.

    Ultralytics builds the encoder with torch.device(...), which rejects
    OpenVINO device strings, so the GPU path is installed by replacing the
    build_encoder symbol that bot_sort.py already imported.
    """
    device = (device or "cpu").strip().lower()
    if device in ("cpu", "torch", ""):
        print("[ReID] Appearance encoder: CPU (PyTorch)")
        return "cpu"

    ov_device = "GPU.0" if device in ("gpu", "openvino") else device.upper()
    try:
        import openvino  # noqa: F401
        from ultralytics.trackers import bot_sort
    except Exception as exc:
        print(f"[ReID] OpenVINO unavailable ({exc}); using CPU encoder.")
        return "cpu"

    resolved = model_path
    if resolved is None:
        resolved = os.path.join(SCRIPT_DIR, "yolo26n-reid.onnx")
    if not os.path.exists(resolved):
        # fall back to whatever ultralytics ships
        try:
            from ultralytics.utils.downloads import attempt_download_asset
            resolved = attempt_download_asset("yolo26n-reid.onnx")
        except Exception:
            pass

    original = getattr(bot_sort, "build_encoder", None)

    def _factory(with_reid, model, _device=None):
        if not with_reid:
            return None
        path = resolved if (model is None or str(model).endswith(".onnx") is False) else model
        if not os.path.exists(str(path)):
            path = resolved
        try:
            enc = OpenVINOReIDEncoder(path, imgsz=224, device=ov_device)
            return enc
        except Exception as exc:
            print(f"[ReID] GPU encoder failed ({exc}); falling back to CPU.")
            if original is not None:
                return original(with_reid, model, "cpu")
            return None

    bot_sort.build_encoder = _factory
    print(f"[ReID] Appearance encoder routed to OpenVINO {ov_device}")
    return f"openvino:{ov_device}"


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


# --- Human / Face Veto (runs on its own thread) ---
# Shared between the veto worker and the detection loop. Kept tiny and
# copy-on-write: the worker replaces the lists, the loop reads them.
veto_state_lock = threading.Lock()
veto_state = {"person_boxes": [], "face_boxes": [], "updated_at": 0.0}
PERSON_CONTAINMENT_THRESH = float(os.environ.get("POULTRYSENSE_PERSON_CONTAINMENT", "0.60"))
FACE_CONTAINMENT_THRESH = float(os.environ.get("POULTRYSENSE_FACE_CONTAINMENT", "0.35"))


def box_is_human(box, person_boxes, face_boxes,
                 person_thresh=None, face_thresh=None):
    """True when a "chicken" box is really a person or a face.

    The old rule skipped the check entirely whenever the chicken box was larger
    than 10% of the person box, so a big false box on a person's torso was never
    vetoed. This compares containment in BOTH directions instead, so a box
    covering a person (inter/person_area) is rejected just like a small box
    sitting inside one (inter/chicken_area).
    """
    if person_thresh is None:
        person_thresh = PERSON_CONTAINMENT_THRESH
    if face_thresh is None:
        face_thresh = FACE_CONTAINMENT_THRESH

    bx1, by1, bx2, by2 = [float(v) for v in box[:4]]
    chicken_area = max(1.0, (bx2 - bx1) * (by2 - by1))
    bcx = (bx1 + bx2) / 2.0
    bcy = (by1 + by2) / 2.0

    for pbox in person_boxes or ():
        px1, py1, px2, py2 = [float(v) for v in pbox[:4]]
        person_area = max(1.0, (px2 - px1) * (py2 - py1))
        ix1, iy1 = max(bx1, px1), max(by1, py1)
        ix2, iy2 = min(bx2, px2), min(by2, py2)
        if ix2 <= ix1 or iy2 <= iy1:
            continue
        inter = (ix2 - ix1) * (iy2 - iy1)
        if (inter / chicken_area) >= person_thresh:
            return True
        if (inter / person_area) >= person_thresh:
            return True

    for fbox in face_boxes or ():
        fx1, fy1, fx2, fy2 = [float(v) for v in fbox[:4]]
        ix1, iy1 = max(bx1, fx1), max(by1, fy1)
        ix2, iy2 = min(bx2, fx2), min(by2, fy2)
        if ix2 > ix1 and iy2 > iy1:
            inter = (ix2 - ix1) * (iy2 - iy1)
            if (inter / chicken_area) >= face_thresh:
                return True
        # A face is small, so "centre inside" is a safe signal here.
        if fx1 <= bcx <= fx2 and fy1 <= bcy <= fy2:
            return True

    return False


def veto_worker(frame_source, stop_event, person_model=None, face_detector=None):
    """Detect people and faces off the detection thread.

    Runs at VETO_HZ against a downscaled copy so a CPU person/face model never
    delays a bounding box. Results are published to ``veto_state``.
    """
    interval = 1.0 / max(0.5, VETO_HZ)
    last_generation = 0
    print(
        f"[INFO] Human/Face veto worker started at {VETO_HZ:.1f} Hz "
        f"(person={'on' if person_model else 'off'}, face={'on' if face_detector else 'off'})"
    )

    while not stop_event.is_set():
        frame, generation = frame_source.wait_for_newer(last_generation, timeout=0.5)
        if frame is None:
            continue
        last_generation = generation

        h, w = frame.shape[:2]
        scale = min(1.0, 480.0 / max(1.0, float(w)))
        if scale < 1.0:
            small = cv2.resize(frame, (int(w * scale), int(h * scale)),
                               interpolation=cv2.INTER_AREA)
        else:
            small = frame

        person_boxes = []
        face_boxes = []

        if person_model is not None:
            try:
                p_res = person_model.predict(
                    source=small, classes=[0], conf=0.30, imgsz=384, verbose=False
                )[0]
                if p_res.boxes is not None and len(p_res.boxes) > 0:
                    raw = p_res.boxes.xyxy.cpu().numpy()
                    person_boxes = (raw / max(scale, 1e-6)).tolist()
            except Exception:
                pass

        if face_detector is not None:
            try:
                f_res = face_detector.process(cv2.cvtColor(small, cv2.COLOR_BGR2RGB))
                if f_res.detections:
                    for det in f_res.detections:
                        rb = det.location_data.relative_bounding_box
                        face_boxes.append([
                            max(0.0, rb.xmin) * w,
                            max(0.0, rb.ymin) * h,
                            min(1.0, rb.xmin + rb.width) * w,
                            min(1.0, rb.ymin + rb.height) * h,
                        ])
            except Exception:
                pass

        with veto_state_lock:
            veto_state["person_boxes"] = person_boxes
            veto_state["face_boxes"] = face_boxes
            veto_state["updated_at"] = time.time()

        time.sleep(interval)


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


def purge_display_id_data(disp_id):
    """Completely scrubs all historical tracking, behavioral stats, Gemini verdicts,
    and cooldowns for a specific display ID so a new bird never inherits stale data."""
    track_first_seen.pop(disp_id, None)
    track_stationary_duration.pop(disp_id, None)
    track_histories.pop(disp_id, None)
    track_motion_windows.pop(disp_id, None)
    track_last_hist_time.pop(disp_id, None)
    track_classifications.pop(disp_id, None)
    behavior_history_sample_times.pop(disp_id, None)
    behavior_stats_cache.pop(disp_id, None)
    behavior_baseline_cache.pop(disp_id, None)
    track_velocities.pop(disp_id, None)
    display_id_last_positions.pop(disp_id, None)
    display_coasting_boxes.pop(disp_id, None)
    if disp_id in behavior_history.records:
        del behavior_history.records[disp_id]
    if disp_id in behavior_history.baselines:
        del behavior_history.baselines[disp_id]
    gemini_escalator.verdicts.pop(disp_id, None)
    gemini_escalator.chicken_summaries.pop(disp_id, None)
    gemini_escalator.active_alerts.pop(disp_id, None)
    with _global_cooldown_lock:
        _last_alert_timestamp_by_id.pop(disp_id, None)
    with _scan_lock:
        _scan_state["chickens"].pop(disp_id, None)
        _scan_state["seen_ids"].discard(disp_id)
        _scan_state["warning_ids"].discard(disp_id)
        _scan_state["alert_ids"].discard(disp_id)
    if requests is not None:
        try:
            requests.delete(f"{FIREBASE_DATABASE_URL}/active_alerts/chicken_{disp_id}.json", timeout=1)
        except Exception:
            pass


def reset_all_tracking_state():
    """Wipes all tracking state, histories, baselines, Gemini verdicts, and active alerts."""
    track_display_ids.clear()
    track_missed_counts.clear()
    track_histories.clear()
    track_first_seen.clear()
    track_stationary_duration.clear()
    track_classifications.clear()
    track_last_positions.clear()
    track_motion_windows.clear()
    track_last_hist_time.clear()
    behavior_history_sample_times.clear()
    behavior_stats_cache.clear()
    behavior_baseline_cache.clear()
    inactive_tracks.clear()
    track_velocities.clear()
    display_id_last_positions.clear()
    track_hit_streaks.clear()
    track_confirmed_raw_ids.clear()
    display_coasting_boxes.clear()
    track_crop_sig.clear()
    track_frozen_streaks.clear()
    behavior_history.records.clear()
    behavior_history.baselines.clear()
    gemini_escalator.verdicts.clear()
    gemini_escalator.chicken_summaries.clear()
    gemini_escalator.active_alerts.clear()
    reset_global_cooldown()
    with _scan_lock:
        _scan_state["seen_ids"].clear()
        _scan_state["warning_ids"].clear()
        _scan_state["alert_ids"].clear()
        _scan_state["chickens"].clear()
    if requests is not None:
        try:
            requests.delete(f"{FIREBASE_DATABASE_URL}/active_alerts.json", timeout=2)
        except Exception:
            pass


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
            # Robust re-ID window: preserves the chicken's identity through 1.3s to 5.0s occlusions/blink
            max_reid_elapsed = 5.0
            if elapsed > max_reid_elapsed:
                continue

            # Linear motion extrapolation: predict where running chicken moved to
            vx, vy = track_velocities.get(disp_id, (0.0, 0.0))
            pred_cx = last_cx + vx * elapsed
            pred_cy = last_cy + vy * elapsed
            speed = float(np.hypot(vx, vy))

            # Dynamic velocity-expanded matching radius
            # For inactive tracks, limit search radius to 110px so panning doesn't steal the ID,
            # but a chicken pausing or stepping near the same spot (within 110px) smoothly resumes its ID and data!
            if old_raw_id is None:
                dynamic_radius = min(110.0, 75.0 + (speed * 0.25))
            else:
                dynamic_radius = min(180.0, max(75.0, 60.0 + (speed * 0.35) + (20.0 * elapsed)))

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
            # Purge any previous history/verdicts for this slot so new chicken starts completely fresh!
            purge_display_id_data(cand)
            track_display_ids[raw_id] = cand
            track_missed_counts[raw_id] = 0
            if cx is not None and cy is not None:
                track_last_positions[raw_id] = (cx, cy, curr_time)
                update_track_velocity(cand, cx, cy, curr_time)
            track_first_seen[cand] = curr_time
            track_stationary_duration[cand] = 0.0
            return cand

    # All slots (1..MAX_TRACKED_CHICKENS) occupied.
    return None


def cleanup_stale_ids(active_raw_ids, max_missed=45):
    """Keeps tracks alive through brief occlusions / fast movement (45 frames = 1.5 seconds)
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

    # Clean up inactive tracks older than 5.0 seconds (persists identity through 1-4s brief occlusions, blinks, and head-turns)
    for disp_id in list(inactive_tracks.keys()):
        if curr_time - inactive_tracks[disp_id]["time"] > 5.0:
            del inactive_tracks[disp_id]
            purge_display_id_data(disp_id)



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


def build_badge_reason(elapsed_obs, behavior_label, stationary_sec, is_isolated,
                       unhealthy_prob, anomaly_score, anomaly_tier="Normal"):
    """Short, factual reason for the on-video badge.

    The box colour already encodes the anomaly tier, so the badge text does not
    repeat the tier name. It says what is actually being observed instead, e.g.
    "still 91s", "isolated" or "walk". One vocabulary, every surface.
    """
    if elapsed_obs < OBSERVATION_WINDOW:
        return ""
    if is_isolated and anomaly_score >= 30:
        return "isolated"
    if stationary_sec >= 15.0:
        return f"still {int(stationary_sec)}s"
    # Never label a bird 'unwell' when the overall anomaly tier is Normal / Healthy
    if anomaly_tier != "Normal" and unhealthy_prob >= 0.50:
        return "unwell"
    if anomaly_score >= 60:
        return "unusual"
    return behavior_label or ""


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



@flask_app.route('/scan/start', methods=['POST'])
def scan_start_route():
    """App signals that a diagnostic scan has begun."""
    scan_start()
    return jsonify({"status": "success", "scan_active": True}), 200


@flask_app.route('/scan/stop', methods=['POST'])
def scan_stop_route():
    """App signals that a scan has finished. Sends one summary SMS."""
    barn_name = ""
    try:
        data = request.get_json(force=True, silent=True) or {}
        barn_name = str(data.get("barn_name") or "").strip()
    except Exception:
        pass
    summary = scan_stop(barn_name)
    return jsonify({"status": "success", **summary}), 200


@flask_app.route('/api/alerts')
def api_alerts():
    """Returns active chicken health alerts generated by the vision engine and Gemini."""
    with frame_lock:
        return jsonify({
            "status": "success",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "global_cooldown_active": is_global_cooldown_active(),
            "global_cooldown_remaining_sec": int(get_global_cooldown_remaining()),
            "gemini_min_query_gap_sec": int(GEMINI_MIN_QUERY_GAP_SEC),
            "gemini_per_bird_cooldown_sec": int(GLOBAL_ALERT_COOLDOWN_SEC),
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
    """Reset persistent chicken IDs, track histories, baselines, Gemini verdicts, and alert cooldown."""
    reset_all_tracking_state()
    return jsonify({"status": "success", "message": "Chicken tracking IDs, histories, verdicts, and alert cooldown reset successfully."})


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
                    <div style="font-size: 13px; color: var(--text-muted);">YOLO26n Chicken Health & Behavior Engine</div>
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

        // The server publishes detections at ~9 Hz. Instead of snapping boxes on
        // each poll, keep the newest target and ease the drawn box toward it on
        // every animation frame so motion reads as continuous.
        const trackedBoxes = new Map();
        let overlayLoopRunning = false;

        function startOverlayLoop() {
            if (overlayLoopRunning) return;
            overlayLoopRunning = true;
            let last = performance.now();

            function frame(now) {
                const dt = Math.min(0.1, (now - last) / 1000);
                last = now;

                if (isStreaming) {
                    overlayCtx.setTransform(1, 0, 0, 1, 0, 0);
                    overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);
                    trackedBoxes.clear();
                    overlayLoopRunning = false;
                    return;
                }

                const dpr = window.devicePixelRatio || 1;
                overlayCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
                overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);

                // Frame-rate independent easing (~110 ms convergence).
                const posFactor = 1 - Math.exp(-14 * dt);
                const alphaFactor = 1 - Math.exp(-10 * dt);
                let stillAnimating = false;

                trackedBoxes.forEach((box, key) => {
                    box.current.x += (box.target.x - box.current.x) * posFactor;
                    box.current.y += (box.target.y - box.current.y) * posFactor;
                    box.current.w += (box.target.w - box.current.w) * posFactor;
                    box.current.h += (box.target.h - box.current.h) * posFactor;
                    box.alpha += (box.targetAlpha - box.alpha) * alphaFactor;

                    const posDiff = Math.abs(box.current.x - box.target.x) +
                                    Math.abs(box.current.y - box.target.y);
                    if (posDiff > 0.5 || Math.abs(box.alpha - box.targetAlpha) > 0.01) {
                        stillAnimating = true;
                    }

                    // Fade out then drop tracks that are no longer detected.
                    if (!box.alive && box.alpha < 0.03) {
                        trackedBoxes.delete(key);
                        return;
                    }
                    if (box.alpha <= 0.01) return;

                    const x = box.current.x;
                    const y = box.current.y;
                    const w = box.current.w;
                    const h = box.current.h;

                    overlayCtx.globalAlpha = box.alpha;
                    overlayCtx.strokeStyle = box.color;
                    overlayCtx.lineWidth = 3;
                    overlayCtx.strokeRect(x, y, w, h);

                    overlayCtx.font = 'bold 13px system-ui, sans-serif';
                    const textWidth = overlayCtx.measureText(box.label).width;
                    const labelY = Math.max(0, y - 20);
                    overlayCtx.fillStyle = 'rgba(15,23,42,0.82)';
                    overlayCtx.fillRect(x, labelY, textWidth + 12, 20);
                    overlayCtx.fillStyle = box.color;
                    overlayCtx.fillText(box.label, x + 6, labelY + 14);
                    overlayCtx.globalAlpha = 1;
                });

                if (trackedBoxes.size > 0 || stillAnimating) {
                    requestAnimationFrame(frame);
                } else {
                    overlayLoopRunning = false;
                }
            }

            requestAnimationFrame(frame);
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

            const seen = new Set();
            (payload.detections || []).forEach((det, index) => {
                const bbox = det.bbox;
                if (!bbox || bbox.length < 4) return;

                const trackId = det.track_id || 0;
                const key = trackId > 0 ? trackId : -(index + 1000);
                seen.add(key);

                const tier = det.anomaly_tier || 'Normal';
                const color = tier === 'High Concern' ? '#ff4646'
                    : tier === 'Suspicious' ? '#ff8c00'
                    : tier === 'Monitor' ? '#00d7ff' : '#00e676';

                const reason = String(det.reason || '').trim();
                const label = '#' + trackId +
                    (reason ? ' ' + reason : '') +
                    (det.gemini_verified ? ' \u2713' : '');

                const target = {
                    x: bbox[0] * scale + offsetX,
                    y: bbox[1] * scale + offsetY,
                    w: (bbox[2] - bbox[0]) * scale,
                    h: (bbox[3] - bbox[1]) * scale
                };

                const existing = trackedBoxes.get(key);
                if (existing) {
                    existing.target = target;
                    existing.color = color;
                    existing.label = label;
                    existing.targetAlpha = 1;
                    existing.alive = true;
                } else {
                    // New bird: fade in where it was detected instead of flying in.
                    trackedBoxes.set(key, {
                        current: { ...target },
                        target: target,
                        color: color,
                        label: label,
                        alpha: 0,
                        targetAlpha: 1,
                        alive: true
                    });
                }
            });

            trackedBoxes.forEach((box, key) => {
                if (!seen.has(key)) {
                    box.targetAlpha = 0;
                    box.alive = false;
                }
            });

            startOverlayLoop();
        }

        let detectionsFetchInFlight = false;
        setInterval(() => {
            if (detectionsFetchInFlight) return;
            detectionsFetchInFlight = true;
            fetch('/detections')
                .then(res => res.json())
                .then(drawDetections)
                .catch(() => {})
                .finally(() => { detectionsFetchInFlight = false; });
        }, 100);

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
    last_cam_info = 0.0

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
            # Surface the negotiated camera mode and reader health ~1 Hz so a
            # silent MJPG/buffer downgrade is visible in /status instead of
            # only in the startup log.
            if now - last_cam_info >= 1.0:
                last_cam_info = now
                latest_status["camera"] = cam.info()


# --- Main Inference & Camera Loop ---
def _track_device(exec_device, model_path):
    """Return a device string Ultralytics/torch can actually accept.

    ``intel:gpu`` and friends are OpenVINO-only names. ``select_device()``
    passes them straight through, so handing one to a PyTorch/ONNX model
    crashes inside ``torch.device()`` with "Invalid device string". When the
    OpenVINO export is missing and we fall back to ``.pt``, the device has to
    fall back too.
    """
    dev = str(exec_device or "")
    path = str(model_path or "").rstrip("\\/").lower()
    if dev.startswith(("intel", "tpu", "vulkan")) and not path.endswith("_openvino_model"):
        return "cpu"
    return dev


def camera_loop():
    global latest_frame, latest_jpeg_bytes, latest_raw_jpeg_bytes, latest_status, _last_cooldown_log_time

    print("[INFO] Initializing PoultrySense AI Engine...")

    backend, exec_device, model_format = get_inference_backend()
    print(f"[AI ENGINE] Backend={backend} device={exec_device} format={model_format}")
    if INT8_ENABLED and model_format != "openvino":
        print(
            f"[WARN] POULTRYSENSE_INT8 is set but the detector backend is "
            f"'{model_format}'. INT8 quantization is only wired for the OpenVINO "
            f"path, so the detector stays FP32."
        )
    if backend == "directml":
        enable_directml_providers()

    det_model = None
    behavior_model = None
    health_model = None
    chicken_classes = [0]
    person_model = None
    face_detector = None

    if HAS_YOLO:
        # 1. Chicken Detection Model
        det_path = get_model_path(DET_MODELS)
        resolved_det = det_path
        if det_path:
            try:
                if model_format == "onnx":
                    resolved_det = resolve_onnx(det_path, imgsz=DETECTOR_IMGSZ)
                elif model_format == "openvino":
                    print(f"[INFO] Detector precision: {DETECTOR_PRECISION}")
                    resolved_det = resolve_accelerated_model(
                        det_path, imgsz=DETECTOR_IMGSZ, precision=DETECTOR_PRECISION
                    )
                else:
                    resolved_det = det_path
                print(f"[INFO] Loading Detection Model from: {resolved_det}")
                if _track_device(exec_device, resolved_det) != exec_device:
                    print(
                        "[WARN] Accelerated OpenVINO model unavailable; the detector "
                        "will run on CPU via PyTorch (slower). Check the [OpenVINO] "
                        "lines above for the export error."
                    )
                try:
                    det_model = YOLO(resolved_det, task='detect')
                except Exception as ex_ov:
                    print(f"[WARN] Accelerated load notice ({ex_ov}); falling back to native PyTorch .pt: {det_path}")
                    det_model = YOLO(det_path, task='detect')
                    resolved_det = det_path

                print("[INFO] YOLO Detection Model loaded successfully!")

                # Determine strict chicken class ID(s). Avoid touching .names on
                # OpenVINO exports: it triggers a ~25s lazy weight load.
                found_classes = []
                if model_format != "openvino" and hasattr(det_model, 'names') and det_model.names:
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
                else:
                    chicken_classes = [0]

                class_labels = [str(c) for c in chicken_classes]
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

        # Face detector (MediaPipe) for the human/face veto, off the detection thread.
        try:
            import mediapipe as mp
            face_detector = mp.solutions.face_detection.FaceDetection(
                model_selection=1, min_detection_confidence=0.5
            )
            print("[INFO] MediaPipe face veto active")
        except Exception as ex_fd:
            print(f"[WARN] MediaPipe face veto unavailable: {ex_fd}")

        # Pin the device once so Ultralytics does not rebuild the predictor (and
        # recompile OpenVINO) on every track() call, and warm it up.
        if det_model is not None:
            try:
                det_model.overrides["device"] = exec_device
                import numpy as _np
                _dummy = _np.zeros((640, 640, 3), dtype=_np.uint8)
                det_model.predict(source=_dummy, imgsz=640, verbose=False)
                print(f"[INFO] Detector pinned to device={exec_device} and warmed up")
            except Exception as ex_wu:
                print(f"[WARN] Detector warm-up notice: {ex_wu}")

        # 2. Behavior Classification Model
        beh_path = get_model_path(BEHAVIOR_MODELS)
        if beh_path:
            try:
                resolved_beh = resolve_accelerated_model(beh_path, imgsz=CLASSIFIER_IMGSZ)
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
                resolved_hlth = resolve_accelerated_model(hlth_path, imgsz=HEALTH_CLASSIFIER_IMGSZ)
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
                args=(behavior_model, health_model, CLASSIFIER_IMGSZ, HEALTH_CLASSIFIER_IMGSZ),
                daemon=True
            )
            c_thread.start()
            print("[INFO] Background AI Health & Behavior Worker started on dedicated core.")

        # Route the BoT-SORT appearance (ReID) encoder to the requested device.
        reid_status = install_reid_device(REID_DEVICE)
        print(f"[INFO] ReID device: {reid_status}")

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

    # Human/face veto runs on its own thread at VETO_HZ against a downscaled copy.
    veto_stop = threading.Event()
    veto_thread = None
    if person_model is not None or face_detector is not None:
        veto_thread = threading.Thread(
            target=veto_worker,
            args=(frame_buffer, veto_stop, person_model, face_detector),
            daemon=True,
        )
        veto_thread.start()

    rate_gate = RateGate(DETECTION_MAX_RATE_HZ)
    # Keep tracks alive for ~1.5 seconds of wall-clock time so brief dropout doesn't flicker,
    # but panning the camera drops stale tracks promptly.
    max_missed_detections = max(3, int(round(1.5 * DETECTION_MAX_RATE_HZ)))
    consecutive_empty_frames = 0

    cached_display_items = []
    cached_person_boxes = []
    last_generation = 0
    prev_det_time = time.time()
    smooth_det_fps = 0.0
    _last_trackdbg_print = 0.0
    _prev_raw_id_set = set()
    _raw_id_switches = 0
    _person_check_counter = 0

    while True:
        # High-FPS frame consumption: short wait bounds latency without stalling video
        wait = rate_gate.remaining()
        if wait > 0:
            time.sleep(min(wait, 0.015))
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
        dt = max(0.001, min(2.0, det_interval))
        inst_det_fps = min(1.0 / det_interval, DETECTION_MAX_RATE_HZ * 1.5)
        smooth_det_fps = (
            inst_det_fps if smooth_det_fps <= 0.0
            else 0.8 * smooth_det_fps + 0.2 * inst_det_fps
        )

        # Human / face veto comes from a dedicated thread running at VETO_HZ, so it
        # never delays a bounding box. Copy the shared lists (cheap) and use them.
        with veto_state_lock:
            person_boxes = list(veto_state.get("person_boxes", []))
            face_boxes = list(veto_state.get("face_boxes", []))

        display_items = cached_display_items
        if det_model is not None:
            active_raw_ids = []
            raw_boxes = []
            raw_confs = []
            raw_seen = 0
            person_vetoed = 0
            size_dropped = 0
            no_slot_dropped = 0
            lowconf_dropped = 0
            frozen_dropped = 0
            unconfirmed_dropped = 0
            detection_error = None
            tracking_unavailable = False
            min_box_side = max(MIN_BOX_PX_FLOOR, int(round(MIN_BOX_FRACTION * w_frame)))

            try:
                tracker_cfg = TRACKER_YAML if os.path.exists(TRACKER_YAML) else "botsort.yaml"
                results = det_model.track(
                    source=frame,
                    persist=True,
                    tracker=tracker_cfg,
                    classes=chicken_classes,
                    conf=DETECTOR_CONF,  # Low: lets BoT-SORT's 2nd association stage keep fast/blurry chickens
                    iou=0.50,
                    imgsz=DETECTOR_IMGSZ,
                    max_det=20,
                    device=_track_device(exec_device, resolved_det),
                    verbose=False
                )[0]

                boxes = results.boxes
                det_boxes = 0 if boxes is None else len(boxes)
                tracked_boxes = 0
                if boxes is not None and len(boxes) > 0:
                    if boxes.id is None:
                        # Transient: tracker has detections but no IDs (reset / first
                        # frame). Keep the last good boxes instead of blanking.
                        tracking_unavailable = True
                    else:
                        tracked_boxes = len(boxes.id)
                        xyxys = boxes.xyxy.cpu().numpy()
                        confs = boxes.conf.cpu().numpy()
                        ids = boxes.id.cpu().numpy()
                        cls_ids = boxes.cls.cpu().numpy() if boxes.cls is not None else np.zeros(len(xyxys))

                        for box, conf, raw_id, c_id in zip(xyxys, confs, ids, cls_ids):
                            if int(c_id) not in chicken_classes:
                                continue
                            raw_seen += 1

                            # Human / face veto: reject boxes that are really a
                            # person or a face (see box_is_human).
                            if box_is_human(box, person_boxes, face_boxes):
                                person_vetoed += 1
                                continue

                            # Minimum Box Dimensions (resolution-relative) + aspect
                            box_w = abs(box[2] - box[0])
                            box_h = abs(box[3] - box[1])
                            aspect = box_w / max(1.0, box_h)
                            if box_w < min_box_side or box_h < min_box_side or aspect < 0.28 or aspect > 3.2:
                                size_dropped += 1
                                continue

                            raw_boxes.append(box)
                            raw_confs.append(float(conf))
                            active_raw_ids.append(int(raw_id))
            except Exception as det_ex:
                detection_error = f"{type(det_ex).__name__}: {det_ex}"
                print(f"[DETECTION] Exception: {detection_error}")
                tracking_unavailable = True

            # Collect display items (both active detections and motion-extrapolated coasting tracks)
            if tracking_unavailable:
                display_items = cached_display_items
            else:
                cleanup_stale_ids(active_raw_ids, max_missed=max_missed_detections)

                display_items = []
                seen_display_ids = set()

            for box, conf, raw_id in zip(raw_boxes, raw_confs, active_raw_ids):
                cx = (box[0] + box[2]) / 2.0
                cy = (box[1] + box[3]) / 2.0

                # 1. Spatial/Motion-Predictive Display ID allocation (instant handoff for running chickens)
                display_id = assign_display_id(raw_id, cx=cx, cy=cy, box=box)
                if display_id is None:
                    no_slot_dropped += 1
                    continue

                # 2. Debounce & Frozen Crop Check (filters out hands, furniture, and room artifacts)
                if raw_id not in track_confirmed_raw_ids:
                    if _crop_is_frozen(frame, box, raw_id):
                        track_frozen_streaks[raw_id] += 1
                        if track_frozen_streaks[raw_id] >= CHURN_MAX_FROZEN:
                            frozen_dropped += 1
                            continue
                    else:
                        track_frozen_streaks[raw_id] = 0
                    track_hit_streaks[raw_id] += 1
                    required_hits = 1  # Immediate 1st-frame appearance — zero confirmation lag
                    if track_hit_streaks[raw_id] >= required_hits:
                        track_confirmed_raw_ids.add(raw_id)
                    else:
                        unconfirmed_dropped += 1
                        continue

                # 3. Box tracking: no smoothing — draw the detector's box as-is.
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

            # Anti-blink coasting: hold the last known box for a brief detector
            # dropout. We deliberately do NOT extrapolate along the velocity
            # vector here: the velocity is one or two frames stale, so a bird
            # that slows or turns would see its box sail past it. Freezing the
            # box is always less wrong than guessing forward.
            if not tracking_unavailable:
                for disp_id in list(display_coasting_boxes.keys()):
                        if disp_id not in seen_display_ids:
                            c_info = display_coasting_boxes[disp_id]
                            c_info["missed"] += 1
                            if c_info["missed"] <= COAST_MAX_FRAMES:
                                # Drop the cached velocity so a re-acquired track
                                # does not inherit stale motion into the next
                                # prediction step.
                                track_velocities[disp_id] = (0.0, 0.0)
                                display_items.append({
                                    "display_id": disp_id,
                                    "box": c_info["box"],
                                    "conf": c_info["conf"],
                                    "raw_id": c_info["raw_id"],
                                    "cx": c_info["cx"],
                                    "cy": c_info["cy"],
                                    "is_coasting": True
                                })
                            else:
                                del display_coasting_boxes[disp_id]

            if MAX_DISPLAY_BOXES > 0:
                display_items = display_items[:MAX_DISPLAY_BOXES]
            cached_display_items = display_items

            if DEBUG_TRACK:
                _new_raw_id_set = set(active_raw_ids)
                if _new_raw_id_set != _prev_raw_id_set:
                    _raw_id_switches += 1
                _prev_raw_id_set = _new_raw_id_set
                if curr_time - _last_trackdbg_print >= 1.0:
                    _last_trackdbg_print = curr_time
                    print(
                        f"[TRACKDBG] det={det_boxes} tracked={tracked_boxes} "
                        f"raw={raw_seen} veto={person_vetoed} "
                        f"size={size_dropped} noslot={no_slot_dropped} "
                        f"lowconf={lowconf_dropped} frozen={frozen_dropped} "
                        f"unconf={unconfirmed_dropped} shown={len(display_items)} "
                        f"id_switches/s={_raw_id_switches} "
                        f"raw_ids={active_raw_ids} "
                        f"disp_ids={[d['display_id'] for d in display_items]}"
                    )
                    _raw_id_switches = 0

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
                last_hist_t = track_last_hist_time.get(display_id)
                motion_dt = dt if last_hist_t is None else max(1e-3, min(2.0, curr_time - last_hist_t))
                if len(hist) > 0:
                    prev_c = hist[-1]
                    delta_px = float(np.hypot(cx - prev_c[0], cy - prev_c[1]))
                    motion = normalize_motion(delta_px, motion_dt)
                hist.append((int(cx), int(cy)))
                track_last_hist_time[display_id] = curr_time

            # Record rolling temporal motion history
            track_motion_windows[display_id].append(motion)

            # Track continuous stationary duration (seconds with motion < 1.6)
            if motion < 1.6:
                track_stationary_duration[display_id] = track_stationary_duration.get(display_id, 0.0) + dt
            elif motion >= 2.5:
                track_stationary_duration[display_id] = 0.0
            else:
                track_stationary_duration[display_id] = max(0.0, track_stationary_duration.get(display_id, 0.0) - (dt * 3.0))

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
                    behavior_time = cached.get("behavior_time", cached.get("time", 0))
                    health_time = cached.get("health_time", 0)
                    unhealthy_prob = (
                        cached.get("unhealthy_prob", 0.0)
                        if curr_time - health_time <= HEALTH_CLASSIFICATION_MAX_AGE_SEC
                        else 0.0
                    )
                else:
                    behavior_label = "stand"
                    unhealthy_prob = 0.0
                    behavior_time = 0
                    health_time = 0

            # Keep behavior classification low-rate. Health classification is only
            # requested after a sustained local risk cue, never for every bird.
            likely_health_risk = (
                stationary_sec >= GEMINI_TRIGGER_STATIONARY_SEC
                or (is_isolated and stationary_sec >= 15.0)
            )
            run_behavior = curr_time - behavior_time >= CLASSIFIER_INTERVAL_SEC
            run_health = likely_health_risk and (
                curr_time - health_time >= HEALTH_CLASSIFIER_INTERVAL_SEC
            )

            # Drop stale work when full. The camera loop always wins over a
            # secondary classifier task.
            if not is_coasting and (run_behavior or run_health) and crop.size > 0 and crop.shape[0] >= 24 and crop.shape[1] >= 24:
                try:
                    classification_queue.put_nowait((display_id, crop.copy(), run_behavior, run_health))
                except queue.Full:
                    try:
                        classification_queue.get_nowait()
                    except Exception:
                        pass
                    try:
                        classification_queue.put_nowait((display_id, crop.copy(), run_behavior, run_health))
                    except Exception:
                        pass
                except Exception:
                    pass

            # Real-time Motion fusion heuristic:
            # Active locomotion / foraging requires optical motion. A motionless chicken cannot be walking or pecking.
            if motion > 4.5:
                behavior_label = "walk"
            elif motion < 1.6:
                # Chicken is motionless / stationary on the litter
                if unhealthy_prob >= 0.45:
                    behavior_label = "recumbent"  # Down / paralyzed / unable to stand
                elif stationary_sec >= 15.0:
                    behavior_label = "rest"
                elif behavior_label not in ("rest", "stand"):
                    behavior_label = "stand"

            # 1. Sample behavior history at 2 Hz. Recomputing a 180-second
            # history on every detection tick is expensive and adds no useful
            # signal for a farm-health alert.
            cx = int((x1 + x2) / 2)
            cy = int((y1 + y2) / 2)
            last_sample_time = behavior_history_sample_times.get(display_id, 0.0)
            if curr_time - last_sample_time >= BEHAVIOR_HISTORY_SAMPLE_SEC:
                behavior_history.record(display_id, curr_time, behavior_label, motion, is_isolated, (cx, cy))
                behavior_history_sample_times[display_id] = curr_time
                behavior_stats_cache[display_id] = behavior_history.get_stats(display_id, curr_time)
                behavior_baseline_cache[display_id] = behavior_history.get_baseline(display_id)

            stats = behavior_stats_cache.get(display_id)
            if stats is None:
                behavior_history.record(display_id, curr_time, behavior_label, motion, is_isolated, (cx, cy))
                behavior_history_sample_times[display_id] = curr_time
                stats = behavior_history.get_stats(display_id, curr_time)
                behavior_stats_cache[display_id] = stats
                behavior_baseline_cache[display_id] = behavior_history.get_baseline(display_id)
            baseline = behavior_baseline_cache.get(display_id, behavior_history.get_baseline(display_id))

            # 2. Calibration vs 5-Component Weighted Anomaly Evaluation
            if elapsed_obs < OBSERVATION_WINDOW:
                health_label = "Observing"
                anomaly_score = 0
                anomaly_tier = "Normal"
                gemini_summary = ""
                gemini_verified = False
                health_detail = f"Baseline Observation {elapsed_obs:.0f}/{OBSERVATION_WINDOW:.0f}s"
                color = get_id_color(display_id)  # Maintain standard ID color during observation
                healthy_count += 1
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
                    if unhealthy_prob >= 0.45 or behavior_label == "recumbent":
                        health_detail = f"Recumbent / Low Activity ({int(unhealthy_prob * 100)}% visual severity)"
                    elif is_isolated:
                        health_detail = "Flock Edge / Moderate Separation"
                    elif stationary_sec >= 25.0:
                        health_detail = f"Extended Resting Pause ({stationary_sec:.0f}s)"
                    else:
                        health_detail = "Mild Activity Deviation"
                    color = (0, 215, 255)  # Amber-Yellow
                    observing_count += 1
                elif anomaly_tier == "Suspicious":
                    health_label = "Needs Attention"
                    if unhealthy_prob >= 0.45 or behavior_label == "recumbent":
                        health_detail = f"Recumbent / Lethargic Posture ({int(unhealthy_prob * 100)}% visual severity)"
                    elif is_isolated and stats["isolation_pct"] >= 50.0:
                        health_detail = "Persistent Isolation & Inactivity"
                    elif stationary_sec >= 45.0:
                        health_detail = f"Prolonged Immobility ({stationary_sec:.0f}s)"
                    else:
                        health_detail = f"Suspicious Activity ({anomaly_score}%)"
                    color = (0, 140, 255)  # Bright Amber-Orange
                    warning_count += 1
                else:  # High Concern (>= 80)
                    health_label = "High Concern"
                    if unhealthy_prob >= 0.45 or behavior_label == "recumbent":
                        health_detail = f"Recumbent / Paralyzed Posture ({int(unhealthy_prob * 100)}%)"
                    else:
                        health_detail = f"Severe Lethargy Marker ({stationary_sec:.0f}s immobile)"
                    # Prior to Gemini confirmation, hold at Amber-Orange (Suspicious/Evaluating) instead of Red
                    color = (0, 140, 255)  # Bright Amber-Orange
                    unhealthy_count += 1

                # 5. Multimodal abnormal-behavior and sensor-stress analysis with Gemini.
                # Triggered when ANY abnormal behavior or environmental stress is detected:
                # - Prolonged immobility / recumbency
                # - Lethargic posture (classifier / posture sub-score)
                # - Persistent flock isolation
                # - Thermal stress (ambient temperature > 30°C or < 18°C from ESP32 DHT22)
                # - Composite anomaly tier (Suspicious / High Concern)
                abnormal_triggers = []
                if behavior_label == "recumbent" or unhealthy_prob >= 0.45:
                    abnormal_triggers.append(f"Abnormal recumbent posture / unable to stand ({unhealthy_prob:.2f})")

                if stationary_sec >= DAYTIME_RECUMBENT_THRESHOLD_SEC:
                    abnormal_triggers.append(f"Prolonged immobility ({stationary_sec:.0f}s >= {DAYTIME_RECUMBENT_THRESHOLD_SEC:.0f}s threshold)")
                elif stationary_sec >= GEMINI_TRIGGER_STATIONARY_SEC and behavior_label in ("rest", "stand", "sit", "recumbent"):
                    abnormal_triggers.append(f"Extended stationary resting ({stationary_sec:.0f}s >= {GEMINI_TRIGGER_STATIONARY_SEC:.0f}s)")

                if unhealthy_prob >= 0.45 or anomaly_info.get("sub_scores", {}).get("posture", 0) >= 45.0:
                    abnormal_triggers.append(f"Lethargic posture / visual anomaly score ({unhealthy_prob:.2f})")

                if is_isolated and (stats.get("isolation_pct", 0) >= 40.0 and elapsed_obs >= OBSERVATION_WINDOW):
                    abnormal_triggers.append(f"Spatial isolation away from flock ({stats.get('isolation_pct', 0):.0f}%)")

                curr_t_check, _, _ = get_current_climate()
                if curr_t_check is not None:
                    if curr_t_check > 30.0 and (stationary_sec >= GEMINI_TRIGGER_STATIONARY_SEC or motion < 1.6):
                        abnormal_triggers.append(f"Heat stress condition ({curr_t_check:.1f}°C > 30°C with reduced activity)")
                    elif curr_t_check < 18.0 and (stationary_sec >= GEMINI_TRIGGER_STATIONARY_SEC or motion < 1.6):
                        abnormal_triggers.append(f"Cold stress condition ({curr_t_check:.1f}°C < 18°C with huddled inactivity)")

                if anomaly_tier in ("Suspicious", "High Concern") or anomaly_score >= 65:
                    abnormal_triggers.append(f"High anomaly score ({anomaly_tier} {anomaly_score}%)")

                # Sustained observation gate: Gemini evaluates ONLY when abnormal behavior is sustained,
                # never on immediate single-frame transients or newly appeared chickens still calibrating.
                is_sustained_recumbency = (
                    (behavior_label == "recumbent" or unhealthy_prob >= 0.50)
                    and (stationary_sec >= GEMINI_TRIGGER_STATIONARY_SEC or (unhealthy_prob >= 0.85 and stationary_sec >= 30.0))
                    and elapsed_obs >= OBSERVATION_WINDOW
                )
                is_prolonged_immobility = (
                    stationary_sec >= DAYTIME_RECUMBENT_THRESHOLD_SEC
                    and elapsed_obs >= OBSERVATION_WINDOW
                )
                is_sustained_anomaly = (
                    (anomaly_tier in ("Suspicious", "High Concern") or anomaly_score >= 65)
                    and stationary_sec >= GEMINI_TRIGGER_STATIONARY_SEC
                    and elapsed_obs >= OBSERVATION_WINDOW
                )

                is_abnormal_flagged = (
                    is_sustained_recumbency
                    or is_prolonged_immobility
                    or is_sustained_anomaly
                )

                if is_abnormal_flagged and crop.size > 0 and crop.shape[0] >= 20 and crop.shape[1] >= 20:
                    # Snapshot is written inside request_verdict_async (once per
                    # cooldown window), so we must NOT write it here every frame:
                    # a per-frame disk write on the main loop is what caused the
                    # visible lag / FPS collapse during EVALUATING.
                    snapshot_file = os.path.join(SCRIPT_DIR, "flagged_chicken.jpg")

                    # Per-chicken cooldown: one bird's alert never blocks the flock.
                    try:
                        if is_track_cooldown_active(display_id, curr_time):
                            if curr_time - _last_cooldown_log_time >= 30.0:
                                _last_cooldown_log_time = curr_time
                                rem = int(get_track_cooldown_remaining(display_id, curr_time))
                                print(f"[COOLDOWN] Chicken #{display_id} cooling down ({rem}s remaining / {GLOBAL_ALERT_COOLDOWN_SEC:.0f}s). Skipping Gemini query.")
                        elif is_global_cooldown_active(curr_time):
                            pass  # short system-wide gap between calls; retry on a later frame
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
                                abnormal_flags=abnormal_triggers,
                                behavior_label=behavior_label
                            )
                    except Exception as ex_cd:
                        print(f"[COOLDOWN] Warning during alert cooldown check: {ex_cd}")

                # 6. GEMINI VERDICT OVERRIDE — Gemini is the definitive authority.
                gemini_verdict = gemini_escalator.get_verdict(display_id)
                gemini_summary = ""
                gemini_verified = False
                gemini_severity = "Low"

                # Condition-Collapse Invalidation Guard:
                # If a bird was previously evaluated as Normal, but NOW collapses (recumbent posture,
                # acute visual sickness >= 0.50, or prolonged immobility >= 60s):
                # The old Normal verdict is INVALIDATED and purged so sickness is NEVER hidden!
                is_condition_collapsed = (
                    behavior_label == "recumbent"
                    or unhealthy_prob >= 0.50
                    or (stationary_sec >= DAYTIME_RECUMBENT_THRESHOLD_SEC and unhealthy_prob >= 0.35)
                )
                if gemini_verdict and gemini_verdict.get("tier") == "Normal" and is_condition_collapsed:
                    gemini_escalator.verdicts.pop(display_id, None)
                    gemini_verdict = None
                    with _global_cooldown_lock:
                        _last_alert_timestamp_by_id.pop(display_id, None)

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
                        anomaly_score = gemini_verdict.get("anomaly_score", min(anomaly_score, 15))
                        health_label = "Healthy"
                        color = get_id_color(display_id)
                        healthy_count += 1
                    elif gemini_tier == "Monitor":
                        anomaly_score = gemini_verdict.get("anomaly_score", 40)
                        health_label = "Monitor"
                        color = (0, 215, 255)
                        observing_count += 1
                    elif gemini_tier == "Suspicious":
                        anomaly_score = gemini_verdict.get("anomaly_score", max(65, min(anomaly_score, 79)))
                        health_label = "Needs Attention"
                        color = (0, 140, 255)
                        warning_count += 1
                    else:  # High Concern
                        anomaly_score = gemini_verdict.get("anomaly_score", max(85, anomaly_score))
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
            if elapsed_obs < OBSERVATION_WINDOW and unhealthy_prob < 0.45:
                badge_text = f"#{display_id} {behavior_label.upper()}"
            elif gemini_verified:
                badge_text = f"#{display_id} {gemini_severity.upper()}{gemini_check}"
            elif behavior_label == "recumbent":
                badge_text = f"#{display_id} RECUMBENT"
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


            # Centroid velocity in source px/sec. Clients use it to glide the
            # box between detection frames; coasting boxes report zero so the
            # client holds position exactly like the server does.
            if is_coasting:
                _vx, _vy = 0.0, 0.0
            else:
                _vx, _vy = track_velocities.get(display_id, (0.0, 0.0))

            detection_list.append({
                "track_id": display_id,
                "class": "chicken",
                "confidence": round(conf, 2),
                "velocity": [round(float(_vx), 1), round(float(_vy), 1)],
                "coasting": bool(is_coasting),
                "behavior": behavior_label,
                "health": health_label,
                "health_detail": health_detail,
                "anomaly_score": anomaly_score,
                "anomaly_tier": anomaly_tier,
                "severity": gemini_severity if gemini_verified else ("Low" if anomaly_tier == "Normal" else ("Medium" if anomaly_tier == "Monitor" else ("High" if anomaly_tier == "Suspicious" else "Critical"))),
                "gemini_summary": gemini_summary,
                "gemini_verified": gemini_verified,
                "reason": behavior_label.upper() if anomaly_tier == "Normal" else anomaly_tier,

                "isolated": is_isolated,
                "motion": round(motion, 1),
                "observed_sec": round(elapsed_obs, 1),
                "stationary_sec": round(stationary_sec, 1),
                "bbox": [x1, y1, x2, y2]
            })

        flock_count = len(detection_list)

        # Tally flagged birds and maintain cumulative details for the scan session.
        for det in detection_list:
            scan_record(det)
            # Live Alert sync (RRL Gated): Only sync confirmed anomalies to Firebase once the chicken
            # has passed the baseline observation calibration window (OBSERVATION_WINDOW = 15s)
            # and is verified or sustained, preventing transient first-frame alerts.
            if det.get("anomaly_tier") in ["Suspicious", "High Concern"] and det.get("observed_sec", 0.0) >= OBSERVATION_WINDOW:
                cid = det["track_id"]
                tag_info = get_poultry_tag_info(cid)
                live_alert_payload = {
                    "chicken_id": cid,
                    "tag_number": tag_info["tag_number"],
                    "tag_color": tag_info["tag_color"],
                    "tag_color_hex": tag_info["tag_color_hex"],
                    "tag_label": tag_info["tag_label"],
                    "status": f"{det['anomaly_tier']} • {tag_info['tag_label']}",
                    "severity": det.get("severity", "Medium"),
                    "tier": det["anomaly_tier"],
                    "anomaly_tier": det["anomaly_tier"],
                    "reason": det.get("reason") or det.get("behavior", ""),
                    "gemini_summary": det.get("gemini_summary", ""),
                    "anomaly_score": det.get("anomaly_score", 0),
                    "behavior": det.get("behavior", ""),
                    "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
                }
                sync_live_alert_debounced(cid, live_alert_payload)

        # Dynamic Health Index
        if flock_count > 0:
            health_index = max(10, int(100 - (unhealthy_count * 25) - (warning_count * 10) - (isolated_count * 5)))
            consecutive_empty_frames = 0
        else:
            health_index = 100
            consecutive_empty_frames += 1
            if consecutive_empty_frames == 180:  # ~6.0 seconds of completely empty frame (camera moved to another pen or put down)
                print("[AI Vision] Scene empty for 6.0s — clearing stale tracks.")
                reset_all_tracking_state()


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
                "gemini_min_query_gap_sec": int(GEMINI_MIN_QUERY_GAP_SEC),
                "gemini_per_bird_cooldown_sec": int(GLOBAL_ALERT_COOLDOWN_SEC),
                "detections": detection_list,
                "alerts": gemini_escalator.get_active_alerts()
            })

    if veto_thread is not None:
        veto_stop.set()
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
