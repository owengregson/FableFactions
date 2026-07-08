plugins { `java-library` }

dependencies {
    // The public API surface re-exports the kernel vocabulary it references (CONTRACTS §5).
    api(project(":kernel"))
    // Bukkit-facing: compile against the 1.13 floor + annotations only; no runtime deps.
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
