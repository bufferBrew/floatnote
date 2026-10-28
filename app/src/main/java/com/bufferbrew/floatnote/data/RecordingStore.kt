package com.bufferbrew.floatnote.data

import java.io.File

/**
 * Audio kept after transcription, so History can play it back or transcribe it again. Files
 * live in [dir] (app storage, left out of backups) under their clip names, and are trimmed to
 * [maxBytes], oldest first.
 */
class RecordingStore(
    private val dir: File,
    /** Off: clips are deleted once transcribed, as before. */
    private val enabled: () -> Boolean,
    private val maxBytes: Long = 300L * 1024 * 1024,
) {
    /** Moves [clip] in and returns its name, or deletes it and returns null when recordings aren't kept. */
    fun keep(clip: File?): String? {
        if (clip == null || !clip.isFile) return null
        if (!enabled()) {
            clip.delete()
            return null
        }
        dir.mkdirs()
        val target = File(dir, clip.name)
        // The cache and app storage are on the same disk, so this is normally a rename.
        val moved = clip.renameTo(target) || runCatching { clip.copyTo(target, overwrite = true); clip.delete() }.isSuccess
        return if (moved) target.name else null
    }

    /** The kept file for [name], if it's still there. */
    fun file(name: String?): File? = name?.let { File(dir, it) }?.takeIf { it.isFile && it.parentFile == dir }

    fun delete(name: String?) {
        file(name)?.delete()
    }

    /** Deletes files no dictation points at, then the oldest until the total fits [maxBytes]. */
    fun prune(referenced: Set<String>) {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        files.filter { it.name !in referenced }.forEach { it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        for (file in files.filter { it.exists() }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** Bytes used, for Settings. */
    fun size(): Long = dir.listFiles().orEmpty().sumOf { it.length() }
}
