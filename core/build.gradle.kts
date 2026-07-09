import fable.JvmdgLegTask
import fable.MergeMultiReleaseTask
import fable.VerifyDescriptorFloorTask
import fable.VerifyDowngradeTask
import fable.VerifyJdk8ApiTask
import fable.VerifyKernelPurityTask
import fable.VerifyNoStickyGetstaticTask
import fable.VerifyProbeIsolationTask
import fable.VerifyRelocationTask
import groovy.json.JsonSlurper
import java.util.UUID
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

// These projects' sourceSets/tasks are read at configuration time; none depend back on :core.
evaluationDependsOn(":api")
evaluationDependsOn(":platform")
evaluationDependsOn(":compat-folia")
evaluationDependsOn(":compat-modern")
evaluationDependsOn(":probe")

/*
 * support-matrix.json — the single machine-readable source of truth for supported
 * versions, their JDKs, CI lanes and the plugin.yml api-version floor.
 */
class SupportEntry(
    val version: String,
    val jdk: Int,
    val platform: String,
    val ci: String,
    val suites: String,
    val bytecodeTier: Int,
)

@Suppress("UNCHECKED_CAST")
val supportMatrix: Map<String, Any> =
    JsonSlurper().parse(rootProject.layout.projectDirectory.file("support-matrix.json").asFile) as Map<String, Any>

val floorApi: String = supportMatrix["floorApi"] as String

@Suppress("UNCHECKED_CAST")
val supportEntries: List<SupportEntry> =
    (supportMatrix["entries"] as List<Map<String, Any>>).map { e ->
        SupportEntry(
            version = e["version"] as String,
            jdk = (e["jdk"] as Number).toInt(),
            platform = e["platform"] as String,
            ci = e["ci"] as String,
            suites = e["suites"] as String,
            bytecodeTier = (e["bytecodeTier"] as Number).toInt(),
        )
    }

dependencies {
    api(project(":api"))
    api(project(":platform"))
    implementation(project(":kernel"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    // Soft-dep, never shaded: the class is FQN-loaded behind the PlaceholderAPI presence gate.
    compileOnly(libs.placeholderapi)

    // Shaded + relocated under dev.fablemc.factions.lib.*. Adventure's annotation-only
    // deps (jetbrains + jspecify) are compile-time metadata — excluded so they never bundle.
    implementation(libs.adventure.api) {
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.jspecify")
    }
    implementation(libs.adventure.minimessage) {
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.jspecify")
    }
    implementation(libs.adventure.serializer.legacy) {
        exclude(group = "org.jetbrains", module = "annotations")
        exclude(group = "org.jspecify")
    }
    implementation(libs.commons.dbcp2)
    implementation(libs.commons.pool2)
    // The official jdk8 build: pure Java-8 bytecode, current release, no natives.
    implementation(variantOf(libs.hsqldb) { classifier("jdk8") })
    implementation(libs.mysql)
    implementation(libs.bstats.bukkit)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.archunit)
    testImplementation(libs.paper.api.floor)
}

// The forked JvmDowngrader CLI (self-contained -all artifact).
val jvmdgCli = configurations.create("jvmdgCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    jvmdgCli(variantOf(libs.jvmdg.cli) { classifier("all") }) { isTransitive = false }
}

tasks.processResources {
    // plugin.yml api-version derives from support-matrix.json's floorApi.
    val props = mapOf("version" to project.version.toString(), "apiVersion" to floorApi)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

/*
 * shadowJar — relocate every third-party package under dev/fablemc/factions/lib/,
 * fold in the compat modules' classes, stage the intermediate out of build/libs.
 */
tasks.shadowJar {
    dependsOn(":compat-folia:classes", ":compat-modern:classes")
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("modern") // intermediate, not shipped
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))

    from(project(":compat-folia").sourceSets.main.get().output)
    from(project(":compat-modern").sourceSets.main.get().output)

    relocate("net.kyori", "dev.fablemc.factions.lib.adventure")
    relocate("org.bstats", "dev.fablemc.factions.lib.bstats")
    relocate("org.apache.commons.dbcp2", "dev.fablemc.factions.lib.dbcp2")
    relocate("org.apache.commons.pool2", "dev.fablemc.factions.lib.pool2")
    relocate("org.apache.commons.logging", "dev.fablemc.factions.lib.commonslogging")
    relocate("org.hsqldb", "dev.fablemc.factions.lib.hsqldb")
    relocate("com.mysql", "dev.fablemc.factions.lib.mysql")

    // Relocated JDBC drivers use explicit driverClassName; drop the service file and
    // third-party Multi-Release trees before the downgrade legs.
    exclude("META-INF/services/java.sql.Driver")
    exclude("META-INF/versions/**")
    exclude("module-info.class")

    // Optional subsystems of the shaded storage libs whose deps we neither bundle nor
    // can ignore (javax/* is a hard verifyJdk8Api tier): HSQLDB's servlet-mode entry
    // point and GUI/transfer tools, c3p0 integration, the commons-logging servlet
    // listener, and DBCP2's managed (XA/JTA) pool. Guarded-optional external SDK refs
    // (OCI, OpenTelemetry...) stay bundled and are --ignored in the gate instead.
    exclude("**/hsqldb/server/Servlet*")
    exclude("**/hsqldb/util/**")
    exclude("**/mysql/cj/jdbc/integration/**")
    exclude("**/commons/logging/impl/ServletContextCleaner*")
    exclude("**/dbcp2/managed/**")
    exclude("javax/transaction/**")
}

