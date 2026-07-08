# Copilot review guide — FableFactions

FableFactions is a clean-room factions plugin: one Multi-Release mega-jar spanning
PaperSpigot 1.7.10 → Paper/Folia 26.1.2, built on a **deterministic kernel**.
**Before reviewing, consult `docs/ARCHITECTURE.md` and `docs/CONTRACTS.md` — they are
NORMATIVE.** A change that contradicts them must change the doc in the same PR.

## Module graph (strict, one direction)

`:kernel ← :api ← :platform ← :core ← :compat-{folia,modern}`

- `:kernel` — pure JDK. State records, COW structures, Intent/Effect vocab, Reducer, rules.
- `:api` / `:platform` — public surface + the Bukkit seam (compileOnly Paper 1.13.2).
- `:core` — the plugin: writer, journal, storage projector, listeners, commands, GUI.
- `:compat-*` — era leaf classes, loaded by FQN string only behind passing probes.

## Iron rules (the build enforces these — flag any violation)

1. **`:kernel` is pure JDK.** No Bukkit / Adventure / JDBC reference anywhere in kernel.
2. **Exactly one writer.** All state mutation flows through `IntentBus.submit`/`submitSystem`
   → the `fable-kernel` writer → the pure `Reducer`. Listeners/commands do snapshot reads +
   intent submits only — never JDBC, journal writes, or kernel-state construction.
3. **No streams / iterators / varargs / boxing on hot paths** (event handlers, `Verdicts`,
   atlas/ledger probes); deliberate writer-side allocation needs a justification comment.
4. **No `byte` or `short` record components, ever** — jvmdg's record-toString downgrade emits
   descriptors absent from JDK 8 (`NoSuchMethodError`). Use `int`; `byte[]`/`short[]` are fine.
5. **Java-8-stdlib-only despite Java-17 source.** Language 17 (records/sealed/switch patterns
   OK) but the jar is jvmdg-downgraded to Java 8: no Java 18+ APIs, stick to long-stable
   `java.util` / `java.util.concurrent` / `java.nio`. `verifyJdk8Api` fails exotic stdlib usage.
6. **Pinned deps must not move** (AM-10): HikariCP 4.0.3, H2 1.4.200, mysql 8.0.33, adventure 4.x,
   slf4j-nop 1.7.x, junit 5.x, jvmdowngrader 1.3.6. All `net.kyori`/storage libs are relocated.

Every class javadoc must state owning thread(s) and mutability class (immutable / confined / COW).
