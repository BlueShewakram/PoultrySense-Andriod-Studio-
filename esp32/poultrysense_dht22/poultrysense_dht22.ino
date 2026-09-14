#include <WiFi.h>
#include <WiFiManager.h>        // Captive portal library (tzapu)
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <time.h>
#include "DHT.h"

// ================= 1. PIN & HARDWARE CONFIG =================
#define DHTPIN       26         // DHT22 Data pin connected to GPIO 26
#define DHTTYPE      DHT22      // DHT 22 (AM2302)
#define LED_PIN      2          // Built-in status LED
#define RESET_BUTTON 0          // Built-in "BOOT" button on ESP32 (hold to reset Wi-Fi)

DHT dht(DHTPIN, DHTTYPE);

// ================= 2. CLOUD DATABASE CONFIG =================
// Direct to Firebase Realtime DB — No IP address needed anywhere!
const char* FIREBASE_HOST = "https://poultrysense-app-default-rtdb.asia-southeast1.firebasedatabase.app";

// Telemetry interval
unsigned long lastSendTime = 0;
const unsigned long SEND_INTERVAL = 3000; // Send telemetry every 3 seconds

// Forward declarations
void sendToFirebase(float temp, float hum);
void checkFactoryReset();
String getFormattedTime();

// =============================================================
//  SETUP
// =============================================================
void setup() {
  pinMode(LED_PIN, OUTPUT);
  pinMode(RESET_BUTTON, INPUT_PULLUP);
  digitalWrite(LED_PIN, LOW);

  Serial.begin(115200);
  delay(1000);

  Serial.println("\n================================================");
  Serial.println("  POULTRYSENSE - 100% PURE CLOUD TELEMETRY      ");
  Serial.println("================================================");

  // Check if buyer is holding the BOOT button to clear Wi-Fi
  checkFactoryReset();

  // 1. Initialize DHT22 Sensor on Pin 26
  dht.begin();
  Serial.println("[SENSOR] DHT22 initialized on GPIO 26.");

  // 2. Initialize WiFiManager (ONLY asks for Wi-Fi Name and Password!)
  WiFiManager wifiManager;

  // Visual customization for the phone captive portal
  wifiManager.setTitle("PoultrySense Smart Connect");
  wifiManager.setConfigPortalTimeout(180); // 3 minutes timeout

  Serial.println("[WIFI] Connecting to saved Wi-Fi or opening setup portal...");

  // Blink LED to indicate connection attempt
  digitalWrite(LED_PIN, HIGH);

  // If no saved Wi-Fi, creates hotspot: "PoultrySense-Setup"
  // User connects on phone -> chooses Wi-Fi & enters password -> Done!
  bool connected = wifiManager.autoConnect("PoultrySense-Setup");

  if (!connected) {
    Serial.println("\n❌ [WIFI] Setup timed out or failed. Restarting...");
    digitalWrite(LED_PIN, LOW);
    delay(3000);
    ESP.restart();
  }

  // 3. Synchronize Real-World Clock via Internet NTP (UTC+8 for Philippines / Asia)
  configTime(8 * 3600, 0, "pool.ntp.org", "time.google.com");
  Serial.println("[TIME] Synchronizing real-time clock via NTP (UTC+8)...");

  // Connected successfully!
  digitalWrite(LED_PIN, HIGH); // Solid LED = Online
  Serial.println("\n✅ [SUCCESS] Connected to Wi-Fi!");
  Serial.print("[INFO] ESP32 IP   : ");
  Serial.println(WiFi.localIP());
  Serial.print("[INFO] Wi-Fi SSID : ");
  Serial.println(WiFi.SSID());
  Serial.printf("[INFO] Cloud Target: %s\n\n", FIREBASE_HOST);
}

