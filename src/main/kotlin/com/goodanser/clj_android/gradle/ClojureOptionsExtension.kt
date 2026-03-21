package com.goodanser.clj_android.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * DSL extension for configuring Clojure compilation within an Android build.
 *
 * Applied to the `android` block as `clojureOptions { ... }`.
 */
abstract class ClojureOptionsExtension {

    /** Emit warnings when reflection is used in Clojure code. */
    abstract val warnOnReflection: Property<Boolean>

    /**
     * Whether to include the dynamic classloader and enable dynamic Clojure
     * compilation at runtime. When true, stock Clojure is substituted with a
     * patched version containing [AndroidDynamicClassLoader] which can compile
     * and load Clojure code on-device.
     *
     * This is required for any runtime code evaluation (e.g. apps that eval
     * user-provided Clojure expressions). It is also automatically enabled
     * when [replEnabled] is true, since nREPL requires dynamic compilation.
     *
     * Default: true for debug builds, false for release builds.
     * A null value means "use the default for this build type".
     */
    abstract val dynamicCompilationEnabled: Property<Boolean>

    /**
     * Whether to include nREPL server infrastructure in the build variant.
     * When true, the runtime-repl module is added and [ClojureApp] will
     * auto-start an nREPL server. Implies [dynamicCompilationEnabled].
     *
     * Default: true for debug builds, false for release builds.
     * A null value means "use the default for this build type".
     */
    abstract val replEnabled: Property<Boolean>

    /** Device-side nREPL port. Only relevant when replEnabled is true. */
    abstract val nreplPort: Property<Int>

    /** Namespaces to exclude from AOT compilation. */
    abstract val aotExcludeNamespaces: ListProperty<String>

    // Glob patterns for Clojure source files to exclude from the packaged
    // APK/AAR resources.  Use this to keep proprietary or internal sources
    // out of the distributed artifact while still allowing AOT compilation.
    // Patterns match the resource path inside the archive,
    // e.g. "com/company/internal/**".
    abstract val sourceResourceExcludes: ListProperty<String>

    init {
        warnOnReflection.convention(false)
        nreplPort.convention(7888)
        aotExcludeNamespaces.convention(emptyList())
        sourceResourceExcludes.convention(emptyList())
    }
}
