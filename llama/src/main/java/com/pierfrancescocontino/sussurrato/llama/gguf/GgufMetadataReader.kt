package com.pierfrancescocontino.sussurrato.llama.gguf

import android.content.Context
import android.net.Uri
import com.pierfrancescocontino.sussurrato.llama.internal.gguf.GgufMetadataReaderImpl
import java.io.File
import java.io.IOException
import java.io.InputStream

interface GgufMetadataReader {
    suspend fun ensureSourceFileFormat(file: File): Boolean

    suspend fun ensureSourceFileFormat(context: Context, uri: Uri): Boolean

    suspend fun readStructuredMetadata(input: InputStream): GgufMetadata

    companion object {
        private val DEFAULT_SKIP_KEYS = setOf(
            "tokenizer.chat_template",
            "tokenizer.ggml.scores",
            "tokenizer.ggml.tokens",
            "tokenizer.ggml.token_type"
        )

        fun create(): GgufMetadataReader = GgufMetadataReaderImpl(
            skipKeys = DEFAULT_SKIP_KEYS,
            arraySummariseThreshold = 1_000
        )

        fun create(
            skipKeys: Set<String> = DEFAULT_SKIP_KEYS,
            arraySummariseThreshold: Int = 1_000
        ): GgufMetadataReader = GgufMetadataReaderImpl(
            skipKeys = skipKeys,
            arraySummariseThreshold = arraySummariseThreshold
        )
    }
}

class InvalidFileFormatException : IOException()
