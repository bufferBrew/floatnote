package com.bufferbrew.floatnote.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSettingsTest {

    /** Reversible stand-in for the Keystore; [broken] simulates a key that can't be decrypted. */
    private class FakeCipher : SecretCipher {
        var broken = false
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(stored: String): String {
            check(!broken && stored.startsWith("enc:"))
            return stored.removePrefix("enc:").reversed()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = FakeCipher()
    private fun settings() = VoiceSettings(context, cipher)

    @Test
    fun `defaults are no auto-record, device engine without a key, OpenAI address`() {
        val s = settings()
        assertFalse(s.autoRecord)
        assertEquals(VoiceEngine.Device, s.engine)
        assertTrue(s.isReady)
        assertEquals(VoiceSettings.DEFAULT_BASE_URL, s.baseUrl)
        assertNull(s.config())
        assertNotNull(s.configProblem())
    }

    @Test
    fun `saving a key makes Whisper the default and builds a config, never storing plaintext`() {
        val s = settings()
        s.setApiKey("  sk-test  ")
        assertTrue(s.hasApiKey)
        assertEquals(VoiceEngine.Whisper, s.engine)
        assertEquals("sk-test", s.config()!!.apiKey)
        val raw = context.getSharedPreferences("voice_secret", Context.MODE_PRIVATE).getString("api_key", null)
        assertFalse(raw!!.contains("sk-test"))
        s.setApiKey("")
        assertFalse(s.hasApiKey)
    }

    @Test
    fun `a key that can't be decrypted counts as no key`() {
        val s = settings()
        s.setApiKey("sk-test")
        cipher.broken = true
        assertFalse(s.hasApiKey)
        assertNull(s.config())
    }

    @Test
    fun `http addresses are refused`() {
        val s = settings()
        s.setApiKey("sk-test")
        s.baseUrl = "http://api.example.com/v1"
        assertFalse(s.baseUrlValid)
        assertNull(s.config())
        assertTrue(s.configProblem()!!.contains("https://"))
    }

    @Test
    fun `a new host needs confirming before the key is sent there`() {
        val s = settings()
        s.setApiKey("sk-test")
        s.baseUrl = "https://api.groq.com/openai/v1"
        assertTrue(s.hostNeedsConfirm)
        assertEquals("api.openai.com", s.keyHost)
        assertNull(s.llmConfig())
        s.confirmHost()
        assertFalse(s.hostNeedsConfirm)
        assertEquals("https://api.groq.com/openai/v1", s.config()!!.baseUrl)
    }

    @Test
    fun `a key from before 2026-10-24 moves out of the backed-up file`() {
        context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
            .putString("api_key", cipher.encrypt("sk-old")).commit()
        val s = settings()
        assertEquals("sk-old", s.config()!!.apiKey)
        assertFalse(context.getSharedPreferences("voice", Context.MODE_PRIVATE).contains("api_key"))
    }

    @Test
    fun `a key with a stray character is refused and nothing is saved`() {
        val s = settings()
        assertFalse(s.setApiKey("sk-te\u200Bst"))
        assertFalse(s.setApiKey("sk test"))
        assertFalse(s.hasApiKey)
        assertTrue(s.setApiKey("gsk_Abc-123.xyz"))
        assertTrue(s.hasApiKey)
    }

    @Test
    fun `a bad key saved before keys were checked blocks the config instead of crashing later`() {
        val s = settings()
        context.getSharedPreferences("voice_secret", Context.MODE_PRIVATE).edit()
            .putString("api_key", cipher.encrypt("sk-\u201Cquoted\u201D")).commit()
        assertTrue(s.hasApiKey)
        assertNull(s.config())
        assertTrue(s.configProblem()!!.contains("Paste it again"))
    }
}
