package io.github.revenge.xposed.tweaks.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginMethodResultTest {
    @Test
    fun `success wraps the value under result`() {
        val payload = listOf("a", "b").toJSPayload()

        assertEquals(listOf("a", "b"), payload["result"])
        assertTrue("error" !in payload)
    }

    @Test
    fun `null is a valid result, not an error`() {
        val payload = null.toJSPayload()

        assertTrue("result" in payload)
        assertNull(payload["result"])
        assertTrue("error" !in payload)
    }

    @Test
    fun `PluginException keeps its code and message`() {
        val payload = PluginSystemError(PluginErrorCodes.NOT_FOUND, "Unknown plugin 'a.b'")
            .toJSPayload()

        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any?>
        assertEquals(PluginErrorCodes.NOT_FOUND, error["code"])
        assertEquals("Unknown plugin 'a.b'", error["message"])
        // The stack is carried separately so `message` stays showable on its own.
        assertTrue((error["stack"] as String).contains("Unknown plugin"))
        assertTrue("result" !in payload)
    }

    @Test
    fun `details ride along for structured extras`() {
        val payload = PluginSystemError(
            PluginErrorCodes.DEPENDENCIES_UNSATISFIED,
            "Unsatisfied dependencies",
            details = mapOf("problems" to listOf(mapOf("id" to "a.b"))),
        ).toJSPayload()

        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val details = error["details"] as Map<String, Any?>
        assertEquals(listOf(mapOf("id" to "a.b")), details["problems"])
    }

    @Test
    fun `untyped throwables get a fallback code, never a bare stack`() {
        val payload = IllegalStateException("something broke").toJSPayload()

        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any?>
        assertEquals(PluginErrorCodes.UNKNOWN, error["code"])
        assertEquals("something broke", error["message"])
    }

    @Test
    fun `bad arguments map to INVALID_ARGUMENT`() {
        val payload = IllegalArgumentException("Expected a string").toJSPayload()

        @Suppress("UNCHECKED_CAST")
        val error = payload["error"] as Map<String, Any?>
        assertEquals(PluginErrorCodes.INVALID_ARGUMENT, error["code"])
    }

    @Test
    fun `details are omitted when absent so event payloads keep their shape`() {
        val payload = PluginError(PluginErrorCodes.LOAD_FAILED, "boom").toJSPayload()

        assertTrue("details" !in payload)
        assertTrue("stack" in payload)
    }
}
