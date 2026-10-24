package com.bufferbrew.floatnote.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the Whisper client needs for one request. */
data class WhisperConfig(
    val apiKey: String,
    /** OpenAI-compatible base, e.g. https://api.openai.com/v1 or https://api.groq.com/openai/v1 */
    val baseUrl: String,
    val model: String,
    /** ISO-639-1 hint such as "en" or "de"; blank lets Whisper detect it. */
    val language: String,
    /** Vocabulary hint sent as Whisper's `prompt`; blank for none. */
    val prompt: String = "",
)

/** What the AI step (clean-up, Organise) needs: same key and server as Whisper. */
data class LlmConfig(
    val apiKey: String,
    val baseUrl: String,
    /** A chat model, e.g. gpt-4o-mini (OpenAI) or llama-3.3-70b-versatile (Groq). */
    val model: String,
)

/** Where speech becomes text. */
enum class VoiceEngine {
    /** Record, then upload to a Whisper-compatible API. Needs a key and a connection. */
    Whisper,

    /** Android's speech recognizer, offline when the phone has a model. Text appears live. */
    Device,
}

/**
 * Voice settings. The API key is encrypted with an AES key that never leaves the Android
 * Keystore; only the ciphertext is stored, in its own file (`voice_secret`) that's excluded from
 * backups, since a restored copy could never be decrypted. Never log the key or put it in a URL.
 *
 * The key is tied to the server it was saved for ([keyHost]): if the server address later points
 * somewhere else, the key isn't sent until the user confirms that host in Settings.
 */
class VoiceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val secret = context.getSharedPreferences("voice_secret", Context.MODE_PRIVATE)

    init {
        // Before 2026-10-24 the key lived in "voice", which is backed up.
        prefs.getString(KEY_API_KEY, null)?.let { old ->
            secret.edit().putString(KEY_API_KEY, old).putString(KEY_KEY_HOST, hostOf(baseUrl)).apply()
            prefs.edit().remove(KEY_API_KEY).apply()
        }
    }

    /** Off by default: recording starts when you tap Record. */
    var autoRecord: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** Until chosen: Whisper if a key has been saved, otherwise the device recognizer. */
    var engine: VoiceEngine
        get() = prefs.getString(KEY_ENGINE, null)
            ?.let { name -> VoiceEngine.entries.firstOrNull { it.name == name } }
            ?: if (hasApiKey) VoiceEngine.Whisper else VoiceEngine.Device
        set(value) = prefs.edit().putString(KEY_ENGINE, value.name).apply()

    /** Whether recording can work without first visiting Settings. */
    val isReady: Boolean get() = engine == VoiceEngine.Device || hasApiKey

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.trim()).apply()

    /** Rewrite each dictation with the AI model (fillers out, punctuation fixed) before it's added. */
    var aiCleanup: Boolean
        get() = prefs.getBoolean(KEY_AI_CLEANUP, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_CLEANUP, value).apply()

    var aiModel: String
        get() = prefs.getString(KEY_AI_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AI_MODEL
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value.trim()).apply()

    /** A key is saved and can still be decrypted (not, say, a copy restored onto a new phone). */
    val hasApiKey: Boolean get() = storedKey() != null

    /** The server address uses https. Anything else would send the key in the clear. */
    val baseUrlValid: Boolean get() = baseUrl.toHttpUrlOrNull()?.isHttps == true

    /** The host the key was saved for, or null if there is no key. */
    val keyHost: String? get() = if (hasApiKey) secret.getString(KEY_KEY_HOST, null) ?: hostOf(baseUrl) else null

    /** The server address points somewhere other than where the key was saved for. */
    val hostNeedsConfirm: Boolean get() = keyHost != null && hostOf(baseUrl) != keyHost

    /** The user agrees to send the saved key to the current server. */
    fun confirmHost() {
        secret.edit().putString(KEY_KEY_HOST, hostOf(baseUrl)).apply()
    }

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            secret.edit().remove(KEY_API_KEY).remove(KEY_KEY_HOST).apply()
            return
        }
        secret.edit().putString(KEY_API_KEY, encrypt(trimmed)).putString(KEY_KEY_HOST, hostOf(baseUrl)).apply()
    }

    /**
     * Null when there's no usable key, the address isn't https, or the address changed host and
     * the user hasn't confirmed it yet. [configProblem] says which, for error messages.
     */
    fun config(): WhisperConfig? {
        if (configProblem() != null) return null
        val key = storedKey() ?: return null
        return WhisperConfig(apiKey = key, baseUrl = baseUrl, model = model, language = language)
    }

    /** Why [config] is null, worded for the user; null when it isn't. */
    fun configProblem(): String? = when {
        !hasApiKey -> "Add your API key in Settings, or switch to device speech recognition."
        !baseUrlValid -> "The server address in Settings must start with https://"
        hostNeedsConfirm -> "The server address changed. Confirm in Settings that your key may be sent there."
        else -> null
    }

    private fun storedKey(): String? {
        val stored = secret.getString(KEY_API_KEY, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    private fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    /** Null under the same conditions as [config]: AI features then explain via [configProblem]. */
    fun llmConfig(): LlmConfig? = config()?.let { LlmConfig(it.apiKey, it.baseUrl, aiModel) }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** Stored as base64(iv) + ":" + base64(ciphertext). */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, sealed) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "whisper-1"
        const val DEFAULT_AI_MODEL = "gpt-4o-mini"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "floatnote_whisper_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        // "auto_record" was on by default until 2026-10-24; a new key turns it off for everyone.
        private const val KEY_AUTO = "auto_record_opt_in"
        private const val KEY_ENGINE = "engine"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_KEY_HOST = "key_host"
        private const val KEY_AI_CLEANUP = "ai_cleanup"
        private const val KEY_AI_MODEL = "ai_model"
    }
}
