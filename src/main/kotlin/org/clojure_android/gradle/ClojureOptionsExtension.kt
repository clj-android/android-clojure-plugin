package org.clojure_android.gradle

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
     * Whether to include REPL infrastructure (nREPL server, dynamic classloader,
     * dx library) in the build variant. When false, only AOT-compiled Clojure
     * code is included and no dynamic compilation is possible at runtime.
     *
     * Default: true for debug builds, false for release builds.
     * A null value means "use the default for this build type".
     */
    abstract val replEnabled: Property<Boolean>

    /** Device-side nREPL port. Only relevant when replEnabled is true. */
    abstract val nreplPort: Property<Int>

    /** Namespaces to exclude from AOT compilation. */
    abstract val aotExcludeNamespaces: ListProperty<String>

    init {
        warnOnReflection.convention(false)
        nreplPort.convention(7888)
        aotExcludeNamespaces.convention(emptyList())
    }
}
