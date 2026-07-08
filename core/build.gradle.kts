import groovy.json.JsonSlurper
import java.util.UUID
import java.util.jar.JarFile
import java.util.zip.ZipFile
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderExtension

// ASM on the SCRIPT classpath so the AM-13 floor gates can run as in-process
// ClassVisitor tasks over the compiled :core/:platform/:api classes.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.7") }
}

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.jvmdowngrader)
}

// Evaluate the projects whose sourceSets/tasks this script reads at configuration time.
// None depend back on :core, so there is no evaluation cycle.
evaluationDependsOn(":api")
evaluationDependsOn(":platform")
evaluationDependsOn(":compat-folia")
evaluationDependsOn(":compat-modern")
evaluationDependsOn(":probe")

/* ────────────────────────────────────────────────────────────────────────
 *  support-matrix.json — THE single machine-readable source of truth. Every
 *  version, its JDK, its CI lane and the plugin.yml api-version floor come from
 *  here; no Minecraft version or JDK literal lives anywhere else in the build.
 * ──────────────────────────────────────────────────────────────────────── */
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

    // Shaded + relocated (dev.fablemc.factions.lib.*). Adventure carries the self-
    // contained legacy-string serializer for below-1.16.5 servers; the annotations it
    // drags in are compile-time only, excluded so they never bundle.
    implementation(libs.adventure.api) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.adventure.minimessage) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.adventure.serializer.legacy) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.hikaricp)
    implementation(libs.slf4j.nop)   // provides the relocated slf4j binder (see catalog note)
    implementation(libs.h2)
    implementation(libs.mysql)
    implementation(libs.bstats.bukkit)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.archunit)
    testImplementation(libs.paper.api.floor)
}

tasks.processResources {
    // api-version derives from support-matrix.json's floorApi — the descriptor owns
    // the Bukkit floor, not a hardcoded literal.
    val props = mapOf("version" to project.version.toString(), "apiVersion" to floorApi)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

/* ────────────────────────────────────────────────────────────────────────
 *  shadowJar — relocate every third-party package under dev/fablemc/factions/lib/,
 *  fold in the compat modules' classes, stage the intermediate OUT of build/libs.
 * ──────────────────────────────────────────────────────────────────────── */
tasks.shadowJar {
    dependsOn(":compat-folia:classes", ":compat-modern:classes")
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("modern")                                  // intermediate, NOT shipped
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))

    from(project(":compat-folia").sourceSets.main.get().output)
    from(project(":compat-modern").sourceSets.main.get().output)

    relocate("net.kyori", "dev.fablemc.factions.lib.adventure")
    relocate("org.bstats", "dev.fablemc.factions.lib.bstats")
    relocate("com.zaxxer.hikari", "dev.fablemc.factions.lib.hikari")
    relocate("org.h2", "dev.fablemc.factions.lib.h2")
    relocate("com.mysql", "dev.fablemc.factions.lib.mysql")
    // HikariCP 4.x transitively needs slf4j-api — shade it too (AM-10).
    relocate("org.slf4j", "dev.fablemc.factions.lib.slf4j")

    // AM-10: the relocated JDBC drivers use explicit driverClassName; drop the service
    // file, and strip third-party Multi-Release trees before the downgrade input.
    exclude("META-INF/services/java.sql.Driver")
    exclude("META-INF/versions/**")
    exclude("module-info.class")

    // Optional integration classes in the shaded storage libs that reference UNBUNDLED
    // third-party types the embedded-DB/JDBC path never links. Two disposal routes, and
    // the choice is FORCED by whether the un-resolvable dep is ignorable:
    //   (1) EXCLUDE the class when its un-resolvable dep is an un-ignorable HARD package.
    //       MANDATORY for the H2 web console — WebServer pulls in javax.servlet, and javax/
    //       is a HARD verifyJdk8Api tier that no --ignore can silence, so the only fix is to
    //       strip the console subsystem (the web servlet + the tools that wrap it: Server,
    //       Console, GUIConsole, Shell). Also strips build-time / OSGi entry points nothing
    //       on the JDBC path references (JavassistProxyFactory, the OSGi activators).
    //   (2) BUNDLE the class and --ignore the genuinely-external optional package it names
    //       (guarded-optional, never linked on our path): HikariPool → the Codahale/
    //       Micrometer metric registries, H2 ValueGeometry → org.locationtech JTS, and the
    //       MySQL OCI auth plugin's AuthenticationOciClient → com.oracle.bmc. Route (2) keeps
    //       the library's OWN code whole and ignores only the truly-external SDK — preferred
    //       wherever the missing dep is ignorable. See serverProvidedIgnores below.
    // When a route-(1) stripped class is itself referenced by a bundled CORE class (H2
    // Database's AUTO_SERVER branch holds the ONLY in-jar reference to the excluded
    // tools/Server), that one dead relocated reference is --ignored via excludedFeatureRefs
    // below — scoped to the exact stripped class so any OTHER missing relocated class (a real
    // shade-forgot-to-bundle bug) still fails the gate.
    // '**/' patterns match both the original and relocated paths (shadow filters on either).
    exclude("**/h2/server/web/**")                         // javax.servlet (web console)
    exclude("**/h2/tools/Server*")                         // wraps the web/tcp/pg servers
    exclude("**/h2/tools/Console*")                        // launches the web console
    exclude("**/h2/tools/GUIConsole*")                     // AWT tray console
    exclude("**/h2/tools/Shell*")                          // references server/web ConnectionInfo
    exclude("**/h2/util/DbDriverActivator*")               // org.osgi BundleActivator
    exclude("**/h2/util/OsgiDataSourceFactory*")           // org.osgi.service.jdbc
    exclude("**/hikari/metrics/prometheus/**")             // io.prometheus (not linked by core)
    exclude("**/hikari/hibernate/**")                      // org.hibernate ConnectionProvider
    exclude("**/hikari/util/JavassistProxyFactory*")       // org.javassist (build-time proxy gen)
    exclude("**/mysql/cj/jdbc/integration/**")             // com.mchange c3p0 ConnectionTester
    // AuthenticationOciClient stays BUNDLED (route 2): it names only com.oracle.bmc
    // externally, which verifyJdk8Api ignores as a guarded-optional SDK (never linked on the
    // native/basic-auth path). Excluding it would dangle NativeAuthenticationProvider's ref.
}

