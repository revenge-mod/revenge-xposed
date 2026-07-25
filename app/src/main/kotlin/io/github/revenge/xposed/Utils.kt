package io.github.revenge.xposed

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import java.io.File

fun File.ensureDir() {
    if (!isDirectory) delete()
    mkdirs()
}

fun File.ensureFile() {
    if (!isFile) deleteRecursively()
}

fun File.openFileGuarded() {
    if (!exists()) throw Error("Path does not exist: $path")
    if (!isFile) throw Error("Path is not a file: $path")
}

fun Context.versionName(): String {
    val pInfo = packageManager.getPackageInfo(packageName, 0)
    return pInfo.versionName ?: "unknown"
}

fun Context.versionCode(): Long {
    val pInfo = packageManager.getPackageInfo(packageName, 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode
    else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
}

val RevengeJson: Json = Json { ignoreUnknownKeys = true }