idf_component_register(
    SRCS "main.c" "servo.c" "ota.c" "ble.c" "led.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES esp_driver_ledc esp_driver_gpio esp_timer console nvs_flash bt
                  app_update esp_app_format bootloader_support
)
