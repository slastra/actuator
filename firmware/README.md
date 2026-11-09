# actuator

Drives an AGFRC 1.5 g linear micro servo (9 mm stroke, worm drive) from a Seeed
XIAO ESP32C6, controlled over **BLE** from the Android app in
[`../android`](../android). Firmware updates also go over BLE. There is no
WiFi. USB is only needed to flash a blank board, and for the serial console.

## Wiring

| Servo wire | XIAO pin |
|---|---|
| red (V+) | **5V** (VBUS) |
| brown (GND) | **GND** |
| orange (signal) | **D10** (GPIO18) |

The servo is rated 3.7-6 V, so the 5 V USB rail is fine, and the 3.3 V signal
level is fine for a digital servo. Two things to watch for:

- **5V, GND and D10 aren't adjacent** on the XIAO. The order is 5V, GND, 3V3,
  D10, so the JR plug can't go on as one block. Use jumper wires. If you push
  it straight on, the signal lands on 3V3.
- **5V is only live on USB power.** If you run from a battery on the back pads,
  the servo needs its own supply.

## Build and flash

ESP-IDF **v6.0.2**:

```sh
. $IDF_PATH/export.sh
idf.py build
idf.py -p /dev/ttyACM0 flash monitor
```

The C6's USB is built into the chip, so it shows up as `/dev/ttyACM0`.
`tools/ota-push` finds ESP-IDF through `$IDF_PATH` (default `~/esp/esp-idf`).

**Secrets:** the BLE pairing PIN lives in `sdkconfig.defaults.local`
(gitignored, mode 600; template in `sdkconfig.defaults.local.example`). Defaults
only seed a *new* `sdkconfig`, so after changing it, `rm sdkconfig` and rebuild.

## BLE

The device advertises as **Actuator**. Every characteristic requires an
authenticated, encrypted link. The first time, a phone pairs with the 6-digit
`CONFIG_ACTUATOR_BLE_PIN` (Android shows its PIN dialog); after that it is
bonded and connects silently. Bonds persist in NVS; `ble forget` on the console
deletes them all. The firmware takes one connection at a time and stops
advertising while connected, so the app lets go when it leaves the foreground.

| UUID `c175482b-6776-4dfd-9299-a5b850b379..` | | |
|---|---|---|
| `..00` service | | |
| `..01` Angle | read, write, write-no-rsp | u16 LE, tenths of a degree, 0-900 |
| `..02` Command | write | u8: 0 = off, 1 = sweep |
| `..03` Status | read, notify | u16 angle (0xFFFF unknown), u16 µs, u8 flags (driving, limits, locked, sweeping), u8 heat %, u8 cooldown s |
| `..10` OTA service | | |
| `..11` OTA control | write, read, notify | write `01`+u32 size = begin, `02` = finish, `03` = abort; read u8 state, u8 error, u32 received, u32 size |
| `..12` OTA data | write, write-no-rsp | u32 offset + image bytes, strictly in order |
| `..13` OTA info | read | text: running slot, state, build, ELF hash |

Write errors (ATT application codes): `0x80` out of range, `0x81` cooling down
(heat lockout), `0x82` busy (sweep or update running), `0x90+n` OTA error *n*
(`main/ota.h`). The firmware asks for a 7.5-15 ms connection interval once the
link is secure.

## Updating over BLE (OTA)

```sh
tools/ota-push              # build, pair if needed, push, wait until confirmed
tools/ota-push --no-build   # push build/actuator.bin as it is
tools/ota-push --info       # what's running
```

The first run pairs this machine automatically: the tool registers a BlueZ
agent that answers the passkey prompt with the PIN from `sdkconfig`. It
releases any lingering desktop connection first, because a held link hides
the device and locks phones out.

- **Two app slots** (`partitions.csv`: `ota_0`/`ota_1`, 1.9 MB each; the
  image is ~650 KB, built `-Os`). An update writes the idle slot, so the running image is
  never touched.
- The device checks the image's project name before writing anything, writes
  chunks strictly in sequence (a lost chunk is an error, not corruption), and
  verifies the image (`esp_ota_end`) before switching the boot slot. A dropped
  connection aborts the update. The servo's pulses are stopped first.
- **Rollback:** the new image boots unconfirmed and confirms itself once BLE is
  advertising, which is what it needs to take the next update. If BLE doesn't
  come up within 30 s, it reboots and the bootloader returns to the previous
  image.

Measured on 2026-11-08: 650 KB in about 15 s (505-byte chunks, 43-55 KB/s);
about 30 s from start to confirmed on the other slot. The radio isn't the
limit: forcing the 2M PHY changed nothing, so the time goes to erasing and
writing flash.