/* ────────────────────────────────────────────────────────────────────────
 *  The mega-jar pipeline: shadowJar → jvmdg DowngradeJar (v61→v52 MR) →
 *  jvmdg ShadeJar (relocate jvmdg runtime) → canonical FableFactions-<v>.jar.
 * ──────────────────────────────────────────────────────────────────────── */

fun failOnJvmdgWarnings(jar: Jar) {
    val captured = StringBuilder()
    val sink = StandardOutputListener { text -> captured.append(text) }
    val logSink = jar.project.layout.buildDirectory.file("jvmdg-stage/${jar.name}-output.log")
    jar.doFirst {
        logging.addStandardOutputListener(sink)
        logging.addStandardErrorListener(sink)
    }
    jar.doLast {
        logging.removeStandardOutputListener(sink)
        logging.removeStandardErrorListener(sink)
        val text = captured.toString()
        val file = logSink.get().asFile
        file.parentFile.mkdirs()
        file.writeText(text)
        // JDK-24+ JEP-498 sun.misc.Unsafe deprecation notes from PARALLEL test JVMs leak
        // into this global console capture (a task LoggingManager listener is not
        // task-scoped) and are not jvmdg output: filter them, keep the rest.
        val jvmNoise = Regex("sun\\.misc\\.Unsafe|Please consider reporting this to the maintainers")
        val warnings = text.lines()
            .filter { line -> Regex("(?i)\\b(warn|warning|error)\\b").containsMatchIn(line) }
            .filterNot { line -> jvmNoise.containsMatchIn(line) }
        if (warnings.isNotEmpty()) {
            throw GradleException("jvmdowngrader emitted ${warnings.size} warning/error line(s) during " +
                "'${jar.name}' (warnings are build failures). First:\n" +
                warnings.take(20).joinToString("\n") { "    $it" } + "\nFull: ${file.absolutePath}")
        }
    }
}

fun mrStrip(name: String): String {
    if (!name.startsWith("META-INF/versions/")) return name
    val rest = name.substringAfter("META-INF/versions/")
    val slash = rest.indexOf('/')
    return if (slash < 0) rest else rest.substring(slash + 1)
}
fun classMajor(bytes: ByteArray): Int =
    ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
