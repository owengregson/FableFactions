plugins { `java-library` }

dependencies {
    // The seam is Bukkit-facing but kernel-FREE (CONTRACTS §3): it holds Scheduling,
    // Capabilities, resolvers, TextPort, MenuModel, Scope. TextPort's Component type is
    // the shaded/relocated Adventure — api(adventure-api) so downstream core sees it;
    // core does the relocation. No project(":kernel") dependency (W1b is kernel-free).
    compileOnly(libs.paper.api.floor)
    api(libs.adventure.api)
    // TextPort (AM-1) renders via LegacyComponentSerializer, which lives in the legacy
    // serializer artifact, NOT adventure-api. api() so it is on the compile classpath here
    // and flows to core, which shades+relocates the whole net.kyori tree.
    api(libs.adventure.serializer.legacy)
    compileOnly(libs.jetbrains.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Floor Bukkit API for the JVM-only resolver tests (Nametags budget math loads a
    // Material/Team-referencing class initializer; no live server is started).
    testImplementation(libs.paper.api.floor)
}