## Status LED

The yellow user LED (GPIO15) shows one pattern, highest priority first:

| Pattern | Meaning |
|---|---|
| Fast flicker | Firmware update coming in |
| Double blink | Heat lockout, cooling down |
| Solid | Connected and paired |
| Quick blink | Connected, pairing in progress |
| Blip every 2 s | Advertising, waiting for a phone |
| Off | BLE not up, or no power |

The red LED next to it belongs to the battery charger and can't be driven.

## Linkage

The servo drives a 6 mm horn arm through 90°. For the least stroke and best
torque, the swing is centred on the perpendicular: at 45° (1500 µs) the arm is
at right angles to the pushrod. That takes 2 × 6 × sin 45° = 8.5 mm of rod
travel. At the rated ~133 µs/mm (900-2100 µs for 9 mm), that is
**935-2065 µs**, set as `SERVO_MIN_US`/`SERVO_MAX_US` in `main/servo.h`.
Fine-tune those against the real linkage until the horn sits at exactly ±45°.

## Stall protection

A hobby servo gives no feedback, so the firmware can't see a stall. Instead it
avoids causing one, keeps any that does happen short, and caps the total heat
(`main/servo.h` has the numbers):

1. **Clamp.** Every pulse is clamped to the rated 900-2100 µs stroke, which
   stays clear of both mechanical stops. The low stop is ~655 µs; pushing into
   it for a while made the motor hot. `limits off` lifts the clamp for
   calibration only.
2. **Release.** Pulses stop once the move should be done: the expected travel
   time (0.12 ms per µs of change, slower than the 6 V rating to allow for
   5 V and load) plus 300 ms (`hold <ms>`). A full 90° move releases after
   ~0.45 s. `hold 0` holds forever and turns protection off.
3. **Heat budget.** All driving time is counted as if it were stall time.
   12 s of driving locks the servo out until the budget drains to 6 s, which
   happens at half speed, so 12 s of cooling. A long `sweep` will trip it.
   That's expected.

**What actually protects it:** the clamp. A test that started a full-stroke
move and stopped the pulses 40 ms in still reached the end, so this servo appears to
keep driving to its last target when the pulses stop. Release and the heat
budget therefore probably don't de-energize the motor. That matters less than
it sounds: the worm drive can't be back-driven, so the servo only stalls when it
is commanded somewhere it can't reach. The clamp prevents that for the end
stops. A jammed linkage would still need a switch on the servo's 5 V line or
current sensing; this is deferred for now.

## Control

Day to day it's the Android app over BLE. Over USB, for the bench:

```sh
tools/servo_gui.py              # slider GUI: horn angle + raw µs slider
tools/servo.py angle 45         # 0-90°, corrected for the sin() of the linkage
tools/servo.py us 1500          # raw pulse, 500-2500 µs
tools/servo.py sweep 2          # glide end to end, then back to 45°
tools/servo.py status           # position, protection state, heat budget
tools/servo.py hold 0           # hold forever: no stall protection
tools/servo.py limits off       # allow pulses past the rated stroke
tools/servo.py                  # interactive prompt
```

`servo.py` runs under uv (PEP 723). `servo_gui.py` is GTK4/libadwaita on the
system Python (native Wayland, no Xwayland needed). It forces
`GSK_RENDERER=ngl` because GTK's default Vulkan renderer can hang on NVIDIA
drivers. Only one of them can hold the serial port at a time. Opening the port does not reset the board
as long as DTR/RTS are left at pyserial's defaults.

The device console (`idf.py monitor`) has the same commands, plus `pos <0-100>`,
`ble [forget]`, `ota` and `help`.

## Files

| File | What it does |
|---|---|
| `main/servo.c` | LEDC PWM at 50 Hz, 14-bit duty; angle/percent/µs control; sweep; clamp, release and heat budget |
| `main/servo.h` | Pin, pulse-range and protection constants |
| `main/ble.h` | The BLE protocol: UUIDs, value layouts, error codes |
| `main/ble.c` | NimBLE peripheral: control and OTA services, PIN pairing, status notifications |
| `main/led.c` | Status LED patterns |
| `main/ota.c` | Receive into the idle slot, verify, switch; confirm-or-rollback |
| `main/main.c` | Boot: NVS, BLE, rollback guard, sweep; the `servo>` console |
| `partitions.csv` | nvs, otadata, two 1.9 MB app slots |
| `tools/ota-push` | Build and push an update over BLE (uv script: bleak + a BlueZ PIN agent) |
| `tools/servo.py` | USB-serial CLI + `Servo` class |
| `tools/servo_gui.py` | GTK4 slider GUI over USB serial |
