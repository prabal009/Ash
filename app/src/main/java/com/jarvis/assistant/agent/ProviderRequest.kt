package com.jarvis.assistant.agent

import com.jarvis.assistant.ai.Endpoints
import com.jarvis.assistant.ai.Endpoints.configureLocalTransport
import com.jarvis.assistant.ai.InvalidProviderEndpoint
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ProviderFailure(message: String) : IOException(message)

/** These are the only errors allowed across the provider boundary; raw server bodies can echo secrets. */
internal inline fun <T> providerResult(operation: () -> T): T = try {
    operation()
} catch (e: ProviderFailure) {
    throw e
} catch (e: InvalidProviderEndpoint) {
    throw e
} catch (_: InterruptedIOException) {
    throw ProviderFailure("The provider request timed out.")
} catch (_: IOException) {
    throw ProviderFailure("Could not reach the provider.")
} catch (_: Exception) {
    throw ProviderFailure("Invalid provider configuration or response.")
}

internal class ProviderRequest(url: String, withTools: Boolean) {
    val baseUrl = Endpoints.requireHttpUrl(url)
    val onPhone = Endpoints.isLoopback(baseUrl)
    private val local = Endpoints.isLocal(baseUrl)
    private val http = (if (local) LOCAL else REMOTE).newBuilder()
        .connectTimeout(if (onPhone) 3 else 10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(
            when {
                local && withTools -> 240
                local -> 90
                withTools -> 60
                else -> 30
            },
            TimeUnit.SECONDS,
        )
        .readTimeout(if (local && withTools) 240 else if (local) 90 else 30, TimeUnit.SECONDS)
        .build()

    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)

    fun cancel() {
        cancelled.set(true)
        activeCall.get()?.cancel()
    }

    fun checkActive() {
        if (cancelled.get()) throw ProviderFailure("The provider request was cancelled.")
    }

    fun <T> execute(request: Request, decode: (Response) -> T): T {
        checkActive()
        val call = http.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "Concurrent provider requests in one session" }
        try {
            if (cancelled.get()) call.cancel()
            return call.execute().use { response ->
                checkActive()
                if (!response.isSuccessful) throw ProviderFailure(statusMessage(response.code))
                decode(response).also { checkActive() }
            }
        } catch (e: Exception) {
            checkActive()
            throw e
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private companion object {
        val REMOTE: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }
        val LOCAL: OkHttpClient by lazy { REMOTE.newBuilder().configureLocalTransport().build() }

        fun statusMessage(code: Int): String = when (code) {
            401, 403 -> "The provider rejected authentication (HTTP $code). Check the API key."
            404 -> "Provider endpoint or model not found (HTTP 404)."
            429 -> "The provider is rate limiting requests (HTTP 429)."
            in 300..399 -> "The provider redirected the request (HTTP $code). Configure the final endpoint."
            else -> "The provider returned HTTP $code."
        }
    }
}
