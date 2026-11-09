#include "ble.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "sdkconfig.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "ota.h"
#include "servo.h"

#define TAG "ble"

// Not in a public header; every NimBLE example declares it like this.
void ble_store_config_init(void);

// Application ATT errors (ble.h).
#define ATT_ERR_OUT_OF_RANGE 0x80
#define ATT_ERR_LOCKED       0x81
#define ATT_ERR_BUSY         0x82
#define ATT_ERR_OTA_BASE     0x90

// c175482b-6776-4dfd-9299-a5b850b379NN, little-endian as NimBLE wants it.
#define ACTUATOR_UUID(nn) \
    BLE_UUID128_INIT(nn, 0x79, 0xb3, 0x50, 0xb8, 0xa5, 0x99, 0x92, \
                     0xfd, 0x4d, 0x76, 0x67, 0x2b, 0x48, 0x75, 0xc1)

static const ble_uuid128_t svc_uuid = ACTUATOR_UUID(0x00);
static const ble_uuid128_t angle_uuid = ACTUATOR_UUID(0x01);
static const ble_uuid128_t command_uuid = ACTUATOR_UUID(0x02);
static const ble_uuid128_t status_uuid = ACTUATOR_UUID(0x03);
static const ble_uuid128_t ota_svc_uuid = ACTUATOR_UUID(0x10);
static const ble_uuid128_t ota_ctrl_uuid = ACTUATOR_UUID(0x11);
static const ble_uuid128_t ota_data_uuid = ACTUATOR_UUID(0x12);
static const ble_uuid128_t ota_info_uuid = ACTUATOR_UUID(0x13);

static uint8_t own_addr_type;
static uint16_t conn = BLE_HS_CONN_HANDLE_NONE;
static bool encrypted;
static uint16_t status_handle;
static uint16_t ota_ctrl_handle;
static EventGroupHandle_t ready;  // bit 0: advertising

static int advertise(void);

/* ---- packing (layouts in ble.h) ------------------------------------------- */

#define STATUS_LEN 7
#define OTA_PROGRESS_LEN 10

// Angle characteristic value: tenths of a degree, 0xFFFF before the first move.
static uint16_t angle_tenths(uint32_t us)
{
    return us ? (uint16_t)(servo_us_to_angle(us) * 10 + 0.5f) : 0xFFFF;
}

static void status_pack(uint8_t out[STATUS_LEN])
{
    servo_status_t st;
    servo_get_status(&st);
    const uint16_t angle = angle_tenths(st.us);
    const uint8_t flags = (st.driving ? 1 : 0) | (st.limits ? 2 : 0) | (st.locked ? 4 : 0) |
                          (st.sweeping ? 8 : 0);
    uint32_t heat = st.heat_ms * 100 / SERVO_HEAT_CAP_MS;
    uint32_t cool = (st.cooldown_ms + 999) / 1000;
    out[0] = angle & 0xff;
    out[1] = angle >> 8;
    out[2] = st.us & 0xff;
    out[3] = (st.us >> 8) & 0xff;
    out[4] = flags;
    out[5] = heat > 100 ? 100 : heat;
    out[6] = cool > 255 ? 255 : cool;
}

static void ota_pack(uint8_t out[OTA_PROGRESS_LEN])
{
    ota_progress_t p;
    ota_get_progress(&p);
    out[0] = p.state;
    out[1] = p.error;
    memcpy(out + 2, &p.received, 4);  // the C6 is little-endian
    memcpy(out + 6, &p.size, 4);
}

/* ---- notifications ---------------------------------------------------------
 * Sent when a value differs from the last one sent, whoever notices first:
 * a GATT write that changed it (immediately) or notify_task (movement,
 * release, heat and lockout happen off the GATT path). Sharing `last_*`
 * keeps the two from sending the same value twice.
 * ble_gatts_chr_updated() only reaches subscribed peers.
 */
static portMUX_TYPE notify_mux = portMUX_INITIALIZER_UNLOCKED;
static uint8_t last_status[STATUS_LEN];
static uint8_t last_ota[OTA_PROGRESS_LEN];

static bool take_if_changed(uint8_t *last, const uint8_t *now, size_t len)
{
    taskENTER_CRITICAL(&notify_mux);
    const bool changed = memcmp(last, now, len) != 0;
    if (changed) memcpy(last, now, len);
    taskEXIT_CRITICAL(&notify_mux);
    return changed;
}

