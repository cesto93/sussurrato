package com.pierfrancescocontino.sussurrato.llama.internal

import android.content.Context
import android.util.Log
import com.pierfrancescocontino.sussurrato.llama.AsrEngine
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class AsrEngineImpl private constructor(
    private val nativeLibDir: String
) : AsrEngine {

    companion object {
        private val TAG = AsrEngineImpl::class.java.simpleName

        @Volatile
        private var instance: AsrEngine? = null

        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating AsrEngineImpl...")
                    AsrEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    @FastNative
    private external fun nativeInit()

    @FastNative
    private external fun nativeLoadModel(modelPath: String, mmprojPath: String): Int

    @FastNative
    private external fun nativeTranscribe(audioData: FloatArray, sampleRate: Int): String

    @FastNative
    private external fun nativeUnload()

    @FastNative
    private external fun nativeShutdown()

    private val _state = MutableStateFlow<AsrEngine.State>(AsrEngine.State.Uninitialized)
    override val state: StateFlow<AsrEngine.State> = _state.asStateFlow()

    @Volatile
    private var _cancelTranscription = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is AsrEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = AsrEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("sussurrato-llama")
                nativeInit()
                _state.value = AsrEngine.State.Initialized
                Log.i(TAG, "Native library loaded!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }
    }

    override suspend fun loadModel(decoderPath: String, mmprojPath: String) = withContext(llamaDispatcher) {
        check(_state.value is AsrEngine.State.Initialized) {
            "Cannot load model in ${_state.value.javaClass.simpleName}!"
        }

        try {
            Log.i(TAG, "Checking access to model files...")
            File(decoderPath).let {
                require(it.exists()) { "Decoder file not found" }
                require(it.isFile) { "Not a valid file" }
                require(it.canRead()) { "Cannot read file" }
            }
            File(mmprojPath).let {
                require(it.exists()) { "MMProj file not found" }
                require(it.isFile) { "Not a valid file" }
                require(it.canRead()) { "Cannot read file" }
            }

            Log.i(TAG, "Loading model...")
            _state.value = AsrEngine.State.LoadingModel
            val result = nativeLoadModel(decoderPath, mmprojPath)
            if (result != 0) throw IOException("Failed to load model (code=$result)")

            _cancelTranscription = false
            _state.value = AsrEngine.State.ModelReady
            Log.i(TAG, "Model loaded!")
        } catch (e: Exception) {
            Log.e(TAG, (e.message ?: "Error loading model"), e)
            _state.value = AsrEngine.State.Error(e)
            throw e
        }

        Unit
    }

    override fun transcribe(audioPcm: FloatArray, sampleRate: Int): Flow<String> = flow {
        require(audioPcm.isNotEmpty()) { "Audio data is empty!" }
        check(_state.value is AsrEngine.State.ModelReady) {
            "Transcription discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            _cancelTranscription = false
            _state.value = AsrEngine.State.Transcribing

            val text = nativeTranscribe(audioPcm, sampleRate)
            if (text.isNotEmpty()) emit(text)

            _state.value = AsrEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Transcription flow collection cancelled.")
            _state.value = AsrEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during transcription!", e)
            _state.value = AsrEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    override fun cleanUp() {
        _cancelTranscription = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is AsrEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model...")
                    _state.value = AsrEngine.State.UnloadingModel
                    nativeUnload()
                    _state.value = AsrEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                }
                is AsrEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _state.value = AsrEngine.State.Initialized
                }
                else -> throw IllegalStateException(
                    "Cannot unload model in ${state.javaClass.simpleName}"
                )
            }
        }
    }

    override fun destroy() {
        _cancelTranscription = true
        runBlocking(llamaDispatcher) {
            when (_state.value) {
                is AsrEngine.State.Uninitialized -> {}
                is AsrEngine.State.Initialized -> nativeShutdown()
                else -> { nativeUnload(); nativeShutdown() }
            }
        }
        llamaScope.cancel()
    }
}
