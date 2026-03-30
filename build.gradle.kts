plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.goodanser.clj-android"
version = "0.5.0-SNAPSHOT"
description = "Clojure plugin for the Gradle-based Android build system"

repositories {
    google()
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.9.0")
    testImplementation("com.android.tools.build:gradle:8.9.0")
    testImplementation("junit:junit:4.13.2")
}

// Generate build-info.properties so the plugin can detect stale daemon classloaders.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    val propsFile = outputDir.map { it.file("android-clojure-plugin-build-info.properties") }
    outputs.dir(outputDir)
    // Re-run whenever any plugin source file changes.
    inputs.files(fileTree("src/main/kotlin") { include("**/*.kt") })
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    doLast {
        val f = propsFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText("build.timestamp=${System.currentTimeMillis()}\n")
    }
}
sourceSets["main"].resources.srcDir(generateBuildInfo.map { it.outputs.files.singleFile })

gradlePlugin {
    plugins {
        create("androidClojure") {
            id = "com.goodanser.clj-android.android-clojure"
            implementationClass = "com.goodanser.clj_android.gradle.AndroidClojurePlugin"
            displayName = "Android Clojure Plugin"
            description = "Integrates Clojure AOT compilation into Android Gradle builds"
        }
    }
}

// Test publishing repo — only configured when explicitly requested via
// -PpublishToTestRepo to avoid F-Droid scanner flagging it as an unknown
// maven repository.
if (findProperty("publishToTestRepo") != null) {
    publishing {
        repositories {
            maven {
                name = "test"
                url = uri(layout.buildDirectory.dir("test-repo"))
            }
        }
    }
}
