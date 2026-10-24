package com.bufferbrew.floatnote.util

import com.bufferbrew.floatnote.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Strings] for plain JVM tests, read from the app's real strings.xml, so tests see the same
 * text users do. Formats like Android's getString (String.format).
 */
object TestStrings : Strings {
    private val byName: Map<String, String> by lazy {
        val file = File("src/main/res/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to unescape(node.textContent)
        }
    }

    private val nameById: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    override fun get(id: Int, vararg args: Any): String {
        val name = nameById[id] ?: error("No R.string with id $id")
        val text = byName[name] ?: error("No <string name=\"$name\"> in strings.xml")
        return if (args.isEmpty()) text else String.format(text, *args)
    }

    private fun unescape(raw: String) = raw.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}
