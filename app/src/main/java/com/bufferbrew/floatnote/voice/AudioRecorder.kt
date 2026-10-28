package com.bufferbrew.floatnote.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records one clip at a time to a file. */
interface AudioRecorder {
    /**
     * Starts recording into [output]. Throws if the mic can't be opened. When a file nears
     * [segmentBytes], recording carries on, without a gap, in the file [next] returns.
     */
    fun start(output: File, segmentBytes: Long, next: () -> File)

    /** Loudness since the last call, 0f (silence) to 1f (peak). */
    fun level(): Float

    /** Pauses and resumes the clip in place; the gap isn't in the file. */
    fun pause()

    fun resume()

    /**
     * Stops and finalises. The files recorded, in order; empty if the clip is unusable (stopped
     * instantly). Unusable files are deleted.
     */
    fun stop(): List<File>
}

/**
 * AAC in an MP4 container (.m4a): small, and accepted by the Whisper API. 16 kHz mono is
 * what Whisper works at internally, so a higher rate would only make uploads bigger.
 */
class MediaAudioRecorder(private val context: Context) : AudioRecorder {
    private var recorder: MediaRecorder? = null

    /** Files recording has started in, in order. */
    private val files = mutableListOf<File>()

    /** Handed to the recorder to switch to, not started yet. */
    private var upcoming: File? = null

    /** The recorder stopped itself at the size limit (switching to the next file failed). */
    private var reachedLimit = false

    override fun start(output: File, segmentBytes: Long, next: () -> File) {
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
            if (segmentBytes > 0) r.setMaxFileSize(segmentBytes)
            // Called on this (main) thread. Near the limit, hand over the next file; the recorder
            // switches to it at a frame boundary, so nothing is lost between the two.
            r.setOnInfoListener { mr, what, _ ->
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                        val file = next()
                        if (runCatching { mr.setNextOutputFile(file) }.isSuccess) upcoming = file else file.delete()
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                        upcoming?.let { files += it }
                        upcoming = null
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> reachedLimit = true
                }
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        files.clear()
        files += output
        upcoming = null
        reachedLimit = false
        recorder = r
    }

    override fun level(): Float =
        ((recorder?.maxAmplitude ?: 0) / MAX_AMPLITUDE).coerceIn(0f, 1f)

    override fun pause() {
        recorder?.pause()
    }

    override fun resume() {
        recorder?.resume()
    }

    override fun stop(): List<File> {
        val r = recorder ?: return emptyList()
        recorder = null
        // stop() throws if no audio was captured yet (stopped right after start, or right after
        // switching files): then the last file is unusable. After the size limit it's already final.
        val ok = runCatching { r.stop() }.isSuccess || reachedLimit
        r.release()
        upcoming?.delete()
        upcoming = null
        val recorded = files.toList()
        files.clear()
        if (ok) return recorded
        recorded.lastOrNull()?.delete()
        return recorded.dropLast(1)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 48_000
        const val MAX_AMPLITUDE = 32_767f
    }
}
