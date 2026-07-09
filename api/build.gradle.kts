plugins { `java-library` }

dependencies {
    // The public API surface re-exports the kernel vocabulary it references (CONTRACTS §5).
    api(project(":kernel"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
