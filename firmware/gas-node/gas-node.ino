/*
 * ============================================================================
 *  GasGuard – firmware cho gas node ESP32
 *  Phần cứng: ESP32-WROOM-32 + MQ-2 (gas/LPG) + DS18B20 (nhiệt độ) + relay quạt hút
 *             + LED xanh (kết nối) + LED đỏ & còi (báo động gas / quá nhiệt tại chỗ). Chân: xem config.h
 *
 *  Giao thức MQTT (giống hệt tests/simulator/simulator.py, đã kiểm thử với backend):
 *    iot/v1/devices/{DEVICE_ID}/telemetry   QoS1  {"deviceId","seq","ts","fw","gas":{value,unit},"temp":{value,unit}}
 *    iot/v1/devices/{DEVICE_ID}/heartbeat   QoS0  {"deviceId","ts","uptimeSec","rssi","fw","state":{fan,fanMode,buzzer,buzzerMode}}
 *    iot/v1/devices/{DEVICE_ID}/status      QoS1 retained + LWT  {"deviceId","state":"ONLINE"|"OFFLINE",...}
 *    iot/v1/devices/{DEVICE_ID}/event       QoS1  SENSOR_FAULT | SENSOR_OK | AUTO_FAN_ON | AUTO_FAN_OFF
 *    iot/v1/devices/{DEVICE_ID}/cmd         QoS1  <- backend {"commandId","action","params"}
 *        FAN_SET {on} / BUZZER_SET {on}: người dùng giữ BẬT/TẮT (MANUAL, kể cả khi có báo động, đến khi đổi lại)
 *        FAN_AUTO / BUZZER_AUTO       : trả về tự động theo báo động gas / quá nhiệt;  PING
 *    iot/v1/devices/{DEVICE_ID}/cmd/ack     QoS1  {"commandId","result","message","state","ts"}
 *    iot/v1/devices/{DEVICE_ID}/config      QoS1 retained <- backend {"configVersion",...}
 *    iot/v1/devices/{DEVICE_ID}/config/ack  QoS1  {"configVersion","result","message","applied","ts"}
 *
 *  Bảo mật đường truyền: MQTT_USE_TLS 1 (secrets.h) -> kết nối broker qua TLS (cổng 8883), kiểm tra chứng chỉ
 *  broker bằng CA của nhóm trong ca_cert.h (tạo bởi deployment/certs/gen-certs.sh). Cần giờ NTP trước khi bắt tay
 *  TLS (kiểm tra hạn chứng chỉ).
 *
 *  Thư viện (Arduino IDE -> Library Manager):
 *    "MQTT" by Joel Gaehwiler (2.5.x)  – hỗ trợ QoS1, retained, LWT
 *    "ArduinoJson" by Benoit Blanchon (7.x)
 *    "OneWire" by Paul Stoffregen, "DallasTemperature" by Miles Burton
 *  Board: "ESP32 Dev Module" (gói esp32 by Espressif 3.x)
 * ============================================================================
 */
#define ARDUINOJSON_USE_LONG_LONG 1
#include <WiFi.h>
#include <MQTT.h>
#include <ArduinoJson.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Preferences.h>
#include <sys/time.h>
#include <time.h>
#include <math.h>

#include "config.h"
#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Thieu secrets.h: copy secrets.h.example thanh secrets.h roi dien WiFi + thong tin MQTT lay tren web"
#endif

#ifndef MQTT_USE_TLS
#define MQTT_USE_TLS 0  // secrets.h cũ không có dòng này -> kết nối không mã hóa như trước
#endif
#if MQTT_USE_TLS
#include <WiFiClientSecure.h>
#if __has_include("ca_cert.h")
#include "ca_cert.h"
#else
#error "Thieu ca_cert.h: chay deployment/certs/gen-certs.sh (file duoc ghi vao firmware/gas-node/ca_cert.h)"
#endif
#endif

// ============================================================================ Biến toàn cục
#if MQTT_USE_TLS
WiFiClientSecure net;  // TLS: mã hóa + kiểm tra chứng chỉ broker bằng CA của nhóm
#else
WiFiClient net;
#endif
MQTTClient mqtt(MQTT_BUFFER_SIZE);
OneWire oneWire(PIN_DS18B20);
DallasTemperature ds(&oneWire);
Preferences prefs;

const String TOPIC_BASE = String("iot/v1/devices/") + DEVICE_ID;
const String TOPIC_STATUS = TOPIC_BASE + "/status";
const String TOPIC_CMD = TOPIC_BASE + "/cmd";
const String TOPIC_CONFIG = TOPIC_BASE + "/config";
String willPayload;  // phải sống suốt chương trình

struct DeviceConfig {
  int32_t version;
  uint32_t intervalSec;
  float gasThreshold;
  bool autoFan;
} cfg = {0, DEFAULT_TELEMETRY_INTERVAL_SEC, DEFAULT_GAS_THRESHOLD_PPM, DEFAULT_AUTO_FAN};

