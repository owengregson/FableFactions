import groovy.json.JsonSlurper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
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
import xyz.wagyourtail.jvmdg.gradle.task.DowngradeJar
import xyz.wagyourtail.jvmdg.gradle.task.ShadeJar

// ASM on the SCRIPT classpath so the AM-13 floor gates can run as in-process
// ClassVisitor tasks over the compiled :core/:platform/:api classes.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.10.1") }
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
    // Soft-dep, compileOnly ONLY (never shaded): the typed FableExpansion supertype.
    // The class is FQN-loaded behind the PlaceholderAPI presence gate (proposal-C §10.3).
    compileOnly(libs.placeholderapi)

    // Shaded + relocated (dev.fablemc.factions.lib.*). Adventure carries the self-
    // contained legacy-string serializer for below-1.16.5 servers; the annotations it
    // drags in are compile-time only, excluded so they never bundle.
    implementation(libs.adventure.api) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.adventure.minimessage) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.adventure.serializer.legacy) { exclude(group = "org.jetbrains", module = "annotations") }
    implementation(libs.commons.dbcp2)   // connection pool (Java-8-native latest; pulls commons-pool2 + commons-logging)
    implementation(libs.commons.pool2)
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
    relocate("org.apache.commons.dbcp2", "dev.fablemc.factions.lib.dbcp2")
    relocate("org.apache.commons.pool2", "dev.fablemc.factions.lib.pool2")
    // DBCP2/pool2 log via commons-logging — shade it too (no slf4j binding needed).
    relocate("org.apache.commons.logging", "dev.fablemc.factions.lib.commonslogging")
    relocate("org.h2", "dev.fablemc.factions.lib.h2")
    relocate("com.mysql", "dev.fablemc.factions.lib.mysql")

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
    exclude("**/mysql/cj/jdbc/integration/**")             // com.mchange c3p0 ConnectionTester
    // commons-logging's servlet-container cleanup listener implements javax.servlet.* — javax/ is
    // an un-ignorable HARD verifyJdk8Api tier (route 1, like the H2 web console), and Paper is not
    // a servlet container, so strip it. (Its optional slf4j/cglib bridges stay bundled but dead —
    // route 2, ignored below — since org/slf4j and net/sf/cglib ARE ignorable external optionals.)
    // NB shadow filters on the SOURCE path, so match the un-relocated org/apache/commons/logging.
    exclude("**/commons/logging/impl/ServletContextCleaner*")
    // DBCP2's optional MANAGED (XA/JTA) datasource pulls javax.transaction, which in turn references
    // javax.enterprise/interceptor (CDI) — un-ignorable javax/ HARD packages. We use the plain
    // BasicDataSource, never the managed pool, so strip that package and the bundled JTA API.
    exclude("**/dbcp2/managed/**")
    exclude("javax/transaction/**")
    // AuthenticationOciClient stays BUNDLED (route 2): it names only com.oracle.bmc
    // externally, which verifyJdk8Api ignores as a guarded-optional SDK (never linked on the
    // native/basic-auth path). Excluding it would dangle NativeAuthenticationProvider's ref.
}

/* ────────────────────────────────────────────────────────────────────────
 *  The mega-jar pipeline (three tiers):
 *      shadowJar ─→ DowngradeJar(8)  ─→ ShadeJar(8)  ─┐
 *              └──→ DowngradeJar(13) ─→ ShadeJar(13) ─┴→ mergeTiers → canonical jar
 *  base = v52 (Java 8, fully shimmed) · META-INF/versions/13 = v57 (the 1.13–1.16
 *  band's JDK 13/14/16 runtimes load J9–13 APIs natively, only 14→17 shimmed) ·
 *  META-INF/versions/17 = the untouched original v61 (Java 17+ runs pristine bytecode).
 * ──────────────────────────────────────────────────────────────────────── */

// jvmdg's warning capture listens on Gradle's GLOBAL console stream (a task
// LoggingManager listener is not task-scoped). Two fences make it parallel-safe:
// this shared service (maxParallelUsages=1) serializes every jvmdg task against the
// others, and the mustRunAfter list below keeps noisy test JVMs out of the window.
abstract class JvmdgConsoleLock : BuildService<BuildServiceParameters.None>

val jvmdgConsoleLock = gradle.sharedServices.registerIfAbsent("jvmdgConsoleLock", JvmdgConsoleLock::class) {
    maxParallelUsages.set(1)
}

