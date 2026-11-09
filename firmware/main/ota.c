#include "ota.h"

#include <stdio.h>
#include <string.h>

#include "esp_app_desc.h"
#include "esp_app_format.h"
#include "esp_image_format.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "ble.h"
#include "servo.h"

#define TAG "ota"

// The app description sits right after the image header and the first
// segment header. Hold the first bytes back until we can check it.
#define DESC_OFFSET (sizeof(esp_image_header_t) + sizeof(esp_image_segment_header_t))
#define DESC_END    (DESC_OFFSET + sizeof(esp_app_desc_t))

static ota_progress_t prog;
static esp_ota_handle_t handle;
static bool opened;  // esp_ota_begin succeeded and handle is live
static const esp_partition_t *target;
static uint8_t head[DESC_END];  // first bytes, until the project check passes

static ota_error_t fail(ota_error_t e, const char *why)
{
    ESP_LOGE(TAG, "update failed at %lu/%lu bytes: %s", (unsigned long)prog.received,
             (unsigned long)prog.size, why);
    if (opened) {
        esp_ota_abort(handle);
        opened = false;
    }
    prog.state = OTA_ERROR;
    prog.error = e;
    return e;
}

ota_error_t ota_begin(uint32_t size)
{
    if (prog.state == OTA_RECEIVING) ota_abort();
    target = esp_ota_get_next_update_partition(NULL);
    memset(&prog, 0, sizeof prog);
    prog.size = size;
    if (!target || size < DESC_END || size > target->size) {
        prog.state = OTA_ERROR;
        prog.error = OTA_ERR_SIZE;
        ESP_LOGE(TAG, "refused: %lu bytes doesn't fit", (unsigned long)size);
        return OTA_ERR_SIZE;
    }
    servo_off();  // nothing should be moving through an update and reboot
    prog.state = OTA_RECEIVING;
    ESP_LOGI(TAG, "receiving %lu bytes into %s", (unsigned long)size, target->label);
    return OTA_OK;
}

static ota_error_t check_and_open(void)
{
    const esp_app_desc_t *in = (const esp_app_desc_t *)(head + DESC_OFFSET);
    if (in->magic_word != ESP_APP_DESC_MAGIC_WORD ||
        strncmp(in->project_name, esp_app_get_description()->project_name, sizeof in->project_name)) {
        return fail(OTA_ERR_PROJECT, "not an actuator image");
    }
    ESP_LOGI(TAG, "image: %s %s, built %s %s", in->project_name, in->version, in->date, in->time);
    if (esp_ota_begin(target, OTA_WITH_SEQUENTIAL_WRITES, &handle) != ESP_OK) {
        return fail(OTA_ERR_FLASH, "esp_ota_begin");
    }
    opened = true;
    if (esp_ota_write(handle, head, DESC_END) != ESP_OK) {
        return fail(OTA_ERR_FLASH, "esp_ota_write");
    }
    return OTA_OK;
}

ota_error_t ota_write(uint32_t offset, const uint8_t *data, size_t len)
{
    if (prog.state != OTA_RECEIVING) return OTA_ERR_STATE;
    if (offset != prog.received) return fail(OTA_ERR_SEQUENCE, "chunk out of sequence");
    if (prog.received + len > prog.size) return fail(OTA_ERR_SIZE, "more data than announced");

    // Still filling the header buffer?
    if (prog.received < DESC_END) {
        const size_t take = MIN(len, DESC_END - prog.received);
        memcpy(head + prog.received, data, take);
        prog.received += take;
        data += take;
        len -= take;
        if (prog.received < DESC_END) return OTA_OK;
        ota_error_t e = check_and_open();
        if (e != OTA_OK) return e;
    }
    if (len && esp_ota_write(handle, data, len) != ESP_OK) {
        return fail(OTA_ERR_FLASH, "esp_ota_write");
    }
    prog.received += len;
    return OTA_OK;
}

static void reboot_task(void *arg)
{
    vTaskDelay(pdMS_TO_TICKS(1000));  // let the reply and a status notify go out
    esp_restart();
}

