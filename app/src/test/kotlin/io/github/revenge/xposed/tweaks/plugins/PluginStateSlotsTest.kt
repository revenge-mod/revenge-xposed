package io.github.revenge.xposed.tweaks.plugins

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Slot model: the boot slot is what runs, the active slot is what the user edits.
 */
class PluginStateSlotsTest {
    private val dataDir: File = Files.createTempDirectory("revenge-slots-test").toFile()
    private val statesDir = File(dataDir, "files/revenge/plugins/states")
    private val slotsDir = File(statesDir, "slots")

    @BeforeTest
    fun reset() = PluginStatesStore.resetForTests()

    @AfterTest
    fun cleanup() {
        PluginStatesStore.resetForTests()
        dataDir.deleteRecursively()
    }

    private fun load() = PluginStatesStore.ensureLoaded(dataDir.absolutePath)

    private fun bootIntoDefaults() {
        PluginStatesStore.setActiveSlot(dataDir.absolutePath, PluginStatesStore.DEFAULTS_SLOT, oneShot = true)
        PluginStatesStore.resetForTests()
        load()
    }

    @Test
    fun `normal boot runs the slot the user chose`() {
        load()

        assertEquals(PluginStatesStore.PRIMARY_SLOT, PluginStatesStore.activeSlotId)
        assertEquals(PluginStatesStore.PRIMARY_SLOT, PluginStatesStore.bootSlotId)
        assertFalse(PluginStatesStore.isDefaultsBoot)
        // Same object, so a session write is a saved write with no branch anywhere
        assertSame(PluginStatesStore.active, PluginStatesStore.boot)
    }

    @Test
    fun `a flow is not an entry`() {
        load()
        val slot = PluginStatesStore.active

        slot.flagsOf("com.example.plugin")
        assertFalse(slot.hasPlugin("com.example.plugin"), "reading must not give the setup an opinion")

        slot.write("com.example.plugin", emptySet())
        assertTrue(slot.hasPlugin("com.example.plugin"), "an empty entry still means explicitly off")
    }

    @Test
    fun `writes reach the live flow`() {
        load()
        val slot = PluginStatesStore.active
        val flow = slot.flagsOf("com.example.plugin")

        slot.write("com.example.plugin", setOf(PluginFlags.ENABLED))

        assertEquals(setOf(PluginFlags.ENABLED), flow.value, "a running plugin sees a slot write")
    }

