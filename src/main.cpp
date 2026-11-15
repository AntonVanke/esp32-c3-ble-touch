#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEHIDDevice.h>
#include <BLE2902.h>
#include <BLESecurity.h>
#include <esp_gap_ble_api.h>
#include <atomic>
#include "touch_report.h"

static constexpr const char *VERSION = "0.1.0";
static BLEServer *server;
static BLECharacteristic *inputReport;
static BLE2902 *subscription;
static std::atomic<bool> connected{false};
static std::atomic<bool> authenticated{false};
static std::atomic<bool> connectionChanged{false};
static TouchPoint points[2];
static char deviceName[24];
static uint32_t lastTouchCommand = 0;
static uint32_t sentReports = 0;
static uint32_t lastStatus = 0;

struct Gesture {
  bool active = false;
  uint32_t start = 0;
  uint32_t duration = 0;
  uint32_t lastFrame = 0;
  TouchPoint from[2];
  TouchPoint to[2];
} gesture;

static bool ready() {
  return connected && authenticated && subscription->getNotifications();
}

static void status() {
  Serial.printf("{\"event\":\"status\",\"version\":\"%s\",\"name\":\"%s\","
                "\"connected\":%s,\"encrypted\":%s,\"subscribed\":%s,"
                "\"ready\":%s,\"reports\":%lu,\"gesture\":%s}\n",
                VERSION, deviceName, connected ? "true" : "false",
                authenticated ? "true" : "false",
                subscription->getNotifications() ? "true" : "false",
                ready() ? "true" : "false", (unsigned long)sentReports,
                gesture.active ? "true" : "false");
}

static void error(const char *message) {
  Serial.printf("{\"event\":\"error\",\"message\":\"%s\"}\n", message);
}

static void sendTouch() {
  if (!ready()) return;
  uint8_t report[13];
  encodeTouchReport(points, report);
  inputReport->setValue(report, sizeof(report));
  inputReport->notify();
  ++sentReports;
}

static void releaseAll() {
  gesture.active = false;
  points[0].down = points[1].down = false;
  sendTouch();
}

class ConnectionCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    connected = true;
    authenticated = false;
    connectionChanged = true;
  }
  void onDisconnect(BLEServer *) override {
    connected = false;
    authenticated = false;
    connectionChanged = true;
  }
};

class SecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return 0; }
  void onPassKeyNotify(uint32_t) override {}
  bool onConfirmPIN(uint32_t) override { return true; }
  bool onSecurityRequest() override { return true; }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    authenticated = result.success;
  }
};

static bool coordinate(int value) { return value >= 0 && value <= 1000; }

static void startGesture(const TouchPoint from[2], const TouchPoint to[2], uint32_t duration) {
  releaseAll();
  for (int i = 0; i < 2; ++i) {
    gesture.from[i] = from[i];
    gesture.to[i] = to[i];
    points[i] = from[i];
  }
  gesture.duration = duration;
  gesture.start = gesture.lastFrame = millis();
  gesture.active = true;
  lastTouchCommand = millis();
  sendTouch();
}

static void clearBonds() {
  releaseAll();
  if (connected) server->disconnect(server->getConnId());
  int count = esp_ble_get_bond_device_num();
  if (count > 0) {
    auto *devices = (esp_ble_bond_dev_t *)calloc(count, sizeof(esp_ble_bond_dev_t));
    if (!devices) { error("no_memory"); return; }
    if (esp_ble_get_bond_device_list(&count, devices) == ESP_OK) {
      for (int i = 0; i < count; ++i) esp_ble_remove_bond_device(devices[i].bd_addr);
    }
    free(devices);
  }
  Serial.println("{\"event\":\"unpaired\"}");
}