// Tasks whose JVMs write to the console while running (JDK-24+ JEP-498 notes etc.);
// referenced by lazy PATH so this list stays configuration-cache-safe.
val consoleNoisyTasks = listOf(
    ":kernel:test", ":api:test", ":platform:test", ":core:test",
    ":compat-folia:test", ":compat-modern:test", ":probe:test",
    ":kernel:compileTestJava", ":api:compileTestJava", ":platform:compileTestJava",
    ":core:compileTestJava",
)

fun failOnJvmdgWarnings(jar: Jar) {
    val captured = StringBuilder()
    val sink = StandardOutputListener { text -> captured.append(text) }
    val logSink = jar.project.layout.buildDirectory.file("jvmdg-stage/${jar.name}-output.log")
    jar.usesService(jvmdgConsoleLock)
    jar.mustRunAfter(consoleNoisyTasks)
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

val jvmdg = extensions.getByType<JVMDowngraderExtension>()

// The classpath jvmdg resolves supertypes against. shadowJar folded the compat modules'
// classes in, whose supertypes are the Folia/modern APIs (absent from core's 1.13 floor).
// Union all three compile classpaths so it resolves with ZERO warnings.
val jvmdgResolveClasspath = sourceSets["main"].compileClasspath +
    project(":compat-folia").sourceSets["main"].compileClasspath +
    project(":compat-modern").sourceSets["main"].compileClasspath

// DowngradeJar (input defaults to shadowJar; retarget explicitly). base = v52 (Java 8) +
// versions/17 = the untouched original v61. multiReleaseOriginal WITHOUT
// multiReleaseVersions: 1.3.6 treats the two as mutually exclusive and the latter would
// DROP the original v61, silently downgrading the modern path.
val downgradeMegaJar = jvmdg.defaultTask
downgradeMegaJar.configure {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    multiReleaseOriginal.set(true)
    classpath = jvmdgResolveClasspath
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("downgraded")
    failOnJvmdgWarnings(this)
}

// ShadeJar — relocate jvmdg's runtime helpers under our lib prefix. STAGED (classifier
// tier8): the canonical jar is assembled by mergeTiers below.
val megaJar = jvmdg.defaultShadeTask
megaJar.configure {
    inputFile.set(downgradeMegaJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    shadePath.set { "dev/fablemc/factions/lib/jvmdg/" }
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("tier8")
    failOnJvmdgWarnings(this)
}

// The v57 leg: same shadowJar input downgraded only to Java 13 for the JDK-13/14/16
// support-matrix band (1.13.2–1.16.5). No multiReleaseOriginal — mergeTiers takes the
// v61 originals from the tier-8 leg; this leg contributes ONLY its v57 base tree.
val downgradeMegaJar13 = tasks.register<DowngradeJar>("downgradeJar13") {
    group = "jvmdg"
    description = "Downgrades the shaded jar to Java 13 (v57) for the META-INF/versions/13 tier."
    convention(jvmdg)
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_13)
    multiReleaseOriginal.set(false)
    classpath = jvmdgResolveClasspath
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("downgraded13")
    failOnJvmdgWarnings(this)
}

val megaJar13 = tasks.register<ShadeJar>("shadeJar13") {
    group = "jvmdg"
    description = "Relocates the jvmdg runtime refs of the v57 leg under the SAME lib prefix as tier 8."
    convention(jvmdg)
    inputFile.set(downgradeMegaJar13.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_13)
    shadePath.set { "dev/fablemc/factions/lib/jvmdg/" }
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("FableFactions")
    archiveClassifier.set("tier13")
    failOnJvmdgWarnings(this)
}

/**
 * Assembles the canonical Multi-Release mega jar: the tier-8 jar (v52 base +
 * versions/17 v61 originals) verbatim, plus META-INF/versions/13/ =
 *   (a) the v57 copy of every first-party class in the versions/17 fork set — fork sets
 *       are consistent across targets: any class jvmdg modifies at target 13 it also
 *       modifies at target 8, and unmodified classes keep identical descriptors in every
 *       tier, so tier-mixing is exactly as safe as the existing base↔versions/17 mix;
 *   (b) any relocated jvmdg runtime class the v57 leg bundles that the tier-8 bundle
 *       lacks (orphan versioned entries — only ever resolved by 13+ class loaders).
 */
@CacheableTask
abstract class MergeTiersTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val tier8Jar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val tier13Jar: RegularFileProperty
    @get:OutputFile abstract val outputJar: RegularFileProperty

    @TaskAction
    fun merge() {
        val firstParty = "dev/fablemc/factions/"
        val lib = "dev/fablemc/factions/lib/"
        val jvmdgRuntime = "dev/fablemc/factions/lib/jvmdg/"
        val tierDir = "META-INF/versions/13/"
        val forkSet = sortedSetOf<String>()
        val tier8Names = hashSetOf<String>()
        ZipFile(tier8Jar.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val name = es.nextElement().name
                tier8Names.add(name)
                if (name.startsWith("META-INF/versions/17/") && name.endsWith(".class")) {
                    val logical = name.removePrefix("META-INF/versions/17/")
                    if (logical.startsWith(firstParty) && !logical.startsWith(lib)) forkSet.add(logical)
                }
            }
        }
        val inject = sortedMapOf<String, ByteArray>()
        ZipFile(tier13Jar.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val wanted = name in forkSet ||
                    (name.startsWith(jvmdgRuntime) && name !in tier8Names)
                if (wanted) inject[tierDir + name] = zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        val missing = forkSet.filter { (tierDir + it) !in inject }
        if (missing.isNotEmpty()) {
            throw GradleException("mergeTiers: ${missing.size} fork-set class(es) absent from the v57 leg " +
                "(downgradeJar13 output diverged from the tier-8 fork set): ${missing.take(5)}")
        }
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        // Fixed timestamp on injected entries keeps the canonical jar deterministic.
        val injectedTime = 315532800000L
        // A .jar is a zip, and a zip may legally carry two entries with the same name; shadow/jvmdg
        // can emit an identical relocated runtime shim (e.g. the J_L_Record stub) more than once, and
        // ZipOutputStream.putNextEntry THROWS on the second — a non-deterministic build failure now
        // that this jar gates `check`. Dedup on write (first occurrence wins; colliding shims are
        // byte-identical), and log any skip so the upstream duplicate stays visible rather than
        // silently masked.
        val written = hashSetOf<String>()
        val skipped = sortedSetOf<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            ZipFile(tier8Jar.get().asFile).use { zip ->
                val es = zip.entries()
                while (es.hasMoreElements()) {
                    val entry = es.nextElement()
                    if (!written.add(entry.name)) { skipped.add(entry.name); continue }
                    val copy = ZipEntry(entry.name)
                    copy.time = entry.time
                    zos.putNextEntry(copy)
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            for ((name, bytes) in inject) {
                if (!written.add(name)) { skipped.add(name); continue }
                val e = ZipEntry(name)
                e.time = injectedTime
                zos.putNextEntry(e)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        val firstPartyCount = inject.keys.count { !it.startsWith(tierDir + lib) }
        val shimCount = inject.size - firstPartyCount
        logger.lifecycle("[mergeTiers] canonical ${out.name}: +versions/13 with $firstPartyCount first-party " +
            "v57 class(es) + $shimCount jvmdg runtime class(es) absent from the tier-8 bundle.")
        if (skipped.isNotEmpty()) {
            logger.lifecycle("[mergeTiers] de-duplicated ${skipped.size} repeated zip entry name(s) " +
                "(first occurrence kept): ${skipped.take(5)}")
        }
    }
}

val mergeTiers = tasks.register<MergeTiersTask>("mergeTiers") {
    group = "jvmdg"
    description = "Assembles the canonical 3-tier mega jar (v52 base / v57 versions-13 / v61 versions-17)."
    tier8Jar.set(megaJar.flatMap { it.archiveFile })
    tier13Jar.set(megaJar13.flatMap { it.archiveFile })
    outputJar.set(layout.buildDirectory.file("libs/FableFactions-${project.version}.jar"))
}

// Every downstream consumer (gates, run tasks, `build`) reads the CANONICAL jar.
val canonicalJar = mergeTiers.flatMap { it.outputJar }

tasks.build { dependsOn(mergeTiers) }

// The probe's FINAL mega jar — looked up by task NAME (jvmdg names its default shade
// task identically in every project, so megaJar.name is the probe's too), NOT by
// extensions.getByType<JVMDowngraderExtension>() which would resolve the wrong
// classloader cross-project. AbstractArchiveTask is a Gradle core type (shared loader).
val probeMegaJar = project(":probe").tasks.named<AbstractArchiveTask>(megaJar.name)

/* ────────────────────────────────────────────────────────────────────────
 *  Verification gates — all wired into `check`, all configuration-cache-safe
 *  task classes with declared outputs (up-to-date + build-cache participants).
 *  Each reads the FINAL mega jar(s), never the staged intermediates. Class bytes
 *  are read as ISO-8859-1 (a lossless byte↔char map) so a substring match equals
 *  an exact constant-pool byte match.
 * ──────────────────────────────────────────────────────────────────────── */

// Shared jar-scan helpers. An `object` (no script-state capture) keeps every gate
// task class serializable for the configuration cache.
object MegaJarScan {
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
    /** Extracts every token starting with [prefix] from a constant-pool text. */
    fun tokensWithPrefix(text: String, prefix: String): Set<String> {
        val out = mutableSetOf<String>()
        var i = text.indexOf(prefix)
        while (i >= 0) {
            var end = i
            while (end < text.length &&
                (text[end].isLetterOrDigit() || text[end] == '/' || text[end] == '_' || text[end] == '$')) end++
            out.add(text.substring(i, end))
            i = text.indexOf(prefix, end)
        }
        return out
    }
}

// verifyRelocation — no un-relocated storage/adventure token survives the shade.
@CacheableTask
abstract class VerifyRelocationTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jarFile: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val libPrefix = "dev/fablemc/factions/lib/"
        val tokens = listOf("net/kyori", "org/apache/commons/dbcp2", "org/apache/commons/pool2",
            "org/apache/commons/logging", "org/h2", "com/mysql")
        val entryViolations = mutableListOf<String>()
        val refViolations = mutableListOf<String>()
        ZipFile(jarFile.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val logical = MegaJarScan.mrStrip(name)
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
                val logical = MegaJarScan.mrStrip(es2.nextElement().name)
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
        report.get().asFile.writeText("clean\n")
        logger.lifecycle("[relocation] clean — no un-relocated storage/adventure token in ${jarFile.get().asFile.name}.")
    }
}

// verifyDowngrade — the Multi-Release tier shape: base ≤ v52; versions/13 first-party
// == v57 and exactly the versions/17 fork set; versions/17 first-party == v61 and a
// subset of base; sentinel forked 52/57/61; H4 no reflective-record token; every
// relocated jvmdg runtime ref from a versions/13 class resolves in-jar.
@CacheableTask
abstract class VerifyDowngradeTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jarFile: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val file = jarFile.get().asFile
        val problems = mutableListOf<String>()
        val sentinel = "dev/fablemc/factions/core/boot/FableFactionsPlugin.class"
        val jvmdgRuntime = "dev/fablemc/factions/lib/jvmdg/"
        var baseSentinelMajor = -1
        var v13SentinelMajor = -1
        var v17SentinelMajor = -1
        val baseFirstParty = sortedSetOf<String>()
        val v13FirstParty = sortedSetOf<String>()
        val v17FirstParty = sortedSetOf<String>()
        val baseBytesByLogical = mutableMapOf<String, ByteArray>()
        val allEntryNames = hashSetOf<String>()
        val v13JvmdgRefs = sortedSetOf<String>()
        var v21DepClasses = 0

        JarFile(file).use { jar ->
            val mr = jar.manifest?.mainAttributes?.getValue("Multi-Release")
            if (!"true".equals(mr, ignoreCase = true)) problems.add("manifest Multi-Release is '$mr' (expected true)")
        }
        ZipFile(file).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                allEntryNames.add(name)
                if (!name.endsWith(".class")) continue
                val logical = MegaJarScan.mrStrip(name)
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val major = MegaJarScan.classMajor(bytes)
                when {
                    name.startsWith("META-INF/versions/17/") -> {
                        if (MegaJarScan.isFirstParty(logical)) {
                            v17FirstParty.add(logical)
                            if (major != 61) problems.add("versions/17 first-party $logical is v$major (expected 61)")
                        }
                        if (name == "META-INF/versions/17/$sentinel") v17SentinelMajor = major
                    }
                    name.startsWith("META-INF/versions/13/") -> {
                        if (MegaJarScan.isFirstParty(logical)) {
                            v13FirstParty.add(logical)
                            if (major != 57) problems.add("versions/13 first-party $logical is v$major (expected 57)")
                        } else if (logical.startsWith(jvmdgRuntime)) {
                            if (major > 57) problems.add("versions/13 jvmdg runtime $logical is v$major (>57)")
                        } else {
                            problems.add("versions/13 carries unexpected non-first-party entry $name")
                        }
                        val text = String(bytes, Charsets.ISO_8859_1)
                        v13JvmdgRefs.addAll(MegaJarScan.tokensWithPrefix(text, jvmdgRuntime))
                        if (name == "META-INF/versions/13/$sentinel") v13SentinelMajor = major
                    }
                    name.startsWith("META-INF/versions/21/") -> {
                        // Modern-dependency tier (build-high). jvmdg keeps a shaded dep's ORIGINAL
                        // post-v61 bytecode here when the dep was built on a newer JDK than the
                        // first-party v61 floor (Adventure 5.x is Java-21 / v65): only 21+ JVMs load
                        // it, everyone else runs the v52 base copy jvmdg produced. Valid ONLY if it
                        // carries relocated third-party (lib.*) classes and NO first-party (which must
                        // stay in the 13/17 tiers), with genuinely post-v61 bytecode — assert that
                        // shape rather than blanket-accepting versions/21, so a real leak still fails.
                        if (MegaJarScan.isFirstParty(logical)) {
                            problems.add("versions/21 carries first-party class $logical (first-party must stay in versions/13-17)")
                        } else if (major <= 61) {
                            problems.add("versions/21 dep class $logical is v$major (not a post-v61 modern-dependency tier)")
                        } else {
                            v21DepClasses++
                        }
                    }
                    name.startsWith("META-INF/versions/") ->
                        problems.add("unexpected versioned tier entry $name (only versions/13, versions/17 and the versions/21 modern-dep tier are expected)")
                    else -> {
                        if (major > 52) problems.add("base entry $logical is v$major (>52)")
                        if (MegaJarScan.isFirstParty(logical)) {
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
        val phantom17 = v17FirstParty - baseFirstParty
        if (phantom17.isNotEmpty()) problems.add("versions/17 has ${phantom17.size} first-party class(es) absent from base (phantom overlay): ${phantom17.take(3)}")
        if (v13FirstParty != v17FirstParty) {
            val extra = v13FirstParty - v17FirstParty
            val absent = v17FirstParty - v13FirstParty
            if (extra.isNotEmpty()) problems.add("versions/13 first-party set has ${extra.size} class(es) outside the versions/17 fork set: ${extra.take(3)}")
            if (absent.isNotEmpty()) problems.add("versions/13 first-party set is missing ${absent.size} fork-set class(es): ${absent.take(3)}")
        }
        for (cls in baseFirstParty - v17FirstParty) {
            val text = String(baseBytesByLogical.getValue(cls), Charsets.ISO_8859_1)
            if (text.contains("dev/fablemc/factions/lib/jvmdg")) {
                problems.add("base-only class $cls references the jvmdg runtime but has NO v61 overlay (a shimmed API would load its shim on modern)")
            }
        }
        // Every jvmdg runtime class a versions/13 class references must resolve in-jar for
        // a 13+ loader: base bundle first, versions/13 orphan second.
        for (ref in v13JvmdgRefs) {
            val cls = "$ref.class"
            if (cls !in allEntryNames && "META-INF/versions/13/$cls" !in allEntryNames) {
                problems.add("versions/13 references jvmdg runtime class $ref which is in NEITHER the tier-8 bundle nor versions/13")
            }
        }
        if (baseSentinelMajor != 52) problems.add("sentinel base major is $baseSentinelMajor (expected 52)")
        if (v13SentinelMajor != 57) problems.add("sentinel versions/13 major is $v13SentinelMajor (expected 57)")
        if (v17SentinelMajor != 61) problems.add("sentinel versions/17 major is $v17SentinelMajor (expected 61)")

        if (problems.isNotEmpty()) throw GradleException("verifyDowngrade: not a well-formed tier set:\n" +
            problems.take(30).joinToString("\n") { "  - $it" })
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyDowngrade] OK — base ≤ v52; ${v13FirstParty.size} first-party class(es) forked to v57 under versions/13; " +
            "${v17FirstParty.size} to v61 under versions/17; sentinel forked 52/57/61; no reflective-record token; versions/13 jvmdg refs resolve" +
            (if (v21DepClasses > 0) "; $v21DepClasses post-v61 modern-dep class(es) under versions/21 (build-high dep originals)." else "."))
    }
}

// verifyKernelPurity — no org/bukkit token in any kernel class in the FINAL jar (the
// bytecode backstop for the kernel resolution firewall).
@CacheableTask
abstract class VerifyKernelPurityTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jarFile: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val violations = mutableListOf<String>()
        var kernelClasses = 0
        ZipFile(jarFile.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                if (!MegaJarScan.mrStrip(name).startsWith("dev/fablemc/factions/kernel/")) continue
                kernelClasses++
                val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                if (text.contains("org/bukkit") || text.contains("org.bukkit")) violations.add(name)
            }
        }
        if (violations.isNotEmpty()) throw GradleException("verifyKernelPurity: kernel class(es) reference org/bukkit:\n" +
            violations.take(20).joinToString("\n") { "  - $it" })
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyKernelPurity] OK — $kernelClasses kernel class(es), none reference org/bukkit.")
    }
}

