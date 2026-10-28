package com.bufferbrew.floatnote.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.bufferbrew.floatnote.data.RoomNoteRepository
import com.bufferbrew.floatnote.data.RoomVoiceRepository
import com.bufferbrew.floatnote.domain.model.Block
import com.bufferbrew.floatnote.domain.model.Note
import com.bufferbrew.floatnote.domain.model.NoteCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Room on Robolectric's SQLite: migrations against the exported schemas, and the DAOs.
 * These are the user's notes, so every schema version bump adds a case here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FloatNoteDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, FloatNoteDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun tearDown() = db.close()

    @Test
    fun `migrating 1 to 2 keeps every note and block`() {
        helper.createDatabase(DB, 1).use { v1 ->
            v1.execSQL("INSERT INTO notes (id, title, taskMode, updatedAt) VALUES ('n1', 'Groceries', 'MoveToBottom', 5)")
            v1.execSQL("INSERT INTO blocks (id, noteId, position, type, text, checked) VALUES ('b1', 'n1', 0, 'Task', 'milk', 1)")
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { v2 ->
            v2.query("SELECT title FROM notes WHERE id = 'n1'").use {
                it.moveToFirst()
                assertEquals("Groceries", it.getString(0))
            }
            v2.query("SELECT text, checked FROM blocks WHERE noteId = 'n1'").use {
                it.moveToFirst()
                assertEquals("milk", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
            // The new tables exist and are empty.
            v2.query("SELECT COUNT(*) FROM dictations").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            v2.query("SELECT COUNT(*) FROM vocabulary").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test
    fun `migrating 2 to 3 adds pinned, off for existing notes`() {
        helper.createDatabase(DB, 2).use { v2 ->
            v2.execSQL("INSERT INTO notes (id, title, taskMode, updatedAt) VALUES ('n1', 'Old', 'KeepInPlace', 5)")
        }
        helper.runMigrationsAndValidate(DB, 3, true).use { v3 ->
            v3.query("SELECT title, pinned FROM notes WHERE id = 'n1'").use {
                it.moveToFirst()
                assertEquals("Old", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
        }
    }

    @Test
    fun `migrating 3 to 4 adds category, empty for existing notes`() {
        helper.createDatabase(DB, 3).use { v3 ->
            v3.execSQL("INSERT INTO notes (id, title, taskMode, updatedAt, pinned) VALUES ('n1', 'Old', 'KeepInPlace', 5, 1)")
        }
        helper.runMigrationsAndValidate(DB, 4, true).use { v4 ->
            v4.query("SELECT title, pinned, category FROM notes WHERE id = 'n1'").use {
                it.moveToFirst()
                assertEquals("Old", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertTrue(it.isNull(2))
            }
        }
    }

    @Test
    fun `migrating 4 to 5 adds audio, empty for existing dictations`() {
        helper.createDatabase(DB, 4).use { v4 ->
            v4.execSQL("INSERT INTO dictations (noteId, text, raw, createdAt) VALUES ('n1', 'hi', NULL, 5)")
        }
        helper.runMigrationsAndValidate(DB, 5, true).use { v5 ->
            v5.query("SELECT text, audio FROM dictations").use {
                it.moveToFirst()
                assertEquals("hi", it.getString(0))
                assertTrue(it.isNull(1))
            }
        }
    }

    @Test
    fun `a dictation's recording is deleted with it and with the history`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("rec").toFile()
        val store = com.bufferbrew.floatnote.data.RecordingStore(java.io.File(dir, "recordings"), { true })
        val repo = RoomVoiceRepository(db.voiceDao(), recordings = store)
        fun kept(name: String) = store.keep(java.io.File(dir, name).apply { writeBytes(byteArrayOf(1)) })
        repo.addDictation("n", "one", null, kept("a.m4a"))
        repo.addDictation("n", "two", null, kept("b.m4a"))
        val history = repo.observeHistory().first()
        assertEquals("b.m4a", history[0].audio)
        repo.deleteDictation(history[0].id)
        assertNull(store.file("b.m4a"))
        assertTrue(store.file("a.m4a") != null)
        repo.clearHistory()
        assertNull(store.file("a.m4a"))
    }

    @Test
    fun `category is saved, filters the list together with search, and lists related titles`() = runBlocking {
        var now = 0L
        val repo = RoomNoteRepository(db.noteDao(), clock = { ++now })
        repo.save(Note("a", title = "Standup", blocks = listOf(Block.Text("a1", "milk")), category = NoteCategory.Work))
        repo.save(Note("b", title = "Groceries", blocks = listOf(Block.Text("b1", "milk")), category = NoteCategory.Personal))
        repo.save(Note("c", title = "Review", category = NoteCategory.Work))
        repo.save(Note("d", title = "", category = NoteCategory.Work))
        repo.save(Note("e", title = "Loose"))
        assertEquals(NoteCategory.Work, repo.load("a")!!.category)
        assertNull(repo.load("e")!!.category)
        assertEquals(listOf("d", "c", "a"), repo.observeSummaries(category = NoteCategory.Work).first().map { it.id })
        assertEquals(listOf("a"), repo.observeSummaries("milk", NoteCategory.Work).first().map { it.id })
        assertEquals(5, repo.observeSummaries().first().size)
        assertEquals(NoteCategory.Personal, repo.observeSummaries("groceries").first().single().category)
        // Newest first, without the note itself or blank titles.
        assertEquals(listOf("Review"), repo.titlesIn(NoteCategory.Work, excludeId = "a", limit = 10))
        assertEquals(listOf("Review", "Standup"), repo.titlesIn(NoteCategory.Work, excludeId = "x", limit = 10))
        assertEquals(listOf("Review"), repo.titlesIn(NoteCategory.Work, excludeId = "x", limit = 1))
    }

    @Test
    fun `search matches title or any line, literally, and pinned notes come first`() = runBlocking {
        var now = 0L
        val repo = RoomNoteRepository(db.noteDao(), clock = { ++now })
        repo.save(Note("a", title = "Groceries", blocks = listOf(Block.Text("a1", "milk"))))
        repo.save(Note("b", title = "Work", blocks = listOf(Block.Task("b1", "50% done report"))))
        repo.save(Note("c", title = "Trip", blocks = listOf(Block.Text("c1", "passport"))))
        assertEquals(listOf("c", "b", "a"), repo.observeSummaries().first().map { it.id })
        assertEquals(listOf("a"), repo.observeSummaries("MILK").first().map { it.id })
        assertEquals(listOf("b"), repo.observeSummaries("50%").first().map { it.id })
        assertEquals(emptyList<String>(), repo.observeSummaries("5_").first().map { it.id })
        repo.setPinned("a", true)
        val ordered = repo.observeSummaries().first()
        assertEquals(listOf("a", "c", "b"), ordered.map { it.id })
        assertEquals(true, ordered.first().pinned)
        assertEquals(true, repo.load("a")!!.pinned)
        assertEquals(3, repo.all().size)
    }

    @Test
    fun `save round-trips a note, rewrites blocks, and summaries preview the first text`() = runBlocking {
        val repo = RoomNoteRepository(db.noteDao(), clock = { 100 })
        val note = Note(
            id = "n",
            title = "Trip",
            blocks = listOf(Block.Text("t", "  "), Block.Task("a", "book taxi"), Block.Checklist("c", "passport", true)),
        )
        repo.save(note)
        assertEquals(note, repo.load("n"))
        repo.save(note.copy(blocks = note.blocks.take(2)))
        assertEquals(2, repo.load("n")!!.blocks.size)
        val summary = repo.observeSummaries().first().single()
        assertEquals("book taxi", summary.preview)
        repo.delete("n")
        assertNull(repo.load("n"))
        assertEquals(emptyList<Any>(), db.noteDao().blocks("n"))
    }

    @Test
    fun `history is newest first, keeps raw only when different, and is capped`() = runBlocking {
        var now = 0L
        val repo = RoomVoiceRepository(db.voiceDao(), clock = { ++now })
        repo.addDictation("n", "Hello.", "hello")
        repo.addDictation("n", "same", "same")
        val history = repo.observeHistory().first()
        assertEquals(listOf("same", "Hello."), history.map { it.text })
        assertNull(history[0].raw)
        assertEquals("hello", history[1].raw)

        repeat(510) { repo.addDictation("n", "x$it", null) }
        assertEquals(500, repo.observeHistory().first().size)
        repo.clearHistory()
        assertEquals(0, repo.observeHistory().first().size)
    }

    @Test
    fun `vocabulary is trimmed and sorted case-insensitively`() = runBlocking {
        val repo = RoomVoiceRepository(db.voiceDao())
        repo.addVocab(" zeta ", "")
        repo.addVocab("Alpha", " A ")
        val words = repo.observeVocabulary().first()
        assertEquals(listOf("Alpha", "zeta"), words.map { it.term })
        assertEquals("A", words[0].replacement)
        repo.deleteVocab(words[0].id)
        assertEquals(listOf("zeta"), repo.observeVocabulary().first().map { it.term })
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
