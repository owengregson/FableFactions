import org.gradle.api.attributes.java.TargetJvmVersion

allprojects {
    group = "dev.fablemc"
    // Single version home: gradle.properties.
    version = providers.gradleProperty("version").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        // Build JDK (compiles everything); per-entry runtime JDKs live in support-matrix.json.
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        // Compile floor: javac emits v61 (Java 17). The v52 base tree and the v57
        // versions/13 tier are produced downstream by the jvmdg legs, never by javac.
        options.release.set(17)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    // Dependencies like Adventure 5 and paper-api 1.20.6 publish jvm.version=21
    // metadata, which release=17 consumers would reject. The build toolchain (25) is
    // the honest consumer level; the actual bytecode floor is enforced by the jvmdg
    // legs and the verify gates, not by this attribute.
    configurations.configureEach {
        if (isCanBeResolved) {
            attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}
