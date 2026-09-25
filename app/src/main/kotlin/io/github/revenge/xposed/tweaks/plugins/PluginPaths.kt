package io.github.revenge.xposed.tweaks.plugins

import java.io.File

private const val PLUGINS_DIR = "files/revenge/plugins/dist"
private const val PLUGIN_STORAGE_DIR = "files/revenge/plugins/storage"

internal fun externalPluginsRoot(dataDir: String): File = File(dataDir, PLUGINS_DIR)

/** Per-slot plugin storage root for the active slot. */
internal fun pluginStorageRoot(dataDir: String): File =
    File(dataDir, PLUGIN_STORAGE_DIR).resolve(PluginStatesStore.activeSlotId)

/** A plugin's storage in every slot. */
internal fun pluginStorageDirsOfAllSlots(dataDir: String, pluginId: String): List<File> =
    File(dataDir, PLUGIN_STORAGE_DIR).listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.resolve(pluginId) }
        .orEmpty()
