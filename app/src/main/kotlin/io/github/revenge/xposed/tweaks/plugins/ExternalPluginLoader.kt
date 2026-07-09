package io.github.revenge.xposed.tweaks.plugins

import android.app.Activity
import android.content.Intent
import dalvik.system.DexClassLoader
import io.github.revenge.Logger
import io.github.revenge.plugins.*
import io.github.revenge.xposed.RevengeJson
import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.hook
import io.github.revenge.xposed.method
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.zip.ZipInputStream

/** Directory (relative to app data dir) every plugin's distributions. */
private const val PLUGINS_DIR = "files/revenge/plugins/dist"

/** Directory (relative to app data dir) for plugin data. */
private const val PLUGIN_STORAGE_DIR = "files/revenge/plugins/storage"

/** Reserved subdirectory used as the [DexClassLoader] optimized-output dir. */
private const val DEX_CACHE_DIR = ".dex-cache"

private const val MANIFEST_FILE = "manifest.json"

@Serializable
internal data class ExternalManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val icon: String? = null,
    /** The API version this plugin was linked against. */
    @SerialName("api_version") val apiVersion: String? = null,
    val dependencies: List<ExternalDependency> = emptyList(),
    val dist: ExternalDist? = null,
) {
    fun toPluginManifest() = PluginManifest(
        id = id,
        name = name,
        description = description,
        author = author,
        icon = icon,
        dependencies = ArrayList(dependencies.map { PluginDependency(it.id, it.url) }),
    )
}

@Serializable
internal data class ExternalDependency(val id: String, val url: String? = null)

@Serializable
internal data class ExternalDist(
    val script: String? = null,
    val android: ExternalAndroidDist? = null,
)

@Serializable
internal data class ExternalAndroidDist(
    val path: String,
    @SerialName("class") val className: String,
)

/** A plugin directory whose manifest has been parsed but whose artifacts are not loaded yet. */
internal class ParsedExternalPlugin(val dir: File, val manifest: ExternalManifest)

/**
 * Scan `<dataDir>/files/revenge/plugins` for **all** external plugins.
 * Each plugin lives in its own `<id>/` directory containing a [MANIFEST_FILE] and its `dist` artifacts.
 *
 * - Native plugins (`dist.android`) can be loaded via [DexClassLoader] using the **module's own** class loader
 *   as the parent so the plugin can resolve the Revenge plugin API.
 * - If JS-only (no `dist.android`), an empty native body is assigned; their `dist.script` source is handed to JS
 *   via the `getPlugins` bridge method.
 *
 * Dependencies are verified before anything is loaded:
 * - A plugin whose dependency is missing (not an internal plugin nor another external plugin) is skipped,
 *   cascading to its dependents.
 * - Plugins load in dependency order (dependencies before dependents), so native externals can link
 *   against another plugin's public API via [CompositeClassLoader]. Cycles are skipped.
 */
internal fun discoverExternalPlugins(dataDir: String, internalIds: Set<String>, log: Logger): List<PluginFactory> {
    val root = externalPluginsRoot(dataDir)
    if (!root.isDirectory) return emptyList()

    val dirs = root.listFiles { it.isDirectory && it.name != DEX_CACHE_DIR } ?: return emptyList()

    // Parse manifests.
    val parsed = mutableMapOf<String, ParsedExternalPlugin>()
    for (dir in dirs) {
        if (!File(dir, MANIFEST_FILE).isFile) continue

        try {
            val plugin = parseExternalPluginDir(dir)
            parsed[plugin.manifest.id] = plugin
        } catch (e: Throwable) {
            log.e("Failed to read external plugin in '${dir.name}'", e)
        }
    }

    // Verify dependencies and order them (dependencies before dependents).
    val ordered = orderByDependencies(parsed, internalIds, log)

    // Load artifacts. A failed dependency cascades to its dependents.
    val failed = mutableSetOf<String>()
    val result = mutableListOf<PluginFactory>()
    for (plugin in ordered) {
        val id = plugin.manifest.id
        val failedDep = plugin.manifest.dependencies.firstOrNull { it.id in failed }
        if (failedDep != null) {
            failed += id
            log.e("Skipping external plugin '$id': dependency '${failedDep.id}' failed to load")
            continue
        }

        try {
            result += buildExternalFactory(plugin, log)
        } catch (e: Throwable) {
            failed += id
            log.e("Failed to load external plugin '$id'", e)
        }
    }

    return result
}