// verifyProbeIsolation (D-8) — (a) the probe mega jar must not carry FableFactions' own
// jvmdg-runtime prefix; (b) NEITHER mega jar may reference an un-relocated jvmdg STUB
// type in a descriptor (a v52-read server would ClassNotFoundException the instant the
// method links — a live-server HANG, not a build failure).
@CacheableTask
abstract class VerifyProbeIsolationTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val coreJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val testerJar: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
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
                    val logical = MegaJarScan.mrStrip(name)
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
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyProbeIsolation] OK — probe carries no FableFactions jvmdg prefix; no un-relocated jvmdg stub descriptors in either mega jar.")
    }
}

// verifyJdk8Api (H1) — closed-world scan of BOTH mega jars' base (v52) tree against a
// real JDK 8, compiling scripts/tools/Jdk8ApiGate.java in-process and running it on a
// foojay-provisioned JDK 8 via injected ExecOperations.
@CacheableTask
abstract class VerifyJdk8ApiTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val coreJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val testerJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val toolSrc: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val allowFile: RegularFileProperty
    @get:Classpath abstract val asmClasspath: ConfigurableFileCollection
    @get:Input abstract val jdk8Path: Property<String>
    @get:Input abstract val coreIgnores: ListProperty<String>
    @get:Input abstract val probeIgnores: ListProperty<String>
    @get:Internal abstract val classesDir: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val jdk8 = File(jdk8Path.get())
        val out = classesDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("no system Java compiler — run Gradle on a JDK, not a JRE")
        val asmCp = asmClasspath.files.joinToString(File.pathSeparator) { it.absolutePath }
        val rc = compiler.run(null, null, System.err, "-cp", asmCp, "-d", out.absolutePath, toolSrc.get().asFile.absolutePath)
        if (rc != 0) throw GradleException("failed to compile ${toolSrc.get().asFile.name} (see errors above)")
        val toolClasspath = asmClasspath.files + out

        val gates = listOf(
            Triple(coreJar.get().asFile, coreIgnores.get(), "FableFactions"),
            Triple(testerJar.get().asFile, probeIgnores.get(), "FableFactionsProbe"),
        )
        for ((jar, ignores, label) in gates) {
            val gateArgs = mutableListOf(jar.absolutePath, jdk8.absolutePath, "--allow", allowFile.get().asFile.absolutePath)
            ignores.forEach { gateArgs.add("--ignore"); gateArgs.add(it) }
            val result = execOps.javaexec {
                classpath = objects.fileCollection().from(toolClasspath)
                mainClass.set("Jdk8ApiGate")
                args = gateArgs
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) throw GradleException("[verifyJdk8Api] $label has references absent from Java 8 (see the [jdk8-gate] output above).")
        }
        report.get().asFile.writeText("ok\n")
    }

    @get:Inject abstract val objects: org.gradle.api.model.ObjectFactory
}

