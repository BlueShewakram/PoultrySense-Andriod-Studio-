# PoultrySense Manuscript — Chapter III Technical Revision Guide
**Document**: Capstone Proposal (BSIT — University of Cebu)  
**Authors**: Ababa, Josh Caleb S. | Beltran, Ivan Mathew M. | Cubol, Kevin Klien E. | Shewakram, Blue G.  
**Adviser**: Ms. Marijoy M. Yaun | **Technical Editor**: Ms. Omega A. Delfin  

---

## Instructions for Google Docs Update
Copy and paste the revised sections below directly into your Google Doc ([PoultrySense Manuscript](https://docs.google.com/document/d/1AAaBMfQZBde_EXK4ZPBARpfk2zmz12ogWPwaNnQrC8k/edit?tab=t.0)) to replace the outdated Flutter and legacy YOLO references.

---

### Section 1: Revised Table 13 — Technology Stack Diagram

*(Replaces lines 4833–4862 in the manuscript)*

#### Table 13: TECHNOLOGY STACK

| MOBILE APPLICATION | SERVER & CLOUD | AI MODEL & INFERENCE | IOT & EDGE HARDWARE |
| :--- | :--- | :--- | :--- |
| • Native Android (Kotlin) | • Python Flask / RESTful API | • Ultralytics YOLO26n (Fine-Tuned Chicken Detector) | • Desktop Computer (Intel Core i5-4570/i5-4590, 16 GB RAM, GTX 750 Ti, 256 GB SSD) |
| • Android Studio IDE | • Google Firebase Realtime Database | • Intel OpenVINO™ Runtime (AVX2/FMA CPU Acceleration, ~32ms/frame) | • USB HD Webcam (720p/1080p, Auto-Focus, USB 3.0) |
| • Android Jetpack ViewBinding | • Firebase Cloud Messaging (FCM) | • BoT-SORT Multi-Object Tracker with Appearance Re-ID (`yolo26n-cls.pt`) | • ESP32 Development Board (30-pin, 2.4GHz Wi-Fi) |
| • Material Design 3 (M3) Components | • Cloud Hosting & Storage | • OpenCV (Computer Vision & Frame Preprocessing) | • DHT22 Digital Temperature & Humidity Sensor |
| • Retrofit 2 & OkHttp3 (API Networking) | • JSON Data Serialization | • Google Gemini Flash Multimodal Reasoning Engine | • Monitoring Enclosure (5–10 Chickens Monitored Capacity) |
| • Glide (Image Loading & Caching) | • Git / GitHub (Version Control) | • NumPy & Pandas (Telemetry Analytics) | • 2.4GHz Wi-Fi Router / Local Area Network |
| • Android NotificationManager (Channel Alerts) | • Postman (API Testing) | • Spatial Re-ID & Pixel-Churn Gatekeeper | • Weather-Resistant Sensor Enclosure Pod |

---

### Section 2: Revised Table 14 — Software Specifications & Narrative

*(Replaces lines 4865–4890 in the manuscript)*

#### Table 14: SOFTWARE SPECIFICATIONS

| Component | Specification |
| :--- | :--- |
| **Operating System** | Windows 10/11 (64-bit) for Edge Computing Station; Android 10.0+ (API Level 29–35) for Mobile App |
| **Development Environment** | Android Studio (Koala/Ladybug), Visual Studio Code (Python & AI), Arduino IDE (ESP32 Firmware) |
| **Programming Language** | Kotlin (Native Android), Python 3.10+ (AI Server & Telemetry), C++ (ESP32 Firmware), PHP 8.x (Web Administration) |
| **AI & Computer Vision** | OpenCV, Ultralytics YOLO26n Fine-Tuned Detector, Intel OpenVINO™ 2024 Runtime, BoT-SORT Multi-Object Tracker with Appearance Re-ID (`yolo26n-cls.pt`), Google Gemini Flash Reasoning Engine |
| **Mobile Application Development** | Native Android SDK (Kotlin), Android Jetpack (ViewBinding, ViewModel, Lifecycle), Material Design 3 Components, MPAndroidChart |
| **Backend Framework** | Flask (Python RESTful Micro-Framework & Edge Streaming Server) / Native PHP 8.x (Web Administration) |
| **Database** | Google Firebase Realtime Database (Live Telemetry & Cloud Synchronization), MySQL 8.x / Local SQLite (Historical Session Logs) |
| **Cloud Platform** | Google Firebase (Realtime Database & Cloud Messaging) |
| **Version Control** | Git / GitHub |
| **API Testing** | Postman |

#### Narrative Description for Table 14:
> "Table 14 outlines the core software technologies required for the development and operation of the PoultrySense system. The system utilizes Python, OpenCV, and a custom fine-tuned **Ultralytics YOLO26n** object detection model optimized via the **Intel OpenVINO™ runtime** to perform high-speed, real-time chicken detection (~32 ms/frame) with minimal edge processing latency. Continuous identity tracking across bird occlusions and deformations is maintained through the **BoT-SORT algorithm with appearance re-identification (Re-ID)**, augmented by a **Google Gemini Flash** multimodal reasoning engine that evaluates behavioral deviations. The mobile application is developed natively in **Kotlin using Android Studio**, providing poultry farmers with an intuitive, responsive interface built with Material Design 3 and Android Jetpack components. The Android app receives real-time telemetry, visual bounding boxes, and immediate push notifications synchronized seamlessly through the **Google Firebase Realtime Database**, while firmware running on the ESP32 microcontroller continuously transmits DHT22 ambient temperature and humidity data. This software architecture delivers high-throughput edge processing, dependable wireless synchronization, and an accessible mobile monitoring experience tailored to small- and medium-scale poultry farming operations."

---

### Section 3: Sensor Telemetry Correction (Eliminating Weather API Contradiction)

*(Replace lines 870 and 877 in Chapter III)*

#### Revised Figure 3 Description (Farmer Program Workflow):
> *"Figure 3: Program Workflow illustrates the detailed program workflow for the farmer navigating the PoultrySense platform. The interaction begins with the farmer logging in using a registered account through the Login Screen, or tapping Create Account if they are new to the platform. Once logged in, the farmer is taken to the Dashboard/Home Screen, which acts as the main hub, presenting the flock summary — including the total flock count, the number of chickens marked Active & Normal, those flagged for attention, and active alerts — together with a live overview of the coop environment. From the dashboard, the farmer may start a new monitoring session, which opens the Live Monitoring Screen. This screen streams live video from the camera booth and displays real-time ambient temperature and humidity readings captured continuously by the on-site DHT22 hardware sensor array. While the session is active, the AI Module continuously analyzes the incoming video, tracking individual chickens and tagging health statuses as Healthy, Warning, or Critical. When a chicken is flagged for an anomaly or environmental parameters breach safe thresholds, the system generates an immediate push notification and displays an alert card on the Alerts Screen. From this alert, the farmer may tap View Camera to inspect the flagged chicken directly, or tap Resolve once the concern has been addressed. When concluded, the session records an aggregated summary into the History Screen for longitudinal flock management."*

#### Revised Figure 4 Description (IoT / Edge Device Program Workflow):
> *"Figure 4: IoT / Edge Device Program Workflow illustrates the underlying hardware and network process that powers PoultrySense's real-time monitoring capability. The process begins when the edge device and sensor pod are powered on inside the poultry facility. A high-definition webcam continuously captures video of the flock and streams frames via USB 3.0 to the edge computing PC, while an ESP32 microcontroller continuously samples real-time temperature and relative humidity levels from an onboard DHT22 digital sensor inside the enclosure. The edge PC executes the OpenVINO-accelerated YOLO26n detector and BoT-SORT tracker on the incoming frames, calculating individual chicken mobility rates, resting durations, and anomaly scores. These annotated metrics and DHT22 telemetry packets are synchronized directly with the Google Firebase Realtime Database over local Wi-Fi. The Firebase cloud database pushes real-time alert triggers directly to the farmer's native Android mobile application. This edge-to-cloud synchronization loop runs continuously, giving the farmer immediate visibility into flock health without overloading local network bandwidth."*

---

### Section 4: Sprint Plan Adjustments (Agile Sprint Plan in Chapter III)

* **Sprint 4 (AI Model Development and Training)**:
  * *Update*: Replace references to generic YOLOv8/11 with: *"Fine-tune YOLO26n lightweight detector on poultry dataset; export to Intel OpenVINO FP16 runtime for edge deployment; integrate BoT-SORT multi-object tracker with Re-ID embeddings (`yolo26n-cls.pt`) for persistent chicken identification."*
* **Sprint 5 (Backend and Cloud Infrastructure)**:
  * *Update*: Replace references to PHP/XAMPP with: *"Configure Google Firebase Realtime Database schema, setup RESTful Python AI edge endpoints, and establish live ESP32 DHT22 sensor telemetry pipeline."*
* **Sprint 6 (Mobile Application Development)**:
  * *Update*: Replace references to Flutter with: *"Develop Native Android application in Kotlin using Android Studio; implement ViewBinding, BottomNavigationView, Live Camera stream player, Firebase listeners, and Android System NotificationManager for high-priority anomaly alerts."*
