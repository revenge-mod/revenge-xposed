package io.github.revenge.xposed.tweaks.plugins.external

import io.github.revenge.plugins.PluginManifest
import io.github.revenge.xposed.tweaks.plugins.*
import java.io.File

/** Plugin directory with parsed manifest, but unloaded artifacts. */
internal class ParsedExternalPlugin(val dir: File, val manifest: ExternalManifest)

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
 * Dependency resolution happens at [PluginDependencyGraph].
 */
internal fun discoverExternalPlugins(
    dataDir: String,
    internalManifests: Map<String, PluginManifest>,
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

    // toPluginManifest shouldn't throw since we already validate()d them.
    val graph = PluginDependencyGraph(
        internalManifests + parsed.mapValues { (_, plugin) -> plugin.manifest.toPluginManifest() },
    )
    val order = graph.loadOrder()

    for ((id, problems) in order.dropped) {
        val plugin = parsed[id] ?: continue
        failures.sessionSkip(
            id, plugin.manifest.toPluginManifest(),
            problems.map { PluginError(it.code, "Unsatisfied dependency: ${it.reason}") },
        )
    }

    for (id in order.cycles) {
        val plugin = parsed[id] ?: continue
        failures.sessionSkip(
            id, plugin.manifest.toPluginManifest(),
            listOf(PluginError(PluginErrorCodes.DEPENDENCY_CYCLE, "Dependency cycle")),
        )
    }

    // The graph doesn't know if a directory describes a plugin at all.
    // This only builds the factory, loading the plugin's code happens at [loadPlugin] for enabled plugins only.
    // A failed required dependency must skip the dependent, a failed optional one just goes ignored/unchained.
    val failed = mutableSetOf<String>()
    val result = mutableListOf<PluginFactory>()
    for (id in order.ordered) {
        val plugin = parsed[id] ?: continue

        val failedRequired = graph.requiredDependencies(id).intersect(failed)
        if (failedRequired.isNotEmpty()) {
            failed += id
            failures.sessionSkip(
                id, plugin.manifest.toPluginManifest(),
                failedRequired.map {
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
                plugin.manifest.toPluginManifest(),
                listOf(PluginError(PluginErrorCodes.LOAD_FAILED, "Failed to load: ${e.message}")),
            )
            pluginLog.e("Failed to load external plugin '$id'", e)
        }
    }

    return ExternalDiscovery(result, failures)
}
