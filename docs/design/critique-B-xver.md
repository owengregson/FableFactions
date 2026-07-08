# Cross-Version Hostile Critique — Proposal B (Frozen-Seam)

Reviewer stance: shipped one-jar plugins on every rung 1.7.10 → 26.1.2 + Folia. I attacked
`proposal-B.md` against `version-deltas.md`, `mental-build.md`, `mental-seam.md`. Verdict up
top: the *architecture* is right and matches the proven Mental model, but three concrete
things detonate as written and several gate/probe holes let cross-version failures ride a
green build. Section references are to proposal-B.

---

## FATAL

### F1. The signature gate does not check method/field *existence* — only descriptor *types*. "Compiler-grade safety against the 1.7.10 floor" (novelty #2) is provably overstated.

§3.5 / §11 / §15.2 make `verifyDescriptorFloor` + `verifyNoStickyGetstatic` the "load-bearing
new invention." Read what they actually scan (lines 311–322):

- `verifyDescriptorFloor`: fails if any field/method **descriptor** references a Bukkit/
  spigot/md_5 **type** absent from the 1.7.10 table.
- `verifyNoStickyGetstatic`: fails on `GETSTATIC` of a Bukkit **enum constant** absent from
  the table.

Neither gate inspects the *member* referenced by an `INVOKEVIRTUAL/INVOKEINTERFACE/
INVOKESTATIC` or a non-enum `GETFIELD/PUTFIELD`. A method whose **owner and parameter/return
types all exist at 1.7.10 but whose *method* does not** sails through both gates and
`NoSuchMethodError`s at runtime. A real javac compiling against a 1.7.10 API — the thing the
proposal claims to re-create — *would* reject these. The gate does not.

Concrete undetected detonations (all compile fine at the 1.13 floor):

| Call | Owner/return types at 1.7.10 | Method at 1.7.10 | Detonates on |
|---|---|---|---|
| `BlockPistonRetractEvent.getBlocks()` | present (event + `List`) | **absent** (added 1.8) | 1.7.10 — fires on every piston retract |
| `PlayerInteractEvent.getHand()` | present (event + `EquipmentSlot`) | **absent** (1.9) | 1.7.10 / 1.8 — every interact (this is *the* off-hand double-fire dedup call factions use) |
| `Material.matchMaterial(String, boolean)` | present (`Material`, `String`) | **absent** (1.13) | 1.9.4–1.12.2 (which read the v61 tier but still on legacy Bukkit API) |
| `Team.setColor(ChatColor)` if ever called unguarded | present | absent (~1.12) | 1.7–1.11 |
| `World.getMinHeight()` if ever called unguarded | present | absent (~1.17) | 1.7–1.16 |

The proposal *does* probe the specific ones it thought of (`pistonRetractBlocks`, `minHeight`,
`teamSetColor`) — but that is exactly the point: the safety is **convention + boot probes +
the runtime D-9 scan**, not the build gate. The gate is sold as the structural guarantee that
"the listener-silently-registers-zero-handlers class is a *build* failure." For descriptor
*types* and enum getstatic, yes. For sub-floor *method invocations* — the more common
cross-version footgun in a listener-heavy plugin — no. The 1.7.10/1.8 lanes catch it only if
the specific vector happens to fire during the tester suite and trips the console scan.

**Fix:** the floor symbol table is already "classes **+ members**" (§3.5). Extend both gates
to resolve every `INVOKE*` `owner#name+desc` and every `GET/PUTFIELD` `owner#name+desc`
against the member table, not just the descriptor's type list. Only then does the claim in
§15.2 hold.

### F2. The scheduler resolver loads the Folia backend on *every* non-Folia Paper ≥ 1.20.6.

Line 259, verbatim:

```java
if (!caps.folia() && !caps.foliaSchedulers()) return new BukkitSchedulePort(plugin);
```

