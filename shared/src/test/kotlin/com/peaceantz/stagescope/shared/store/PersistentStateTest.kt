package com.peaceantz.stagescope.shared.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistentStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    data class Doc(val count: Int = 0, val items: List<String> = emptyList())

    private fun store(file: File, migrate: (Int, JsonElement) -> JsonElement = { _, d -> d }, version: Int = 1) =
        PersistentState(file, Doc.serializer(), version, { Doc() }, migrate = migrate)

    @Test
    fun `a value written by one instance is read back by a new one`() = runBlocking {
        val f = File(tmp.root, "doc.json")
        store(f).update { it.copy(count = 7, items = listOf("a", "b")) }
        assertEquals(Doc(7, listOf("a", "b")), store(f).value)
    }

    @Test
    fun `concurrent updates are serialized and none is lost`() = runBlocking {
        val s = store(File(tmp.root, "doc.json"))
        (1..200).map { async(kotlinx.coroutines.Dispatchers.Default) { s.update { it.copy(count = it.count + 1) } } }.awaitAll()
        assertEquals(200, s.value.count)
        assertEquals(200, store(File(tmp.root, "doc.json")).value.count)
    }

    @Test
    fun `a write leaves no temp file behind and the previous content survives a failed write attempt`() = runBlocking {
        val f = File(tmp.root, "doc.json")
        val s = store(f)
        s.update { it.copy(count = 1) }
        assertFalse(File(f.absolutePath + ".tmp").exists())
        // Simulate a crash mid-write: a stale temp file with garbage must not affect the real file.
        File(f.absolutePath + ".tmp").writeText("{ half-written")
        assertEquals(1, store(f).value.count)
    }

    @Test
    fun `a corrupt file is quarantined, not overwritten, and the store starts from the default`() = runBlocking {
        val f = File(tmp.root, "doc.json")
        f.writeText("{ this is not json")
        val s = store(f)
        assertEquals(Doc(), s.value)
        assertTrue(File(f.absolutePath + ".corrupt-0").exists())
        assertEquals("{ this is not json", File(f.absolutePath + ".corrupt-0").readText())
        s.update { it.copy(count = 3) }
        assertEquals(3, store(f).value.count)
    }

    @Test
    fun `a legacy unwrapped file is adopted, then migrated forward on read`() = runBlocking {
        val f = File(tmp.root, "doc.json")
        f.writeText("""{"count":5,"items":["x"],"someOldField":1}""")
        assertEquals(Doc(5, listOf("x")), store(f).value)

        val g = File(tmp.root, "versioned.json")
        g.writeText("""{"schemaVersion":1,"data":{"count":2,"oldName":"ignored"}}""")
        val migrated = store(g, version = 2, migrate = { from, data ->
            assertEquals(1, from)
            JsonObject((data as JsonObject) + ("items" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("migrated")))))
        })
        assertEquals(Doc(2, listOf("migrated")), migrated.value)
    }

    @Test
    fun `an unchanged update does not rewrite the file`() = runBlocking {
        val f = File(tmp.root, "doc.json")
        val s = store(f)
        s.update { it.copy(count = 1) }
        val before = f.lastModified()
        Thread.sleep(20)
        s.update { it }
        assertEquals(before, f.lastModified())
    }
}