/* ────────────────────────────────────────────────────────────────────────
 *  AM-13 floor gates — in-process ASM ClassVisitor scans over the COMPILED
 *  :core/:platform/:api classes (v61), resolved against scripts/floor-symbols.txt.
 * ──────────────────────────────────────────────────────────────────────── */

class FloorClassScan {
    var name: String = "?"
    var superName: String? = null
    val interfaces = mutableListOf<String>()
    var probeGated = false
    val descriptorTypes = mutableSetOf<String>()
    val getstatics = mutableListOf<Pair<String, String>>()
}

object FloorScanSupport {
    fun parseFloorSymbols(file: File): Pair<Set<String>, Set<String>> {
        val types = mutableSetOf<String>()
        val consts = mutableSetOf<String>()
        var section = ""
        file.readLines().forEach { raw ->
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

    private fun addType(t: Type, into: MutableSet<String>) {
        var x = t
        if (x.sort == Type.ARRAY) x = x.elementType
        if (x.sort == Type.OBJECT) into.add(x.internalName)
    }

    private fun collectTypes(desc: String, into: MutableSet<String>) {
        if (desc.isEmpty()) return
        if (desc[0] == '(') {
            val mt = Type.getMethodType(desc)
            for (a in mt.argumentTypes) addType(a, into)
            addType(mt.returnType, into)
        } else addType(Type.getType(desc), into)
    }

    fun scanClassBytes(bytes: ByteArray): FloorClassScan {
        val scan = FloorClassScan()
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

    fun collectScans(dirs: Iterable<File>): Map<String, FloorClassScan> {
        val map = LinkedHashMap<String, FloorClassScan>()
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                val scan = scanClassBytes(f.readBytes())
                map[scan.name] = scan
            }
        }
        return map
    }

    fun implementsListener(name: String?, map: Map<String, FloorClassScan>, seen: MutableSet<String>): Boolean {
        if (name == null) return false
        if (name == "org/bukkit/event/Listener") return true
        if (!seen.add(name)) return false
        val s = map[name] ?: return false
        if (s.interfaces.any { implementsListener(it, map, seen) }) return true
        return implementsListener(s.superName, map, seen)
    }
}

// verifyDescriptorFloor — a baseline (non-@ProbeGated) Listener may not mention a
// post-floor Bukkit type in ANY method/field descriptor (GAP 1 / listener-swallow).
@CacheableTask
abstract class VerifyDescriptorFloorTask : DefaultTask() {
    @get:InputFiles @get:Classpath abstract val classDirs: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val floorSymbols: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val (postFloorTypes, _) = FloorScanSupport.parseFloorSymbols(floorSymbols.get().asFile)
        val map = FloorScanSupport.collectScans(classDirs.files)
        val problems = mutableListOf<String>()
        for (s in map.values) {
            if (s.probeGated) continue
            if (!FloorScanSupport.implementsListener(s.name, map, mutableSetOf())) continue
            val hits = s.descriptorTypes.filter { it in postFloorTypes }
            if (hits.isNotEmpty()) problems.add("${s.name} (baseline Listener) → descriptor mentions ${hits.sorted()}")
        }
        if (problems.isNotEmpty()) throw GradleException("verifyDescriptorFloor (AM-13): baseline Listener(s) mention post-floor types:\n" +
            problems.take(30).joinToString("\n") { "  - $it" } +
            "\nMove the post-floor-typed handler into its own @ProbeGated listener registered behind a capability.")
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyDescriptorFloor] OK — ${map.size} class(es) scanned; no baseline Listener mentions a post-floor descriptor type.")
    }
}