static void notify_status(void)
{
    uint8_t now[STATUS_LEN];
    status_pack(now);
    if (take_if_changed(last_status, now, sizeof now)) ble_gatts_chr_updated(status_handle);
}

static void notify_ota(void)
{
    uint8_t now[OTA_PROGRESS_LEN];
    ota_pack(now);
    if (take_if_changed(last_ota, now, sizeof now)) ble_gatts_chr_updated(ota_ctrl_handle);
}

static void notify_task(void *arg)
{
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(100));
        if (conn == BLE_HS_CONN_HANDLE_NONE || !encrypted) continue;
        notify_status();
        notify_ota();
    }
}

/* ---- GATT ----------------------------------------------------------------- */

static void sweep_task(void *arg)
{
    servo_sweep(1);
    vTaskDelete(NULL);
}

static int read_flat(struct os_mbuf *om, void *dst, uint16_t len)
{
    if (OS_MBUF_PKTLEN(om) != len) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    return ble_hs_mbuf_to_flat(om, dst, len, NULL) == 0 ? 0 : BLE_ATT_ERR_UNLIKELY;
}

static int on_access(uint16_t conn_handle, uint16_t attr_handle,
                     struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    const ble_uuid_t *uuid = ctxt->chr->uuid;

    if (ble_uuid_cmp(uuid, &status_uuid.u) == 0) {
        uint8_t buf[STATUS_LEN];
        status_pack(buf);
        return os_mbuf_append(ctxt->om, buf, sizeof buf) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    if (ble_uuid_cmp(uuid, &angle_uuid.u) == 0) {
        if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
            servo_status_t st;
            servo_get_status(&st);
            const uint16_t a = angle_tenths(st.us);
            const uint8_t le[2] = { a & 0xff, a >> 8 };
            return os_mbuf_append(ctxt->om, le, 2) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
        }
        uint8_t le[2];
        int rc = read_flat(ctxt->om, le, 2);
        if (rc) return rc;
        const uint16_t tenths = le[0] | (le[1] << 8);
        if (tenths > SERVO_ANGLE_RANGE * 10) return ATT_ERR_OUT_OF_RANGE;
        servo_status_t st;
        servo_get_status(&st);
        if (st.sweeping || ota_in_progress()) return ATT_ERR_BUSY;
        const esp_err_t err = servo_set_angle(tenths / 10.0f);
        if (err == ESP_ERR_INVALID_STATE) return ATT_ERR_LOCKED;
        if (err != ESP_OK) return BLE_ATT_ERR_UNLIKELY;
        notify_status();
        return 0;
    }

    if (ble_uuid_cmp(uuid, &command_uuid.u) == 0) {
        uint8_t op;
        int rc = read_flat(ctxt->om, &op, 1);
        if (rc) return rc;
        servo_status_t st;
        servo_get_status(&st);
        switch (op) {
        case 0:
            servo_off();
            break;
        case 1:
            if (st.sweeping || ota_in_progress()) return ATT_ERR_BUSY;
            if (st.locked) return ATT_ERR_LOCKED;
            // A sweep blocks for ~4 s: never on the NimBLE host task.
            if (xTaskCreate(sweep_task, "ble_sweep", 3072, NULL, 5, NULL) != pdPASS) {
                return BLE_ATT_ERR_INSUFFICIENT_RES;
            }
            break;
        default:
            return ATT_ERR_OUT_OF_RANGE;
        }
        notify_status();
        return 0;
    }

    return BLE_ATT_ERR_UNLIKELY;
}

/* ---- OTA ------------------------------------------------------------------ */