`foliaSchedulers` (line 173) = `Class.forName("io.papermc.paper.threadedregions.scheduler.
EntityScheduler")`. Per `version-deltas` §3.10 and `mental-seam` (`modernSchedulers = folia ||
classPresent(EntityScheduler)`), that class **ships on non-Folia Paper from ~1.20** (it
delegates to the main thread). So on ordinary Paper 1.20.6 / 1.21.11 / 26.1.2(paper):
`folia=false`, `foliaSchedulers=true` → `(!false && !true)` = **false** → `BukkitSchedulePort`
is skipped and `FoliaSchedulePort` is loaded.

This directly contradicts §4.5 ("`BukkitSchedulePort` is never touched on Folia" — implying it
*is* the Paper backend), the boot-report example (`scheduling=bukkit`), and the whole
"one loaded impl, monomorphic" story (the `describe()` boot line will read `folia` on a plain
Paper box). Consequences on 1.20.6 / 1.21.11 / 26.1.2 paper lanes:

- `runGlobal` now routes through `GlobalRegionScheduler.run(...)`, which on non-Folia Paper
  runs **next tick**, not inline — the §4.2 "zero hop on Paper" `ensureOn` optimisation is
  silently gone, and any code assuming synchronous same-tick global execution changes timing.
- If `FoliaSchedulePort.isOwnedByCurrentRegion` / retired handling touch a **Folia-only**
  ownership API (present only when `RegionizedServer` exists), that method is **absent on
  non-Folia Paper 1.20+** → `NoSuchMethodError`/`NoSuchMethodError` at first schedule → the
  scheduler seam hard-fails on the most common modern deployment. The catch block even
  mislabels it: `"Folia detected but backend failed to load"` on a server that is not Folia.

The matrix would catch this (the 1.20.6/1.21.11/26.1.2 paper lanes boot the real thing), so it
is "only" a design bug — but it is a shown-code bug that mis-selects the backend for the
entire modern-Paper population.

**Fix:** gate on `caps.folia()` alone (`if (!caps.folia()) return new BukkitSchedulePort`).
`foliaSchedulers` is a red herring in this condition; it belongs (if anywhere) in a boot
assertion that *Folia* implies the scheduler API is present, not in backend selection.

### F3. The default storage stack cannot run on the Java-8 base tier that hosts 1.7.10 / 1.8.8.

§6.3 pins the H2 URL to `…;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE` (line 598).
`NON_KEYWORDS` is an **H2 2.x-only** connection setting — 2.x requires **Java 11**. §11 adds
**HikariCP** to the shade set (line 806); HikariCP **5.x requires Java 11** (4.0.3 is the last
Java-8 line). But the matrix runs 1.7.10 and 1.8.8 on **Java 8**, reading the **v52 base tree**
(support-matrix, lines 65–66), and the *default* storage is H2 (`storage=h2` boot line).

So on the two floor lanes, the plugin must run an H2-2.x engine and HikariCP-5.x — both
downgraded by jvmdg to v52 — on a real Java-8 JVM. Two outcomes, both bad:

1. `verifyJdk8Api` (empty-allowlist policy, scans the base tree *including bundled libs*,
   §11/mental-build §6) hits the un-shimmable Java-9+ APIs those engines call → **build fails**
   until the libs are downgraded or allowlisted (the latter is forbidden by policy). H2 2.x /
   Hikari 5.x are not going to pass a real JDK-8 `rt.jar` resolution.
2. If any slip the static net (reflective `Class.forName` lookups the ASM scanner can't see —
   H2 and Hikari both do service/driver reflection), you get `NoSuchMethodError` /
   `UnsupportedClassVersionError` / `NoClassDefFoundError` the first time storage opens on the
   1.7.10 / 1.8.8 lanes — i.e., the plugin's *default* config does not boot on its *own
   declared floor*.