// verifyNoStickyGetstatic — no GETSTATIC of a post-floor enum constant (GAP 2 / sticky
// NoSuchFieldError); such constants must flow through the Constants resolver / Enum.valueOf.
@CacheableTask
abstract class VerifyNoStickyGetstaticTask : DefaultTask() {
    @get:InputFiles @get:Classpath abstract val classDirs: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val floorSymbols: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val (_, postFloorConsts) = FloorScanSupport.parseFloorSymbols(floorSymbols.get().asFile)
        val map = FloorScanSupport.collectScans(classDirs.files)
        val problems = mutableListOf<String>()
        for (s in map.values) {
            for ((owner, fname) in s.getstatics) {
                if ("$owner.$fname" in postFloorConsts) problems.add("${s.name} → GETSTATIC $owner.$fname")
            }
        }
        if (problems.isNotEmpty()) throw GradleException("verifyNoStickyGetstatic (AM-13): sticky-getstatic hazard(s):\n" +
            problems.take(30).joinToString("\n") { "  - $it" } +
            "\nResolve post-floor enum constants once at boot via the Constants resolver / Enum.valueOf.")
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyNoStickyGetstatic] OK — ${map.size} class(es) scanned; no GETSTATIC of a post-floor enum constant.")
    }
}

/* ── gate registrations ─────────────────────────────────────────────────── */

