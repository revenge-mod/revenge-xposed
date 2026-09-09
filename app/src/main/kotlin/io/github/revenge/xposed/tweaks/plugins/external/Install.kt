package io.github.revenge.xposed.tweaks.plugins.external

import android.app.Activity
import android.content.Intent
import io.github.revenge.plugins.Version
import io.github.revenge.xposed.*
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.tweaks.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.zip.ZipFile

private const val PICK_ZIP_REQUEST_CODE = 0x5256

/** Prefix of the temp directory holding a staged install until the user answers. */
private const val CONFIRM_TMP_PREFIX = ".confirm-"

/** Largest decompressed size of a plugin ZIP (1 GiB). */
private const val MAX_EXTRACTED_ZIP_BYTES: Long = 1L shl 30

@Volatile
private var pickZipHooked = false

@Volatile
private var pendingPick: ((Activity, android.net.Uri) -> Unit)? = null

/** An extracted and validated plugin ZIP that hasn't replaced the existing yet. */
internal class StagedPlugin(val dir: File, val manifest: ExternalManifest)

internal class InstallPrompt(
    val token: String,
    val manifest: ExternalManifest,
    /** The installed version this would replace. */
    val replaces: Version?,
)

internal sealed class InstallResult {
    /** A new plugin can load right away. */
    class New(val factory: PluginFactory) : InstallResult()

    /** An update, files are on disk. The new code loads next session. */
    class Updated(val manifest: ExternalManifest, val version: Version) : InstallResult()
}

/** Staged sideload installs, keyed by single-use token. At most one at a time. */
private val pendingInstalls = mutableMapOf<String, StagedPlugin>()

/**
 * Opens the document picker for a plugin ZIP, extracts and validates it, then runs [onReady].
 *
 * The callback sends the confirmation event to JS, which calls [confirmPluginInstall] to apply or discard.
 * A new pick discards the previous staged install if there is one.
 */
context(host: HostScope)
internal fun promptInstallPlugin(
    installedVersion: (String) -> Version?,
    onReady: (Result<InstallPrompt>) -> Unit,
) {
    if (!pickZipHooked) {
        pickZipHooked = true

        Activity::class.java.method(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java,
        ).hook {
            after {
                if (args[0] as Int != PICK_ZIP_REQUEST_CODE) return@after

                val callback = pendingPick ?: return@after
                pendingPick = null

                val uri = (args[2] as? Intent)?.data
                if (args[1] as Int != Activity.RESULT_OK || uri == null) return@after

                callback(thisObject as Activity, uri)
            }
        }
    }

    pendingPick = { activity, uri ->
        pluginJobScope.launch(Dispatchers.IO) {
            val result = runCatching {
                discardPendingInstalls()

                val root = externalPluginsRoot(host.appInfo.dataDir).apply { mkdirs() }
                if (!root.isDirectory || !root.canWrite()) throw PluginSystemError(
                    PluginErrorCodes.STORAGE_FAILED,
                    "Plugin directory is not writable: $root",
                )

                val token = UUID.randomUUID().toString()
                val staged = activity.contentResolver.openInputStream(uri)
                    ?.use { extractPluginZip(it, root, "$CONFIRM_TMP_PREFIX$token") }
                    ?: throw PluginSystemError(PluginErrorCodes.STORAGE_FAILED, "Unable to open $uri")

                pendingInstalls[token] = staged
                InstallPrompt(token, staged.manifest, replaces = installedVersion(staged.manifest.id))
            }.onFailure { pluginLog.e("Failed to stage plugin from $uri", it) }

            onReady(result)
        }
    }

    host.withAppActivity { activity ->
        activity.startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            },
            PICK_ZIP_REQUEST_CODE,
        )
    }
}

/**
 * Applies or discards a staged sideload install.
 * Unknown/stale token, or a decline discards the staged files.
 *
 * @return The applied [InstallResult], or `null` when nothing was applied.
 */
internal fun confirmPluginInstall(
    token: String,
    accepted: Boolean,
    dataDir: String,
    knownVersions: Map<String, Version>,
    isUpdate: (String) -> Boolean,
): InstallResult? {
    val staged = pendingInstalls.remove(token)
    if (staged == null || !accepted) {
        staged?.dir?.deleteRecursively()
        return null
    }

    val root = externalPluginsRoot(dataDir)
    val dir = try {
        applyStagedPlugin(staged, root)
    } catch (e: Throwable) {
        staged.dir.deleteRecursively()
        throw e
    }

    return if (isUpdate(staged.manifest.id)) {
        InstallResult.Updated(staged.manifest, Version.parse(staged.manifest.version))
    } else {
        InstallResult.New(readExternalPluginDir(dir, knownVersions))
    }
}

private fun discardPendingInstalls() {
    for (staged in pendingInstalls.values) staged.dir.deleteRecursively()
    pendingInstalls.clear()
}

/**
 * Extracts a plugin ZIP into `<root>/<tmpName>/`, then parses and validates its manifest.
 *
 * The manifest is checked before full extraction. Extraction stops at [MAX_EXTRACTED_ZIP_BYTES] decompressed bytes.
 */
internal fun extractPluginZip(input: InputStream, root: File, tmpName: String): StagedPlugin {
    val tmp = File(root, tmpName).apply {
        deleteRecursively()
        if (!mkdirs()) throw PluginSystemError(
            PluginErrorCodes.STORAGE_FAILED,
            "Unable to create install directory: $this",
        )
    }
    val spool = File(root, "$tmpName.zip")

    try {
        // We can only read [input] once, so we need to copy it.
        spool.outputStream().use(input::copyTo)

        ZipFile(spool).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_FILE)
                ?: throw PluginSystemError(
                    PluginErrorCodes.INSTALL_INVALID_ZIP,
                    "Not a Revenge plugin ZIP: missing $MANIFEST_FILE",
                )

            val manifest = try {
                RevengeJson.decodeFromString(
                    ExternalManifest.serializer(),
                    zip.getInputStream(manifestEntry).use { it.readBytes().decodeToString() },
                ).also { it.validate() }
            } catch (e: PluginSystemError) {
                throw e
            } catch (e: Throwable) {
                throw PluginSystemError(
                    PluginErrorCodes.MANIFEST_INVALID,
                    "Invalid $MANIFEST_FILE: ${e.message}",
                    e,
                )
            }

            var budget = MAX_EXTRACTED_ZIP_BYTES
            for (entry in zip.entries()) {
                val out = File(tmp, entry.name)
                out.requireInside(tmp, "ZIP entry", entry.name)

                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { dest ->
                        zip.getInputStream(entry).use { src ->
                            budget -= src.copyToCapped(dest, budget) {
                                throw PluginSystemError(
                                    PluginErrorCodes.INSTALL_INVALID_ZIP,
                                    "Plugin ZIP expands past the $MAX_EXTRACTED_ZIP_BYTES-byte limit",
                                )
                            }
                        }
                    }
                }
            }

            return StagedPlugin(tmp, manifest)
        }
    } catch (e: Throwable) {
        tmp.deleteRecursively()
        throw e
    } finally {
        spool.delete()
    }
}

/**
 * Creates or replaces `<root>/<id>/` with a staged plugin.
 *
 * The running session keeps running the code it already loaded. The new files will take effect on the next boot.
 */
internal fun applyStagedPlugin(staged: StagedPlugin, root: File): File {
    val dest = File(root, staged.manifest.id)
    dest.deleteRecursively()
    if (!staged.dir.renameTo(dest)) {
        staged.dir.copyRecursively(dest, overwrite = true)
        staged.dir.deleteRecursively()
    }
    return dest
}
