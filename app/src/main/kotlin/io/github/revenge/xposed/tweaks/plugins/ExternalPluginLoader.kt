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
import io.github.revenge.xposed.tweaks.plugins.internal.RESERVED_DEPENDENCY_IDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/** Directory (relative to app data dir) every plugin's distributions. */
private const val PLUGINS_DIR = "files/revenge/plugins/dist"

/** Directory (relative to app data dir) for plugin data. */
private const val PLUGIN_STORAGE_DIR = "files/revenge/plugins/storage"

/** Reserved subdirectory used as the [DexClassLoader] optimized-output dir. */
private const val DEX_CACHE_DIR = ".dex-cache"

private const val MANIFEST_FILE = "manifest.json"

/** The supported `manifest.json` format version. */
internal const val MANIFEST_FORMAT = 1

@Serializable
internal data class ExternalManifest(
    /** Manifest format version. Required; only [MANIFEST_FORMAT] is accepted. */
    val format: Int,
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    /* See [validatedPluginIcon]. */
    val icon: String? = null,
    /** The plugin's version. */
    val version: String,
    /** Dependencies keyed by plugin ID: `{ "<id>": { "version"?: "<range>", "optional"?: true } }`. */
    val dependencies: Map<String, ExternalDependency> = emptyMap(),
    val dist: ExternalDist? = null,
) {
    fun toPluginManifest() = PluginManifest(
        id = id,
        name = name,
        description = description,
        author = author,
        icon = icon?.let(::validatedPluginIcon),
        dependencies = dependencies.entries.associate { (depId, dep) ->
            requireValidPluginId(depId)
            depId to PluginDependency(
                version = dep.version?.let(VersionRange::parse) ?: VersionRange.ANY,
                optional = dep.optional,
            )
        },
        version = Version.parse(version),
    )
}

@Serializable
internal data class ExternalDependency(
    /** Version range; `null` (an empty object `{}`) means `"*"`. */
    val version: String? = null,
    /** Optional dependencies that never block the dependent. Missing/unsatisfied/failed ones are ignored. */
    val optional: Boolean = false,
)

/** Maximum accepted length of a `data:` icon value. */
private const val MAX_ICON_DATA_URL_LENGTH = 128 * 1024

private val URI_SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * A valid icon must be a Discord-packaged asset name (no URI scheme) or a size-capped `data:` URL.
 * Anything else is stripped. Icons should never be fetched remotely.
 */
internal fun validatedPluginIcon(icon: String): String? = when {
    icon.startsWith("data:") -> icon.takeIf { it.length <= MAX_ICON_DATA_URL_LENGTH }
    URI_SCHEME_REGEX.containsMatchIn(icon) -> null
    else -> icon
}

internal fun ExternalManifest.validate() {
    require(format == MANIFEST_FORMAT) {
        "Plugin '$id' has unsupported manifest format $format (supported: $MANIFEST_FORMAT)"
    }
    requireValidPluginId(id)
    Version.parse(version)

    // Verify that reserved dependencies are declared and required.
    for (reservedId in RESERVED_DEPENDENCY_IDS) {
        require(reservedId in dependencies) {
            "Plugin '$id' does not declare the mandatory '$reservedId' dependency"
        }
    }
    for ((depId, dep) in dependencies) {
        requireValidPluginId(depId)
        dep.version?.let(VersionRange::parse)
        require(!(dep.optional && depId in RESERVED_DEPENDENCY_IDS)) {
            "Plugin '$id': the '$depId' dependency cannot be optional"
        }
    }
}

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
internal class ParsedExternalPlugin(val dir: File, val manifest: ExternalManifest) {
    /**
     * Dependency IDs deemed available for this plugin. Unsatisfied dependencies will be removed later.
     */
    var availableDeps: Set<String> = manifest.dependencies.keys

    /**
     * Optional dependencies that are installed but unsatisfied, so this plugin loaded without linking them.
     */
    val unsatisfiedOptionalDeps = mutableSetOf<String>()
}