    @Test
    fun `a persistent slot round-trips through its file`() {
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED, PluginFlags.REQUIRED_BY_USER))

        assertTrue(File(slotsDir, PluginStatesStore.PRIMARY_SLOT).exists())

        PluginStatesStore.resetForTests()
        load()

        assertEquals(
            setOf(PluginFlags.ENABLED, PluginFlags.REQUIRED_BY_USER),
            PluginStatesStore.active.entryFlags("com.example.plugin"),
        )
    }

    @Test
    fun `bit-less flags live in a slot but never on disk`() {
        load()
        PluginStatesStore.active.write(
            "com.example.plugin",
            setOf(PluginFlags.ENABLED, PluginFlags.PENDING_RELOAD, PluginFlags.STARTED_LATE),
        )

        assertTrue(PluginFlags.PENDING_RELOAD in PluginStatesStore.active.entryFlags("com.example.plugin")!!)

        PluginStatesStore.resetForTests()
        load()

        assertEquals(
            setOf(PluginFlags.ENABLED),
            PluginStatesStore.active.entryFlags("com.example.plugin"),
            "flags with no bit cannot survive serialization",
        )
    }

    @Test
    fun `defaults boot runs an ephemeral slot and leaves the chosen one alone`() {
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED))

        bootIntoDefaults()

        assertTrue(PluginStatesStore.isDefaultsBoot)
        assertEquals(PluginStatesStore.PRIMARY_SLOT, PluginStatesStore.activeSlotId)
        assertEquals(PluginStatesStore.DEFAULTS_SLOT, PluginStatesStore.bootSlotId)

        // The session starts from nothing, so every plugin falls back to its defaults
        assertFalse(PluginStatesStore.boot.hasPlugin("com.example.plugin"))
        assertFalse(PluginStatesStore.boot.isPluginEnabled("com.example.plugin"))

        // The user's setup is intact and still editable
        assertTrue(PluginStatesStore.active.isPluginEnabled("com.example.plugin"))
    }

    @Test
    fun `a session write during a defaults boot cannot reach disk`() {
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED))
        bootIntoDefaults()

        PluginStatesStore.boot.write("com.example.plugin", setOf(PluginFlags.PENDING_RELOAD))

        PluginStatesStore.resetForTests()
        load()

        assertEquals(
            setOf(PluginFlags.ENABLED),
            PluginStatesStore.active.entryFlags("com.example.plugin"),
            "the setup the user is repairing must survive the session",
        )
    }

    @Test
    fun `the user's edits during a defaults boot stick`() {
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED))
        bootIntoDefaults()

        PluginStatesStore.active.write("com.example.plugin", emptySet())

        PluginStatesStore.resetForTests()
        load()

        assertFalse(PluginStatesStore.active.isPluginEnabled("com.example.plugin"))
    }

    @Test
    fun `a one-shot is consumed before any plugin loads`() {
        PluginStatesStore.setActiveSlot(dataDir.absolutePath, PluginStatesStore.DEFAULTS_SLOT, oneShot = true)
        assertTrue(File(statesDir, "next").exists())

        PluginStatesStore.resetForTests()
        load()
        assertEquals(PluginStatesStore.DEFAULTS_SLOT, PluginStatesStore.bootSlotId)
        assertFalse(File(statesDir, "next").exists(), "deleted before any plugin loads")

        // Next boot is normal again, so a crash loop cannot trap the user
        PluginStatesStore.resetForTests()
        load()
        assertEquals(PluginStatesStore.PRIMARY_SLOT, PluginStatesStore.bootSlotId)
    }

    @Test
    fun `slot ids that escape the directory are rejected`() {
        for (id in listOf("../evil", "a/b", ".hidden", "")) {
            assertFailsWith<IllegalArgumentException>("expected '$id' to be rejected") {
                requireValidSlotId(id)
            }
        }

        requireValidSlotId("primary")
        requireValidSlotId(PluginStatesStore.DEFAULTS_SLOT)
    }

    @Test
    fun `the active slot survives a reload`() {
        Files.createDirectories(statesDir.toPath())
        PluginStatesStore.setActiveSlot(dataDir.absolutePath, "work", oneShot = false)

        PluginStatesStore.resetForTests()
        load()

        assertEquals("work", PluginStatesStore.activeSlotId)
        assertEquals("work", PluginStatesStore.bootSlotId)
    }

    @Test
    fun `uninstall drops the plugin from a slot this boot never loaded`() {
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED))
        PluginStatesStore.resetForTests()

        // A second setup the user isn't in, so nothing loads it this boot.
        PluginStatesStore.setActiveSlot(dataDir.absolutePath, "work", oneShot = false)
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED))

        PluginStatesStore.removePluginFromAllSlots(dataDir.absolutePath, "com.example.plugin")

        PluginStatesStore.resetForTests()
        PluginStatesStore.setActiveSlot(dataDir.absolutePath, PluginStatesStore.PRIMARY_SLOT, oneShot = false)
        load()

        assertFalse(
            PluginStatesStore.active.hasPlugin("com.example.plugin"),
            "a leftover entry would resurrect the plugin's setup on reinstall",
        )
    }

    @Test
    fun `read sends every loaded slot`() {
        load()
        PluginStatesStore.active.write("com.example.plugin", setOf(PluginFlags.ENABLED))
        bootIntoDefaults()

        val payload = PluginStatesStore.toJSPayload()

        @Suppress("UNCHECKED_CAST")
        val primary = payload[PluginStatesStore.PRIMARY_SLOT] as Map<String, Map<String, Any>>
        assertEquals(true, primary["com.example.plugin"]?.get("enabled"))

        @Suppress("UNCHECKED_CAST")
        val defaults = payload[PluginStatesStore.DEFAULTS_SLOT] as Map<String, Any>
        assertTrue(defaults.isEmpty(), "JS must agree with native or it runs plugins native skipped")
    }
}
