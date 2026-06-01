package com.pierfrancescocontino.sussurrato

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AudioUtils {

    fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val fileSize = 44 + dataSize

        val buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(fileSize - 8)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        buf.put(pcm)
        return buf.array()
    }

    fun convertToMono(multiChannelPcm: ByteArray, channels: Int): ByteArray {
        val shorts = ShortArray(multiChannelPcm.size / 2)
        ByteBuffer.wrap(multiChannelPcm).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)

        val monoSamples = shorts.size / channels
        val monoShorts = ShortArray(monoSamples)
        for (i in 0 until monoSamples) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += shorts[i * channels + ch]
            }
            monoShorts[i] = (sum / channels).toShort()
        }

        val monoBytes = ByteArray(monoShorts.size * 2)
        ByteBuffer.wrap(monoBytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(monoShorts)
        return monoBytes
    }

    fun resamplePcm(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) return pcm

        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)

        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val outputLength = (shorts.size * ratio).toInt()
        val output = ShortArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIndex = (i / ratio).toInt()
            val clamped = srcIndex.coerceIn(0, shorts.size - 1)
            output[i] = shorts[clamped]
        }

        val outBytes = ByteArray(output.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(output)
        return outBytes
    }

    fun extractPcmFromWav(wavBytes: ByteArray): WavData {
        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4)
        buf.get(riff)
        buf.getInt() // file size
        val wave = ByteArray(4)
        buf.get(wave)

        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var pcmData: ByteArray? = null

        while (buf.remaining() >= 8) {
            val chunkId = ByteArray(4)
            buf.get(chunkId)
            val chunkSize = buf.getInt()
            val id = String(chunkId)

            when (id) {
                "fmt " -> {
                    buf.getShort() // audio format
                    channels = buf.getShort().toInt()
                    sampleRate = buf.getInt()
                    buf.getInt() // byte rate
                    buf.getShort() // block align
                    bitsPerSample = buf.getShort().toInt()
                    val fmtExtra = chunkSize - 16
                    if (fmtExtra > 0) buf.position(buf.position() + fmtExtra)
                }
                "data" -> {
                    pcmData = ByteArray(chunkSize)
                    buf.get(pcmData)
                }
                else -> {
                    buf.position(buf.position() + chunkSize)
                }
            }

            if (chunkSize % 2 != 0 && buf.hasRemaining()) {
                buf.position(buf.position() + 1)
            }
        }

        val data = pcmData ?: throw IllegalArgumentException("No data chunk found in WAV")
        return WavData(data, sampleRate, channels, bitsPerSample)
    }

    data class WavData(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )
}
