package org.clojure_android.gradle

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import java.io.File
import java.net.URI

/**
 * Manages Clojure source directories for a single Android source set.
 *
 * Default directory: `src/{sourceSetName}/clojure`.
 * Detects Clojure namespaces by scanning for `.clj` files and converting
 * file paths to namespace names (replacing `/` with `.` and `_` with `-`).
 */
class ClojureSourceDirectorySet(
    val name: String,
    private val project: Project,
) {
    private val directories: MutableList<Any> = mutableListOf("src/$name/clojure")

    fun srcDir(dir: Any) {
        directories.add(dir)
    }

    fun srcDirs(vararg dirs: Any) {
        directories.addAll(dirs)
    }

    fun setSrcDirs(dirs: Iterable<Any>) {
        directories.clear()
        dirs.forEach { directories.add(it) }
    }

    /** Returns the source directories as a FileCollection for use on the classpath. */
    val classpath: FileCollection
        get() = project.files(*directories.toTypedArray())

    /** Scans source directories for .clj files and returns their namespace names. */
    val namespaces: List<String>
        get() = classpath.files.flatMap { srcDir -> namespacesIn(srcDir) }

    private fun namespacesIn(srcDir: File): List<String> {
        if (!srcDir.isDirectory) return emptyList()
        val srcDirUri: URI = srcDir.toURI()
        val tree = project.fileTree(srcDir).matching { include("**/*.clj") }
        return tree.map { file ->
            srcDirUri.relativize(file.toURI()).toString()
                .removeSuffix(".clj")
                .replace('/', '.')
                .replace('_', '-')
        }
    }

    override fun toString(): String = directories.toString()
}
