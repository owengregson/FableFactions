# Contributing to FableFactions

Thanks for your interest in contributing. FableFactions is an unusual codebase — a single
Multi-Release jar that runs on PaperSpigot 1.7.10 through Paper 26.1.2 and Folia, built
around a deterministic kernel with one writer thread. That design only survives if every
change respects a small set of hard rules. This guide tells you what they are, how the
build enforces them, and how to get a PR merged.

## Dev setup

```bash
git clone https://github.com/owengregson/FableFactions.git
cd FableFactions
./gradlew build
```

That is the whole setup. The build uses Gradle 9.5.1 (wrapper included) with JDK
toolchains auto-provisioned via the foojay resolver — the build JDK (25) and the JDK 8
used by the `verifyJdk8Api` gate are downloaded automatically if not already installed.
You only need **any JDK 17+ on your PATH to launch Gradle itself**; no other manual JDK
install is required.

`./gradlew build` produces the canonical artifact at
`core/build/libs/FableFactions-<version>.jar` and runs every verification gate and test
suite (see [Verification gates](#verification-gates)).

Note: the build runs with the parallel executor, the build cache, and the configuration
cache enabled (`gradle.properties`). The jvmdg warning-capture listens on Gradle's
*global* console stream, so every jvmdg task is serialized behind a shared
`jvmdgConsoleLock` build service and fenced after the test/test-compile tasks — if you
add a task that writes warnings to the console while jars are being downgraded, add it
to the fence list in `core/build.gradle.kts`/`probe/build.gradle.kts` rather than
turning the parallel executor off.

### IDE

Import the repository as a **Gradle project** (IntelliJ IDEA: *Open* the root directory;
it picks up `settings.gradle.kts`. Eclipse: import via Buildship's *Existing Gradle
Project*). No IDE metadata is checked in (generated `.project`/`.classpath`/`.idea` files
are gitignored) and no IDE-specific run configuration is required — the build and all
gates run through the Gradle wrapper.

## Repo tour

Seven Gradle modules with a **strict dependency direction**
(`kernel ← api ← platform ← core ← compat-*`; `:probe` stands alone):

| Module | Purpose |
|---|---|
| `:kernel` | Pure JDK. State records, COW structures, Intent/Effect vocabulary, the Reducer, rules, math, `MessageKey`, `ConfigImage`. Build-fails on any Bukkit/Adventure/JDBC dependency. |
| `:api` | Public surface (`dev.fablemc.factions.api`): `FableFactionsApi`, `FactionsView`, request builders, 7 Bukkit events, `PlaceholderSource`. Compiles against Paper 1.13.2. |
| `:platform` | The version seam: `Scheduling`, `Capabilities`, `PlatformProfile`, the resolvers (`Players`, `LegacyMaterials`, `Views`, `Feedback`, `Hands`, `Nametags`, `ItemCodec`, `Constants`), `TextPort`, `Scope`, `MenuModel`. |
| `:core` | The plugin: boot, `IntentBus`/writer, `EffectJournal`, `StorageProjector`, SQL dialects, listeners, command trees, GUI, chest/teleport/session engines, config parser, `MessageCatalog`, integrations, bStats, update checker. |
| `:compat-folia` | `FoliaScheduling` only. Compiles against Paper 1.20.4; loaded by FQN string behind a probe. Lives at `compat/folia`. |
| `:compat-modern` | `ModernItemCodec` (`serializeAsBytes`), `BrigadierInstaller`, async-chunk helper. Compiles against Paper 1.20.6; FQN-loaded behind probes. Lives at `compat/modern`. |
| `:probe` | Self-test plugin for the live server matrix (its own jvmdg runtime prefix, isolated from the main jar). |

Lower modules never see higher ones: `:kernel` cannot reference Bukkit at all, `:api` and
`:platform` compile only against the 1.13.2 floor API, and only the compat modules may
touch modern Paper/Folia symbols. `support-matrix.json` at the root is the single
machine-readable source of truth for supported versions, runtime JDKs, and CI lanes — no
Minecraft version or JDK literal lives anywhere else in the build.

## Required reading

For anything beyond a typo fix, read these **in order** before writing code:

1. **`docs/ARCHITECTURE.md`** — the normative design: the deterministic-kernel spine and
   the seventeen normative amendments (AM-1 … AM-17). If your change contradicts an
   amendment, the change is wrong.
2. **`docs/CONTRACTS.md`** — the normative implementation contract: module/package/file
   tree, every cross-boundary type signature, conventions, and the addenda learned during
   implementation (§7).
3. **`docs/research/ref-*.md`** — the behavior parity specs. Every command, permission,
   config key, message key, GUI menu, integration behavior, and placeholder is specified
   there. Behavioral changes must cite these or explicitly declare a deviation.

## The iron rules

Distilled from `docs/CONTRACTS.md` — the build or review will reject violations.

1. **The kernel is pure JDK.** No Bukkit, no Adventure, no JDBC anywhere in `:kernel` —
   enforced by the module classpath, ArchUnit tests, and the `verifyKernelPurity`
   bytecode gate over the final jar. Kernel effects carry `MessageKey` + args, never text.
2. **All mutations flow through intents to the single writer.** Domain state is one
   immutable snapshot; the only way to change it is `IntentBus.submit` → the
   `fable-kernel` writer thread → the pure `Reducer`. Never construct or mutate kernel
   state anywhere else.
3. **Listeners and command bodies do snapshot reads + intent submits only.** Per
   CONTRACTS §4: listener bodies and command `perform` run on the calling region/main
   thread — snapshot reads, `IntentBus.submit`, and `Messages` only; **no JDBC, no
   journal, no kernel-state construction** on event threads. The storage package runs
   only on the `fable-storage` thread.
4. **No streams, iterators, varargs, or boxing on hot paths** (event handlers,
   `Verdicts`, atlas/ledger probes). Protection checks must stay wait-free and
   allocation-free. Any deliberate writer-side allocation needs a justification comment.
5. **No `byte` or `short` record components, ever** (CONTRACTS §7.1). jvmdg 1.3.6's
   record-toString downgrade emits `StringBuilder.append(B)`/`(S)` descriptors that exist
   in no JDK → `NoSuchMethodError` on the Java 8 base tier. Use `int` components;
   `byte[]`/`short[]` fields and byte-returning methods are fine.
6. **Java 17 language, long-stable stdlib only.** Records, sealed types, and switch
   patterns are fine; the jar is jvmdg-downgraded to Java 8, so no Java 18+ APIs and no
   exotic stdlib — stick to long-stable `java.util` / `java.util.concurrent` / `java.nio`
   on all paths, and prefer Java-8-era methods when in doubt. `verifyJdk8Api` enforces
   this against a real JDK 8 with an **empty allowlist**.
7. **House style** (CONTRACTS §7.8, established by the idiom sweep):
   - *Guard idiom* — reducers use `ReduceSupport.rejectIf(...)` / `factionOrReject(...)`
     single-expression guards; command/listener layers use the same one-shape
     early-return guards.
   - *Emission funnel* — reducers never touch the effect list directly; everything goes
     through `ReduceSupport.emit(...)` or its named wrappers.
   - *Enums carry their constants* — an enum mapping to a `MessageKey`/codec/label
     interns it once in a final field; DB/wire labels are explicit fields, never
     `name()`/`ordinal()`.
   - *Switch discipline* — exhaustive switch expressions on enums/sealed types with no
     `default`, so the compiler proves totality.
   - *Javadoc voice* — the first line of every class is one present-tense role sentence
     ("Owns…", "Publishes…", "The only…") plus owning-thread and mutability tags.
   - *Imports* — java → javax → third-party → dev.fablemc, alphabetical, no wildcards,
     no inline FQNs for imported types.
   - *Named constants over magic literals.*
   - UTF-8, LF, 4-space indent, ≤120 columns. No Lombok.

## Verification gates

`./gradlew build` runs all of these (wired into `check`). Each reads the **final** mega
jar(s) or compiled classes; descriptions match `core/build.gradle.kts`.

| Gate | What it protects | A failure means |
|---|---|---|
| `verifyKernelPurity` | No `dev/fablemc/factions/kernel/` class in the mega jar references `org/bukkit` — the bytecode backstop for the kernel firewall. | You leaked a Bukkit type into `:kernel`. Move the code to `:platform`/`:core`, or pass the data through the Intent/Effect vocabulary. |
| `verifyJdk8Api` | Every reference in both mega jars' base (v52) tree resolves against a real JDK-8 `rt.jar`, in-jar, or a server-provided package. Empty allowlist. | You used a post-Java-8 stdlib API (or added a dependency that does). Replace with a Java-8-era equivalent. |
| `verifyDowngrade` | The Multi-Release tier shape: base ≤ v52, `versions/17` first-party classes are v61 and a subset of base, the boot sentinel is forked 52/61, and no first-party base class reflectively introspects records. | The downgrade pipeline produced a malformed jar — usually reflective record introspection (`Class.isRecord` / `RecordComponent`) or a language feature jvmdg couldn't lower cleanly. |
| `verifyRelocation` | No un-relocated `net/kyori`, `com/zaxxer`, `org/h2`, `com/mysql`, or `org/slf4j` token survives outside `dev/fablemc/factions/lib/`. | You referenced a third-party class in a way that escaped relocation, or added a shaded dependency without a relocation rule. |
| `verifyProbeIsolation` | The probe jar carries no FableFactions jvmdg runtime prefix, and neither jar references an un-relocated jvmdg stub type in a descriptor (which would hang a live v52 server, not fail the build). | The jvmdg shading contract was broken — almost always a build-script change; revert it or get a work order. |
| `verifyDescriptorFloor` | No baseline (non-`@ProbeGated`) `Listener` mentions a post-1.7.10 Bukkit type in any method/field descriptor (AM-13). | Your listener would be silently swallowed at registration on old servers. Move the post-floor-typed handler into its own `@ProbeGated` listener registered behind a capability. |
| `verifyNoStickyGetstatic` | No `GETSTATIC` of a Bukkit enum constant absent at the 1.7.10 floor (AM-13; sticky `NoSuchFieldError`). | Resolve the constant once at boot via the `Constants` resolver / `Enum.valueOf`, never as a direct field reference. |

Alongside the gates, `build` runs the unit, jqwik property, and ArchUnit suites across
all modules. Gate wiring, relocations, and ignore lists in `core/build.gradle.kts` are
final — do not touch them without prior discussion (CONTRACTS §7.6).

## Testing

- `./gradlew build` — everything: compilation, all gates, all unit/property/arch tests.
- `./gradlew :kernel:test` — the kernel property and rule tests in isolation (fast loop
  while working on reducer/rules code).
- Live server matrix (boots real servers via run-paper; not wired into `check`):
  - `./gradlew integrationTest` — PR smoke: the modern floor + ceiling entries.
  - `./gradlew integrationTestMatrix` — all 18 entries in `support-matrix.json`
    (Paper 1.7.10 → 26.1.2 + Folia).
  - `./gradlew runIntegrationTest_<version>` / `runIntegrationTestFolia_<version>` — one
    lane, dots replaced by underscores (e.g. `runIntegrationTest_1_20_6`,
    `runIntegrationTestFolia_26_1_2`).

Expectations:

- **Behavior changes need tests.** No exceptions.
- **Kernel rule changes need jqwik property tests**, not just examples — the existing
  suites (relation symmetry, escrow conservation, cache==recompute, page bounds) show
  the shape.
- If your change is version-sensitive (probes, resolvers, listeners), run at least the
  relevant live lane before opening the PR.

## Dependency policy

- All dependencies are pinned in `gradle/libs.versions.toml` (the version catalog).
- The storage stack is **Java-8-line pinned and must never be bumped** (AM-10):
  HikariCP `4.0.3` (last Java-8 line), H2 `1.4.200`, mysql-connector-j `8.0.33`.
  Newer lines require Java 11+ and would break the base tier. A PR bumping any of these
  will be closed.
- **No new dependencies without prior discussion** (open an issue or Discussion first).
  Every shaded dependency needs a relocation rule and must pass `verifyJdk8Api` with the
  allowlist still empty — that bar is high on purpose.

## PR process

1. **Branch from `main`.**
2. **Conventional commits**, matching the existing log: `feat:`, `fix:`, `refactor:`,
   `style:`, `docs:`, `test:`, `build:` (with `!` for breaking changes, e.g.
   `refactor!:`).
3. **Keep PRs small and focused** — one concern per PR. Large mechanical sweeps go in
   their own PR, separate from behavior changes.
4. **Behavioral changes need a parity citation**: point to the `docs/research/ref-*.md`
   section that specifies the behavior, or include an explicit deviation note in the PR
   description explaining why FableFactions intentionally differs.
5. **CI must be green** — run `./gradlew build` locally before pushing; it is the same
   gate set CI runs.
6. Fill in the PR template, including the thread-rules question (which thread does your
   new code run on, and why is that correct?).

One project-wide rule worth repeating from CONTRACTS §7.7: FableFactions is a standalone
project. Reference-implementation names may not appear anywhere in the repo; parity
research refers only to "the reference implementation".
