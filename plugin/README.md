# Revenge plugin

API for Revenge plugins.

## Usage

1. Add the Revenge plugin dependency as compile-only to your `build.gradle.kts`:
    
    ```kotlin
    dependencies {
        compileOnly("io.github.revenge:plugin:<version>")
    }
    ```

2. Use the `plugin` API. Here is an example of a plugin that registers 10 callbacks:
    
    ```kotlin
    // src/main/kotlin/io/github/you/MyPlugin.kt
    import io.github.revenge.Plugin
    
    // Or "val myPlugin by plugin {}" or "fun myPlugin() = plugin {}"
   @Suppress("unused")
    val myPlugin = plugin(name = "My Plugin") {
        init {
            println("Plugin initialized: $name") 
        }
    
        (1..10).forEach { i ->
            "callback$i" {
                println("Callback $i called")
            }
        }
    }
    ```

3. Specify the class that contains the plugin in the plugin jar file manifest
    
    ```text
    Revenge-Plugin-Class=io.github.you.MyPluginKt
    ```

