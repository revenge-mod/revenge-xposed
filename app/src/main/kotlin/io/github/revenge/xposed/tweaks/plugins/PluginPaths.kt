package io.github.revenge.xposed.tweaks.plugins

import java.io.File

private const val PLUGINS_DIR = "files/revenge/plugins/dist"
private const val PLUGIN_STORAGE_DIR = "files/revenge/plugins/storage"

internal fun externalPluginsRoot(dataDir: String): File = File(dataDir, PLUGINS_DIR)
internal fun pluginStorageRoot(dataDir: String): File = File(dataDir, PLUGIN_STORAGE_DIR)