fun isFirstParty(logical: String): Boolean =
    logical.startsWith("dev/fablemc/factions/") &&
        !logical.startsWith("dev/fablemc/factions/lib/") &&
        !logical.startsWith("dev/fablemc/factions/probe/lib/")

val jvmdg = extensions.getByType<JVMDowngraderExtension>()

// DowngradeJar (input defaults to shadowJar; retarget explicitly). base = v52 (Java 8) +
// versions/17 = the untouched original v61. multiReleaseOriginal WITHOUT
// multiReleaseVersions: 1.3.6 treats the two as mutually exclusive and the latter would
// DROP the original v61, silently downgrading the modern path.
val downgradeMegaJar = jvmdg.defaultTask
downgradeMegaJar.configure {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    multiReleaseOriginal.set(true)
    // jvmdg must resolve supertypes of every referenced type. shadowJar folded the
    // compat modules' classes in, whose supertypes are the Folia/modern APIs (absent
    // from core's 1.13 floor). Union all three compile classpaths so it resolves with
    // ZERO warnings.
    classpath = sourceSets["main"].compileClasspath +
        project(":compat-folia").sourceSets["main"].compileClasspath +
        project(":compat-modern").sourceSets["main"].compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("downgraded")
    failOnJvmdgWarnings(this)
}

// ShadeJar — relocate jvmdg's runtime helpers under our lib prefix, emit the canonical jar.
val megaJar = jvmdg.defaultShadeTask
megaJar.configure {
    inputFile.set(downgradeMegaJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    shadePath.set { "dev/fablemc/factions/lib/jvmdg/" }
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("")
    failOnJvmdgWarnings(this)
}

tasks.build { dependsOn(megaJar) }

// The probe's FINAL mega jar — looked up by task NAME (jvmdg names its default shade
// task identically in every project, so megaJar.name is the probe's too), NOT by
// extensions.getByType<JVMDowngraderExtension>() which would resolve the wrong
// classloader cross-project. AbstractArchiveTask is a Gradle core type (shared loader).
val probeMegaJar = project(":probe").tasks.named<AbstractArchiveTask>(megaJar.name)

/* ────────────────────────────────────────────────────────────────────────
 *  Verification gates — all wired into `check`. Each reads the FINAL mega jar(s),
 *  never the staged intermediates. Class bytes are read as ISO-8859-1 (a lossless
 *  byte↔char map) so a substring match equals an exact constant-pool byte match.
 * ──────────────────────────────────────────────────────────────────────── */

// verifyRelocation — no un-relocated storage/adventure token survives the shade.
val verifyRelocation = tasks.register("verifyRelocation") {
    group = "verification"
    description = "Fails if net/kyori, com/zaxxer, org/h2, com/mysql or org/slf4j survives outside the lib prefix."
    dependsOn(megaJar)
    val jarFile = megaJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val libPrefix = "dev/fablemc/factions/lib/"
        val tokens = listOf("net/kyori", "com/zaxxer", "org/h2", "com/mysql", "org/slf4j")
        val entryViolations = mutableListOf<String>()
        val refViolations = mutableListOf<String>()
        ZipFile(jarFile.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val logical = mrStrip(name)
                if (logical.startsWith(libPrefix)) continue
                val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                for (t in tokens) {
                    if (text.contains(t) || text.contains(t.replace('/', '.'))) {
                        refViolations.add("$name → $t"); break
                    }
                }
            }
            // Entry-name scan: no un-relocated storage/adventure package directory survives.
            val es2 = zip.entries()
            while (es2.hasMoreElements()) {
                val logical = mrStrip(es2.nextElement().name)
                if (tokens.any { logical.startsWith("$it/") }) entryViolations.add(logical)
            }
        }
        if (entryViolations.isNotEmpty() || refViolations.isNotEmpty()) {
            throw GradleException(buildString {
                append("Relocation rot: un-relocated token(s) survived the shade.\n")
                entryViolations.take(20).forEach { append("  entry  $it\n") }
                refViolations.take(20).forEach { append("  ref    $it\n") }
                append("Everything third-party MUST relocate under $libPrefix.")
            })
        }
        logger.lifecycle("[relocation] clean — no un-relocated storage/adventure token in ${jarFile.get().asFile.name}.")
    }
}

