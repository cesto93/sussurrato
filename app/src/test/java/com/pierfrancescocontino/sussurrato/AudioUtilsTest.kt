package com.pierfrancescocontino.sussurrato

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioUtilsTest {

    private lateinit var wavBytes: ByteArray
    private lateinit var pcmBytes: ByteArray
    private var sourceSampleRate = 0
    private var sourceChannels = 0

    @Before
    fun setUp() {
        val resource = javaClass.classLoader?.getResourceAsStream("output.wav")
            ?: throw IllegalStateException("output.wav not found in test resources")
        wavBytes = resource.readBytes()
        resource.close()

        val wavData = AudioUtils.extractPcmFromWav(wavBytes)
        pcmBytes = wavData.pcm
        sourceSampleRate = wavData.sampleRate
        sourceChannels = wavData.channels
    }

    @Test
    fun pcmToWav_createsValidWavHeader() {
        val wav = AudioUtils.pcmToWav(pcmBytes, sourceSampleRate)
        val data = AudioUtils.extractPcmFromWav(wav)

        assertEquals(sourceSampleRate, data.sampleRate)
        assertEquals(1, data.channels)
        assertEquals(16, data.bitsPerSample)
        assertArrayEquals(pcmBytes, data.pcm)
    }

    @Test
    fun pcmToWav_handlesEmptyPcm() {
        val wav = AudioUtils.pcmToWav(ByteArray(0), 16000)
        val data = AudioUtils.extractPcmFromWav(wav)

        assertEquals(16000, data.sampleRate)
        assertEquals(1, data.channels)
        assertEquals(0, data.pcm.size)
    }

    @Test
    fun pcmToWav_matchesActualFileHeader() {
        val wav = AudioUtils.pcmToWav(pcmBytes, sourceSampleRate)
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        val riff = ByteArray(4)
        buf.get(riff)
        assertEquals("RIFF", String(riff))
        val size = buf.getInt()
        val wave = ByteArray(4)
        buf.get(wave)
        assertEquals("WAVE", String(wave))
        assertEquals(wav.size - 8, size)
    }

    @Test
    fun extractPcmFromWav_parsesActualFile() {
        assertEquals(48000, sourceSampleRate)
        assertEquals(1, sourceChannels)
        assertTrue(pcmBytes.size > 0)
    }

    @Test
    fun convertToMono_averagesStereo() {
        val shorts = shortArrayOf(100, 200, 300, 400, -100, -200)
        val stereo = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(stereo).order(ByteOrder.nativeOrder()).asShortBuffer().put(shorts)

        val mono = AudioUtils.convertToMono(stereo, 2)
        val monoShorts = ShortArray(mono.size / 2)
        ByteBuffer.wrap(mono).order(ByteOrder.nativeOrder()).asShortBuffer().get(monoShorts)

        assertEquals(3, monoShorts.size)
        assertEquals(((100 + 200) / 2).toShort(), monoShorts[0])
        assertEquals(((300 + 400) / 2).toShort(), monoShorts[1])
        assertEquals(((-100 + -200) / 2).toShort(), monoShorts[2])
    }

    @Test
    fun convertToMono_withMonoInput_returnsSame() {
        val result = AudioUtils.convertToMono(pcmBytes, 1)
        assertArrayEquals(pcmBytes, result)
    }

    @Test
    fun resamplePcm_downsample() {
        val samples = 1000
        val shorts = ShortArray(samples) { (it * 32767 / samples - 16384).toShort() }
        val pcm = ByteArray(shorts.size * 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asShortBuffer().put(shorts)

        val resampled = AudioUtils.resamplePcm(pcm, 48000, 16000)

        val expectedOutputSamples = (samples * 16000.0 / 48000.0).toInt()
        assertEquals(expectedOutputSamples, resampled.size / 2)

        val outShorts = ShortArray(resampled.size / 2)
        ByteBuffer.wrap(resampled).order(ByteOrder.nativeOrder()).asShortBuffer().get(outShorts)

        assertEquals(shorts[0], outShorts[0])
        val lastSrcIdx = ((expectedOutputSamples - 1) / (16000.0 / 48000.0)).toInt()
        assertEquals(shorts[lastSrcIdx], outShorts[expectedOutputSamples - 1])
    }

    @Test
    fun resamplePcm_sameRate_returnsOriginal() {
        val result = AudioUtils.resamplePcm(pcmBytes, 48000, 48000)
        assertArrayEquals(pcmBytes, result)
    }

    @Test
    fun fullPipeline_roundTrip() {
        val wav = AudioUtils.pcmToWav(pcmBytes, sourceSampleRate)
        val data = AudioUtils.extractPcmFromWav(wav)

        assertArrayEquals(pcmBytes, data.pcm)
        assertEquals(sourceSampleRate, data.sampleRate)
        assertEquals(1, data.channels)
        assertEquals(16, data.bitsPerSample)
    }
}
