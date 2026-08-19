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