// verifyDowngrade — the Multi-Release tier shape: base ≤ v52, versions/17 first-party
// == v61 and a subset of base, sentinel forked 52/61, H4 no reflective-record token.
val verifyDowngrade = tasks.register("verifyDowngrade") {
    group = "verification"
    description = "Fails unless the mega jar is a well-formed MR tier set (base ≤52, versions/17 == v61 subset, sentinel forked)."
    dependsOn(megaJar)
    val jarFile = megaJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val file = jarFile.get().asFile
        val problems = mutableListOf<String>()
        val sentinel = "dev/fablemc/factions/core/boot/FableFactionsPlugin.class"
        var baseSentinelMajor = -1
        var v17SentinelMajor = -1
        val baseFirstParty = sortedSetOf<String>()
        val v17FirstParty = sortedSetOf<String>()
        val baseBytesByLogical = mutableMapOf<String, ByteArray>()

        JarFile(file).use { jar ->
            val mr = jar.manifest?.mainAttributes?.getValue("Multi-Release")
            if (!"true".equals(mr, ignoreCase = true)) problems.add("manifest Multi-Release is '$mr' (expected true)")
        }
        ZipFile(file).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val logical = mrStrip(name)
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val major = classMajor(bytes)
                when {
                    name.startsWith("META-INF/versions/17/") -> {
                        if (isFirstParty(logical)) {
                            v17FirstParty.add(logical)
                            if (major != 61) problems.add("versions/17 first-party $logical is v$major (expected 61)")
                        }
                        if (name == "META-INF/versions/17/$sentinel") v17SentinelMajor = major
                    }
                    name.startsWith("META-INF/versions/") ->
                        problems.add("unexpected versioned tier entry $name (only versions/17 is expected)")
                    else -> {
                        if (major > 52) problems.add("base entry $logical is v$major (>52)")
                        if (isFirstParty(logical)) {
                            baseFirstParty.add(logical)
                            baseBytesByLogical[logical] = bytes
                            // H4: a downgraded record is NOT a reflective record (Class.isRecord()==false),
                            // so a first-party base class must never reflectively introspect records.
                            val text = String(bytes, Charsets.ISO_8859_1)
                            if (text.contains("isRecord") || text.contains("java/lang/reflect/RecordComponent")) {
                                problems.add("base class $logical references Class.isRecord/RecordComponent (H4)")
                            }
                        }
                        if (name == sentinel) baseSentinelMajor = major
                    }
                }
            }
        }
        val phantom = v17FirstParty - baseFirstParty
        if (phantom.isNotEmpty()) problems.add("versions/17 has ${phantom.size} first-party class(es) absent from base (phantom overlay): ${phantom.take(3)}")
        for (cls in baseFirstParty - v17FirstParty) {
            val text = String(baseBytesByLogical.getValue(cls), Charsets.ISO_8859_1)
            if (text.contains("dev/fablemc/factions/lib/jvmdg")) {
                problems.add("base-only class $cls references the jvmdg runtime but has NO v61 overlay (a shimmed API would load its shim on modern)")
            }
        }
        if (baseSentinelMajor != 52) problems.add("sentinel base major is $baseSentinelMajor (expected 52)")
        if (v17SentinelMajor != 61) problems.add("sentinel versions/17 major is $v17SentinelMajor (expected 61)")

        if (problems.isNotEmpty()) throw GradleException("verifyDowngrade: not a well-formed tier set:\n" +
            problems.take(30).joinToString("\n") { "  - $it" })
        logger.lifecycle("[verifyDowngrade] OK — base ≤ v52; ${v17FirstParty.size} first-party class(es) forked to v61 under versions/17; sentinel forked 52/61; no reflective-record token.")
    }
}

// verifyKernelPurity — no org/bukkit token in any kernel class in the FINAL jar (the
// bytecode backstop for the kernel resolution firewall).
val verifyKernelPurity = tasks.register("verifyKernelPurity") {
    group = "verification"
    description = "Fails if any dev/fablemc/factions/kernel/ class in the mega jar references org/bukkit."
    dependsOn(megaJar)
    val jarFile = megaJar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    doLast {
        val violations = mutableListOf<String>()
        var kernelClasses = 0
        ZipFile(jarFile.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                if (!mrStrip(name).startsWith("dev/fablemc/factions/kernel/")) continue
                kernelClasses++
                val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                if (text.contains("org/bukkit") || text.contains("org.bukkit")) violations.add(name)
            }
        }
        if (violations.isNotEmpty()) throw GradleException("verifyKernelPurity: kernel class(es) reference org/bukkit:\n" +
            violations.take(20).joinToString("\n") { "  - $it" })
        logger.lifecycle("[verifyKernelPurity] OK — $kernelClasses kernel class(es), none reference org/bukkit.")
    }
}

