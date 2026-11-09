#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

// XIAO ESP32C6 pin D10 is GPIO18.
#define SERVO_GPIO 18

// Pulse widths for the ends of the travel the linkage uses.
//
// The servo is rated 900-2100 us for its 9 mm stroke (~133 us/mm). The horn
// arm is 6 mm and must swing 90 deg (+/-45 about perpendicular), which needs
// 2 * 6 * sin(45) = 8.5 mm of stroke: 1500 +/- 565 us.
#define SERVO_MIN_US 935
#define SERVO_MAX_US 2065

// Horn swing that MIN..MAX covers, for servo_set_angle().
#define SERVO_ANGLE_RANGE 90.0f

// ---- Stall protection ----------------------------------------------------
// There is no feedback from a hobby servo, so a stall can't be detected.
// Instead: never command one, keep any that happens short, and cap the heat.
//
// 1. Clamp. Every pulse is clamped to the rated stroke, which stays clear of
//    both mechanical stops (the low one is ~655 us; pushing into it cooked
//    the motor). `limits off` widens this to the HARD range for calibration.
#define SERVO_SAFE_MIN_US 900
#define SERVO_SAFE_MAX_US 2100
#define SERVO_HARD_MIN_US 500
#define SERVO_HARD_MAX_US 2500

// 2. Release. Pulses stop once the move should be done: expected travel time
//    plus a margin (`hold <ms>`; 0 = hold forever, which also disables 3).
//    Rated 0.012 s/mm at 6 V is ~0.09 ms per us; 0.12 allows for 5 V + load.
#define SERVO_TRAVEL_US_PER_US 120  // microseconds of travel per us of pulse
#define SERVO_HOLD_MS_DEFAULT 300

// 3. Heat budget. Driving time is assumed to be stall time (worst case).
//    It accrues while pulsing and drains at 1/COOL_DIV the rate while idle.
//    Hitting CAP locks the servo out until the budget drains to RESUME.
#define SERVO_HEAT_CAP_MS    12000
#define SERVO_HEAT_RESUME_MS  6000
#define SERVO_HEAT_COOL_DIV      2

typedef struct {
    uint32_t us;          // last commanded pulse (0 = never set)
    bool driving;         // pulses currently being sent
    bool limits;          // clamp to the SAFE range
    bool locked;          // heat lockout active
    uint32_t hold_ms;
    uint32_t heat_ms;     // accrued heat budget
    uint32_t cooldown_ms; // time until the lockout lifts (0 if not locked)
    bool sweeping;        // servo_sweep() in progress
} servo_status_t;

esp_err_t servo_init(void);

// All moves return ESP_ERR_INVALID_STATE while the heat lockout is active.
esp_err_t servo_set_us(uint32_t us);
esp_err_t servo_set_pct(float pct);  // 0 = MIN, 100 = MAX
// Horn angle, 0..SERVO_ANGLE_RANGE. The rod moves as r*sin(angle), so this
// corrects for it: equal steps in angle give equal steps of horn rotation.
esp_err_t servo_set_angle(float deg);
// Move gradually to a percentage over `ms` milliseconds.
esp_err_t servo_glide_pct(float pct, uint32_t ms);

// Glide 0 -> 100 % and back `times` times, then return to centre. Blocks.
// ESP_ERR_INVALID_STATE if a sweep is already running or the heat lockout
// trips (the sweep stops where it is).
esp_err_t servo_sweep(int times);

// Horn angle (0..SERVO_ANGLE_RANGE) a pulse width corresponds to: the inverse
// of servo_set_angle(). Pulses outside MIN..MAX clamp to the ends.
float servo_us_to_angle(uint32_t us);

// Stop sending pulses now.
void servo_off(void);

void servo_set_hold_ms(uint32_t ms);
void servo_set_limits(bool on);
void servo_get_status(servo_status_t *out);
