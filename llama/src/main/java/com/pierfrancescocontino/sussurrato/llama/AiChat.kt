package com.pierfrancescocontino.sussurrato.llama

import android.content.Context
import com.pierfrancescocontino.sussurrato.llama.internal.AsrEngineImpl

object AiChat {
    fun getAsrEngine(context: Context): AsrEngine = AsrEngineImpl.getInstance(context)
}
