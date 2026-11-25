package com.ssgenterprises.radioreplay

/**
 * Circular buffer of 16-bit mono samples, addressed by absolute sample index.
 * Pure Kotlin (no Android APIs) so it can be unit-tested on the JVM.
 */
class RingBuffer(val capacity: Int) {
    private val buf = ShortArray(capacity)

    /** Total samples ever written. */
    var total = 0L
        private set

    val available: Int get() = minOf(total, capacity.toLong()).toInt()

    fun write(src: ShortArray, offset: Int, count: Int) {
        var off = offset
        var n = count
        if (n > capacity) {               // block larger than buffer: keep the tail
            off += n - capacity
            total += (n - capacity).toLong()
            n = capacity
        }
        val start = (total % capacity).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(src, off, buf, start, first)
        if (first < n) System.arraycopy(src, off + first, buf, 0, n - first)
        total += n.toLong()
    }

    fun read(absStart: Long, dst: ShortArray, dstOffset: Int, count: Int) {
        val start = (absStart % capacity).toInt()
        val first = minOf(count, capacity - start)
        System.arraycopy(buf, start, dst, dstOffset, first)
        if (first < count) System.arraycopy(buf, 0, dst, dstOffset + first, count - first)
    }
}

/**
 * Continuous recorder with instant replay of the last N seconds.
 *
 * Capture thread calls [capture] with every block from the microphone/line input.
 * Playback thread calls [readPlayback] to pull replay audio.
 * UI calls [replay] / [stop].
 *
 * A replay starts N seconds behind "now" and is read straight out of the ring buffer.
 * It advances at the same rate as recording, so it stays N seconds behind and the data
 * it reads is never overwritten (the buffer has 2 s of headroom). Nothing is copied.
 */
class ReplayEngine(val sampleRate: Int, val maxSeconds: Int = 240) {
    private val ring = RingBuffer((maxSeconds + 2) * sampleRate)
    private val lock = Any()

    private var playPos = 0L
    private var playEnd = 0L

    @Volatile var playing = false
        private set
    @Volatile var requestedSeconds = 0
        private set
    /** Changes on every replay/stop so the player can flush stale audio immediately. */
    @Volatile var generation = 0
        private set
    /** If true, input is not recorded while replaying (avoids re-recording the phone speaker). */
    @Volatile var pauseRecordingDuringReplay = false
    /** Peak input level of the most recent block, 0..1. */
    @Volatile var inputLevel = 0f
        private set

    val bufferedSeconds: Float get() = synchronized(lock) { ring.available.toFloat() / sampleRate }

    val remainingSeconds: Float
        get() = synchronized(lock) { if (playing) (playEnd - playPos).toFloat() / sampleRate else 0f }

    fun capture(src: ShortArray, count: Int) {
        var peak = 0
        for (i in 0 until count) {
            val v = src[i].toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        inputLevel = peak / 32768f
        if (pauseRecordingDuringReplay && playing) return
        synchronized(lock) { ring.write(src, 0, count) }
    }

    /** Start replaying the last [seconds] seconds, ending at this moment. */
    fun replay(seconds: Int) = synchronized(lock) {
        val n = minOf(seconds.toLong() * sampleRate, ring.available.toLong())
        playEnd = ring.total
        playPos = playEnd - n
        playing = n > 0
        requestedSeconds = seconds
        generation++
    }

    fun stop() = synchronized(lock) {
        playing = false
        generation++
    }

    /**
     * Copy up to [count] replay samples into [dst].
     * Returns the number copied, 0 if nothing is playing, or -1 if [expectedGeneration]
     * is stale (a new replay/stop happened: caller should flush its output first).
     */
    fun readPlayback(dst: ShortArray, count: Int, expectedGeneration: Int): Int = synchronized(lock) {
        if (expectedGeneration != generation) return -1
        if (!playing) return 0
        val k = minOf(count.toLong(), playEnd - playPos).toInt()
        ring.read(playPos, dst, 0, k)
        playPos += k
        if (playPos >= playEnd) playing = false
        k
    }

    /** Copy of the most recent [seconds] seconds (oldest first). */
    fun snapshot(seconds: Int): ShortArray = synchronized(lock) {
        val n = minOf(seconds.toLong() * sampleRate, ring.available.toLong()).toInt()
        ShortArray(n).also { ring.read(ring.total - n, it, 0, n) }
    }
}
