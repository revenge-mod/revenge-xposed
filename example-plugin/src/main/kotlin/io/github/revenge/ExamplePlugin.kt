package io.github.revenge

import android.content.pm.ApplicationInfo
import android.util.Log
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import de.robv.android.xposed.XposedBridge

@Suppress("unused")
class ExamplePlugin(applicationInfo: ApplicationInfo, classLoader: ClassLoader) : ReactContextBaseJavaModule() {
    override fun getName() = "Example plugin"

    init {
        Log.i(
            name,
            "Example plugin initialized in ${applicationInfo.sourceDir} with class loader $classLoader"
        )
    }

    @ReactMethod
    fun getCallbacks() = listOf(
        Callback { args ->
            // Example callback function
            println("Callback invoked")
            XposedBridge.log("Callback invoked")
        }
    )
}
