# Parts List

Prices are approximate (USD) and change often. Where a link is a search, any equivalent part works.

## Required

| # | Qty | Part | Notes | Approx. | Link |
|---|---|---|---|---|---|
| 1 | 1 | **Raspberry Pi Zero 2 W** (or Pi 3 / 4 / 5) | Zero 2 W is ample. Buy the **"WH"** version with pre-soldered header if you don't want to solder | $15–20 | [raspberrypi.com](https://www.raspberrypi.com/products/raspberry-pi-zero-2-w/) |
| 2 | 1 | Power supply | Zero 2 W / Pi 3: 5 V 2.5 A micro-USB. Pi 4: USB-C 3 A. Pi 5: 27 W USB-C | $8–12 | [Micro-USB](https://www.raspberrypi.com/products/micro-usb-power-supply/) · [USB-C (Pi 4)](https://www.raspberrypi.com/products/type-c-power-supply/) · [27 W (Pi 5)](https://www.raspberrypi.com/products/27w-power-supply/) |
| 3 | 1 | microSD card, 16–32 GB, Class 10 / A1 | For Raspberry Pi OS (Bookworm or later) | $8 | [Search](https://www.amazon.com/s?k=sandisk+32gb+microsd+a1) |
| 4 | 1 | **USB audio adapter** with mic/line in + headphone out | Any C-Media CM108/CM119 dongle works | $5–10 | [Adafruit USB Audio Adapter](https://www.adafruit.com/product/1475) · [Search](https://www.amazon.com/s?k=usb+audio+adapter+mic+headphone+raspberry+pi) |
| 5 | 1 | Micro-USB OTG adapter (male micro-B → female USB-A) | **Zero 2 W only**: needed to plug in the USB sound card | $3–5 | [Search](https://www.amazon.com/s?k=micro+usb+otg+adapter) |
| 6 | 8–9 | Momentary push buttons, normally open | 7 replay + STOP (+ SAVE). Panel-mount 12–16 mm for a box, or tactile switches for a breadboard | $5–10 | [Adafruit tactile 10-pack](https://www.adafruit.com/product/1009) · [Panel-mount search](https://www.amazon.com/s?k=12mm+momentary+push+button+panel+mount) |
| 7 | 1 | 3.5 mm stereo audio cable (male–male) | Receiver headphone/line out → adapter input | $4 | [Search](https://www.amazon.com/s?k=3.5mm+male+to+male+audio+cable) |
| 8 | 1 | Powered speaker or headphones | Plugs into the adapter's headphone out | $10–20 | [Search](https://www.amazon.com/s?k=usb+powered+computer+speaker+3.5mm) |
| 9 | 1 set | Female-to-female jumper wires (Dupont) or hookup wire | Buttons → GPIO header | $5 | [Search](https://www.amazon.com/s?k=dupont+jumper+wires+female+female) |

## Recommended / optional

| # | Qty | Part | Notes | Approx. | Link |
|---|---|---|---|---|---|
| 10 | 1 | **Ground-loop isolator**, 3.5 mm | Removes hum/buzz when the radio and Pi share grounds. Strongly recommended | $8–12 | [Search](https://www.amazon.com/s?k=3.5mm+ground+loop+noise+isolator) |
| 11 | 1 | 10 kΩ + 1 kΩ resistors | 10:1 attenuator if the adapter only has a sensitive mic input (see README) | $1 | [Resistor kit search](https://www.amazon.com/s?k=1%2F4w+resistor+assortment+kit) |
| 12 | 1 | 5 mm LED + 330 Ω resistor | "Replaying" indicator on GPIO 12 | $1 | [Adafruit LED search](https://www.adafruit.com/search?q=5mm+LED) |
| 13 | 1 | Project enclosure, ~150 × 100 × 50 mm | Holds Pi + buttons on the front panel | $8–15 | [Search](https://www.amazon.com/s?k=abs+project+box+150x100x50) |
| 14 | 1 | GPIO header 2×20, 2.54 mm | Only if you bought a Zero 2 W without header | $1 | [Search](https://www.amazon.com/s?k=2x20+pin+header+2.54mm) |
| 15 | 1 | Ferrite clip-on chokes | Keep Pi RF noise out of the receiver (put on the USB and power cables) | $8 | [Search](https://www.amazon.com/s?k=clip+on+ferrite+core+5mm) |

**Typical total:** about **$60–80** for a Zero 2 W build with a speaker and enclosure.

## RF-quiet tips for the shack

- The Pi and its power supply can cause receiver birdies. A linear or well-filtered 5 V supply,
  ferrites on every cable, and a metal enclosure help.
- On a Pi 3 / 4 / 5 you can also turn off Wi-Fi/Bluetooth after setup
  (`dtoverlay=disable-wifi`, `dtoverlay=disable-bt` in `/boot/firmware/config.txt`).
