package com.bufferbrew.floatnote.voice

/** Naming and limits for audio files shared to FloatNote to be transcribed. Pure, so it's tested. */
object SharedAudio {
    /** Files taken from one share; the rest are left out. */
    const val MAX_FILES = 20

    /** A file bigger than this isn't copied (OpenAI's own limit is 25 MB; others allow more). */
    const val MAX_BYTES = 100L * 1024 * 1024

    /** What OpenAI's and Groq's `/audio/transcriptions` accept, going by the file's extension. */
    private val ACCEPTED = setOf("flac", "m4a", "mp3", "mp4", "mpeg", "mpga", "oga", "ogg", "wav", "webm")

    private val BY_TYPE = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/ogg" to "ogg",
        "audio/opus" to "ogg",
        "application/ogg" to "ogg",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/webm" to "webm",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
    )

    /**
     * The extension to save a shared file under, which is what the server reads the format from.
     * WhatsApp's voice notes are Ogg Opus named `.opus`, which servers only take as `.ogg`.
     * An unknown format keeps its own extension, so the server's error says what's wrong.
     */
    fun extension(displayName: String?, mimeType: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (fromName == "opus") return "ogg"
        if (fromName in ACCEPTED) return fromName
        BY_TYPE[mimeType?.lowercase()]?.let { return it }
        // It becomes part of a file name: letters and digits only.
        return fromName.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "m4a"
    }

    /** A note title from the shared file's name, without its extension; null if there's nothing usable. */
    fun title(displayName: String?): String? =
        displayName?.substringBeforeLast('.')?.replace('_', ' ')?.trim()?.take(80)?.takeIf { it.isNotBlank() }
}
