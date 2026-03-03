package com.goodanser.clj_android.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import com.android.build.gradle.BaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.plugins.ExtensionAware

/**
 * Gradle plugin that integrates Clojure AOT compilation into Android builds.
 */
class AndroidClojurePlugin : Plugin<Project> {

    companion object {
        private val ANDROID_PLUGIN_IDS = listOf(
            "com.android.application",
            "com.android.library",
        )

        private const val RUNTIME_CORE = "com.goodanser.clj-android:runtime-core:0.1.0-SNAPSHOT"
        private const val RUNTIME_REPL = "com.goodanser.clj-android:runtime-repl:0.1.0-SNAPSHOT"
        private const val PATCHED_CLOJURE = "com.goodanser.clj-android:clojure:1.12.0-1"
    }

    override fun apply(project: Project) {
        project.logger.debug("Applying android-clojure plugin")

        val clojureOptions = project.extensions.create(
            "clojureOptions",
            ClojureOptionsExtension::class.java,
        )

        var configured = false
        ANDROID_PLUGIN_IDS.forEach { id ->
            project.plugins.withId(id) {
                if (!configured) {
                    configured = true
                    configure(project, clojureOptions)
                }
            }
        }

        project.afterEvaluate {
            if (!configured) {
                throw ProjectConfigurationException(
                    "The 'com.goodanser.clj-android.android-clojure' plugin requires " +
                        "an Android plugin ('com.android.application' or 'com.android.library') " +
                        "to be applied.",
                    listOf(),
                )
            }
        }
    }

    private fun configure(project: Project, clojureOptions: ClojureOptionsExtension) {
        val androidExtension = project.extensions.getByType(BaseExtension::class.java)
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        // Add Clojure source directories to each Android source set.
        // AndroidSourceSet doesn't directly extend ExtensionAware in Kotlin's
        // view, so we cast explicitly.
        androidExtension.sourceSets.configureEach {
            val extensionAware = this as ExtensionAware
            if (extensionAware.extensions.findByName("clojure") == null) {
                extensionAware.extensions.add(
                    "clojure",
                    ClojureSourceDirectorySet(name, project),
                )
            }
        }

        androidComponents.onVariants { variant ->
            registerClojureCompileTask(project, variant, androidExtension, clojureOptions)
            configureRuntimeDependencies(project, variant, clojureOptions)
        }
    }

    /**
     * Adds the appropriate runtime dependencies for this variant:
     * - runtime-core is always added
     * - When dynamic compilation is enabled (or implied by REPL), stock Clojure
     *   is substituted with patched Clojure containing AndroidDynamicClassLoader
     * - runtime-repl is added only when REPL is explicitly enabled
     */
    private fun configureRuntimeDependencies(
        project: Project,
        variant: Variant,
        clojureOptions: ClojureOptionsExtension,
    ) {
        project.afterEvaluate {
            val isDebug = variant.buildType == "debug"
            val replEnabled = clojureOptions.replEnabled.orNull ?: isDebug
            // REPL requires dynamic compilation, so force it on when REPL is enabled
            val dynCompEnabled = replEnabled || (clojureOptions.dynamicCompilationEnabled.orNull ?: isDebug)

            // Always add runtime-core
            project.dependencies.add(
                "${variant.name}Implementation",
                RUNTIME_CORE,
            )

            // Always substitute stock Clojure with patched version.
            // The patched RT.makeClassLoader() falls back gracefully to stock
            // DynamicClassLoader when AndroidDynamicClassLoader is absent
            // (release builds), so it's safe for all variants. This also
            // ensures spec.alpha and core.specs.alpha are on the classpath
            // for AOT compilation.
            project.configurations.named("${variant.name}CompileClasspath").configure {
                resolutionStrategy.dependencySubstitution {
                    substitute(module("org.clojure:clojure"))
                        .using(module(PATCHED_CLOJURE))
                        .because("Patched Clojure with correct POM dependencies for AOT compilation")
                }
            }
            project.configurations.named("${variant.name}RuntimeClasspath").configure {
                resolutionStrategy.dependencySubstitution {
                    substitute(module("org.clojure:clojure"))
                        .using(module(PATCHED_CLOJURE))
                        .because("Patched Clojure with correct POM dependencies for AOT compilation")
                }
            }

            if (replEnabled) {
                // Add REPL infrastructure (nREPL server, Android socket stubs)
                project.dependencies.add(
                    "${variant.name}Implementation",
                    RUNTIME_REPL,
                )
                project.logger.info(
                    "Clojure REPL enabled for variant '${variant.name}' " +
                        "(port ${clojureOptions.nreplPort.get()})",
                )
            }

            if (dynCompEnabled && !replEnabled) {
                project.logger.info(
                    "Clojure dynamic compilation enabled for variant '${variant.name}' (no REPL)",
                )
            }

            if (!dynCompEnabled) {
                project.logger.info(
                    "Clojure dynamic compilation disabled for variant '${variant.name}' (AOT-only)",
                )
            }
        }
    }