Bonus hazard: H2 2.x ships as a **Multi-Release JAR** (`META-INF/versions/9`, …). Shaded in,
those tiers merge into the mega-jar and `verifyDowngrade` (base + `versions/17` only; "any
other `META-INF/versions/*` tier is unexpected and fails") **fails the build** unless the tiers
are stripped — which the proposal never mentions.

**Fix:** pin **H2 1.4.200** (last Java-8 line; drop `NON_KEYWORDS`, quote `VALUE` or set
`NON_KEYWORDS` only when a runtime probe says the H2 version supports it), **HikariCP 4.0.3**,
**Connector/J 8.0.x**; strip bundled `META-INF/versions/*` from the storage jars before
shade; and add the storage stack to the things `verifyJdk8Api` proves on the *real* JDK 8, not
just assume it. The proposal's Java-8-floor promise and its storage-stack choice are currently
contradictory.

---

## MAJOR

### M1. The descriptor-floor gate excludes `:api` and `:ports`, yet API event classes load and fire on 1.7.10.

§1 rule 5 and §3.5 (line 314) scope the gate to `:core` + `:platform` **output only**. But the
custom Bukkit events in `:api` (`FactionChunkClaimEvent`, `FactionBankTransactionEvent`, …,
§10.1) are compiled against the 1.13.2 API and are **constructed + `callEvent`-fired on every
claim/bank/relation mutation across the whole range, including 1.7.10**. Any post-1.7.10
Bukkit type in an event field or accessor descriptor (e.g. an `Optional<Player>` is fine, but
a getter returning a 1.14 `PersistentDataContainer`, a 1.16 type, etc.) `NoClassDefFoundError`s
when the event class links on 1.7.10 — and the gate never looks at `:api`. Same for any
post-floor descriptor in a `:ports` interface (also unscanned). The proposal's own design law
("no sub-floor symbol in a baseline descriptor") is not enforced where it fires most visibly.

**Fix:** run `verifyDescriptorFloor` (and F1's member check) over `:api` and `:ports` output
too. These are baseline-loaded on every server; they belong in scope.

### M2. The floor gate guards only the *bottom* of the range. Modern *removals* of deprecated baseline types are ungated.

The symbol table is `bukkit-1.7.10.txt`. It can only catch types **absent at 1.7.10**. It
cannot catch a type that exists at 1.7.10 but has been **removed by 26.1.2**. Two concrete
baseline listeners are exposed:

- `PickupListener` uses `PlayerPickupItemEvent` as **baseline** (§7.1, line 662) — deprecated
  since 1.12 and a standing candidate for removal on modern Paper. If 26.1.2 drops it, the
  baseline class links zero handlers on 26.x, and the 1.7.10 table can't flag it (the type is
  *in* the table).
- The `org.spigotmc…EntityMountEvent` variant is **already removed on newer Paper** (research
  §Part1/UNCERTAIN #7). It is correctly a probe-gated *pair* — good — but that pattern is the
  fix `PlayerPickupItemEvent` also needs and doesn't have.

Only the D-9 runtime scan on the 26.1.2 lane backstops this; the "build failure, not a field
incident" promise (§3.5) does not hold at the ceiling.

**Fix:** either add a ceiling symbol table (`bukkit-26.1.2.txt`) so the gate flags baseline
descriptors referencing types absent at *either* end, or demote every deprecated-and-removable
event (starting with `PlayerPickupItemEvent`) to a probe-gated dual like the mount pair.

### M3. The `nativeAudience` probe collides with relocation *and* with `verifyTextBoundary` — native Adventure may never engage on 1.16.5+.

The probe (line 179) is `getMethod(CommandSender, "sendMessage", net.kyori…Component)` "by
name string," and it lives in `:platform`, which **is** in the relocated shade. Shadow's
relocator rewrites `net/kyori` string literals. So a literal probe string is either:

- rewritten to `dev/fablemc/factions/lib/adventure/…Component` → the probe checks for a
  `sendMessage(<relocated Component>)` that the server never has → **probe always false → the
  native-audience path is never selected on any modern Paper (1.16.5+)** → every modern server
  silently drops to the Bungee/flat path. A functional regression on the majority of the
  supported population, reported as a "fallback" nobody expected; or
- left intact and then **`verifyTextBoundary` fails the build** (§3.5: no `net/kyori` token
  outside `text/` and `lib/adventure`; `:platform.Capabilities` is neither).

The proposal states the `String.valueOf` relocation-defeat trick only for the *port*
(`NativeAudienceTextPort`, line 236), not for the *probe* that gates it. And
`NativeAudienceTextPort` lives in `compat/modern`, which is **outside** the declared Component
boundary (`text/` + `lib/adventure`) — so the native bridge itself is in tension with the
boundary rule unless it references the server's serializer purely via dynamically-built
strings (no `net/kyori` token in the constant pool at all).

**Fix:** specify the relocation-defeat construction for the probe explicitly, and either move
the native bridge under `text/` or widen the `verifyTextBoundary` allowlist to the bridge
package. As written the two invariants and the relocator can't all be satisfied.

### M4. Shaded Adventure pinned to "newest 4.x," but the JSON is parsed by the server's *older* Adventure on 1.16.5–1.18.

§11 (line 811) deliberately deviates from Mental — Mental BOM-pins the shaded Adventure to the
floor server's version (4.9.3) precisely so the shaded copy is API-identical to the server's.
The proposal argues BOM identity is unnecessary "because we never hand Components to Paper
(JSON bridge only)." But the JSON *is* handed to Paper: `NativeAudienceTextPort` parses the
plugin's JSON with the **server's** `GsonComponentSerializer` (whatever Adventure ships on that
server — ≈4.9–4.11 on 1.16.5–1.18). If the shaded newest-4.x serializer emits any component/
field added after the server's Adventure version (shadow colors, newer hover/click content
shapes, nested/added component types), the server's parser throws on those versions — the
native path breaks on exactly the older modern servers it is meant to serve. This is the
mismatch Mental's BOM pin exists to prevent, reintroduced.

**Fix:** pin the shaded Adventure to the *oldest* native-Adventure server in the matrix (the
1.16.5 line's version), or restrict the emitted JSON to the feature set that oldest version
parses, and add a matrix assertion that 1.16.5 round-trips a representative rich message.

### M5. The Folia read plane uses backward-shift deletion under wait-free concurrent readers — a transient protection bypass.

§4.4: `ClaimTable`/`RelationTable` are open-addressed, "no tombstone rot (deletes use
**backward-shift**)," writer does `VarHandle.setRelease` element stores, readers `getAcquire`.
On **Paper** this is safe because the writer (global/main thread) and the protection listeners
are the *same* thread — no concurrency. On **Folia**, protection listeners read from **region
threads** while the global writer deletes. Backward-shift deletion is the classic operation
that is **not safe for lock-free readers**: shifting a live key from slot *j* back to slot *i*
(< *j* in probe order) can move it *past* a concurrent reader whose probe cursor is already
between *i* and *j*; the reader then reaches the cleared slot *j*, sees empty, and concludes
the key is **absent**. For a claim lookup that means a momentary false "wilderness" during an
unclaim/relation edit → `AccessEngine` allows a block break / PvP in still-claimed land for
that window. This is precisely why the non-blocking hash-map family the proposal invokes uses
**tombstones**, not backward-shift. The Folia lane is `claims-smoke` (release only) and a
race this narrow will not reliably surface there.

**Fix:** use tombstone deletion (with periodic writer-side compaction) on the concurrent path,
or adopt Mental's proven copy-on-write snapshot-swap (`AtomicReference<ClaimsView>`, mental-seam
§6) for the tables that region threads read. The jcstress suite (§13.4) must explicitly model a
region-thread reader racing a shift-delete of a key it is probing for, not just resize.

