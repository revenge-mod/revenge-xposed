package io.github.revenge.xposed.tweaks.plugins.external

import dalvik.system.DexClassLoader
import io.github.revenge.plugins.PluginBuilder
import io.github.revenge.xposed.CompositeClassLoader
import io.github.revenge.xposed.requireInside
import io.github.revenge.xposed.tweaks.plugins.pluginLog
import java.io.File
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member
import java.lang.reflect.Modifier

// lives next to the plugin directories.
internal const val DEX_CACHE_DIR = ".dex-cache"

internal val nativePluginLoaders = mutableMapOf<String, ClassLoader>()

internal fun forgetNativePluginLoader(pluginId: String) {
    nativePluginLoaders.remove(pluginId)
}

internal fun loadNativeBuilder(
    dir: File,
    dexCache: File,
    android: ExternalAndroidDist,
    pluginId: String,
    depLoaders: List<ClassLoader>,
): PluginBuilder {
    val jar = File(dir, android.path)
    require(jar.isFile) { "dist.android.path does not exist: ${android.path}" }
    jar.requireInside(dir, "dist.android.path", android.path)

    // Android 14+ refuses to load a writable DEX file.
    jar.setReadOnly()

    // The parent must be the module class loader, which has the plugin API.
    val moduleLoader = PluginBuilder::class.java.classLoader!!
    val loader = DexClassLoader(
        jar.absolutePath,
        dexCache.absolutePath,
        null,
        CompositeClassLoader(moduleLoader, depLoaders),
    )
    nativePluginLoaders[pluginId] = loader

    val clazz = loader.loadClass(android.className)

    return resolvePluginBuilder(clazz)
        ?: throw IllegalArgumentException(
            "${clazz.name} does not expose a PluginBuilder val (declare `val myPlugin = plugin { ... }`)",
        )
}

/** Finds the first [PluginBuilder] that [clazz] exposes. */
private fun resolvePluginBuilder(clazz: Class<*>): PluginBuilder? {
    val getters = clazz.declaredMethods
        .filter {
            Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    PluginBuilder::class.java.isAssignableFrom(it.returnType)
        }
        .sortedBy { it.name }

    firstBuilderCandidate(getters, clazz) { it.invoke(null) as PluginBuilder }?.let { return it }

    val fields = clazz.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                    PluginBuilder::class.java.isAssignableFrom(it.type)
        }
        .sortedBy { it.name }

    return firstBuilderCandidate(fields, clazz) { it.get(null) as PluginBuilder }
}

/** Takes the first name-sorted candidate and [read]s it. Warns when there is more than one. */
private fun <T> firstBuilderCandidate(
    candidates: List<T>,
    clazz: Class<*>,
    read: (T) -> PluginBuilder,
): PluginBuilder? where T : AccessibleObject, T : Member {
    val first = candidates.firstOrNull() ?: return null
    if (candidates.size > 1) {
        pluginLog.w("${clazz.name} exposes multiple PluginBuilder vals ${candidates.map { it.name }}; using '${first.name}'")
    }
    return read(first.apply { isAccessible = true })
}
