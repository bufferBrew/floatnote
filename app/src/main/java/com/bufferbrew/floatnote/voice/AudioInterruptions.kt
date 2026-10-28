package com.bufferbrew.floatnote.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Tells a recording when something else needs the audio for a while: a call ringing, an alarm. */
interface AudioInterruptions {
    /** Starts watching. [onPause] runs when an interruption starts, [onResume] when it's over. */
    fun watch(onPause: () -> Unit, onResume: () -> Unit)

    fun unwatch()
}

/** For hosts and tests with nothing to watch. */
object NoInterruptions : AudioInterruptions {
    override fun watch(onPause: () -> Unit, onResume: () -> Unit) = Unit
    override fun unwatch() = Unit
}

/**
 * Holds transient audio focus while a clip records. A ringing call or an alarm takes the focus
 * for a while (AUDIOFOCUS_LOSS_TRANSIENT) and hands it back when it ends (AUDIOFOCUS_GAIN).
 * "May duck" asks music to play quieter rather than stop. A permanent loss (another app starts
 * playing media) doesn't pause: the user may well want to dictate over it.
 */
class AudioFocusInterruptions(context: Context) : AudioInterruptions {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun watch(onPause: () -> Unit, onResume: () -> Unit) {
        unwatch()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onPause()
                    AudioManager.AUDIOFOCUS_GAIN -> onResume()
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        // Refused during a call that's already going; recording goes ahead either way.
        audio.requestAudioFocus(focus)
        request = focus
    }

    override fun unwatch() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}
