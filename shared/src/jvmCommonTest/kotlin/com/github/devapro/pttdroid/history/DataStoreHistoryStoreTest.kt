package com.github.devapro.pttdroid.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.devapro.pttdroid.audio.AudioConfig
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File

/**
 * [DataStoreHistoryStore] against the real preferences DataStore and the real `java.io` file
 * storage, in a temp directory — the two things the store is entirely about (JSON index
 * round-tripping through DataStore, and files whose lifetime follows their index entry) are
 * exactly the parts a fake would hide. The class's own semantics — FIFO trim, answered-record
 * purge, orphan sweep, corrupt-index recovery, monotonic seen-mark — are the assertions.
 */
class DataStoreHistoryStoreTest {

    private val tempRoot: File = createTempDirectory("history-store").toFile()
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { File(tempRoot, "index.preferences_pb").absolutePath.toPath() },
    )
    private val fileStorage = JvmHistoryFileStorage(File(tempRoot, "files").apply { mkdirs() })
    private val store = DataStoreHistoryStore(dataStore, fileStorage, FakeClock())

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun record(id: String, endedAtMs: Long = 1L): VoiceRecord = VoiceRecord(
        id = id,
        fileName = "$id.wav",
        startedAtMs = endedAtMs - 80,
        endedAtMs = endedAtMs,
        durationMs = 80,
        sizeBytes = AudioConfig.FRAME_BYTES.toLong(),
        senderName = "Alice",
    )

    private fun wav(): ByteArray =
        Wav.toWav(ByteArray(AudioConfig.FRAME_BYTES) { it.toByte() })

    @Test
    fun `a written record comes back identical, and its audio with it`() = runTest {
        val record = record("r1")
        val wav = wav()

        assertTrue(store.addRecord(record, wav))

        assertEquals(listOf(record), store.records.first())
        assertContentEquals(wav, store.wavOf(record))
        assertTrue(File(tempRoot, "files/r1.wav").isFile)
    }

    @Test
    fun `the index stays newest-first and the store stops at the retention cap`() = runTest {
        repeat(DataStoreHistoryStore.MAX_RECORDS + 3) { n ->
            assertTrue(store.addRecord(record("r$n"), wav()))
        }

        val kept = store.records.first()
        assertEquals(DataStoreHistoryStore.MAX_RECORDS, kept.size)
        assertEquals("r52", kept.first().id, "newest first")
        assertEquals("r3", kept.last().id, "the three oldest fell off the tail")
        assertNull(fileStorage.read("r0.wav"), "an evicted record's file goes with it")
        assertNotNull(fileStorage.read("r3.wav"))
    }

    @Test
    fun `deleteRecord takes the index entry and the file together`() = runTest {
        store.addRecord(record("r1"), wav())
        store.addRecord(record("r2"), wav())

        store.deleteRecord("r1")

        assertEquals(listOf("r2"), store.records.first().map { it.id })
        assertNull(fileStorage.read("r1.wav"))
    }

    @Test
    fun `deleteEndedAfter purges exactly the records that ended inside the window`() = runTest {
        store.addRecord(record("old", endedAtMs = 100), wav())
        store.addRecord(record("edge", endedAtMs = 200), wav())
        store.addRecord(record("fresh", endedAtMs = 300), wav())

        val removed = store.deleteEndedAfter(200)

        assertEquals(listOf("fresh", "edge"), removed.map { it.id })
        assertEquals(listOf("old"), store.records.first().map { it.id })
        assertNull(fileStorage.read("edge.wav"))
        assertNotNull(fileStorage.read("old.wav"))
    }

    @Test
    fun `the seen-mark only moves forward`() = runTest {
        assertEquals(0L, store.lastViewedAt.first())

        store.markAllSeen(500)
        store.markAllSeen(400)

        assertEquals(500L, store.lastViewedAt.first(), "a clock jump backwards cannot resurrect unread")

        store.markAllSeen(600)
        assertEquals(600L, store.lastViewedAt.first())
    }

    @Test
    fun `a corrupt index reads as empty and the next write heals it`() = runTest {
        store.addRecord(record("r1"), wav())
        // Same key the store uses internally (private on purpose); this simulates a torn write.
        dataStore.edit { it[stringPreferencesKey("history_index")] = "{ not json" }

        assertTrue(store.records.first().isEmpty())

        store.addRecord(record("r2"), wav())
        assertEquals(listOf("r2"), store.records.first().map { it.id })
    }

    @Test
    fun `pruneOrphans sweeps files the index no longer names`() = runTest {
        store.addRecord(record("keeper"), wav())
        fileStorage.write("ghost.wav", wav())

        store.pruneOrphans()

        assertNull(fileStorage.read("ghost.wav"))
        assertNotNull(fileStorage.read("keeper.wav"))
        assertEquals(1, store.records.first().size)
    }

    @Test
    fun `a failed file write stores nothing and reports the failure`() = runTest {
        // A plain file where the storage wants to keep its directory: every write into it fails.
        val blockedDir = File(tempRoot, "blocked").apply { writeText("in the way") }
        val blocked = DataStoreHistoryStore(
            dataStore,
            JvmHistoryFileStorage(blockedDir),
            FakeClock(),
        )

        assertFalse(blocked.addRecord(record("never"), wav()))

        assertFalse(store.records.first().any { it.id == "never" })
    }
}
