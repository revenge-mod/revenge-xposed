package io.github.revenge.xposed.tweaks.plugins

import io.github.revenge.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 *   pluginLoader          discovers plugins at boot, then loads and starts the enabled ones
 *   pluginMethods         revenge.plugins.* lifecycle methods (list, setEnabled, uninstall, ...)
 *   pluginInstallMethods  install, update, and checks
 *   pluginRepos           repository config, indexes, and the resolver
 */

internal val pluginRegistry = PluginRegistry()

/** Scope for work that outlives a bridge call: flag persistence, JS events, installs. */
internal val pluginJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal val pluginLog = logger("plugins")
