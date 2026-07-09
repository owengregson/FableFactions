package fable

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/*
 * AM-13 floor gates — in-process ASM scans over the compiled :core/:platform/:api
 * classes (v61), resolved against scripts/floor-symbols.txt.
 */

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

/**
 * verifyDescriptorFloor — a baseline (non-@ProbeGated) Listener may not mention a
 * post-floor Bukkit type in any method/field descriptor (GAP 1 / listener-swallow).
 */
@CacheableTask
abstract class VerifyDescriptorFloorTask : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val floorSymbols: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val (postFloorTypes, _) = FloorScanSupport.parseFloorSymbols(floorSymbols.get().asFile)
        val map = FloorScanSupport.collectScans(classDirs.files)
        val problems = mutableListOf<String>()
        for (s in map.values) {
            if (s.probeGated) continue
            if (!FloorScanSupport.implementsListener(s.name, map, mutableSetOf())) continue
            val hits = s.descriptorTypes.filter { it in postFloorTypes }
            if (hits.isNotEmpty()) problems.add("${s.name} (baseline Listener) -> descriptor mentions ${hits.sorted()}")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("verifyDescriptorFloor (AM-13): baseline Listener(s) mention post-floor types:\n" +
                problems.take(30).joinToString("\n") { "  - $it" } +
                "\nMove the post-floor-typed handler into its own @ProbeGated listener registered behind a capability.")
        }
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyDescriptorFloor] OK — ${map.size} class(es) scanned; no baseline Listener mentions a post-floor descriptor type.")
    }
}

/**
 * verifyNoStickyGetstatic — no GETSTATIC of a post-floor enum constant (GAP 2 / sticky
 * NoSuchFieldError); such constants must flow through the Constants resolver.
 */
@CacheableTask
abstract class VerifyNoStickyGetstaticTask : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val floorSymbols: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val (_, postFloorConsts) = FloorScanSupport.parseFloorSymbols(floorSymbols.get().asFile)
        val map = FloorScanSupport.collectScans(classDirs.files)
        val problems = mutableListOf<String>()
        for (s in map.values) {
            for ((owner, fname) in s.getstatics) {
                if ("$owner.$fname" in postFloorConsts) problems.add("${s.name} -> GETSTATIC $owner.$fname")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("verifyNoStickyGetstatic (AM-13): sticky-getstatic hazard(s):\n" +
                problems.take(30).joinToString("\n") { "  - $it" } +
                "\nResolve post-floor enum constants once at boot via the Constants resolver / Enum.valueOf.")
        }
        report.get().asFile.writeText("ok\n")
        logger.lifecycle("[verifyNoStickyGetstatic] OK — ${map.size} class(es) scanned; no GETSTATIC of a post-floor enum constant.")
    }
}
