package org.clojure_android.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Gradle task that AOT-compiles Clojure namespaces to JVM bytecode.
 *
 * Invokes `clojure.lang.Compile` in a forked JVM process with the
 * project's classpath (compiled Java classes, Clojure sources, and the
 * Android boot classpath).
 */
abstract class ClojureCompileTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** Combined classpath: Java compiled output + Clojure source dirs + android.jar. */
    @get:Classpath
    abstract val compilationClasspath: ConfigurableFileCollection

    /** Fully-qualified Clojure namespace names to compile. */
    @get:Input
    abstract val namespaces: ListProperty<String>

    /** Emit reflection warnings during compilation. */
    @get:Input
    abstract val warnOnReflection: Property<Boolean>

    /** Directory where compiled .class files are written. */
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    init {
        warnOnReflection.convention(false)
    }

    @TaskAction
    fun compile() {
        val nsList = namespaces.get()
        if (nsList.isEmpty()) {
            logger.info("No Clojure namespaces to compile, skipping.")
            return
        }

        val destDir = destinationDir.get().asFile
        destDir.mkdirs()

        // Build the effective classpath, extracting classes.jar from AARs
        val effectiveClasspath = buildEffectiveClasspath()

        logger.info("Compiling ${nsList.size} Clojure namespace(s) to $destDir")
        logger.info("Namespaces: $nsList")

        execOperations.javaexec {
            mainClass.set("clojure.lang.Compile")
            classpath(effectiveClasspath)
            systemProperty("clojure.compile.path", destDir.absolutePath)
            systemProperty(
                "clojure.compile.warn-on-reflection",
                warnOnReflection.get().toString(),
            )
            args(nsList)
        }
    }

    /**
     * Processes the compilation classpath, replacing .aar files with their
     * extracted classes.jar. The JVM classpath can't use AAR files directly,
     * but the Clojure AOT compiler needs the classes and resources inside them.
     */
    private fun buildEffectiveClasspath(): List<File> {
        val aarExtractDir = File(temporaryDir, "aar-jars")
        aarExtractDir.mkdirs()

        return compilationClasspath.files.map { file ->
            if (file.name.endsWith(".aar") && file.exists()) {
                extractClassesJar(file, aarExtractDir)
            } else {
                file
            }
        }
    }

    /**
     * Extracts classes.jar from an AAR file into a unique directory.
     * Returns the path to the extracted JAR.
     */
    private fun extractClassesJar(aarFile: File, extractDir: File): File {
        val targetDir = File(extractDir, aarFile.nameWithoutExtension)
        val targetJar = File(targetDir, "classes.jar")

        if (targetJar.exists() && targetJar.lastModified() >= aarFile.lastModified()) {
            return targetJar
        }

        targetDir.mkdirs()
        ZipFile(aarFile).use { zip ->
            val entry = zip.getEntry("classes.jar")
            if (entry != null) {
                zip.getInputStream(entry).use { input ->
                    targetJar.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                logger.info("Extracted classes.jar from ${aarFile.name}")
            } else {
                logger.warn("No classes.jar found in ${aarFile.name}")
                return aarFile
            }
        }

        return targetJar
    }
}