// verifyProbeIsolation (D-8) — (a) the probe mega jar must not carry FableFactions' own
// jvmdg-runtime prefix; (b) NEITHER mega jar may reference an un-relocated jvmdg STUB
// type in a descriptor (a v52-read server would ClassNotFoundException the instant the
// method links — a live-server HANG, not a build failure).
val verifyProbeIsolation = tasks.register("verifyProbeIsolation") {
    group = "verification"
    description = "D-8: probe carries no FableFactions jvmdg prefix; neither mega jar references an un-relocated jvmdg stub descriptor."
    dependsOn(megaJar, probeMegaJar)
    val coreJar = megaJar.flatMap { it.archiveFile }
    val testerJar = probeMegaJar.flatMap { it.archiveFile }
    inputs.file(coreJar); inputs.file(testerJar)
    doLast {
        val stub = "xyz/wagyourtail/jvmdg/"
        val reloc = "lib/jvmdg/"
        val runtimePrefixes = listOf("dev/fablemc/factions/lib/jvmdg/", "dev/fablemc/factions/probe/lib/jvmdg/")
        val problems = mutableListOf<String>()
        ZipFile(testerJar.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val e = es.nextElement()
                if (!e.name.endsWith(".class")) continue
                val t = zip.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                if (t.contains("dev/fablemc/factions/lib/jvmdg")) problems.add("probe references dev/fablemc/factions/lib/jvmdg (${e.name})")
            }
        }
        for (jar in listOf(coreJar.get().asFile, testerJar.get().asFile)) {
            ZipFile(jar).use { zip ->
                val es = zip.entries()
                while (es.hasMoreElements()) {
                    val e = es.nextElement()
                    val name = e.name
                    if (!name.endsWith(".class")) continue
                    val logical = mrStrip(name)
                    if (runtimePrefixes.any { logical.startsWith(it) }) continue
                    val t = zip.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    var i = t.indexOf(stub)
                    while (i >= 0) {
                        val relocated = i >= reloc.length && t.regionMatches(i - reloc.length, reloc, 0, reloc.length)
                        if (!relocated) {
                            var end = i
                            while (end < t.length && (t[end].isLetterOrDigit() || t[end] == '/' || t[end] == '_' || t[end] == '$')) end++
                            val fqn = t.substring(i, end)
                            if (fqn.contains("/stub/")) problems.add("${jar.name}: $name → $fqn (un-relocated jvmdg stub in a descriptor)")
                        }
                        i = t.indexOf(stub, i + 1)
                    }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException("verifyProbeIsolation (D-8):\n" +
            problems.distinct().take(20).joinToString("\n") { "  - $it" })
        logger.lifecycle("[verifyProbeIsolation] OK — probe carries no FableFactions jvmdg prefix; no un-relocated jvmdg stub descriptors in either mega jar.")
    }
}

// verifyJdk8Api (H1) — closed-world scan of BOTH mega jars' base (v52) tree against a
// real JDK 8, compiling scripts/tools/Jdk8ApiGate.java in-process and running it on a
// foojay-provisioned JDK 8 via ExecOperations (Gradle 9 removed Project.javaexec).
val jdk8GateAsm: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
dependencies { jdk8GateAsm(libs.asm) }

val serverProvidedIgnores = listOf(
    // Server-provided packages: present on the running server, never bundled or validated.
    "org/bukkit", "net/minecraft", "com/destroystokyo", "io/papermc", "org/spigotmc", "io/netty", "com/mojang",
    "org/jetbrains", "org/intellij", "org/jspecify", "org/checkerframework", "xyz/wagyourtail", "com/google",
    "net/md_5", "net/milkbowl", "com/sk89q", "me/clip", "org/dynmap", "com/earth2me", "github/scarsz",
    "com/griefcraft", "org/apache", "org/yaml",
    // Guarded-optional third-party integrations HARD-referenced by BUNDLED classes of the
    // shaded storage libs, but never LINKED by the embedded-DB/JDBC path: HikariPool casts
    // to the Dropwizard/Micrometer metric registries only when one is configured
    // (string-guarded); H2's ValueGeometry touches JTS only when the optional JTS lib is
    // present; the MySQL OCI auth plugin (AuthenticationOciClient) reads com.oracle.bmc only
    // when authenticationPlugins names it. All external SDKs never on our path, so never a
    // runtime miss — the Mental com/viaversion precedent (a guarded optional integration
    // ignored rather than bundled). The allowlist stays EMPTY.
    "com/codahale", "io/micrometer", "org/locationtech", "com/oracle",
)

// A relocated FIRST-PARTY class we were FORCED to strip from the shaded jar (route (1) in
// the shadowJar block) yet a bundled CORE class still names in a provably-dead code path:
// H2's org.h2.engine.Database references org.h2.tools.Server ONLY from its AUTO_SERVER
// branch, and the embedded file-mode URL never sets AUTO_SERVER, so the reference never
// links. Ignored ONLY for the core jar (the probe bundles no storage libs) and scoped to
// the exact stripped class, so any OTHER missing relocated class still fails the gate (H1).
val excludedFeatureRefs = listOf("dev/fablemc/factions/lib/h2/tools/Server")

val javaToolchains = extensions.getByType<JavaToolchainService>()

val verifyJdk8Api = tasks.register("verifyJdk8Api") {
    group = "verification"
    description = "Fails if any reference in either mega jar's base (v52) tree resolves neither in a real JDK-8 rt.jar, in-jar, nor a server-provided package (H1)."
    dependsOn(megaJar, probeMegaJar)
    val coreJar = megaJar.flatMap { it.archiveFile }
    val testerJar = probeMegaJar.flatMap { it.archiveFile }
    val toolSrc = rootProject.layout.projectDirectory.file("scripts/tools/Jdk8ApiGate.java")
    val allowFile = rootProject.layout.projectDirectory.file("scripts/jdk8-api-gate.allow")
    val jdk8Home = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }
        .map { it.metadata.installationPath.asFile }
    val classesDir = layout.buildDirectory.dir("jdk8-gate/classes")
    val asm = jdk8GateAsm
    val execOps = serviceOf<ExecOperations>()
    inputs.file(coreJar); inputs.file(testerJar); inputs.file(toolSrc); inputs.file(allowFile)
    doLast {
        val jdk8 = jdk8Home.get()
        val out = classesDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("no system Java compiler — run Gradle on a JDK, not a JRE")
        val asmCp = asm.files.joinToString(File.pathSeparator) { it.absolutePath }
        val rc = compiler.run(null, null, System.err, "-cp", asmCp, "-d", out.absolutePath, toolSrc.asFile.absolutePath)
        if (rc != 0) throw GradleException("failed to compile ${toolSrc.asFile.name} (see errors above)")
        val toolClasspath = files(asm.files + out)

        // The probe provides no kernel/api/platform/core code (the FableFactions jar does at runtime), so those
        // packages are server-provided FROM ITS PERSPECTIVE — ignore dev/fablemc/factions/ for it only.
        val gates = listOf(
            Triple(coreJar.get().asFile, serverProvidedIgnores + excludedFeatureRefs, "FableFactions"),
            Triple(testerJar.get().asFile, serverProvidedIgnores + "dev/fablemc/factions/", "FableFactionsProbe"),
        )
        for ((jar, ignores, label) in gates) {
            val gateArgs = mutableListOf(jar.absolutePath, jdk8.absolutePath, "--allow", allowFile.asFile.absolutePath)
            ignores.forEach { gateArgs.add("--ignore"); gateArgs.add(it) }
            val result = execOps.javaexec {
                classpath = toolClasspath
                mainClass.set("Jdk8ApiGate")
                args = gateArgs
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) throw GradleException("[verifyJdk8Api] $label has references absent from Java 8 (see the [jdk8-gate] output above).")
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────
 *  AM-13 floor gates — in-process ASM ClassVisitor scans over the COMPILED
 *  :core/:platform/:api classes (v61), resolved against scripts/floor-symbols.txt.
 * ──────────────────────────────────────────────────────────────────────── */
val floorSymbolsFile = rootProject.layout.projectDirectory.file("scripts/floor-symbols.txt").asFile
val floorGateClassDirs = files(
    sourceSets["main"].output.classesDirs,
    project(":platform").sourceSets["main"].output.classesDirs,
    project(":api").sourceSets["main"].output.classesDirs,
)
val floorGateDeps = listOf(
    tasks.named("classes"),
    project(":platform").tasks.named("classes"),
    project(":api").tasks.named("classes"),
)

fun parseFloorSymbols(): Pair<Set<String>, Set<String>> {
    val types = mutableSetOf<String>()
    val consts = mutableSetOf<String>()
    var section = ""
    floorSymbolsFile.readLines().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) { section = line; return@forEach }
        when (section) {
            "[POST_FLOOR_TYPES]" -> types.add(line)
            "[POST_FLOOR_ENUM_CONSTANTS]" -> consts.add(line)
        }
    }
    return types to consts
}

class ClassScan {
    var name: String = "?"
    var superName: String? = null
    val interfaces = mutableListOf<String>()
    var probeGated = false
    val descriptorTypes = mutableSetOf<String>()
    val getstatics = mutableListOf<Pair<String, String>>()
}

fun addType(t: Type, into: MutableSet<String>) {
    var x = t
    if (x.sort == Type.ARRAY) x = x.elementType
    if (x.sort == Type.OBJECT) into.add(x.internalName)
}
fun collectTypes(desc: String, into: MutableSet<String>) {
    if (desc.isEmpty()) return
    if (desc[0] == '(') {
        val mt = Type.getMethodType(desc)
        for (a in mt.argumentTypes) addType(a, into)
        addType(mt.returnType, into)
    } else addType(Type.getType(desc), into)
}
fun scanClassBytes(bytes: ByteArray): ClassScan {
    val scan = ClassScan()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visit(v: Int, access: Int, name: String, sig: String?, sup: String?, itf: Array<out String>?) {
            scan.name = name; scan.superName = sup
            if (itf != null) scan.interfaces.addAll(itf)
        }
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor == "Ldev/fablemc/factions/platform/life/ProbeGated;") scan.probeGated = true
            return null
        }
        override fun visitField(access: Int, name: String, descriptor: String, sig: String?, value: Any?): FieldVisitor? {
            collectTypes(descriptor, scan.descriptorTypes); return null
        }
        override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, exc: Array<out String>?): MethodVisitor? {
            collectTypes(descriptor, scan.descriptorTypes)
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitFieldInsn(opcode: Int, owner: String, fname: String, fdesc: String) {
                    if (opcode == Opcodes.GETSTATIC) scan.getstatics.add(owner to fname)
                }
            }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return scan
}
fun collectScans(): Map<String, ClassScan> {
    val map = LinkedHashMap<String, ClassScan>()
    floorGateClassDirs.files.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
            val scan = scanClassBytes(f.readBytes())
            map[scan.name] = scan
        }
    }
    return map
}
fun implementsListener(name: String?, map: Map<String, ClassScan>, seen: MutableSet<String>): Boolean {
    if (name == null) return false
    if (name == "org/bukkit/event/Listener") return true
    if (!seen.add(name)) return false
    val s = map[name] ?: return false
    if (s.interfaces.any { implementsListener(it, map, seen) }) return true
    return implementsListener(s.superName, map, seen)
}

