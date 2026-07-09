plugins { `java-library` }

dependencies {
    // The seam is Bukkit-facing but kernel-free (CONTRACTS §3). TextPort's Component
    // type is the shaded/relocated Adventure: api() so downstream core sees it; core
    // does the relocation. The legacy serializer lives in its own artifact, not
    // adventure-api, and TextPort renders through it (AM-1).
    compileOnly(libs.paper.api.floor)
    // Annotation-only transitive deps excluded so they never reach core's shade.
    api(libs.adventure.api) {
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.jspecify")
    }
    api(libs.adventure.serializer.legacy) {
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.jspecify")
    }
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Floor Bukkit API for the JVM-only resolver tests (no live server is started).
    testImplementation(libs.paper.api.floor)
}
