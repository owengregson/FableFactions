allprojects {
    group = "dev.fablemc"
    // The single version home is gradle.properties; every module reads it here.
    version = providers.gradleProperty("version").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // PlaceholderAPI (compileOnly soft-dep for the typed expansion subclass).
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        // BUILD JDK, not a support-matrix member: the toolchain that COMPILES the
        // plugin (25 = the newest matrix runtime's required JDK). The per-entry
        // RUNTIME JDKs live only in support-matrix.json.
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        // COMPILE FLOOR, not a support-matrix member: javac emits class major 61
        // (Java 17), the bytecode target that keeps the jar loadable on the matrix
        // floor (Java 17, Paper 1.17.1). The v52 base tree and the v57 versions/13
        // tier are produced DOWNSTREAM by JVMDowngrader, never by javac — there is
        // no release.set(8) anywhere.
        options.release.set(17)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
        // Fork test classes across half the cores (capped): the kernel property
        // suites are CPU-bound and embarrassingly parallel at class granularity.
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}
