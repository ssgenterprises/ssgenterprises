import os
import tempfile
import wave

import numpy as np

from radio_replay.engine import ReplayEngine, RingBuffer

RATE = 100  # tiny sample rate keeps tests fast; logic is rate-independent
BLOCK = 10


def feed(engine, start, nblocks):
    """Feed a ramp signal (sample value == absolute frame index) and collect output."""
    outs = []
    for i in range(nblocks):
        x = (np.arange(start + i * BLOCK, start + (i + 1) * BLOCK) % 30000).astype(np.int16)
        indata = x.reshape(-1, 1)
        out = np.empty_like(indata)
        engine.process(indata, out)
        outs.append(out[:, 0].copy())
    return np.concatenate(outs)


def test_ring_wraps():
    r = RingBuffer(7)
    r.write(np.arange(5, dtype=np.int16).reshape(-1, 1))
    r.write(np.arange(5, 10, dtype=np.int16).reshape(-1, 1))
    assert r.available == 7
    assert r.snapshot(7)[:, 0].tolist() == [3, 4, 5, 6, 7, 8, 9]
    assert r.snapshot(3)[:, 0].tolist() == [7, 8, 9]


def test_replay_plays_exact_last_n_seconds_then_silence():
    e = ReplayEngine(sample_rate=RATE, max_seconds=240)
    feed(e, 0, 100)                      # 10 s recorded, frames 0..999
    e.replay(5)
    out = feed(e, 1000, 60)              # 6 s more, while replaying
    assert out[:500].tolist() == list(range(500, 1000))
    assert not out[500:].any()
    assert not e.status().playing


def test_replay_longest_after_buffer_wraps():
    e = ReplayEngine(sample_rate=RATE, max_seconds=240)
    total = 300 * RATE                   # more than the buffer holds
    feed(e, 0, total // BLOCK)
    e.replay(240)
    out = feed(e, total, 240 * RATE // BLOCK)
    expected = (np.arange(total - 240 * RATE, total) % 30000).astype(np.int16)
    assert np.array_equal(out, expected)


def test_replay_clamped_to_available_and_stop():
    e = ReplayEngine(sample_rate=RATE, max_seconds=240)
    feed(e, 0, 30)                       # 3 s
    e.replay(60)
    out = feed(e, 300, 5)
    assert out.tolist() == list(range(0, 50))
    assert e.status().playing
    e.stop()
    out = feed(e, 350, 2)
    assert not out.any() and not e.status().playing


def test_new_press_restarts_from_now():
    e = ReplayEngine(sample_rate=RATE, max_seconds=240)
    feed(e, 0, 100)
    e.replay(10)
    feed(e, 1000, 10)
    e.replay(5)                          # now = frame 1100
    out = feed(e, 1100, 5)
    assert out.tolist() == list(range(600, 650))


def test_monitor_passthrough():
    e = ReplayEngine(sample_rate=RATE, monitor=True)
    out = feed(e, 0, 3)
    assert out.tolist() == list(range(30))


def test_save_wav():
    e = ReplayEngine(sample_rate=RATE, max_seconds=240)
    feed(e, 0, 50)
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "x.wav")
        assert e.save_wav(p, 2) == 2.0
        with wave.open(p) as w:
            data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
        assert data.tolist() == list(range(300, 500))
