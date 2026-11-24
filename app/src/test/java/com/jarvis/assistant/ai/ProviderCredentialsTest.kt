package com.jarvis.assistant.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.CloudVoiceConfig
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.ProviderCredentials
import com.jarvis.assistant.Settings
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderCredentialsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val settings = Settings(context, ProviderCredentials(key = { key }))

    @Test
    fun `migration keeps profile metadata ordering and all active credentials`() {
        prefs.edit().putString("profiles", """
            [
              {"id":"primary","name":"Named","provider":"anthropic","key":"legacy-profile-secret","base":"https://example.com/gateway","model":"model-a","effort":"low"},
              {"id":"local","name":"Desk","provider":"ollama","key":"","base":"http://192.168.0.10:11434/v1","model":"favourite"}
            ]
            """.trimIndent())
            .putString("primary", "local")
            .putString("p.anthropic.key", "superseded-secret")
            .putString("tts_fish_key", "legacy-voice-secret")
            .putString("tts_fish_voice", "favourite-voice")
            .putString("runtime_key", "legacy-runtime-secret")
            .putBoolean("fallback", false)
            .commit()

        assertEquals("", settings.profiles().first().apiKey)
        assertEquals("", settings.runtimeApiKey)
        assertTrue(settings.migrateProviderCredentials())
        assertTrue(settings.migrateProviderCredentials())
        assertEquals(listOf("local", "primary"), settings.modelsInOrder().map { it.id })
        val profile = settings.profiles().first()
        assertEquals("https://example.com/gateway", profile.baseUrl)
        assertEquals("low", profile.effort)
        assertEquals("legacy-profile-secret", profile.apiKey)
        assertEquals("legacy-voice-secret", settings.cloudVoice("fish").apiKey)
        assertEquals("favourite-voice", settings.cloudVoice("fish").voice)
        assertEquals("legacy-runtime-secret", settings.getOrCreateRuntimeApiKey { error("rotation not requested") })
        assertFalse(settings.fallbackEnabled)
        assertFalse(JSONArray(prefs.getString("profiles", null)).getJSONObject(0).has("key"))
        assertFalse(prefs.contains("p.anthropic.key"))
        assertFalse(prefs.contains("tts_fish_key"))
        assertFalse(prefs.contains("runtime_key"))
        assertFalse(prefs.all.values.any { it.toString().contains("-secret") })
    }

    @Test
    fun `first generation model preferences become encrypted profiles`() {
        prefs.edit().putString("p.anthropic.key", "old-claude-key")
            .putString("p.lmstudio.model", "favourite")
            .putString("p.lmstudio.base", "http://10.0.0.4:1234/v1")
            .putString("provider", "lmstudio")
            .commit()
        assertTrue(settings.migrateProviderCredentials())
        assertEquals(listOf("anthropic", "lmstudio"), settings.profiles().map { it.providerId })
        assertEquals("old-claude-key", settings.profiles().first().apiKey)
        assertEquals("lmstudio", settings.primary()?.providerId)
        assertFalse(prefs.contains("p.anthropic.key"))
    }

    @Test
    fun `failure leaves legacy ciphertext candidates untouched and unusable`() {
        val unavailable = Settings(context, ProviderCredentials(key = { null }))
        prefs.edit().putString("p.openai.key", "old-provider-key")
            .putString("tts_elevenlabs_key", "old-voice-key").putString("runtime_key", "old-runtime-key").commit()
        assertFalse(unavailable.migrateProviderCredentials())
        assertEquals("old-provider-key", prefs.getString("p.openai.key", null))
        assertEquals("old-voice-key", prefs.getString("tts_elevenlabs_key", null))
        assertEquals("old-runtime-key", prefs.getString("runtime_key", null))
        assertTrue(unavailable.profiles().isEmpty())
        assertEquals("", unavailable.runtimeApiKey)
        assertEquals("", unavailable.cloudVoice("elevenlabs").apiKey)
        assertFalse(unavailable.saveCloudVoice(CloudVoiceConfig("fish", "new-secret", "", "", "")))
        assertFalse(prefs.all.values.contains("new-secret"))
    }

    @Test
    fun `fresh nonces authenticated slots and key loss fail closed without losing profile metadata`() {
        val a = ModelProfile("a", "First", "openai", "https://api.openai.com/v1", "same-secret", "saved-model")
        assertTrue(settings.saveProfile(a))
        val slotA = "provider_secret.profile.a"
        val first = prefs.getString(slotA, null)
        assertTrue(settings.saveProfile(a))
        assertNotEquals(first, prefs.getString(slotA, null))
        assertTrue(settings.saveProfile(a.copy(id = "b", name = "Second")))
        prefs.edit().putString(slotA, prefs.getString("provider_secret.profile.b", null)).commit()
        assertEquals("", settings.primary()?.apiKey)
        assertEquals("same-secret", settings.profiles().last().apiKey)
        val lostKey = Settings(context, ProviderCredentials(key = { null }))
        assertEquals("saved-model", lostKey.primary()?.model)
        assertTrue(lostKey.profiles().all { it.apiKey.isBlank() })
        // A connection to one service must not implicitly lend its key to a different service.
        assertTrue(settings.saveProfile(a.copy(id = "b", baseUrl = "https://gateway.example/v1")))
        assertEquals("", settings.cloudVoice("openai").apiKey)
        assertTrue(settings.deleteProfile("b"))
        assertFalse(prefs.contains("provider_secret.profile.b"))
        assertFalse(a.toJson().has("key"))
        assertFalse(a.toString().contains("same-secret"))
        assertFalse(a.toConfig().toString().contains("same-secret"))
    }
}