ota_error_t ota_finish(void)
{
    if (prog.state != OTA_RECEIVING) return OTA_ERR_STATE;
    if (prog.received != prog.size) return fail(OTA_ERR_INCOMPLETE, "finish before all bytes arrived");
    // esp_ota_end checks the header, checksum and embedded SHA-256, and
    // frees the handle whatever the outcome.
    opened = false;
    if (esp_ota_end(handle) != ESP_OK) {
        prog.state = OTA_ERROR;
        prog.error = OTA_ERR_VERIFY;
        ESP_LOGE(TAG, "image failed verification");
        return OTA_ERR_VERIFY;
    }
    if (esp_ota_set_boot_partition(target) != ESP_OK) {
        prog.state = OTA_ERROR;
        prog.error = OTA_ERR_FLASH;
        return OTA_ERR_FLASH;
    }
    prog.state = OTA_DONE;
    ESP_LOGW(TAG, "update written to %s; rebooting into it", target->label);
    xTaskCreate(reboot_task, "ota_reboot", 2048, NULL, 5, NULL);
    return OTA_OK;
}

void ota_abort(void)
{
    if (opened) {
        esp_ota_abort(handle);
        opened = false;
    }
    if (prog.state == OTA_RECEIVING) ESP_LOGW(TAG, "update aborted");
    memset(&prog, 0, sizeof prog);
}

void ota_get_progress(ota_progress_t *out)
{
    *out = prog;
}

bool ota_in_progress(void)
{
    return prog.state == OTA_RECEIVING || prog.state == OTA_DONE;
}

/* ---- slot state and rollback --------------------------------------------- */

static const char *state_name(esp_ota_img_states_t s)
{
    switch (s) {
    case ESP_OTA_IMG_NEW:            return "new";
    case ESP_OTA_IMG_PENDING_VERIFY: return "pending-verify";
    case ESP_OTA_IMG_VALID:          return "valid";
    case ESP_OTA_IMG_INVALID:        return "invalid";
    case ESP_OTA_IMG_ABORTED:        return "aborted";
    default:                         return "undefined";  // flashed over serial
    }
}

static esp_ota_img_states_t running_state(void)
{
    esp_ota_img_states_t s = ESP_OTA_IMG_UNDEFINED;
    esp_ota_get_state_partition(esp_ota_get_running_partition(), &s);
    return s;
}

void ota_status(char *out, size_t len)
{
    const esp_app_desc_t *d = esp_app_get_description();
    char sha[65];
    esp_app_get_elf_sha256(sha, sizeof sha);
    int n = snprintf(out, len, "running %s (%s), built %s %s, elf %.9s, next slot %s",
                     esp_ota_get_running_partition()->label, state_name(running_state()),
                     d->date, d->time, sha, esp_ota_get_next_update_partition(NULL)->label);
    if (prog.state == OTA_RECEIVING && n > 0 && (size_t)n < len) {
        snprintf(out + n, len - n, "; receiving %lu/%lu", (unsigned long)prog.received,
                 (unsigned long)prog.size);
    }
}

static void confirm_task(void *arg)
{
    const unsigned timeout_ms = (unsigned)(uintptr_t)arg;
    if (ble_wait_ready(timeout_ms)) {
        esp_ota_mark_app_valid_cancel_rollback();
        ESP_LOGW(TAG, "new image confirmed (BLE advertising); rollback cancelled");
    } else {
        ESP_LOGE(TAG, "new image's BLE didn't come up in %u s; rebooting to roll back", timeout_ms / 1000);
        servo_off();
        vTaskDelay(pdMS_TO_TICKS(200));
        esp_restart();
    }
    vTaskDelete(NULL);
}

void ota_confirm_or_rollback(unsigned timeout_ms)
{
    if (running_state() != ESP_OTA_IMG_PENDING_VERIFY) return;
    ESP_LOGW(TAG, "running an unconfirmed update; confirming once BLE is up");
    xTaskCreate(confirm_task, "ota_confirm", 3072, (void *)(uintptr_t)timeout_ms, 5, NULL);
}