bool fanOn = false;
bool gasAlarm = false;        // gas đang vượt ngưỡng (LED đỏ + còi)
bool tempAlarm = false;       // nhiệt độ quá cao (LED đỏ + còi)
uint32_t alarmClearedAtMs = 0;  // lúc hết báo động (để AUTO tắt quạt sau FAN_AUTO_OFF_DELAY_SEC)

// Chế độ điều khiển: AUTO = theo báo động; MANUAL = người dùng giữ bật/tắt từ web (lưu flash, giữ cả khi khởi động lại)
bool fanManual = false, fanManualOn = false;
bool buzzerManual = false, buzzerManualOn = false;
bool buzzerSounding = false;  // còi đang kêu (báo động hoặc bật tay)
bool stateDirty = false;      // trạng thái quạt/còi vừa đổi -> gửi heartbeat ngay để web cập nhật
uint32_t seqNo = 0;
float mq2R0 = 0;              // kΩ
bool mq2Ready = false;        // đã làm nóng + có R0
uint32_t bootMs = 0;

// Lỗi cảm biến: đếm lỗi liên tiếp, trạng thái lỗi hiện tại và trạng thái đã báo cho backend
struct SensorHealth {
  const char *name;
  uint8_t fails;
  bool faulty;
  bool reportedFaulty;
  String lastError;
};
SensorHealth gasHealth = {"gas", 0, false, false, ""};
SensorHealth tempHealth = {"temp", 0, false, false, ""};

// Sự kiện AUTO_FAN_ON / AUTO_FAN_OFF chờ gửi (có thể xảy ra lúc mất mạng)
struct PendingEvent {
  const char *type;    // "AUTO_FAN_ON" | "AUTO_FAN_OFF"
  const char *reason;  // "GAS" | "TEMP" | "CLEARED"
  float value, threshold;
  uint64_t ts;
};
const uint8_t EVENT_QUEUE = 6;
PendingEvent eventQueue[EVENT_QUEUE];
uint8_t eventHead = 0, eventCount = 0;

// Bộ đệm bản đo khi mất kết nối (gửi bù khi kết nối lại; backend chấp nhận dữ liệu cũ tới 7 ngày)
struct Reading {
  uint32_t seq;
  uint64_t ts;
  bool hasGas, hasTemp;
  float gas, temp;
};
Reading offlineBuf[OFFLINE_BUFFER_SIZE];
uint16_t bufHead = 0, bufCount = 0;
uint32_t bufDropped = 0;

// Bản tin nhận được: thư viện MQTT không cho publish trong callback -> xếp hàng, xử lý ở loop()
struct Incoming {
  String topic, payload;
};
const uint8_t INBOX_SIZE = 6;
Incoming inbox[INBOX_SIZE];
uint8_t inboxHead = 0, inboxCount = 0;

// Nhớ các lệnh đã thực thi: QoS1 có thể giao trùng -> không chạy lại, chỉ gửi lại ACK cũ
const uint8_t CMD_MEMORY = 10;
String recentCmdIds[CMD_MEMORY];
String recentCmdAcks[CMD_MEMORY];
uint8_t recentCmdNext = 0;

uint32_t lastTelemetryMs = 0, lastHeartbeatMs = 0, lastMqttAttemptMs = 0, wifiLostSinceMs = 0;
uint32_t mqttRetryDelayMs = 2000;
bool wasMqttConnected = false;
bool wifiUp = false;
uint32_t lastNtpRequestMs = 0;
bool timeSyncLogged = false;
float lastTemp = NAN;
float lastGas = NAN;

// ============================================================================ Tiện ích
void logf(const char *fmt, ...) {
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  Serial.printf("[%8.1fs] %s\n", millis() / 1000.0, buf);
}

bool timeValid() { return time(nullptr) > 1704067200; }  // sau 01/01/2024 => đã đồng bộ NTP

uint64_t nowMs() {
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  return (uint64_t)tv.tv_sec * 1000ULL + tv.tv_usec / 1000;
}

String topic(const char *suffix) { return TOPIC_BASE + "/" + suffix; }

bool publishJson(const char *suffix, JsonDocument &doc, int qos, bool retained = false) {
  if (!mqtt.connected()) return false;
  String body;
  serializeJson(doc, body);
  bool ok = mqtt.publish(topic(suffix), body, retained, qos);
  if (!ok) logf("Gửi %s THẤT BẠI (lỗi %d)", suffix, (int)mqtt.lastError());
  return ok;
}

void setFan(bool on) {
  if (fanOn != on) stateDirty = true;
  fanOn = on;
#if RELAY_ACTIVE_LOW
  if (on) {
    digitalWrite(PIN_RELAY, LOW);
    pinMode(PIN_RELAY, OUTPUT);   // kéo IN xuống GND -> relay đóng
  } else {
    pinMode(PIN_RELAY, INPUT);    // thả nổi: điện trở kéo lên 5V trên module giữ relay nhả hẳn
  }
#else
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, on ? HIGH : LOW);
#endif
}

