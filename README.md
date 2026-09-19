<div align="center">

# 🐔 PoultrySense

### AI-Powered Flock Health, Posture & Behavior Monitoring System

[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0+-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![AI Server: Python](https://img.shields.io/badge/AI%20Server-Python%203.10%20%7C%203.11-3776AB?logo=python&logoColor=white)](https://www.python.org)
[![Detection: YOLO](https://img.shields.io/badge/Vision-YOLO11%20%2F%20Custom%20Chicken%20Detector-00FFFF?logo=ultralytics&logoColor=black)](https://ultralytics.com)
[![Server: Flask](https://img.shields.io/badge/Server-Flask%20(Port%205050)-000000?logo=flask&logoColor=white)](https://flask.palletsprojects.com)
[![Auth: Firebase](https://img.shields.io/badge/Auth-Firebase%20BoM%2033.7.0-FFCA28?logo=firebase&logoColor=black)](https://firebase.google.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**PoultrySense** is an edge-native smart poultry farm monitoring system combining an **AI Vision Server** (supporting **macOS** and **Windows**) and a companion **Android mobile application**. It performs real-time poultry detection, visual re-identification tracking, and posture/motion-based health assessment (detecting normal, sitting, crouched, and lethargic birds) while streaming annotated live video and telemetry over your local Wi-Fi.

</div>

---

## ✨ System Capabilities

| Capability | Technical Description |
|---|---|
| 🐔 **Flock Detection** | Fast multi-bird detection using YOLO11 or trained custom chicken detector models |
| 🏷️ **Persistent Visual Re-ID** | Multi-zone HSV + Lab color appearance signatures keep bird IDs consistent across occlusions |
| 🩺 **Behavior & Health Scoring** | Real-time motion tracking and bounding-box geometry classify birds as **HEALTHY**, **LETHARGIC**, or exhibiting **ABNORMAL POSTURE** |
| 📈 **Flock Health Index** | Automatically computes an aggregate flock health score (0–100%) based on active bird statuses |
| 🎥 **Ultra-Low Latency MJPEG Stream** | Annotated video feed with HUD bounding boxes, trails, and health badges delivered over HTTP |
| 💻 **Cross-Platform AI Server** | Turnkey launcher scripts for both **macOS** (`start_ai_server.sh`) and **Windows** (`start_ai_server.bat`) |
| 🔌 **Non-Conflicting Port (5050)** | Standardized on port `5050` to avoid conflicts with macOS AirPlay Receiver on port `5000` |
| 📱 **Android Mobile Client** | Live stream viewer, telemetry HUD, health alerts, scan history, and configurable server IP/port |
| 🔬 **Dataset & Training Pipeline** | Dedicated dataset collection tools, Roboflow/CVAT conversion scripts, and YOLO training pipelines |

---

## 📁 Repository Structure

```
PoultrySense/
├── app/                                    # Android Studio Application (Kotlin)
│   ├── src/main/
│   │   ├── java/com/example/poultrysense/
│   │   │   ├── data/                       # Data layer (ServerPreferences, Telemetry, Models)
│   │   │   └── ui/                         # UI screens
│   │   │       ├── auth/                   # Firebase Login, SignUp, Password Reset
│   │   │       ├── home/                   # Farm Overview & Health Index Dashboard
│   │   │       ├── monitor/                # Live MJPEG Stream & CameraX telemetry view
│   │   │       ├── alerts/                 # Real-time flock health alert logs
│   │   │       ├── history/                # Historical monitoring scan records
│   │   │       └── profile/                # IP address & port connection settings
│   │   └── res/                            # Layouts, vector drawables, themes, strings
│   └── build.gradle.kts                    # App-level Gradle dependencies (compileSdk 34)
│
├── poultrysense_ai/                        # Edge AI Vision & Telemetry Server
│   ├── poultrysense_ai.py                  # Core inference server: YOLO + ReID + Behavior Engine + Flask
│   ├── behavior_health.py                  # Motion tracking & geometric posture assessment engine
│   ├── launch_server.py                    # Wrapper launcher with PID tracking & signal handling
│   ├── dataset_capture.py                  # CLI tool to capture labeled posture images from camera
│   ├── test_model_integration.py           # Verification script for YOLO model integration
│   ├── requirements.txt                    # Python dependencies (ultralytics, opencv, flask, etc.)
│   ├── dataset/                            # Posture collection reference & workflow
│   ├── tests/                              # Unit tests for detector selection and inference
│   └── training/                           # Model fine-tuning & annotation workflows
│       ├── CVAT_WORKFLOW.md                # Video annotation guide for CVAT
│       ├── train.sh                        # Training entrypoint
│       └── scripts/                        # Dataset preparation, Roboflow import, YOLO fine-tuning
│
├── start_ai_server.sh                      # macOS / Linux: Auto-detects IP, handles camera TCC permissions
├── start_ai_server.bat                     # Windows: Launches AI server on port 5050
├── stop_ai_server.bat                      # Windows: Terminates AI server process cleanly
├── build.gradle.kts                        # Root Gradle configuration
├── settings.gradle.kts                     # Gradle plugin & dependency resolution management
└── README.md                               # Project documentation
```

---

## 🛠️ Getting Started

### System Requirements

* **AI Server Host**:
  * **macOS** (Apple Silicon or Intel, macOS 12+) or **Windows 10 / 11**
  * **Python**: `3.10` or `3.11` recommended (avoid Python 3.13+ for PyTorch compatibility)
  * Web camera, USB coop camera, or RTSP stream
* **Android Device**:
  * Android 8.0+ (API level 24 minimum, target API 34)
  * Android Studio Hedgehog (2023.1.1) or newer
* **Network**:
  * Host computer and Android device connected to the same local Wi-Fi router (or use Android Emulator with loopback `10.0.2.2`).

---

### Part 1: AI Vision Server Setup

#### 1. Navigate to the AI Server Directory
```bash
cd poultrysense_ai
```

#### 2. Create and Activate Virtual Environment

**macOS / Linux:**
```bash
python3 -m venv venv
source venv/bin/activate
```

**Windows:**
```cmd
python -m venv venv
venv\Scripts\activate
```

#### 3. Install Dependencies
```bash
pip install --upgrade pip
pip install -r requirements.txt
```

> **Note:** `torch` and `ultralytics` will download backend weights. This may take 2–5 minutes depending on connection speed.

#### 4. Launch the Server

**Option A — macOS (Automated Script):**
From the repository root:
```bash
chmod +x start_ai_server.sh
./start_ai_server.sh
```
*This opens the server inside a dedicated Terminal window to request macOS Camera permissions (TCC) properly.*

**Option B — Windows (Automated Batch):**
From the repository root:
```cmd
start_ai_server.bat
```

**Option C — Manual CLI Launch (Any OS):**
```bash
cd poultrysense_ai
python launch_server.py
```

The AI Server will listen on **`http://0.0.0.0:5050`** by default.

#### 5. Verify the Server

Open your browser to:
* **Dashboard / Stream Preview:** `http://127.0.0.1:5050/`
* **Raw MJPEG Stream:** `http://127.0.0.1:5050/video_feed`
* **JSON Telemetry Endpoint:** `http://127.0.0.1:5050/status`
* **Snapshot JPEG:** `http://127.0.0.1:5050/frame.jpg`
* **Reset Re-ID Gallery:** `http://127.0.0.1:5050/reset_ids`

Example `/status` response:
```json
{
  "active": true,
  "fps": 28.4,
  "flock_count": 8,
  "healthy_count": 7,
  "ill_count": 1,
  "health_index": 87,
  "detections": [
    {
      "track_id": 1,
      "class": "chicken",
      "confidence": 0.88,
      "status": "HEALTHY",
      "behavior": "normal",
      "speed": 0.045,
      "aspect": 0.95,
      "bbox": [120, 85, 260, 240]
    },
    {
      "track_id": 3,
      "class": "chicken",
      "confidence": 0.79,
      "status": "ILL",
      "behavior": "lethargic",
      "speed": 0.002,
      "aspect": 1.48,
      "bbox": [310, 190, 480, 310]
    }
  ]
}
```

---

### Part 2: Android Application Setup

#### 1. Open in Android Studio
1. Launch **Android Studio**.
2. Select **Open** and choose the repository root folder.
3. Allow Gradle to sync dependencies (`compileSdk 34`, `Java 17`).

#### 2. Configure Firebase
1. Visit the [Firebase Console](https://console.firebase.google.com).
2. Create or select a project.
3. Register an Android app with package name: `com.example.poultrysense`.
4. Download your generated `google-services.json` and place it in the `app/` folder (overwriting `app/google-services.json`).
5. Enable **Authentication** (Email/Password provider) and **Realtime Database** in your Firebase console.

#### 3. Build & Run
1. Connect an Android phone via USB (with Developer Options and USB Debugging enabled) or start an Android Emulator.
2. Click **Run 'app'** (`Shift + F10`).

#### 4. Connect App to the AI Server
1. In the mobile app, navigate to **Profile → AI Server Settings**.
2. Enter your host machine's Wi-Fi IP address:
   * **Physical Android Device:** Enter your computer's local IP (e.g. `192.168.1.50`).
   * **Android Studio Emulator:** Enter `10.0.2.2` (Android emulator loopback address).
3. Tap **Test Connection**. You should see **Connected — server is reachable**.
4. Tap **Save Settings**.
5. Switch to the **Monitor** tab to view the live HUD stream with real-time flock analytics.

---

### Part 3: Dataset Capture & Model Training

PoultrySense includes dedicated utilities to capture training data and fine-tune detection models:

#### Capture Labeled Postures
Collect images for postures directly through your camera feed:
```bash
cd poultrysense_ai
python dataset_capture.py --label normal_standing --count 100 --interval 1.0 --show-window
python dataset_capture.py --label lethargic --count 100 --interval 1.0 --show-window
python dataset_capture.py --label sitting --count 100 --interval 1.0 --show-window
```

#### Train Custom Detector
Fine-tune a lightweight YOLO model on annotated poultry datasets:
```bash
cd poultrysense_ai/training
./train.sh
# or run the training script directly:
python scripts/train_chicken_detector.py --data datasets/chicken_det/data.yaml --epochs 50 --imgsz 640
```

Refer to `poultrysense_ai/training/CVAT_WORKFLOW.md` for video frame extraction and CVAT labeling procedures.

---

## 🧠 Behavior & Posture Assessment Engine

The behavior engine (`behavior_health.py`) uses a temporal sliding window to assess individual bird activity:

```
Bounding Box Detection
       ↓
Visual Re-ID Signature (HSV + Lab Color Histograms)
       ↓
Trajectory & Speed Tracking (Rolling Euclidean Centroid Shift)
       ↓
Aspect Ratio & Posture Analysis (Width / Height Geometry)
       ↓
┌───────────────────────────────────────────────┐
│ Speed < Lethargy Threshold → LETHARGIC (ILL)  │
│ Aspect Ratio > Threshold   → ABNORMAL POSTURE │
│ Nominal Motion & Upright   → HEALTHY          │
└───────────────────────────────────────────────┘
       ↓
Flock Health Index % = (Healthy Count / Total Birds) × 100
```

---

## 🔧 Environment Variables & Tuning

| Variable / Setting | Default | Description |
|---|---|---|
| `POULTRYSENSE_PORT` | `5050` | Server listening port (set in environment or shell) |
| `POULTRYSENSE_CAMERA` | `0` | Camera device index (or RTSP URL) |
| `reid_thresh` | `0.62` | Cosine similarity threshold for bird re-identification |
| `history_sec` | `8.0` | Observation window in seconds for lethargy scoring |
| `lethargy_speed` | `0.012` | Minimum centroid displacement rate (fraction of frame diagonal/sec) |
| `lying_aspect` | `1.35` | Width-to-height ratio above which bird is flagged as lying/sprawled |

---

## 🐞 Troubleshooting

| Issue | Cause | Solution |
|---|---|---|
| **Port 5000 in use** | macOS AirPlay Receiver uses port 5000 | PoultrySense defaults to port **5050**. Use `POULTRYSENSE_PORT=5050`. |
| **Camera permission denied on macOS** | Terminal lacks Camera entitlement | Launch using `./start_ai_server.sh` or enable in *System Settings → Privacy & Security → Camera → Terminal*. |
| **Android cannot reach server** | Firewall blocking port or wrong IP | 1. Ensure phone & PC are on identical Wi-Fi SSID.<br>2. Allow port 5050 through OS firewall.<br>3. For emulator, use IP `10.0.2.2`. |
| **PyTorch install errors** | Incompatible Python release | Ensure Python is 3.10.x or 3.11.x (`python --version`). |
| **Firebase Auth fails** | Missing or mismatched config | Replace `app/google-services.json` with your project's file and enable Email/Password provider. |

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
