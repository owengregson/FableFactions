pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // Auto-provisions the per-module toolchains (build JDK 25, the JDK-8 gate runtime)
    // from a real vendor download when a matching JDK is not already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "FableFactions"

include(":kernel", ":api", ":platform", ":core", ":compat-folia", ":compat-modern", ":probe")

// The compat modules live under compat/<flavour> but keep flat Gradle path ids.
project(":compat-folia").projectDir = file("compat/folia")
project(":compat-modern").projectDir = file("compat/modern")
