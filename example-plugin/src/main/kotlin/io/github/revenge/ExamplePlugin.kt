import android.util.Log
import io.github.revenge.Plugin.Companion.plugin

@Suppress("unused")
val corePlugin by plugin {
    init { 
        Log.i("ExamplePlugin", "Core plugin initialized")
    }
}