val jdk8GateAsm: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
dependencies { jdk8GateAsm(libs.asm) }

val serverProvidedIgnores = listOf(
    // Server-provided packages: present on the running server, never bundled or validated.
    "org/bukkit", "net/minecraft", "com/destroystokyo", "io/papermc", "org/spigotmc", "io/netty", "com/mojang",
    "org/jetbrains", "org/intellij", "org/jspecify", "org/checkerframework", "xyz/wagyourtail", "com/google",
    "net/md_5", "net/milkbowl", "com/sk89q", "me/clip", "org/dynmap", "com/earth2me", "github/scarsz",
    "com/griefcraft", "org/apache", "org/yaml",
    // Guarded-optional third-party integrations HARD-referenced by BUNDLED classes of the
    // shaded storage libs, but never LINKED by the embedded-DB/JDBC path: H2's ValueGeometry
    // touches JTS only when the optional JTS lib is present; the MySQL OCI auth plugin
    // (AuthenticationOciClient) reads com.oracle.bmc only when authenticationPlugins names it;
    // the MySQL OpenTelemetry handler (cj.otel, new in connector-j 9.x) touches io.opentelemetry
    // only when its <clinit>-guarded Class.forName("io.opentelemetry.api.GlobalOpenTelemetry")
    // succeeds — absent, NativeSession falls back to NoopTelemetryHandler and the handler's
    // io.opentelemetry-typed members never link. All external SDKs never on our path, so never a
    // runtime miss — the Mental com/viaversion precedent (a guarded optional integration ignored
    // rather than bundled). The allowlist stays EMPTY. (DBCP2/pool2 carry no such optional SDK ref.)
    "org/locationtech", "com/oracle", "io/opentelemetry",
    // commons-logging bundles optional bridges to slf4j (Slf4jLogFactory) and commons-pool2 an
    // optional cglib proxy source — both guarded (commons-logging discovers a Log impl in a
    // try/catch and we ship no slf4j; pool2 defaults to JDK proxies and we ship no cglib), so the
    // classes are dead and their org/slf4j + net/sf/cglib references never link. Ignorable external
    // optionals (route 2), same as the OCI/OTel SDKs above.
    "org/slf4j", "net/sf/cglib",
)

