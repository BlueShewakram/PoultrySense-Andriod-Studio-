# 🐔 Key Updates & Changes Summary

**Branch:** `updated-geminiai-and-sms-notification`  
**For:** PoultrySense Team  

---

## ⚡ 1. Multimodal Gemini AI Diagnostics
* **What it does:** Whenever YOLO/ByteTrack or environmental sensors flag abnormal behavior (prolonged immobility $>45$s, lethargic posture, flock isolation, or thermal stress $>30^\circ\text{C}$ / $<18^\circ\text{C}$):
  1. The AI server automatically crops the flagged chicken and saves it as `flagged_chicken.jpg`.
  2. Sends **both** the cropped photo + live sensor telemetry (DHT22 temp & humidity) to **Gemini 3.6 Flash**.
  3. Returns structured verdicts (`Low`, `Medium`, `High`, `Critical`) and automatically syncs them to Firebase (`/active_alerts`).

---

## ⏱️ 2. Synchronized 15-Minute Alert Cooldown
* **What it does:** Set a unified **15-minute (900 seconds)** global cooldown across **both** Gemini AI queries and Twilio SMS notifications.
* **Why it matters:** When chickens walk in and out of frame, ByteTrack assigns new tracker IDs. This 15-minute global cooldown eliminates duplicate alerts, prevents SMS spam, and saves API tokens.

---

## 🎥 3. Separated Feeds: Streaming vs. Detection
* **The Problem:** Running AI box-drawing directly on the video was causing low FPS and stuttering.
* **The Solution:** Separated the camera output into two independent feeds:
  * **📹 Streaming:** Pure, crystal-clear 30 FPS camera video with **NO bounding boxes or text** (zero lag).
  * **🎯 Detection:** AI Vision feed showing **YOLO bounding boxes**, tracking IDs (`#1`, `#2`), and health badges.
* **Backend Endpoints:**
  * `GET /raw_frame.jpg` & `/raw_video_feed` ➔ Clean 30 FPS stream.
  * `GET /frame.jpg` & `/video_feed` ➔ AI detection feed with boxes.

---

## 📱 4. 1-Tap Toggle in the Android App
* **What was added:** A 1-tap segmented switch right above the camera viewport in the **Monitor** screen:
  * Tap **`📹 Streaming`** ➔ View smooth, clean 30 FPS camera video without boxes.
  * Tap **`🎯 Detection`** ➔ Instantly reveal YOLO bounding boxes, tracking numbers, and health badges.
* Farmers can also tap the **`STREAMING` / `DETECTION`** badge on the video card to switch modes anytime.

---

## 📂 Key Files Modified
* `poultrysense_ai/poultrysense_ai.py` — Gemini 3.6 Flash multimodal crop trigger, 15-min cooldown, `/raw_frame.jpg` & `/raw_video_feed` endpoints.
* `app/src/main/res/layout/fragment_monitor.xml` — `[ 📹 Streaming | 🎯 Detection ]` toggle control.
* `app/src/main/java/com/example/poultrysense/ui/monitor/MonitorFragment.kt` — 1-tap feed switching logic and HUD status sync.
* `app/src/main/res/drawable/bg_segmented_container.xml` & `bg_segmented_active.xml` — Toggle UI assets.










## 🔧 Setup Issue & Resolution: NumPy / Compiler Build Error

### Symptom:
When running `setup_ai_server.bat`, `pip install -r requirements.txt` failed with:
```
..\meson.build:1:0: ERROR: Unknown compiler(s): [['icl'], ['cl'], ['cc'], ['gcc'], ['clang'], ['clang-cl'], ['pgcc']]
error: metadata-generation-failed
╰─> numpy
[ERROR] Failed to install dependencies.
```

### Root Cause:
The default system Python was set to Python 3.14 (`C:\Python314\python.exe`). AI and computer vision packages (such as `numpy<2.0.0`, `torch`, `torchvision`, `mediapipe`, and `opencv-python`) require Python 3.10 or 3.11 prebuilt binary wheels on Windows. On Python 3.14, prebuilt wheels are not available, forcing pip to attempt building NumPy from source using Meson, which fails when no C/C++ compiler is present.

