# Radio Replay

Raspberry Pi application that **continuously records** the audio output of an amateur radio
receiver and **instantly replays the last 5, 10, 20, 30, 60, 120 or 240 seconds** at the press of a
button: "What was that callsign?" → press **10 s**.

- Recording never stops, even during replay. A replay always ends at the moment you pressed.
- Pressing another replay button during playback restarts from *now* with the new length.
- **STOP** button ends playback. **SAVE** button (optional) writes the last 240 s to a timestamped WAV.
- LED (optional) is lit while replaying.
- Optional *monitor* mode passes live audio through to the speaker when not replaying.
- Runs at boot as a systemd service. Uses about 8 MB of RAM at 16 kHz.

## Why a Raspberry Pi and not an Arduino?

240 s of even phone-quality audio (8 kHz, 8-bit) is about 1.9 MB. A Uno has 2 KB of RAM and has no
decent audio in/out. An ESP32 with PSRAM plus an I2S codec could do it, but it would be a much bigger
firmware job. Any Pi (Zero 2 W, 3, 4 or 5) with a $10 USB sound card does it easily.

## Hardware

| Item | Notes |
|---|---|
| Raspberry Pi (Zero 2 W / 3 / 4 / 5) | Raspberry Pi OS Bookworm or later |
| USB audio adapter with mic/line in and headphone out | e.g. any CM108/C-Media dongle |
| 7–9 momentary push buttons | normally open |
| LED + 330 Ω resistor | optional |
| Speaker / powered speaker or headphones | on the USB adapter's output |

**Audio in:** receiver headphone/speaker/line out → (attenuator, see below) → USB adapter MIC/LINE in.
Many dongles have only a mic input, which is very sensitive. Turn the receiver volume low, or use a
simple 10:1 divider (10 kΩ series, 1 kΩ to ground). Then set the capture level with `alsamixer`
(F6 → USB device, F4 → capture) so loud signals don't clip.

### Button wiring (BCM numbering; each button goes between the GPIO pin and **GND**)

| Function | GPIO | Header pin |
|---|---|---|
| Replay 5 s   | 17 | 11 |
| Replay 10 s  | 27 | 13 |
| Replay 20 s  | 22 | 15 |
| Replay 30 s  | 5  | 29 |
| Replay 60 s  | 6  | 31 |
| Replay 120 s | 13 | 33 |
| Replay 240 s | 19 | 35 |
| STOP         | 26 | 37 |
| SAVE (opt.)  | 21 | 40 |
| LED + (opt.) | 12 | 32 → 330 Ω → LED → GND |
| GND          | —  | 9, 14, 20, 25, 30, 34, 39 |

The internal pull-up resistors are used, so no external resistors are needed. Pins, durations and
all other settings are in `config.ini`.

## Install

```bash
git clone <this repo> && cd ssgenterprises/radio-replay
./install.sh                # installs deps, creates .venv, enables + starts the service
journalctl -u radio-replay -f   # watch the log
```

Find your sound card name or index with:

```bash
.venv/bin/python -m radio_replay.main --list-devices
```

then set `input_device` / `output_device` in `config.ini` (the default `USB` matches most
dongles) and run `sudo systemctl restart radio-replay`.

## Testing without buttons

```bash
sudo systemctl stop radio-replay
.venv/bin/python -m radio_replay.main -c config.ini --keyboard
```

Type `1`–`7` + Enter to replay 5…240 s, `s` to stop, `w` to save a WAV, `q` to quit.

Unit tests (no hardware needed): `python3 -m pytest tests`.

## How it works

A single full-duplex audio stream feeds every input block into a 242-second circular buffer in RAM.
When a button is pressed, playback starts N seconds behind the live write position and reads straight
out of the same buffer. It advances at the same rate as recording, so the data it is playing can
never be overwritten, and nothing is copied. Recording continues during replay.

## Tips

- For wideband/AM broadcast or music, set `sample_rate = 48000` (≈23 MB RAM).
- To extend the times, edit `durations` and add one GPIO pin per entry to `replay_pins`.
- Saved clips go to `~/radio-replay-clips/`.