// =============================================================
//  LOOP
// =============================================================
void loop() {
  // Check factory reset button at ANY time while running
  checkFactoryReset();

  // Allow typing "reset" in Serial Monitor to wipe Wi-Fi
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.equalsIgnoreCase("reset")) {
      Serial.println("\n🧨 [COMMAND] 'reset' command received! Clearing Wi-Fi...");
      WiFiManager wm;
      wm.resetSettings();
      delay(1000);
      ESP.restart();
    }
  }

  unsigned long now = millis();

  // Transmit telemetry every SEND_INTERVAL ms
  if (now - lastSendTime >= SEND_INTERVAL) {
    lastSendTime = now;

    // Read real sensor values from DHT22 on Pin 26
    float humidity    = dht.readHumidity();
    float temperature = dht.readTemperature(); // Celsius

    // Check if the sensor read succeeded
    if (isnan(humidity) || isnan(temperature)) {
      Serial.println("⚠️ [WARN] Could not read from DHT22 on Pin 26.");
      Serial.println("   -> Verify Data is on Pin 26, GND is connected, and VCC is on 3V3 or VIN.");
      return;
    }

    // Print readings to Serial Monitor
    String timeStr = getFormattedTime();
    Serial.println("\n------------------------------------------");
    Serial.printf("🕒 Time        : %s\n", timeStr.c_str());
    Serial.printf("🌡️ Temperature : %.1f °C\n", temperature);
    Serial.printf("💧 Humidity    : %.1f %%\n", humidity);

    if (WiFi.status() == WL_CONNECTED) {
      // Send directly to Firebase Cloud Database (No local PC or IP needed!)
      sendToFirebase(temperature, humidity);
    } else {
      Serial.println("⚠️ [WIFI] Connection lost. Reconnecting...");
      digitalWrite(LED_PIN, LOW);
      WiFi.reconnect();
    }
  }
}

// ─────────────────────────────────────────────────────────────
//  Send directly to Firebase Cloud Database
// ─────────────────────────────────────────────────────────────
void sendToFirebase(float temp, float hum) {
  HTTPClient https;
  WiFiClientSecure client;
  client.setInsecure(); // Allows SSL without complex certificate handling

  String url = String(FIREBASE_HOST) + "/sensor_data/live.json";
  https.begin(client, url);
  https.addHeader("Content-Type", "application/json");
  https.setTimeout(4000);

  // Determine climate status
  String climateStatus = "NORMAL";
  if (temp > 30.0) climateStatus = "HEAT ALERT";
  else if (temp < 18.0) climateStatus = "COLD ALERT";
  else if (hum > 75.0)  climateStatus = "HIGH HUMIDITY";
  else if (hum < 40.0)  climateStatus = "DRY AIR";

  // Human-readable timestamp (e.g. "13 Sep 2026, 06:46:15 PM")
  String timeStr = getFormattedTime();

  // Schema matching PoultrySense EnvironmentRepository
  String payload = "{";
  payload += "\"temperature\":" + String(temp, 1) + ",";
  payload += "\"humidity\":"    + String(hum,  1) + ",";
  payload += "\"status\":\""    + climateStatus   + "\",";
  payload += "\"zone\":\"Barn Alpha • Zone 1\",";
  payload += "\"timestamp\":\"" + timeStr         + "\"";
  payload += "}";

  int httpCode = https.sendRequest("PATCH", payload);

  if (httpCode > 0) {
    Serial.printf("🔥 [FIREBASE] HTTP %d OK - Cloud Database Updated live!\n", httpCode);
  } else {
    Serial.printf("❌ [FIREBASE] Error (%s) - Check internet connection\n", https.errorToString(httpCode).c_str());
  }

  https.end();
}

// ─────────────────────────────────────────────────────────────
//  Helper: Format human-readable date & time from NTP
// ─────────────────────────────────────────────────────────────
String getFormattedTime() {
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo, 200)) {
    return "Syncing...";
  }
  char buf[40];
  // Example: "13 Sep 2026, 06:46:15 PM"
  strftime(buf, sizeof(buf), "%d %b %Y, %I:%M:%S %p", &timeinfo);
  return String(buf);
}

// ─────────────────────────────────────────────────────────────
//  FACTORY RESET: Hold BOOT (GPIO 0) during power-up
// ─────────────────────────────────────────────────────────────
void checkFactoryReset() {
  if (digitalRead(RESET_BUTTON) == LOW) {
    Serial.println("\n⚠️ [RESET] BOOT button detected held down!");
    Serial.println("           Keep holding for 3 seconds to clear Wi-Fi...");
    
    int holdCount = 0;
    while (digitalRead(RESET_BUTTON) == LOW && holdCount < 30) {
      delay(100);
      digitalWrite(LED_PIN, !digitalRead(LED_PIN)); // Fast blink
      holdCount++;
    }

    if (holdCount >= 30) {
      Serial.println("\n🧨 [RESET] Resetting Wi-Fi settings!");
      WiFiManager wm;
      wm.resetSettings();

      Serial.println("   - Rebooting now into Setup Hotspot Mode!");
      for (int i = 0; i < 10; i++) {
        digitalWrite(LED_PIN, !digitalRead(LED_PIN));
        delay(80);
      }
      ESP.restart();
    } else {
      Serial.println("ℹ️ [RESET] Button released early. Continuing normal boot...");
    }
  }
}
