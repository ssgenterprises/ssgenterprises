"""Radio Replay – continuously record a receiver and replay the last N seconds on a button press.

Run:  python3 -m radio_replay.main [--config config.ini] [--keyboard] [--list-devices]
"""

from __future__ import annotations

import argparse
import configparser
import datetime as dt
import logging
import os
import signal
import sys
import threading
import time

from .engine import ReplayEngine

log = logging.getLogger("radio_replay")

DEFAULTS = {
    "audio": {
        "sample_rate": "16000",      # 16 kHz is plenty for SSB/FM voice; use 48000 for wideband
        "channels": "1",
        "input_device": "",          # blank = system default; name substring or index
        "output_device": "",
        "blocksize": "1024",
        "playback_gain": "1.0",
        "monitor": "no",             # yes = pass live audio to output when not replaying
        "monitor_gain": "1.0",
    },
    "buffer": {
        "durations": "5,10,20,30,60,120,240",
    },
    "gpio": {
        # BCM pin numbers, one per duration above, in the same order. Buttons go pin -> GND.
        "replay_pins": "17,27,22,5,6,13,19",
        "stop_pin": "26",
        "save_pin": "21",            # blank to disable
        "led_pin": "12",             # blank to disable; lit while replaying
        "bounce_ms": "50",
    },
    "save": {
        "directory": "~/radio-replay-clips",
        "seconds": "240",
    },
}


def load_config(path: str | None) -> configparser.ConfigParser:
    cfg = configparser.ConfigParser()
    cfg.read_dict(DEFAULTS)
    if path:
        if not cfg.read(path):
            log.warning("config file %s not found, using defaults", path)
    return cfg


def parse_device(value: str):
    value = value.strip()
    if not value:
        return None
    return int(value) if value.isdigit() else value


def int_list(value: str) -> list[int]:
    return [int(v) for v in value.replace(" ", "").split(",") if v]


class App:
    def __init__(self, cfg: configparser.ConfigParser):
        a = cfg["audio"]
        self.durations = int_list(cfg["buffer"]["durations"])
        self.engine = ReplayEngine(
            sample_rate=a.getint("sample_rate"),
            channels=a.getint("channels"),
            max_seconds=max(self.durations),
            playback_gain=a.getfloat("playback_gain"),
            monitor=a.getboolean("monitor"),
            monitor_gain=a.getfloat("monitor_gain"),
        )
        self.cfg = cfg
        self.led = None
        self._buttons = []
        self._stop_event = threading.Event()

    # ---- actions ---------------------------------------------------------
    def replay(self, seconds: int) -> None:
        avail = self.engine.ring.available / self.engine.sample_rate
        if avail < seconds:
            log.info("replay last %ds (only %.0fs recorded so far)", seconds, avail)
        else:
            log.info("replay last %ds", seconds)
        self.engine.replay(seconds)

    def stop(self) -> None:
        log.info("stop")
        self.engine.stop()

    def save(self) -> None:
        s = self.cfg["save"]
        directory = os.path.expanduser(s["directory"])
        os.makedirs(directory, exist_ok=True)
        name = dt.datetime.now().strftime("replay_%Y%m%d_%H%M%S.wav")
        path = os.path.join(directory, name)

        def worker():
            secs = self.engine.save_wav(path, s.getfloat("seconds"))
            log.info("saved %.0fs to %s", secs, path)

        threading.Thread(target=worker, daemon=True).start()

    # ---- inputs ----------------------------------------------------------
    def setup_gpio(self) -> bool:
        try:
            from gpiozero import LED, Button
        except ImportError:
            log.warning("gpiozero not installed – GPIO buttons disabled")
            return False
        g = self.cfg["gpio"]
        pins = int_list(g["replay_pins"])
        if len(pins) != len(self.durations):
            raise SystemExit("gpio.replay_pins must have one pin per buffer.durations entry")
        bounce = g.getint("bounce_ms") / 1000.0
        try:
            for pin, secs in zip(pins, self.durations):
                b = Button(pin, pull_up=True, bounce_time=bounce)
                b.when_pressed = lambda s=secs: self.replay(s)
                self._buttons.append(b)
            b = Button(int(g["stop_pin"]), pull_up=True, bounce_time=bounce)
            b.when_pressed = self.stop
            self._buttons.append(b)
            if g["save_pin"].strip():
                b = Button(int(g["save_pin"]), pull_up=True, bounce_time=bounce)
                b.when_pressed = self.save
                self._buttons.append(b)
            if g["led_pin"].strip():
                self.led = LED(int(g["led_pin"]))
        except Exception as e:  # not on a Pi, no GPIO access, etc.
            log.warning("GPIO unavailable (%s) – buttons disabled", e)
            return False
        log.info("GPIO ready: %s",
                 ", ".join(f"{s}s=GPIO{p}" for s, p in zip(self.durations, pins))
                 + f", stop=GPIO{g['stop_pin']}"
                 + (f", save=GPIO{g['save_pin']}" if g["save_pin"].strip() else ""))
        return True

    def keyboard_loop(self) -> None:
        keys = "1234567890"[: len(self.durations)]
        print("Keyboard control:")
        for k, s in zip(keys, self.durations):
            print(f"  {k} + Enter : replay last {s}s")
        print("  s + Enter : stop    w + Enter : save WAV    q + Enter : quit")
        for line in sys.stdin:
            for ch in line.strip().lower():
                if ch in keys:
                    self.replay(self.durations[keys.index(ch)])
                elif ch == "s":
                    self.stop()
                elif ch == "w":
                    self.save()
                elif ch == "q":
                    self._stop_event.set()
                    return
        # stdin closed (e.g. running as a service) – just keep running

    # ---- main ------------------------------------------------------------
    def run(self, keyboard: bool) -> None:
        import sounddevice as sd

        a = self.cfg["audio"]
        e = self.engine

        def callback(indata, outdata, frames, time_info, status):
            if status:
                log.debug("audio status: %s", status)
            e.process(indata, outdata)

        stream = sd.Stream(
            samplerate=e.sample_rate,
            channels=e.channels,
            dtype="int16",
            blocksize=a.getint("blocksize"),
            device=(parse_device(a["input_device"]), parse_device(a["output_device"])),
            callback=callback,
            latency="high",
        )

        gpio_ok = self.setup_gpio()
        if keyboard or not gpio_ok:
            threading.Thread(target=self.keyboard_loop, daemon=True).start()

        signal.signal(signal.SIGTERM, lambda *_: self._stop_event.set())
        with stream:
            log.info("recording at %d Hz, %d ch, buffer %ds",
                     e.sample_rate, e.channels, e.max_seconds)
            last = None
            try:
                while not self._stop_event.wait(0.05):
                    playing = e.status().playing
                    if self.led is not None and playing != last:
                        self.led.value = playing
                    if last and not playing:
                        log.info("replay finished")
                    last = playing
            except KeyboardInterrupt:
                pass
        if self.led is not None:
            self.led.off()
        log.info("exiting")


def main(argv=None) -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--config", "-c", help="INI config file")
    p.add_argument("--keyboard", "-k", action="store_true",
                   help="also accept keyboard commands (for testing without buttons)")
    p.add_argument("--list-devices", action="store_true", help="list audio devices and exit")
    p.add_argument("--verbose", "-v", action="store_true")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    if args.list_devices:
        import sounddevice as sd
        print(sd.query_devices())
        return
    App(load_config(args.config)).run(args.keyboard)


if __name__ == "__main__":
    main()