/*
 * The mega-jar pipeline: three independent jvmdg CLI legs off the shaded jar
 * (v52 / v57 / v61), merged into one Multi-Release jar:
 *     base = leg52 · versions/13 = leg57 diff · versions/17 = leg61 diff
 * Each leg forks a fresh JVM (see JvmdgLegTask for why that is load-bearing).
 */
val javaToolchains = extensions.getByType<JavaToolchainService>()
val jvmdgLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

// Supertype-resolution classpath: the compat modules' classes are folded into the
// shaded jar, so their (Folia/modern) compile classpaths must be unioned in.
val jvmdgResolveClasspath = sourceSets["main"].compileClasspath +
    project(":compat-folia").sourceSets["main"].compileClasspath +
    project(":compat-modern").sourceSets["main"].compileClasspath

fun jvmdgLeg(version: Int) = tasks.register<JvmdgLegTask>("jvmdgLeg$version") {
    group = "jvmdg"
    description = "Downgrades + shades the shaded jar to class version $version (forked CLI)."
    inputJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    classVersion.set(version)
    shadePrefix.set("dev/fablemc/factions/lib/jvmdg/")
    cliClasspath.from(jvmdgCli)
    resolveClasspath.from(jvmdgResolveClasspath)
    launcher.set(jvmdgLauncher)
    outputJar.set(layout.buildDirectory.file("jvmdg-stage/FableFactions-leg$version.jar"))
    logFile.set(layout.buildDirectory.file("jvmdg-stage/jvmdgLeg$version-output.log"))
}

val leg52 = jvmdgLeg(52)
val leg57 = jvmdgLeg(57)
val leg61 = jvmdgLeg(61)

val mergeTiers = tasks.register<MergeMultiReleaseTask>("mergeTiers") {
    group = "jvmdg"
    description = "Assembles the canonical Multi-Release mega jar (v52 base / v57 versions-13 / v61 versions-17)."
    baseJar.set(leg52.flatMap { it.outputJar })
    tier13Jar.set(leg57.flatMap { it.outputJar })
    tier17Jar.set(leg61.flatMap { it.outputJar })
    outputJar.set(layout.buildDirectory.file("libs/FableFactions-${project.version}.jar"))
}

// Every downstream consumer (gates, run tasks, `build`) reads the canonical jar.
val canonicalJar = mergeTiers.flatMap { it.outputJar }

tasks.build { dependsOn(mergeTiers) }

// The probe's final mega jar (typed lookup — the task classes live in buildSrc).
val probeMegaJar = project(":probe").tasks.named<MergeMultiReleaseTask>("mergeTiers")

/*
 * Verification gates — all wired into `check`, all cacheable task classes reading the
 * final mega jar(s), never the staged intermediates.
 */
val jdk8GateAsm = configurations.create("jdk8GateAsm") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { jdk8GateAsm(libs.asm) }

