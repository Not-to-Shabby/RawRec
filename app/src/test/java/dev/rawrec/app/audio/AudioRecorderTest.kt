package dev.rawrec.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorderTest {

    @Test
    fun `computeStereoPeakDb calculates accurate dBFS for 16-bit PCM`() {
        val samples = 1000
        val pcm = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN)

        // Half scale: peak 16384 -> approx -6 dBFS
        for (i in 0 until samples) {
            pcm.putShort(16384.toShort()) // Left
            pcm.putShort(8192.toShort())  // Right (quarter scale -> -12 dBFS)
        }

        val peaks = AudioRecorder.computeStereoPeakDb(pcm.array(), isFloat = false)
        assertEquals(-6.0f, peaks[0], 0.5f)
        assertEquals(-12.0f, peaks[1], 0.5f)
    }

    @Test
    fun `computeStereoPeakDb calculates accurate dBFS for 32-bit float audio`() {
        val samples = 1000
        val pcm = ByteBuffer.allocate(samples * 8).order(ByteOrder.LITTLE_ENDIAN)

        // Left = 0.5f (-6 dBFS), Right = 1.0f (0 dBFS)
        for (i in 0 until samples) {
            pcm.putFloat(0.5f)
            pcm.putFloat(1.0f)
        }

        val peaks = AudioRecorder.computeStereoPeakDb(pcm.array(), isFloat = true)
        assertEquals(-6.02f, peaks[0], 0.1f)
        assertEquals(0.0f, peaks[1], 0.1f)
    }

    @Test
    fun `computeStereoPeakDb returns -60dBFS for silence`() {
        val pcm = ByteArray(1024)
        val peaks16 = AudioRecorder.computeStereoPeakDb(pcm, isFloat = false)
        assertEquals(-60.0f, peaks16[0], 0.1f)
        assertEquals(-60.0f, peaks16[1], 0.1f)

        val peaksFloat = AudioRecorder.computeStereoPeakDb(pcm, isFloat = true)
        assertEquals(-60.0f, peaksFloat[0], 0.1f)
        assertEquals(-60.0f, peaksFloat[1], 0.1f)
    }
}
