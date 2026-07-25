package io.github.revenge.plugins

import io.github.revenge.Logger
import io.github.revenge.xposed.api.HostScope
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

interface PluginScope : HostScope {
    /** Per-plugin logger, namespaced by [manifest].`id`. */
    val log: Logger

    val manifest: PluginManifest

    /**
     * This plugin's data directory (`files/revenge/plugins/storage/<id>/`), created on first access.
     *
     * Preserved across plugin updates and deleted on uninstall. Shared with the plugin's JS side
     * (the `jsonStorage` API stores its documents here, the file name `storage.json` is reserved
     * as its default document). Store whatever you want in it, in whatever format fits.
     */
    val storageDir: File

    /** Whether this plugin is currently enabled. */
    val enabled: Boolean

    /**
     * Whether this plugin was enabled *after* the initial load (e.g. user-toggled at runtime).
     * Plugins that perform early-only work should react by calling [requireReload].
     */
    val enabledLate: Boolean

    /**
     * Errors that this plugin encountered during lifecycles. This does not include JS errors.
     *
     * You can emit any arbitrary [Throwable] to surface it in the UI.
     * Emitted errors are treated as non-fatal: they won't stop or disable the plugin.
     * Call [stop] or [disable] manually if an error should halt the plugin.
     */
    val errors: MutableSharedFlow<Throwable>

    /** Mark this plugin as requiring a host reload to (un)apply its changes. */
    fun requireReload()

    /** Stops this plugin. */
    fun stop()

    /** Disables this plugin. */
    fun disable()
}
