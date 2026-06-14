// SPDX-FileCopyrightText: 2025 Pierfrancesco Contino
// SPDX-License-Identifier: GPL-3.0-only

package com.pierfrancescocontino.sussurrato

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AudioUtils {

    fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 32
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
        buf.putShort(3) // IEEE float
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

    fun convert16BitPcmToFloat32(pcm16: ByteArray): ByteArray {
        val shorts = ShortArray(pcm16.size / 2)
        ByteBuffer.wrap(pcm16).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)

        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) {
            floats[i] = shorts[i].toFloat() / 32768f
        }

        val outBytes = ByteArray(floats.size * 4)
        ByteBuffer.wrap(outBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floats)
        return outBytes
    }

    fun convertToMono(multiChannelPcm: ByteArray, channels: Int): ByteArray {
        val floats = FloatArray(multiChannelPcm.size / 4)
        ByteBuffer.wrap(multiChannelPcm).order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)

        val monoSamples = floats.size / channels
        val monoFloats = FloatArray(monoSamples)
        for (i in 0 until monoSamples) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += floats[i * channels + ch]
            }
            monoFloats[i] = sum / channels
        }

        val monoBytes = ByteArray(monoFloats.size * 4)
        ByteBuffer.wrap(monoBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().put(monoFloats)
        return monoBytes
    }

    fun resamplePcm(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
        if (sourceRate == targetRate) return pcm

        val floats = FloatArray(pcm.size / 4)
        ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)

        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        val outputLength = (floats.size * ratio).toInt()
        val output = FloatArray(outputLength)

        for (i in 0 until outputLength) {
            val srcIndex = (i / ratio).toInt()
            val clamped = srcIndex.coerceIn(0, floats.size - 1)
            output[i] = floats[clamped]
        }

        val outBytes = ByteArray(output.size * 4)
        ByteBuffer.wrap(outBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().put(output)
        return outBytes
    }

    fun byteArrayToFloatArray(pcm: ByteArray): FloatArray {
        val floats = FloatArray(pcm.size / 4)
        ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)
        return floats
    }

    fun extractPcmFromWav(wavBytes: ByteArray): WavData {
        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4)
        buf.get(riff)
        buf.getInt()
        val wave = ByteArray(4)
        buf.get(wave)

        var audioFormat: Short = 1
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
                    audioFormat = buf.getShort()
                    channels = buf.getShort().toInt()
                    sampleRate = buf.getInt()
                    buf.getInt()
                    buf.getShort()
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
        return WavData(data, sampleRate, channels, bitsPerSample, audioFormat)
    }

    data class WavData(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val audioFormat: Short = 1,
    )
}
