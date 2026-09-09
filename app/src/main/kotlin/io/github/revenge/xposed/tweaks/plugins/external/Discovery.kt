package io.github.revenge.xposed.tweaks.plugins.external

import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.Version
import io.github.revenge.plugins.VersionRange
import io.github.revenge.xposed.tweaks.plugins.*
import java.io.File

/** Plugin directory with parsed manifest, but unloaded artifacts. */
internal class ParsedExternalPlugin(val dir: File, val manifest: ExternalManifest) {
    /** Dependencies this plugin can link against. Unsatisfied ones are removed later. */
    var availableDeps: Set<String> = manifest.dependencies.keys

    /** Installed optional dependencies that are unsatisfied. This plugin will load without them. */
    val unsatisfiedOptionalDeps = mutableSetOf<String>()
}

internal class DiscoveryFailure(
    val manifest: PluginManifest?,
    val errors: List<PluginError>,
) {
    /**
     * If the plugin itself is at fault (bad code, bad manifest) rather than its environment (dependencies).
     * It'll get disabled instead of being skipped.
     */
    val isPluginFault: Boolean
        get() = errors.any {
            it.code == PluginErrorCodes.LOAD_FAILED || it.code == PluginErrorCodes.MANIFEST_INVALID
        }
}

/** Records a skipped plugin for [id] and logs. */
private fun MutableMap<String, DiscoveryFailure>.sessionSkip(
    id: String,
    manifest: PluginManifest?,
    errors: List<PluginError>,
) {
    this[id] = DiscoveryFailure(manifest, errors)
    pluginLog.e("Skipping external plugin '$id': ${errors.joinToString("; ") { it.message }}")
}

internal class ExternalDiscovery(
    val factories: List<PluginFactory>,
    /** Keyed by plugin ID, or directory name if manifest failed to parse. */
    val failures: Map<String, DiscoveryFailure>,
)

/**
 * Reads installed plugins in `<dataDir>/files/revenge/plugins/dist`.
 * Each plugin has its own `<id>/` directory with a [MANIFEST_FILE] and `dist` artifacts.
 *
 * Plugins with missing or unsatisfying required dependencies are skipped, cascading to its dependents.
 * Dependencies always load first, so a plugin can link against the classes of its dependencies.
 */
internal fun discoverExternalPlugins(
    dataDir: String,
    knownVersions: Map<String, Version>,
): ExternalDiscovery {
    val failures = mutableMapOf<String, DiscoveryFailure>()
    val root = externalPluginsRoot(dataDir)
    if (!root.isDirectory) return ExternalDiscovery(emptyList(), failures)

    // Temp files and spooled ZIPs left mid-install.
    root.listFiles { it.name.startsWith(".") && it.name != DEX_CACHE_DIR }
        ?.forEach { it.deleteRecursively() }

    // Plugin IDs can't start with a dot.
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
                    PluginError(
                        PluginErrorCodes.MANIFEST_INVALID,
                        "Failed to read plugin manifest: ${e.message}",
                    ),
                ),
            )
            pluginLog.e("Failed to read external plugin in '${dir.name}'", e)
        }
    }

    val ordered = orderByDependencies(parsed, knownVersions, failures)

    // Failed required dependencies fail dependents too. Optionals are ignored.
    val failed = mutableSetOf<String>()
    val result = mutableListOf<PluginFactory>()
    for (plugin in ordered) {
        val id = plugin.manifest.id
        plugin.availableDeps = plugin.availableDeps.filterNot { depId ->
            depId in failed && plugin.manifest.dependencies[depId]?.optional == true
        }.toSet()

        val failedDeps = plugin.manifest.dependencies
            .filter { (depId, dep) -> !dep.optional && depId in failed }
            .keys
        if (failedDeps.isNotEmpty()) {
            failed += id
            failures.sessionSkip(
                id, plugin.manifest.toPluginManifest(),
                failedDeps.map {
                    PluginError(PluginErrorCodes.DEPENDENCY_FAILED, "Dependency '$it' failed to load")
                },
            )
            continue
        }

        try {
            result += buildExternalFactory(plugin)
        } catch (e: Throwable) {
            failed += id
            failures[id] = DiscoveryFailure(
                runCatching { plugin.manifest.toPluginManifest() }.getOrNull(),
                listOf(PluginError(PluginErrorCodes.LOAD_FAILED, "Failed to load: ${e.message}")),
            )
            pluginLog.e("Failed to load external plugin '$id'", e)
        }
    }

    return ExternalDiscovery(result, failures)
}

internal class DependencyProblem(val code: String, val reason: String)

internal fun unsatisfiedDependencyReason(
    depId: String,
    dep: ExternalDependency,
    /** null = missing */
    version: Version?,
): DependencyProblem? {
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
 * Drops plugins with unsatisfiable required dependencies and their dependents, then topologically sorts the remainder (dependencies before dependents).
 *
 * Unsatisfied optionals from [ParsedExternalPlugin.unsatisfiedOptionalDeps] are removed, and dependency cycles are skipped.
 */
private fun orderByDependencies(
    parsed: Map<String, ParsedExternalPlugin>,
    knownVersions: Map<String, Version>,
    failures: MutableMap<String, DiscoveryFailure>,
): List<ParsedExternalPlugin> {
    val known = knownVersions + parsed.mapValues { (_, plugin) -> Version.parse(plugin.manifest.version) }
    val dropped = mutableSetOf<String>()

    var changed = true
    while (changed) {
        changed = false
        for ((id, plugin) in parsed) {
            if (id in dropped) continue

            val problems = mutableListOf<PluginError>()
            for ((depId, dep) in plugin.manifest.dependencies) {
                val version = if (depId in dropped) null else known[depId]
                val problem = unsatisfiedDependencyReason(depId, dep, version) ?: continue

                if (dep.optional) {
                    if (depId in plugin.availableDeps) {
                        plugin.availableDeps -= depId
                        if (version != null) {
                            plugin.unsatisfiedOptionalDeps += depId
                            pluginLog.w("Optional ${problem.reason} for plugin '$id'; ignoring")
                        }
                    }
                    continue
                }

                problems += PluginError(problem.code, "Unsatisfied dependency: ${problem.reason}")
            }

            if (problems.isNotEmpty()) {
                dropped += id
                changed = true
                failures.sessionSkip(id, plugin.manifest.toPluginManifest(), problems)
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
                    listOf(PluginError(PluginErrorCodes.DEPENDENCY_CYCLE, "Dependency cycle")),
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
