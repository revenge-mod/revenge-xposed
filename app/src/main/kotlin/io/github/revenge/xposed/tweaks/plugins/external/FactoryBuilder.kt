package io.github.revenge.xposed.tweaks.plugins.external

import io.github.revenge.plugins.Version
import io.github.revenge.plugins.plugin
import io.github.revenge.xposed.RevengeJson
import io.github.revenge.xposed.requireInside
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
 */
internal fun readExternalPluginDir(dir: File, knownVersions: Map<String, Version>): PluginFactory {
    val parsed = parseExternalPluginDir(dir)

    for ((depId, dep) in parsed.manifest.dependencies) {
        val problem = unsatisfiedDependencyReason(depId, dep, knownVersions[depId]) ?: continue

        if (dep.optional) {
            parsed.availableDeps -= depId
            if (knownVersions[depId] != null) parsed.unsatisfiedOptionalDeps += depId
            pluginLog.w("Optional ${problem.reason} for plugin '${parsed.manifest.id}'; ignoring")
        } else {
            throw PluginSystemError(problem.code, "Plugin '${parsed.manifest.id}': ${problem.reason}")
        }
    }

    return buildExternalFactory(parsed)
}

internal fun buildExternalFactory(parsed: ParsedExternalPlugin): PluginFactory {
    val (dir, manifest) = parsed.dir to parsed.manifest
    val dexCache = File(dir.parentFile, DEX_CACHE_DIR).apply { mkdirs() }

    val pluginManifest = manifest.toPluginManifest()

    val builder = manifest.dist?.android
        ?.let { android ->
            // Chain class loaders of dependencies this plugin can link against.
            val depLoaders = parsed.availableDeps.mapNotNull { nativePluginLoaders[it] }
            loadNativeBuilder(dir, dexCache, android, manifest.id, depLoaders)
        }
        ?: plugin {} // JS-only plugin, no native body.

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

    pluginLog.i("Loaded external plugin: ${pluginManifest.id} ${pluginManifest.version}")

    return PluginFactory(
        builder,
        pluginManifest,
        scriptPath = scriptPath,
        unsatisfiedOptionalDependencies = parsed.unsatisfiedOptionalDeps.toSet(),
    )
}