---

## MINOR (real, lower blast radius)

- **Missing `AreaEffectCloud` probe.** §5b routes AEC true-attacker resolution through "a
  probe-gated helper," but no `areaEffectCloud` probe appears in the §3.2 inventory. The
  `instanceof AreaEffectCloud` must execute only behind a real probe or it `NoClassDefFoundError`s
  on 1.7.10/1.8 PvP (an unguarded `instanceof` of an absent type throws when executed — it is
  *not* a safe body-only reference). Add the probe and cite it.
- **Off-hand interact double-fire.** `InteractListener` is baseline; if it calls
  `PlayerInteractEvent.getHand()` to dedupe the main+off-hand double-fire (1.9+), that is an
  F1-class ungated NoSuchMethodError on 1.7.10/1.8; if it doesn't, container-open side effects
  double-fire on 1.9+. Needs an explicit `interactHand` probe + MethodHandle.
- **`AsyncTabCompleteEvent` package.** §3.2 probes only
  `com.destroystokyo.paper.event.server.AsyncTabCompleteEvent`. Paper has been migrating
  `com.destroystokyo` events to `io.papermc.paper`; if the old FQN is gone on 26.x, async
  tab-complete silently dies on the newest servers. Dual-probe both packages.
- **Brigadier "decoration" vs Paper's registration model.** §3.3 says `BrigadierDecorator`
  "walks the same `CommandTreeSpec` to *add* typed argument suggestions" to the plugin.yml
  command. Paper's 1.20.6 `io.papermc.paper.command.brigadier` API **registers full commands
  into the dispatcher via the `LifecycleEvents.COMMANDS` handler** — it does not decorate a
  foreign `PluginCommand`. Registering a brigadier `/f` alongside the plugin.yml `/f` risks
  double-registration/shadowing on 1.20.6+, and the COMMANDS lifecycle handler registered in
  `onEnable` (boot step 11) may be too late for the initial event. Clarify the mechanism; at
  minimum it is not free "cosmetic."
