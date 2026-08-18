package io.github.revenge.xposed.tweaks.bridge

import io.github.revenge.bridge.asDelegate
import io.github.revenge.reloadApp
import io.github.revenge.xposed.api.registerNativeMethod
import io.github.revenge.xposed.openFileGuarded
import io.github.revenge.xposed.tweak
import java.io.File

/**
 * `revenge.fs.*` + `revenge.app.*` bridge methods.
 */
val additionalBridgeMethods by tweak {
    registerNativeMethod("revenge.app.reload") {
        reloadApp()
    }

    withAppContext { ctx ->
        registerNativeMethod("revenge.fs.getConstants") {
            mapOf(
                "data" to ctx.dataDir.absolutePath,
                "files" to ctx.filesDir.absolutePath,
                "cache" to ctx.cacheDir.absolutePath,
            )
        }

        registerNativeMethod("revenge.fs.delete") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val f = File(path)
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }

        registerNativeMethod("revenge.fs.exists") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            File(path).exists()
        }

        registerNativeMethod("revenge.fs.read") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val file = File(path).also { it.openFileGuarded() }
            file.bufferedReader().use { it.readText() }
        }

        registerNativeMethod("revenge.fs.write") { args ->
            val argv = args.asDelegate()
            val path by argv.string()
            val contents by argv.string()
            File(path).apply {
                if (isDirectory) throw Error("Path is a directory: $path")
                parentFile?.mkdirs()
                writeText(contents)
            }

            null
        }
    }
}
