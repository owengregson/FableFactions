# Mental (StrikeSync) Build Pipeline — Replication Recipe

Source studied: `/Users/owengregson/Documents/StrikeSync`. This is a complete, self-contained
recipe for reproducing Mental's **Multi-Release mega-jar** pipeline in a NEW project without
reading Mental again. Mental-specific bits (PacketEvents, Adventure shade, the `tester` module,
Folia compat) are flagged; the generic mega-jar machinery is the reusable core.

---

## 0. TL;DR — what the pipeline produces

One shipped artifact per plugin: `build/libs/Mental-<version>.jar`, a **Multi-Release JAR** where

- the **base tree is class-file major 52 (Java 8)** — loads on every JVM Java 8+;
- the **original modern classes (major 61 / Java 17) are kept verbatim under `META-INF/versions/17/`** — byte-identical to the pre-mega "modern" line, selected by any JVM whose feature version ≥ 17;
- there is **no `versions/16` tier** (deliberately dropped — see §4 gotcha);
- all third-party libs are shaded + relocated under `me/vexmc/mental/lib/…`;
- jvmdg's own runtime helpers are shaded under `me/vexmc/mental/lib/jvmdg/` (a **per-plugin distinct prefix**).

The jar therefore classloads from Java 8 upward from a single file: legacy Paper (1.9.4–1.16.5, running on whatever JDK they accept) read the v52 base; every Java-17+ runtime reads `versions/17` and runs the original modern bytecode.

Pipeline = **shadowJar (relocate, stage as `-modern`) → jvmdg DowngradeJar (v61→v52 MR, stage as `-downgraded`) → jvmdg ShadeJar (relocate jvmdg runtime, emit canonical jar)**, plus a wall of build-time verification gates.

---

## 1. Versions & tool coordinates (exact)

`gradle/libs.versions.toml`:

```toml
[versions]
shadow        = "9.4.2"
run-paper     = "3.0.2"
jvmdowngrader = "1.3.6"          # THE load-bearing tool version; behavior below is 1.3.6-specific
junit         = "5.11.4"
adventure     = "4.9.3"          # Mental-specific (shaded Adventure)
packetevents  = "2.12.1"         # Mental-specific

[plugins]
shadow        = { id = "com.gradleup.shadow",              version.ref = "shadow" }
run-paper     = { id = "xyz.jpenilla.run-paper",           version.ref = "run-paper" }
jvmdowngrader = { id = "xyz.wagyourtail.jvmdowngrader",    version.ref = "jvmdowngrader" }
```

- **Gradle wrapper: 9.5.1** (`gradle/wrapper/gradle-wrapper.properties`, `gradle-9.5.1-bin.zip`). Note Gradle 9 removed `Project.javaexec` — use injected `ExecOperations` (see §6 `verifyJdk8Api`).
- japicmp CLI: `com.github.siom79.japicmp:japicmp:0.23.1:jar-with-dependencies` (Mental-specific API gate; §7).
- ASM for the JDK-8 gate: `org.ow2.asm:asm:9.7` (§6).
- Shadow plugin id is the **gradleup** fork `com.gradleup.shadow` (not the old `johnrengelman`).

`settings.gradle.kts` uses foojay resolver for toolchain auto-provisioning:

```kotlin
pluginManagement {
    repositories { gradlePluginPortal(); maven("https://repo.papermc.io/repository/maven-public/") }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
```

Repos (root `build.gradle.kts`): `mavenCentral()`, `repo.papermc.io`, `repo.codemc.io` releases + snapshots (codemc serves PacketEvents; Mental-specific).

---

## 2. Compile floor, toolchain, and how v52-on-Java-8 is achieved

Root `build.gradle.kts` `subprojects` block — **this is the crux of "how does v52 end up loadable on Java 8":**

```kotlin
subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))   // BUILD JDK (compiles the plugin)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)                                     // COMPILE FLOOR: bytecode target v61
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}
```

Key distinction the comments hammer:

- **Toolchain = JDK 25.** This is *not* a support-matrix member; it's just the newest matrix runtime's required JDK (25). CI derives it as `jq '[.entries[].jdk] | max' support-matrix.json`.
- **Compile floor = `options.release = 17`** → javac emits **class major 61 (Java 17)**. The source compiles against Java 17 language + API. It is NOT compiled to Java 8. The comment: "the bytecode target that keeps the jar loadable on the matrix FLOOR (Java 17, Paper 1.17.1)."
- **The v52 base tree is NOT produced by javac.** It is produced **downstream by JVMDowngrader**, which mechanically lowers the v61 bytecode (records, sealed types, switch-expr, string-concat invokedynamic) to v52 and shims common post-8 stdlib calls. So: `javac → v61` everywhere, then `jvmdg → v52 base + v61 kept under versions/17`. That downgraded v52 tree is what classloads on Java 8.
- `version` lives in **one** place: `gradle.properties` (`version=2.4.6-beta`), read by `providers.gradleProperty("version")` in the root `allprojects`. `group = "me.vexmc"`.

