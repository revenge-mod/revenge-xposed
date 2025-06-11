package io.github.revenge

import android.content.pm.ApplicationInfo

class Context(
    val applicationInfo: ApplicationInfo,
    val classLoader: ClassLoader,
)
