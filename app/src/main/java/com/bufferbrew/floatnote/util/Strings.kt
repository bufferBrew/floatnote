package com.bufferbrew.floatnote.util

import android.content.Context
import androidx.annotation.StringRes

/**
 * User-facing text from resources, for classes that have no Context of their own (voice and AI
 * clients, the session). Unit tests pass one that reads the real strings.xml.
 */
interface Strings {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** Resource-backed [Strings]. Keep a reference to the application context only. */
fun Context.strings(): Strings {
    val app = applicationContext
    return object : Strings {
        override fun get(id: Int, vararg args: Any): String = app.getString(id, *args)
    }
}