// ============================================================================ Lưu cấu hình vào flash
void loadPrefs() {
  prefs.begin("gasguard", false);
  cfg.version = prefs.getInt("cfgVer", 0);
  cfg.intervalSec = prefs.getUInt("interval", DEFAULT_TELEMETRY_INTERVAL_SEC);
  cfg.gasThreshold = prefs.getFloat("thr", DEFAULT_GAS_THRESHOLD_PPM);
  cfg.autoFan = prefs.getBool("autoFan", DEFAULT_AUTO_FAN);
  fanManual = prefs.getBool("fanMan", false);
  fanManualOn = prefs.getBool("fanOn", false);
  buzzerManual = prefs.getBool("buzMan", false);
  buzzerManualOn = prefs.getBool("buzOn", false);
  if (MQ2_R0_KOHM > 0) {
    mq2R0 = MQ2_R0_KOHM;
  } else if (!MQ2_FORCE_CALIBRATE) {
    mq2R0 = prefs.getFloat("r0", 0);
  }
  logf("Cấu hình đã lưu: v%d, chu kỳ %us, ngưỡng %.0f ppm, tự bật quạt=%d, R0=%.2f kΩ", cfg.version,
       cfg.intervalSec, cfg.gasThreshold, cfg.autoFan, mq2R0);
  logf("Chế độ: quạt %s, còi %s", fanManual ? (fanManualOn ? "TAY-BẬT" : "TAY-TẮT") : "TỰ ĐỘNG",
       buzzerManual ? (buzzerManualOn ? "TAY-BẬT" : "TAY-TẮT") : "TỰ ĐỘNG");
}

void saveModes() {
  prefs.putBool("fanMan", fanManual);
  prefs.putBool("fanOn", fanManualOn);
  prefs.putBool("buzMan", buzzerManual);
  prefs.putBool("buzOn", buzzerManualOn);
}

void savePrefs() {
  prefs.putInt("cfgVer", cfg.version);
  prefs.putUInt("interval", cfg.intervalSec);
  prefs.putFloat("thr", cfg.gasThreshold);
  prefs.putBool("autoFan", cfg.autoFan);
}

// ============================================================================ Cảm biến
// Đọc điện trở cảm biến Rs (kΩ). Trả về false nếu tín hiệu bất thường (đứt dây / chạm GND).
bool readMq2Rs(float &rsKohm, float &vPinOut) {
  uint32_t sum = 0;
  const int N = 16;
  for (int i = 0; i < N; i++) {
    sum += analogReadMilliVolts(PIN_MQ2_AO);
    delayMicroseconds(200);
  }
  float vPin = sum / (float)N / 1000.0f;
  vPinOut = vPin;
  if (vPin < 0.02f) return false;  // ~0V liên tục: AO không nối hoặc cảm biến mất nguồn
  float vOut = vPin * MQ2_DIVIDER_RATIO;
  if (vOut > MQ2_VC - 0.01f) vOut = MQ2_VC - 0.01f;
  rsKohm = MQ2_RL_KOHM * (MQ2_VC - vOut) / vOut;
  return true;
}

void calibrateMq2() {
  logf("Hiệu chuẩn MQ-2 (để cảm biến trong KHÔNG KHÍ SẠCH)...");
  float total = 0;
  int ok = 0;
  for (int i = 0; i < 50; i++) {
    float rs, v;
    if (readMq2Rs(rs, v)) {
      total += rs;
      ok++;
    }
    delay(100);
  }
  if (ok < 25) {
    logf("Hiệu chuẩn THẤT BẠI: tín hiệu MQ-2 không hợp lệ (kiểm tra dây AO / nguồn 5V)");
    return;
  }
  mq2R0 = (total / ok) / MQ2_CLEAN_AIR_RATIO;
  prefs.putFloat("r0", mq2R0);
  logf("R0 = %.2f kΩ (đã lưu vào flash)", mq2R0);
}

void markSensor(SensorHealth &h, bool ok, const String &err) {
  if (ok) {
    h.fails = 0;
    if (h.faulty) logf("Cảm biến %s hoạt động lại", h.name);
    h.faulty = false;
    return;
  }
  h.lastError = err;
  if (h.fails < 255) h.fails++;
  if (!h.faulty && h.fails >= SENSOR_FAIL_THRESHOLD) {
    h.faulty = true;
    logf("LỖI cảm biến %s: %s", h.name, err.c_str());
  }
}

// gas: ppm (NAN nếu chưa sẵn sàng / lỗi)
float readGasPpm() {
  if (!mq2Ready) {
    if (millis() - bootMs < (uint32_t)MQ2_WARMUP_SEC * 1000) return NAN;
    if (mq2R0 <= 0) calibrateMq2();
    if (mq2R0 <= 0) {
      markSensor(gasHealth, false, "Chưa hiệu chuẩn được MQ-2");
      return NAN;
    }
    mq2Ready = true;
    logf("MQ-2 sẵn sàng (R0=%.2f kΩ)", mq2R0);
  }
  float rs, vPin;
  if (!readMq2Rs(rs, vPin)) {
    markSensor(gasHealth, false, "MQ-2 không có tín hiệu (AO ~0V) - kiểm tra dây/nguồn");
    return NAN;
  }
  markSensor(gasHealth, true, "");
  float ppm = MQ2_CURVE_A * powf(rs / mq2R0, MQ2_CURVE_B);
  if (!isfinite(ppm) || ppm < 0) ppm = 0;
  if (ppm > MQ2_MAX_PPM) ppm = MQ2_MAX_PPM;
  return ppm;
}

