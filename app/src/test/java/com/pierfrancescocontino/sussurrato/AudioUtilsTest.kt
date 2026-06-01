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
        assertEquals(32, data.bitsPerSample)
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
        val inputFloats = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, -0.2f, -0.4f)
        val stereo = ByteArray(inputFloats.size * 4)
        ByteBuffer.wrap(stereo).order(ByteOrder.nativeOrder()).asFloatBuffer().put(inputFloats)

        val mono = AudioUtils.convertToMono(stereo, 2)
        val monoFloats = FloatArray(mono.size / 4)
        ByteBuffer.wrap(mono).order(ByteOrder.nativeOrder()).asFloatBuffer().get(monoFloats)

        assertEquals(3, monoFloats.size)
        assertEquals(0.2f, monoFloats[0])
        assertEquals(0.6f, monoFloats[1])
        assertEquals(-0.3f, monoFloats[2])
    }

    @Test
    fun convertToMono_withMonoInput_returnsSame() {
        val pcmFloat = AudioUtils.convert16BitPcmToFloat32(pcmBytes)
        val result = AudioUtils.convertToMono(pcmFloat, 1)
        assertArrayEquals(pcmFloat, result)
    }

    @Test
    fun resamplePcm_downsample() {
        val samples = 1000
        val inputFloats = FloatArray(samples) { it / samples.toFloat() }
        val pcm = ByteArray(inputFloats.size * 4)
        ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asFloatBuffer().put(inputFloats)

        val resampled = AudioUtils.resamplePcm(pcm, 48000, 16000)

        val expectedSamples = (samples * 16000.0 / 48000.0).toInt()
        assertEquals(expectedSamples, resampled.size / 4)

        val outFloats = FloatArray(resampled.size / 4)
        ByteBuffer.wrap(resampled).order(ByteOrder.nativeOrder()).asFloatBuffer().get(outFloats)

        assertEquals(inputFloats[0], outFloats[0])
        val lastSrcIdx = ((expectedSamples - 1) / (16000.0 / 48000.0)).toInt()
        assertEquals(inputFloats[lastSrcIdx], outFloats[expectedSamples - 1])
    }

    @Test
    fun resamplePcm_sameRate_returnsOriginal() {
        val pcmFloat = AudioUtils.convert16BitPcmToFloat32(pcmBytes)
        val result = AudioUtils.resamplePcm(pcmFloat, 48000, 48000)
        assertArrayEquals(pcmFloat, result)
    }

    @Test
    fun fullPipeline_roundTrip() {
        val wav = AudioUtils.pcmToWav(pcmBytes, sourceSampleRate)
        val data = AudioUtils.extractPcmFromWav(wav)

        assertArrayEquals(pcmBytes, data.pcm)
        assertEquals(sourceSampleRate, data.sampleRate)
        assertEquals(1, data.channels)
        assertEquals(32, data.bitsPerSample)
    }
}
