# actuator (Android)

Drives the linear-servo actuator over BLE. Firmware, protocol and wiring live in
[`../firmware`](../firmware) (see its README, section BLE).

Kotlin + Jetpack Compose, **Material 3 Expressive** (material3 1.5.0-rc01:
button groups, loading indicator, expressive motion), Material You colours from
the wallpaper. minSdk 33, compileSdk/target 37. Built for and tested on a Pixel
8a (Android 17).

| File | |
|---|---|
| `ActuatorLink.kt` | BLE: scan, pair, connect, GATT op queue, status decoding |
| `ActuatorScreen.kt` | The screen: controls, status card, connection states |
| `HornGauge.kt` | The animated horn drawing |
| `MainActivity.kt` | Theme, permissions, starts/stops the link with the activity |

## Use

Open the app. It scans for the actuator's service UUID and connects. The first
time, Android asks for the 6-digit pairing PIN, which is
`CONFIG_ACTUATOR_BLE_PIN` in the firmware's `sdkconfig.defaults.local`. After
that the phone is bonded and connects silently.

- **Slider:** horn angle 0-90° in 0.5° steps. Only the newest position is sent,
  so a fast drag never queues up.
- **0° / 45° / 90°, Sweep, Off:** sent with write responses, so the firmware
  can refuse them. Refusals show as a message: cooling down (heat lockout) or
  busy (sweep or update running).
- **Status card:** live from notifications: pulse width, driving/released/
  sweeping, heat budget, and a cooldown banner during a heat lockout. Sliders and
  presets lock while sweeping or cooling; Off always works.

The app holds the connection only while it is in the foreground. The firmware
takes one connection at a time, so leaving the app frees it for the desktop
update tool.

To re-pair: forget the device in Android's Bluetooth settings, run `ble forget`
on the firmware console (or just re-pair: the firmware drops a stale bond when
the phone asks to pair again), then reopen the app.

## Build and install

Toolchain: AGP 9.5.1 (it compiles Kotlin itself; KGP 2.4.21 on the buildscript
classpath, matched by the Compose compiler plugin), Gradle 9.9.0. Gradle needs
`JAVA_HOME` at JDK 17 or newer. The Android SDK needs `platforms/android-37.0`
and `build-tools/37.0.0`, found through `local.properties` (`sdk.dir=...`, which
Android Studio writes) or `ANDROID_HOME`. material3 1.5 is what
forces compileSdk 37 and AGP 9.1+.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# optional: skip the Nearby devices permission dialog
adb shell pm grant us.lastra.actuator android.permission.BLUETOOTH_SCAN
adb shell pm grant us.lastra.actuator android.permission.BLUETOOTH_CONNECT
```
