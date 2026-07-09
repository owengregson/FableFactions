package fable

import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Shared jar-scan helpers. Class bytes are read as ISO-8859-1 (a lossless byte-to-char
 * map) so a substring match equals an exact constant-pool byte match.
 */
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

/** verifyRelocation — no un-relocated third-party token survives the shade. */
@CacheableTask
abstract class VerifyRelocationTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    /** Package tokens (slash form) that must only appear under the lib prefix. */
    @get:Input
    abstract val forbiddenTokens: ListProperty<String>

    @get:Input
    abstract val libPrefix: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val lib = libPrefix.get()
        val tokens = forbiddenTokens.get()
        val entryViolations = mutableListOf<String>()
        val refViolations = mutableListOf<String>()
        ZipFile(jarFile.get().asFile).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                val logical = MegaJarScan.mrStrip(name)
                if (name.endsWith(".class") && !logical.startsWith(lib)) {
                    val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    for (t in tokens) {
                        if (text.contains(t) || text.contains(t.replace('/', '.'))) {
                            refViolations.add("$name -> $t"); break
                        }
                    }
                }
                if (tokens.any { logical.startsWith("$it/") }) entryViolations.add(logical)
            }
        }
        if (entryViolations.isNotEmpty() || refViolations.isNotEmpty()) {
            throw GradleException(buildString {
                append("Relocation rot: un-relocated token(s) survived the shade.\n")
                entryViolations.take(20).forEach { append("  entry  $it\n") }
                refViolations.take(20).forEach { append("  ref    $it\n") }
                append("Everything third-party MUST relocate under $lib.")
            })
        }
        report.get().asFile.writeText("clean\n")
        logger.lifecycle("[relocation] clean — no un-relocated third-party token in ${jarFile.get().asFile.name}.")
    }
}

/**
 * verifyDowngrade — the Multi-Release tier shape produced by the leg pipeline:
 * base <= v52, versions/13 <= v57, versions/17 <= v61, no other tiers; the boot
 * sentinel forked to each tier's exact major; no first-party base class reflectively
 * introspects records (H4 — a downgraded record is not a reflective record); and every
 * relocated jvmdg-runtime reference resolves in-jar for every tier's effective class
 * set (base, base+13, base+13+17 — MR fall-through semantics).
 */
@CacheableTask
abstract class VerifyDowngradeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    /** Sentinel class path (base form), asserted forked into every expected tier. */
    @get:Input
    abstract val sentinel: Property<String>

    @get:Input
    abstract val jvmdgPrefix: Property<String>

    /** Whether a versions/13 tier is expected (core: yes, probe: no). */
    @get:Input
    abstract val expectTier13: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val file = jarFile.get().asFile
        val problems = mutableListOf<String>()
        val sentinelPath = sentinel.get()
        val prefix = jvmdgPrefix.get()
        val tierCap = mapOf(0 to 52, 13 to 57, 17 to 61)

        JarFile(file).use { jar ->
            val mr = jar.manifest?.mainAttributes?.getValue("Multi-Release")
            if (!"true".equals(mr, ignoreCase = true)) problems.add("manifest Multi-Release is '$mr' (expected true)")
        }

        // tier -> logical name -> bytes
        val tiers = mapOf(0 to HashMap<String, ByteArray>(), 13 to HashMap(), 17 to HashMap())
        ZipFile(file).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val entry = es.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                val tier = when {
                    name.startsWith("META-INF/versions/13/") -> 13
                    name.startsWith("META-INF/versions/17/") -> 17
                    name.startsWith("META-INF/versions/") -> {
                        problems.add("unexpected versioned tier entry $name (only versions/13 and versions/17 are allowed)")
                        continue
                    }
                    else -> 0
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val major = MegaJarScan.classMajor(bytes)
                val cap = tierCap.getValue(tier)
                if (major > cap) problems.add("tier $tier entry ${MegaJarScan.mrStrip(name)} is v$major (>$cap)")
                tiers.getValue(tier)[MegaJarScan.mrStrip(name)] = bytes
            }
        }

        val expectedTiers = if (expectTier13.get()) listOf(0, 13, 17) else listOf(0, 17)
        if (!expectTier13.get() && tiers.getValue(13).isNotEmpty()) {
            problems.add("versions/13 tier present but not expected (${tiers.getValue(13).size} class(es))")
        }
        val sentinelMajors = mapOf(0 to 52, 13 to 57, 17 to 61)
        for (t in expectedTiers) {
            val bytes = tiers.getValue(t)[sentinelPath]
            when {
                bytes == null -> problems.add("sentinel $sentinelPath missing from tier $t")
                MegaJarScan.classMajor(bytes) != sentinelMajors.getValue(t) ->
                    problems.add("sentinel tier-$t major is ${MegaJarScan.classMajor(bytes)} (expected ${sentinelMajors.getValue(t)})")
            }
        }

        // H4: Class.isRecord()/RecordComponent on a downgraded record lies at runtime.
        for ((logical, bytes) in tiers.getValue(0)) {
            if (!MegaJarScan.isFirstParty(logical)) continue
            val text = String(bytes, Charsets.ISO_8859_1)
            if (text.contains("isRecord") || text.contains("java/lang/reflect/RecordComponent")) {
                problems.add("base class $logical references Class.isRecord/RecordComponent (H4)")
            }
        }

        // jvmdg-runtime closure per effective tier set.
        val effective = HashMap(tiers.getValue(0))
        for (t in listOf(0, 13, 17)) {
            if (t != 0) effective.putAll(tiers.getValue(t))
            if (t !in expectedTiers) continue
            val refs = sortedSetOf<String>()
            for (bytes in effective.values) {
                refs.addAll(MegaJarScan.tokensWithPrefix(String(bytes, Charsets.ISO_8859_1), prefix))
            }
            for (ref in refs) {
                if (ref.endsWith("/")) continue // bare path constant, not a class ref
                if ("$ref.class" !in effective) {
                    problems.add("tier-$t effective set references jvmdg runtime class $ref which does not resolve in-jar")
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("verifyDowngrade: not a well-formed tier set:\n" +
                problems.take(30).joinToString("\n") { "  - $it" })
        }
        report.get().asFile.writeText("ok\n")
        logger.lifecycle(
            "[verifyDowngrade] OK — base ${tiers.getValue(0).size} class(es) <= v52; " +
                "versions/13 ${tiers.getValue(13).size} <= v57; versions/17 ${tiers.getValue(17).size} <= v61; " +
                "sentinel forked; jvmdg refs resolve in every tier.")
    }
}