/**
 * Drop plugins with unsatisfiable dependencies (cascading), then order the rest so dependencies load before their dependents.
 * Members of a dependency cycle are skipped.
 */
private fun orderByDependencies(
    parsed: Map<String, ParsedExternalPlugin>,
    internalIds: Set<String>,
    log: Logger,
): List<ParsedExternalPlugin> {
    val known = parsed.keys + internalIds
    val dropped = mutableSetOf<String>()

    var changed = true
    while (changed) {
        changed = false
        for ((id, plugin) in parsed) {
            if (id in dropped) continue
            val bad = plugin.manifest.dependencies.firstOrNull { it.id !in known || it.id in dropped }
            if (bad != null) {
                dropped += id
                changed = true
                log.e("Skipping external plugin '$id': missing dependency '${bad.id}'")
            }
        }
    }

    val remaining = parsed.filterKeys { it !in dropped }.toMutableMap()
    val ordered = mutableListOf<ParsedExternalPlugin>()
    while (remaining.isNotEmpty()) {
        val ready = remaining.values.filter { plugin ->
            plugin.manifest.dependencies.none { it.id in remaining }
        }
        if (ready.isEmpty()) {
            for (id in remaining.keys) log.e("Skipping external plugin '$id': dependency cycle")
            break
        }
        for (plugin in ready) {
            ordered += plugin
            remaining.remove(plugin.manifest.id)
        }
    }
    return ordered
}

internal fun externalPluginsRoot(dataDir: String): File = File(dataDir, PLUGINS_DIR)

internal fun pluginStorageRoot(dataDir: String): File = File(dataDir, PLUGIN_STORAGE_DIR)

private val PLUGIN_ID_REGEX = Regex("[a-zA-Z0-9.-]+")

/**
 * Validates a plugin id: only `[a-zA-Z0-9.-]`, no consecutive dots, no leading dot.
 */
internal fun requireValidPluginId(id: String) {
    require(PLUGIN_ID_REGEX.matches(id) && ".." !in id && !id.startsWith(".")) {
        "Invalid plugin ID: $id"
    }
}

/** Parse a single `<id>/` plugin directory's manifest. Throws on malformed input. */
internal fun parseExternalPluginDir(dir: File): ParsedExternalPlugin = ParsedExternalPlugin(
    dir,
    RevengeJson.decodeFromString(ExternalManifest.serializer(), File(dir, MANIFEST_FILE).readText())
        .also { requireValidPluginId(it.id) },
)

/**
 * Parse and load a single `<id>/` plugin directory into a [PluginFactory].
 * Throws when malformed input or missing dependencies.
 */
internal fun readExternalPluginDir(dir: File, knownIds: Set<String>, log: Logger): PluginFactory {
    val parsed = parseExternalPluginDir(dir)

    val missing = parsed.manifest.dependencies.firstOrNull { it.id !in knownIds }
    require(missing == null) { "Missing dependency: ${missing!!.id}" }

    return buildExternalFactory(parsed, log)
}

