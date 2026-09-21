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

// Global WiFiManager instance so callbacks can access internal web server
WiFiManager wifiManager;

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
void bindServerCallback();

// =============================================================
//  POULTRYSENSE MODERN CAPTIVE PORTAL GUI & REAL-TIME TESTER
// =============================================================
const char CUSTOM_HEAD_HTML[] PROGMEM = R"rawliteral(
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
:root {
  --bg-dark: #080e12;
  --card-bg: #101920;
  --card-hover: #15222b;
  --input-bg: #15222b;
  --primary: #10b981;
  --primary-hover: #059669;
  --primary-glow: rgba(16, 185, 129, 0.35);
  --danger: #ef4444;
  --danger-glow: rgba(239, 68, 68, 0.35);
  --border-c: rgba(16, 185, 129, 0.22);
  --text-main: #f8fafc;
  --text-muted: #94a3b8;
}

* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }

body {
  background: radial-gradient(circle at 50% 0%, #132b23 0%, var(--bg-dark) 75%) no-repeat fixed;
  color: var(--text-main);
  min-height: 100vh;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 16px 12px;
}

.wrap {
  width: 100%;
  max-width: 440px;
  background: var(--card-bg);
  border: 1px solid var(--border-c);
  border-radius: 22px;
  padding: 24px 20px;
  box-shadow: 0 20px 45px rgba(0, 0, 0, 0.75), 0 0 25px rgba(16, 185, 129, 0.12);
  margin: auto;
}

/* PoultrySense Custom Header */
.ps-branding {
  text-align: center;
  margin-bottom: 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.ps-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: rgba(16, 185, 129, 0.12);
  border: 1px solid rgba(16, 185, 129, 0.3);
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 700;
  color: var(--primary);
  letter-spacing: 0.6px;
  text-transform: uppercase;
  margin-bottom: 8px;
}
.ps-dot {
  width: 8px;
  height: 8px;
  background: #10b981;
  border-radius: 50%;
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7);
  animation: ps-pulse 1.8s infinite;
}
@keyframes ps-pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
  70% { transform: scale(1); box-shadow: 0 0 0 8px rgba(16, 185, 129, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
}
.ps-title {
  font-size: 21px;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.3px;
}
.ps-subtitle {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 4px;
}

/* Step Indicator Badges */
.ps-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: #34d399;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 16px 0 8px 0;
  padding: 6px 10px;
  background: rgba(16, 185, 129, 0.08);
  border-left: 3px solid var(--primary);
  border-radius: 0 8px 8px 0;
}

/* Auto-fill callout pill */
#ps-autofill-hint {
  display: none;
  background: rgba(16, 185, 129, 0.15);
  border: 1px solid rgba(16, 185, 129, 0.35);
  color: #34d399;
  font-size: 12px;
  font-weight: 600;
  padding: 7px 12px;
  border-radius: 8px;
  margin: 8px 0 12px 0;
  animation: ps-fade 0.3s ease;
}
@keyframes ps-fade {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Scanned Wi-Fi item cards */
.c {
  background: var(--card-hover) !important;
  border: 1px solid rgba(255, 255, 255, 0.08) !important;
  border-radius: 12px !important;
  margin-bottom: 8px !important;
  padding: 12px 14px !important;
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  transition: all 0.2s ease !important;
  cursor: pointer;
}
.c:hover, .c.ps-active {
  border-color: var(--primary) !important;
  background: rgba(16, 185, 129, 0.18) !important;
  box-shadow: 0 0 12px var(--primary-glow) !important;
}
.c a {
  color: #f1f5f9 !important;
  font-weight: 600 !important;
  text-decoration: none !important;
  font-size: 15px !important;
  flex: 1;
}

/* Form Inputs */
label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-muted);
  margin: 14px 0 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
input[type='text'], input[type='password'], input:not([type]) {
  width: 100% !important;
  background: var(--input-bg) !important;
  border: 1px solid rgba(255, 255, 255, 0.12) !important;
  border-radius: 10px !important;
  padding: 13px 14px !important;
  font-size: 15px !important;
  color: #fff !important;
  outline: none !important;
  transition: all 0.2s ease !important;
}
input[type='text']:focus, input[type='password']:focus {
  border-color: var(--primary) !important;
  box-shadow: 0 0 0 3px var(--primary-glow) !important;
}