// Đọc kết quả lần chuyển đổi trước rồi yêu cầu lần mới (không chặn 750ms)
float readTempC() {
  float t = ds.getTempCByIndex(0);
  ds.requestTemperatures();
  if (t == DEVICE_DISCONNECTED_C) {
    markSensor(tempHealth, false, "DS18B20 không phản hồi (đọc được -127)");
    return NAN;
  }
  if (t == 85.0f && isnan(lastTemp)) {  // 85°C = giá trị mặc định lúc mới cấp nguồn, chưa đo xong
    return NAN;
  }
  if (t < -55 || t > 125) {
    markSensor(tempHealth, false, "DS18B20 trả giá trị ngoài dải -55..125");
    return NAN;
  }
  markSensor(tempHealth, true, "");
  lastTemp = t;
  return t;
}

// ============================================================================ Gửi bản tin
bool sendReading(const Reading &r) {
  JsonDocument doc;
  doc["deviceId"] = DEVICE_ID;
  doc["seq"] = r.seq;
  doc["ts"] = r.ts;
  doc["fw"] = FW_VERSION;
  if (r.hasGas) {
    doc["gas"]["value"] = serialized(String(r.gas, 1));
    doc["gas"]["unit"] = "ppm";
  }
  if (r.hasTemp) {
    doc["temp"]["value"] = serialized(String(r.temp, 2));
    doc["temp"]["unit"] = "C";
  }
  return publishJson("telemetry", doc, 1);
}

void bufferReading(const Reading &r) {
  uint16_t idx = (bufHead + bufCount) % OFFLINE_BUFFER_SIZE;
  if (bufCount == OFFLINE_BUFFER_SIZE) {  // đầy -> bỏ bản cũ nhất
    bufHead = (bufHead + 1) % OFFLINE_BUFFER_SIZE;
    bufCount--;
    bufDropped++;
  }
  offlineBuf[idx] = r;
  bufCount++;
}

void flushBuffer(uint8_t maxItems) {
  uint8_t sent = 0;
  while (bufCount > 0 && sent < maxItems && mqtt.connected()) {
    if (!sendReading(offlineBuf[bufHead])) return;
    bufHead = (bufHead + 1) % OFFLINE_BUFFER_SIZE;
    bufCount--;
    sent++;
  }
  if (sent > 0) logf("Gửi bù %u bản đo lưu lúc mất mạng (còn %u)", sent, bufCount);
}

void fillState(JsonObject st) {
  st["fan"] = fanOn;
  st["fanMode"] = fanManual ? "MANUAL" : "AUTO";
  st["buzzer"] = buzzerSounding;
  st["buzzerMode"] = buzzerManual ? "MANUAL" : "AUTO";
}

void sendHeartbeat() {
  JsonDocument doc;
  doc["deviceId"] = DEVICE_ID;
  doc["ts"] = nowMs();
  doc["uptimeSec"] = (millis() - bootMs) / 1000;
  doc["rssi"] = WiFi.RSSI();
  doc["fw"] = FW_VERSION;
  fillState(doc["state"].to<JsonObject>());
  publishJson("heartbeat", doc, 0);
}

void sendStatusOnline() {
  JsonDocument doc;
  doc["deviceId"] = DEVICE_ID;
  doc["state"] = "ONLINE";
  if (timeValid()) doc["ts"] = nowMs();
  doc["fw"] = FW_VERSION;
  doc["hw"] = HW_VERSION;
  doc["ip"] = WiFi.localIP().toString();
  publishJson("status", doc, 1, true);
}

// Báo lỗi/phục hồi cảm biến và AUTO_FAN_ON. Gọi lặp lại được: chỉ gửi khi trạng thái chưa được báo.
void syncEvents() {
  if (!mqtt.connected() || !timeValid()) return;
  SensorHealth *list[] = {&gasHealth, &tempHealth};
  for (SensorHealth *h : list) {
    if (h->faulty == h->reportedFaulty) continue;
    JsonDocument doc;
    doc["deviceId"] = DEVICE_ID;
    doc["ts"] = nowMs();
    doc["type"] = h->faulty ? "SENSOR_FAULT" : "SENSOR_OK";
    doc["sensor"] = h->name;
    doc["message"] = h->faulty ? h->lastError : String("Cảm biến hoạt động lại");
    if (publishJson("event", doc, 1)) h->reportedFaulty = h->faulty;
  }
  while (eventCount > 0) {
    PendingEvent &ev = eventQueue[eventHead];
    JsonDocument doc;
    doc["deviceId"] = DEVICE_ID;
    doc["ts"] = ev.ts ? ev.ts : nowMs();
    doc["type"] = ev.type;
    doc["reason"] = ev.reason;
    if (strcmp(ev.reason, "TEMP") == 0) doc["temp"] = serialized(String(ev.value, 2));
    else if (strcmp(ev.reason, "GAS") == 0) doc["gas"] = serialized(String(ev.value, 1));
    if (strcmp(ev.reason, "CLEARED") != 0) doc["threshold"] = serialized(String(ev.threshold, 1));
    if (!publishJson("event", doc, 1)) return;
    eventHead = (eventHead + 1) % EVENT_QUEUE;
    eventCount--;
  }
}

