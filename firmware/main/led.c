#include "led.h"

#include "ble.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "ota.h"
#include "servo.h"

#define TICK_MS 50
#define FRAME 40  // ticks: patterns repeat every 2 s

static bool lit(unsigned t)
{
    ota_progress_t ota;
    ota_get_progress(&ota);
    if (ota.state == OTA_RECEIVING) return (t / 2) % 2 == 0;       // 100 ms on/off

    servo_status_t s;
    servo_get_status(&s);
    if (s.locked) return t < 3 || (t >= 6 && t < 9);                // two 150 ms blinks

    switch (ble_link()) {
    case BLE_LINK_SECURE:      return true;
    case BLE_LINK_CONNECTED:   return (t / 5) % 2 == 0;           // 250 ms on/off
    case BLE_LINK_ADVERTISING: return t < 2;                      // 100 ms blip
    default:                   return false;
    }
}

static void led_task(void *arg)
{
    for (unsigned t = 0;; t = (t + 1) % FRAME) {
        gpio_set_level(LED_GPIO, lit(t) ? 0 : 1);  // active low
        vTaskDelay(pdMS_TO_TICKS(TICK_MS));
    }
}

void led_start(void)
{
    gpio_reset_pin(LED_GPIO);
    gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);
    gpio_set_level(LED_GPIO, 1);
    xTaskCreate(led_task, "led", 2048, NULL, 2, NULL);
}
