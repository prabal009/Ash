package com.jarvis.assistant

import android.app.KeyguardManager
import android.content.Context
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.ai.Endpoints
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The kind of wire API a provider speaks. */
enum class ApiKind { ANTHROPIC, OPENAI_COMPATIBLE }

/** A built-in provider preset. Every field except [kind] can be overridden by the user. */
data class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ApiKind,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
)

val PROVIDERS = listOf(
    ProviderPreset("anthropic", "Claude (Anthropic)", ApiKind.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", true),
    ProviderPreset("openai", "OpenAI", ApiKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5", true),
    ProviderPreset("lmstudio", "LM Studio (local)", ApiKind.OPENAI_COMPATIBLE, "http://192.168.1.100:1234/v1", "local-model", false),
    ProviderPreset("ollama", "Ollama (local)", ApiKind.OPENAI_COMPATIBLE, "http://192.168.1.100:11434/v1", "qwen2.5:14b", false),
    ProviderPreset("openrouter", "OpenRouter", ApiKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "anthropic/claude-opus-5-5", true),
    ProviderPreset("gemini", "Google Gemini", ApiKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true),
    ProviderPreset("groq", "Groq", ApiKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true),
    ProviderPreset("custom", "Custom (OpenAI-compatible)", ApiKind.OPENAI_COMPATIBLE, "http://localhost:8080/v1", "", false),
)

/** A model the user has saved. Several can exist; one is primary, the rest are fallbacks in order. */
data class ModelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val effort: String = "medium",
) {
    val preset get() = PROVIDERS.firstOrNull { it.id == providerId } ?: PROVIDERS.last()
    fun toConfig() = ProviderConfig(preset, baseUrl, apiKey, model, if (preset.kind == ApiKind.ANTHROPIC) effort else "")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("provider", providerId).put("base", baseUrl)
        .put("model", model).put("effort", effort)

    override fun toString() = "ModelProfile(credentials redacted)"

    companion object {
        fun fromJson(o: JSONObject, apiKey: String = "") = ModelProfile(
            o.getString("id"), o.optString("name"), o.optString("provider", "custom"), o.optString("base"),
            apiKey, o.optString("model"), o.optString("effort", "medium"),
        )
    }
}

/** Resolved settings for the provider the agent should talk to. */
data class ProviderConfig(
    val preset: ProviderPreset,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** Claude only: "low" | "medium" | "high" | "xhigh" | "max" | "" (omit). */
    val effort: String,
) {
    override fun toString() = "ProviderConfig(credentials redacted)"
}

class Settings internal constructor(context: Context, private val credentials: ProviderCredentials) {
    constructor(context: Context) : this(context, ProviderCredentials())

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 40)
        set(v) = prefs.edit().putInt("max_steps", v).apply()

    /** Whether the model may request screenshots (needs a vision-capable model and Android 11+). */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean("screenshots", true)
        set(v) = prefs.edit().putBoolean("screenshots", v).apply()

    var customWakePhrase: String
        get() = prefs.getString("custom_wake_phrase", "").orEmpty()
        set(v) = prefs.edit().putString("custom_wake_phrase", v).apply()

    val wakePromptPhrase get() = customWakePhrase.ifEmpty { com.jarvis.assistant.voice.WakePhrase.DEFAULT }

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", true)
        set(v) = prefs.edit().putBoolean("wake_enabled", v).apply()

    /** Whether the user has Jarvis switched on. Only the user changes this, so "Turn off" sticks. */
    var switchedOn: Boolean
        get() = prefs.getBoolean("switched_on", false)
        set(v) = prefs.edit().putBoolean("switched_on", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("boot", false)
        set(v) = prefs.edit().putBoolean("boot", v).apply()

    /** Free-form facts the agent should know, e.g. "My wife is saved as 'Amaka' in WhatsApp". */
    var personalNotes: String
        get() = prefs.getString("notes", "")!!
        set(v) = prefs.edit().putString("notes", v).apply()

    /** The user's own app tips, one per line as "App name: tip". See [com.jarvis.assistant.agent.AppTips]. */
    var appTips: String
        get() = prefs.getString("app_tips", "")!!
        set(v) = prefs.edit().putString("app_tips", v).apply()

    /** Show the task island at the top of the screen while Jarvis works. */
    var showIsland: Boolean
        get() = prefs.getBoolean("island", true)
        set(v) = prefs.edit().putBoolean("island", v).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    // ---- Models ----

    /** Try the next saved model if the primary one fails mid-task. */
    var fallbackEnabled: Boolean
        get() = prefs.getBoolean("fallback", true)
        set(v) = prefs.edit().putBoolean("fallback", v).apply()

    fun profiles(): List<ModelProfile> = runCatching {
        val arr = profileMetadata()
        List(arr.length()) { i ->
            val profile = arr.getJSONObject(i)
            ModelProfile.fromJson(profile, secret(profileSlot(profile.getString("id"))))
        }
    }.getOrDefault(emptyList())

    var primaryId: String?
        get() = primary()?.id
        set(v) = prefs.edit().putString("primary", v).apply()

    fun primary(): ModelProfile? = primaryIn(profiles())

    private fun primaryIn(all: List<ModelProfile>): ModelProfile? {
        val selected = prefs.getString("primary", null)
        return all.firstOrNull { it.id == selected } ?: all.firstOrNull()
    }

    /** Primary first, then the others in the order they were added. */
    fun modelsInOrder(): List<ModelProfile> {
        val all = profiles()
        val p = primaryIn(all) ?: return all
        return listOf(p) + all.filter { it.id != p.id }
    }

    fun saveProfile(profile: ModelProfile): Boolean = synchronized(CREDENTIALS_LOCK) {
        if (!migrateProviderCredentials()) return@synchronized false
        runCatching {
            val arr = profileMetadata()
            val index = (0 until arr.length()).firstOrNull { arr.getJSONObject(it).getString("id") == profile.id }
            arr.put(index ?: arr.length(), profile.toJson())
            val edit = prefs.edit().putString("profiles", arr.toString())
            if (arr.length() == 1) edit.putString("primary", profile.id)
            putSecret(edit, profileSlot(profile.id), profile.apiKey)
            edit.commit()
        }.getOrDefault(false)
    }

    fun deleteProfile(id: String): Boolean = synchronized(CREDENTIALS_LOCK) {
        if (!migrateProviderCredentials()) return@synchronized false
        runCatching {
            val arr = profileMetadata()
            val index = (0 until arr.length()).firstOrNull { arr.getJSONObject(it).getString("id") == id }
            if (index != null) arr.remove(index)
            val edit = prefs.edit().putString("profiles", arr.toString()).remove(profileSlot(id))
            if (prefs.getString("primary", null) == id) edit.putString("primary", arr.optJSONObject(0)?.getString("id"))
            edit.commit()
        }.getOrDefault(false)
    }

    private fun profileMetadata() = JSONArray(prefs.getString("profiles", "[]"))

    /** Called by visible setup/settings. Leave plaintext intact if secure persistence cannot be verified. */
    fun migrateProviderCredentials(): Boolean = synchronized(CREDENTIALS_LOCK) {
        if (app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) return@synchronized false
        runCatching { migrateUnlockedCredentials() }.getOrDefault(false)
    }

    private fun migrateUnlockedCredentials(): Boolean {
        val hasProfiles = prefs.contains("profiles")
        val arr = profileMetadata()
        val secrets = linkedMapOf<String, String>()
        val obsoleteKeys = mutableListOf<String>()
        var rewriteProfiles = false
        for (i in 0 until arr.length()) {
            val profile = arr.getJSONObject(i)
            if (!profile.has("key")) continue
            profile.optString("key").takeUnless(String::isBlank)?.let { secrets[profileSlot(profile.getString("id"))] = it }
            profile.remove("key")
            rewriteProfiles = true
        }
        var oldPrimary: String? = null
        val oldProvider = if (hasProfiles) null else prefs.getString("provider", null)
        for (preset in PROVIDERS) {
            val prefix = "p.${preset.id}"
            val oldSlot = "$prefix.key"
            if (prefs.contains(oldSlot)) obsoleteKeys += oldSlot
            if (hasProfiles) continue // Old duplicates were superseded by saved profiles.
            val key = prefs.getString(oldSlot, "").orEmpty()
            val model = prefs.getString("$prefix.model", null)
            if (key.isBlank() && (preset.needsKey || model == null)) continue
            val profile = ModelProfile(
                id = "legacy-${preset.id}", name = preset.label.substringBefore(" ("), providerId = preset.id,
                baseUrl = prefs.getString("$prefix.base", null) ?: preset.defaultBaseUrl, apiKey = key,
                model = model ?: preset.defaultModel,
                effort = prefs.getString("$prefix.effort", null).orEmpty().ifEmpty { "medium" },
            )
            if (preset.id == oldProvider || oldPrimary == null) oldPrimary = profile.id
            arr.put(profile.toJson())
            if (key.isNotBlank()) secrets[profileSlot(profile.id)] = key
            rewriteProfiles = true
        }
        for (preset in CLOUD_VOICES) {
            val oldSlot = "tts_${preset.id}_key"
            if (!prefs.contains(oldSlot)) continue
            obsoleteKeys += oldSlot
            prefs.getString(oldSlot, "").orEmpty().takeUnless(String::isBlank)?.let { secrets[voiceSlot(preset.id)] = it }
        }
        if (prefs.contains("runtime_key")) {
            obsoleteKeys += "runtime_key"
            prefs.getString("runtime_key", "").orEmpty().takeUnless(String::isBlank)?.let { secrets[RUNTIME_SLOT] = it }
        }
        if (secrets.isNotEmpty()) {
            val stage = prefs.edit()
            secrets.forEach { (slot, value) -> putSecret(stage, slot, value) }
            if (!stage.commit() || secrets.any { (slot, value) -> secret(slot) != value }) return false
        }
        if (!rewriteProfiles && obsoleteKeys.isEmpty()) return true
        val edit = prefs.edit()
        if (rewriteProfiles) edit.putString("profiles", arr.toString())
        if (!hasProfiles && rewriteProfiles) edit.putString("primary", oldPrimary)
        obsoleteKeys.forEach { edit.remove(it) }
        return edit.commit()
    }

    private fun secret(slot: String): String =
        prefs.getString(slot, null)?.let { credentials.decrypt(slot, it) }.orEmpty()

    private fun putSecret(edit: android.content.SharedPreferences.Editor, slot: String, value: String) {
        if (value.isBlank()) {
            edit.remove(slot)
        } else {
            val sealed = credentials.encrypt(slot, value)
            check(credentials.decrypt(slot, sealed) == value)
            edit.putString(slot, sealed)
        }
    }

    private fun profileSlot(id: String) = "provider_secret.profile.$id"
    private fun voiceSlot(id: String) = "provider_secret.voice.$id"

    // ---- Voice ----

    /**
     * "kokoro" (natural on-device neural voice), "system" (Android text-to-speech), or the id of a
     * cloud voice in [CLOUD_VOICES] ("openai", "elevenlabs", "fish", "custom").
     */
    var voiceEngine: String
        get() = prefs.getString("voice_engine", "kokoro")!!
        set(v) = prefs.edit().putString("voice_engine", v).apply()

    var kokoroSpeaker: Int
        get() = prefs.getInt("kokoro_sid", KOKORO_VOICES.first().sid)
        set(v) = prefs.edit().putInt("kokoro_sid", v).apply()

    var voiceSpeed: Float
        get() = prefs.getFloat("voice_speed", 1.0f)
        set(v) = prefs.edit().putFloat("voice_speed", v).apply()

    // ---- Cloud voices ----

    fun cloudVoice(id: String): CloudVoiceConfig {
        val preset = CLOUD_VOICES.first { it.id == id }
        val slot = voiceSlot(id)
        val base = prefs.getString("tts_${id}_url", "").orEmpty().ifEmpty { preset.defaultBaseUrl }
        val key = if (prefs.contains(slot)) {
            secret(slot)
        } else if (id == "openai" && !prefs.contains("tts_${id}_key") && isOfficialOpenAi(base)) {
            openAiKeyFromModels()
        } else {
            ""
        }
        return CloudVoiceConfig(
            id = id,
            apiKey = key,
            model = prefs.getString("tts_${id}_model", "")!!.ifEmpty { preset.defaultModel },
            voice = prefs.getString("tts_${id}_voice", null) ?: preset.defaultVoice,
            baseUrl = base,
        )
    }

    fun saveCloudVoice(c: CloudVoiceConfig): Boolean = synchronized(CREDENTIALS_LOCK) {
        if (!migrateProviderCredentials()) return@synchronized false
        runCatching {
            val edit = prefs.edit()
                .putString("tts_${c.id}_model", c.model.trim())
                .putString("tts_${c.id}_voice", c.voice.trim())
                .putString("tts_${c.id}_url", c.baseUrl.trim().trimEnd('/'))
            putSecret(edit, voiceSlot(c.id), c.apiKey.trim())
            edit.commit()
        }.getOrDefault(false)
    }

    /** Only re-use an official OpenAI credential for the official OpenAI TTS endpoint. */
    private fun openAiKeyFromModels(): String =
        modelsInOrder().firstOrNull { it.providerId == "openai" && isOfficialOpenAi(it.baseUrl) && it.apiKey.isNotBlank() }
            ?.apiKey.orEmpty()

    private fun isOfficialOpenAi(url: String): Boolean =
        runCatching { Endpoints.requireHttpUrl(url) }.getOrNull()?.let { it.isHttps && it.host == "api.openai.com" && it.port == 443 } == true

    // ---- Conversation ----

    /** Let the user talk over Jarvis to interrupt it (the microphone stays on while it speaks). */
    var interruptByTalking: Boolean
        get() = prefs.getBoolean("barge_in", true)
        set(v) = prefs.edit().putBoolean("barge_in", v).apply()

    /** After answering, keep listening for a few seconds so a follow-up needs no wake word. */
    var followUp: Boolean
        get() = prefs.getBoolean("follow_up", true)
        set(v) = prefs.edit().putBoolean("follow_up", v).apply()

    // ---- Assistant behaviour ----

    /** AUTO, ONLINE or OFFLINE. Controls which model answers; simple phone actions never use a model. */
    var aiMode: AiMode
        get() = AiMode.parse(prefs.getString("ai_mode", null))
        set(v) = prefs.edit().putString("ai_mode", v.name).apply()

    /** "en" = offline English recognizer; "hi" = Google recognizer in Hindi (also understands Hinglish). */
    var speechLanguage: String
        get() = prefs.getString("speech_lang", "en") ?: "en"
        set(v) = prefs.edit().putString("speech_lang", if (v == "hi") "hi" else "en").apply()

    /** Ask before placing calls or sending messages. */
    var confirmSensitive: Boolean
        get() = prefs.getBoolean("confirm_sensitive", true)
        set(v) = prefs.edit().putBoolean("confirm_sensitive", v).apply()

    /** Let cloud models see memories you asked Jarvis to keep. Off by default: memories stay on the phone. */
    var shareMemoryWithCloud: Boolean
        get() = prefs.getBoolean("share_memory_cloud", false)
        set(v) = prefs.edit().putBoolean("share_memory_cloud", v).apply()

    // ---- Local model server (llama-server in Termux) ----

    /** Start the local server through Termux when it is needed and not running. */
    var localServerAutoStart: Boolean
        get() = prefs.getBoolean("local_autostart", true)
        set(v) = prefs.edit().putBoolean("local_autostart", v).apply()

    /** Script installed by termux/install-termux.sh. Must be an absolute path inside Termux. */
    var termuxScriptPath: String
        get() = prefs.getString("termux_script", DEFAULT_TERMUX_SCRIPT) ?: DEFAULT_TERMUX_SCRIPT
        set(v) = prefs.edit().putString("termux_script", v.ifBlank { DEFAULT_TERMUX_SCRIPT }).apply()

    /** Local date (yyyy-MM-dd) of the last daily greeting. Kept across restarts so Jarvis greets once per day. */
    var lastGreetingDate: String?
        get() = prefs.getString("last_greeting_date", null)
        set(v) = prefs.edit().putString("last_greeting_date", v).apply()

    /** Speak a chat answer sentence by sentence as it is written, instead of waiting for the whole answer. */
    var streamReplies: Boolean
        get() = prefs.getBoolean("stream_replies", true)
        set(v) = prefs.edit().putBoolean("stream_replies", v).apply()

    /** Save the latest 100 eligible tasks and their replay steps on this device. */
    var rememberTasks: Boolean
        get() = prefs.getBoolean("remember_tasks", true)
        set(v) = prefs.edit().putBoolean("remember_tasks", v).apply()

    // ---- On-device model runtime (llama-server shipped inside the app) ----

    /** Use the model server that ships inside the app. Turn off to rely on Termux or cloud models only. */
    var embeddedRuntimeEnabled: Boolean
        get() = prefs.getBoolean("embedded_runtime", true)
        set(v) = prefs.edit().putBoolean("embedded_runtime", v).apply()

    /** Random key the app uses to talk to its own model server; no other app knows it. */
    var runtimeApiKey: String
        get() = secret(RUNTIME_SLOT)
        set(v) = synchronized(CREDENTIALS_LOCK) {
            val saved = runCatching {
                if (prefs.contains("runtime_key")) return@runCatching false
                val edit = prefs.edit()
                putSecret(edit, RUNTIME_SLOT, v)
                edit.commit()
            }.getOrDefault(false)
            check(saved) { "Secure storage for the on-device model is unavailable." }
        }

    internal fun getOrCreateRuntimeApiKey(create: () -> String): String = synchronized(CREDENTIALS_LOCK) {
        runtimeApiKey.ifEmpty {
            check(!prefs.contains("runtime_key")) { "Open Settings to secure the on-device model credential." }
            create().also { runtimeApiKey = it }
        }
    }

    /** Minutes without a request before the model is unloaded to free about 1 GB of memory. 0 = keep it loaded. */
    var runtimeKeepWarmMinutes: Int
        get() = prefs.getInt("runtime_keep_warm_min", 20)
        set(v) = prefs.edit().putInt("runtime_keep_warm_min", v.coerceAtLeast(0)).apply()

    /** Allow the 1 GB model download on mobile data. Off by default: it waits for Wi-Fi. */
    var downloadModelOnMobile: Boolean
        get() = prefs.getBoolean("model_download_mobile", false)
        set(v) = prefs.edit().putBoolean("model_download_mobile", v).apply()

    companion object {
        const val DEFAULT_TERMUX_SCRIPT = "/data/data/com.termux/files/home/.jarvis/start-llama.sh"
        private const val RUNTIME_SLOT = "provider_secret.runtime"
        private val CREDENTIALS_LOCK = Any()
    }
}

data class CloudVoiceConfig(val id: String, val apiKey: String, val model: String, val voice: String, val baseUrl: String) {
    override fun toString() = "CloudVoiceConfig(credentials redacted)"
}

data class CloudVoicePreset(
    val id: String,
    val name: String,
    val defaultModel: String,
    val defaultVoice: String,
    val defaultBaseUrl: String,
    /** Where to get a key. */
    @androidx.annotation.StringRes val keyHint: Int,
    /** What to put in the voice field. */
    @androidx.annotation.StringRes val voiceHint: Int,
    val needsKey: Boolean = true,
    val editableUrl: Boolean = false,
)

/** Voices that run in the cloud. Audio streams back as it's generated, so speech starts quickly. */
val CLOUD_VOICES = listOf(
    CloudVoicePreset(
        "openai", "OpenAI", "gpt-4o-mini-tts", "onyx", "https://api.openai.com/v1",
        keyHint = R.string.openai_key_hint,
        voiceHint = R.string.openai_voice_hint,
    ),
    CloudVoicePreset(
        "elevenlabs", "ElevenLabs", "eleven_flash_v2_5", "JBFqnCBsd6RMkjVDRZzb", "https://api.elevenlabs.io/v1",
        keyHint = R.string.elevenlabs_key_hint,
        voiceHint = R.string.elevenlabs_voice_hint,
    ),
    CloudVoicePreset(
        "fish", "Fish Audio", "s2.1-pro", "", "https://api.fish.audio/v1",
        keyHint = R.string.fish_key_hint,
        voiceHint = R.string.fish_voice_hint,
    ),
    CloudVoicePreset(
        "custom", "Custom", "tts-1", "alloy", "http://192.168.1.10:8880/v1",
        keyHint = R.string.custom_voice_key_hint,
        voiceHint = R.string.custom_voice_voice_hint,
        needsKey = false, editableUrl = true,
    ),
)

data class KokoroVoice(val sid: Int, val name: String, val accent: String)

/** Speakers in the kokoro-en-v0_19 model; sid is the model's speaker id. British male first: it suits Jarvis. */
val KOKORO_VOICES = listOf(
    KokoroVoice(9, "George", "British male"),
    KokoroVoice(10, "Lewis", "British male"),
    KokoroVoice(7, "Emma", "British female"),
    KokoroVoice(8, "Isabella", "British female"),
    KokoroVoice(5, "Adam", "American male"),
    KokoroVoice(6, "Michael", "American male"),
    KokoroVoice(1, "Bella", "American female"),
    KokoroVoice(2, "Nicole", "American female"),
    KokoroVoice(3, "Sarah", "American female"),
    KokoroVoice(4, "Sky", "American female"),
    KokoroVoice(0, "Default", "American female"),
)