// verifyDescriptorFloor — a baseline (non-@ProbeGated) Listener may not mention a
// post-floor Bukkit type in ANY method/field descriptor (GAP 1 / listener-swallow).
val verifyDescriptorFloor = tasks.register("verifyDescriptorFloor") {
    group = "verification"
    description = "AM-13: no baseline (non-@ProbeGated) Listener mentions a post-floor type in a method/field descriptor."
    floorGateDeps.forEach { dependsOn(it) }
    inputs.files(floorGateClassDirs)
    inputs.file(floorSymbolsFile)
    doLast {
        val (postFloorTypes, _) = parseFloorSymbols()
        val map = collectScans()
        val problems = mutableListOf<String>()
        for (s in map.values) {
            if (s.probeGated) continue
            if (!implementsListener(s.name, map, mutableSetOf())) continue
            val hits = s.descriptorTypes.filter { it in postFloorTypes }
            if (hits.isNotEmpty()) problems.add("${s.name} (baseline Listener) → descriptor mentions ${hits.sorted()}")
        }
        if (problems.isNotEmpty()) throw GradleException("verifyDescriptorFloor (AM-13): baseline Listener(s) mention post-floor types:\n" +
            problems.take(30).joinToString("\n") { "  - $it" } +
            "\nMove the post-floor-typed handler into its own @ProbeGated listener registered behind a capability.")
        logger.lifecycle("[verifyDescriptorFloor] OK — ${map.size} class(es) scanned; no baseline Listener mentions a post-floor descriptor type.")
    }
}

