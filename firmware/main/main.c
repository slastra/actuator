#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_console.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "ble.h"
#include "led.h"
#include "nvs_flash.h"
#include "ota.h"
#include "servo.h"

#define TAG "actuator"

// How long an unconfirmed update gets to start advertising before rolling back.
#define OTA_CONFIRM_TIMEOUT_MS 30000

static bool parse_num(const char *s, float *out)
{
    char *end;
    *out = strtof(s, &end);
    return end != s && *end == '\0';
}

static uint32_t commanded_us(void)
{
    servo_status_t st;
    servo_get_status(&st);
    return st.us;
}

// Report a refused move. Returns non-zero so the console flags it.
static int refused(esp_err_t err)
{
    if (err == ESP_ERR_INVALID_STATE) {
        servo_status_t st;
        servo_get_status(&st);
        printf("locked: cooling down, %lu s left\n", (unsigned long)(st.cooldown_ms + 999) / 1000);
    } else {
        printf("failed: %s\n", esp_err_to_name(err));
    }
    return 1;
}

static int cmd_pos(int argc, char **argv)
{
    float pct;
    if (argc != 2 || !parse_num(argv[1], &pct) || pct < 0 || pct > 100) {
        printf("usage: pos <0-100>\n");
        return 1;
    }
    esp_err_t err = servo_set_pct(pct);
    if (err != ESP_OK) return refused(err);
    printf("pos %.1f%% -> %lu us\n", pct, (unsigned long)commanded_us());
    return 0;
}

static int cmd_angle(int argc, char **argv)
{
    float deg;
    if (argc != 2 || !parse_num(argv[1], &deg) || deg < 0 || deg > SERVO_ANGLE_RANGE) {
        printf("usage: angle <0-%g>\n", SERVO_ANGLE_RANGE);
        return 1;
    }
    esp_err_t err = servo_set_angle(deg);
    if (err != ESP_OK) return refused(err);
    printf("angle %.1f deg -> %lu us\n", deg, (unsigned long)commanded_us());
    return 0;
}

static int cmd_us(int argc, char **argv)
{
    float us;
    if (argc != 2 || !parse_num(argv[1], &us) ||
        us < SERVO_HARD_MIN_US || us > SERVO_HARD_MAX_US) {
        printf("usage: us <%d-%d>\n", SERVO_HARD_MIN_US, SERVO_HARD_MAX_US);
        return 1;
    }
    esp_err_t err = servo_set_us((uint32_t)us);
    if (err != ESP_OK) return refused(err);
    const uint32_t got = commanded_us();
    if (got != (uint32_t)us) {
        printf("%lu us (clamped to safe range; `limits off` to override)\n", (unsigned long)got);
    } else {
        printf("%lu us\n", (unsigned long)got);
    }
    return 0;
}

static int cmd_sweep(int argc, char **argv)
{
    float n = 1;
    if (argc > 2 || (argc == 2 && (!parse_num(argv[1], &n) || n < 1 || n > 100))) {
        printf("usage: sweep [times 1-100]\n");
        return 1;
    }
    esp_err_t err = servo_sweep((int)n);
    return err == ESP_OK ? 0 : refused(err);
}

static int cmd_off(int argc, char **argv)
{
    servo_off();
    printf("pulses off\n");
    return 0;
}

static int cmd_hold(int argc, char **argv)
{
    float ms;
    if (argc == 2 && parse_num(argv[1], &ms) && ms >= 0 && ms <= 600000) {
        servo_set_hold_ms((uint32_t)ms);
    } else if (argc != 1) {
        printf("usage: hold [ms after the move, 0 = hold forever]\n");
        return 1;
    }
    servo_status_t st;
    servo_get_status(&st);
    if (st.hold_ms) {
        printf("hold %lu ms after each move\n", (unsigned long)st.hold_ms);
    } else {
        printf("hold forever (release and heat budget off: no stall protection)\n");
    }
    return 0;
}

static int cmd_limits(int argc, char **argv)
{
    if (argc == 2 && (!strcmp(argv[1], "on") || !strcmp(argv[1], "off"))) {
        servo_set_limits(!strcmp(argv[1], "on"));
    } else if (argc != 1) {
        printf("usage: limits [on|off]\n");
        return 1;
    }
    servo_status_t st;
    servo_get_status(&st);
    if (st.limits) {
        printf("limits on: %d-%d us\n", SERVO_SAFE_MIN_US, SERVO_SAFE_MAX_US);
    } else {
        printf("limits OFF: %d-%d us, can drive into the end stops\n",
               SERVO_HARD_MIN_US, SERVO_HARD_MAX_US);
    }
    return 0;
}