void queueEvent(const char *type, const char *reason, float value, float threshold) {
  if (eventCount == EVENT_QUEUE) {  // đầy -> bỏ sự kiện cũ nhất
    eventHead = (eventHead + 1) % EVENT_QUEUE;
    eventCount--;
  }
  eventQueue[(eventHead + eventCount) % EVENT_QUEUE] = {type, reason, value, threshold, timeValid() ? nowMs() : 0};
  eventCount++;
}

// Quyết định quạt + còi từ báo động và chế độ người dùng chọn. Gọi sau mỗi lần đo và sau mỗi lệnh.
void applyActuators(float gas, float temp) {
  if (isnan(gas)) gas = isnan(lastGas) ? 0 : lastGas;  // gọi từ lệnh: dùng số đo gần nhất cho sự kiện
  if (isnan(temp)) temp = isnan(lastTemp) ? 0 : lastTemp;
  bool alarm = gasAlarm || tempAlarm;
  if (fanManual) {
    setFan(fanManualOn);  // người dùng giữ bật/tắt, kể cả khi có báo động
  } else {
    bool keep = alarm || (alarmClearedAtMs != 0 && millis() - alarmClearedAtMs < FAN_AUTO_OFF_DELAY_SEC * 1000UL);
    bool want = cfg.autoFan && keep;
    if (want && !fanOn) {
      setFan(true);
      const char *reason = gasAlarm ? "GAS" : "TEMP";
      queueEvent("AUTO_FAN_ON", reason, gasAlarm ? gas : temp, gasAlarm ? cfg.gasThreshold : TEMP_ALARM_ON_C);
      if (gasAlarm) logf("Gas %.1f ppm > ngưỡng %.0f -> TỰ BẬT QUẠT", gas, cfg.gasThreshold);
      else logf("Nhiệt độ %.1f C > %.0f C -> TỰ BẬT QUẠT", temp, TEMP_ALARM_ON_C);
    } else if (!want && fanOn) {
      setFan(false);
      queueEvent("AUTO_FAN_OFF", "CLEARED", 0, 0);
      logf("Hết báo động %d giây -> TỰ TẮT QUẠT", FAN_AUTO_OFF_DELAY_SEC);
    }
  }
  bool sound = buzzerManual ? buzzerManualOn : alarm;
  if (sound != buzzerSounding) {
    buzzerSounding = sound;
    stateDirty = true;
  }
}

// ============================================================================ Đo + luật tại thiết bị
void measureAndSend() {
  float gas = readGasPpm();
  float temp = readTempC();
  bool hasGas = !isnan(gas), hasTemp = !isnan(temp);
  if (hasGas) lastGas = gas;

  // Báo động tại chỗ, có trễ để không bật/tắt liên tục quanh ngưỡng
  bool wasAlarm = gasAlarm || tempAlarm;
  if (hasGas && gas > cfg.gasThreshold) gasAlarm = true;
  else if (!hasGas || gas < cfg.gasThreshold * 0.9f) gasAlarm = false;
  if (hasTemp && temp > TEMP_ALARM_ON_C) tempAlarm = true;
  else if (!hasTemp || temp < TEMP_ALARM_OFF_C) tempAlarm = false;
  bool isAlarm = gasAlarm || tempAlarm;
  if (wasAlarm && !isAlarm) alarmClearedAtMs = millis() ? millis() : 1;
  if (isAlarm) alarmClearedAtMs = 0;

  // Luật tại biên: chạy cả khi mất mạng / backend chết
  applyActuators(gas, temp);

  logf("gas=%s ppm  temp=%s C  quạt=%s%s  còi=%s%s", hasGas ? String(gas, 1).c_str() : "--",
       hasTemp ? String(temp, 2).c_str() : "--", fanOn ? "BẬT" : "TẮT", fanManual ? "(tay)" : "",
       buzzerSounding ? "KÊU" : "im", buzzerManual ? "(tay)" : "");

  if (!hasGas && !hasTemp) return;  // không có số đo hợp lệ nào
  if (!timeValid()) {
    logf("Chưa đồng bộ giờ NTP -> bỏ qua bản đo (backend từ chối timestamp sai)");
    return;
  }
  Reading r = {++seqNo, nowMs(), hasGas, hasTemp, hasGas ? gas : 0, hasTemp ? temp : 0};
  if (mqtt.connected() && bufCount == 0 && sendReading(r)) return;
  bufferReading(r);  // mất kết nối, hoặc còn bản cũ chưa gửi -> giữ đúng thứ tự
}

