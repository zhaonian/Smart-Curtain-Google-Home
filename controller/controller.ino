#include <ESP8266WiFi.h>
#include <ArduinoJson.h>
#include <MQTTClient.h>

#define SLEEP 16    // enable/disable the step motor.
#define STEP 13     // number of steps for the step motor to run.
#define DIRECTION 0 // direction of the step motor to run.
#define STEPS_PER_ROTATION 200 // 1.8 degree per step.

char ssid[] = "Nest Wifi";
char pass[] = "luanbaby";
int keyIndex = 0;
int status = WL_IDLE_STATUS;
WiFiClient net;
MQTTClient client;
unsigned long lastMillis = 0;

/** MQTT info */
const char* thehostname = "zluan-curtains.cloud.shiftr.io";
const char* user = "zluan-curtains";
const char* user_password = "";
const char* id = "ESP8266-Curtains";
boolean OnOff = false;

void connect() {
  Serial.print("checking wifi…");
  while (WiFi.status() != WL_CONNECTED) {
    Serial.print(".");
    delay(1000);
  }
  Serial.print("\nconnecting…");
  while (!client.connect(id, user, user_password)) {
    Serial.print(".");
    delay(1000);
  }
  Serial.println("\nconnected!");
  client.subscribe("/1els-client");
}

void onMessageReceived(String &topic, String &payload) {
  Serial.println("incoming: " + topic + " - " + payload);
  DynamicJsonBuffer jsonBuffer;
  JsonObject& json = jsonBuffer.parseObject(payload);
  String deviceOn = json["on"];

  Serial.println(deviceOn);
  if (deviceOn == "true") {
    stepMotorOn(STEPS_PER_ROTATION, 1);
    OnOff = true;
  }
  if (deviceOn == "false") {
    stepMotorOn(STEPS_PER_ROTATION, 0);
    OnOff = false;
  }
}

/** Lifecycle - setup */
void setup() {
  Serial.begin(9600);
  pinMode(SLEEP, OUTPUT);
  pinMode(STEP, OUTPUT);
  pinMode(DIRECTION, OUTPUT);

  WiFi.begin(ssid, pass);
  client.begin(thehostname, net);
  client.onMessage(onMessageReceived);
  connect();
  delay(1000);
}

/** Lifecycle - loop */
void loop() {
  client.loop();
  delay(10);  // <- fixes some issues with WiFi stability
  if (!client.connected()) {
    connect();
  }
}

/** Starts the step motor with the number of steps to run. */
void stepMotorOn(int steps, int dir) {
  Serial.print(steps);
  Serial.println(F(" steps."));
  if (dir == 0) {
    digitalWrite(DIRECTION, LOW);
  } else {
    digitalWrite(DIRECTION, HIGH);
  }
  
  for (int i = 0; i < steps; ++i) {
    digitalWrite(STEP, HIGH);
    delayMicroseconds(1100);
    digitalWrite(STEP, LOW);
    delayMicroseconds(1100);
  }
}