// A relocated FIRST-PARTY class we were FORCED to strip from the shaded jar (route (1) in
// the shadowJar block) yet a bundled CORE class still names in a provably-dead code path:
// H2's org.h2.engine.Database references org.h2.tools.Server ONLY from its AUTO_SERVER
// branch, and the embedded file-mode URL never sets AUTO_SERVER, so the reference never
// links. Ignored ONLY for the core jar (the probe bundles no storage libs) and scoped to
// the exact stripped class, so any OTHER missing relocated class still fails the gate (H1).
val excludedFeatureRefs = listOf("dev/fablemc/factions/lib/h2/tools/Server")

val javaToolchains = extensions.getByType<JavaToolchainService>()

val gateReports = layout.buildDirectory.dir("verify-gates")

val verifyRelocation = tasks.register<VerifyRelocationTask>("verifyRelocation") {
    group = "verification"
    description = "Fails if net/kyori, org/apache/commons/{dbcp2,pool2,logging}, org/h2 or com/mysql survives outside the lib prefix."
    jarFile.set(canonicalJar)
    report.set(gateReports.map { it.file("relocation.txt") })
}

val verifyDowngrade = tasks.register<VerifyDowngradeTask>("verifyDowngrade") {
    group = "verification"
    description = "Fails unless the mega jar is a well-formed MR tier set (base ≤52, versions/13 == v57 fork set, versions/17 == v61 subset, sentinel forked)."
    jarFile.set(canonicalJar)
    report.set(gateReports.map { it.file("downgrade.txt") })
}

