<div align="center">

# 🐔 PoultrySense

### AI-Powered Anomaly & Environment Monitor for Poultry Farms

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Python](https://img.shields.io/badge/AI%20Server-Python%203.10+-3776AB?logo=python&logoColor=white)](https://www.python.org)
[![YOLO11](https://img.shields.io/badge/Detection-YOLO11-00FFFF?logo=ultralytics&logoColor=black)](https://ultralytics.com)
[![Flask](https://img.shields.io/badge/Server-Flask-000000?logo=flask&logoColor=white)](https://flask.palletsprojects.com)
[![Firebase](https://img.shields.io/badge/Auth-Firebase-FFCA28?logo=firebase&logoColor=black)](https://firebase.google.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**PoultrySense** is a full-stack smart monitoring system combining a Windows AI vision server and an Android mobile app. It uses **YOLO11** with a custom **Persistent Person Re-ID tracker** to stream real-time AI-processed video to your phone — no cloud, no latency, just raw on-device inference over your local Wi-Fi.

</div>

---

## ✨ Features

| Feature | Description |
|---|---|
| 🎯 **Person Detection** | YOLO11n detects people in real time with class-filtered inference (class 0 only) |
| 🧠 **Persistent Re-ID** | Custom visual appearance tracker keeps person IDs consistent even after leaving & re-entering the camera view |
| 🎥 **Live MJPEG Stream** | AI-annotated video streamed from PC to Android over local Wi-Fi |
| 📊 **Telemetry HUD** | Real-time FPS, person count, gallery ID count overlaid on the video feed |
| 🔥 **Firebase Auth** | Full sign-up / login / forgot password with Firebase Authentication |
| 🌡️ **Environment Monitor** | Home dashboard for flock activity index, environment alerts, and scan history |
| 📡 **Configurable IP** | Set your PC's IP directly from the Android profile settings — no hardcoding needed |
| 🪄 **One-click Server** | `start_ai_server.bat` / `stop_ai_server.bat` for easy server lifecycle management |

---

## 📁 Project Structure

```
PoultrySense/
├── app/                          # Android Studio Project
│   └── src/main/
│       ├── java/com/example/poultrysense/
│       │   ├── data/             # Data layer (ServerPreferences, Models, Repository)
│       │   └── ui/               # UI layer
│       │       ├── auth/         # Login, SignUp activities
│       │       ├── home/         # Home dashboard fragment
│       │       ├── monitor/      # Live AI stream & telemetry monitor
│       │       ├── alerts/       # Flock & environment alerts
│       │       ├── history/      # Scan history log
│       │       └── profile/      # Profile & AI server settings
│       └── res/                  # Layouts, drawables, values
│
├── poultrysense_ai/              # Python AI Vision Server
│   ├── poultrysense_ai.py        # Main server: YOLO detection + Re-ID tracker + Flask API
│   ├── yolo11n.pt                # YOLO11 Nano model weights (auto-downloaded if missing)
│   └── requirements.txt          # Python dependencies
│
├── start_ai_server.bat           # Windows: launch AI server in background
└── stop_ai_server.bat            # Windows: stop server & release camera
```

---

## 🛠️ Setup Guide

### Prerequisites

| Requirement | Version |
|---|---|
| Windows PC (for AI server) | Windows 10 / 11 |
| Python | 3.10 or 3.11 (recommended) |
| Android Studio | Hedgehog or newer |
| Android Device | API 26 (Android 8.0+) |
| Same Wi-Fi Network | PC & Android must be on the same LAN |

---

### Part 1 — AI Vision Server Setup (Windows PC)

#### Step 1: Clone the Repository

```bash
git clone https://github.com/BlueShewakram/PoultrySense-Andriod-Studio-.git
cd PoultrySense-Andriod-Studio-
```

#### Step 2: Create a Python Virtual Environment

```bash
cd poultrysense_ai
python -m venv venv
```

#### Step 3: Activate the Virtual Environment

```bash
venv\Scripts\activate
```

#### Step 4: Install Dependencies

```bash
pip install -r requirements.txt
```

> **Note:** `torch` and `ultralytics` are large packages (~800MB). This will take a few minutes on first install.

#### Step 5: Verify YOLO Model

The `yolo11n.pt` model file is included in the repo. If it's missing, the server will auto-download it from Ultralytics on first launch.

#### Step 6: Start the AI Server

**Option A — Double-click (easiest):**
```
Double-click: start_ai_server.bat
```

**Option B — Manual launch:**
```bash
cd poultrysense_ai
venv\Scripts\python.exe poultrysense_ai.py
```

The server starts at: **`http://0.0.0.0:5000`**

| Endpoint | Description |
|---|---|
| `http://<your-ip>:5000/` | Live monitoring dashboard |
| `http://<your-ip>:5000/video_feed` | MJPEG stream |
| `http://<your-ip>:5000/status` | JSON telemetry |
| `http://<your-ip>:5000/frame.jpg` | Single JPEG snapshot |
| `http://<your-ip>:5000/reset_ids` | Reset person ID gallery |

#### Step 7: Find Your PC's IP Address

```bash
ipconfig
```
Look for **IPv4 Address** under your active Wi-Fi adapter (e.g., `192.168.1.50`).

#### Step 8: Stop the Server

```
Double-click: stop_ai_server.bat
```

---

### Part 2 — Android App Setup

#### Step 1: Open in Android Studio

```
File → Open → Select the PoultrySense root folder
```

#### Step 2: Firebase Setup

> ⚠️ **Important:** The included `google-services.json` is a placeholder. You must configure your own Firebase project.

1. Go to [Firebase Console](https://console.firebase.google.com) → **Add project**
2. Register your Android app with package name: `com.example.poultrysense`
3. Download your `google-services.json`
4. Replace `app/google-services.json` with your downloaded file
5. Enable **Authentication → Email/Password** in Firebase Console

#### Step 3: Sync & Build

```
Android Studio → File → Sync Project with Gradle Files
Build → Make Project
```

#### Step 4: Run on Device

Connect your Android device via USB (with USB Debugging enabled) or use an emulator:

```
Run → Run 'app'
```

#### Step 5: Connect to AI Server

1. Open PoultrySense app
2. Go to **Profile → AI Server Settings**
3. Enter your PC's IP address (e.g., `192.168.1.50`)
4. Tap **Test Connection** → should show ✅ **Connected**
5. Navigate to the **Monitor** tab to see the live AI feed

---

## 🧠 How the Re-ID Tracker Works

The `PersonReIDTracker` class in `poultrysense_ai.py` solves the common problem of IDs changing when people leave and re-enter the camera view:

```
Detection → Extract multi-zone HSV+Lab appearance signature
          ↓
    Active Track Match? (IoU + visual cosine similarity + spatial distance)
          ↓                              ↓
        YES → update EMA profile     NO → Check Re-ID Gallery
                                          ↓            ↓
                                     Match ≥ 0.62   No match
                                       → restore ID  → new ID
```

- **Zone-based features**: Upper body, torso, and lower body color histograms in HSV and Lab color spaces
- **EMA adaptation**: Appearance profiles slowly adapt to lighting changes without drifting
- **Gallery persistence**: Up to unlimited persons stored — consistent IDs across the entire session
- **Smooth bounding boxes**: Exponential moving average on bbox coordinates reduces jitter

---

## 📦 Key Dependencies

### AI Server (Python)
| Package | Purpose |
|---|---|
| `ultralytics` | YOLO11 person detection |
| `opencv-python` | Video capture & image processing |
| `flask` | HTTP server for video stream & telemetry API |
| `numpy` | Feature vector operations |
| `torch` | YOLO model inference backend |

### Android App (Kotlin)
| Library | Purpose |
|---|---|
| Firebase Auth | User authentication |
| OkHttp | HTTP client for AI server communication |
| Gson | JSON parsing for telemetry |
| AndroidX CameraX | On-device camera preview |
| Kotlin Coroutines | Async stream polling |
| ViewBinding | Type-safe layout access |

---

## 🔧 Configuration

### Change Detection Confidence
In `poultrysense_ai.py`, adjust:
```python
results = yolo_model(frame, classes=[0], conf=0.40, iou=0.45, ...)
```

### Change Re-ID Sensitivity
```python
reid_tracker = PersonReIDTracker(reid_thresh=0.62)  # Lower = more lenient re-id
```

### Change Camera Source
```python
cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)  # Change 0 to 1, 2... for other cameras
```

### Change Stream Port
```python
flask_app.run(host='0.0.0.0', port=5000, ...)  # Change port here
```

---

## 🐞 Troubleshooting

| Problem | Solution |
|---|---|
| `Camera not opening` | Ensure no other app is using the webcam. Try `cv2.VideoCapture(1)` for external cameras. |
| `Server not reachable from phone` | Check Windows Firewall — allow port 5000 inbound. Verify both devices are on same Wi-Fi. |
| `IDs keep changing` | Lower `reid_thresh` to `0.55` in `PersonReIDTracker`. Ensure good lighting. |
| `YOLO model not found` | Delete partial `yolo11n.pt` and let it auto-download on next launch. |
| `Firebase auth error` | Verify `google-services.json` matches your Firebase project and Email/Password auth is enabled. |
| `pip install fails on torch` | Use Python 3.10 or 3.11. Avoid Python 3.13+. |

---

## 🚀 Quick Start Checklist

- [ ] Clone repo
- [ ] Set up Python venv and install requirements
- [ ] Run `start_ai_server.bat`
- [ ] Open browser at `http://localhost:5000` to verify feed
- [ ] Configure your own `google-services.json`
- [ ] Build and run Android app
- [ ] Set server IP in app Profile settings
- [ ] Open Monitor tab — enjoy live AI tracking!

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">

Made with ❤️ by **Blue Shewakram**

[🐛 Report Bug](https://github.com/BlueShewakram/PoultrySense-Andriod-Studio-/issues) · [💡 Request Feature](https://github.com/BlueShewakram/PoultrySense-Andriod-Studio-/issues)

</div>
