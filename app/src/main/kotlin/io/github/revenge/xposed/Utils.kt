package io.github.revenge.xposed

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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

/** Requires the path to be inside the [parent] directory, or throws an [IllegalArgumentException]. */
fun File.requireInside(parent: File, what: String, value: String) {
    require(canonicalPath.startsWith(parent.canonicalPath + File.separator)) {
        "$what escapes the directory: $value"
    }
}

/**
 * Copies at most [budget] bytes to [dest] and returns the number copied.
 * Calls [onExceeded] the moment the stream would go past the budget.
 */
inline fun InputStream.copyToCapped(
    dest: OutputStream,
    budget: Long,
    onExceeded: () -> Nothing,
): Long {
    var copied = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read == -1) return copied
        if (copied + read > budget) onExceeded()
        dest.write(buffer, 0, read)
        copied += read
    }
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