### Solution Applied:
1. Re-created `poultrysense_ai/venv` using the installed Python 3.11 (`Python 3.11.9` via `py -3.11`).
2. Successfully installed all dependencies from `requirements.txt` via precompiled binary wheels.
3. Successfully ran `download_models.py` to retrieve `yolo26n.pt` and `yolo26n-cls.pt`.
4. Updated `setup_ai_server.bat` to automatically select Python 3.11 or Python 3.10 if available instead of falling back to incompatible versions like Python 3.14+.

---

## 🔧 Runtime Issue & Resolution: Missing Detection Boxes & Repeating ULTRALYTICS_SKIP_REQUIREMENTS_CHECKS

### Symptom:
When running `start_ai_server.bat`, the live stream showed no bounding boxes, and the terminal continuously repeated:
```
requirements: ULTRALYTICS_SKIP_REQUIREMENTS_CHECKS detected, skipping requirements check.
```

### Root Cause:
1. Ultralytics tracking (`det_model.track(...)` using BoT-SORT/ByteTrack) requires the `lap` (Linear Assignment Problem) library for bipartite matching.
2. `lap` was missing from `requirements.txt`.
3. Because `ULTRALYTICS_SKIP_REQUIREMENTS_CHECKS=1` was set, Ultralytics bypassed auto-installing `lap`, resulting in an internal `ModuleNotFoundError: No module named 'lap'` on every frame.
4. The tracking loop caught the error silently, causing 0 chicken boxes to be produced and logging the skipping message on each frame attempt.

### Solution Applied:
1. Installed `lap>=0.5.12` in the virtual environment and added it to `poultrysense_ai/requirements.txt`.
2. Updated `poultrysense_ai.py` tracking logic with fallback to standard detection (`det_model.predict`) if tracking encounters an error, ensuring bounding boxes are never lost.
3. Relaxed box handling to support frames where tracker IDs are initializing (`boxes.id is None`).

---

## 🚀 Hardware Optimization: AMD Ryzen 5 3500 + AMD Radeon RX 580 (Native DirectML & ByteTrack)

### Symptom:
When running on AMD Ryzen 5 3500 + AMD Radeon RX 580, the HUD and telemetry previously reported only ~2.3 AI/s (compared to ~9.1 on Intel setups), resulting in slow, sluggish detection and delayed bounding boxes.

### Root Cause:
1. **Artificial Rate Gate:** `DETECTION_MAX_RATE_HZ` was defaulted to `3.0`, causing the detection loop to deliberately sleep for ~333 ms on every frame. Furthermore, `load_env_file()` was invoked *after* reading `DETECTION_MAX_RATE_HZ`, preventing `.env` overrides from taking effect.
2. **Missing AMD GPU Acceleration:** The code only checked for NVIDIA CUDA and silently defaulted to CPU, bypassing the AMD Radeon RX 580.
3. **Heavy BoT-SORT ReID Overhead:** `pipeline/botsort.yaml` had `with_reid: true`, which ran a deep appearance feature extractor on every chicken crop, adding 200–300 ms of CPU latency per frame.
4. **Over-Strict Debounce Gate:** The detection loop required 3 consecutive hits before showing a box for any chicken under 0.50 confidence. At 2.3 Hz, this caused a 1.3-second delay, causing fast-moving chickens to drop tracking before boxes appeared.

### Solution Applied (Full AMD-Native Transition):
1. **Integrated Microsoft DirectML (DirectX 12 Compute):** Added `onnxruntime-directml` and automatic ONNX FP16 model export. Benchmarked at **71.8 FPS** raw detection and **62.9 FPS** tracking on the AMD Radeon RX 580.
2. **Dual Acceleration Backend Support:** Added `AI_ACCELERATION_BACKEND` in `.env` supporting `DIRECTML` (fastest on AMD GPU), `OPENVINO` (fallback/Intel compatibility), and `CPU`.
3. **Migrated to Tuned ByteTrack:** Integrated `pipeline/bytetrack.yaml` with two-stage association ($0.15 \le \text{conf} \le 0.30$) to maintain track continuity when chickens peck or become occluded.
4. **Unlocked Detection Rate to 12.0 Hz:** Configured `DETECTION_MAX_RATE_HZ=12.0` in `.env`, producing smooth, responsive bounding box rendering.
5. **Adaptive Bounding Box Filter & Fast Debounce:** Added `MIN_CHICKEN_BOX_SIZE=28` to detect distant flock members and tuned debounce (`required_hits = 1 if conf >= 0.40 else 2`) for instant box rendering.
