"""Core audio engine: continuous ring-buffer recording + instant replay.

This module has no hardware dependencies so it can be unit-tested anywhere.
The audio callback (`process`) is called by the sound card driver for every
block of samples: it always records the incoming block, and if a replay is
active it outputs audio read straight out of the ring buffer (zero-copy).

Why zero-copy works: a replay of the last N seconds starts N seconds behind
the live write position and advances at exactly the same rate, so it always
stays N seconds behind.  As long as the buffer holds a bit more than the
longest replay, the samples being played are never overwritten.
"""

from __future__ import annotations

import threading
import wave
from dataclasses import dataclass

import numpy as np


class RingBuffer:
    """Fixed-size circular buffer of int16 samples addressed by absolute frame index."""

    def __init__(self, capacity_frames: int, channels: int = 1):
        self.capacity = int(capacity_frames)
        self.channels = channels
        self.buf = np.zeros((self.capacity, channels), dtype=np.int16)
        self.total = 0  # absolute number of frames ever written

    def write(self, data: np.ndarray) -> None:
        n = len(data)
        if n == 0:
            return
        if n >= self.capacity:  # pathological: block bigger than buffer
            data = data[-self.capacity:]
            self.total += n - self.capacity
            n = self.capacity
        start = self.total % self.capacity
        first = min(n, self.capacity - start)
        self.buf[start:start + first] = data[:first]
        if first < n:
            self.buf[:n - first] = data[first:]
        self.total += n

    @property
    def available(self) -> int:
        """Frames currently held (grows until the buffer is full)."""
        return min(self.total, self.capacity)

    def read(self, abs_start: int, n: int, out: np.ndarray | None = None) -> np.ndarray:
        """Read n frames starting at absolute frame index abs_start."""
        if out is None:
            out = np.empty((n, self.channels), dtype=np.int16)
        start = abs_start % self.capacity
        first = min(n, self.capacity - start)
        out[:first] = self.buf[start:start + first]
        if first < n:
            out[first:n] = self.buf[:n - first]
        return out

    def snapshot(self, n: int) -> np.ndarray:
        """Copy of the most recent n frames (oldest first)."""
        n = min(n, self.available)
        return self.read(self.total - n, n)


@dataclass
class Status:
    playing: bool
    seconds_requested: float
    seconds_remaining: float
    seconds_buffered: float


class ReplayEngine:
    def __init__(
        self,
        sample_rate: int = 16000,
        channels: int = 1,
        max_seconds: int = 240,
        playback_gain: float = 1.0,
        monitor: bool = False,
        monitor_gain: float = 1.0,
    ):
        self.sample_rate = sample_rate
        self.channels = channels
        self.max_seconds = max_seconds
        # 2 s of headroom so the oldest replayed samples can't be overwritten
        self.ring = RingBuffer((max_seconds + 2) * sample_rate, channels)
        self.playback_gain = playback_gain
        self.monitor = monitor
        self.monitor_gain = monitor_gain

        # Playback state – only touched inside process() except for _request.
        self._play_pos = 0
        self._play_end = 0
        self._playing = False
        self._requested_seconds = 0.0
        self._request: float | None = None  # set by UI thread, consumed by callback
        self._stop_request = False
        self._lock = threading.Lock()  # protects ring during snapshot()

    # ---- UI-thread API -------------------------------------------------
    def replay(self, seconds: float) -> None:
        """Request playback of the last `seconds` seconds (takes effect next block)."""
        self._request = float(seconds)

    def stop(self) -> None:
        self._stop_request = True

    def status(self) -> Status:
        rate = self.sample_rate
        remaining = max(0, self._play_end - self._play_pos) / rate if self._playing else 0.0
        return Status(self._playing, self._requested_seconds, remaining, self.ring.available / rate)

    def save_wav(self, path: str, seconds: float | None = None) -> float:
        """Write the last `seconds` (default: whole buffer) to a WAV file. Returns seconds saved."""
        n = int((seconds or self.max_seconds) * self.sample_rate)
        with self._lock:
            data = self.ring.snapshot(n)
        with wave.open(path, "wb") as w:
            w.setnchannels(self.channels)
            w.setsampwidth(2)
            w.setframerate(self.sample_rate)
            w.writeframes(data.tobytes())
        return len(data) / self.sample_rate

    # ---- audio-thread API ----------------------------------------------
    def process(self, indata: np.ndarray, outdata: np.ndarray) -> None:
        """Audio callback body. indata/outdata are int16 arrays of shape (frames, channels)."""
        frames = len(indata)
        with self._lock:
            self.ring.write(indata)

        if self._stop_request:
            self._stop_request = False
            self._request = None
            self._set_playing(False)

        req = self._request
        if req is not None:
            self._request = None
            n = int(min(req, self.max_seconds) * self.sample_rate)
            # Replay ends at "now" (the moment the button was pressed).
            n = min(n, self.ring.available - frames) if self.ring.available > frames else 0
            end = self.ring.total - frames  # exclude the block just captured
            self._play_pos = end - n
            self._play_end = end
            self._requested_seconds = req
            self._set_playing(n > 0)

        if self._playing:
            n = min(frames, self._play_end - self._play_pos)
            self.ring.read(self._play_pos, n, outdata)
            if self.playback_gain != 1.0:
                np.copyto(outdata[:n], np.clip(outdata[:n] * self.playback_gain, -32768, 32767),
                          casting="unsafe")
            outdata[n:] = 0
            self._play_pos += n
            if self._play_pos >= self._play_end:
                self._set_playing(False)
        elif self.monitor:
            if self.monitor_gain == 1.0:
                outdata[:] = indata
            else:
                np.copyto(outdata, np.clip(indata * self.monitor_gain, -32768, 32767),
                          casting="unsafe")
        else:
            outdata.fill(0)

    def _set_playing(self, value: bool) -> None:
        self._playing = value