    private fun registerClojureCompileTask(
        project: Project,
        variant: Variant,
        androidExtension: BaseExtension,
        clojureOptions: ClojureOptionsExtension,
    ) {
        val variantName = variant.name.replaceFirstChar { c -> c.uppercase() }

        // Determine which source sets contribute to this variant
        val sourceSetNames: List<String> = buildList {
            add("main")
            variant.productFlavors.forEach { (_, flavorName) -> add(flavorName) }
            variant.buildType?.let { bt -> add(bt) }
            if (variant.productFlavors.isNotEmpty()) {
                add(variant.name)
            }
        }

        val clojureSourceSets: List<ClojureSourceDirectorySet> = sourceSetNames.mapNotNull { ssName ->
            val sourceSet = androidExtension.sourceSets.findByName(ssName) ?: return@mapNotNull null
            (sourceSet as ExtensionAware).extensions.findByType(ClojureSourceDirectorySet::class.java)
        }

        val excludeNs: Set<String> = clojureOptions.aotExcludeNamespaces.get().toSet()

        val compileTask = project.tasks.register(
            "compile${variantName}Clojure",
            ClojureCompileTask::class.java,
        ) {
            description = "Compiles Clojure sources for the ${variant.name} variant"
            group = "build"

            warnOnReflection.set(clojureOptions.warnOnReflection)

            val allNamespaces: List<String> = clojureSourceSets
                .flatMap { ss: ClojureSourceDirectorySet -> ss.namespaces }
                .filter { ns: String -> ns !in excludeNs }
            namespaces.set(allNamespaces)

            destinationDir.set(
                project.layout.buildDirectory.dir("intermediates/clojure/${variant.name}/classes"),
            )

            for (sourceSet: ClojureSourceDirectorySet in clojureSourceSets) {
                compilationClasspath.from(sourceSet.classpath)
            }

            for (bootJar in androidExtension.bootClasspath) {
                compilationClasspath.from(bootJar)
            }
        }

        // Wire compiled Clojure classes into the build before dexing.
        // toAppend adds our output directory to PROJECT classes.
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(compileTask)
            .toAppend(
                ScopedArtifact.CLASSES,
                ClojureCompileTask::destinationDir,
            )

        // Add the project's dependency classpath so Clojure AOT can resolve references.
        project.afterEvaluate {
            compileTask.configure {
                val compileConfig = project.configurations.findByName("${variant.name}CompileClasspath")
                if (compileConfig != null) {
                    compilationClasspath.from(compileConfig)
                }

                // Add compiled Java classes from this project so Clojure can reference them.
                val javaCompileTaskName = "compile${variantName}JavaWithJavac"
                val javaCompileTask = project.tasks.findByName(javaCompileTaskName)
                if (javaCompileTask != null) {
                    val javacTask = javaCompileTask as org.gradle.api.tasks.compile.JavaCompile
                    compilationClasspath.from(javacTask.destinationDirectory)
                    dependsOn(javaCompileTask)
                }
            }
        }
    }
}