static void command(char *line) {
  char *args[8];
  unsigned argc = 0;
  char *context = nullptr;
  for (char *p = strtok_r(line, " \t\r", &context); p; p = strtok_r(nullptr, " \t\r", &context)) {
    if (argc == 8) { error("too_many_arguments"); return; }
    args[argc++] = p;
  }
  if (!argc) return;
  if (!strcmp(args[0], "status") && argc == 1) { status(); return; }
  if (!strcmp(args[0], "release") && argc == 1) {
    releaseAll(); Serial.println("{\"event\":\"released\"}"); return;
  }
  if (!strcmp(args[0], "unpair") && argc == 1) { clearBonds(); return; }
  if (!ready()) { error("phone_not_ready"); return; }
  if (!strcmp(args[0], "pinch") && argc == 2) {
    if (strcmp(args[1], "in") && strcmp(args[1], "out")) { error("use_pinch_in_or_out"); return; }
    bool outward = !strcmp(args[1], "out");
    TouchPoint from[2], to[2];
    from[0].down = from[1].down = to[0].down = to[1].down = true;
    from[0].x = outward ? 400 : 150; from[1].x = outward ? 600 : 850;
    to[0].x = outward ? 150 : 400; to[1].x = outward ? 850 : 600;
    startGesture(from, to, 800);
  } else {
    int values[7] = {};
    for (unsigned i = 1; i < argc; ++i) {
      char *end;
      long value = strtol(args[i], &end, 10);
      if (*end || value < 0 || value > 10000) { error("invalid_number"); return; }
      values[i - 1] = value;
    }
    if (!strcmp(args[0], "tap") && argc == 3) {
      if (!coordinate(values[0]) || !coordinate(values[1])) { error("coordinate_0_to_1000"); return; }
      TouchPoint from[2];
      from[0].down = true; from[0].x = values[0]; from[0].y = values[1];
      startGesture(from, from, 100);
    } else if (!strcmp(args[0], "swipe") && argc == 6) {
      for (int i = 0; i < 4; ++i) if (!coordinate(values[i])) { error("coordinate_0_to_1000"); return; }
      if (values[4] < 100 || values[4] > 5000) { error("duration_100_to_5000_ms"); return; }
      TouchPoint from[2], to[2];
      from[0].down = to[0].down = true;
      from[0].x = values[0]; from[0].y = values[1];
      to[0].x = values[2]; to[0].y = values[3];
      startGesture(from, to, values[4]);
    } else if ((!strcmp(args[0], "down") || !strcmp(args[0], "move")) && argc == 4) {
      if (values[0] > 1 || !coordinate(values[1]) || !coordinate(values[2])) { error("invalid_contact"); return; }
      if (gesture.active) releaseAll();
      if (!strcmp(args[0], "move") && !points[values[0]].down) { error("contact_not_down"); return; }
      points[values[0]].down = true;
      points[values[0]].x = values[1]; points[values[0]].y = values[2];
      lastTouchCommand = millis();
      sendTouch();
    } else if (!strcmp(args[0], "up") && argc == 2 && values[0] < 2) {
      gesture.active = false;
      points[values[0]].down = false;
      lastTouchCommand = millis();
      sendTouch();
    } else { error("unknown_command_or_arguments"); return; }
  }
  Serial.println("{\"event\":\"ok\"}");
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(0);
  snprintf(deviceName, sizeof(deviceName), "C3-Touch-%04X", (unsigned)((ESP.getEfuseMac() >> 32) & 0xFFFF));
  BLEDevice::init(deviceName);
  BLEDevice::setEncryptionLevel(ESP_BLE_SEC_ENCRYPT);
  BLEDevice::setSecurityCallbacks(new SecurityCallbacks());
  server = BLEDevice::createServer();
  server->setCallbacks(new ConnectionCallbacks());
  auto *hid = new BLEHIDDevice(server);
  hid->manufacturer()->setValue("C3 Touch Demo");
  // Local prototype identity; these values are not a certified product VID/PID.
  uint8_t pnp[] = {0x02, 0x00, 0x00, 0x01, 0x00, 0x00, 0x01};
  hid->deviceInfo()->getCharacteristic(BLEUUID((uint16_t)0x2A50))->setValue(pnp, sizeof(pnp));
  hid->hidInfo(0, 0x02);
  hid->reportMap((uint8_t *)TOUCH_REPORT_MAP, sizeof(TOUCH_REPORT_MAP));
  inputReport = hid->inputReport(1);
  subscription = (BLE2902 *)inputReport->getDescriptorByUUID(BLEUUID((uint16_t)0x2902));
  uint8_t maximum = 2;
  hid->featureReport(2)->setValue(&maximum, 1);
  hid->startServices();
  hid->setBatteryLevel(100);
  auto *security = new BLESecurity();
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  security->setCapability(ESP_IO_CAP_NONE);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->setAppearance(0x03C0);
  advertising->addServiceUUID(hid->hidService()->getUUID());
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMaxPreferred(0x12);
  advertising->start();
  status();
}

void loop() {
  static char line[128];
  static size_t length = 0;
  static bool overflow = false;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      if (overflow) error("command_too_long");
      else { line[length] = 0; command(line); }
      length = 0; overflow = false;
    } else if (length < sizeof(line) - 1) line[length++] = c;
    else overflow = true;
  }
  uint32_t now = millis();
  if (connectionChanged.exchange(false)) {
    gesture.active = false;
    points[0].down = points[1].down = false;
    if (!connected) {
      subscription->setNotifications(false);
      BLEDevice::startAdvertising();
    }
    status();
  }
  if (gesture.active && now - gesture.lastFrame >= 20) {
    if (!ready()) { gesture.active = false; points[0].down = points[1].down = false; }
    else {
      uint32_t elapsed = now - gesture.start;
      if (elapsed > gesture.duration) elapsed = gesture.duration;
      for (int i = 0; i < 2; ++i) {
        points[i].x = gesture.from[i].x + (int32_t(gesture.to[i].x) - gesture.from[i].x) * int32_t(elapsed) / int32_t(gesture.duration);
        points[i].y = gesture.from[i].y + (int32_t(gesture.to[i].y) - gesture.from[i].y) * int32_t(elapsed) / int32_t(gesture.duration);
      }
      sendTouch();
      gesture.lastFrame = now;
      if (elapsed == gesture.duration) {
        releaseAll();
        Serial.println("{\"event\":\"gesture_done\"}");
      }
    }
  }
  if (!gesture.active && (points[0].down || points[1].down) && now - lastTouchCommand > 2000) {
    releaseAll();
    Serial.println("{\"event\":\"touch_timeout\"}");
  }
  if (now - lastStatus >= 1000) { status(); lastStatus = now; }
  delay(1);
}
