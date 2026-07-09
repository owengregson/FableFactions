# Dependency modernization — what shipped and why

**Status: DONE (v1 beta.3).** Wave 5 asked whether the mega-jar could run newer dependencies
across the whole 1.7.10→26.x range. The original question — *bundle a different version per
Minecraft/Java version* — is unsound (see "Why naïve Multi-Release override does NOT work"
below: MR overrides can't version-swap a library's class graph). But the project owner's
reframing works and is now **implemented**: ship a **single, newest** version of each dependency
and let the same jvmdg pipeline that downgrades our own bytecode downgrade *it* too — plus a
`build-high` variant-selection tweak for deps that publish a high `org.gradle.jvm.version`.

## Shipped dependency set (beta.3)

| Dependency | was | now | how |
|---|---|---|---|
| Connection pool | HikariCP 4.0.3 (pinned) | **Apache Commons DBCP2 2.14.0** (latest) | migrated (`StorageBoot`): DBCP2's newest release is Java-8-native, so it drops into the v52 base tier with no jvmdg tricks — **removes the HikariCP pin** |
| Logging binder | slf4j-nop 1.7.30 (pinned) | **gone** | DBCP2 logs via commons-logging (bundled) — **removes the slf4j pin** |
| MySQL driver | 8.0.33 | **9.7.0** (latest) | consumed as-is; the optional OpenTelemetry handler is `<clinit>`-guarded and ignored |
| Embedded DB | H2 1.4.200 (pinned) | **H2 2.2.224** | the latest **Java-8-native** H2 line (2.3.x needs Java 11 and jvmdg can't resolve its excluded `tools/Server` on downgrade). Our schema + MODE=MySQL clears 2.x's reserved words with no `NON_KEYWORDS`. Beta: no on-disk migration from the 1.4.200 format — a fresh DB is created |
| bStats | 3.2.1 | 3.2.1 | already latest |
| Text | Adventure 4.26.1 | **held at 4.26.1** (latest 4.x) | see below |

First-party v52/v57/v61 tiers are untouched (`options.release` stays 17), and the jar stays a clean
base/v13/v17 Multi-Release with no extra tier. Full build + all 7 gates + tests green (the larger
dep set needed the daemon heap raised to 6g so jvmdg's shade stays under its internal timeout).

### Held: Adventure 5.x (deferred, not rejected)

Adventure 5.2.0 IS consumable via build-high (jvm.version=21 → jvmdg downgrade) with **zero
`.java` change** — proven green locally. But 5.x pulls in many more modern-JDK APIs
(`StringConcatFactory`, `HexFormat`, `RandomGenerator`, `MatchException`, `Locale`…), and jvmdg's
shim-stub set for them composes **non-deterministically** with the `versions/13` + `versions/21`
tiers: it passed locally but `verifyJdk8Api` flagged ~17 missing base-tree jvmdg stubs in CI (a
different environment/ordering surfaced the gap). The `mergeTiers` dedup even reported repeated
`versions/13` stub entries. Root cause is the three-way tier composition (base v52 / v57 versions-13
/ v65 versions-21) not producing a complete, deterministic jvmdg-stub closure. Fixing it needs a
deterministic multi-tier stub-merge (compute the full stub closure per tier and inject it
explicitly), which is its own build-surgery task. Until then Adventure stays on the latest 4.x line
(Java-8-native, base tier) — the other four dependencies modernized without it.

---

## Appendix — why the *per-version bundling* framing was rejected

---

## What we ship today

The mega-jar is a Multi-Release JAR whose **first-party** classes are tiered (base v52 Java 8,
`versions/13` v57, `versions/17` v61 — wave 5). The **shaded dependencies** are single-version,
relocated once under `dev.fablemc.factions.lib.*`, and pinned to the last Java-8-compatible line
(AM-10): HikariCP 4.0.3, H2 1.4.200, mysql-connector-j 8.0.33, Adventure 4.x, slf4j 1.7.x. Those
artifacts are already Java-8 bytecode, so jvmdg leaves them unchanged and they run as their
original bytecode on **every** tier — already optimal *for those versions*.

The open question is only whether MODERN servers (Paper 1.20+/JDK 17+) could instead run a
**newer** dependency line (HikariCP 5/6, H2 2.x, Adventure 5) for marginal perf/feature gains.

## Why naïve Multi-Release override does NOT work

A Multi-Release JAR resolves overrides **per class**: a `META-INF/versions/17/<class>` shadows the
base `<class>` on a 17+ JVM; where no versioned copy exists, the base class is used. That is built
for **small shims** (one class reaching a newer API), not for swapping a whole library.

Putting HikariCP 4.0.3 in the base and HikariCP 6.x under `versions/17` — both relocated to the
*same* `…lib.hikari` package — fails because the two versions have **different internal class
graphs**:

1. A 17+ JVM would load `versions/17` Hikari classes where present and **fall back to base
   (4.0.3) classes where 6.x removed/renamed them** → a Frankenstein library whose classes
   reference each other inconsistently → `NoSuchMethodError`/`NoClassDefFoundError` at runtime.
2. First-party code is **version-blind at the call site** (one relocated package). It compiles
   against one API; the *other* tier's library may not honor that API. The base-tier (v52)
   first-party call would break against whichever Hikari surface differs.
3. `verifyJdk8Api` requires the entire base tree to resolve in Java 8. That already forces the
   base to carry the Java-8 line — so the base can *never* be the modern library.

Conclusion: **MR overrides cannot safely version-swap a library with a divergent class graph.**

## The one mechanism that WOULD be sound (post-v1 option)

Bundle **both full libraries under DISTINCT relocation packages** and **tier the (small) first-party
call site**, so no class-graph mixing ever happens:

- relocate 4.0.3 → `…lib.hikari4` (base tree, Java 8) and 6.x → `…lib.hikari6` (`versions/17`, Java 17);
- write the pool bootstrap against a tiny internal `Pool` interface with two implementations, each
  compiled into its own tier (or a base impl + a `versions/17` override that targets `hikari6`);
- the loader picks the impl the running JVM resolves.

This is coherent because each tier references a **complete, self-consistent** library. Costs:
(a) jar size grows by the full second copy of each doubled library; (b) every first-party class that
touches the library must be maintained as a tiered pair; (c) build complexity (a second relocation +
tiered compile per doubled lib); (d) the test matrix doubles for that subsystem.

An orthogonal modern-only path exists too: **Paper's runtime library loader** (`PluginLoader`,
Paper 1.19.3+) can resolve newer deps into a child classloader on modern servers, with the shaded
Java-8 stack as the legacy fallback — but it only covers ≥1.19.3 and still needs the first-party
code to abstract over both APIs, i.e. the same (b) cost.

## Why it is NOT worth it for v1

- The pinned stack is **mature and fast**: HikariCP 4.0.3 is an excellent, stable pool; H2 1.4.200
  is fine for the embedded default; Adventure 4.x is the actively-maintained line. For a factions
  plugin's embedded H2 / modest MySQL pool and legacy-string text pipeline, the modern-line delta
  is negligible.
- The complexity/correctness **risk is high** (tiered libraries, doubled test surface) against a
  **marginal** benefit.
- It cuts against the project's one-jar thesis and AM-10's deliberate Java-8-line pin, which exists
  precisely so one artifact is correct everywhere.

**What we DO get per-version already:** the first-party hot paths (verdicts, ledger/atlas probes,
reducer) ship as v61 bytecode on modern JVMs via the `versions/17` tier and v57 on the 1.13–1.16
band via `versions/13` — that is where per-version bytecode actually moves the needle, and it is
done. The dependencies are a mature constant underneath.

**Revisit trigger:** a measured, modern-only dependency win large enough to justify a tiered library
(e.g. a materially faster embedded DB engine that requires JDK 17). At that point implement the
distinct-package + tiered-call-site mechanism above for that one library only — never a blanket swap.