val verifyKernelPurity = tasks.register<VerifyKernelPurityTask>("verifyKernelPurity") {
    group = "verification"
    description = "Fails if any dev/fablemc/factions/kernel/ class in the mega jar references org/bukkit."
    jarFile.set(canonicalJar)
    report.set(gateReports.map { it.file("kernel-purity.txt") })
}

val verifyProbeIsolation = tasks.register<VerifyProbeIsolationTask>("verifyProbeIsolation") {
    group = "verification"
    description = "D-8: probe carries no FableFactions jvmdg prefix; neither mega jar references an un-relocated jvmdg stub descriptor."
    coreJar.set(canonicalJar)
    testerJar.set(probeMegaJar.flatMap { it.archiveFile })
    report.set(gateReports.map { it.file("probe-isolation.txt") })
}

val verifyJdk8Api = tasks.register<VerifyJdk8ApiTask>("verifyJdk8Api") {
    group = "verification"
    description = "Fails if any reference in either mega jar's base (v52) tree resolves neither in a real JDK-8 rt.jar, in-jar, nor a server-provided package (H1)."
    coreJar.set(canonicalJar)
    testerJar.set(probeMegaJar.flatMap { it.archiveFile })
    toolSrc.set(rootProject.layout.projectDirectory.file("scripts/tools/Jdk8ApiGate.java"))
    allowFile.set(rootProject.layout.projectDirectory.file("scripts/jdk8-api-gate.allow"))
    asmClasspath.from(jdk8GateAsm)
    jdk8Path.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }
        .map { it.metadata.installationPath.asFile.absolutePath })
    coreIgnores.set(serverProvidedIgnores + excludedFeatureRefs)
    // The probe provides no kernel/api/platform/core code (the FableFactions jar does at
    // runtime), so those packages are server-provided FROM ITS PERSPECTIVE.
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
    description = "AM-13: no baseline (non-@ProbeGated) Listener mentions a post-floor type in a method/field descriptor."
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
        dependsOn(mergeTiers, probeMegaJar)
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
        pluginJars.from(canonicalJar)
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
