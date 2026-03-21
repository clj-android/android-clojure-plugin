# android-clojure-plugin

Gradle plugin that integrates Clojure AOT compilation into Android builds. Handles source scanning, classpath assembly, DEX wiring, and runtime dependency management.

## Installation

The plugin is consumed as a **composite build** — no binary publishing required.

In your project's `settings.gradle.kts`, include the plugin source:

```kotlin
pluginManagement {
    includeBuild("path/to/android-clojure-plugin")
}
```

Then apply it in your module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") // or com.android.library
    id("com.goodanser.clj-android.android-clojure")
}
```

Clojure sources go in `src/main/clojure/` (mirroring `src/main/java/`). The plugin auto-discovers `.clj` files and compiles them ahead-of-time.

## Configuration

```kotlin
clojureOptions {
    warnOnReflection.set(true)              // default: false
    dynamicCompilationEnabled.set(true)     // default: true for debug, false for release
    replEnabled.set(true)                   // default: true for debug, false for release
    nreplPort.set(7888)                     // default: 7888
    aotExcludeNamespaces.set(listOf("my.ns.dev-only"))
    sourceResourceExcludes.set(listOf("com/company/internal/**"))
}
```

### Source bundling

`.clj` source files are bundled as resources in the APK only when dynamic compilation is enabled (debug builds by default). Release builds include only AOT-compiled classes — no source files.

To exclude specific sources from all builds (e.g. proprietary code that should be AOT-compiled but not distributed as source):

```kotlin
clojureOptions {
    sourceResourceExcludes.set(listOf(
        "com/company/internal/**",    // entire package
        "com/company/secret_ns.clj",  // single file
    ))
}
```

Excluded sources are still AOT-compiled normally — only the `.clj` resource files are stripped from the output.

## Updating

Pull the latest source:

```bash
cd path/to/android-clojure-plugin
git pull
```

Then rebuild your project. The composite build picks up source changes automatically.

## Troubleshooting stale builds

If the plugin appears to use old behavior after updating, the Gradle daemon is serving cached classes. The plugin detects this and prints:

```
android-clojure-plugin: source files are newer than the loaded plugin.
The Gradle daemon may be serving a stale version. Run './gradlew --stop' and rebuild.
```

If you see this warning, or suspect stale behavior without the warning:

```bash
./gradlew --stop
./gradlew :app:assembleDebug
```