val serverProvidedIgnores = listOf(
    // Server-provided packages: present on the running server, never bundled or validated.
    "org/bukkit", "net/minecraft", "com/destroystokyo", "io/papermc", "org/spigotmc", "io/netty", "com/mojang",
    "org/jetbrains", "org/intellij", "org/jspecify", "org/checkerframework", "xyz/wagyourtail", "com/google",
    "net/md_5", "net/milkbowl", "com/sk89q", "me/clip", "org/dynmap", "com/earth2me", "github/scarsz",
    "com/griefcraft", "org/apache", "org/yaml",
    // Guarded-optional SDK integrations hard-referenced by bundled storage-lib classes
    // but never linked on the embedded-DB/JDBC path (MySQL OCI + OpenTelemetry,
    // commons-logging/slf4j, pool2/cglib). External, never on our path, allowlist stays empty.
    "com/oracle", "io/opentelemetry", "org/slf4j", "net/sf/cglib",
)

val gateReports = layout.buildDirectory.dir("verify-gates")

val relocationTokens = listOf(
    "net/kyori", "org/apache/commons/dbcp2", "org/apache/commons/pool2",
    "org/apache/commons/logging", "org/hsqldb", "com/mysql",
)

val verifyRelocation = tasks.register<VerifyRelocationTask>("verifyRelocation") {
    group = "verification"
    description = "Fails if any storage/adventure token survives outside the lib prefix."
    jarFile.set(canonicalJar)
    forbiddenTokens.set(relocationTokens)
    libPrefix.set("dev/fablemc/factions/lib/")
    report.set(gateReports.map { it.file("relocation.txt") })
}

val verifyDowngrade = tasks.register<VerifyDowngradeTask>("verifyDowngrade") {
    group = "verification"
    description = "Fails unless the mega jar is a well-formed MR tier set (base <=52, versions/13 <=57, versions/17 <=61, sentinel forked)."
    jarFile.set(canonicalJar)
    sentinel.set("dev/fablemc/factions/core/boot/FableFactionsPlugin.class")
    jvmdgPrefix.set("dev/fablemc/factions/lib/jvmdg/")
    expectTier13.set(true)
    report.set(gateReports.map { it.file("downgrade.txt") })
}

val verifyKernelPurity = tasks.register<VerifyKernelPurityTask>("verifyKernelPurity") {
    group = "verification"
    description = "Fails if any kernel class in the mega jar references org/bukkit."
    jarFile.set(canonicalJar)
    report.set(gateReports.map { it.file("kernel-purity.txt") })
}

val verifyProbeIsolation = tasks.register<VerifyProbeIsolationTask>("verifyProbeIsolation") {
    group = "verification"
    description = "D-8: probe carries no FableFactions jvmdg prefix; no un-relocated jvmdg stub descriptors in either mega jar."
    coreJar.set(canonicalJar)
    testerJar.set(probeMegaJar.flatMap { it.outputJar })
    report.set(gateReports.map { it.file("probe-isolation.txt") })
}

val verifyJdk8Api = tasks.register<VerifyJdk8ApiTask>("verifyJdk8Api") {
    group = "verification"
    description = "H1: every base-tree reference resolves in a real JDK-8 rt.jar, in-jar, or a server-provided package."
    coreJar.set(canonicalJar)
    testerJar.set(probeMegaJar.flatMap { it.outputJar })
    toolSrc.set(rootProject.layout.projectDirectory.file("scripts/tools/Jdk8ApiGate.java"))
    allowFile.set(rootProject.layout.projectDirectory.file("scripts/jdk8-api-gate.allow"))
    asmClasspath.from(jdk8GateAsm)
    jdk8Path.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }
        .map { it.metadata.installationPath.asFile.absolutePath })
    coreIgnores.set(serverProvidedIgnores)
    // The probe links kernel/api/platform/core classes the FableFactions jar provides
    // at runtime, so those are server-provided from its perspective.
    probeIgnores.set(serverProvidedIgnores + "dev/fablemc/factions/")
    classesDir.set(layout.buildDirectory.dir("jdk8-gate/classes"))
    report.set(gateReports.map { it.file("jdk8-api.txt") })
}

val floorGateClassDirs = files(
    sourceSets["main"].output.classesDirs,
    project(":platform").sourceSets["main"].output.classesDirs,
    project(":api").sourceSets["main"].output.classesDirs,
).builtBy(
    tasks.named("classes"),
    project(":platform").tasks.named("classes"),
    project(":api").tasks.named("classes"),
)

