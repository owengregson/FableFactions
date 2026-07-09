package fable

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/**
 * One downgrade leg of the mega-jar pipeline: forks the JvmDowngrader CLI to
 * `downgrade -c N | shade` the shaded jar in a fresh, short-lived JVM.
 *
 * Forking is load-bearing, not a style choice: jvmdg's in-daemon execution keeps a
 * static ForkJoinPool and hot timed-wait loops alive across builds, which degrades
 * into instant TimeoutExceptions in long-lived daemons. A fresh JVM per leg makes
 * that class of failure impossible and scopes all output to this task (no global
 * console listeners, no cross-task locks).
 */
@CacheableTask
abstract class JvmdgLegTask : DefaultTask() {
    /** The shaded (relocated) input jar. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputJar: RegularFileProperty

    /** Target class-file major version (52 = Java 8, 57 = 13, 61 = 17). */
    @get:Input
    abstract val classVersion: Property<Int>

    /** Relocation prefix for the jvmdg runtime classes the shade step injects. */
    @get:Input
    abstract val shadePrefix: Property<String>

    /**
     * Package/class prefixes whose unresolvable references are expected: guarded-optional
     * integrations of the shaded libs, and classes route-(1)-stripped from the shade.
     */
    @get:Input
    abstract val ignoreWarningsIn: ListProperty<String>

    /** The jvmdg CLI (-all) jar. */
    @get:Classpath
    abstract val cliClasspath: ConfigurableFileCollection

    /** Classpath jvmdg resolves supertypes against (union of compile classpaths). */
    @get:CompileClasspath
    abstract val resolveClasspath: ConfigurableFileCollection

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:OutputFile
    abstract val logFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun run() {
        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        // Private working/tmp dir per leg so concurrent forks never share scratch state.
        val work = temporaryDir
        work.deleteRecursively()
        work.mkdirs()

        val captured = ByteArrayOutputStream()
        val result = execOps.javaexec {
            executable = launcher.get().executablePath.asFile.absolutePath
            classpath = cliClasspath
            mainClass.set("xyz.wagyourtail.jvmdg.cli.Main")
            workingDir = work
            systemProperty("java.io.tmpdir", work.absolutePath)
            args("--noColor", "--classVersion", classVersion.get().toString())
            ignoreWarningsIn.get().forEach { args("--ignoreWarningsIn", it) }
            args(
                "downgrade",
                "--classpath", resolveClasspath.asPath,
                "--target", inputJar.get().asFile.absolutePath, "-",
                "shade",
                "--prefix", shadePrefix.get(),
                "--target", "-", out.absolutePath,
            )
            standardOutput = captured
            errorOutput = captured
            isIgnoreExitValue = true
        }

        val text = captured.toString(Charsets.UTF_8)
        val log = logFile.get().asFile
        log.parentFile.mkdirs()
        log.writeText(text)

        if (result.exitValue != 0) {
            throw GradleException(
                "jvmdg CLI exited ${result.exitValue} for '$name'. Tail:\n" +
                    text.lines().takeLast(20).joinToString("\n") { "    $it" } +
                    "\nFull: ${log.absolutePath}")
        }
        // jvmdg warnings mean an unresolved supertype or missing stub — always a real
        // defect in the pipeline, so they fail the build. JVM startup notes are not ours.
        val jvmNoise = Regex("sun\\.misc\\.Unsafe|Please consider reporting this to the maintainers")
        val warnings = text.lines()
            .filter { Regex("(?i)\\b(warn|warning|error)\\b").containsMatchIn(it) }
            .filterNot { jvmNoise.containsMatchIn(it) }
        if (warnings.isNotEmpty()) {
            throw GradleException(
                "jvmdg emitted ${warnings.size} warning/error line(s) during '$name'. First:\n" +
                    warnings.take(20).joinToString("\n") { "    $it" } +
                    "\nFull: ${log.absolutePath}")
        }
    }
}
