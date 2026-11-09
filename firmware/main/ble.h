#pragma once

#include <stdbool.h>
#include <stddef.h>

/*
 * BLE control (NimBLE peripheral), the primary way to drive the actuator.
 *
 * Service  c175482b-6776-4dfd-9299-a5b850b37900
 *   ...7901 Angle    read/write(+no-rsp)  u16 LE, tenths of a degree, 0..900
 *   ...7902 Command  write                u8: 0 = off, 1 = sweep
 *   ...7903 Status   read/notify          7 bytes LE:
 *           u16 angle     tenths of a degree (0xFFFF = never positioned)
 *           u16 us        last pulse width commanded
 *           u8  flags     bit0 driving, bit1 limits on, bit2 locked (cooling),
 *                         bit3 sweeping
 *           u8  heat      heat budget used, percent
 *           u8  cooldown  seconds until the lockout lifts (0 if not locked)
 *
 * OTA service c175482b-6776-4dfd-9299-a5b850b37910
 *   ...7911 OTA control  write/read/notify
 *           write: 0x01 + u32 size = begin, 0x02 = finish (verify, switch,
 *                  reboot), 0x03 = abort
 *           read:  u8 state, u8 error, u32 received, u32 size (ota.h)
 *   ...7912 OTA data     write(+no-rsp): u32 offset + image bytes, in order
 *   ...7913 OTA info     read: text, running slot, its state, build, elf
 *
 * Refusals come back as application ATT errors:
 *   0x80 out of range, 0x81 locked (cooling down), 0x82 busy (sweep or
 *   update running), 0x90 + ota_error_t for OTA failures.
 *
 * Every characteristic needs an authenticated (MITM) encrypted link: the
 * phone pairs once with the 6-digit CONFIG_ACTUATOR_BLE_PIN and is bonded
 * from then on. Bonds persist in NVS.
 */
void ble_start(void);

// True once the host has synced and advertising started, i.e. this image
// can take the next update. Used by the OTA rollback guard.
bool ble_wait_ready(unsigned timeout_ms);

typedef enum {
    BLE_LINK_DOWN = 0,         // host not synced yet
    BLE_LINK_ADVERTISING = 1,  // no connection
    BLE_LINK_CONNECTED = 2,    // connected, not yet encrypted
    BLE_LINK_SECURE = 3,       // connected and paired
} ble_link_t;

ble_link_t ble_link(void);

// One line of BLE state for the console.
void ble_status(char *out, size_t len);

// Delete every bond (phones must pair again).
void ble_forget_all(void);
