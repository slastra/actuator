#include "servo.h"

#include <math.h>
#include <stdlib.h>

#include "driver/ledc.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#define TAG "servo"

// 50 Hz frame (20 ms). 14-bit duty gives 16384 counts per frame, about
// 1.2 us per count: plenty for a servo pulse.
#define FRAME_HZ   50
#define FRAME_MS   (1000 / FRAME_HZ)
#define FRAME_US   (1000000 / FRAME_HZ)
#define DUTY_BITS  LEDC_TIMER_14_BIT
#define DUTY_MAX   (1u << 14)

#define MODE    LEDC_LOW_SPEED_MODE
#define TIMER   LEDC_TIMER_0
#define CHANNEL LEDC_CHANNEL_0

#define TICK_MS 50  // protection task period

#define SWEEP_MS 1500  // one end-to-end stroke

// Angle mapping (servo_set_angle): the horn swings +/-HALF about
// perpendicular, and the rod moves as sin() of that.
#define DEG2RAD  ((float)M_PI / 180)
#define HALF_RAD (SERVO_ANGLE_RANGE / 2 * DEG2RAD)
#define MID_US   ((SERVO_MIN_US + SERVO_MAX_US) / 2.0f)
#define SPAN_US  ((SERVO_MAX_US - SERVO_MIN_US) / 2.0f)

// Longest a move can ever take: the full safe stroke.
#define FULL_TRAVEL_US \
    ((int64_t)(SERVO_SAFE_MAX_US - SERVO_SAFE_MIN_US) * SERVO_TRAVEL_US_PER_US)

// Everything below is guarded by `lock`.
static SemaphoreHandle_t lock;
static uint32_t current_us;
static bool driving;
static bool limits = true;
static bool locked;
static int64_t release_at;  // esp_timer time to drop the pulses
static uint32_t hold_ms = SERVO_HOLD_MS_DEFAULT;
static int32_t heat_ms;
static volatile bool sweeping;  // only touched by servo_sweep()

static uint32_t clamp_us(uint32_t us)
{
    const uint32_t lo = limits ? SERVO_SAFE_MIN_US : SERVO_HARD_MIN_US;
    const uint32_t hi = limits ? SERVO_SAFE_MAX_US : SERVO_HARD_MAX_US;
    return us < lo ? lo : us > hi ? hi : us;
}

static uint32_t pct_to_us(float pct)
{
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    return SERVO_MIN_US + (uint32_t)((SERVO_MAX_US - SERVO_MIN_US) * pct / 100.0f + 0.5f);
}

static void write_duty(uint32_t duty)
{
    ledc_set_duty(MODE, CHANNEL, duty);
    ledc_update_duty(MODE, CHANNEL);
}

static void stop_pulses(void)
{
    write_duty(0);
    driving = false;
}

// Runs the release timer and the heat budget.
static void protect_task(void *arg)
{
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(TICK_MS));
        xSemaphoreTake(lock, portMAX_DELAY);
        const bool protect = hold_ms != 0;
        if (driving) {
            heat_ms += TICK_MS;
            if (protect && esp_timer_get_time() >= release_at) {
                stop_pulses();
            }
            if (protect && heat_ms >= SERVO_HEAT_CAP_MS) {
                stop_pulses();
                locked = true;
                ESP_LOGW(TAG, "heat budget used up: locked out for %d s to cool",
                         (SERVO_HEAT_CAP_MS - SERVO_HEAT_RESUME_MS) * SERVO_HEAT_COOL_DIV / 1000);
            }
        } else if (heat_ms > 0) {
            heat_ms -= TICK_MS / SERVO_HEAT_COOL_DIV;
            if (heat_ms < 0) heat_ms = 0;
            if (locked && heat_ms <= SERVO_HEAT_RESUME_MS) {
                locked = false;
                ESP_LOGI(TAG, "cooled down, moves allowed again");
            }
        }
        xSemaphoreGive(lock);
    }
}