static int cmd_status(int argc, char **argv)
{
    servo_status_t st;
    servo_get_status(&st);
    printf("us %lu, %s, limits %s, hold %lu ms, heat %lu/%d ms%s\n",
           (unsigned long)st.us, st.driving ? "driving" : "released",
           st.limits ? "on" : "off", (unsigned long)st.hold_ms,
           (unsigned long)st.heat_ms, SERVO_HEAT_CAP_MS,
           st.locked ? ", LOCKED (cooling)" : "");
    return 0;
}

static int cmd_ota(int argc, char **argv)
{
    char line[200];
    ota_status(line, sizeof line);
    printf("%s\n", line);
    return 0;
}

static int cmd_ble(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "forget")) {
        ble_forget_all();
        printf("all bonds deleted; phones must pair again\n");
        return 0;
    }
    if (argc != 1) {
        printf("usage: ble [forget]\n");
        return 1;
    }
    char line[160];
    ble_status(line, sizeof line);
    printf("%s\n", line);
    return 0;
}

static void start_console(void)
{
    esp_console_repl_t *repl = NULL;
    esp_console_repl_config_t repl_cfg = ESP_CONSOLE_REPL_CONFIG_DEFAULT();
    repl_cfg.prompt = "servo>";
    esp_console_dev_usb_serial_jtag_config_t hw_cfg =
        ESP_CONSOLE_DEV_USB_SERIAL_JTAG_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_console_new_repl_usb_serial_jtag(&hw_cfg, &repl_cfg, &repl));

    const esp_console_cmd_t cmds[] = {
        { .command = "angle",  .hint = "<0-90>",   .func = cmd_angle,
          .help = "Horn angle in degrees, corrected for the linkage" },
        { .command = "pos",    .hint = "<0-100>",  .func = cmd_pos,
          .help = "Jump to a stroke position in percent" },
        { .command = "us",     .hint = "<us>",     .func = cmd_us,
          .help = "Send a raw pulse width (clamped unless limits are off)" },
        { .command = "sweep",  .hint = "[times]",  .func = cmd_sweep,
          .help = "Glide end to end, then return to centre" },
        { .command = "off",    .func = cmd_off,
          .help = "Stop sending pulses" },
        { .command = "hold",   .hint = "[ms]",     .func = cmd_hold,
          .help = "Keep pulsing this long after a move should finish (0 = forever, no protection)" },
        { .command = "limits", .hint = "[on|off]", .func = cmd_limits,
          .help = "Clamp pulses to the rated stroke (off only for calibration)" },
        { .command = "status", .func = cmd_status,
          .help = "Show position, protection state and heat budget" },
        { .command = "ble",    .hint = "[forget]", .func = cmd_ble,
          .help = "BLE state; `ble forget` deletes every bond" },
        { .command = "ota",    .func = cmd_ota,
          .help = "Firmware slot, build and update state" },
    };
    for (size_t i = 0; i < sizeof cmds / sizeof cmds[0]; i++) {
        ESP_ERROR_CHECK(esp_console_cmd_register(&cmds[i]));
    }
    ESP_ERROR_CHECK(esp_console_register_help_command());
    ESP_ERROR_CHECK(esp_console_start_repl(repl));
}

void app_main(void)
{
    ESP_ERROR_CHECK(servo_init());
    ESP_LOGI(TAG, "servo on GPIO%d (D10), %d-%d us, safe %d-%d us",
             SERVO_GPIO, SERVO_MIN_US, SERVO_MAX_US, SERVO_SAFE_MIN_US, SERVO_SAFE_MAX_US);

    // NVS holds the BLE bonds (and PHY calibration).
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    // BLE first: it is both the control path and the update path, and an
    // unconfirmed update counts as good once it is advertising again.
    ble_start();
    led_start();
    ota_confirm_or_rollback(OTA_CONFIRM_TIMEOUT_MS);

    ESP_LOGI(TAG, "boot: to %.0f deg", SERVO_ANGLE_RANGE);
    servo_set_angle(SERVO_ANGLE_RANGE);

    start_console();
}
