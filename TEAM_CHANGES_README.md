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
