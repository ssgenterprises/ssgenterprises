# Radio Replay for Android

The Android version of [Radio Replay](../radio-replay/README.md). It records your receiver all the
time and replays the **last 5, 10, 20, 30 s or 1, 2, 4 min** with one tap.

- Keeps recording with the screen off or while you use other apps (foreground service with a
  notification).
- The notification has **Last 10 s**, **Last 30 s** and **Stop** buttons, so you don't need to open the app.
- A replay ends at the moment you tapped. Tapping another length during a replay starts over from that moment.
- **SAVE** writes the last 240 s to `Music/RadioReplay/replay_YYYYMMDD_HHMMSS.wav`.
- Input level meter to help set the receiver volume.
- Android 8.0 (API 26) or later. No internet access, no ads, no extra libraries.

## Getting the audio in

| Setup | How | Notes |
|---|---|---|
| **Phone mic near the radio's speaker** | Nothing to connect | Easiest. Use earbuds for replay, or tick *Don't record while replaying* so the phone doesn't re-record its own speaker |
| **Wired, USB-C phones** | Receiver line/headphone out → **USB-C audio adapter with mic input** (or a USB sound dongle + USB-C OTG adapter) | Cleanest audio. Android records from the adapter automatically when it's plugged in |
| **Wired, 3.5 mm jack phones** | Receiver out → attenuator → **TRRS** plug (mic on the sleeve ring) | Needs a TRRS splitter or cable. Keep the level low because the mic input is very sensitive |

Add a ground-loop isolator if you hear hum when wired to the radio.

## Install

**Easiest: download the built APK**
1. On GitHub open **Actions → Android APK →** the latest run → **Artifacts → radio-replay-apk**.
2. Unzip it and copy `app-debug.apk` to the phone (or download it on the phone directly).
3. Open it and allow *Install unknown apps* for your browser or file manager when asked.
4. Open **Radio Replay** and allow the microphone and notifications.

**Or build it yourself**: open the `android-replay` folder in Android Studio and press Run. Or use
`./gradlew assembleDebug` with the Android SDK installed.

## Battery and reliability tips

- Recording uses little power, but some phones kill background apps. If recording stops on its own,
  set the app's battery use to **Unrestricted** (Settings → Apps → Radio Replay → Battery).
- The app uses Android's *voice recognition* input. It turns off most automatic gain control and
  noise suppression, which otherwise distort radio audio.

## Code

- `ReplayEngine.kt`: ring buffer and replay logic, with no Android dependencies. Unit tests are in `app/src/test`.
- `ReplayService.kt`: the foreground service, with the recording thread (AudioRecord) and playback thread (AudioTrack).
- `MainActivity.kt`: the screen.
