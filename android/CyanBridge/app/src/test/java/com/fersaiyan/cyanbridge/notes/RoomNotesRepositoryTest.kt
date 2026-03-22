package com.fersaiyan.cyanbridge.notes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.ai.summarization.FakeSummarizationService
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomNotesRepositoryTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `transcript -summarize- db roundtrip stores formatted summary`() = runBlocking {
        val repo: NotesRepository = RoomNotesRepository(
            noteDao = db.noteDao(),
            summarizationService = FakeSummarizationService(fixedTitle = "Fixed"),
        )

        val transcript = "Alice: TODO finalize deck. We decided to ship on Friday?"

        val id = repo.createFromTranscript(
            transcript = transcript,
            hintTitle = "Sprint Sync",
            deviceClass = "GENERIC_AUDIO",
            durationSec = 120,
            tagsCsv = "sprint,planning",
            storeTranscript = true,
        )

        val note = repo.getNoteById(id)
        assertNotNull(note)
        assertEquals("Sprint Sync", note!!.title)
        assertEquals(transcript, note.transcript)

        val body = note.summary
        assertTrue(body.contains("# "))
        assertTrue(body.contains("## Summary"))
        assertTrue(body.contains("## Action items"))
        assertTrue(body.contains("## Key decisions"))
        assertTrue(body.contains("## Open questions"))
        assertTrue(body.contains("## Timeline highlights"))
    }
}
