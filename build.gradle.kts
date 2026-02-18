plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "org.clojure-android"
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

gradlePlugin {
    plugins {
        create("androidClojure") {
            id = "org.clojure-android.android-clojure"
            implementationClass = "org.clojure_android.gradle.AndroidClojurePlugin"
            displayName = "Android Clojure Plugin"
            description = "Integrates Clojure AOT compilation into Android Gradle builds"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "test"
            url = uri(layout.buildDirectory.dir("test-repo"))
        }
    }
}
