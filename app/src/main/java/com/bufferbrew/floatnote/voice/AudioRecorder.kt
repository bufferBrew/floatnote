package com.bufferbrew.floatnote.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records one clip at a time to a file. */
interface AudioRecorder {
    /** Starts recording into [output]. Throws if the mic can't be opened. */
    fun start(output: File)

    /** Loudness since the last call, 0f (silence) to 1f (peak). */
    fun level(): Float

    /** Stops and finalises the file. False if the clip is unusable (e.g. stopped instantly). */
    fun stop(): Boolean
}

/**
 * AAC in an MP4 container (.m4a): small, and accepted by the Whisper API. 16 kHz mono is
 * what Whisper works at internally, so a higher rate would only make uploads bigger.
 */
class MediaAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    override fun start(output: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setOutputFile(output.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    override fun level(): Float =
        ((recorder?.maxAmplitude ?: 0) / MAX_AMPLITUDE).coerceIn(0f, 1f)

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        // stop() throws if no audio was captured yet (stopped right after start).
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return ok
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 48_000
        const val MAX_AMPLITUDE = 32_767f
    }
}