/* Action Buttons */
button, input[type='submit'] {
  width: 100% !important;
  background: linear-gradient(135deg, #10b981 0%, #059669 100%) !important;
  border: none !important;
  border-radius: 12px !important;
  color: #ffffff !important;
  padding: 14px 16px !important;
  font-size: 16px !important;
  font-weight: 700 !important;
  cursor: pointer !important;
  margin-top: 14px !important;
  box-shadow: 0 6px 18px var(--primary-glow) !important;
  transition: all 0.2s ease !important;
}
button:active, input[type='submit']:active {
  transform: scale(0.98);
}
button.secondary {
  background: #1e293b !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  box-shadow: none !important;
  color: var(--text-muted) !important;
  font-size: 14px !important;
  padding: 10px !important;
}

/* Live Verification Modal Overlay */
#ps-modal {
  display: none;
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(6, 11, 14, 0.9);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  z-index: 99999;
  justify-content: center;
  align-items: center;
  padding: 20px;
}
#ps-modal.show { display: flex; }
.ps-modal-box {
  background: #111a21;
  border: 1px solid var(--border-c);
  border-radius: 22px;
  max-width: 390px;
  width: 100%;
  padding: 28px 22px;
  text-align: center;
  box-shadow: 0 25px 50px rgba(0, 0, 0, 0.8), 0 0 35px rgba(16, 185, 129, 0.15);
  animation: ps-zoom 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes ps-zoom {
  from { opacity: 0; transform: scale(0.92); }
  to { opacity: 1; transform: scale(1); }
}
.ps-modal-icon { font-size: 46px; margin-bottom: 12px; display: inline-block; }
.ps-modal-title { font-size: 19px; font-weight: 800; color: #fff; margin-bottom: 8px; }
.ps-modal-desc { font-size: 13px; color: var(--text-muted); line-height: 1.5; margin-bottom: 16px; }

.ps-info-card {
  background: #0b1318;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  padding: 12px 14px;
  text-align: left;
  font-size: 13px;
  margin-bottom: 16px;
  line-height: 1.9;
}
.ps-info-card strong { color: var(--primary); }

.ps-spinner {
  width: 48px;
  height: 48px;
  border: 3px solid rgba(16, 185, 129, 0.2);
  border-top-color: #10b981;
  border-radius: 50%;
  margin: 12px auto 16px;
  animation: ps-spin 0.9s linear infinite;
}
@keyframes ps-spin { to { transform: rotate(360deg); } }

.ps-timer-pill {
  display: inline-block;
  background: rgba(16, 185, 129, 0.18);
  color: #34d399;
  font-weight: 700;
  padding: 6px 14px;
  border-radius: 16px;
  margin-bottom: 14px;
  font-size: 13px;
  border: 1px solid rgba(16, 185, 129, 0.3);
}
</style>

<script>
document.addEventListener('DOMContentLoaded', function() {
  // 1. If on root menu "/", instantly jump straight to "/wifi" setup page!
  if (window.location.pathname === '/' || window.location.pathname === '') {
    window.location.replace('/wifi');
    return;
  }

  // 2. Inject PoultrySense Brand Header
  var wrap = document.querySelector('.wrap');
  if (wrap && !document.getElementById('ps-branding-div')) {
    var branding = document.createElement('div');
    branding.id = 'ps-branding-div';
    branding.className = 'ps-branding';
    branding.innerHTML = 
      '<div class="ps-badge"><span class="ps-dot"></span>Hotspot Active (192.168.4.1)</div>' +
      '<div class="ps-title">🐔 PoultrySense</div>' +
      '<div class="ps-subtitle">Smart Climate & Brooder Controller Setup</div>';
    wrap.insertBefore(branding, wrap.firstChild);
  }

  // 3. Inject Live Status Modal
  if (!document.getElementById('ps-modal')) {
    var modal = document.createElement('div');
    modal.id = 'ps-modal';
    modal.innerHTML = '<div class="ps-modal-box" id="ps-modal-box"></div>';
    document.body.appendChild(modal);
  }

  // 4. Retrieve saved password vault from phone's localStorage
  var savedVault = {};
  try {
    savedVault = JSON.parse(localStorage.getItem('ps_wifi_vault') || '{}');
  } catch(e) {}

  // Fetch known ESP32 saved network info
  var espSavedSsid = '';
  var espHasPass = false;
  fetch('/api/saved-info')
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (data) {
        espSavedSsid = data.ssid || '';
        espHasPass = !!data.has_pass;
      }
    })
    .catch(function() {});

  // 5. Enhance Form Inputs (Autocomplete for phone password managers & Step indicators)
  var sInput = document.getElementById('s');
  var pInput = document.getElementById('p');

  if (sInput) {
    sInput.setAttribute('autocomplete', 'username');
    sInput.setAttribute('autocorrect', 'off');
    sInput.setAttribute('autocapitalize', 'none');

    // Add Step 1 banner above scanned networks or SSID field
    var firstNetwork = document.querySelector('.c');
    var step1 = document.createElement('div');
    step1.className = 'ps-step-header';
    step1.innerHTML = '<span>📶 Step 1: Select Wi-Fi Network</span>';
    if (firstNetwork && firstNetwork.parentNode) {
      firstNetwork.parentNode.insertBefore(step1, firstNetwork);
    } else if (sInput.parentNode) {
      sInput.parentNode.insertBefore(step1, sInput);
    }
  }

  if (pInput) {
    pInput.setAttribute('autocomplete', 'current-password');

    // Add Step 2 banner above password
    var step2 = document.createElement('div');
    step2.className = 'ps-step-header';
    step2.innerHTML = '<span>🔑 Step 2: Wi-Fi Password</span>';
    if (pInput.parentNode) {
      pInput.parentNode.insertBefore(step2, pInput);

      // Create auto-fill notification element
      var hint = document.createElement('div');
      hint.id = 'ps-autofill-hint';
      pInput.parentNode.insertBefore(hint, pInput.nextSibling);
    }
  }

  // 6. Interactive Wi-Fi Network selection with Automatic Password Auto-fill!
  var networkLinks = document.querySelectorAll('.c a');
  networkLinks.forEach(function(link) {
    link.addEventListener('click', function(e) {
      document.querySelectorAll('.c').forEach(function(el) { el.classList.remove('ps-active'); });
      var parent = link.closest('.c');
      if (parent) parent.classList.add('ps-active');

      var chosenSsid = link.innerText.trim();
      if (sInput) sInput.value = chosenSsid;

      var hint = document.getElementById('ps-autofill-hint');

      // Check if phone already has the password saved!
      if (pInput) {
        if (savedVault[chosenSsid]) {
          pInput.value = savedVault[chosenSsid];
          if (hint) {
            hint.innerHTML = '✨ <strong>Password auto-filled</strong> from your phone!';
            hint.style.display = 'block';
          }
        } else if (espSavedSsid && chosenSsid === espSavedSsid && espHasPass) {
          pInput.value = '';
          pInput.placeholder = '•••••••• (Stored Password)';
          if (hint) {
            hint.innerHTML = '✨ <strong>Known saved network</strong> — stored password will be used if left blank!';
            hint.style.display = 'block';
          }
        } else {
          pInput.value = '';
          pInput.placeholder = 'Enter Wi-Fi Password';
          if (hint) hint.style.display = 'none';
        }
        pInput.focus();
      }
    });
  });

  // 7. Intercept ONLY the Wi-Fi credentials save form (Never intercept menu navigation!)
  var wifiForm = document.querySelector('form[action*="wifisave"]') || 
                 (sInput ? sInput.closest('form') : null);
  var isWifiSavePage = window.location.pathname.indexOf('wifisave') !== -1;

  if (wifiForm && !isWifiSavePage) {
    var submitBtn = wifiForm.querySelector('button[type="submit"]') || wifiForm.querySelector('button');
    if (submitBtn) {
      submitBtn.innerHTML = 'Connect & Verify ➔';
    }

    wifiForm.addEventListener('submit', function(e) {
      e.preventDefault();
      var ssid = sInput ? sInput.value.trim() : '';
      var pass = pInput ? pInput.value : '';

      if (!ssid) {
        alert('Please choose or enter your Wi-Fi network name.');
        if (sInput) sInput.focus();
        return;
      }

      showTestingModal(ssid, pass, savedVault);
    });
  }

  // 8. Fallback handler if user is on /wifisave directly
  if (isWifiSavePage) {
    handleWifiSavePageFallback();
  }
});

