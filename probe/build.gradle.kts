import groovy.json.JsonSlurper
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderExtension

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.jvmdowngrader)
}

dependencies {
    // A standalone self-test plugin: it links the FableFactions/kernel/api/platform
    // classes the mega jar provides at runtime, so everything is compileOnly here.
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
}

// api-version derives from support-matrix.json's floorApi (the descriptor owns the
// Bukkit floor). The probe must load on every supported server, so this expansion is
// identical to core's — a stale literal would fail the whole legacy boot.
@Suppress("UNCHECKED_CAST")
val supportMatrix: Map<String, Any> =
    JsonSlurper().parse(rootProject.layout.projectDirectory.file("support-matrix.json").asFile) as Map<String, Any>
val floorApi: String = supportMatrix["floorApi"] as String

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "apiVersion" to floorApi)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

/* ────────────────────────────────────────────────────────────────────────
 *  The probe's OWN mega-jar pipeline (mental-build §3-5), with a DISTINCT jvmdg
 *  prefix (dev/fablemc/factions/probe/lib/jvmdg/) from core's. Distinctness is the
 *  load-bearing D-8 isolation property: two downgraded plugins sharing a same-FQN
 *  pruned jvmdg runtime cross-link and fail on the shared legacy class cache.
 *  (The probe stays TWO-tier — 5 classes, nothing hot; the v57 tier is core-only.)
 * ──────────────────────────────────────────────────────────────────────── */

// Same global-console-capture hazard as core's jvmdg tasks: serialize every jvmdg
// task across the whole build via the shared lock (registerIfAbsent is name-keyed,
// so this resolves to the one service core registered) and fence out test JVMs.
abstract class JvmdgConsoleLock : BuildService<BuildServiceParameters.None>

val jvmdgConsoleLock = gradle.sharedServices.registerIfAbsent("jvmdgConsoleLock", JvmdgConsoleLock::class) {
    maxParallelUsages.set(1)
}

val consoleNoisyTasks = listOf(
    ":kernel:test", ":api:test", ":platform:test", ":core:test",
    ":compat-folia:test", ":compat-modern:test", ":probe:test",
    ":kernel:compileTestJava", ":api:compileTestJava", ":platform:compileTestJava",
    ":core:compileTestJava",
)

// Warning capture (warnings = build failures). Filters JDK-24+ Unsafe deprecation
// noise from parallel JVMs that leaks into Gradle's GLOBAL console stream.
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

tasks.shadowJar {
    archiveBaseName.set("FableFactionsProbe")
    archiveClassifier.set("modern")                                   // intermediate, staged out of build/libs
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    exclude("META-INF/versions/**")
    exclude("META-INF/services/java.sql.Driver")
}

val jvmdg = extensions.getByType<JVMDowngraderExtension>()

val downgradeProbeJar = jvmdg.defaultTask
downgradeProbeJar.configure {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    multiReleaseOriginal.set(true)                                    // never multiReleaseVersions (1.3.6 drops v61)
    classpath = sourceSets["main"].compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("FableFactionsProbe")
    archiveClassifier.set("downgraded")
    failOnJvmdgWarnings(this)
}

val probeMegaJar = jvmdg.defaultShadeTask
probeMegaJar.configure {
    inputFile.set(downgradeProbeJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    shadePath.set { "dev/fablemc/factions/probe/lib/jvmdg/" }        // DISTINCT from core's prefix (D-8)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set("FableFactionsProbe")
    archiveClassifier.set("")
    failOnJvmdgWarnings(this)
}

tasks.build { dependsOn(probeMegaJar) }
