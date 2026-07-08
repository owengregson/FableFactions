plugins { `java-library` }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.jqwik)
}

// ── Resolution firewall (ARCHITECTURE §1/§3, mental-build §8) ─────────────────────
// The kernel realm is pure JDK: state records, COW structures, the Reducer, rules,
// math, ConfigImage. It may NEVER see Bukkit, Adventure, or any JDBC driver — that
// purity is what lets the single writer thread run the reducer with no Bukkit API in
// reach, and it is verified again at the bytecode level by verifyKernelPurity. Fail
// the build here the instant a forbidden group lands on ANY kernel configuration.
configurations.all {
    resolutionStrategy.eachDependency {
        val g = requested.group
        require(!g.startsWith("io.papermc"))        { "kernel must stay Bukkit-free (io.papermc)" }
        require(!g.startsWith("org.spigotmc"))      { "kernel must stay Bukkit-free (org.spigotmc)" }
        require(!g.startsWith("com.destroystokyo"))  { "kernel must stay Bukkit-free (com.destroystokyo)" }
        require(!g.startsWith("net.kyori"))          { "kernel must stay Adventure-free (net.kyori)" }
        require(!g.startsWith("com.zaxxer"))         { "kernel must stay JDBC-free (com.zaxxer)" }
        require(!g.startsWith("com.h2database"))     { "kernel must stay JDBC-free (com.h2database)" }
        require(!g.startsWith("com.mysql"))          { "kernel must stay JDBC-free (com.mysql)" }
    }
}
