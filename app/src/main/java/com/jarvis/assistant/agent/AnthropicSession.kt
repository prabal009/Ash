package com.jarvis.assistant.agent

import android.util.Base64
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.jarvis.assistant.ProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.jvm.optionals.getOrNull

/** Claude Messages API with SDK wire models so native thinking blocks replay intact. */
class AnthropicSession(
    private val config: ProviderConfig,
    private val system: String,
    toolSpecs: List<ToolSpec>,
) : ChatSession {

    private val wire = ProviderRequest(config.baseUrl.ifBlank { config.preset.defaultBaseUrl }, toolSpecs.isNotEmpty())

    override fun cancel() = wire.cancel()

    private val tools: List<Tool> = toolSpecs.map { spec ->
        val props = spec.parameters.optJSONObject("properties") ?: JSONObject()
        val required = spec.parameters.optJSONArray("required")
        Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder().apply {
                            props.keys().forEach { putAdditionalProperty(it, JsonValue.from(jsonToPlain(props.get(it)))) }
                        }.build()
                    )
                    .required((0 until (required?.length() ?: 0)).map { required!!.getString(it) })
                    .build()
            )
            .build()
    }

    private val history = mutableListOf<MessageParam>()

    override fun seedHistory(exchanges: List<Pair<String, String>>) {
        if (history.isNotEmpty()) return
        exchanges.takeLast(3).forEach { (user, assistant) ->
            history += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(user)
                .build()
            history += MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content(assistant)
                .build()
        }
    }

    override fun send(userText: String?, toolOutputs: List<ToolOutput>): AssistantTurn = providerResult {
        wire.checkActive()
        val blocks = mutableListOf<ContentBlockParam>()
        // All tool results for one assistant turn go back in a single user message.
        for (out in toolOutputs) {
            val content = mutableListOf(
                ToolResultBlockParam.Content.Block.ofText(TextBlockParam.builder().text(out.text).build())
            )
            out.imageJpeg?.let { content += ToolResultBlockParam.Content.Block.ofImage(image(it)) }
            blocks += ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(out.callId)
                    .contentOfBlocks(content)
                    .isError(out.isError)
                    .build()
            )
        }
        if (userText != null) blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(userText).build())
        history += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build()

        val params = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(16000L)
            .system(system)
            .messages(history)
            // Top-level auto caching: the growing history is re-sent every step, so cache it.
            .cacheControl(CacheControlEphemeral.builder().build())
            .apply {
                tools.forEach { addTool(it) }
                if (config.effort.isNotBlank()) {
                    outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(config.effort)).build())
                }
            }
            .build()

        val request = Request.Builder()
            .url(wire.baseUrl.newBuilder().addPathSegments("v1/messages").build())
            .header("anthropic-version", "2023-06-01")
            .header("x-api-key", config.apiKey)
            .post(jsonMapper().writeValueAsBytes(params._body()).toRequestBody(JSON))
            .build()
        val response = wire.execute(request) { reply ->
            val body = reply.body ?: throw ProviderFailure("The provider returned no reply.")
            jsonMapper().readValue(body.byteStream(), Message::class.java)
        }
        history += response.toParam()

        if (response.stopReason().getOrNull() == StopReason.REFUSAL) {
            return@providerResult AssistantTurn("", emptyList(), stopNote = "The model declined this request.")
        }

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (block in response.content()) {
            block.text().getOrNull()?.let { text.append(it.text()) }
            block.toolUse().getOrNull()?.let { use ->
                val input = runCatching { JSONObject(use._input().convert(Map::class.java) ?: emptyMap<Any, Any>()) }.getOrElse { JSONObject() }
                calls += ToolCall(use.id(), use.name(), input)
            }
        }
        val note = if (response.stopReason().getOrNull() == StopReason.MAX_TOKENS) "Response was cut off (max tokens)." else null
        wire.checkActive()
        AssistantTurn(text.toString(), calls, note)
    }

    // The Anthropic transport is buffered, but a cancelled request never publishes its reply.
    override fun send(userText: String?, toolOutputs: List<ToolOutput>, onText: (String) -> Unit): AssistantTurn =
        send(userText, toolOutputs).also { turn ->
            wire.checkActive()
            if (turn.text.isNotBlank()) onText(turn.text)
        }

    private fun image(jpeg: ByteArray): ImageBlockParam = ImageBlockParam.builder()
        .source(
            Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                .build()
        )
        .build()

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
