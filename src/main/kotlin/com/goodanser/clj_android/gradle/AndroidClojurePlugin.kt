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
import org.gradle.api.tasks.PathSensitivity

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
        checkForStaleDaemon(project)

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

    /**
     * Detects when the Gradle daemon has loaded a stale version of this plugin.
     *
     * The plugin JAR contains a build-info.properties with the compile timestamp.
     * If the plugin is consumed via an included build and any source file in that
     * build is newer than the baked-in timestamp, the daemon's classloader is
     * serving an outdated version.  Warn the developer to restart the daemon.
     */
    private fun checkForStaleDaemon(project: Project) {
        try {
            val props = java.util.Properties()
            val stream = AndroidClojurePlugin::class.java.classLoader
                .getResourceAsStream("android-clojure-plugin-build-info.properties")
                ?: return
            stream.use { props.load(it) }
            val buildTimestamp = props.getProperty("build.timestamp")?.toLongOrNull() ?: return

            // Find the plugin's source directory via the included build.
            val pluginBuild = project.gradle.includedBuilds.firstOrNull { included ->
                included.name == "android-clojure-plugin"
            } ?: return

            val sourceDir = java.io.File(pluginBuild.projectDir, "src/main/kotlin")
            if (!sourceDir.isDirectory) return

            val newestSource = sourceDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .maxOfOrNull { it.lastModified() }
                ?: return

            if (newestSource > buildTimestamp) {
                project.logger.warn(
                    "android-clojure-plugin: source files are newer than the loaded plugin. " +
                        "The Gradle daemon may be serving a stale version. " +
                        "Run './gradlew --stop' and rebuild.",
                )
            }
        } catch (_: Exception) {
            // Non-fatal — skip the check silently.
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

        // Register Clojure source directories as Android Java resources so
        // .clj files are bundled into the AAR/APK and loadable at runtime.
        androidExtension.sourceSets.configureEach {
            val ext = (this as ExtensionAware).extensions
                .findByType(ClojureSourceDirectorySet::class.java)
            if (ext != null) {
                resources.srcDirs(ext.classpath.files.filter { it.isDirectory })
            }
        }

        androidComponents.onVariants { variant ->
            registerClojureCompileTask(project, variant, androidExtension, clojureOptions)
            configureRuntimeDependencies(project, variant, clojureOptions)
            fixJavaResourceTracking(project, variant, androidExtension)
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
            } else {
                // Make REPL library available for AOT compilation only — namespaces
                // that require clj-android.repl.server can compile, but the library
                // won't be shipped in the APK.  At runtime, repl-available? returns
                // false when the nREPL classes are absent.
                project.dependencies.add(
                    "${variant.name}CompileOnly",
                    RUNTIME_REPL,
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

    /**
     * AGP's processJavaRes and mergeJavaResource tasks don't always detect
     * changes to files in resources.srcDirs, causing stale .clj files in
     * incremental builds.  Explicitly declare Clojure source trees as tracked
     * task inputs so Gradle invalidates the tasks when sources change.
     */
    private fun fixJavaResourceTracking(
        project: Project,
        variant: Variant,
        androidExtension: BaseExtension,
    ) {
        val variantName = variant.name.replaceFirstChar { c -> c.uppercase() }

        project.afterEvaluate {
            // Collect all Clojure source directories across source sets.
            val clojureDirs = androidExtension.sourceSets
                .flatMap { sourceSet ->
                    val ext = (sourceSet as ExtensionAware).extensions
                        .findByType(ClojureSourceDirectorySet::class.java)
                    ext?.classpath?.files?.filter { it.isDirectory } ?: emptySet()
                }

            if (clojureDirs.isNotEmpty()) {
                val clojureFiles = project.files(clojureDirs.map { dir ->
                    project.fileTree(dir) { include("**/*.clj") }
                })

                // Track .clj source changes in processJavaRes.
                project.tasks.matching { it.name == "process${variantName}JavaRes" }.configureEach {
                    inputs.files(clojureFiles)
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                        .withPropertyName("clojureSources")
                }
            }

            // For app projects, also track library .clj resources flowing
            // through mergeJavaResource so the merge re-runs when an
            // included library build updates its .clj files.
            val isApp = project.plugins.hasPlugin("com.android.application")
            if (isApp) {
                project.tasks.matching { it.name == "merge${variantName}JavaResource" }.configureEach {
                    val runtimeConfig = project.configurations.findByName("${variant.name}RuntimeClasspath")
                    if (runtimeConfig != null) {
                        inputs.files(runtimeConfig)
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                            .withPropertyName("runtimeClasspathForClojure")
                    }
                }
            }
        }
    }
}