internal class DiscoveryFailure(
    val manifest: PluginManifest?,
    /** Everything wrong with the plugin, eg. one entry per unsatisfied/failed dependency. Never empty. */
    val errors: List<PluginErrorInfo>,
) {
    /**
     * True when the failure is the plugin's own fault (bad code, bad manifest) rather than an environmental one (dependencies).
     * Own-fault plugins get disabled instead of session-skipped, matching JS disabling a plugin whose script throws at evaluation.
     */
    val isPluginFault: Boolean
        get() = errors.any {
            it.code == PluginErrorCodes.LOAD_FAILED || it.code == PluginErrorCodes.MANIFEST_INVALID
        }
}

/** Record a session-skip [DiscoveryFailure] for [id] and log it. */
private fun MutableMap<String, DiscoveryFailure>.sessionSkip(
    id: String,
    manifest: PluginManifest?,
    errors: List<PluginErrorInfo>,
    log: Logger,
) {
    this[id] = DiscoveryFailure(manifest, errors)
    log.e("Skipping external plugin '$id': ${errors.joinToString("; ") { it.message }}")
}

internal class ExternalDiscovery(
    val factories: List<PluginFactory>,
    /** Keyed by plugin ID (or directory name if the manifest failed to parse). */
    val failures: Map<String, DiscoveryFailure>,
)

/**
 * Scan `[dataDir]/files/revenge/plugins` for **all** external plugins.
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
internal fun discoverExternalPlugins(
    dataDir: String,
    knownVersions: Map<String, Version>,
    log: Logger,
): ExternalDiscovery {
    val failures = mutableMapOf<String, DiscoveryFailure>()
    val root = externalPluginsRoot(dataDir)
    if (!root.isDirectory) return ExternalDiscovery(emptyList(), failures)

    // Clean up temp dirs and spooled ZIPs left by a crash mid-install (staged confirms, aborted extractions).
    root.listFiles { it.name.startsWith(".") && it.name != DEX_CACHE_DIR }
        ?.forEach { it.deleteRecursively() }

    // Plugin IDs can't start with a dot, so dot-dirs are never plugin installs.
    val dirs = root.listFiles { it.isDirectory && !it.name.startsWith(".") }
        ?: return ExternalDiscovery(emptyList(), failures)

    val parsed = mutableMapOf<String, ParsedExternalPlugin>()
    for (dir in dirs) {
        if (!File(dir, MANIFEST_FILE).isFile) continue

        try {
            val plugin = parseExternalPluginDir(dir)
            parsed[plugin.manifest.id] = plugin
        } catch (e: Throwable) {
            failures[dir.name] = DiscoveryFailure(
                null,
                listOf(
                    PluginErrorInfo(
                        PluginErrorCodes.MANIFEST_INVALID,
                        "Failed to read plugin manifest: ${e.message}",
                    ),
                ),
            )
            log.e("Failed to read external plugin in '${dir.name}'", e)
        }
    }

    val ordered = orderByDependencies(parsed, knownVersions, failures, log)

    // Failed required dependencies bubble errors to their dependents, optionals are ignored.
    val failed = mutableSetOf<String>()
    val result = mutableListOf<PluginFactory>()
    for (plugin in ordered) {
        val id = plugin.manifest.id
        plugin.availableDeps = plugin.availableDeps.filterNot { depId ->
            depId in failed && plugin.manifest.dependencies[depId]?.optional == true
        }.toSet()

        // Every failed required dep gets its own error, not just the first one found.
        val failedDeps = plugin.manifest.dependencies
            .filter { (depId, dep) -> !dep.optional && depId in failed }
            .keys
        if (failedDeps.isNotEmpty()) {
            failed += id
            failures.sessionSkip(
                id, plugin.manifest.toPluginManifest(),
                failedDeps.map {
                    PluginErrorInfo(PluginErrorCodes.DEPENDENCY_FAILED, "Dependency '$it' failed to load")
                },
                log,
            )
            continue
        }

        try {
            result += buildExternalFactory(plugin, log)
        } catch (e: Throwable) {
            failed += id
            failures[id] = DiscoveryFailure(
                runCatching { plugin.manifest.toPluginManifest() }.getOrNull(),
                listOf(PluginErrorInfo(PluginErrorCodes.LOAD_FAILED, "Failed to load: ${e.message}")),
            )
            log.e("Failed to load external plugin '$id'", e)
        }
    }

    return ExternalDiscovery(result, failures)
}

/** Why a dependency is unsatisfied: a [PluginErrorCodes] code plus the human reason. */
private class DependencyProblem(val code: String, val reason: String)

