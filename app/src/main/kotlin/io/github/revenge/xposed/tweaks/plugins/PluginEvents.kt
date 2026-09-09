package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.xposed.api.HostScope
import io.github.revenge.xposed.api.callJSMethod
import kotlinx.coroutines.launch

internal object PluginEvents {
    const val DOWNLOAD_PROGRESS = "revenge.plugins.events.downloadProgress"
    const val PLUGIN_INSTALL_FILE_READY = "revenge.plugins.events.pluginInstallFileReady"
    const val PLUGIN_INSTALL_RESULT = "revenge.plugins.events.pluginInstallResult"
    const val PLUGIN_UPDATED = "revenge.plugins.events.pluginUpdated"
    const val PLUGIN_ERRORED = "revenge.plugins.events.pluginErrored"
    const val REPO_STATE_UPDATE = "revenge.plugins.events.repoStateUpdate"
}

context(host: HostScope)
internal fun emitPluginEvent(name: String, payload: Map<String, Any?>) {
    pluginJobScope.launch {
        runCatching { host.callJSMethod(name, listOf(payload)) }
            .onFailure { pluginLog.e("Failed to emit $name", it) }
    }
}

private object PluginStateMethods {
    /** Flags of a running plugin. */
    const val SESSION = "revenge.plugins.states.update"

    /** Flags written to the saved setup, which is what the UI edits during a defaults-only boot. */
    const val SAVED = "revenge.plugins.states.updateSaved"
}

context(host: HostScope)
private suspend fun sendStateToJS(method: String, pluginId: String, flags: Set<PluginFlags>) {
    runCatching { host.callJSMethod(method, listOf(pluginId, flags.toJSPayload())) }
        .onFailure { pluginLog.e("Failed to send $method for $pluginId", it) }
}

/** Tells JS a running plugin's flags. */
context(host: HostScope)
internal suspend fun sendSessionStateToJS(pluginId: String, flags: Set<PluginFlags>) =
    sendStateToJS(PluginStateMethods.SESSION, pluginId, flags)

/** Non-blocking version of [sendSessionStateToJS]. */
context(host: HostScope)
internal fun dispatchSessionStateToJS(pluginId: String, flags: Set<PluginFlags>) =
    pluginJobScope.launch { sendSessionStateToJS(pluginId, flags) }

/** Tells JS the saved states changed. */
context(host: HostScope)
internal fun dispatchSavedStateToJS(pluginId: String, flags: Set<PluginFlags>) =
    pluginJobScope.launch { sendStateToJS(PluginStateMethods.SAVED, pluginId, flags) }
