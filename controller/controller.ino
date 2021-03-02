#define SLEEP 16    // enable/disable the step motor.
#define STEP 13     // number of steps for the step motor to run.
#define DIRECTION 0 // direction of the step motor to run.

#define STEPS_PER_ROTATION 300

/** Lifecycle - setup */
void setup() {
  Serial.begin(9600);
  pinMode(SLEEP, OUTPUT);
  pinMode(STEP, OUTPUT);
  pinMode(DIRECTION, OUTPUT);
}

/** Lifecycle - loop */
void loop() {
  delayMicroseconds(500);
  stepMotorOn(STEPS_PER_ROTATION);
}

/** Starts the step motor with number of steps to run. */
void stepMotorOn(int steps) {
  Serial.print(steps);
  Serial.println(F(" steps."));

  for (int i = 0; i < steps; ++i) {
    digitalWrite(STEP, HIGH);
    delayMicroseconds(1100);
    digitalWrite(STEP, LOW);
    delayMicroseconds(1100);
  }
}