// verifyNoStickyGetstatic — no GETSTATIC of a post-floor enum constant (GAP 2 / sticky
// NoSuchFieldError); such constants must flow through the Constants resolver / Enum.valueOf.
val verifyNoStickyGetstatic = tasks.register("verifyNoStickyGetstatic") {
    group = "verification"
    description = "AM-13: no GETSTATIC of a post-floor Bukkit enum constant in :core/:platform/:api classes."
    floorGateDeps.forEach { dependsOn(it) }
    inputs.files(floorGateClassDirs)
    inputs.file(floorSymbolsFile)
    doLast {
        val (_, postFloorConsts) = parseFloorSymbols()
        val map = collectScans()
        val problems = mutableListOf<String>()
        for (s in map.values) {
            for ((owner, fname) in s.getstatics) {
                if ("$owner.$fname" in postFloorConsts) problems.add("${s.name} → GETSTATIC $owner.$fname")
            }
        }
        if (problems.isNotEmpty()) throw GradleException("verifyNoStickyGetstatic (AM-13): sticky-getstatic hazard(s):\n" +
            problems.take(30).joinToString("\n") { "  - $it" } +
            "\nResolve post-floor enum constants once at boot via the Constants resolver / Enum.valueOf.")
        logger.lifecycle("[verifyNoStickyGetstatic] OK — ${map.size} class(es) scanned; no GETSTATIC of a post-floor enum constant.")
    }
}

