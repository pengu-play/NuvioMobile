package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamNdjsonTest {

    @Test
    fun `isNdjsonContentType matches x-ndjson with and without charset`() {
        assertTrue(isNdjsonContentType("application/x-ndjson"))
        assertTrue(isNdjsonContentType("application/x-ndjson; charset=utf-8"))
        assertTrue(isNdjsonContentType("APPLICATION/X-NDJSON"))
    }

    @Test
    fun `isNdjsonContentType rejects other content types`() {
        assertFalse(isNdjsonContentType("application/json"))
        assertFalse(isNdjsonContentType("text/event-stream"))
        assertFalse(isNdjsonContentType("application/x-ndjsonx"))
        assertFalse(isNdjsonContentType(null))
    }

    @Test
    fun `parseNdjsonBatch decodes each line independently`() {
        val line1 = """{"streams":[{"name":"A","url":"https://a.example/1"}]}"""
        val line2 = """{"streams":[{"name":"B","url":"https://b.example/2"}]}"""

        val batch1 = StreamParser.parseNdjsonBatch(line1, "Addon", "addon.id")
        val batch2 = StreamParser.parseNdjsonBatch(line2, "Addon", "addon.id")

        assertEquals(listOf("A"), batch1.map { it.name })
        assertEquals(listOf("B"), batch2.map { it.name })
    }

    @Test
    fun `parseNdjsonBatch tolerates blank and malformed lines`() {
        assertEquals(emptyList(), StreamParser.parseNdjsonBatch("", "Addon", "addon.id"))
        assertEquals(emptyList(), StreamParser.parseNdjsonBatch("   ", "Addon", "addon.id"))
        assertEquals(emptyList(), StreamParser.parseNdjsonBatch("not json", "Addon", "addon.id"))
        assertEquals(emptyList(), StreamParser.parseNdjsonBatch("""{"streams":[]}""", "Addon", "addon.id"))
        assertEquals(emptyList(), StreamParser.parseNdjsonBatch("""{"other":1}""", "Addon", "addon.id"))
    }

    @Test
    fun `parseNdjsonBatch propagates addon attribution`() {
        val stream = StreamParser.parseNdjsonBatch(
            payload = """{"streams":[{"url":"https://x.example/s"}]}""",
            addonName = "My Addon",
            addonId = "addon:xyz",
            addonLogo = "https://logo.example/l.png",
        ).single()

        assertEquals("My Addon", stream.addonName)
        assertEquals("addon:xyz", stream.addonId)
        assertEquals("https://logo.example/l.png", stream.addonLogo)
        assertEquals("https://x.example/s", stream.url)
    }

    @Test
    fun `parseNdjsonBatch skips entries without any source or external target`() {
        val batch = StreamParser.parseNdjsonBatch(
            payload = """{"streams":[{"name":"no-target"},{"url":"https://x.example/s"}]}""",
            addonName = "Addon",
            addonId = "addon.id",
        )

        assertEquals(1, batch.size)
        assertEquals("https://x.example/s", batch.single().url)
    }
}