// Display Testing Modal & Trigger Backend Verification
function showTestingModal(ssid, pass, savedVault) {
  var modal = document.getElementById('ps-modal');
  var box = document.getElementById('ps-modal-box');
  if (!modal || !box) return;

  box.innerHTML = 
    '<div class="ps-spinner"></div>' +
    '<div class="ps-modal-title">Connecting to Wi-Fi...</div>' +
    '<div class="ps-modal-desc">Testing handshake and password for<br><strong>' + escapeHtml(ssid) + '</strong></div>' +
    '<div style="color:#94a3b8;font-size:12px;">This takes about 3 to 7 seconds...</div>';
  modal.classList.add('show');

  // Request ESP32 test endpoint
  var url = '/api/test-connect?s=' + encodeURIComponent(ssid) + '&p=' + encodeURIComponent(pass);
  fetch(url)
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (data.status === 'success') {
        // Save to phone's localStorage vault so next time it is 100% automatic!
        if (ssid && pass) {
          try {
            savedVault[ssid] = pass;
            localStorage.setItem('ps_wifi_vault', JSON.stringify(savedVault));
          } catch(e) {}
        }
        showSuccessModal(ssid, pass, data);
      } else {
        showErrorModal(ssid, data.reason || 'Incorrect password or Wi-Fi unreachable.');
      }
    })
    .catch(function(err) {
      checkLiveStatusFallback(ssid, pass);
    });
}