There is no `release.set(8)` anywhere. The Java-8 support comes entirely from the jvmdg downgrade step, never from javac.

---

## 3. shadowJar configuration (per-plugin)

`core/build.gradle.kts`, `tasks.shadowJar`:

```kotlin
tasks.shadowJar {
    dependsOn(":compat-folia:classes")               // Mental-specific: folds Folia compat classes in
    archiveBaseName.set("Mental")
    archiveClassifier.set("modern")                  // STAGE, not shipped
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))   // OUT of build/libs
    from(project(":compat-folia").sourceSets.main.get().output)          // Mental-specific

    // ---- relocations (Mental-specific set) ----
    relocate("com.github.retrooper.packetevents", "me.vexmc.mental.lib.packetevents.api")
    relocate("io.github.retrooper.packetevents",  "me.vexmc.mental.lib.packetevents.impl")
    relocate("org.bstats",                        "me.vexmc.mental.lib.bstats")
    relocate("net.kyori",                         "me.vexmc.mental.lib.adventure")
}
```

Load-bearing shadowJar decisions (generic, reusable):

- **The shaded modern jar is an INTERMEDIATE, not the shipped artifact.** It is given `archiveClassifier = "modern"` and staged into `build/jvmdg-stage/`, deliberately OFF the canonical name and OUT of `build/libs`, so the `Mental-*.jar` glob (used by CI/release/scripts) only ever resolves the *final* mega jar. This prevents the intermediate from being mistaken for the release artifact.
- **No `minimize()` is used.** Mental does not call shadow's minimization. (Relevant because minimize + relocation + reflection is a classic footgun; Mental avoids it entirely.)
- All relocations move third-party packages under `me/vexmc/mental/lib/…`. This single prefix is what every downstream verify gate keys off ("first-party = `me/vexmc/mental/` but NOT `me/vexmc/mental/lib/`").

