plugins { `java-library` }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.jqwik)
}

// Resolution firewall: the kernel realm is pure JDK and may never see Bukkit,
// Adventure, or any JDBC driver (verified again at the bytecode level by
// verifyKernelPurity). Fail the instant a forbidden group lands on any configuration.
configurations.all {
    resolutionStrategy.eachDependency {
        val g = requested.group
        require(!g.startsWith("io.papermc"))        { "kernel must stay Bukkit-free (io.papermc)" }
        require(!g.startsWith("org.spigotmc"))      { "kernel must stay Bukkit-free (org.spigotmc)" }
        require(!g.startsWith("com.destroystokyo")) { "kernel must stay Bukkit-free (com.destroystokyo)" }
        require(!g.startsWith("net.kyori"))         { "kernel must stay Adventure-free (net.kyori)" }
        require(!g.startsWith("com.zaxxer"))        { "kernel must stay JDBC-free (com.zaxxer)" }
        require(!g.startsWith("org.hsqldb"))        { "kernel must stay JDBC-free (org.hsqldb)" }
        require(!g.startsWith("com.mysql"))         { "kernel must stay JDBC-free (com.mysql)" }
    }
}
