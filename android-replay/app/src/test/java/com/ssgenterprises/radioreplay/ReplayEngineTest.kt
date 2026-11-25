package com.ssgenterprises.radioreplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ReplayEngineTest {
    private val rate = 100   // tiny rate keeps tests fast; logic is rate-independent
    private val block = 10

    /** Feed a ramp (sample value == absolute index % 30000). */
    private fun feed(e: ReplayEngine, start: Int, blocks: Int) {
        for (b in 0 until blocks) {
            val x = ShortArray(block) { ((start + b * block + it) % 30000).toShort() }
            e.capture(x, block)
        }
    }

    private fun drain(e: ReplayEngine, max: Int): List<Int> {
        val out = ArrayList<Int>()
        val buf = ShortArray(block)
        while (out.size < max) {
            val k = e.readPlayback(buf, block, e.generation)
            if (k <= 0) break
            for (i in 0 until k) out.add(buf[i].toInt())
        }
        return out
    }

    @Test fun ringWraps() {
        val r = RingBuffer(7)
        r.write(ShortArray(5) { it.toShort() }, 0, 5)
        r.write(ShortArray(5) { (it + 5).toShort() }, 0, 5)
        assertEquals(7, r.available)
        val d = ShortArray(7)
        r.read(r.total - 7, d, 0, 7)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7, 8, 9), d)
    }

    @Test fun replaysExactlyLastNSeconds() {
        val e = ReplayEngine(rate)
        feed(e, 0, 100)                       // 10 s
        e.replay(5)
        assertTrue(e.playing)
        assertEquals((500 until 1000).toList(), drain(e, 10_000))
        assertFalse(e.playing)
    }

    @Test fun replayWhileStillRecording() {
        val e = ReplayEngine(rate)
        feed(e, 0, 100)
        e.replay(5)
        val got = ArrayList<Int>()
        val buf = ShortArray(block)
        var t = 1000
        while (true) {                         // interleave capture and playback
            feed(e, t, 1); t += block
            val k = e.readPlayback(buf, block, e.generation)
            if (k <= 0) break
            for (i in 0 until k) got.add(buf[i].toInt())
        }
        assertEquals((500 until 1000).toList(), got)
    }

    @Test fun longestReplayAfterWrap() {
        val e = ReplayEngine(rate, 240)
        val total = 300 * rate
        feed(e, 0, total / block)
        e.replay(240)
        assertEquals(((total - 240 * rate) until total).map { it % 30000 }, drain(e, 100_000))
    }

    @Test fun clampsToAvailableAndStops() {
        val e = ReplayEngine(rate)
        feed(e, 0, 30)                         // 3 s
        e.replay(60)
        assertEquals((0 until 50).toList(), drain(e, 50))
        e.stop()
        assertFalse(e.playing)
        assertEquals(0, e.readPlayback(ShortArray(block), block, e.generation))
    }

    @Test fun staleGenerationSignalsFlush() {
        val e = ReplayEngine(rate)
        feed(e, 0, 100)
        val g = e.generation
        e.replay(5)
        assertEquals(-1, e.readPlayback(ShortArray(block), block, g))
    }

    @Test fun pauseRecordingDuringReplay() {
        val e = ReplayEngine(rate)
        e.pauseRecordingDuringReplay = true
        feed(e, 0, 100)
        e.replay(5)
        feed(e, 1000, 10)                      // ignored while playing
        assertEquals(10f, e.bufferedSeconds, 0.001f)
    }

    @Test fun wavHeader() {
        val out = ByteArrayOutputStream()
        WavWriter.write(out, shortArrayOf(1, -1), 16000)
        val b = out.toByteArray()
        assertEquals(48, b.size)
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals("data", String(b, 36, 4))
    }
}
