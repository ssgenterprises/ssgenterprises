package com.ssgenterprises.radioreplay

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    /** Write 16-bit mono PCM [samples] as a WAV file. */
    fun write(out: OutputStream, samples: ShortArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        out.write(header.array())
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(samples)
        out.write(body.array())
        out.flush()
    }
}