/** verifyKernelPurity — no org/bukkit token in any kernel class in the final jar. */
@CacheableTask
abstract class VerifyKernelPurityTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

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
        if (violations.isNotEmpty()) {
            throw GradleException("verifyKernelPurity: kernel class(es) reference org/bukkit:\n" +
                violations.take(20).joinToString("\n") { "  - $it" })
        }
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyKernelPurity] OK — $kernelClasses kernel class(es), none reference org/bukkit.")
    }
}

/**
 * verifyProbeIsolation (D-8) — (a) the probe mega jar must not carry FableFactions' own
 * jvmdg-runtime prefix; (b) neither mega jar may reference an un-relocated jvmdg STUB
 * type in a descriptor (a v52-read server would hang at link time, not fail the build).
 */
@CacheableTask
abstract class VerifyProbeIsolationTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coreJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val testerJar: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

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
                if (t.contains("dev/fablemc/factions/lib/jvmdg")) {
                    problems.add("probe references dev/fablemc/factions/lib/jvmdg (${e.name})")
                }
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
                            if (fqn.contains("/stub/")) problems.add("${jar.name}: $name -> $fqn (un-relocated jvmdg stub in a descriptor)")
                        }
                        i = t.indexOf(stub, i + 1)
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("verifyProbeIsolation (D-8):\n" +
                problems.distinct().take(20).joinToString("\n") { "  - $it" })
        }
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyProbeIsolation] OK — probe carries no FableFactions jvmdg prefix; no un-relocated jvmdg stub descriptors.")
    }
}

/**
 * verifyJdk8Api (H1) — closed-world scan of both mega jars' base (v52) tree against a
 * real JDK 8: compiles scripts/tools/Jdk8ApiGate.java in-process and runs it on a
 * foojay-provisioned JDK 8.
 */
@CacheableTask
abstract class VerifyJdk8ApiTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val coreJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val testerJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolSrc: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowFile: RegularFileProperty

    @get:Classpath
    abstract val asmClasspath: ConfigurableFileCollection

    @get:Input
    abstract val jdk8Path: Property<String>

    @get:Input
    abstract val coreIgnores: ListProperty<String>

    @get:Input
    abstract val probeIgnores: ListProperty<String>

    @get:Internal
    abstract val classesDir: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val objects: ObjectFactory

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
            if (result.exitValue != 0) {
                throw GradleException("[verifyJdk8Api] $label has references absent from Java 8 (see the [jdk8-gate] output above).")
            }
        }
        report.get().asFile.writeText("ok\n")
    }
}
