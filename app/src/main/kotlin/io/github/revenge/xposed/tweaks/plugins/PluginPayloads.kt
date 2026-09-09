package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.plugins.PluginManifest
import io.github.revenge.plugins.PluginScope
import io.github.revenge.xposed.tweaks.plugins.external.DiscoveryFailure
import io.github.revenge.xposed.tweaks.plugins.external.MANIFEST_FORMAT
import io.github.revenge.xposed.tweaks.plugins.internal.InternalPluginFlags
import io.github.revenge.xposed.tweaks.plugins.repos.PluginSource
import java.io.File

internal fun PluginFactory.toJSPayload(
    loaded: LoadedPlugin? = null,
    bootError: PluginError? = null,
    /* `null` = sideloaded */
    source: PluginSource? = null,
): Map<String, Any?> = mapOf(
    "manifest" to manifest.toMap(),
    "script" to readScript(),
    "internal" to (InternalPluginFlags.INTERNAL in internalFlags),
    "essential" to (InternalPluginFlags.ESSENTIAL in internalFlags),
    "enabledByDefault" to (InternalPluginFlags.ENABLED_BY_DEFAULT in internalFlags),
    "api" to (InternalPluginFlags.API in internalFlags),
    "source" to source?.toJSPayload(),
    "unsatisfiedOptionalDependencies" to unsatisfiedOptionalDependencies.toList(),
    // Errors the native side has already hit (e.g. at boot, before JS was up).
    "errors" to buildList {
        bootError?.apply { add(toJSPayload()) }
        loaded?.apply {
            addAll(scope.errorsJSPayload)
        }
    },
)

val PluginScope.errorsJSPayload
    get() = errors.replayCache.map {
        it.toPluginError(PluginErrorCodes.PLUGIN_ERROR).toJSPayload()
    }

internal fun DiscoveryFailure.toJSPayload(
    manifest: PluginManifest,
    source: PluginSource? = null,
): Map<String, Any?> = mapOf(
    "manifest" to manifest.toMap(),
    "script" to null,
    "internal" to false,
    "essential" to false,
    "enabledByDefault" to false,
    "api" to false,
    "failed" to true,
    "source" to source?.toJSPayload(),
    "unsatisfiedOptionalDependencies" to emptyList<String>(),
    "errors" to errors.map { it.toJSPayload() },
)

internal fun PluginSource.toJSPayload(): Map<String, Any?> = mapOf(
    "repo" to repo,
    "channel" to channel,
    "held" to held,
)

/** Read the plugin's `dist.script` source (if any) for JS to evaluate. */
private fun PluginFactory.readScript(): String? = scriptPath?.let { path ->
    try {
        File(path).readText()
    } catch (e: Throwable) {
        pluginLog.e("Failed to read plugin script for ${manifest.id}: $path", e)
        null
    }
}

internal fun PluginManifest.toMap(): Map<String, Any?> = mapOf(
    // Anything that reaches runtime already passed format validation.
    "format" to MANIFEST_FORMAT,
    "id" to id,
    "name" to name,
    "description" to description,
    "author" to author,
    "icon" to icon,
    "dependencies" to dependencies.mapValues { (_, dep) ->
        mapOf("version" to dep.version.toString(), "optional" to dep.optional)
    },
    "version" to mapOf("nums" to version.nums, "label" to version.label),
)
