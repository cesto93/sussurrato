package com.pierfrancescocontino.sussurrato

import org.junit.Assert.fail
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

class ModelUrlTest {

    @Test
    fun modelUrlsAreReachable() {
        val errors = mutableListOf<String>()

        for (model in TranscriptionViewModel.MODELS) {
            checkUrl(model.url, model.id, "model", errors)
            if (model.mmprojUrl != null) {
                checkUrl(model.mmprojUrl, model.id, "mmproj", errors)
            }
        }

        if (errors.isNotEmpty()) {
            fail("URL check failures:\n" + errors.joinToString("\n"))
        }
    }

    private fun checkUrl(url: String, modelId: String, type: String, errors: MutableList<String>) {
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                errors.add("$modelId ($type): HTTP $code for $url")
            }
            connection.disconnect()
        } catch (e: Exception) {
            errors.add("$modelId ($type): ${e.message} for $url")
        }
    }
}