**Mental-specific relocation notes** (why each exists):
- PacketEvents split into `.api`/`.impl` (its two top-level packages `com.github.retrooper` / `io.github.retrooper`).
- Adventure (`net.kyori`) is shaded so its legacy-string serializer exists on servers older than Paper 1.16.5 where `net.kyori` is absent. On modern servers the relocated copy is inert (Paper has its own Adventure); on legacy it is the only copy. The invariant "no Component crosses a Bukkit boundary; every `net.kyori` reference is the relocated copy" is enforced by `verifyRelocation` (§6). Adventure pinned to 4.9.3 (the version Paper's 1.17.1 floor API declares) via the BOM so the shaded copy is API-identical to the compile classpath.
- bStats relocated (standard requirement — bStats refuses to load un-relocated).

The **tester** (`tester/build.gradle.kts`) has its own shadowJar with the SAME staging pattern but different relocations and a **different lib prefix**:

```kotlin
tasks.shadowJar {
    archiveBaseName.set("MentalTester")
    archiveClassifier.set("modern")
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    relocate("xyz.jpenilla.reflectionremapper", "me.vexmc.mental.tester.lib.reflectionremapper")
    relocate("net.fabricmc.mappingio",          "me.vexmc.mental.tester.lib.mappingio")
}
```

Note the tester uses `me/vexmc/mental/tester/lib/…` — a **distinct** prefix. This distinctness is the load-bearing D-8 isolation property (see §4/§6).

---

## 4. The jvmdg DowngradeJar step (v61 → Multi-Release v52 + versions/17)

`core/build.gradle.kts`. The jvmdg plugin **pre-registers** two default tasks accessed via its extension:

```kotlin
import xyz.wagyourtail.jvmdg.gradle.JVMDowngraderExtension

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.jvmdowngrader)
}

val jvmdg = extensions.getByType<JVMDowngraderExtension>()

// DowngradeJar (input defaults to shadowJar; retarget it explicitly)
val downgradeMegaJar = jvmdg.defaultTask
downgradeMegaJar.configure {
    inputFile.set(tasks.shadowJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)          // base tier = v52
    multiReleaseOriginal.set(true)                    // KEEP original v61 under META-INF/versions/17
    // multiReleaseVersions is deliberately NOT set (see gotcha below)

    // Downgrade classpath MUST carry supertypes of every referenced type:
    classpath = sourceSets["main"].compileClasspath +
                project(":compat-folia").sourceSets["main"].compileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("jvmdg-stage"))
    archiveBaseName.set("Mental")
    archiveClassifier.set("downgraded")               // STAGE, not shipped
    mustRunAfter(":api:apiCompat")                    // global-console-capture ordering (see gotcha)
    failOnJvmdgWarnings(this)                          // warnings = build failures
}
```

**How `multiReleaseOriginal` works and the #1 gotcha (jvmdg 1.3.6):**

- `multiReleaseOriginal.set(true)` → jvmdg emits base tree at v52 **and** keeps the *untouched original v61 classes* under `META-INF/versions/17/`. Result = two trees: base v52 + versions/17 major-61.
- jvmdg only forks a v61 overlay for classes whose downgrade could *behave differently* on a modern JVM (those calling shimmed Java-9+ APIs — they must run the REAL API on 17+). Classes needing only behavior-*preserving* downgrades (string-concat → StringBuilder, record/sealed metadata annotations) get **no overlay** and load as v52 even on modern JVMs — functionally identical. So `versions/17` is a **subset** of base, not a full duplicate. Third-party classes already ≤ v52 (packetevents/bstats/adventure targets) get no MR duplicate.
- **CRITICAL GOTCHA:** jvmdg 1.3.6 treats `multiReleaseOriginal` (`-mro`, keep original) and `multiReleaseVersions` (`-mr <ver>`, keep a semi-downgraded intermediate) as **MUTUALLY EXCLUSIVE**. Requesting a `versions/16` (v60) tier via `multiReleaseVersions` would **DROP the original v61**, silently downgrading the modern path. Mental therefore keeps only the original (no `versions/16`). A 1.16.5-on-Java-16 server just reads the v52 base tree (fully functional; v52 is the tested legacy base). This was an escalated jvmdg limitation.
- **Downgrade classpath gotcha:** jvmdg must resolve supertypes of every referenced type. Since shadowJar folded in `compat-folia` classes whose supertypes are the Folia scheduler API (absent from core's 1.17.1 floor compile classpath), the classpath is the **union** of core's own compile classpath and compat-folia's, or jvmdg emits warnings (which fail the build).

**Tester DowngradeJar** is identical shape but classpath = default `compileClasspath` (it carries the external supertypes not bundled: paper-api, netty, and the kernel/api/platform/core classes the Mental jar provides at runtime — all `compileOnly` in tester).

---

## 5. The jvmdg ShadeJar step (relocate jvmdg runtime, emit canonical jar)

```kotlin
// ShadeJar — relocates jvmdg's runtime helpers under our lib prefix, emits the glob-visible jar
val megaJar = jvmdg.defaultShadeTask
megaJar.configure {
    inputFile.set(downgradeMegaJar.flatMap { it.archiveFile })
    downgradeTo.set(JavaVersion.VERSION_1_8)
    // Kotlin DSL sets the Function1 property directly; input class path is ignored.
    // Every jvmdg helper lands under this ONE prefix: me/vexmc/mental/lib/jvmdg/xyz/wagyourtail/…
    shadePath.set { "me/vexmc/mental/lib/jvmdg/" }
    destinationDirectory.set(layout.buildDirectory.dir("libs"))   // FINAL: build/libs
    archiveBaseName.set("Mental")
    archiveClassifier.set("")                                      // canonical Mental-<version>.jar
    mustRunAfter(":api:apiCompat")
    failOnJvmdgWarnings(this)
}

tasks.build { dependsOn(megaJar) }
```

- The ShadeJar takes the downgraded MR jar and **relocates jvmdg's own runtime helper classes** (which jvmdg's downgrade injects as calls into `xyz/wagyourtail/jvmdg/…`) under the plugin's `me/vexmc/mental/lib/jvmdg/` prefix. This is what makes the plugin self-contained on Java 8 (the runtime shims travel inside the jar).
- **Per-plugin prefix is load-bearing (D-8):** the tester uses `shadePath.set { "me/vexmc/mental/tester/lib/jvmdg/" }`. Two downgraded plugins on the same server share a class cache; if both carried the *same-FQN* pruned jvmdg runtime they'd cross-link and fail. Distinct prefixes prevent it. `verifyTesterIsolation` (§6) asserts this at build time.
- `shadePath` is set as a **`Function1`** (a lambda returning the prefix string) in the Kotlin DSL — the input class path argument is ignored; every helper lands under the one prefix.
- Only THIS task's output (`build/libs/Mental-<version>.jar`) is the shipped artifact. `tasks.build { dependsOn(megaJar) }` wires it into `build`.

### Task wiring / dependency chain (full)

```
processResources (expands plugin.yml)
        │
     jar / classes ──► :compat-folia:classes (Mental-specific dep of shadowJar)
        │
   shadowJar  (build/jvmdg-stage/Mental-<v>-modern.jar)         [relocate 3rd-party]
        │  inputFile
   downgradeMegaJar = jvmdg.defaultTask  (…-downgraded.jar)     [v61→v52 MR]  mustRunAfter :api:apiCompat
        │  inputFile
   megaJar = jvmdg.defaultShadeTask  (build/libs/Mental-<v>.jar)[relocate jvmdg runtime]  mustRunAfter :api:apiCompat
        │
   build ──dependsOn──► megaJar
   check ──dependsOn──► verifyRelocation, verifyDowngrade, verifyTesterIsolation, verifyJdk8Api, apiCompat (:api)
```

`core` does `evaluationDependsOn(":tester")` so it can reference the tester's mega-jar task. The tester's final mega jar is looked up **by task NAME**, not by extension type:

```kotlin
val testerMegaJar = project(":tester").tasks.named<AbstractArchiveTask>(megaJar.name)
```

Gotcha: you CANNOT use `project(":tester").extensions.getByType<JVMDowngraderExtension>()` — that reified type resolves through core's plugin classloader and won't match the tester's separately-loaded extension class (throws cross-project). `AbstractArchiveTask` is a Gradle core type (shared classloader), so the by-name typed lookup is safe. The jvmdg plugin names its default shade task identically in every project, so `megaJar.name` is the tester's task name too.

### `failOnJvmdgWarnings` helper (warnings = build failures)

Both `core` and `tester` define this (core as a top-level fun, tester as a `Jar.` extension fun). It captures Gradle's stdout/stderr during the jvmdg task, writes it to `build/jvmdg-stage/<task>-output.log`, and throws `GradleException` if any line matches `(?i)\b(warn|warning|error)\b`.

**Gotcha (documented, cost a release):** the capture listens on Gradle's **GLOBAL** console stream (a task `LoggingManager` listener is NOT task-scoped), so a *parallel* task's output can leak into the window. The 2.4.1 release job failed because japicmp's `--ignore-missing-classes` startup banner landed in the jvmdg window. Two mitigations: (1) `mustRunAfter(":api:apiCompat")` orders the one known contaminator out of the window; (2) the JDK-24+ JEP-498 `sun.misc.Unsafe` deprecation notes from parallel test JVMs are explicitly filtered out (`Regex("sun\\.misc\\.Unsafe|Please consider reporting this to the maintainers")`). japicmp was also switched to a real classpath so it no longer prints the banner at all (§7).

---

## 6. Build-time verification gates (all wired into `check`)

These make the mega-jar invariants un-rottable on every `./gradlew build`. All read the **final mega jar(s)**, never the staged intermediates. Class bytes are read as **ISO-8859-1** (a lossless byte↔char map) so a substring match equals an exact constant-pool byte-sequence match — a technique reused across every gate.

Helper functions in core:
```kotlin
fun mrStrip(name): String            // strips META-INF/versions/<n>/ prefix → logical name
fun classMajor(bytes): Int           // bytes[6..7] big-endian = class-file major version
fun isFirstParty(logical): Boolean   // me/vexmc/mental/ AND NOT .../lib/ AND NOT .../tester/lib/
```

### verifyRelocation (F-BP3) — Mental-specific (Adventure), pattern is generic
Two scans over the produced mega jar:
1. **Entry names:** no jar entry may live under `net/kyori/` or `xyz/wagyourtail/` (un-relocated class/resource).
2. **Class references:** no `.class` entry OUTSIDE the relocated prefix `me/vexmc/mental/lib/` may contain the token `net/kyori` or `net.kyori` (a type ref or a reflection/service string).

Any hit fails the build. Catches relocation rot that would bind to Paper's native Adventure on modern (silent drift) or `NoClassDefFoundError` on legacy. Note: jvmdg leaves bare CLASS-retention marker annotations (`xyz/wagyourtail/jvmdg/j{11,16,17}` NestHost/RecordComponents/etc.) un-relocated *by design* — harmless, never resolved; their correctness is `verifyJdk8Api`'s job, so verifyRelocation scans only `net.kyori` by reference.

### verifyDowngrade (Q2/H4) — GENERIC, the core MR-shape assertion
Asserts the mega jar is a well-formed Multi-Release tier set:
- Manifest declares `Multi-Release: true`.
- **Base tree: no class-file major > 52.** (`isFirstParty` classes collected.)
- **`versions/17`: every first-party class is major 61**; it must be a **subset** of base (no "phantom overlay" — a versions/17 class absent from base fails).
- **`versions/16`** (if ever produced): ≤ v60. Any *other* `META-INF/versions/*` tier is "unexpected" and fails.
- **Sentinel class** `me/vexmc/mental/v5/MentalPluginV5.class` must be major **52 in base** and major **61 in versions/17** (proves the fork happened).
- Any **base-only** first-party class (in base, no v61 overlay) must carry **NO** `me/vexmc/mental/lib/jvmdg` reference — if it did, a shimmed API would load its shim on a modern JVM, breaking modern-behavior identity (D-1). This is verified by scanning the base class bytes.
- **H4:** no downgraded base class (other than the jvmdg runtime itself) may contain `isRecord` or `java/lang/reflect/RecordComponent` — a downgraded record is NOT a reflective record (`Class.isRecord()==false`), so reflective record introspection would silently misbehave.

### verifyJdk8Api (H1) — GENERIC, the closed-world Java-8 resolvability gate
The strongest gate. A standalone tool `scripts/tools/Jdk8ApiGate.java` (ASM-based, adapted from "StarEnchants") is compiled in-process against `org.ow2.asm:asm:9.7` and run via `ExecOperations.javaexec` against a **real JDK 8** (provisioned by the foojay toolchain, resolved by `javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }`).

It scans the **base (v52) tree only** (`META-INF/versions/*` skipped) of BOTH mega jars. Every JDK reference must resolve in a real Java-8 `rt.jar`; every non-JDK non-ignored reference must resolve **in-jar** (subsumes H2: a jvmdg runtime helper the shade forgot to bundle resolves nowhere → hard miss). Why it exists: jvmdg downgrades the language and shims *common* post-8 stdlib calls but NOT everything; a stray un-shimmable JDK-9+ API (the cited real catch: `ThreadLocalRandom.nextDouble(double)`) compiles/downgrades/shades green then `NoSuchMethodError`s on real Java 8. This is the static net.

- **`--ignore` server-provided packages** (present on the server, never bundled, never validated): `org/bukkit`, `net/minecraft`, `com/destroystokyo`, `io/papermc`, `org/spigotmc`, `io/netty`, `com/mojang`, `org/jetbrains`, `xyz/wagyourtail`, `org/intellij`, `org/jspecify`, `org/checkerframework`, `com/google/auto`, `com/google/errorprone`, `com/google/gson`, `com/google/common`, `com/viaversion`. (The Bukkit/Paper/Spigot/NMS/netty/Mojang set is generic; the compile-only-annotation and packetevents-dep set is Mental-specific.)
- For the **tester** jar, add `me/vexmc/mental/` to the ignores (the Mental jar provides those at runtime, so from the tester's perspective they're server-provided).
- **`--allow` allowlist** (`scripts/tools/jdk8-api-gate.allow`) starts EMPTY and must stay that way: a real miss is FIXED (Java-8 alternative, a jvmdg shim, or shade the runtime), not allowlisted. Format: anchored prefix keys `CLASS <owner>` / `METHOD <owner>#<name> <desc>` / `FIELD <owner>#<name> <desc>`.
- Severity: `java/ javax/ org/w3c/…` → HARD (class or member miss blocks). `jdk/ sun/ com/sun/` → class miss blocks, member miss WARNs. Scanner cannot see reflective `Class.forName` string lookups (inherent limit).

Gradle 9 note: uses `serviceOf<ExecOperations>()` captured at config time (Project.javaexec removed), and compiles the tool with `javax.tools.ToolProvider.getSystemJavaCompiler()` (requires Gradle run on a JDK, not JRE).

### verifyTesterIsolation (D-8) — GENERIC pattern (multi-plugin jvmdg safety)
Two failure modes, both fatal on the server's shared legacy class cache, both reproduced live:
- **(a)** The tester mega jar must NOT carry Mental's `me/vexmc/mental/lib/jvmdg` prefix (two plugins sharing a same-FQN pruned runtime cross-link and fail). Scans every tester `.class` for that token.
- **(b)** Neither mega jar may reference an **un-relocated jvmdg STUB type in a method descriptor**. jvmdg rewrites a post-8 type (e.g. `java.util.random.RandomGenerator`) to a stub under `xyz/wagyourtail/jvmdg/*/stub/`, and ShadeJar relocates it — but the relocator only rewrites CLASS constant-pool entries and invoke OWNERS, NOT a stub type that appears *only* as a parameter Utf8 in a cross-plugin call's descriptor. On a v52-read server that raw FQN resolves to nothing → `ClassNotFoundException` the instant the method links = a **live-server HANG, not a build failure**. (The cited live case: the 1.12.2 gate hung when a `computePaced` call passed `java.util.Random` into a `RandomGenerator` descriptor.) The gate walks each `xyz/wagyourtail/` occurrence, checks it isn't preceded by a `lib/jvmdg/` relocation prefix, and flags any `/stub/` FQN. Skips the bundled runtime prefixes `me/vexmc/mental/lib/jvmdg/` and `me/vexmc/mental/tester/lib/jvmdg/` (their internal self-refs are jvmdg's concern).

Wiring:
```kotlin
tasks.named("check") { dependsOn(verifyRelocation) }
tasks.named("check") { dependsOn(verifyDowngrade, verifyTesterIsolation, verifyJdk8Api) }
```

---

## 7. japicmp API compatibility gate (:api) — Mental-specific but reusable

`api/build.gradle.kts`. The public `:api` surface may only GROW. `apiCompat` runs japicmp comparing the freshly built `:api` jar against a committed baseline `gradle/api-baseline/api-2.2.2.jar`:

```kotlin
val japicmp: Configuration by configurations.creating { isCanBeConsumed = false; isCanBeResolved = true }
dependencies { japicmp("com.github.siom79.japicmp:japicmp:0.23.1:jar-with-dependencies") }

val apiCompat = tasks.register<JavaExec>("apiCompat") {
    dependsOn(tasks.named("jar"))
    classpath = japicmp
    mainClass.set("japicmp.JApiCmp")
    doFirst {
        val cp = configurations.named("compileClasspath").get().asPath
        args("--old", apiBaselineJar.absolutePath, "--new", newJar,
             "--old-classpath", cp, "--new-classpath", cp,   // REAL classpath, not --ignore-missing-classes
             "--only-modified", "--error-on-binary-incompatibility",
             "--html-file", reportHtml)
    }
}
tasks.named("check") { dependsOn(apiCompat) }
```

- `--error-on-binary-incompatibility` is the gate (non-zero exit fails build). Removed class / removed or re-signed method fails; additive (e.g. a new default method `apiVersion()`) passes.
- **Deliberately NOT using `--ignore-missing-classes`** — that flag's startup WARNING banner leaked into the jvmdg global-console warning capture under parallel execution and failed the 2.4.1 release (see §5). Supplying the real compile classpath (`--old-classpath`/`--new-classpath`) both suppresses the banner and is the stronger check.
- Baseline provenance (`gradle/api-baseline/README.md`): built from tag `v2.2.2` via `git worktree add … v2.2.2 && ./gradlew :api:jar`, sha256 pinned. Bump only for an intentional additive release, never "to make the gate pass."

---

## 8. plugin.yml api-version derivation & kernel purity

### api-version from support-matrix.json (generic pattern)
`plugin.yml` (`core/src/main/resources/plugin.yml`) is a template with `${version}` and `${apiVersion}`:
```yaml
name: Mental
version: '${version}'
main: me.vexmc.mental.v5.MentalPluginV5
api-version: '${apiVersion}'
folia-supported: true
```
`processResources` expands it from `floorApi` read out of `support-matrix.json` (NOT a hardcoded literal):
```kotlin
val floorApi: String = supportMatrix["floorApi"] as String   // JsonSlurper parse of support-matrix.json
tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "apiVersion" to floorApi)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
```
`floorApi` in the matrix is `"1.13"`. The **tester** does the identical expansion (it must load on every supported server; on 1.13.2 the server's `CraftMagicNumbers.checkSupported` rejects any plugin whose `api-version` isn't exactly the floor, so a stale `"1.17"` would fail the whole legacy boot).

### Kernel purity — Bukkit/PacketEvents-free asserted at build time (generic pattern)
`kernel/build.gradle.kts` — the architecture's enforcement edge. The kernel realm may never see Bukkit or PacketEvents; the build FAILS if either sneaks onto any configuration:
```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        require(!requested.group.startsWith("io.papermc"))            { "kernel must stay Bukkit-free" }
        require(!requested.group.startsWith("com.github.retrooper"))  { "kernel must stay PacketEvents-free" }
    }
}
```
The kernel is a plain `java-library` with only JUnit test deps — no `compileOnly(paper-api)`. It ships **unrelocated** inside the Mental jar (core `implementation(project(":kernel"))`).

---

## 9. Module graph & dependency wiring

`settings.gradle.kts`:
```kotlin
include(":api", ":platform", ":core", ":compat-folia", ":tester"); include("kernel")
project(":compat-folia").projectDir = file("compat/folia")
rootProject.name = "Mental"
```

- **`kernel`** — pure, Bukkit-free math/motion authority. No API deps.
- **`api`** — public surface (`me.vexmc.mental.api.*`), `compileOnly(paper-api-floor)` + annotations. Has the japicmp gate.
- **`platform`** — Bukkit-facing foundation. `api(project(":kernel"))` (re-exports kernel transitively), `compileOnly(paper-api-floor)`. Publishes a Scheduling TCK as `java-test-fixtures` (Mental-specific; consumed by compat-folia's live test).
- **`compat-folia`** (dir `compat/folia`) — `compileOnly(project(":platform"))`, `compileOnly(paper-api-folia)`. Its output classes are folded into core's shadowJar.
- **`core`** — the plugin. `api(project(":api"))`, `api(project(":platform"))`, `implementation(project(":kernel"))`, `compileOnly(paper-api-floor)`, `implementation(packetevents-spigot)`, `implementation(bstats-bukkit)`, shaded Adventure. Runs the whole mega-jar pipeline.
- **`tester`** (Mental-specific) — a second plugin jar that boots inside real servers and self-runs a suite. All Mental modules `compileOnly` (provided by the Mental jar at runtime); `implementation(reflection-remapper)`. Same mega-jar pipeline, distinct jvmdg prefix.

Compile-API artifacts (three paper-api levels, NOT runtime matrix members):
```toml
paper-floor  = "1.17.1-R0.1-SNAPSHOT"   # compile floor
paper-folia  = "1.20.4-R0.1-SNAPSHOT"   # Folia symbols
paper-modern = "1.20.6-R0.1-SNAPSHOT"   # modern symbols
```

---

## 10. support-matrix.json — the single source of truth

`support-matrix.json` is THE machine-readable matrix; **no Minecraft version or JDK literal lives anywhere else** in the build (enforced by convention). Shape:
```json
{ "floorApi": "1.13",
  "entries": [
    { "version": "1.9.4",  "jdk": 21, "platform": "paper", "suites": "full",         "ci": "pr",      "bytecodeTier": 61 },
    { "version": "1.13.2", "jdk": 13, "platform": "paper", "suites": "full",         "ci": "release", "bytecodeTier": 52 },
    { "version": "1.16.5", "jdk": 16, "platform": "paper", "suites": "full",         "ci": "release", "bytecodeTier": 52 },
    { "version": "1.17.1", "jdk": 17, "platform": "paper", "suites": "full",         "ci": "pr",      "bytecodeTier": 61 },
    { "version": "26.1.2", "jdk": 25, "platform": "folia", "suites": "combat-smoke", "ci": "release", "bytecodeTier": 61 }
    // … 1.10.2,1.11.2,1.12.2,1.14.4,1.15.2,1.18.2,1.19.4,1.20.6,1.21.4,1.21.11,26.1.2(paper)
  ]}
```

Per-entry fields and who reads them:
- **`version`** — Minecraft version to boot. `entries[0]` = floor (positional; version-sorted ascending), `entries[-1]` = ceiling.
- **`jdk`** — RUNTIME JDK for that server (the *newest clean flagless* rung for legacy). Used by RunServer `javaLauncher` / the shell matrix / CI. Build JDK is `max(jdk)` = 25.
- **`platform`** — `paper` | `folia`.
- **`suites`** — `full` | `boot` | `combat-smoke`; passed to the tester as `-Dmental.tester.suites`.
- **`ci`** — `pr` (runs on PRs) | `release` (release only). Consumed by both GitHub workflows via `jq`.
- **`bytecodeTier`** — **the MR tier that entry's loader × JVM ACTUALLY reads** (52 or 61), passed as `-Dmental.tester.tier` so the tester **asserts the loaded tree live** — which tree loaded is a measured per-version fact, not an assumption. Rule: the jar has exactly base v52 + versions/17 (major 61); a JVM reads versions/17 only when its feature version ≥ 17 AND the plugin loader honors MR. So 1.9.4–1.12.2 (no Java cap, run on Java 21) reach versions/17 → 61; 1.13.2/1.14.4 (Java 13), 1.15.2 (14), 1.16.5 (16) are capped below 17 → read base v52; 1.17.1+ → 61.

Consumers: `core/build.gradle.kts` (task registration + api-version), `tester/build.gradle.kts` (api-version), `scripts/integration-matrix.sh` (via `jq`), `.github/workflows/{build,release}.yml`. `floorApi` = plugin.yml `api-version`.

---

## 11. Live integration matrix (Mental-specific harness, generic idea)

`core/build.gradle.kts` `registerIntegrationServer(...)` registers, per matrix entry, a run-paper `RunServer` task + a paired check task. Salient reusable mechanisms:

- Boots real Paper/Folia (`minecraftVersion(version)`; for Folia sets `downloadsApiService.set(DownloadsAPIService.folia(project))` BEFORE the version), installs the **shipped mega jars** (`pluginJars.from(megaJar…)`, `pluginJars.from(testerMegaJar…)`), runs the tester in-process.
- **Freshness nonce per Gradle invocation** (`UUID.randomUUID()`): stamped into boot as `-Dmental.tester.nonce`, echoed by the tester into `test-results.txt` as `PASS nonce=<n>`; the check accepts ONLY this nonce, so a leftover result from a prior boot structurally fails (can't masquerade as PASS).
- Per-entry JDK: `javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) })`. **No Java-version-guard bypass flag is passed** — every legacy build runs its newest clean flagless JVM and the v52 base tree loads natively (this is the whole point of the mega jar: it kills the old `-DIgnoreJavaVersion`/outdated-Java story).
- `jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", …, "-Dmental.tester.tier=$bytecodeTier")` — watchdog disabled so a slow-CI tick stall doesn't hang legacy servers.
- **D-9 honesty log scan:** Bukkit swallows listener-registration failures and per-event handler throws into the console and keeps running, so a `PASS` verdict alone can hide them. The check greps the server log for `has failed to register events for class me\.vexmc\.mental\.` / `Could not pass event .* to Mental`, and for `NoSuchFieldError|NoSuchMethodError|NoClassDefFoundError` linkage errors whose following `at` frames name `me.vexmc.mental` — any hit downgrades PASS to FAIL. `scripts/integration-matrix.sh` mirrors this exact scan (concurrent local gate; sequential Gradle tasks are canonical for CI).
- `runServer` (the default run-paper task) is **disabled** (`enabled = false`) — only `integrationTest`/`integrationTestMatrix`/`integrationTestFolia` are used. `integrationTest` = PR smoke on modern-floor (first non-boot entry) + ceiling; `integrationTestMatrix` = every paper + folia entry (chained sequentially, `mustRunAfter`, so they never double-bind the port).

---

## 12. Reproduction checklist for a NEW project (generic core only)

1. **Wrapper Gradle 9.5.1**; `settings.gradle.kts` adds foojay-resolver 1.0.0 + PaperMC repo.
2. **Version catalog** with `com.gradleup.shadow:9.4.2`, `xyz.wagyourtail.jvmdowngrader:1.3.6`, `xyz.jpenilla.run-paper:3.0.2`.
3. **Root build**: `toolchain = JavaLanguageVersion.of(25)` (build JDK), `options.release = 17` (compile floor → v61), `-parameters`, UTF-8. Version from `gradle.properties`.
4. **shadowJar**: relocate every third-party pkg under `<yourgroup>/lib/…`; set `archiveClassifier="modern"`, `destinationDirectory=build/jvmdg-stage`. Do NOT `minimize()`.
5. **DowngradeJar** (`jvmdg.defaultTask`): `inputFile=shadowJar`, `downgradeTo=VERSION_1_8`, `multiReleaseOriginal=true` (NEVER also set `multiReleaseVersions` in 1.3.6 — mutually exclusive, drops v61), `classpath = union of all compile classpaths whose supertypes appear in the shaded jar`, stage as `-downgraded`.
6. **ShadeJar** (`jvmdg.defaultShadeTask`): `inputFile=downgradeJar`, `shadePath.set { "<yourgroup>/lib/jvmdg/" }` (distinct per plugin), `destinationDirectory=build/libs`, `archiveClassifier=""`. `build.dependsOn(this)`.
7. **`failOnJvmdgWarnings`** helper on both jvmdg tasks; `mustRunAfter` any parallel task that prints banners; filter JDK-24+ Unsafe noise.
8. **Gates into `check`**: verifyDowngrade (MR tier shape: base ≤ v52, versions/17 == v61 subset, sentinel forked), verifyJdk8Api (ASM tool vs real JDK 8, empty allowlist), verifyRelocation (no un-relocated tokens), verifyTesterIsolation if you have a second downgraded plugin.
9. **plugin.yml**: template `${version}`/`${apiVersion}`, expand in `processResources` from a JSON matrix's `floorApi`.
10. **Matrix JSON** as single source of truth; derive build JDK as `max(jdk)`, CI lanes from `ci`, live-tier assertion from `bytecodeTier`.

**Mental-specific to drop when reusing:** PacketEvents + bStats + Adventure relocations and their `--ignore` entries, the `compat-folia` fold-in, the whole `tester` module + integration harness + D-9 scan, the japicmp `:api` gate (unless you also keep a stable public API), and the sentinel class name `me/vexmc/mental/v5/MentalPluginV5`.
