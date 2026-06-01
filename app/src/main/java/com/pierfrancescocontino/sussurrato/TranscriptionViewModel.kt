package com.pierfrancescocontino.sussurrato

import android.app.DownloadManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DownloadableModel(
    val id: String,
    val displayName: String,
    val sizeLabel: String,
    val url: String,
    val filename: String,
)

data class TranscriptionUiState(
    val isModelLoaded: Boolean = false,
    val isModelLoading: Boolean = false,
    val isTranscribing: Boolean = false,
    val transcription: String? = null,
    val error: String? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadError: String? = null,
    val isCheckingModel: Boolean = false,
    val downloadingModelId: String? = null,
)

class TranscriptionViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    private var engine: Engine? = null
    private var downloadJob: Job? = null
    private var modelDownloadManager: ModelDownloadManager? = null
    private var currentDownloadId: Long = -1L

    companion object {
        val MODELS = listOf(
            DownloadableModel(
                id = "gemma-4-E4B",
                displayName = "Gemma 4 E4B",
                sizeLabel = "3.7 GB",
                url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                filename = "gemma-4-E4B-it.litertlm",
            ),
            DownloadableModel(
                id = "gemma-4-E2B",
                displayName = "Gemma 4 E2B",
                sizeLabel = "2.6 GB",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                filename = "gemma-4-E2B-it.litertlm",
            ),
        )
    }

    fun loadModel(context: Context) {
        if (_uiState.value.isModelLoaded || _uiState.value.isModelLoading) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isModelLoading = true, error = null)

            try {
                val modelFile = findModelFile(context)
                if (modelFile == null) {
                    _uiState.value = _uiState.value.copy(
                        isModelLoading = false,
                        error = "No model found. Download a model from Hugging Face to get started.",
                        isCheckingModel = true,
                    )
                    return@launch
                }

                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    audioBackend = Backend.CPU(),
                )
                engine = Engine(config).also { it.initialize() }
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,
                    isModelLoading = false,
                    isCheckingModel = false,
                    error = null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = false,
                    isModelLoading = false,
                    error = "Failed to load model: ${e.message}"
                )
            }
        }
    }

    fun transcribe(context: Context, audioUri: Uri) {
        val engine = engine
        if (engine == null) {
            _uiState.value = _uiState.value.copy(error = "Model not loaded")
            return
        }
        if (_uiState.value.isTranscribing) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isTranscribing = true, error = null, transcription = null)

            try {
                val conversation = engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of("You are a transcription assistant. Transcribe the audio speech accurately. Output only the transcribed text.")
                    )
                )

                conversation.use { conv ->
                    val pcmBytes = decodeAudioToPcm(context, audioUri)
                    val wavBytes = pcmToWav(pcmBytes, 16000)
                    val response = conv.sendMessage(
                        Contents.of(
                            Content.AudioBytes(wavBytes),
                            Content.Text("Transcribe this audio."),
                        )
                    )
                    _uiState.value = _uiState.value.copy(
                        transcription = response.toString(),
                        isTranscribing = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTranscribing = false,
                    error = "Transcription failed: ${e.message}"
                )
            }
        }
    }

    fun downloadModel(context: Context, modelId: String) {
        if (_uiState.value.isDownloading) return
        val model = MODELS.find { it.id == modelId } ?: return

        val downloadManager = ModelDownloadManager(context)
        modelDownloadManager = downloadManager
        val downloadId = downloadManager.enqueueDownload(model)
        currentDownloadId = downloadId

        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            downloadProgress = 0f,
            downloadError = null,
            error = null,
            downloadingModelId = modelId,
        )

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            var lastProgress = 0

            while (true) {
                val info = downloadManager.queryProgress(downloadId)
                if (info == null) break

                when (info.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val modelFile = File(context.filesDir, model.filename)
                        val externalFile = context.getExternalFilesDir(null)
                            ?.let { File(it, model.filename) }
                        if (externalFile?.exists() == true) {
                            if (!externalFile.renameTo(modelFile)) {
                                externalFile.copyTo(modelFile, overwrite = true)
                                externalFile.delete()
                            }
                        }

                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            downloadProgress = 1f,
                            downloadingModelId = null,
                        )
                        loadModel(context)
                        break
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val errorMsg = when (info.reason) {
                            DownloadManager.ERROR_INSUFFICIENT_SPACE ->
                                "Insufficient storage space"
                            DownloadManager.ERROR_CANNOT_RESUME ->
                                "Download cannot be resumed"
                            DownloadManager.ERROR_DEVICE_NOT_FOUND ->
                                "Storage device not found"
                            DownloadManager.ERROR_FILE_ALREADY_EXISTS ->
                                "File already exists"
                            DownloadManager.ERROR_FILE_ERROR ->
                                "File error"
                            DownloadManager.ERROR_HTTP_DATA_ERROR ->
                                "HTTP data error"
                            DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
                                "Too many redirects"
                            DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
                                "Unhandled HTTP code: ${info.reason}"
                            else ->
                                "Download failed (reason: ${info.reason})"
                        }
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            downloadProgress = 0f,
                            downloadError = errorMsg,
                            downloadingModelId = null,
                        )
                        break
                    }

                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        if (info.totalBytes > 0) {
                            val progress = ((info.bytesDownloaded * 100) / info.totalBytes).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _uiState.value = _uiState.value.copy(
                                    downloadProgress = progress / 100f,
                                )
                            }
                        }
                    }
                }

                delay(500)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (currentDownloadId != -1L) {
            modelDownloadManager?.cancelDownload(currentDownloadId)
            modelDownloadManager = null
            currentDownloadId = -1L
        }
        _uiState.value = _uiState.value.copy(
            isDownloading = false,
            downloadProgress = 0f,
            downloadingModelId = null,
        )
    }

    private fun findModelFile(context: Context): File? {
        listOf(
            context.filesDir,
            context.getExternalFilesDir(null),
        ).forEach { dir ->
            if (dir != null) {
                dir.listFiles()
                    ?.filter { it.extension == "litertlm" || it.name.endsWith(".litertlm") }
                    ?.maxByOrNull { it.lastModified() }
                    ?.let { return it }
            }
        }
        val sdcard = File("/sdcard")
        if (sdcard.isDirectory) {
            sdcard.listFiles()
                ?.filter { it.extension == "litertlm" || it.name.endsWith(".litertlm") }
                ?.maxByOrNull { it.lastModified() }
                ?.let { return it }
        }
        return null
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val fileSize = 36 + dataSize

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

    private fun decodeAudioToPcm(context: Context, uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            check(audioTrackIndex >= 0) { "No audio track found" }
            extractor.selectTrack(audioTrackIndex)

            val mime = audioFormat!!.getString(MediaFormat.KEY_MIME)!!
            val outPcm = ByteArrayOutputStream()

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outPcm.write(chunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.unselectTrack(audioTrackIndex)

            val rawPcm = outPcm.toByteArray()

            val targetSampleRate = 16000
            val sourceSampleRate = audioFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate)
            val channelCount = audioFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)

            val pcm = if (channelCount > 1) {
                convertToMono(rawPcm, channelCount)
            } else {
                rawPcm
            }

            return if (sourceSampleRate != targetSampleRate) {
                resamplePcm(pcm, sourceSampleRate, targetSampleRate)
            } else {
                pcm
            }
        } finally {
            extractor.release()
        }
    }

    private fun convertToMono(stereoPcm: ByteArray, channels: Int): ByteArray {
        val shorts = ShortArray(stereoPcm.size / 2)
        ByteBuffer.wrap(stereoPcm).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)

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

    private fun resamplePcm(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
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

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        if (currentDownloadId != -1L) {
            modelDownloadManager?.cancelDownload(currentDownloadId)
        }
        engine?.close()
        engine = null
    }
}