// ============================================================================ Lệnh điều khiển
void handleCommand(const String &payload) {
  JsonDocument cmd;
  if (deserializeJson(cmd, payload)) {
    logf("Bỏ qua lệnh không phải JSON");
    return;
  }
  const char *cid = cmd["commandId"] | "";
  if (strlen(cid) == 0) {
    logf("Bỏ qua lệnh thiếu commandId");
    return;
  }
  String id(cid);
  for (uint8_t i = 0; i < CMD_MEMORY; i++) {
    if (recentCmdIds[i] == id) {
      logf("Lệnh %s bị giao trùng -> không thực thi lại, gửi lại ACK cũ", id.substring(0, 8).c_str());
      mqtt.publish(topic("cmd/ack"), recentCmdAcks[i], false, 1);
      return;
    }
  }

  String action = cmd["action"] | "";
  const char *result = "FAILED";
  String message;
  JsonVariant on = cmd["params"]["on"];
  if (action == "FAN_SET" || action == "BUZZER_SET") {
    if (on.is<bool>()) {
      bool v = on.as<bool>();
      if (action == "FAN_SET") {
        fanManual = true;
        fanManualOn = v;
        message = v ? "Quạt BẬT (giữ bật đến khi đổi lại)" : "Quạt TẮT (giữ tắt, kể cả khi có báo động)";
      } else {
        buzzerManual = true;
        buzzerManualOn = v;
        message = v ? "Còi BẬT (kêu đến khi tắt)" : "Còi TẮT (im lặng, kể cả khi có báo động)";
      }
      saveModes();
      applyActuators(NAN, NAN);
      result = "SUCCESS";
    } else {
      message = "params.on phải là true hoặc false";
    }
  } else if (action == "FAN_AUTO" || action == "BUZZER_AUTO") {
    if (action == "FAN_AUTO") fanManual = false;
    else buzzerManual = false;
    saveModes();
    applyActuators(NAN, NAN);
    result = "SUCCESS";
    message = action == "FAN_AUTO" ? (fanOn ? "Quạt về TỰ ĐỘNG (đang bật do báo động)" : "Quạt về TỰ ĐỘNG")
                                   : (buzzerSounding ? "Còi về TỰ ĐỘNG (đang kêu do báo động)" : "Còi về TỰ ĐỘNG");
    stateDirty = true;
  } else if (action == "PING") {
    result = "SUCCESS";
    message = "pong";
  } else {
    message = "Không hỗ trợ lệnh '" + action + "'";
  }
  logf("Lệnh %s (%s) -> %s: %s", action.c_str(), id.substring(0, 8).c_str(), result, message.c_str());

  JsonDocument ack;
  ack["commandId"] = id;
  ack["result"] = result;
  ack["message"] = message;
  fillState(ack["state"].to<JsonObject>());
  ack["ts"] = nowMs();
  String body;
  serializeJson(ack, body);
  recentCmdIds[recentCmdNext] = id;
  recentCmdAcks[recentCmdNext] = body;
  recentCmdNext = (recentCmdNext + 1) % CMD_MEMORY;
  mqtt.publish(topic("cmd/ack"), body, false, 1);
}

// ============================================================================ Cấu hình từ xa
bool isNumber(JsonVariant v) { return v.is<long>() || v.is<double>(); }

void handleConfig(const String &payload) {
  if (payload.length() == 0) return;  // backend xóa retained config (thiết bị bị ngừng sử dụng)
  JsonDocument doc;
  if (deserializeJson(doc, payload)) {
    logf("Bỏ qua cấu hình không phải JSON");
    return;
  }
  JsonVariant ver = doc["configVersion"];
  String errors;
  if (!ver.is<long>() || ver.as<long>() < 1) errors += "configVersion phải là số nguyên >= 1; ";
  JsonVariant iv = doc["telemetryIntervalSec"];
  if (!iv.isNull() && (!iv.is<long>() || iv.as<long>() < 1 || iv.as<long>() > 3600))
    errors += "telemetryIntervalSec phải từ 1 đến 3600; ";
  JsonVariant thr = doc["gasThreshold"];
  if (!thr.isNull() && (!isNumber(thr) || thr.as<float>() < 50 || thr.as<float>() > 10000))
    errors += "gasThreshold phải từ 50 đến 10000 ppm; ";
  JsonVariant af = doc["autoFan"];
  if (!af.isNull() && !af.is<bool>()) errors += "autoFan phải là true/false; ";

  JsonDocument ack;
  ack["configVersion"] = ver;
  if (errors.length() > 0) {
    errors.remove(errors.length() - 2);
    ack["result"] = "REJECTED";
    ack["message"] = errors;
    logf("TỪ CHỐI cấu hình: %s", errors.c_str());
  } else {
    // Retained config được giao lại mỗi lần kết nối -> áp dụng lại là vô hại
    if (!iv.isNull()) cfg.intervalSec = iv.as<long>();
    if (!thr.isNull()) cfg.gasThreshold = thr.as<float>();
    if (!af.isNull()) cfg.autoFan = af.as<bool>();
    cfg.version = ver.as<long>();
    savePrefs();
    ack["result"] = "APPLIED";
    ack["message"] = "OK";
    logf("Áp dụng cấu hình v%d: chu kỳ %us, ngưỡng %.0f ppm, tự bật quạt=%d", cfg.version, cfg.intervalSec,
         cfg.gasThreshold, cfg.autoFan);
  }
  JsonObject applied = ack["applied"].to<JsonObject>();
  applied["configVersion"] = cfg.version;
  applied["telemetryIntervalSec"] = cfg.intervalSec;
  applied["gasThreshold"] = cfg.gasThreshold;
  applied["autoFan"] = cfg.autoFan;
  ack["ts"] = nowMs();
  publishJson("config/ack", ack, 1);
}

