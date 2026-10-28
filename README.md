<div align="center">

# 🐔 PoultrySense
### Intelligent Real-Time Poultry Vision Hub & Farm Anomaly Monitor

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Python](https://img.shields.io/badge/AI%20Server-Python%203.10+-3776AB?logo=python&logoColor=white)](https://www.python.org)
[![YOLO11](https://img.shields.io/badge/Detection-YOLO11-00FFFF?logo=ultralytics&logoColor=black)](https://ultralytics.com)
[![Flask](https://img.shields.io/badge/Server-Flask-000000?logo=flask&logoColor=white)](https://flask.palletsprojects.com)
[![ESP32](https://img.shields.io/badge/IoT-ESP32-E7352C?logo=espressif&logoColor=white)](https://www.espressif.com)
[![Firebase](https://img.shields.io/badge/Auth%20%26%20DB-Firebase-FFCA28?logo=firebase&logoColor=black)](https://firebase.google.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**PoultrySense** is a state-of-the-art smart monitoring ecosystem designed for poultry farms and broiler houses. It connects an on-premise **Computer Vision AI Hub** (Windows/Linux) with an **ESP32 IoT Climate Node** and a native **Android Mobile App**. 

It streams real-time, low-latency, AI-annotated video (up to 30 FPS) with persistent multi-chicken tracking, 3-tier health classification, and behavior recognition directly over your local Wi-Fi without cloud fees.

</div>

---

## 🤖 One-Prompt Setup for AI Agents (Cursor, Antigravity, Claude, Copilot)

If you are cloning this repository and using an AI coding assistant (such as **Antigravity**, **Cursor**, **Claude Code**, or **GitHub Copilot Workspace**), simply copy and paste this single prompt:

```text
Please set up and launch PoultrySense AI on my machine:
1. Check if Python (3.10 or 3.11) is available.
2. In the poultrysense_ai/ directory, create a virtual environment (venv) if it doesn't already exist.
3. Install all required dependencies from poultrysense_ai/requirements.txt.
4. Run poultrysense_ai/download_models.py to verify and fetch the model weights (YOLO11n and custom pipeline models) into poultrysense_ai/models/.
5. Confirm that poultrysense_ai/pipeline/bytetrack.yaml is configured for chicken tracking (120-frame buffer, spatial re-identification).
6. Verify external camera index (1 or 0) and start the AI server using poultrysense_ai/poultrysense_ai.py on port 5000.
7. Print out my local IPv4 address and confirm that the web stream at http://localhost:5000 and UDP auto-discovery on port 5005 are active.
```

---

## ✨ Core System Features

| Feature | Description |
|---|---|
| 🎯 **Strict Chicken Detection** | Powered by **YOLO11 Nano** with class filtering strictly for poultry. Rejects hands, phones, persons, and background noise. |
| 🏷️ **Persistent Chicken IDs** | Custom **Spatial Re-Identification** and 120-frame **ByteTrack Kalman buffer** keep chicken IDs consistent (`Chicken #1` to `#10`) even during pecking, wing flapping, or brief occlusions. |
| 🏥 **Conservative Health Classification** | Multi-factor fusion engine combining deep visual classification, per-chicken tracking history, and locomotion vectors: <br>• 🟢 **HEALTHY**: Active walking, foraging, normal gait. <br>• 🟡 **NEEDS REVIEW**: Repeated low activity, weak visual health signal, or isolation. <br>• 🔴 **UNHEALTHY**: Strictly confirmed repeated lethargy evidence from the same chicken ID. |
| 🏃 **Real-Time Behavior AI** | Dynamically classifies states into `WALK`, `STAND`, and `PECK` with instant 0ms latency centroid velocity tracking. |
| ⚡ **Zero-Latency Multi-Threading** | Video capture and YOLO tracking run on the main thread (28–30 FPS), while secondary neural networks run on an asynchronous background worker thread. |
| 🎥 **Dual-Camera Auto-Switching** | Connects to external USB webcam (`index 1`) with seamless fallback to laptop webcam (`index 0`). |
| 📡 **Zero-Config Auto-Discovery** | Android app broadcasts on UDP port 5005 to automatically find the AI server IP on your LAN—no manual IP typing required! |
| 🌡️ **IoT Climate & Heat Stress** | Integrates ESP32 DHT22 sensor data (Temperature and Humidity) with stricter repeated-reading alert thresholds. |

---

## 📁 Repository Structure

```
PoultrySense/
├── setup_ai_server.bat           # One-click Windows installer (creates venv, installs deps, fetches models)
├── start_ai_server.bat           # One-click launch script for the AI Server
├── stop_ai_server.bat            # One-click stop script (frees camera & port 5000)
│
├── poultrysense_ai/              # Python AI Vision & Tracking Server
│   ├── poultrysense_ai.py        # Main engine: YOLO11 + ByteTrack + 3-Tier Health + Flask Streamer
│   ├── download_models.py        # Automated model downloader & integrity checker
│   ├── requirements.txt          # Python dependencies (PyTorch, Ultralytics, OpenCV, Flask)
│   ├── pipeline/
│   │   ├── bytetrack.yaml        # Tuned ByteTrack tracker configuration for poultry
│   │   ├── tracker.py            # Spatial re-identification module
│   │   └── pipeline.py           # Multi-model inference orchestrator
│   └── models/                   # Neural network weights directory
│       ├── PoultrySense_BehaviorModel.pt   # Stand/Peck/Walk classifier
│       ├── PoultrySense_HealthClassifier.pt # Health diagnostic model
│       └── PoultrySense_YOLOv8_ChickenDet.pt # Custom chicken detector
│
├── app/                          # Native Android Studio Project (Kotlin)
│   └── src/main/java/com/example/poultrysense/
│       ├── data/                 # Networking, UDP Auto-Discovery, Preferences
│       └── ui/                   # Home, Monitor, Alerts, Profile fragments
│
└── esp32/                        # IoT Environmental Sensor Firmware
    └── poultrysense_dht22/       # Arduino / C++ code for DHT22 temperature/humidity telemetry
```

---

## 🚀 Quick Setup Guide (Windows PC)

### Option 1 — One-Click Setup (Recommended)

1. Clone the repository:
   ```bash
   git clone https://github.com/BlueShewakram/PoultrySense-Andriod-Studio-.git
   cd PoultrySense-Andriod-Studio-
   ```
2. Double-click **`setup_ai_server.bat`**:
   * Automatically creates the Python virtual environment (`venv`).
   * Installs all dependencies (`torch`, `ultralytics`, `opencv`, `flask`).
   * Verifies pipeline and downloads the required base models.
3. Double-click **`start_ai_server.bat`** to start streaming.
4. Open `http://localhost:5000` in your browser to view the live dashboard!

---

### Option 2 — Manual Command-Line Setup

```bash
# 1. Navigate to AI folder
cd poultrysense_ai

# 2. Create and activate virtual environment
python -m venv venv
venv\Scripts\activate

# 3. Install dependencies
pip install --upgrade pip
pip install -r requirements.txt

# 4. Verify/download models
python download_models.py

# 5. Launch server
python poultrysense_ai.py
```

The server starts listening on:
* **Web & MJPEG Stream**: `http://0.0.0.0:5000`
* **UDP Auto-Discovery**: `0.0.0.0:5005`

---

## 📱 Android App Setup

1. Open **Android Studio** (Hedgehog or newer).
2. Click **File → Open** and select the repository root folder.
3. Replace `app/google-services.json` with your Firebase configuration (or keep the demo configuration for local monitoring).
   * Farmer profiles are persisted in **Firebase Realtime Database → `/users/{uid}`** (name, farm, role, Base64 avatar).
   * **Database rules matter:** the original demo rule `now < 1790438400000` expired on **2026-09-27**, after which Firebase rejects every read/write. Deploy the permanent rules in [`database.rules.json`](database.rules.json) from **Firebase Console → Realtime Database → Rules**, or run:
     ```bash
     firebase login
     firebase deploy --only database
     ```
   * Those rules keep `sensor_data/live` and `system_control` writable by the ESP32, while restricting `/users/{uid}` to the signed-in farmer.
4. Connect your Android phone via USB (or Wi-Fi pairing) and press **Run 'app'**.
5. Connect your phone to the **same Wi-Fi network** as your PC.
6. Open the app:
   * The app will **automatically discover** the AI server via UDP broadcast!
   * Or manually enter your PC's IPv4 address under **Profile → AI Server Settings**.
   * Navigate to the **Monitor** tab to view the live AI feed, chicken IDs, and real-time health badges.

---

## 🧠 Technical Architecture

```
                          ┌─────────────────────────────────────┐
                          │         HD Camera Feed (30 FPS)     │
                          └──────────────────┬──────────────────┘
                                             │
                                             ▼
                          ┌─────────────────────────────────────┐
                          │   YOLO11n + ByteTrack (Poultry)     │
                          │   • 120-frame Kalman Buffer         │
                          │   • Max 10 Chickens (No Overload)   │
                          └──────────────────┬──────────────────┘
                                             │
                      ┌──────────────────────┴──────────────────────┐
                      ▼                                             ▼
           Main Thread (0ms Latency)                     Background Worker Thread
    • Instant Centroid Motion Vectors               • Evaluates Crops Asynchronously
    • Spatial Re-ID (Keeps IDs Consistent)          • PoultrySense_HealthClassifier
    • In-Place ROI HUD Rendering                    • PoultrySense_BehaviorModel
                      │                                             │
                      └──────────────────────┬──────────────────────┘
                                             ▼
                          ┌─────────────────────────────────────┐
                          │  3-Tier Health Behavioral Fusion    │
                          │  • Walking/Mobile   ➔ HEALTHY 🟢    │
                          │  • Stationary/Iso   ➔ WARNING 🟡    │
                          │  • Immobile+Defect  ➔ UNHEALTHY 🔴  │
                          └──────────────────┬──────────────────┘
                                             │
                      ┌──────────────────────┴──────────────────────┐
                      ▼                                             ▼
           Web Dashboard & MJPEG Stream                UDP Broadcast (Port 5005)
             (http://0.0.0.0:5000)                    (PoultrySense Android App)
```

---

## 🌐 API Reference

| Endpoint | Protocol | Description |
|---|---|---|
| `GET /` | HTTP | Modern browser web dashboard with real-time video, FPS counter, and flock statistics |
| `GET /video_feed` | HTTP MJPEG | Multi-part live JPEG video stream for Android app & web browsers |
| `GET /status` | HTTP JSON | Real-time flock telemetry (`flock_count`, `healthy_count`, `warning_count`, `unhealthy_count`, `health_index`, `fps`) |
| `GET /frame.jpg` | HTTP JPEG | High-resolution single frame snapshot |
| `GET /reset_ids` | HTTP JSON | Resets the active chicken ID allocator |
| `POST /sensor_data`| HTTP JSON | Ingestion endpoint for ESP32 climate telemetry (`temperature`, `humidity`) |
| `UDP 5005` | UDP Broadcast | Auto-discovery listener responding with server IP and port |

---

## 🔧 Troubleshooting

| Issue | Resolution |
|---|---|
| **Camera appears dark** | Hardware auto-exposure is enabled by default. Ensure external webcam lens is unobstructed and lighting is sufficient. |
| **Video is lagging / low FPS** | Secondary AI is offloaded to a background thread. Ensure tracking is set to max 10 chickens (`MAX_TRACKED_CHICKENS = 10` in `poultrysense_ai.py`). |
| **IDs change when a chicken ducks or turns** | ByteTrack buffer is set to 120 frames with spatial re-identification. Ensure the camera angle captures the full pen clearly. |
| **Phone cannot connect to AI server** | Check your Windows Wi-Fi network profile: set it from **Public** to **Private** so Windows Firewall allows inbound connections on port 5000 and 5005. |
| **Port 5000 is already in use** | Run `stop_ai_server.bat` to terminate any previous Python process holding port 5000. |

---

## 🧠 PoultrySense AI - Gemini Verdict Engine Integration

This branch handles the integration of the **Gemini 1.5 Flash Verdict Engine** within the PoultrySense backend (`poultrysense_ai.py`). It processes telemetry data from the local vision engine (OpenVINO-accelerated YOLO) and generates structured diagnostic verdicts for poultry health monitoring.

### 📌 Current Status: System Instruction Enforcement (Temporary Fix)

> **Status Notice**: Full fine-tuning via Vertex AI is currently **PAUSED / PENDING BILLING ACTIVATION**.  
> The system is fully operational using **Gemini 1.5 Flash** with **System Instruction Schema Enforcement** as a functional alternative.

### 🔍 Why Fine-Tuning is Pending

* **No Fine-Tuning in Google AI Studio**: Fine-tuning features have been completely removed/deprecated inside Google AI Studio (`aistudio.google.com`). All model tuning capability has been permanently migrated to Google Cloud's **Vertex AI**.
* **Access & Billing Gate**: Because Google AI Studio no longer supports fine-tuning, performing supervised fine-tuning inside Vertex AI requires linking an active Google Cloud Billing Account / activating $300 Free Trial credits.
* **MFA Security Requirements**: Google Cloud Console requires mandatory Multi-Factor Authentication (MFA) on enterprise accounts to access fine-tuning training jobs.
* **Important Note**: To re-emphasize, there is **NO fine-tuning feature in Google AI Studio anymore**. Managed model weight training can only be performed via Vertex AI.

### 🛠️ Implemented Solution (Temporary Fix)

To maintain full system functionality without triggering enterprise cloud billing, the backend relies on **System Instruction Schema Enforcement** using standard Google AI Studio Developer Keys.

```
+------------------------+      +--------------------------+      +-------------------------------+
|   Local Vision Engine  | ---> |   poultrysense_ai.py     | ---> |  Gemini 1.5 Flash Engine      |
|  (YOLO Telemetry Data) |      | (System Prompt Enforcer) |      | (System Instruction Enforced) |
+------------------------+      +--------------------------+      +-------------------------------+
                                                                                  |
                                                                                  v
                                                                   Structured JSON Verdict Output
```

#### Key Differences: System Instructions vs. Fine-Tuned Model

| Feature | System Instructions (Current Setup) | Fine-Tuned Model (Vertex AI - Planned) |
|---|---|---|
| **Execution** | Prompt engineering rules sent per request | Weights modified through cloud GPU training |
| **Output Control** | Schema enforced via API parameters | Native output formatting learned during training |
| **Variability** | Evaluates telemetry conditions against rules | Contextually reasons on novel/unseen telemetry |
| **Setup Cost** | Free / Instant (Google Developer API) | Requires GCP Billing / Credit Card link |

### 📊 Dataset Work Completed (`gemini_lethargy_detection_tuning.csv`)

Although fine-tuning in Vertex AI is on hold, all preparatory data pipeline steps have been completed:
* **Dataset Path**: `poultrysense_ai/gemini_lethargy_detection_tuning.csv`
* **Entry Count**: 30 prompt-completion pairs covering lethargy, isolation, heat stress, and normal behavioral states.
* **Validation**: Verified programmatically via `upload_and_tune.py` using the official `google-genai` SDK.
* **Files API Upload**: Ingested into Google's Developer API (Resource ID: `files/53itfkyj5qc6`).

#### Diagnostic Output Schema
All verdicts output valid JSON with three mandatory fields:
```json
{
  "severity": "Low | Medium | High | Critical",
  "reason": "Physiological explanation based on motion delta and environment vectors.",
  "farmer_action": "Operational guidance for farm personnel."
}
```

### ⚙️ How to Switch to Fine-Tuned Model in the Future

The backend uses dynamic configuration via `gemini_config.json`. When fine-tuning is completed in Vertex AI:

1. Copy the resulting model ID (e.g., `tunedModels/poultrysense-chicken-lethargy-xxxx`).
2. Update `gemini_config.json`:
   ```json
   {
     "gemini_api_key": "YOUR_API_KEY",
     "model": "tunedModels/poultrysense-chicken-lethargy-xxxx",
     "cooldown_seconds": 300
   }
   ```
3. Restart the server (`.\start_ai_server.bat`). The server automatically pivots to the fine-tuned model without code changes.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

<div align="center">

Made with ❤️ for precision agriculture by **Blue Shewakram**

</div>
