#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * Firmware updates over BLE, into the idle A/B slot.
 *
 * ble.c owns the GATT side and calls these from the NimBLE host task:
 *   ota_begin(size)              erase-as-you-go into the idle slot
 *   ota_write(offset, data, n)   sequential chunks; a gap is an error
 *   ota_finish()                 verify, switch the boot slot, reboot
 * The first bytes are checked before anything is written: an image whose
 * project name isn't ours is refused.
 */

typedef enum {
    OTA_IDLE = 0,
    OTA_RECEIVING = 1,
    OTA_DONE = 2,     // verified and switched; rebooting
    OTA_ERROR = 3,
} ota_state_t;

typedef enum {
    OTA_OK = 0,
    OTA_ERR_STATE = 1,       // wrong command for the current state
    OTA_ERR_SIZE = 2,        // bigger than the slot, or too small to be an image
    OTA_ERR_SEQUENCE = 3,    // a chunk arrived at the wrong offset (one was lost)
    OTA_ERR_PROJECT = 4,     // not an actuator image
    OTA_ERR_FLASH = 5,       // begin/write failed
    OTA_ERR_VERIFY = 6,      // image failed verification at the end
    OTA_ERR_INCOMPLETE = 7,  // finish before all bytes arrived
} ota_error_t;

typedef struct {
    ota_state_t state;
    ota_error_t error;
    uint32_t received;
    uint32_t size;
} ota_progress_t;

ota_error_t ota_begin(uint32_t size);
ota_error_t ota_write(uint32_t offset, const uint8_t *data, size_t len);
ota_error_t ota_finish(void);  // on success, reboots ~1 s later
void ota_abort(void);
void ota_get_progress(ota_progress_t *out);
bool ota_in_progress(void);

// Rollback guard. A freshly updated image boots PENDING_VERIFY; this
// confirms it once BLE is advertising (so it can take the next update), or
// reboots after `timeout_ms` so the bootloader returns to the previous image.
// No-op for a confirmed or serial-flashed image. Returns immediately.
void ota_confirm_or_rollback(unsigned timeout_ms);

// One line of OTA state for the console.
void ota_status(char *out, size_t len);
