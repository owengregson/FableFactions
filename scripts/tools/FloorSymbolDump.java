/*
 * FloorSymbolDump — the AM-13 floor-symbol table generator.
 *
 * Given a real API jar (the intended source: a procured PaperSpigot 1.7.10 API jar), it emits one line per
 * PUBLIC-or-PROTECTED symbol — the complete class + member table that IS the floor. The AM-13 gates
 * (verifyDescriptorFloor / verifyNoStickyGetstatic) currently run against the hand-curated hazard list in
 * scripts/floor-symbols.txt; when the real jar is procured this tool produces the authoritative table and the
 * gate mechanism upgrades in place (a type/member/enum-constant PRESENT here is at the floor; anything a
 * baseline listener references that is ABSENT here is a post-floor hazard).
 *
 * Usage:
 *   javac -cp asm-9.7.jar -d out scripts/tools/FloorSymbolDump.java
 *   java  -cp out:asm-9.7.jar FloorSymbolDump <apiJar> [outFile]      (stdout when outFile omitted)
 *
 * Output format (stable, greppable; owners/members in JVM-internal form):
 *   CLASS  <internalName>
 *   METHOD <internalName>#<name> <descriptor>
 *   FIELD  <internalName>#<name> <descriptor>
 * Only public/protected classes and their public/protected members are emitted (the callable API surface);
 * synthetic/bridge members are skipped. Lines are sorted for a deterministic, diff-friendly table.
 */
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class FloorSymbolDump {

    static final TreeSet<String> lines = new TreeSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: FloorSymbolDump <apiJar> [outFile]");
            System.exit(2);
        }
        File jar = new File(args[0]);
        if (!jar.isFile()) { System.err.println("FATAL: jar not found: " + jar); System.exit(2); }

        JarFile jf = new JarFile(jar);
        try {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || n.equals("module-info.class")) continue;
                if (n.startsWith("META-INF/")) continue; // skip multi-release overlays
                byte[] bytes = readAll(jf.getInputStream(e));
                try {
                    new ClassReader(bytes).accept(new DumpVisitor(),
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable t) {
                    System.err.println("[floor-dump] warn: could not read " + n + " (" + t + ")");
                }
            }
        } finally {
            jf.close();
        }

        PrintStream out = System.out;
        boolean toFile = args.length >= 2;
        if (toFile) out = new PrintStream(Files.newOutputStream(new File(args[1]).toPath()), false, "UTF-8");
        try {
            out.println("# FloorSymbolDump of " + jar.getName() + " — " + lines.size() + " public/protected symbols");
            for (String s : lines) out.println(s);
        } finally {
            if (toFile) out.close();
        }
        System.err.println("[floor-dump] wrote " + lines.size() + " symbol line(s)"
            + (toFile ? " to " + args[1] : ""));
    }

    static boolean visible(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    static final class DumpVisitor extends ClassVisitor {
        String owner;
        boolean emit;
        DumpVisitor() { super(Opcodes.ASM9); }

        @Override
        public void visit(int v, int access, String name, String sig, String sup, String[] itf) {
            owner = name;
            emit = visible(access);
            if (emit) lines.add("CLASS  " + name);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            if (emit && visible(access) && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0) {
                lines.add("METHOD " + owner + "#" + name + " " + desc);
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String sig, Object val) {
            if (emit && visible(access) && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                lines.add("FIELD  " + owner + "#" + name + " " + desc);
            }
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private FloorSymbolDump() {}
}