tasks.named("check") {
    dependsOn(verifyRelocation, verifyDowngrade, verifyKernelPurity, verifyProbeIsolation,
        verifyJdk8Api, verifyDescriptorFloor, verifyNoStickyGetstatic)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Live integration matrix (run-paper) — one RunServer per support-matrix entry,
 *  per-entry runtime JDK + nonce/tier/suites system properties, the shipped mega
 *  jars installed. Registered but NOT wired into `check`; the default runServer
 *  task is disabled.
 * ──────────────────────────────────────────────────────────────────────── */
tasks.named<RunServer>("runServer") {
    enabled = false
    description = "Disabled — use integrationTest or integrationTestMatrix."
}

val integrationRunTasks = mutableListOf<TaskProvider<RunServer>>()
var previousRun: TaskProvider<RunServer>? = null

supportEntries.forEach { entry ->
    val suffix = (if (entry.platform == "folia") "Folia_" else "_") + entry.version.replace(".", "_")
    val nonce = UUID.randomUUID().toString()
    val runDir = rootProject.layout.projectDirectory.dir("run/${entry.platform}/${entry.version}").asFile
    val runTask = tasks.register<RunServer>("runIntegrationTest$suffix") {
        group = "fablefactions integration"
        description = "Boots ${entry.platform} ${entry.version} with FableFactions + probe and runs the suite."
        dependsOn(megaJar, probeMegaJar)
        runDirectory.set(runDir)
        // Folia downloads from its own project; set the source BEFORE the version.
        if (entry.platform == "folia") downloadsApiService.set(DownloadsAPIService.folia(project))
        minecraftVersion(entry.version)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(entry.jdk)) })
        // No Java-version-guard bypass flag: every legacy build runs its newest clean
        // flagless JVM, and the v52 base tree loads there natively. tier is the declared
        // Multi-Release bytecodeTier the loader × JVM reads; the probe asserts it live.
        jvmArgs(
            "-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G",
            "-Dfablefactions.probe.nonce=$nonce", "-Dfablefactions.probe.suites=${entry.suites}",
            "-Dfablefactions.probe.tier=${entry.bytecodeTier}",
        )
        pluginJars.from(megaJar.flatMap { it.archiveFile })
        pluginJars.from(probeMegaJar.flatMap { it.archiveFile })
    }
    // Chain sequentially so the matrix never double-binds the port.
    previousRun?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousRun = runTask
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
