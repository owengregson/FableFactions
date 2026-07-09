import fable.JvmdgLegTask
import fable.MergeMultiReleaseTask
import fable.VerifyDowngradeTask
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    // A standalone self-test plugin: it links the FableFactions/kernel/api/platform
    // classes the mega jar provides at runtime, so everything is compileOnly here.
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
}

// api-version derives from support-matrix.json's floorApi, identical to core's — the
// probe must load on every supported server.
@Suppress("UNCHECKED_CAST")
val supportMatrix: Map<String, Any> =
    JsonSlurper().parse(rootProject.layout.projectDirectory.file("support-matrix.json").asFile) as Map<String, Any>
val floorApi: String = supportMatrix["floorApi"] as String

tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "apiVersion" to floorApi)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

/*
 * The probe's own mega-jar pipeline, with a DISTINCT jvmdg prefix from core's.
 * Distinctness is the load-bearing D-8 isolation property: two downgraded plugins
 * sharing a same-FQN pruned jvmdg runtime cross-link on the shared legacy class cache.
 * The probe stays two-tier (v52 base + v61 versions/17) — 5 classes, nothing hot.
 */
tasks.shadowJar {
    archiveBaseName.set("FableFactionsProbe")
    archiveClassifier.set("modern") // intermediate, staged out of build/libs
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    exclude("META-INF/versions/**")
    exclude("META-INF/services/java.sql.Driver")
}

val jvmdgCli = configurations.create("jvmdgCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    jvmdgCli(variantOf(libs.jvmdg.cli) { classifier("all") }) { isTransitive = false }
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val jvmdgLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

fun jvmdgLeg(version: Int) = tasks.register<JvmdgLegTask>("jvmdgLeg$version") {
    group = "jvmdg"
    description = "Downgrades + shades the probe jar to class version $version (forked CLI)."
    inputJar.set(tasks.shadowJar.flatMap { it.archiveFile })
    classVersion.set(version)
    shadePrefix.set("dev/fablemc/factions/probe/lib/jvmdg/") // distinct from core's (D-8)
    cliClasspath.from(jvmdgCli)
    resolveClasspath.from(sourceSets["main"].compileClasspath)
    launcher.set(jvmdgLauncher)
    outputJar.set(layout.buildDirectory.file("jvmdg-stage/FableFactionsProbe-leg$version.jar"))
    logFile.set(layout.buildDirectory.file("jvmdg-stage/jvmdgLeg$version-output.log"))
}

val leg52 = jvmdgLeg(52)
val leg61 = jvmdgLeg(61)

val mergeTiers = tasks.register<MergeMultiReleaseTask>("mergeTiers") {
    group = "jvmdg"
    description = "Assembles the probe's Multi-Release jar (v52 base / v61 versions-17)."
    baseJar.set(leg52.flatMap { it.outputJar })
    tier17Jar.set(leg61.flatMap { it.outputJar })
    outputJar.set(layout.buildDirectory.file("libs/FableFactionsProbe-${project.version}.jar"))
}

tasks.build { dependsOn(mergeTiers) }

val verifyDowngrade = tasks.register<VerifyDowngradeTask>("verifyDowngrade") {
    group = "verification"
    description = "Fails unless the probe jar is a well-formed two-tier MR set (base <=52, versions/17 <=61, sentinel forked)."
    jarFile.set(mergeTiers.flatMap { it.outputJar })
    sentinel.set("dev/fablemc/factions/probe/FableFactionsProbe.class")
    jvmdgPrefix.set("dev/fablemc/factions/probe/lib/jvmdg/")
    expectTier13.set(false)
    report.set(layout.buildDirectory.file("verify-gates/downgrade.txt"))
}

tasks.named("check") { dependsOn(verifyDowngrade) }
