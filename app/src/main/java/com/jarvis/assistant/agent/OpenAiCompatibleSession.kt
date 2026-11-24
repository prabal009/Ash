package com.jarvis.assistant.agent

import android.util.Base64
import android.util.Log
import android.os.SystemClock
import com.jarvis.assistant.ProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Any server that speaks the OpenAI Chat Completions API with tool calling:
 * OpenAI, LM Studio, Ollama, OpenRouter, Gemini, Groq, vLLM, llama.cpp server, ...
 */
class OpenAiCompatibleSession(
    private val config: ProviderConfig,
    system: String,
    toolSpecs: List<ToolSpec>,
) : ChatSession {

    private val wire = ProviderRequest(config.baseUrl, toolSpecs.isNotEmpty())
    private val onPhone = wire.onPhone
    private val withTools = toolSpecs.isNotEmpty()
    private val modelLower = config.model.lowercase(java.util.Locale.ROOT)
    private val isReasoningModel = REASONING_HINTS.any { it in modelLower } ||
        modelLower.startsWith("o1") || modelLower.startsWith("o3") || modelLower.startsWith("o4")

    override fun cancel() = wire.cancel()

    private val tools = toolsJson(toolSpecs)

    private val messages = mutableListOf(JSONObject().put("role", "system").put("content", system))

    override fun seedHistory(exchanges: List<Pair<String, String>>) {
        if (messages.size != 1) return
        exchanges.takeLast(3).forEach { (user, assistant) ->
            messages += JSONObject().put("role", "user").put("content", user)
            messages += JSONObject().put("role", "assistant").put("content", assistant)
        }
    }

    override fun send(userText: String?, toolOutputs: List<ToolOutput>): AssistantTurn = providerResult {
        wire.checkActive()
        val images = mutableListOf<ByteArray>()
        for (out in toolOutputs) {
            messages += JSONObject().put("role", "tool").put("tool_call_id", out.callId)
                .put("content", if (out.isError) "ERROR: ${out.text}" else out.text)
            out.imageJpeg?.let { images += it }
        }
        if (userText != null || images.isNotEmpty()) {
            // Tool messages can't carry images, so screenshots go in a follow-up user message.
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", userText ?: "Screenshot of the current screen:"))
            images.forEach { jpeg ->
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
            }
            messages += JSONObject().put("role", "user").put("content", if (images.isEmpty()) userText else parts)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val json = wire.execute(request(stream = false)) { response ->
            JSONObject(response.body?.string().orEmpty())
        }
        Log.i("JarvisTiming", "provider=compatible loopback=$onPhone total=${SystemClock.elapsedRealtime() - startedAt}ms")

        val choice = json.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        val calls = mutableListOf<ToolCall>()
        val rawCalls = message.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val c = rawCalls.getJSONObject(i)
                // Some local servers omit ids; the follow-up tool message needs one either way.
                if (c.optString("id").isBlank()) c.put("id", "call_" + UUID.randomUUID().toString().take(8))
                val fn = c.getJSONObject("function")
                val args = when (val a = fn.opt("arguments")) {
                    is JSONObject -> a
                    is String -> runCatching { JSONObject(a.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                    else -> JSONObject()
                }
                calls += ToolCall(c.getString("id"), fn.getString("name"), args)
            }
        }
        wire.checkActive()
        val rawContent = message.optString("content", "").takeUnless { message.isNull("content") } ?: ""
        val cleanContent = stripThinking(rawContent)
        messages += JSONObject().put("role", "assistant")
            .put("content", cleanContent)
            .apply { if (rawCalls != null && rawCalls.length() > 0) put("tool_calls", rawCalls) }

        val finish = choice.optString("finish_reason")
        val note = if (finish == "length" && cleanContent.isBlank()) "Response was cut off (max tokens)." else null
        AssistantTurn(cleanContent, calls, note)
    }

    private fun buildBody(stream: Boolean): JSONObject = JSONObject()
        .put("model", config.model)
        .put("messages", JSONArray(trimmedHistory()))
        .put("max_tokens", maxTokens())
        .apply {
            if (stream) put("stream", true)
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if ((config.preset.id == "groq" && "gpt-oss" in modelLower) ||
                (config.preset.id == "gemini" && "gemini-2.5" in modelLower)
            ) {
                // Keep reasoning latency low so voice replies start quickly without exhausting max_tokens.
                put("reasoning_effort", "low")
            }
            if (onPhone) {
                // llama.cpp options: reuse the cached start of the prompt, and answer steadily instead of creatively.
                put("cache_prompt", true)
                put("temperature", 0.3)
            }
        }

    /** Plain chat streams: [onText] gets each piece as it arrives, so speech can start with the first sentence. */
    override fun send(userText: String?, toolOutputs: List<ToolOutput>, onText: (String) -> Unit): AssistantTurn =
        providerResult {
            wire.checkActive()
            // Tool calls are only usable whole.
            if (withTools || toolOutputs.isNotEmpty() || userText == null) {
                return@providerResult send(userText, toolOutputs).also { turn ->
                    wire.checkActive()
                    if (turn.text.isNotBlank()) onText(turn.text)
                }
            }
            messages += JSONObject().put("role", "user").put("content", userText)
            val startedAt = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            val text = StringBuilder()
            wire.execute(request(stream = true)) { response ->
                val reader = (response.body ?: throw ProviderFailure("The provider returned no reply."))
                    .byteStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    wire.checkActive()
                    val event = SseParser.parseLine(line)
                    if (event is SseParser.Event.Done) break
                    if (event is SseParser.Event.Text) {
                        if (text.isEmpty()) firstTokenAt = SystemClock.elapsedRealtime()
                        text.append(event.text)
                        onText(event.text)
                    }
                }
            }
            var reply = stripThinking(text.toString())
            if (reply.isBlank()) {
                // Some reasoning models or gateways omit streamed delta.content; retry once non-streamed.
                wire.checkActive()
                val json = wire.execute(request(stream = false)) { response ->
                    JSONObject(response.body?.string().orEmpty())
                }
                val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val fallback = msg?.optString("content", "")?.takeUnless { msg.isNull("content") }.orEmpty()
                reply = stripThinking(fallback)
                if (reply.isNotBlank()) {
                    wire.checkActive()
                    onText(reply)
                }
            }
            val now = SystemClock.elapsedRealtime()
            Log.i("JarvisTiming", "provider=compatible stream firstToken=${if (firstTokenAt > 0) firstTokenAt - startedAt else -1}ms total=${now - startedAt}ms")
            messages += JSONObject().put("role", "assistant").put("content", reply)
            AssistantTurn(reply, emptyList())
        }

    private fun request(stream: Boolean): Request = Request.Builder()
        .url(wire.baseUrl.newBuilder().addPathSegments("chat/completions").build())
        .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
        .post(buildBody(stream).toString().toRequestBody(JSON))
        .build()

    private fun maxTokens(): Int = when {
        withTools && isReasoningModel -> 1536
        withTools && onPhone -> 512
        withTools -> 768
        isReasoningModel -> 1024
        onPhone -> 256
        else -> 384
    }

    /**
     * Screen dumps are large and only the latest one matters, so older ones are shortened before
     * sending. This keeps long tasks inside the small context windows local models often have.
     */
    private fun trimmedHistory(): List<JSONObject> {
        val observationIdx = messages.indices.filter { i ->
            val m = messages[i]
            m.optString("role") == "tool" || (m.optString("role") == "user" && m.opt("content") is JSONArray)
        }
        val keep = observationIdx.takeLast(KEEP_FULL_OBSERVATIONS).toSet()
        return messages.mapIndexed { i, m ->
            if (i !in observationIdx || i in keep) return@mapIndexed m
            val copy = JSONObject(m.toString())
            when (val c = copy.opt("content")) {
                is String -> copy.put("content", c.substringBefore(SCREEN_MARKER).trim() + "\n(older screen omitted)")
                is JSONArray -> copy.put("content", "(older screenshot omitted)")
            }
            copy
        }
    }

    companion object {
        private const val KEEP_FULL_OBSERVATIONS = 1
        const val SCREEN_MARKER = "=== SCREEN"
        private val JSON = "application/json".toMediaType()
        private val REASONING_HINTS = listOf("gpt-oss", "deepseek-r1", "qwen3", "gemini-2.5", "reasoning", "thinking")
        private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>\\s*", RegexOption.IGNORE_CASE)

        internal fun stripThinking(text: String): String = text.replace(THINK_BLOCK, "").trim()

        private fun toolsJson(specs: List<ToolSpec>): JSONArray = JSONArray().apply {
            specs.forEach { spec ->
                put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", spec.parameters),
                    ),
                )
            }
        }
    }
}
