#!/usr/bin/env python3
"""Slider GUI for the actuator servo (GTK4 / libadwaita, native Wayland).

    tools/servo_gui.py [--port /dev/ttyACM0]

Uses the system Python, which has PyGObject, GTK4, libadwaita and pyserial.
"""

import argparse
import os
import sys
import threading

# GTK's default Vulkan renderer can hang in present() on NVIDIA drivers;
# the GL renderer works. Must be set before Gtk is imported.
os.environ.setdefault("GSK_RENDERER", "ngl")

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

import serial  # noqa: E402

from servo import Servo  # noqa: E402


class Worker:
    """Sends commands on a background thread. Only the newest request is kept,
    so dragging a slider fast never queues a backlog."""

    def __init__(self, servo: Servo, on_reply):
        self.servo, self.on_reply = servo, on_reply
        self.pending: tuple[str, float] | None = None
        self.cv = threading.Condition()
        threading.Thread(target=self.run, daemon=True).start()

    def request(self, cmd: str, value: float = 0) -> None:
        with self.cv:
            self.pending = (cmd, value)
            self.cv.notify()

    def run(self) -> None:
        while True:
            with self.cv:
                while self.pending is None:
                    self.cv.wait()
                cmd, value = self.pending
                self.pending = None
            try:
                match cmd:
                    case "angle":
                        reply = self.servo.angle(value)
                    case "us":
                        reply = self.servo.us(int(value))
                    case "sweep":
                        GLib.idle_add(self.on_reply, "sweeping…")
                        self.servo.sweep(1)
                        reply = "sweep done, back at 45°"
                    case "off":
                        reply = self.servo.off()
                    case _:
                        reply = f"unknown command {cmd}"
            except (ValueError, TimeoutError, serial.SerialException) as e:
                reply = f"error: {e}"
            GLib.idle_add(self.on_reply, reply)


class Window(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application, servo: Servo):
        super().__init__(application=app, title="Actuator")
        self.set_default_size(460, -1)
        self.set_resizable(False)
        self.worker = Worker(servo, self.show_reply)
        self.syncing = False  # True while a slider is being set from code

        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14,
                       margin_top=12, margin_bottom=18, margin_start=18, margin_end=18)

        body.append(self.heading("Horn angle"))
        self.angle = self.slider(0, 90, 0.5, 1, 45, "°", self.on_angle)
        for mark in (0, 45, 90):
            self.angle.add_mark(mark, Gtk.PositionType.BOTTOM, f"{mark}°")
        body.append(self.angle)

        body.append(self.heading("Pulse (µs) — fine-tuning, clamped to 900–2100"))
        self.us = self.slider(900, 2100, 5, 0, 1500, " µs", self.on_us)
        body.append(self.us)

        buttons = Gtk.Box(spacing=6, homogeneous=True)
        for label, handler in (("0°", lambda *_: self.jump(0)),
                               ("45°", lambda *_: self.jump(45)),
                               ("90°", lambda *_: self.jump(90)),
                               ("Sweep", self.on_sweep),
                               ("Off", lambda *_: self.worker.request("off"))):
            b = Gtk.Button(label=label)
            b.connect("clicked", handler)
            buttons.append(b)
        body.append(buttons)

        self.status = Gtk.Label(label=f"connected to {servo.ser.port}", xalign=0)
        self.status.add_css_class("dim-label")
        self.status.add_css_class("monospace")
        body.append(self.status)

        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        view.set_content(body)
        self.set_content(view)

        self.worker.request("angle", 45)

    @staticmethod
    def heading(text: str) -> Gtk.Label:
        label = Gtk.Label(label=text, xalign=0)
        label.add_css_class("heading")
        return label

    def slider(self, lo, hi, step, digits, value, unit, handler) -> Gtk.Scale:
        s = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, lo, hi, step)
        s.set_value(value)
        s.set_digits(digits)
        s.set_draw_value(True)
        s.set_value_pos(Gtk.PositionType.RIGHT)
        s.set_format_value_func(lambda _s, v: f"{v:.{digits}f}{unit}")
        s.set_hexpand(True)
        s.connect("value-changed", handler)
        return s

    def set_quietly(self, scale: Gtk.Scale, value: float) -> None:
        self.syncing = True
        scale.set_value(value)
        self.syncing = False

    def on_angle(self, scale: Gtk.Scale) -> None:
        if not self.syncing:
            self.worker.request("angle", scale.get_value())

    def on_us(self, scale: Gtk.Scale) -> None:
        if not self.syncing:
            self.worker.request("us", scale.get_value())

    def jump(self, deg: float) -> None:
        self.set_quietly(self.angle, deg)
        self.worker.request("angle", deg)

    def on_sweep(self, *_) -> None:
        self.set_quietly(self.angle, 45)  # the device returns to centre after a sweep
        self.worker.request("sweep")

    def show_reply(self, text: str) -> bool:
        self.status.set_label(text)
        # "angle 45.0 deg -> 1500 us": keep the µs slider in step
        if "->" in text:
            us = text.rsplit("->", 1)[-1].split()[0]
            if us.isdigit():
                self.set_quietly(self.us, int(us))
        return False  # one-shot idle callback


def main() -> int:
    ap = argparse.ArgumentParser(description="Slider GUI for the actuator servo.")
    ap.add_argument("-p", "--port", default="/dev/ttyACM0")
    args = ap.parse_args()
    try:
        servo = Servo(args.port)
    except serial.SerialException as e:
        print(f"can't open {args.port}: {e}", file=sys.stderr)
        return 1

    app = Adw.Application(application_id="us.lastra.Actuator")
    app.connect("activate", lambda a: Window(a, servo).present())
    try:
        return app.run([sys.argv[0]])
    finally:
        servo.close()


if __name__ == "__main__":
    sys.exit(main())