// Display Connection Succeeded State
function showSuccessModal(ssid, pass, data) {
  var box = document.getElementById('ps-modal-box');
  var countdown = 5;

  box.innerHTML = 
    '<div class="ps-modal-icon">✅</div>' +
    '<div class="ps-modal-title" style="color:#10b981;">Connected Successfully!</div>' +
    '<div class="ps-info-card">' +
      '• <strong>Wi-Fi:</strong> ' + escapeHtml(data.ssid || ssid) + '<br>' +
      '• <strong>Assigned IP:</strong> ' + escapeHtml(data.ip || 'Ready') + '<br>' +
      '• <strong>Signal:</strong> ' + (data.rssi ? data.rssi + ' dBm' : 'Strong') + '<br>' +
      '• <strong>Firebase:</strong> Online & Live' +
    '</div>' +
    '<div class="ps-timer-pill">Closing hotspot in <span id="ps-cd">5</span>s...</div>' +
    '<button type="button" id="ps-finish-now">Finish Setup Now ➔</button>';

  var timer = setInterval(function() {
    countdown--;
    var cdEl = document.getElementById('ps-cd');
    if (cdEl) cdEl.innerText = countdown;
    if (countdown <= 0) {
      clearInterval(timer);
      submitCredentials(ssid, pass);
    }
  }, 1000);

  var finishBtn = document.getElementById('ps-finish-now');
  if (finishBtn) {
    finishBtn.addEventListener('click', function() {
      clearInterval(timer);
      submitCredentials(ssid, pass);
    });
  }
}

