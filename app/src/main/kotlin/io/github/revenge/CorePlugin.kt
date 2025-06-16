package io.github.revenge

import android.content.pm.ApplicationInfo
import android.util.Log
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

@Suppress("unused")
class CorePlugin(applicationInfo: ApplicationInfo, classLoader: ClassLoader) : ReactContextBaseJavaModule() {
    override fun getName() = "Revenge Core Plugin"

    init {
        Log.i(name, "Core plugin initialized in ${applicationInfo.sourceDir} with class loader $classLoader")
    }

    @ReactMethod
    fun getCallbacks() = emptyList<Callback>()
}