/** Load a parsed plugin's artifacts into a [PluginFactory]. Throws on any malformed input. */
private fun buildExternalFactory(parsed: ParsedExternalPlugin, log: Logger): PluginFactory {
    val (dir, manifest) = parsed.dir to parsed.manifest
    val dexCache = File(dir.parentFile, DEX_CACHE_DIR).apply { mkdirs() }

    val pluginManifest = manifest.toPluginManifest()

    val pluginApiVersion = manifest.apiVersion?.let(SemVer::parse)
    // @TODO: For now, plugins load regardless.
    // if (pluginApiVersion == null ||
    //     pluginApiVersion.major != API_VERSION.major ||
    //     pluginApiVersion > API_VERSION
    // ) {
    //     log.w(
    //         "Skipping ${pluginManifest.id}: incompatible api_version " +
    //             "${manifest.apiVersion} (loader $API_VERSION)",
    //     )
    //     continue
    // }

    val builder = manifest.dist?.android
        ?.let { android ->
            // Chain the class loaders of native dependencies so this plugin can link against their public API.
            val depLoaders = manifest.dependencies.mapNotNull { nativePluginLoaders[it.id] }
            loadNativeBuilder(dir, dexCache, android, manifest.id, depLoaders, log)
        }
        ?: plugin {} // JS-only plugin, no native body

    val scriptPath = manifest.dist?.script?.let { script ->
        val file = File(dir, script)
        require(file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            "dist.script escapes the plugin directory: $script"
        }
        if (!file.isFile) {
            log.w("dist.script does not exist, skipping JS for ${pluginManifest.id}: $script")
            null
        } else {
            file.absolutePath
        }
    }

    log.i("Loaded external plugin: ${pluginManifest.id} (api_version ${pluginApiVersion ?: "unspecified"}, loader $API_VERSION)")

    return PluginFactory(builder, pluginManifest, scriptPath = scriptPath)
}

/** Class loaders of already-loaded native external plugins, keyed by plugin id, for dependency linking. */
private val nativePluginLoaders = mutableMapOf<String, ClassLoader>()

/** Forget a plugin's class loader (on uninstall) so a reinstall doesn't link against a stale one. */
internal fun forgetNativePluginLoader(pluginId: String) {
    nativePluginLoaders.remove(pluginId)
}

/**
 * Delegates to the module class loader first (standard parent delegation), then to each dependency plugin's loader
 */
private class CompositeClassLoader(
    parent: ClassLoader,
    private val fallbacks: List<ClassLoader>,
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        for (loader in fallbacks) {
            try {
                return loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        throw ClassNotFoundException(name)
    }
}

private fun loadNativeBuilder(
    dir: File,
    dexCache: File,
    android: ExternalAndroidDist,
    pluginId: String,
    depLoaders: List<ClassLoader>,
    log: Logger,
): PluginBuilder {
    val jar = File(dir, android.path)
    require(jar.isFile) { "dist.android.path does not exist: ${android.path}" }
    // Guard against path traversal.
    require(jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
        "dist.android.path escapes the plugin directory: ${android.path}"
    }

    // Android 14+ refuses to load writable dex files ("Writable dex file ... is not allowed").
    jar.setReadOnly()

    // Parent MUST be the module class loader (the one that loaded the plugin API),
    // NOT the host (Discord) class loader, otherwise API types will fail to resolve at link time.
    val moduleLoader = PluginBuilder::class.java.classLoader!!
    val loader = DexClassLoader(
        jar.absolutePath,
        dexCache.absolutePath,
        null,
        if (depLoaders.isEmpty()) moduleLoader else CompositeClassLoader(moduleLoader, depLoaders),
    )
    nativePluginLoaders[pluginId] = loader

    // `dist.android.class`
    val clazz = loader.loadClass(android.className)

    val builder = resolvePluginBuilder(clazz, log)
        ?: throw IllegalArgumentException(
            "${clazz.name} exposes no PluginBuilder val (declare `val myPlugin = plugin { ... }`)",
        )

    return builder
}

/**
 * Resolve the first [PluginBuilder] exposed by [clazz].
 *
 * A Kotlin top-level `val myPlugin = plugin { ... }` compiles to a `private static` backing field
 * + `public static` getter on the file-facade class.
 *
 * Resolution order (sorted by name):
 * 1. A public static no-arg getter whose return type is a [PluginBuilder] (the top-level `val`).
 * 2. A static field whose type is a [PluginBuilder] (read reflectively as a fallback).
 *
 * When more than one candidate exists, the first is used and a warning is logged.
 */
private fun resolvePluginBuilder(clazz: Class<*>, log: Logger): PluginBuilder? {
    val getters = clazz.declaredMethods
        .filter {
            Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    PluginBuilder::class.java.isAssignableFrom(it.returnType)
        }
        .sortedBy { it.name }

    if (getters.isNotEmpty()) {
        if (getters.size > 1) {
            log.w("${clazz.name} exposes multiple PluginBuilder values ${getters.map { it.name }}; using '${getters.first().name}'.")
        }
        return getters.first().apply { isAccessible = true }.invoke(null) as PluginBuilder
    }

    val fields = clazz.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                    PluginBuilder::class.java.isAssignableFrom(it.type)
        }
        .sortedBy { it.name }

    if (fields.isNotEmpty()) {
        if (fields.size > 1) {
            log.w("${clazz.name} exposes multiple PluginBuilder values ${fields.map { it.name }}; using '${fields.first().name}'.")
        }
        return fields.first().apply { isAccessible = true }.get(null) as PluginBuilder
    }

    return null
}

