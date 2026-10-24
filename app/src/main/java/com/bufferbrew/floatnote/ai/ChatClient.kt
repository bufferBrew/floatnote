package com.bufferbrew.floatnote.ai

import com.bufferbrew.floatnote.R
import com.bufferbrew.floatnote.net.await
import com.bufferbrew.floatnote.util.Strings
import com.bufferbrew.floatnote.voice.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** A failure from the AI step, with a message fit to show the user. [needsSettings]: fix it there. */
class AiException(
    message: String,
    cause: Throwable? = null,
    val needsSettings: Boolean = false,
) : IOException(message, cause)

/** One system + user message in, the model's reply out. */
interface ChatModel {
    suspend fun complete(system: String, user: String, config: LlmConfig, json: Boolean = false): String
}

/**
 * OpenAI-compatible `POST {baseUrl}/chat/completions` (OpenAI, Groq, or a local server).
 * The key goes in the Authorization header only, and nothing here is logged.
 */
class ChatClient(
    private val strings: Strings,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        // Hard ceiling, so "Organising…" can never hang.
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) : ChatModel {

    override suspend fun complete(system: String, user: String, config: LlmConfig, json: Boolean): String {
        val payload = JSONObject()
            .put("model", config.model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        if (json) payload.put("response_format", JSONObject().put("type", "json_object"))
        val url = (config.baseUrl.trimEnd('/') + "/chat/completions").toHttpUrlOrNull()
            ?: throw AiException(strings.get(R.string.err_invalid_url), needsSettings = true)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            val reason = strings.get(if (e is InterruptedIOException) R.string.err_ai_slow else R.string.err_ai_unreachable)
            throw AiException(reason, e)
        }
        return withContext(Dispatchers.IO) {
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw AiException(errorMessage(it.code, text), needsSettings = it.code == 401 || it.code == 404)
                runCatching {
                    JSONObject(text).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }.getOrElse { e -> throw AiException(strings.get(R.string.err_ai_bad_reply), e) }
            }
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        val reason = when (code) {
            401 -> strings.get(R.string.err_key_rejected)
            404 -> strings.get(R.string.err_ai_404)
            429 -> strings.get(R.string.err_rate_limit)
            else -> strings.get(R.string.err_ai_http, code)
        }
        return if (detail != null && code != 401) strings.get(R.string.err_with_detail, reason, detail) else reason
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}