/**
 * The reason [dep] is unsatisfied by the dependency [version] (null = missing), or `null` when satisfied.
 */
private fun unsatisfiedDependencyReason(depId: String, dep: ExternalDependency, version: Version?): DependencyProblem? {
    val range = dep.version?.let(VersionRange::parse) ?: VersionRange.ANY
    return when {
        version == null -> DependencyProblem(
            PluginErrorCodes.DEPENDENCY_MISSING,
            "missing dependency '$depId'",
        )

        !range.satisfies(version) -> DependencyProblem(
            PluginErrorCodes.DEPENDENCY_UNSATISFIED,
            "dependency '$depId' version $version does not satisfy '${dep.version}'",
        )

        else -> null
    }
}

/**
 * Drop plugins with unsatisfiable required dependencies (cascading), then sort the rest so dependencies load before dependents.
 * Missing/unsatisfied optionals are removed from [ParsedExternalPlugin.availableDeps], Members of a cycle are skipped.
 */
private fun orderByDependencies(
    parsed: Map<String, ParsedExternalPlugin>,
    knownVersions: Map<String, Version>,
    failures: MutableMap<String, DiscoveryFailure>,
    log: Logger,
): List<ParsedExternalPlugin> {
    val known = knownVersions + parsed.mapValues { (_, plugin) -> Version.parse(plugin.manifest.version) }
    val dropped = mutableSetOf<String>()

    var changed = true
    while (changed) {
        changed = false
        for ((id, plugin) in parsed) {
            if (id in dropped) continue
            // Collect every unsatisfied required dep instead of stopping at the first one,
            // so the user sees the full list at once.
            val problems = mutableListOf<PluginErrorInfo>()
            for ((depId, dep) in plugin.manifest.dependencies) {
                val version = if (depId in dropped) null else known[depId]
                val problem = unsatisfiedDependencyReason(depId, dep, version) ?: continue

                if (dep.optional) {
                    if (depId in plugin.availableDeps) {
                        plugin.availableDeps -= depId
                        if (version != null) {
                            plugin.unsatisfiedOptionalDeps += depId
                            log.w("Optional ${problem.reason} for plugin '$id'; ignoring")
                        }
                    }
                    continue
                }

                problems += PluginErrorInfo(problem.code, "Unsatisfied dependency: ${problem.reason}")
            }

            if (problems.isNotEmpty()) {
                dropped += id
                changed = true
                failures.sessionSkip(id, plugin.manifest.toPluginManifest(), problems, log)
            }
        }
    }

    val remaining = parsed.filterKeys { it !in dropped }.toMutableMap()
    val ordered = mutableListOf<ParsedExternalPlugin>()
    while (remaining.isNotEmpty()) {
        val ready = remaining.values.filter { plugin ->
            plugin.manifest.dependencies.none { (depId, dep) ->
                depId in remaining && plugin.manifest.id != depId &&
                        (!dep.optional || depId in plugin.availableDeps)
            }
        }
        if (ready.isEmpty()) {
            for (id in remaining.keys) {
                failures.sessionSkip(
                    id, remaining[id]?.manifest?.toPluginManifest(),
                    listOf(PluginErrorInfo(PluginErrorCodes.DEPENDENCY_CYCLE, "Dependency cycle")),
                    log,
                )
            }
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
 * Validates a plugin ID: only `[a-zA-Z0-9.-]`, no consecutive dots, no leading dot.
 */
internal fun requireValidPluginId(id: String) {
    require(PLUGIN_ID_REGEX.matches(id) && ".." !in id && !id.startsWith(".")) {
        "Invalid plugin ID: $id"
    }
}

/** Parse a single `<id>/` plugin directory's manifest. */
internal fun parseExternalPluginDir(dir: File): ParsedExternalPlugin = ParsedExternalPlugin(
    dir,
    RevengeJson.decodeFromString(ExternalManifest.serializer(), File(dir, MANIFEST_FILE).readText())
        .also { it.validate() },
)

/**
 * Parse and load a single `<id>/` plugin directory into a [PluginFactory].
 *
 * Throws when malformed input, missing required dependencies, or unsatisfied version ranges.
 * Unavailable optionals are ignored and warned.
 */
internal fun readExternalPluginDir(dir: File, knownVersions: Map<String, Version>, log: Logger): PluginFactory {
    val parsed = parseExternalPluginDir(dir)

    for ((depId, dep) in parsed.manifest.dependencies) {
        val problem = unsatisfiedDependencyReason(depId, dep, knownVersions[depId]) ?: continue

        if (dep.optional) {
            parsed.availableDeps -= depId
            if (knownVersions[depId] != null) parsed.unsatisfiedOptionalDeps += depId
            log.w("Optional ${problem.reason} for plugin '${parsed.manifest.id}'; ignoring")
        } else {
            throw PluginException(problem.code, "Plugin '${parsed.manifest.id}': ${problem.reason}")
        }
    }

    return buildExternalFactory(parsed, log)
}

/** Load a parsed plugin's artifacts into a [PluginFactory]. Throws on any malformed input. */
private fun buildExternalFactory(parsed: ParsedExternalPlugin, log: Logger): PluginFactory {
    val (dir, manifest) = parsed.dir to parsed.manifest
    val dexCache = File(dir.parentFile, DEX_CACHE_DIR).apply { mkdirs() }

    val pluginManifest = manifest.toPluginManifest()

    val builder = manifest.dist?.android
        ?.let { android ->
            // Chain the class loaders of available native dependencies (required + satisfied optionals),
            // so this plugin can link against their public API. Unavailable optionals are excluded.
            val depLoaders = parsed.availableDeps.mapNotNull { nativePluginLoaders[it] }
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

    log.i("Loaded external plugin: ${pluginManifest.id} ${pluginManifest.version}")

    return PluginFactory(
        builder,
        pluginManifest,
        scriptPath = scriptPath,
        unsatisfiedOptionalDependencies = parsed.unsatisfiedOptionalDeps.toSet(),
    )
}

/** Class loaders of already-loaded native external plugins, keyed by plugin id, for dependency linking. */
private val nativePluginLoaders = mutableMapOf<String, ClassLoader>()

/** Forget a plugin's class loader (on uninstall) so a re-installation doesn't link against a stale one. */
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

    firstBuilderCandidate(getters, clazz, log) { it.invoke(null) as PluginBuilder }?.let { return it }

    val fields = clazz.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                    PluginBuilder::class.java.isAssignableFrom(it.type)
        }
        .sortedBy { it.name }

    return firstBuilderCandidate(fields, clazz, log) { it.get(null) as PluginBuilder }
}

/**
 * Pick the first (name-sorted) reflective candidate, warn when more >1, and [read] the [PluginBuilder].
 */
private fun <T> firstBuilderCandidate(
    candidates: List<T>,
    clazz: Class<*>,
    log: Logger,
    read: (T) -> PluginBuilder,
): PluginBuilder? where T : AccessibleObject, T : Member {
    val first = candidates.firstOrNull() ?: return null
    if (candidates.size > 1) {
        log.w("${clazz.name} exposes multiple PluginBuilder values ${candidates.map { it.name }}; using '${first.name}'.")
    }
    return read(first.apply { isAccessible = true })
}

private const val PICK_ZIP_REQUEST_CODE = 0x5256

private const val CONFIRM_TMP_PREFIX = ".confirm-"

@Volatile
private var pickZipHooked = false

@Volatile
private var pendingPick: ((Activity, android.net.Uri) -> Unit)? = null

/** A staged sideload install waiting for the user's confirmation. */
internal class InstallPrompt(
    val token: String,
    val manifest: ExternalManifest,
    /** The installed version this would replace. */
    val replaces: Version?,
)

/** Staged sideload installs keyed by single-use token. At most one at a time. */
private val pendingInstalls = mutableMapOf<String, StagedPlugin>()

/**
 * Open the document picker for a plugin ZIP, extract + validate, and hand an [InstallPrompt]
 * to [onReady] without applying. Failures are logged and reported through [onReady].
 *
 * The callback fires the confirmation event to JS. [confirmPluginInstall] applies or discards the plan.
 *
 * A canceled pick does nothing. A new pick discards the previous unconfirmed plan.
 */
internal fun promptInstallPlugin(
    host: HostScope,
    scope: CoroutineScope,
    installedVersion: (String) -> Version?,
    log: Logger,
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
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                discardPendingInstalls()

                val root = externalPluginsRoot(host.appInfo.dataDir).apply { mkdirs() }
                check(root.isDirectory && root.canWrite()) { "Plugin directory is not writable: $root" }

                val token = java.util.UUID.randomUUID().toString()
                val staged = activity.contentResolver.openInputStream(uri)
                    ?.use { extractPluginZip(it, root, "$CONFIRM_TMP_PREFIX$token") }
                    ?: throw IllegalStateException("Unable to open $uri")

                pendingInstalls[token] = staged
                val replaces = installedVersion(staged.manifest.id)
                InstallPrompt(token, staged.manifest, replaces = replaces)
            }.onFailure { log.e("Failed to stage plugin from $uri", it) }

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
 * Unknown or stale tokens, and declines return `null` after discarding.
 *
 * @return The applied [InstallResult], or `null` when nothing was applied.
 */
internal fun confirmPluginInstall(
    token: String,
    accepted: Boolean,
    dataDir: String,
    knownVersions: Map<String, Version>,
    isUpdate: (String) -> Boolean,
    log: Logger,
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
        InstallResult.New(readExternalPluginDir(dir, knownVersions, log))
    }
}

private fun discardPendingInstalls() {
    for (staged in pendingInstalls.values) staged.dir.deleteRecursively()
    pendingInstalls.clear()
}

internal sealed class InstallResult {
    /** New plugin install, can be loaded immediately. */
    class New(val factory: PluginFactory) : InstallResult()

    /** An update was installed. It'll be loaded next session. */
    class Updated(val manifest: ExternalManifest, val version: Version) : InstallResult()
}

/** An extracted-and-validated plugin ZIP that hasn't replaced any installation yet. */
internal class StagedPlugin(val dir: File, val manifest: ExternalManifest)

/**
 * Extract a plugin ZIP into `<root>/[tmpName]/` and parse + validate its manifest.
 *
 * [input] is one-shot (content resolver / network), so it is spooled to a file first,
 * allowing us to check and validate the manifest before writing a single plugin file.
 */
internal fun extractPluginZip(input: InputStream, root: File, tmpName: String): StagedPlugin {
    val tmp = File(root, tmpName).apply {
        deleteRecursively()
        check(mkdirs()) { "Unable to create install directory: $this" }
    }
    val spool = File(root, "$tmpName.zip")

    try {
        spool.outputStream().use(input::copyTo)

        ZipFile(spool).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_FILE)
                ?: throw PluginException(
                    PluginErrorCodes.INSTALL_INVALID_ZIP,
                    "Not a Revenge plugin ZIP: missing $MANIFEST_FILE",
                )

            val manifest = try {
                RevengeJson.decodeFromString(
                    ExternalManifest.serializer(),
                    zip.getInputStream(manifestEntry).use { it.readBytes().decodeToString() },
                ).also { it.validate() }
            } catch (e: PluginException) {
                throw e
            } catch (e: Throwable) {
                throw PluginException(
                    PluginErrorCodes.MANIFEST_INVALID,
                    "Invalid $MANIFEST_FILE: ${e.message}",
                    e,
                )
            }

            for (entry in zip.entries()) {
                val out = File(tmp, entry.name)
                // Guard against path traversal.
                require(out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                    "ZIP entry escapes the plugin directory: ${entry.name}"
                }

                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { dest -> zip.getInputStream(entry).use { it.copyTo(dest) } }
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

/** Create or replace `[root]/<id>/` with a staged plugin. The new files take effect in the next session. */
internal fun applyStagedPlugin(
    staged: StagedPlugin,
    root: File,
): File {
    val dest = File(root, staged.manifest.id)
    dest.deleteRecursively()
    if (!staged.dir.renameTo(dest)) {
        staged.dir.copyRecursively(dest, overwrite = true)
        staged.dir.deleteRecursively()
    }
    return dest
}