void onMqttMessage(String &t, String &payload) {
  if (inboxCount == INBOX_SIZE) {
    logf("Hàng đợi bản tin đầy -> bỏ %s", t.c_str());
    return;
  }
  uint8_t idx = (inboxHead + inboxCount) % INBOX_SIZE;
  inbox[idx].topic = t;
  inbox[idx].payload = payload;
  inboxCount++;
}

void processInbox() {
  while (inboxCount > 0) {
    Incoming &m = inbox[inboxHead];
    if (m.topic == TOPIC_CMD) handleCommand(m.payload);
    else if (m.topic == TOPIC_CONFIG) handleConfig(m.payload);
    m.topic = "";
    m.payload = "";
    inboxHead = (inboxHead + 1) % INBOX_SIZE;
    inboxCount--;
  }
}

// ============================================================================ Kết nối
void connectMqtt() {
  logf("Kết nối MQTT%s %s:%d với tài khoản %s ...", MQTT_USE_TLS ? " (TLS)" : "", MQTT_HOST, MQTT_PORT, DEVICE_ID);
  if (!mqtt.connect(DEVICE_ID, DEVICE_ID, MQTT_PASSWORD)) {
    int rc = (int)mqtt.returnCode();
    if (rc == 4 || rc == 5) {
      logf("Broker TỪ CHỐI (mã %d): sai mật khẩu, hoặc thiết bị bị tạm ngưng / thu hồi / đổi mật khẩu", rc);
    } else {
      logf("Không kết nối được broker (lỗi %d, mã %d): kiểm tra MQTT_HOST, tường lửa cổng %d",
           (int)mqtt.lastError(), rc, MQTT_PORT);
#if MQTT_USE_TLS
      char tlsErr[100];
      if (net.lastError(tlsErr, sizeof(tlsErr)) != 0) {
        logf("Lỗi TLS: %s -> kiểm tra ca_cert.h khớp CA của broker và MQTT_HOST có trong chứng chỉ (gen-certs.sh)",
             tlsErr);
      }
#endif
    }
    mqttRetryDelayMs = min<uint32_t>(mqttRetryDelayMs * 2, 30000);
    return;
  }
  mqttRetryDelayMs = 2000;
  logf("Đã kết nối broker");
  mqtt.subscribe(TOPIC_CMD, 1);
  mqtt.subscribe(TOPIC_CONFIG, 1);  // nhận ngay cấu hình retained mới nhất
  sendStatusOnline();
  if (timeValid()) sendHeartbeat();
  lastHeartbeatMs = millis();
}

// Giờ UTC (backend/giao diện tự đổi sang giờ Việt Nam)
void requestNtp() {
  lastNtpRequestMs = millis();
  configTime(0, 0, NTP_SERVER1, NTP_SERVER2, NTP_SERVER3);
}

// Báo hiệu tại chỗ: hoạt động cả khi mất mạng
void updateIndicators() {
  uint32_t ms = millis();
  bool green;
  if (WiFi.status() != WL_CONNECTED) green = (ms / 150) % 2;  // nháy nhanh: chưa có WiFi
  else if (!mqtt.connected()) green = (ms / 600) % 2;         // nháy chậm: có WiFi, chưa vào broker
  else green = true;                                          // sáng: online
  digitalWrite(PIN_LED_GREEN, green ? HIGH : LOW);

  bool red = false;
  if (gasAlarm || tempAlarm) red = true;                      // gas cao / quá nhiệt: đỏ sáng (kể cả khi tắt còi)
  else if (gasHealth.faulty || tempHealth.faulty) red = (ms / 1000) % 2;  // cảm biến lỗi: đỏ nháy chậm
  bool buzz = BUZZER_ENABLED && buzzerSounding && (ms / 400) % 2;        // còi kêu ngắt quãng
  digitalWrite(PIN_LED_RED, red ? HIGH : LOW);
  digitalWrite(PIN_BUZZER, buzz ? HIGH : LOW);
}

