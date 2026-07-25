package io.github.revenge.xposed.tweaks.plugins

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ExtractPluginZipTest {
    private val root: File = Files.createTempDirectory("revenge-zip-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private val validManifest = """
        {
            "format": 1,
            "id": "com.example.zip",
            "name": "Zip Test",
            "version": "1.0.0",
            "dependencies": { "revenge.api": {}, "discord": {} }
        }
    """.trimIndent()

    @Test
    fun `a valid plugin ZIP extracts and parses`() {
        val staged = extractPluginZip(zipOf("manifest.json" to validManifest), root, ".test")

        assertEquals("com.example.zip", staged.manifest.id)
        assertTrue(File(staged.dir, "manifest.json").isFile)
    }

    @Test
    fun `a ZIP without a manifest fails with a clear message without extracting`() {
        val e = assertFailsWith<PluginException> {
            extractPluginZip(zipOf("readme.txt" to "hello"), root, ".test")
        }

        assertEquals(PluginErrorCodes.INSTALL_INVALID_ZIP, e.code)
        assertTrue("missing manifest.json" in (e.message ?: ""), "unexpected message: ${e.message}")
        assertTrue(!File(root, ".test").exists(), "tmp dir shouldn't exist")
    }

    @Test
    fun `an invalid manifest aborts before anything is extracted and leaves root clean`() {
        val e = assertFailsWith<PluginException> {
            extractPluginZip(zipOf("manifest.json" to "{}", "index.js" to "code"), root, ".test")
        }

        assertEquals(PluginErrorCodes.MANIFEST_INVALID, e.code)
        assertEquals(emptyList(), root.listFiles()?.map { it.name }.orEmpty(), "no tmp dir or spool left behind")
    }

    @Test
    fun `success leaves no spool file behind`() {
        extractPluginZip(zipOf("manifest.json" to validManifest), root, ".test")

        assertTrue(!File(root, ".test.zip").exists(), "spooled ZIP should be deleted")
    }
}