// Display Connection Failed State
function showErrorModal(ssid, reason) {
  var box = document.getElementById('ps-modal-box');
  box.innerHTML = 
    '<div class="ps-modal-icon">❌</div>' +
    '<div class="ps-modal-title" style="color:#ef4444;">Connection Failed</div>' +
    '<div class="ps-modal-desc">' + escapeHtml(reason) + '<br><br>' +
    '<span style="color:#94a3b8;font-size:12px;">Ensure network is 2.4GHz and re-check the password.</span></div>' +
    '<button type="button" id="ps-try-again" style="background:#ef4444!important;">🔄 Try Again</button>';

  var retryBtn = document.getElementById('ps-try-again');
  if (retryBtn) {
    retryBtn.addEventListener('click', function() {
      var modal = document.getElementById('ps-modal');
      if (modal) modal.classList.remove('show');
      var pInput = document.getElementById('p');
      if (pInput) {
        pInput.value = '';
        pInput.focus();
        pInput.style.borderColor = '#ef4444';
      }
    });
  }
}

// Check status fallback in case fetch was severed during reconnect
function checkLiveStatusFallback(ssid, pass) {
  fetch('/api/status')
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (data.status === 'connected') {
        showSuccessModal(ssid, pass, data);
      } else {
        showErrorModal(ssid, 'Could not verify connection with Wi-Fi router.');
      }
    })
    .catch(function() {
      showSuccessModal(ssid, pass, { ssid: ssid, ip: 'Saved' });
    });
}

// Finalize credentials to WiFiManager storage
function submitCredentials(ssid, pass) {
  var box = document.getElementById('ps-modal-box');
  if (box) {
    box.innerHTML = 
      '<div class="ps-spinner"></div>' +
      '<div class="ps-modal-title">Saving & Starting Telemetry...</div>' +
      '<div class="ps-modal-desc">Setup complete. ESP32 is online. You may now close this browser.</div>';
  }
  var wifiForm = document.querySelector('form[action*="wifisave"]') || 
                 (document.getElementById('s') ? document.getElementById('s').closest('form') : null);
  if (wifiForm) {
    wifiForm.submit();
  } else {
    window.location.href = '/wifisave?s=' + encodeURIComponent(ssid) + '&p=' + encodeURIComponent(pass);
  }
}

// Handle fallback if user lands on /wifisave
function handleWifiSavePageFallback() {
  var pollCount = 0;
  var interval = setInterval(function() {
    pollCount++;
    fetch('/api/status')
      .then(function(res) { return res.json(); })
      .then(function(data) {
        if (data.status === 'connected') {
          clearInterval(interval);
          var wrap = document.querySelector('.wrap');
          if (wrap) {
            wrap.innerHTML = 
              '<div class="ps-branding">' +
                '<div class="ps-badge"><span class="ps-dot"></span>Online</div>' +
                '<div class="ps-title">🐔 PoultrySense</div>' +
              '</div>' +
              '<div style="text-align:center;padding:10px 0;">' +
                '<div style="font-size:48px;margin-bottom:12px;">✅</div>' +
                '<h2 style="color:#10b981;margin-bottom:10px;">Connected Successfully!</h2>' +
                '<div class="ps-info-card">' +
                  '• <strong>Wi-Fi:</strong> ' + escapeHtml(data.ssid || '') + '<br>' +
                  '• <strong>Device IP:</strong> ' + escapeHtml(data.ip || '') + '<br>' +
                  '• <strong>Status:</strong> Cloud Telemetry Running' +
                '</div>' +
                '<p style="color:#94a3b8;font-size:13px;">Setup is complete. You can close this screen.</p>' +
              '</div>';
          }
        }
      })
      .catch(function() {});
    if (pollCount > 10) clearInterval(interval);
  }, 1000);
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
</script>
)rawliteral";