// ============================================================================ setup / loop
void setup() {
  Serial.begin(115200);
  delay(200);
  bootMs = millis();
  setFan(false);  // quạt tắt ngay khi khởi động
  pinMode(PIN_LED_GREEN, OUTPUT);
  pinMode(PIN_LED_RED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_BUZZER, LOW);
  // Tự kiểm tra: 2 LED sáng + còi bíp ngắn => mạch LED/còi nối đúng
  digitalWrite(PIN_LED_GREEN, HIGH);
  digitalWrite(PIN_LED_RED, HIGH);
  if (BUZZER_ENABLED) digitalWrite(PIN_BUZZER, HIGH);
  delay(150);
  digitalWrite(PIN_BUZZER, LOW);
  delay(350);
  digitalWrite(PIN_LED_GREEN, LOW);
  digitalWrite(PIN_LED_RED, LOW);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_MQ2_AO, ADC_11db);  // đọc được tới ~3.1V

  logf("GasGuard gas node %s | thiết bị %s", FW_VERSION, DEVICE_ID);
  loadPrefs();

  ds.begin();
  ds.setResolution(12);
  ds.setWaitForConversion(false);
  if (ds.getDeviceCount() == 0) logf("CẢNH BÁO: không tìm thấy DS18B20 trên chân %d", PIN_DS18B20);
  ds.requestTemperatures();

  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  logf("Đang kết nối WiFi \"%s\" ...", WIFI_SSID);
  // Đồng bộ giờ NTP gọi SAU khi có WiFi (trong loop): gọi trước thì DHCP của router có thể ghi đè máy chủ NTP

  willPayload = String("{\"deviceId\":\"") + DEVICE_ID + "\",\"state\":\"OFFLINE\",\"reason\":\"LWT\"}";
#if MQTT_USE_TLS
  net.setCACert(MQTT_CA_CERT);                     // chỉ tin broker có chứng chỉ do CA của nhóm ký
  net.setHandshakeTimeout(MQTT_TLS_HANDSHAKE_SEC);
#endif
  mqtt.begin(MQTT_HOST, MQTT_PORT, net);
  mqtt.setWill(TOPIC_STATUS.c_str(), willPayload.c_str(), true, 1);
  mqtt.setKeepAlive(MQTT_KEEPALIVE_SEC);
  mqtt.setCleanSession(true);
  mqtt.setTimeout(3000);
  mqtt.onMessage(onMqttMessage);
  applyActuators(NAN, NAN);  // áp dụng chế độ tay đã lưu (vd. người dùng đã giữ quạt BẬT)
  logf("MQ-2 đang làm nóng %d giây...", MQ2_WARMUP_SEC);
}

void loop() {
  uint32_t ms = millis();
  updateIndicators();

  if (WiFi.status() != WL_CONNECTED) {
    if (wifiUp) {
      wifiUp = false;
      logf("Mất WiFi, đang thử kết nối lại...");
    }
    if (wifiLostSinceMs == 0) wifiLostSinceMs = ms ? ms : 1;
    if (ms - wifiLostSinceMs > WIFI_RESTART_AFTER_SEC * 1000UL) {
      logf("Không có WiFi quá %d giây -> khởi động lại", WIFI_RESTART_AFTER_SEC);
      delay(100);
      ESP.restart();
    }
  } else {
    if (!wifiUp) {
      wifiUp = true;
      logf("WiFi OK, IP %s, RSSI %d dBm", WiFi.localIP().toString().c_str(), WiFi.RSSI());
      requestNtp();
    }
    wifiLostSinceMs = 0;
    if (!timeValid() && ms - lastNtpRequestMs >= 30000) {
      logf("Chưa có giờ NTP sau 30 giây -> thử lại (mạng phải ra được Internet, cổng UDP 123)");
      requestNtp();
    }
    if (timeValid() && !timeSyncLogged) {
      timeSyncLogged = true;
      time_t now = time(nullptr);
      struct tm t;
      gmtime_r(&now, &t);
      logf("Đã đồng bộ giờ NTP: %04d-%02d-%02d %02d:%02d:%02d UTC", t.tm_year + 1900, t.tm_mon + 1, t.tm_mday,
           t.tm_hour, t.tm_min, t.tm_sec);
    }
    // TLS cần giờ đúng để kiểm tra hạn chứng chỉ -> chờ đồng bộ NTP rồi mới kết nối broker
    bool mqttReady = !MQTT_USE_TLS || timeValid();
    if (mqttReady && !mqtt.connected() && (lastMqttAttemptMs == 0 || ms - lastMqttAttemptMs >= mqttRetryDelayMs)) {
      lastMqttAttemptMs = ms ? ms : 1;
      connectMqtt();
    }
  }

  mqtt.loop();  // nhận bản tin + giữ kết nối (keepalive)
  bool connectedNow = mqtt.connected();
  if (wasMqttConnected && !connectedNow) logf("Mất kết nối broker -> đo vẫn chạy, lưu đệm tối đa %d bản", OFFLINE_BUFFER_SIZE);
  wasMqttConnected = connectedNow;

  processInbox();

  if (ms - lastTelemetryMs >= cfg.intervalSec * 1000UL) {
    measureAndSend();
    lastTelemetryMs = millis();  // tính từ lúc đo xong (lần đầu có thể mất vài giây để hiệu chuẩn)
  }
  if (connectedNow) {
    flushBuffer(5);
    syncEvents();
    if ((stateDirty || ms - lastHeartbeatMs >= HEARTBEAT_SEC * 1000UL) && timeValid()) {
      stateDirty = false;
      lastHeartbeatMs = ms;
      sendHeartbeat();
    }
  }
  delay(10);
}
