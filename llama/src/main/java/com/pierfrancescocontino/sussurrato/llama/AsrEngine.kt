package com.pierfrancescocontino.sussurrato.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AsrEngine {
    val state: StateFlow<State>

    suspend fun loadModel(decoderPath: String, mmprojPath: String)

    fun transcribe(audioPcm: FloatArray, sampleRate: Int): Flow<String>

    fun cleanUp()

    fun destroy()

    sealed class State {
        data object Uninitialized : State()
        data object Initializing : State()
        data object Initialized : State()
        data object LoadingModel : State()
        data object UnloadingModel : State()
        data object ModelReady : State()
        data object Transcribing : State()
        data class Error(val exception: Exception) : State()
    }
}
