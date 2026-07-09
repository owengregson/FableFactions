package fable

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Assembles the canonical Multi-Release mega jar from independently downgraded legs:
 * the base tree is the Java-8 (v52) leg verbatim; META-INF/versions/13 carries every
 * class whose Java-13 leg differs from the base; META-INF/versions/17 every class whose
 * Java-17 leg differs from what a 17+ loader would otherwise resolve (versions/13
 * fall-through, else base). MR resolution picks the highest tier <= the runtime, so
 * this yields minimal tiers with no duplicate copies. Any byte difference forks —
 * including a bare class-file-version bump — because the probe asserts the LOADED
 * class's major equals the support-matrix bytecodeTier on live servers.
 */
@CacheableTask
abstract class MergeMultiReleaseTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseJar: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tier13Jar: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tier17Jar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    private fun classBytes(file: java.io.File): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipFile(file).use { zip ->
            val es = zip.entries()
            while (es.hasMoreElements()) {
                val e = es.nextElement()
                if (e.isDirectory || !e.name.endsWith(".class")) continue
                if (e.name.startsWith("META-INF/versions/")) continue
                map[e.name] = zip.getInputStream(e).use { it.readBytes() }
            }
        }
        return map
    }

    @TaskAction
    fun merge() {
        val base = classBytes(baseJar.get().asFile)
        val inject = sortedMapOf<String, ByteArray>()
        var tier13Count = 0
        var tier17Count = 0

        val t13 = tier13Jar.orNull?.let { classBytes(it.asFile) } ?: emptyMap()
        val effective13 = HashMap(base)
        for ((name, bytes) in t13) {
            if (!bytes.contentEquals(base[name])) {
                inject["META-INF/versions/13/$name"] = bytes
                tier13Count++
            }
            effective13[name] = bytes
        }

        val t17 = tier17Jar.orNull?.let { classBytes(it.asFile) } ?: emptyMap()
        for ((name, bytes) in t17) {
            if (!bytes.contentEquals(effective13[name])) {
                inject["META-INF/versions/17/$name"] = bytes
                tier17Count++
            }
        }

        val out = outputJar.get().asFile
        out.parentFile.mkdirs()
        // One fixed, timezone-independent stamp on EVERY entry (the upstream leg jars
        // carry build wall-clock times) so the canonical jar is a function of content only.
        val fixedStamp = java.time.LocalDateTime.of(1980, 2, 1, 0, 0)
        val written = hashSetOf<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            ZipFile(baseJar.get().asFile).use { zip ->
                val es = zip.entries()
                while (es.hasMoreElements()) {
                    val entry = es.nextElement()
                    if (!written.add(entry.name)) continue
                    val copy = ZipEntry(entry.name)
                    copy.timeLocal = fixedStamp
                    zos.putNextEntry(copy)
                    if (!entry.isDirectory) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == "META-INF/MANIFEST.MF") {
                            val mf = Manifest(bytes.inputStream())
                            mf.mainAttributes.putValue("Multi-Release", "true")
                            val bos = ByteArrayOutputStream()
                            mf.write(bos)
                            zos.write(bos.toByteArray())
                        } else {
                            zos.write(bytes)
                        }
                    }
                    zos.closeEntry()
                }
            }
            for ((name, bytes) in inject) {
                if (!written.add(name)) continue
                val e = ZipEntry(name)
                e.timeLocal = fixedStamp
                zos.putNextEntry(e)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        logger.lifecycle(
            "[${this.name}] ${out.name}: base ${base.size} class(es), " +
                "+versions/13 $tier13Count, +versions/17 $tier17Count")
    }
}
