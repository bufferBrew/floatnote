package com.bufferbrew.floatnote.net

import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.ai.AiException
import com.bufferbrew.floatnote.ai.ChatModel
import com.bufferbrew.floatnote.util.Strings
import com.bufferbrew.floatnote.voice.LlmConfig
import com.bufferbrew.floatnote.voice.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One line of a connection test. [ok] is null when it couldn't be told either way. */
data class CheckLine(val label: String, val ok: Boolean?, val detail: String)

/**
 * Settings' "Test connection": is the key accepted by the server, does the server offer the
 * speech model, and does the AI model answer. Uses `GET {baseUrl}/models` (OpenAI and Groq both
 * have it) and one tiny chat request. Nothing is recorded or saved, and the key is never shown.
 */
class ConnectionCheck(
    private val strings: Strings,
    private val chat: ChatModel,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    /** [config] null means the settings can't be used yet; [problem] says why. */
    suspend fun run(config: WhisperConfig?, problem: String?, aiModel: String): List<CheckLine> {
        val keyLabel = strings.get(R.string.check_key)
        if (config == null) return listOf(CheckLine(keyLabel, false, problem ?: strings.get(R.string.err_no_key)))
        val host = config.baseUrl.toHttpUrlOrNull()?.host.orEmpty()

        val models = when (val listed = listModels(config)) {
            is Listed.Failed -> return listOf(CheckLine(keyLabel, false, listed.reason))
            is Listed.Models -> listed.ids
            is Listed.Unknown -> null
        }
        val lines = mutableListOf(CheckLine(keyLabel, true, strings.get(R.string.check_key_ok, host)))

        val speechLabel = strings.get(R.string.check_speech_model, config.model)
        lines += when {
            models == null -> CheckLine(speechLabel, null, strings.get(R.string.check_model_unlisted))
            config.model in models -> CheckLine(speechLabel, true, strings.get(R.string.check_model_offered))
            else -> CheckLine(speechLabel, false, strings.get(R.string.check_model_missing, host))
        }

        val aiLabel = strings.get(R.string.check_ai_model, aiModel)
        lines += try {
            chat.complete(PING_SYSTEM, PING_USER, LlmConfig(config.apiKey, config.baseUrl, aiModel))
            CheckLine(aiLabel, true, strings.get(R.string.check_ai_ok))
        } catch (e: AiException) {
            CheckLine(aiLabel, false, e.message ?: strings.get(R.string.err_organise_failed))
        }
        return lines
    }

    private sealed interface Listed {
        data class Models(val ids: Set<String>) : Listed
        /** The server answered but has no usable model list; the key may still be fine. */
        data object Unknown : Listed
        data class Failed(val reason: String) : Listed
    }

    private suspend fun listModels(config: WhisperConfig): Listed {
        val url = (config.baseUrl.trimEnd('/') + "/models").toHttpUrlOrNull()
            ?: return Listed.Failed(strings.get(R.string.err_invalid_url))
        val request = try {
            Request.Builder().url(url).header("Authorization", "Bearer ${config.apiKey}").get().build()
        } catch (e: IllegalArgumentException) {
            return Listed.Failed(strings.get(R.string.err_key_bad_chars))
        }
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            return Listed.Failed(strings.get(R.string.check_unreachable, url.host))
        }
        return withContext(Dispatchers.IO) {
            response.use {
                when {
                    it.code == 401 || it.code == 403 -> Listed.Failed(strings.get(R.string.err_key_rejected))
                    !it.isSuccessful -> Listed.Unknown
                    else -> runCatching {
                        val data = JSONObject(it.body?.string().orEmpty()).getJSONArray("data")
                        Listed.Models((0 until data.length()).map { i -> data.getJSONObject(i).getString("id") }.toSet())
                    }.getOrDefault(Listed.Unknown)
                }
            }
        }
    }

    private companion object {
        const val PING_SYSTEM = "Reply with the single word OK."
        const val PING_USER = "ping"
    }
}
