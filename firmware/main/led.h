#pragma once

/*
 * The XIAO ESP32C6's yellow user LED (GPIO15, lit when low) as a status
 * light. One pattern at a time, highest priority first:
 *
 *   fast flicker       firmware update coming in
 *   double blink       heat lockout (cooling down)
 *   solid              connected and paired
 *   quick blink        connected, pairing in progress
 *   blip every 2 s     advertising, waiting for a phone
 *   off                BLE not up (or not powered)
 *
 * The red LED beside it is the battery charger's and can't be driven.
 */
#define LED_GPIO 15

void led_start(void);