esp_err_t servo_init(void)
{
    const ledc_timer_config_t timer = {
        .speed_mode = MODE,
        .duty_resolution = DUTY_BITS,
        .timer_num = TIMER,
        .freq_hz = FRAME_HZ,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ESP_RETURN_ON_ERROR(ledc_timer_config(&timer), TAG, "timer config");

    // Start with duty 0 (line held low, no pulses) until a position is set.
    const ledc_channel_config_t channel = {
        .gpio_num = SERVO_GPIO,
        .speed_mode = MODE,
        .channel = CHANNEL,
        .timer_sel = TIMER,
        .duty = 0,
        .hpoint = 0,
    };
    ESP_RETURN_ON_ERROR(ledc_channel_config(&channel), TAG, "channel config");

    lock = xSemaphoreCreateMutex();
    ESP_RETURN_ON_FALSE(lock, ESP_ERR_NO_MEM, TAG, "mutex");
    ESP_RETURN_ON_FALSE(xTaskCreate(protect_task, "servo_protect", 3072, NULL, 5, NULL) == pdPASS,
                        ESP_ERR_NO_MEM, TAG, "protect task");
    return ESP_OK;
}

esp_err_t servo_set_us(uint32_t us)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    if (locked) {
        xSemaphoreGive(lock);
        return ESP_ERR_INVALID_STATE;
    }
    us = clamp_us(us);

    // When should the servo have arrived? Unknown start: assume a full stroke.
    // If the previous move may still be under way, add to its deadline (the
    // servo is somewhere between the two targets), capped at one full stroke.
    const int64_t now = esp_timer_get_time();
    const int64_t travel = current_us
        ? (int64_t)abs((int32_t)us - (int32_t)current_us) * SERVO_TRAVEL_US_PER_US
        : FULL_TRAVEL_US;
    const int64_t margin = (int64_t)hold_ms * 1000;
    int64_t base = now + margin;
    if (driving && release_at > base) base = release_at;
    release_at = base + travel;
    if (release_at > now + margin + FULL_TRAVEL_US) release_at = now + margin + FULL_TRAVEL_US;

    current_us = us;
    write_duty((uint32_t)((uint64_t)us * DUTY_MAX / FRAME_US));
    driving = true;
    xSemaphoreGive(lock);
    return ESP_OK;
}

esp_err_t servo_set_pct(float pct)
{
    return servo_set_us(pct_to_us(pct));
}

esp_err_t servo_set_angle(float deg)
{
    if (deg < 0) deg = 0;
    if (deg > SERVO_ANGLE_RANGE) deg = SERVO_ANGLE_RANGE;
    const float a = (deg - SERVO_ANGLE_RANGE / 2) * DEG2RAD;  // from perpendicular
    return servo_set_us((uint32_t)lroundf(MID_US + SPAN_US * sinf(a) / sinf(HALF_RAD)));
}

esp_err_t servo_glide_pct(float pct, uint32_t ms)
{
    const uint32_t target = pct_to_us(pct);
    servo_status_t st;
    servo_get_status(&st);
    const uint32_t start = st.us ? st.us : target;
    // One step per frame: updating faster than 50 Hz gains nothing.
    const uint32_t steps = ms / FRAME_MS;

    for (uint32_t i = 1; i <= steps; i++) {
        ESP_RETURN_ON_ERROR(
            servo_set_us(start + (int32_t)(target - start) * (int32_t)i / (int32_t)steps),
            TAG, "glide stopped");
        vTaskDelay(pdMS_TO_TICKS(FRAME_MS));
    }
    return servo_set_us(target);
}

esp_err_t servo_sweep(int times)
{
    // Callers on different tasks (console, BLE) may race to start one.
    static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
    taskENTER_CRITICAL(&mux);
    const bool busy = sweeping;
    sweeping = true;
    taskEXIT_CRITICAL(&mux);
    if (busy) return ESP_ERR_INVALID_STATE;

    esp_err_t err = ESP_OK;
    for (int i = 0; i < times && err == ESP_OK; i++) {
        err = servo_glide_pct(0, SWEEP_MS);
        if (err == ESP_OK) err = servo_glide_pct(100, SWEEP_MS);
    }
    if (err == ESP_OK) err = servo_glide_pct(50, SWEEP_MS / 2);
    sweeping = false;
    return err;
}

float servo_us_to_angle(uint32_t us)
{
    float x = ((float)us - MID_US) / SPAN_US * sinf(HALF_RAD);
    if (x < -1) x = -1;
    if (x > 1) x = 1;
    return SERVO_ANGLE_RANGE / 2 + asinf(x) / DEG2RAD;
}

void servo_off(void)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    stop_pulses();
    xSemaphoreGive(lock);
}

void servo_set_hold_ms(uint32_t ms)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    hold_ms = ms;
    release_at = esp_timer_get_time() + (int64_t)ms * 1000;
    xSemaphoreGive(lock);
}

void servo_set_limits(bool on)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    limits = on;
    xSemaphoreGive(lock);
}

void servo_get_status(servo_status_t *out)
{
    xSemaphoreTake(lock, portMAX_DELAY);
    *out = (servo_status_t){
        .us = current_us,
        .driving = driving,
        .limits = limits,
        .locked = locked,
        .hold_ms = hold_ms,
        .heat_ms = heat_ms,
        .cooldown_ms = locked
            ? (uint32_t)(heat_ms - SERVO_HEAT_RESUME_MS) * SERVO_HEAT_COOL_DIV
            : 0,
        .sweeping = sweeping,
    };
    xSemaphoreGive(lock);
}