private const val PICK_ZIP_REQUEST_CODE = 0x5256

private const val INSTALL_TMP_DIR = ".install"

@Volatile
private var pickZipHooked = false

@Volatile
private var pendingPick: ((Activity, android.net.Uri) -> Unit)? = null

/**
 * Open the system document picker for a plugin ZIP, install it into `<id>/` (replacing any
 * existing install), and invoke [onResult] with the parsed [PluginFactory].
 *
 * Native owns installation entirely; callers only decide what to do with the result.
 * A canceled pick is a no-op. Failures are logged and reported through [onResult] (the picker
 * result arrives long after the bridge call returned). [knownIds] supplies the currently
 * registered plugin ids used to verify the installed plugin's dependencies.
 *
 * [onBeforeReplace] is awaited with the plugin id **after** extraction but **before** the
 * existing installation is replaced — the update flow's stop point
 * (download -> stop -> replace -> start).
 */
internal fun promptInstallPlugin(
    host: HostScope,
    scope: CoroutineScope,
    knownIds: () -> Set<String>,
    log: Logger,
    onBeforeReplace: suspend (pluginId: String) -> Unit,
    onResult: (Result<PluginFactory>) -> Unit,
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
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val root = externalPluginsRoot(host.appInfo.dataDir).apply { mkdirs() }
                check(root.isDirectory && root.canWrite()) { "Plugin directory is not writable: $root" }
                val dir = activity.contentResolver.openInputStream(uri)
                    ?.use { installPluginZip(it, root, onBeforeReplace) }
                    ?: throw IllegalStateException("Unable to open $uri")

                readExternalPluginDir(dir, knownIds(), log)
            }.onFailure { log.e("Failed to install plugin from $uri", it) }

            onResult(result)
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
 * Extract a plugin ZIP into `<root>/<id>/` (per its `manifest.json`), replacing any existing installation.
 * [onBeforeReplace] is awaited before the existing installation is touched.
 */
private suspend fun installPluginZip(
    input: InputStream,
    root: File,
    onBeforeReplace: suspend (pluginId: String) -> Unit,
): File {
    val tmp = File(root, INSTALL_TMP_DIR).apply {
        deleteRecursively()
        check(mkdirs()) { "Unable to create install directory: $this" }
    }

    try {
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(tmp, entry.name)
                // Guard against path traversal.
                require(out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                    "ZIP entry escapes the plugin directory: ${entry.name}"
                }

                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use(zip::copyTo)
                }
                entry = zip.nextEntry
            }
        }

        val manifest = RevengeJson.decodeFromString(
            ExternalManifest.serializer(),
            File(tmp, MANIFEST_FILE).readText(),
        )

        requireValidPluginId(manifest.id)
        onBeforeReplace(manifest.id)

        val dest = File(root, manifest.id)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) {
            tmp.copyRecursively(dest, overwrite = true)
            tmp.deleteRecursively()
        }
        return dest
    } catch (e: Throwable) {
        tmp.deleteRecursively()
        throw e
    }
}
