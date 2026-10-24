package com.bufferbrew.floatnote.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Turns a recorded clip into text. */
interface Transcriber {
    suspend fun transcribe(audio: File, config: WhisperConfig): String
}

/** A transcription failure with a message fit to show the user. */
class TranscriptionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Calls an OpenAI-compatible `POST {baseUrl}/audio/transcriptions` (OpenAI, Groq, or a
 * self-hosted server). The key goes in the Authorization header only.
 */
class WhisperClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Hard ceiling for the whole request, so "Transcribing…" can never hang for minutes.
        .callTimeout(90, TimeUnit.SECONDS)
        .build(),
) : Transcriber {

    override suspend fun transcribe(audio: File, config: WhisperConfig): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("response_format", "json")
            .apply { if (config.language.isNotBlank()) addFormDataPart("language", config.language) }
            .addFormDataPart("file", audio.name, audio.asRequestBody(AUDIO_TYPE))
            .build()
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/audio/transcriptions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()

        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            val reason = if (e is InterruptedIOException) "The transcription service took too long." else
                "Couldn't reach the transcription service. Check your connection."
            throw TranscriptionException(reason, e)
        }
        return withContext(Dispatchers.IO) {
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw TranscriptionException(errorMessage(it.code, text))
                runCatching { JSONObject(text).getString("text").trim() }
                    .getOrElse { e -> throw TranscriptionException("Unexpected reply from the transcription service.", e) }
            }
        }
    }

    /** Runs the call on OkHttp's threads; cancelling the coroutine cancels the HTTP request. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // Cancelled while the reply was arriving: nobody will read it, so close it here.
                if (cont.isActive) cont.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
        val reason = when (code) {
            401 -> "The API key was rejected. Check it in Settings."
            404 -> "Transcription endpoint not found. Check the server address and model in Settings."
            413 -> "The recording is too long to upload."
            429 -> "Rate limit or quota reached on your API account."
            else -> "Transcription failed (HTTP $code)."
        }
        return if (detail != null && code != 401) "$reason $detail" else reason
    }

    private companion object {
        val AUDIO_TYPE = "audio/mp4".toMediaType()
    }
}
