package io.github.revenge.xposed.tweaks.plugins.external

import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.RevengeJson
import io.github.revenge.xposed.requireInside
import io.github.revenge.xposed.tweaks.plugins.PluginDependencyGraph
import io.github.revenge.xposed.tweaks.plugins.PluginFactory
import io.github.revenge.xposed.tweaks.plugins.PluginSystemError
import io.github.revenge.xposed.tweaks.plugins.pluginLog
import java.io.File

/** Parses and validates a `<id>/manifest.json`. */
internal fun parseExternalPluginDir(dir: File): ParsedExternalPlugin = ParsedExternalPlugin(
    dir,
    RevengeJson.decodeFromString(ExternalManifest.serializer(), File(dir, MANIFEST_FILE).readText())
        .also { it.validate() },
)

/**
 * Creates [PluginFactory] from `<id>/` directory.
 *
 * Throws on malformed manifest, missing required dependencies/unsatisfied version range.
 * Unavailable optional dependencies are dropped with a warning.
 *
 * @param knownManifests Every plugin installed used to resolve this plugin's dependencies.
 */
internal fun readExternalPluginDir(dir: File, knownManifests: Map<String, PluginManifest>): PluginFactory {
    val parsed = parseExternalPluginDir(dir)
    val manifest = parsed.manifest.toPluginManifest()
    val id = manifest.id

    val graph = PluginDependencyGraph(knownManifests + (id to manifest))

    for (depId in manifest.dependencies.keys) {
        val problem = graph.problem(id, depId) ?: continue

        if (manifest.dependencies.getValue(depId).optional) {
            pluginLog.w("Optional ${problem.reason} for plugin '$id'; ignoring")
        } else {
            throw PluginSystemError(problem.code, "Plugin '$id': ${problem.reason}")
        }
    }

    return buildExternalFactory(parsed)
}

internal fun buildExternalFactory(parsed: ParsedExternalPlugin): PluginFactory {
    val (dir, manifest) = parsed.dir to parsed.manifest

    val pluginManifest = manifest.toPluginManifest()

    val scriptPath = manifest.dist?.script?.let { script ->
        val file = File(dir, script)
        file.requireInside(dir, "dist.script", script)
        if (!file.isFile) {
            pluginLog.i("dist.script does not exist, skipping JS for ${pluginManifest.id}: $script")
            null
        } else {
            file.absolutePath
        }
    }

    val android = manifest.dist?.android

    pluginLog.i("Discovered external plugin: ${pluginManifest.id} ${pluginManifest.version}")

    return PluginFactory(pluginManifest, scriptPath = scriptPath) { chain ->
        // JS-only plugin, no native body.
        if (android == null) plugin {}
        else {
            val dexCache = File(dir.parentFile, DEX_CACHE_DIR).apply { mkdirs() }
            loadNativeBuilder(dir, dexCache, android, manifest.id, chain.mapNotNull { nativePluginLoaders[it] })
        }
    }
}