static int on_ota_access(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    const ble_uuid_t *uuid = ctxt->chr->uuid;

    if (ble_uuid_cmp(uuid, &ota_info_uuid.u) == 0) {
        char line[200];
        ota_status(line, sizeof line);
        return os_mbuf_append(ctxt->om, line, strlen(line)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    if (ble_uuid_cmp(uuid, &ota_data_uuid.u) == 0) {
        // u32 offset + bytes. Flatten into a buffer big enough for any MTU.
        static uint8_t buf[BLE_ATT_ATTR_MAX_LEN];
        const uint16_t n = OS_MBUF_PKTLEN(ctxt->om);
        if (n <= 4 || n > sizeof buf) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        if (ble_hs_mbuf_to_flat(ctxt->om, buf, n, NULL) != 0) return BLE_ATT_ERR_UNLIKELY;
        uint32_t offset;
        memcpy(&offset, buf, 4);
        const ota_error_t e = ota_write(offset, buf + 4, n - 4);
        return e == OTA_OK ? 0 : ATT_ERR_OTA_BASE + e;
    }

    // Control.
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t out[OTA_PROGRESS_LEN];
        ota_pack(out);
        return os_mbuf_append(ctxt->om, out, sizeof out) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    uint8_t cmd[5];
    const uint16_t n = OS_MBUF_PKTLEN(ctxt->om);
    if (n < 1 || n > sizeof cmd || ble_hs_mbuf_to_flat(ctxt->om, cmd, n, NULL) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    ota_error_t e;
    switch (cmd[0]) {
    case 0x01: {
        if (n != 5) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        servo_status_t st;
        servo_get_status(&st);
        if (st.sweeping) return ATT_ERR_BUSY;
        uint32_t size;
        memcpy(&size, cmd + 1, 4);
        e = ota_begin(size);
        break;
    }
    case 0x02:
        e = ota_finish();
        break;
    case 0x03:
        ota_abort();
        e = OTA_OK;
        break;
    default:
        return ATT_ERR_OUT_OF_RANGE;
    }
    notify_ota();
    return e == OTA_OK ? 0 : ATT_ERR_OTA_BASE + e;
}

// Authenticated (MITM, i.e. passkey) and encrypted, for every access.
#define SECURE_RD (BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC | BLE_GATT_CHR_F_READ_AUTHEN)
#define SECURE_WR (BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_WRITE_AUTHEN)

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &angle_uuid.u,
                .access_cb = on_access,
                .flags = SECURE_RD | SECURE_WR | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &command_uuid.u,
                .access_cb = on_access,
                .flags = SECURE_WR,
            },
            {
                .uuid = &status_uuid.u,
                .access_cb = on_access,
                .flags = SECURE_RD | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &status_handle,
            },
            { 0 },
        },
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &ota_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &ota_ctrl_uuid.u,
                .access_cb = on_ota_access,
                .flags = SECURE_RD | SECURE_WR | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &ota_ctrl_handle,
            },
            {
                .uuid = &ota_data_uuid.u,
                .access_cb = on_ota_access,
                .flags = SECURE_WR | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = &ota_info_uuid.u,
                .access_cb = on_ota_access,
                .flags = SECURE_RD,
            },
            { 0 },
        },
    },
    { 0 },
};

/* ---- GAP ------------------------------------------------------------------ */

static int on_gap(struct ble_gap_event *ev, void *arg)
{
    struct ble_gap_conn_desc desc;

    switch (ev->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (ev->connect.status != 0) {
            advertise();
            return 0;
        }
        conn = ev->connect.conn_handle;
        encrypted = false;
        ESP_LOGI(TAG, "connected; asking for security");
        // Bonded phones re-encrypt silently; new ones get the PIN prompt.
        ble_gap_security_initiate(conn);
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected (reason 0x%x)", ev->disconnect.reason);
        conn = BLE_HS_CONN_HANDLE_NONE;
        encrypted = false;
        ota_progress_t p;
        ota_get_progress(&p);
        if (p.state == OTA_RECEIVING) ota_abort();  // the sender is gone
        advertise();
        return 0;

    case BLE_GAP_EVENT_ENC_CHANGE:
        if (ev->enc_change.status == 0 && ble_gap_conn_find(ev->enc_change.conn_handle, &desc) == 0) {
            encrypted = desc.sec_state.encrypted && desc.sec_state.authenticated;
            ESP_LOGI(TAG, "link %s%s", desc.sec_state.authenticated ? "authenticated" : "encrypted only",
                     desc.sec_state.bonded ? ", bonded" : "");
            // Ask for a short connection interval: snappier slider and far
            // faster updates. Centrals may grant something longer.
            const struct ble_gap_upd_params fast = {
                .itvl_min = 6,   // x1.25 ms = 7.5 ms
                .itvl_max = 12,  // 15 ms
                .latency = 0,
                .supervision_timeout = 400,  // x10 ms = 4 s
            };
            ble_gap_update_params(ev->enc_change.conn_handle, &fast);
        } else {
            ESP_LOGW(TAG, "pairing failed (status %d): wrong PIN?", ev->enc_change.status);
        }
        return 0;

    case BLE_GAP_EVENT_PASSKEY_ACTION:
        // We have no display, so we "display" a fixed passkey: the phone asks
        // its user to type it.
        if (ev->passkey.params.action == BLE_SM_IOACT_DISP) {
            struct ble_sm_io pk = {
                .action = BLE_SM_IOACT_DISP,
                .passkey = CONFIG_ACTUATOR_BLE_PIN,
            };
            ble_sm_inject_io(ev->passkey.conn_handle, &pk);
        }
        return 0;

    case BLE_GAP_EVENT_REPEAT_PAIRING:
        // The phone lost its bond but we kept ours: drop ours and pair again.
        if (ble_gap_conn_find(ev->repeat_pairing.conn_handle, &desc) == 0) {
            ble_store_util_delete_peer(&desc.peer_id_addr);
        }
        return BLE_GAP_REPEAT_PAIRING_RETRY;

    case BLE_GAP_EVENT_CONN_UPDATE:
        if (ble_gap_conn_find(ev->conn_update.conn_handle, &desc) == 0) {
            ESP_LOGI(TAG, "connection interval %.2f ms", desc.conn_itvl * 1.25);
        }
        return 0;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu %d", ev->mtu.value);
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "status notifications %s", ev->subscribe.cur_notify ? "on" : "off");
        return 0;

    default:
        return 0;
    }
}

