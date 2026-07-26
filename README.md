<p align="center">
  <picture>
    <source
      width="512px"
      media="(prefers-color-scheme: dark)"
      srcset="assets/wordmark/wordmark+slogan-dark.svg"
    >
    <img
      width="512px"
      src="assets/wordmark/wordmark+slogan-light.svg"
    >
  </picture>
  <br>
   <a href="https://discord.com/invite/ddcQf3s2Uq">
       <picture>
           <source height="32px" media="(prefers-color-scheme: dark)" srcset="https://user-images.githubusercontent.com/13122796/178032563-d4e084b7-244e-4358-af50-26bde6dd4996.png" />
           <img height="32px" src="https://user-images.githubusercontent.com/13122796/178032563-d4e084b7-244e-4358-af50-26bde6dd4996.png" />
       </picture>
   </a>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
   <a href="https://github.com/revenge-mod">
       <picture>
           <source height="32px" media="(prefers-color-scheme: dark)" srcset="https://i.ibb.co/dMMmCrW/Git-Hub-Mark.png" />
           <img height="32px" src="https://i.ibb.co/9wV3HGF/Git-Hub-Mark-Light.png" />
       </picture>
   </a>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
   </a>
</p>

# RevengeXposed

**Discord, your way.** RevengeXposed is an Xposed module that loads [Revenge](https://github.com/revenge-mod/revenge-bundle-next) into Discord Android.

RevengeXposed is the root installation method for Revenge. It bootstraps the Revenge bundle inside Discord's process, manages the plugin system, and bridges JavaScript with the Android platform.

## ❓ About

This repository builds the Xposed module (the loader) for Revenge. The module injects into official Discord Android clients via the Xposed Framework, loads the Revenge Hermes bytecode bundle,
and provides native capabilities to it: plugin loading, file system access, and more.

## 💪 Features

- **📦 Bundle loading**: Bootstraps the Revenge bundle inside Discord
- **🔌 Native plugins**: Install, update, and run plugins with native (Kotlin/JVM) and JavaScript parts
- **🎨 Themes, fonts & system colors**: Native support for appearance customization
- **🛡️ Tracking blockers**: Blocks crash reporting and deep-link tracking

## ⬇️ Download

Grab the latest module APK from the [Releases](https://github.com/revenge-mod/revenge-xposed/releases/latest) page and install it on a device with the Xposed Framework (e.g. [Vector](https://github.com/JingMatrix/Vector)):

1. Install the module APK (Android 7.0+).
2. Enable the module for Discord in your Xposed manager.
3. Restart Discord, and you should be running Revenge!

## 👷 Development

You'll need a JDK (25 recommended) and the Android SDK. Once you have those, follow these steps:

```sh
# Build the debug module APK
./gradlew :app:assembleDebug

# Build the release module APK
./gradlew :app:assembleRelease
```

<sub>APKs are generated at `app/build/outputs/apk/`.</sub>

```sh
# Publish the plugin API to your local Maven repository (for plugin development)
./gradlew :api:publishToMavenLocal
```

<sub>Native plugins link against the `io.github.revenge:api` artifact.</sub>

## 📄 License

This project is licensed under the GPL-3.0 License. See [LICENSE](LICENSE) for details.