val verifyDescriptorFloor = tasks.register<VerifyDescriptorFloorTask>("verifyDescriptorFloor") {
    group = "verification"
    description = "AM-13: no baseline (non-@ProbeGated) Listener mentions a post-floor type in a descriptor."
    classDirs.from(floorGateClassDirs)
    floorSymbols.set(rootProject.layout.projectDirectory.file("scripts/floor-symbols.txt"))
    report.set(gateReports.map { it.file("descriptor-floor.txt") })
}

val verifyNoStickyGetstatic = tasks.register<VerifyNoStickyGetstaticTask>("verifyNoStickyGetstatic") {
    group = "verification"
    description = "AM-13: no GETSTATIC of a post-floor Bukkit enum constant in :core/:platform/:api classes."
    classDirs.from(floorGateClassDirs)
    floorSymbols.set(rootProject.layout.projectDirectory.file("scripts/floor-symbols.txt"))
    report.set(gateReports.map { it.file("sticky-getstatic.txt") })
}

tasks.named("check") {
    dependsOn(verifyRelocation, verifyDowngrade, verifyKernelPurity, verifyProbeIsolation,
        verifyJdk8Api, verifyDescriptorFloor, verifyNoStickyGetstatic)
}

/*
 * Live integration matrix (run-paper) — one RunServer per support-matrix entry with the
 * shipped mega jars installed. Registered but NOT wired into `check`.
 */
tasks.named<RunServer>("runServer") {
    enabled = false
    description = "Disabled — use integrationTest or integrationTestMatrix."
}

val integrationRunTasks = mutableListOf<TaskProvider<RunServer>>()

supportEntries.forEachIndexed { index, entry ->
    val suffix = (if (entry.platform == "folia") "Folia_" else "_") + entry.version.replace(".", "_")
    val nonce = UUID.randomUUID().toString()
    val runDir = rootProject.layout.projectDirectory.dir("run/${entry.platform}/${entry.version}").asFile
    val runTask = tasks.register<RunServer>("runIntegrationTest$suffix") {
        group = "fablefactions integration"
        description = "Boots ${entry.platform} ${entry.version} with FableFactions + probe and runs the suite."
        dependsOn(mergeTiers, probeMegaJar)
        runDirectory.set(runDir)
        // Folia downloads from its own project; set the source BEFORE the version.
        if (entry.platform == "folia") downloadsApiService.set(DownloadsAPIService.folia(project))
        minecraftVersion(entry.version)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(entry.jdk)) })
        // A per-entry port so a lingering socket (TIME_WAIT, a stray local server) never
        // fails the next lane's bind.
        args("--port", (25580 + index).toString())
        // tier is the Multi-Release bytecodeTier this loader x JVM reads; the probe asserts
        // it live, writes probe-results.txt, then stops the server (probe.shutdown).
        jvmArgs(
            "-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G",
            "-Dfablefactions.probe.nonce=$nonce", "-Dfablefactions.probe.suites=${entry.suites}",
            "-Dfablefactions.probe.tier=${entry.bytecodeTier}",
            "-Dfablefactions.probe.shutdown=true",
        )
        pluginJars.from(canonicalJar)
        pluginJars.from(probeMegaJar.flatMap { it.outputJar })
    }
    // Order after EVERY earlier lane (not just the immediate predecessor) so any subset
    // of lanes — like integrationTest's floor+ceiling pick — still runs one at a time.
    val priors = integrationRunTasks.toList()
    if (priors.isNotEmpty()) runTask.configure { mustRunAfter(priors) }
    integrationRunTasks.add(runTask)
}

tasks.register("integrationTest") {
    group = "fablefactions integration"
    description = "Boots the modern floor + ceiling (PR smoke). NOT wired into check."
    val modernFloor = supportEntries.first { it.platform == "paper" && it.bytecodeTier == 61 }.version
    val ceiling = supportEntries.last { it.platform == "paper" }.version
    val pick = setOf(modernFloor, ceiling)
    dependsOn(integrationRunTasks.filter { rt -> pick.any { rt.name.endsWith("_" + it.replace(".", "_")) } })
}

tasks.register("integrationTestMatrix") {
    group = "fablefactions integration"
    description = "Boots every entry in support-matrix.json (paper + folia). NOT wired into check."
    dependsOn(integrationRunTasks)
}
