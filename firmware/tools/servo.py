#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyserial"]
# ///
"""Control the actuator servo over USB serial.

    tools/servo.py angle 45      # horn angle, 0-90 deg (linkage-corrected)
    tools/servo.py pos 50        # stroke position, 0-100 %
    tools/servo.py us 1500       # raw pulse width, 500-2500 us
    tools/servo.py sweep 3       # glide end to end 3 times, then centre
    tools/servo.py off           # stop pulses now
    tools/servo.py hold 0        # hold forever: disables stall protection
    tools/servo.py limits off    # allow pulses past the rated stroke (calibration only)
    tools/servo.py status        # position, protection state, heat budget
    tools/servo.py               # interactive prompt

Or from Python (in tools/):  from servo import Servo; Servo().pos(75)
"""

import argparse
import sys
import time

import serial

PROMPT = b"servo> "
SWEEP_SECONDS = 3.0  # one sweep cycle on the device (2 x 1.5 s strokes)


class Servo:
    def __init__(self, port: str = "/dev/ttyACM0"):
        # Keep pyserial's default DTR/RTS (both asserted). Setting them
        # individually trips the ESP32-C6's USB reset and re-runs the boot sweep.
        self.ser = serial.Serial(port, 115200, timeout=0.05)

    def command(self, line: str, timeout: float = 2.0) -> str:
        """Send one console line; return the device's reply (echo stripped)."""
        self.ser.reset_input_buffer()
        self.ser.write(line.encode() + b"\r\n")
        buf = b""
        deadline = time.monotonic() + timeout
        while not buf.endswith(PROMPT):
            if time.monotonic() > deadline:
                raise TimeoutError(f"no reply to {line!r}")
            buf += self.ser.read(256)
        lines = buf[: -len(PROMPT)].decode(errors="replace").splitlines()
        reply = [l.strip() for l in lines if l.strip() not in ("", line, "servo>")]
        text = "\n".join(reply)
        if "usage:" in text or "non-zero error" in text or "Unrecognized" in text:
            raise ValueError(text)
        return text

    def pos(self, pct: float) -> str:
        return self.command(f"pos {pct:g}")

    def angle(self, deg: float) -> str:
        return self.command(f"angle {deg:g}")

    def hold(self, ms: int | None = None) -> str:
        return self.command("hold" if ms is None else f"hold {ms}")

    def limits(self, on: bool | None = None) -> str:
        return self.command("limits" if on is None else f"limits {'on' if on else 'off'}")

    def status(self) -> str:
        return self.command("status")

    def us(self, us: int) -> str:
        return self.command(f"us {us}")

    def sweep(self, times: int = 1) -> str:
        return self.command(f"sweep {times}", timeout=times * SWEEP_SECONDS + 3)

    def off(self) -> str:
        return self.command("off")

    def close(self) -> None:
        self.ser.close()


def interactive(servo: Servo) -> None:
    print("servo control: angle <0-90> | pos <0-100> | us <us> | sweep [n] | off | hold [ms] | limits [on|off] | status | help | quit")
    while True:
        try:
            line = input("servo> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if line in ("quit", "exit", "q"):
            return
        if not line:
            continue
        timeout = 2.0
        if line.startswith("sweep"):
            parts = line.split()
            n = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 1
            timeout = n * SWEEP_SECONDS + 3
        try:
            print(servo.command(line, timeout))
        except (ValueError, TimeoutError) as e:
            print(e)


def main() -> int:
    ap = argparse.ArgumentParser(description="Control the actuator servo.")
    ap.add_argument("-p", "--port", default="/dev/ttyACM0")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("angle", help="horn angle in degrees (0-90)").add_argument("deg", type=float)
    sub.add_parser("hold", help="auto-release delay in ms (0 = never)").add_argument("ms", type=int, nargs="?")
    sub.add_parser("limits", help="clamp to the rated stroke").add_argument("state", choices=["on", "off"], nargs="?")
    sub.add_parser("status", help="position, protection state and heat budget")
    sub.add_parser("pos", help="stroke position in percent").add_argument("pct", type=float)
    sub.add_parser("us", help="raw pulse width in microseconds").add_argument("us", type=int)
    sub.add_parser("sweep", help="glide end to end").add_argument("times", type=int, nargs="?", default=1)
    sub.add_parser("off", help="stop sending pulses")
    args = ap.parse_args()

    try:
        servo = Servo(args.port)
    except serial.SerialException as e:
        print(f"can't open {args.port}: {e}", file=sys.stderr)
        return 1

    try:
        match args.cmd:
            case None:
                interactive(servo)
                return 0
            case "angle":
                print(servo.angle(args.deg))
            case "hold":
                print(servo.hold(args.ms))
            case "limits":
                print(servo.limits(None if args.state is None else args.state == "on"))
            case "status":
                print(servo.status())
            case "pos":
                print(servo.pos(args.pct))
            case "us":
                print(servo.us(args.us))
            case "sweep":
                servo.sweep(args.times)
                print(f"swept {args.times}x, back at centre")
            case "off":
                print(servo.off())
    except (ValueError, TimeoutError) as e:
        print(e, file=sys.stderr)
        return 1
    finally:
        servo.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
