package com.jarvis.assistant.ai

import com.jarvis.assistant.ai.Endpoints.configureLocalTransport
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What a quick `GET /health` says about a local model server. No Android classes, so it is unit-tested. */
object ServerHealth {
    enum class State { OK, LOADING, DOWN }

    /** llama-server answers 200 when ready and 503 while the model loads. Servers without /health (404) are alive. */
    fun check(origin: String, timeoutMs: Int = 700): State {
        return try {
            val base = Endpoints.requireHttpUrl(origin)
            val client = if (Endpoints.isLocal(base)) LOCAL_HTTP else HTTP
            val request = Request.Builder().url(base.newBuilder().addPathSegment("health").build()).build()
            val call = client.newCall(request)
            call.timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                when (response.code) {
                    in 200..299, 404, 405 -> State.OK
                    503 -> State.LOADING
                    else -> State.DOWN
                }
            }
        } catch (_: Exception) {
            State.DOWN
        }
    }

    private val HTTP = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val LOCAL_HTTP = HTTP.newBuilder().configureLocalTransport().build()
}