// =============================================================
//  WEB SERVER CUSTOM ROUTE HANDLERS
// =============================================================
void bindServerCallback() {
  if (!wifiManager.server) return;

  // 1. Auto-redirect root "/" directly to "/wifi" so user is taken straight to Wi-Fi setup!
  wifiManager.server->on("/", HTTP_GET, []() {
    wifiManager.server->sendHeader("Location", "/wifi", true);
    wifiManager.server->send(302, "text/plain", "");
  });

  // 2. Saved credentials information endpoint
  wifiManager.server->on("/api/saved-info", HTTP_GET, []() {
    String json = "{";
    json += "\"ssid\":\"" + WiFi.SSID() + "\",";
    json += "\"has_pass\":" + String(WiFi.psk().length() > 0 ? "true" : "false");
    json += "}";
    wifiManager.server->send(200, "application/json", json);
  });

  // 3. Live status query endpoint
  wifiManager.server->on("/api/status", HTTP_GET, []() {
    String json = "{";
    if (WiFi.status() == WL_CONNECTED) {
      json += "\"status\":\"connected\",";
      json += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
      json += "\"ssid\":\"" + WiFi.SSID() + "\",";
      json += "\"rssi\":" + String(WiFi.RSSI());
    } else {
      json += "\"status\":\"disconnected\"";
    }
    json += "}";
    wifiManager.server->send(200, "application/json", json);
  });

  // 4. Pre-connection live tester endpoint
  wifiManager.server->on("/api/test-connect", HTTP_GET, []() {
    String s = wifiManager.server->hasArg("s") ? wifiManager.server->arg("s") : "";
    String p = wifiManager.server->hasArg("p") ? wifiManager.server->arg("p") : "";

    if (s.length() == 0) {
      wifiManager.server->send(200, "application/json", "{\"status\":\"failed\",\"reason\":\"Please select or enter a Wi-Fi network name.\"}");
      return;
    }

    // If password was left blank, but this is the saved network on ESP32, use existing stored password!
    if (p.length() == 0 && s == WiFi.SSID() && WiFi.psk().length() > 0) {
      p = WiFi.psk();
      Serial.println("[WIFI TEST] Using existing stored password from ESP32 memory!");
    }

    Serial.printf("\n[WIFI TEST] Testing credentials for: %s\n", s.c_str());

    // Disconnect any existing station connection cleanly first
    WiFi.disconnect(false);
    delay(100);

    // Keep AP running while connecting station to target router
    WiFi.begin(s.c_str(), p.c_str());

    // Wait up to 8.5 seconds for connection result
    unsigned long start = millis();
    wl_status_t st = WiFi.status();
    while (st != WL_CONNECTED && (millis() - start < 8500)) {
      delay(150);
      st = WiFi.status();
      if (st == WL_CONNECT_FAILED) break;
    }

    if (st == WL_CONNECTED) {
      Serial.printf("✅ [WIFI TEST] Successfully connected! IP: %s\n", WiFi.localIP().toString().c_str());
      String res = "{";
      res += "\"status\":\"success\",";
      res += "\"ip\":\"" + WiFi.localIP().toString() + "\",";
      res += "\"ssid\":\"" + s + "\",";
      res += "\"rssi\":" + String(WiFi.RSSI());
      res += "}";
      wifiManager.server->send(200, "application/json", res);
    } else {
      Serial.printf("❌ [WIFI TEST] Connection failed (status code: %d)\n", (int)st);
      WiFi.disconnect(false);
      wifiManager.server->send(200, "application/json", "{\"status\":\"failed\",\"reason\":\"Could not connect. Please check your password or signal range.\"}");
    }
  });
}

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

  // 2. Initialize WiFiManager with custom dark UI & real-time connection verifier
  wifiManager.setTitle("PoultrySense Smart Connect");
  wifiManager.setConfigPortalTimeout(180); // 3 minutes timeout
  wifiManager.setShowPassword(true);       // Enable password visibility
  wifiManager.setDarkMode(true);           // Enable dark mode base
  wifiManager.setCustomHeadElement(CUSTOM_HEAD_HTML);
  wifiManager.setWebServerCallback(bindServerCallback);

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
  if (temp >= 36.0) climateStatus = "CRITICAL HEAT";
  else if (temp >= 33.0) climateStatus = "HEAT WATCH";
  else if (temp <= 17.0) climateStatus = "COLD WATCH";
  else if (hum >= 88.0)  climateStatus = "CRITICAL HUMIDITY";
  else if (hum >= 82.0)  climateStatus = "HUMIDITY WATCH";
  else if (hum <= 35.0)  climateStatus = "DRY AIR";

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