- **No gate for "post-floor-typed non-listener helper instantiated only behind a probe."** The
  design law (§3.1.5 / §3.5) that hoisted helpers are reached only behind a resolver is
  enforced only for *listeners*. A boot-path helper with a post-floor descriptor instantiated
  unconditionally would link-fail on 1.7.10 and no build gate catches it — only the runtime
  D-9 scan. This is the same convention-not-structure gap as F1.
- **World identity CHM vs late/reloaded worlds.** §5a interns `World` by object identity;
  claims load by world *name* at boot (§6.3 `board(world VARCHAR)`). Lazy multiverse worlds not
  loaded at `onEnable`, or a world unload/recreate mid-session, break the name→identity→id
  mapping (new `World` object → new id; claims for a not-yet-loaded world can't intern). No
  `WorldLoadEvent` handling is described. Common on multi-world servers.
- **`hexChat` decided by version string** (§3.2) violates design law #1 ("probe, never
  parse"). It happens to work (all ≥1.16 support `§x`), but a `ChatColor.of(String)` /
  hex-serializer method probe is available and would keep the law intact.

---

## STRENGTHS worth keeping regardless of outcome

- The seam skeleton is correct and matches the proven Mental model: probe-once-freeze,
  monomorphic ports read as final fields, losing backends never classloaded (FQN-gated compat
  leaves), Enum.valueOf `Constants` table, separate probe-gated listener classes per post-floor
  event, the D-9 console-swallow CI scan, and the per-lane bytecode-tier assertion.
- **Compile floor = 1.13** to turn 1.13–1.16 into a zero-probe band (confining runtime
  resolution to 1.7–1.12 + modern extras) is the right probe-budget call and is exactly the
  lean `version-deltas` §3.17 recommends.
- **`InventoryView` via `MethodHandles.findVirtual(InventoryView.class, …)` with no boolean
  probe** correctly defuses the 1.21 class→interface ICCE in *both* directions — the single
  most dangerous GUI delta, handled properly.
- **`getOnlinePlayers` dual-descriptor MethodHandle** (Collection first, `Player[]` wrapped)
  behind a single `PlayersPort` funnel is the correct answer to the 1.7.10 binary break.
- **Item-blob envelope `{codec, dataVersion, payload}` + `serializeAsBytes` behind a probe +
  explicit `/fa chest migrate`** across the 1.13 flattening boundary is precisely right
  (research Risk #9); the implicit-load trap is avoided.
- **Single-writer Realm + storage-as-projection (no read-modify-write anywhere) + reserve→
  settle Vault + effective-only `RelationTable`** structurally erase whole catalogued bug
  classes rather than promising to avoid them — the strongest part of the design.
- **`teleportAsync` mandatory on Folia, retired-callback TCK, `runGlobal` writer, protection
  checks inline on the event's region thread reading a published plane** is the correct Folia
  routing (modulo M5's deletion hazard and F2's selector bug).