static int advertise(void)
{
    // Service UUID in the advert (the app scans for it), name in the scan
    // response: both together don't fit in 31 bytes.
    struct ble_hs_adv_fields adv = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .uuids128 = &svc_uuid,
        .num_uuids128 = 1,
        .uuids128_is_complete = 1,
    };
    const char *name = ble_svc_gap_device_name();
    struct ble_hs_adv_fields rsp = {
        .name = (const uint8_t *)name,
        .name_len = strlen(name),
        .name_is_complete = 1,
    };
    struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
    };
    int rc = ble_gap_adv_set_fields(&adv);
    if (rc == 0) rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc == 0) rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, on_gap, NULL);
    if (rc != 0) ESP_LOGE(TAG, "advertising failed: %d", rc);
    return rc;
}

static void on_sync(void)
{
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &own_addr_type);
    uint8_t addr[6];
    ble_hs_id_copy_addr(own_addr_type, addr, NULL);
    ESP_LOGI(TAG, "advertising as \"%s\" (%02x:%02x:%02x:%02x:%02x:%02x)", ble_svc_gap_device_name(),
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
    if (advertise() == 0) xEventGroupSetBits(ready, BIT0);
}

static void on_reset(int reason)
{
    ESP_LOGE(TAG, "host reset: %d", reason);
}

static void host_task(void *arg)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void ble_start(void)
{
    ready = xEventGroupCreate();
    ESP_ERROR_CHECK(nimble_port_init());

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_DISP_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    ble_svc_gap_device_name_set(CONFIG_ACTUATOR_BLE_NAME);
    ble_store_config_init();

    xTaskCreate(notify_task, "ble_notify", 3072, NULL, 4, NULL);
    nimble_port_freertos_init(host_task);
}

bool ble_wait_ready(unsigned timeout_ms)
{
    return ready && (xEventGroupWaitBits(ready, BIT0, pdFALSE, pdTRUE, pdMS_TO_TICKS(timeout_ms)) & BIT0);
}

ble_link_t ble_link(void)
{
    if (conn != BLE_HS_CONN_HANDLE_NONE) return encrypted ? BLE_LINK_SECURE : BLE_LINK_CONNECTED;
    return ready && (xEventGroupGetBits(ready) & BIT0) ? BLE_LINK_ADVERTISING : BLE_LINK_DOWN;
}

void ble_status(char *out, size_t len)
{
    int bonds = 0;
    ble_store_util_count(BLE_STORE_OBJ_TYPE_PEER_SEC, &bonds);
    struct ble_gap_conn_desc desc;
    if (conn != BLE_HS_CONN_HANDLE_NONE && ble_gap_conn_find(conn, &desc) == 0) {
        const uint8_t *a = desc.peer_id_addr.val;
        snprintf(out, len, "ble \"%s\": connected to %02x:%02x:%02x:%02x:%02x:%02x (%s), %d bonded",
                 ble_svc_gap_device_name(), a[5], a[4], a[3], a[2], a[1], a[0],
                 encrypted ? "secure" : "not yet paired", bonds);
    } else {
        snprintf(out, len, "ble \"%s\": advertising, %d bonded", ble_svc_gap_device_name(), bonds);
    }
}

void ble_forget_all(void)
{
    if (conn != BLE_HS_CONN_HANDLE_NONE) {
        ble_gap_terminate(conn, BLE_ERR_REM_USER_CONN_TERM);
    }
    ble_store_clear();
    ESP_LOGW(TAG, "all bonds deleted");
}
