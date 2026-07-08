# Cross-Version Hostile Review — Proposal A (Data-Oriented Core)

Reviewer stance: I have shipped one-jar plugins across 1.7.10 → 26.1.2 + Folia. I attacked
proposal-A.md against `version-deltas.md`, `mental-build.md`, `mental-seam.md`. Findings are
ordered by blast radius. Every finding names the section, the exact detonation version(s),
and a fix.

---

## FATAL — F1. Native Adventure delivery is unbuildable under whole-jar relocation

**Sections:** §1 (compat-modern: `NativeAudience`, `ModernChatRenderer`), §4(d), §6.3, §15.6,
plus the build gates G3 and `verifyRelocation` (§11, §13).

The proposal wants BOTH of these at once, and they are mutually exclusive in a shaded jar:

1. Shade **and relocate** `net.kyori` → `dev/fablemc/factions/lib/adventure` across the whole
   shadowJar (§1 core, §11 relocation set), with `compat-modern` **folded into that same
   shadowJar** (§1, §11.5). Shadow relocation rewrites *every* `net/kyori/*` reference in
   *every* class in the jar — including the folded compat-modern classes.
2. Have `compat-modern.ModernChatRenderer` **implement Paper's native
   `io.papermc.paper.chat.ChatRenderer`** and `NativeAudience` call the server's native
   `CommandSender#sendMessage(net.kyori.adventure.text.Component)` (§4d "native component",
   §6.3 "cached MethodHandles to the server's native Adventure").

`ChatRenderer.render(Player, Component, Component, Audience)` takes/returns the server's
**native** `net.kyori.adventure.text.Component`. When compat-modern compiles
`class ModernChatRenderer implements ChatRenderer { public Component render(...) }` and the
shadowJar then relocates `net.kyori` → `dev.fablemc.factions.lib.adventure`, the override's
descriptor becomes `render(...)Ldev/fablemc/factions/lib/adventure/text/Component;`. That no
longer matches the native interface method
`render(...)Lnet/kyori/adventure/text/Component;`. Result on the server: `ModernChatRenderer`
does **not** actually implement the abstract method → **`AbstractMethodError` the moment Paper
invokes `render`** (or the class simply fails verification). Same mechanism kills
`NativeAudience`: it ends up calling `sendMessage(dev.fablemc…Component)`, which the server
does not declare → **`NoSuchMethodError`**.

There is no partial-relocation escape: shadow's `relocate("net.kyori", …)` is jar-global; you
cannot relocate kyori in `MessageCatalog` (needed — legacy servers have no kyori) while
leaving it native in the folded `compat-modern` (needed — the interface it implements is the
server's). And the proposal's own gates make the contradiction explicit:

- **G3** ("no `net.kyori` reference outside `TextPort`/`MessageCatalog`") **forbids**
  compat-modern from referencing kyori at all.
- **`verifyRelocation`** (cloned from mental-build §6) fails the build if any `.class`
  *outside* the `…/lib/` prefix contains the token `net/kyori`. compat-modern lives under
  `dev/fablemc/factions/compat/modern/…`, outside `…/lib/`. So if compat-modern references
  **native** kyori (as it must to implement `ChatRenderer`), the build **fails**; if it
  references **relocated** kyori (what shadow produces), it **breaks at runtime**. Catch-22.

**Why Mental never hit this:** mental-seam §3 is explicit — Mental's `TextPort` serializes
every Component to a `§`-legacy String and uses the **universal String sinks**
(`sendMessage(String)`, `setFormat(String)`). Mental **never** implements `ChatRenderer` and
**never** calls a native `Component` sink, so its shaded Adventure copy "stays inert" on
modern. Proposal A deletes that invariant (adds `NativeAudience`, native `ModernChatRenderer`,
`chatTagNative`) and thereby breaks the one property that made relocation safe.

**Detonates:** every modern Paper where the native chat path is selected — **1.16.5 →
26.1.2**, i.e. the entire modern half of the matrix, plus Folia 26.1.2.

**Fix:** pick one text delivery model.
- (Recommended, = Mental) Deliver everything as `§`-legacy Strings through `TextPort` on the
  universal String sinks. Drop `NativeAudience` and the native `ModernChatRenderer`; keep the
  legacy `AsyncPlayerChatEvent` `setFormat` path on *all* versions (a `ChatRenderer` is only
  needed if you insist on hover/click in the tag). This forfeits the §15.6 "zero parse on
  modern / native component" SOTA claim — that claim is not achievable alongside relocation.
- Or, if native delivery is truly required: reach native Adventure **100% reflectively** (no
  compiled `net.kyori` symbol anywhere on the native path; register the renderer via a
  reflective `java.lang.reflect.Proxy` of `ChatRenderer` so no relocated override descriptor
  exists), and convert the shaded template component to a native one by
  `shadedGson.serialize → JSON String → nativeGson.deserialize` via MethodHandles. This is
  far more than "0 parse per message," and it drags in F4/F10 below.

---

## MAJOR

### M2. Folia shared-live-Inventory chest = cross-region thread-ownership violation

**Sections:** §7.3, §2.5, §14 (concurrency bug #7 "structurally gone").

The anti-dupe design is "one live shared `Inventory` per (faction, chest); subsequent openers
get *the same instance* (Bukkit then handles viewer concurrency)." That last clause is a
**Paper** assumption. On **Folia (26.1.2, in-matrix)** each viewing player's inventory
click/drag/close events fire on **that player's region thread**. Two faction members standing
in two different regions viewing the *same* `Inventory` object means two different region
threads read and mutate one un-synchronized `Inventory` concurrently. Folia does **not**
arbitrate that — it enforces `ensureTickThread`, it does not make cross-region inventory
mutation safe. §2.5 even admits the owner is "region thread (Bukkit) + writer," and on Folia
"region thread" is not singular.

So concurrency bug #7 is **not** structurally killed on Folia — the "two divergent copies can
never exist" claim is a Paper-only guarantee, and the shared object instead produces a data
race / `IllegalStateException` under region checks.

**Detonates:** Folia 26.1.2 (any faction chest opened by members in ≥2 regions concurrently).

**Fix:** on Folia, do not share one `Inventory` across regionally-distinct viewers. Give each
opener a private view backed by the writer's authoritative contents, and funnel every slot
change through a `ChestSlotOp` on the writer (last-writer-wins on the authoritative array,
re-pushed to open viewers via each viewer's `EntityScheduler`). This reintroduces the
reconciliation the shared-instance trick was meant to avoid — meaning the "structural"
dupe-freedom claim must be downgraded to "single-authoritative-array + per-op reconciliation,"
which is fine, but it is not free and not what §7.3 describes.

### M3. Shaded DB drivers are not pinned to Java-8-compatible versions → the 1.7.10 / 1.8.8 rungs break

**Sections:** §1 (shade HikariCP, H2, mysql-connector-j), §5.5, §11.3, §13.8
(`verifyJdk8Api`, empty allowlist), §15.8 ("nineteen years of servers").

The plugin claims flagless boot on **1.7.10 / 1.8.8 running on Java 8** (§11.2 matrix, tier
52). On those rungs only the **v52 base tree** loads and it runs on a **real Java 8** JVM.
jvmdg downgrades *bytecode* and shims *common* post-8 stdlib calls — it does **not** shim
arbitrary Java 9+ APIs (mental-build §6 `verifyJdk8Api` exists precisely because a stray
un-shimmable JDK-9+ call, e.g. `ThreadLocalRandom.nextDouble(double)`, compiles/downgrades
green then `NoSuchMethodError`s on Java 8). **HikariCP 5.x requires Java 11** and uses Java
11 stdlib APIs; modern H2 and recent mysql-connector-j lines similarly baseline above Java 8.
Shaded and downgraded to v52, those libraries will either (a) trip `verifyJdk8Api` (empty
allowlist) and **fail the build**, or (b) if a call slips past the ASM scanner's
reflective-lookup blind spot, **`NoSuchMethodError` on the Java-8 rung at runtime**.

The proposal names no dependency versions and defaults would pull HikariCP 5.x — which cannot
run on Java 8. Note the mid-matrix rungs are fine (1.13.2/1.14.4 = Java 13, 1.15.2 = Java 14,
1.16.5 = Java 16, all ≥ 11); the exposure is specifically the **true Java-8 rungs, 1.7.10 and
1.8.8** — the exact versions the "one artifact, nineteen years" headline rests on.

**Fix:** pin the shaded stack to its last Java-8 line — **HikariCP 4.0.3**, an H2 line that
still targets Java 8, mysql-connector-j 8.0.x (or drop MySQL support below Java 11 and
document it) — and prove it: run `verifyJdk8Api` over the *shaded+downgraded* jar including
these libs, and add a 1.7.10/1.8.8 live-boot DB smoke to the tester. Without the pin the
"flagless on 1.7.10" claim is false.

### M4. `ModernChatRenderer` gated on the wrong probe (AsyncChatEvent) but uses `ChatRenderer.ViewerUnaware`

**Sections:** §4(d), §6.1 (`modernChat` probe = `AsyncChatEvent` presence, 1.16.5+).

The modern chat path is selected by the `modernChat` probe = presence of
`io.papermc.paper.event.player.AsyncChatEvent` (**1.16.5+**). But §4(d) implements it with
`ChatRenderer.ViewerUnaware`, which was added to Paper's chat API **later** than the initial
`AsyncChatEvent`/`ChatRenderer` merge. On the band where `AsyncChatEvent` exists but
`ViewerUnaware` does not (**~1.16.5 – 1.18.x**), the probe passes, `ModernChatRenderer` is
selected and loaded, and it **`NoClassDefFoundError`s on `ChatRenderer$ViewerUnaware`** — and
because this is the chat listener, faction chat formatting silently dies on exactly those
versions (the D-9 console-scan would catch the linkage error, i.e. it turns into a matrix
FAIL rather than a field bug, but it is still a shipped defect).

**Detonates:** 1.16.5 – ~1.18.x (AsyncChatEvent present, ViewerUnaware absent).

**Fix:** probe `ChatRenderer.ViewerUnaware` specifically (class-presence), not
`AsyncChatEvent`; or implement the base viewer-aware `ChatRenderer.render(...)` (present since
1.16.5) since the tag is identical for all viewers anyway. This is separate from — and
additional to — F1.

### M5. `DamageIntake` is registered unconditionally but its attribution resolver names `AreaEffectCloud` (1.9)

**Sections:** §7.1 (`DamageIntake`, HIGH, no probe gate), §7.2, §6.1 (probe inventory), §5(c).

`DamageIntake` handles `EntityDamageByEntityEvent` (universal) and is registered **on every
version** with no probe. Its attribution resolver is documented as handling "Projectile →
shooter, TNTPrimed → source, **AreaEffectCloud**, tamed-pet owner." `AreaEffectCloud` is a
**1.9** type. Per the listener-descriptor hazard (version-deltas Risk #4, mental-seam §2e),
if `AreaEffectCloud` appears in **any method/field descriptor** of `DamageIntake` (e.g. a
declared helper `UUID sourceOf(AreaEffectCloud c)` or a field), Bukkit's
`createRegisteredListeners` fails to link the class on **1.7.10 / 1.8** and registers **zero**
handlers — meaning **all PvP protection silently dies** while the plugin looks alive. This is
the single highest-severity failure mode in the whole cross-version surface, and the proposal
walks right up to it: §6.1's "complete" probe inventory contains **no 1.9-entity guard**, and
the §7.1 `DamageIntake` row names `AreaEffectCloud` without stating it must be body-only.

The proposal's general §7.2 rule and G5 gate *can* catch this — but only if G5's "1.7.10-safe
set" whitelist is exact and the AreaEffectCloud handling is genuinely hoisted / body-only.
As written, the concrete design contradicts the general rule.

**Detonates:** 1.7.10 / 1.8 (all PvP/damage protection off, silently — a green-looking boot).

**Fix:** keep all 1.9+ entity types (`AreaEffectCloud`, and check `LingeringPotionSplashEvent`
handlers) out of `DamageIntake`'s descriptors — reference them only via in-body
`instanceof`/checkcast (safe) — or hoist cloud/lingering attribution into a probe-gated
non-Listener helper. Add an explicit `areaEffectCloud`/`lingering` (1.9) row to §6.1 so the
capability is tracked, not assumed.

---

## MEDIUM

### m6. Chest serialization on the Core Writer contradicts "the writer never touches Bukkit API"

**Sections:** §3.1 ("never touches the Bukkit API — what makes it legal on Folia"), §2.5, §7.3
(`ChestCommitOp` "persists contents"), §3.5 (shutdown "force-persist … through the writer").

Serializing a chest's `ItemStack[]` (`BukkitObjectOutputStream` or
`ItemStack#serializeAsBytes`) **is** a Bukkit/Paper API call — and on modern versions it can
touch item-component registries. If `ChestCommitOp` runs on the Core Writer and serializes
there, the writer touches the Bukkit API, breaking the invariant that makes it Folia-legal and
risking off-region registry access on Folia. The proposal is silent on where serialization
happens.

**Fix:** serialize on the **region thread** at `InventoryClose`/dirty-tick (where the
inventory legally lives), and hand the writer only the resulting `byte[]` + data-version tag.
State this explicitly; otherwise §3.1's core claim is violated by §7.3.

### m7. `compat-modern` compiled against one Paper (1.20.6) must run 1.16.5 → 26.1.2

**Sections:** §1 (compat-modern @ paper 1.20.6), §6.6 (`BrigadierDecorator`).

compat-modern is a single compile against Paper 1.20.6, but its classes load across a wide
band gated by different probes: `ModernItemBlobs` on 1.16.5+, `ModernChatRenderer` on 1.16.5+,
`BrigadierDecorator` on 1.20.6+. Two binary-stability risks:
- **Brigadier lifecycle** (`io.papermc.paper.command.brigadier`) was **experimental/evolving**
  from 1.20.6 through 1.21.x. Compiling against 1.20.6 and running on **26.1.2** risks
  `NoSuchMethodError`/`AbstractMethodError` if `Commands`/`LifecycleEvents` signatures moved.
- `ChatRenderer`/`ViewerUnaware` shape differences across 1.16.5 → 26.x (see M4).

Mental pinned its modern compile at 1.20.6 too, but used a narrower slice of it. A factions
plugin leaning on the Brigadier lifecycle needs to treat "modern" as a **band**, not a point.

**Fix:** capability-probe each modern API by method/class presence rather than trusting one
compile version to be binary-stable across a 10-version span; smoke `BrigadierDecorator` on
both 1.20.6 and 26.1.2 in the live matrix (the proposal tests 26.1.2 but should assert
Brigadier registration specifically, not just boot).

### m8. Two writers per `PlayerSession` undercut the single-writer / zero-lock claim

**Sections:** §2.1, §2.5 ("PlayerSession fields written only by the writer"), §4(a)
(reads `s.lastOwnerOrd`), §7.4 (combat tag set by `DamageIntake`).

Hot cache fields — `lastChunkKey`, `lastOwnerOrd`, `combatTagUntil`, `flyOn` — must be written
at event latency (a chunk crossing, a PvP hit), yet §2.1/§2.5 declare sessions **writer-only**.
Either these are written on the **region thread** (violating A2's "single linearization point"
and the §15.1 "no monitors in listener packages / single writer" claim), or every chunk
crossing submits an Op just to refresh a cache field (heavy, and it lags the very next event).
On **Folia** it is race-free *only if* each session's hot fields are touched by exactly one
region thread AND never also by the Core Writer for the same field — the proposal never
establishes that disjointness, so release/acquire alone does not prevent a lost update between
the region thread and the writer.

**Fix:** explicitly partition session fields into "region-owned hot cache" (written only by
the owning player's region thread) vs "writer-owned authoritative" (written only by the Core
Writer), and prove the partition is disjoint. Then A2's "one writer" is really "one writer per
field-class," which is defensible — but must be stated, because §15.1/§15.2 currently
overclaim a single global linearization point.

### m9. Missing / imprecise probes

**Section:** §6.1 inventory (billed as complete), §7.1, §7.4.

- **`AreaEffectCloud` (1.9)** — no row (see M5).
- **`LingeringPotionSplashEvent` / `AreaEffectCloudApplyEvent` (1.9)** — §7.1 says
  "probe-gated" but §6.1 has no probe row; needs one or the PotionIntake class risks the
  descriptor hazard on 1.7.10/1.8.
- **`EntityToggleGlideEvent` / elytra gliding (1.9+)** — version-deltas 3.16 explicitly flags
  that fly-in-claim logic must treat gliding separately on 1.9+. §7.4's fly system never
  mentions elytra; a gliding player in enemy territory bypasses fly-revocation reasoning.
- **`hidePlayer(Plugin, Player)` vs `hidePlayer(Player)` (1.12.2)** — no vanish overload
  probe (version-deltas 3.16). Low impact if vanish is delegated to EssentialsX metadata, but
  the seam should own it.
- **`AsyncTabCompleteEvent` (Paper ~1.12)** — §6.6 wants off-thread completion, but core
  compiles against **Spigot** 1.13.2 where this Paper event class is **absent**, so a
  `@EventHandler(AsyncTabCompleteEvent)` cannot compile in core, and compat-modern's class
  list (§1) does not include it. The listener has no compilable home.

**Fix:** add the four missing probe rows; assign the async-tab-complete listener to
compat-modern (or drop the feature) since Spigot-floor core cannot see the event type.

### m10. Adventure version skew on the native round-trip

**Sections:** §6.3, §5.5 (DFU), F1.

Core compiles against **Spigot 1.13.2**, which ships **no Adventure at all** (native Adventure
is Paper 1.16.5+), so the shaded Adventure version is a free choice (say 4.x). On **26.1.2**
the server's native Adventure is much newer (4.17+). Any native-delivery path (F1) that
round-trips shaded-template → native must survive tag/component types present in native-4.17
but absent in shaded-4.x (and vice versa) — new MiniMessage tags, component kinds, style
fields silently drop or throw on deserialize. mental-build §3 avoided this by pinning the
shaded Adventure to the *exact version the floor API declares* so shaded == native; with a
Spigot floor that has no Adventure, that pinning anchor does not exist.

**Fix:** if F1 is resolved by dropping native delivery, this is moot (shaded copy stays inert,
version is irrelevant). If native delivery is kept, pin shaded Adventure conservatively and
JSON-round-trip through the *native* serializer only (never hand a shaded component object to
a native sink), and accept that new native features won't render.

---

## MINOR

- **m11. Probe-order dependency.** §6.1 mixes tier-1 coarse booleans and tier-2 resolutions in
  one table; `teamApis` budget "16 vs 64 by `flattened`" reads another probe's result. Ensure
  the coarse `flattened` capability is fully resolved (tier 1, mental-seam §1) before any
  tier-2 entry consumes it, or the budget silently defaults wrong. (Reusing `flattened` for
  the 1.13 team-limit flip is otherwise correct and elegant — same boundary.)
- **m12. Cross-flatten chest first-open crash.** §5.5 defers chest deserialization to first
  open (§7.3). After a 1.12→1.13 in-place upgrade, a stale `INK_SACK:4` blob throws on first
  open if `/fa import chests` wasn't run. "Explicit migration step" is documented but
  unenforced; gate first-open on a per-blob data-version check that routes pre-flatten blobs to
  a translation path (or refuses with a clear message) rather than throwing.
- **m13. api-version 26.x acceptance** (§11.1) rests on version-deltas Part 4 UNCERTAIN #10
  ('1.13' accepted by 26.x). The matrix boots 26.1.2, which validates it — good — but this
  must actually be asserted, not assumed, since a rejection fails the *entire* modern half.

---

## What is genuinely right (graft these even if A loses)

- **Writer-off-the-Bukkit-thread state model** (§3.1): a plugin-owned writer that never calls
  the Bukkit API is the cleanest possible Folia story for *state* — correct and rare. (Its
  reach into chest serialization and session-field writes must be tightened — m6, m8 — but the
  core idea is sound.)
- **InventoryView ICCE handling** (§6.5, G4): holder-identity routing + `getInventory()` +
  rawSlot math + MethodHandle `findVirtual` for the rare genuine view call, plus a static gate
  banning direct `InventoryView` invokes. This is the textbook defense for the 1.21
  class→interface flip and is executed correctly — no ICCE exposure found.
- **getOnlinePlayers dual-descriptor MethodHandle + G6 gate** (§6.1, §13.4): exactly the
  version-deltas Risk #1 remedy, funnelled through one `Players.online()`.
- **Static listener-descriptor gate G5 + D-9 console scan** (§7.2, §11): making the
  "protection silently registered zero handlers" catastrophe a *build/check* failure is the
  highest-leverage discipline in the whole design (it is what would catch M5 if the whitelist
  is exact).
- **`flattened`-probe reuse for the 16→64 team-prefix boundary** (§6.1 `teamApis`): both flip
  at 1.13; one probe, correctly.
- **api-version '1.13'** (§11.1): the unique value that loads 1.7.10→26.x with no Commodore
  shim fighting the modern-name kernel — matches version-deltas Risk #5.
- **Chest blob data-version prefixing + explicit cross-flatten migration** (§5.5): the correct
  shape for version-deltas Risk #9 (modulo m6/m12).
- **MRJAR fidelity** (§11): `multiReleaseOriginal=true`, never `multiReleaseVersions`
  (1.3.6 mutual-exclusivity), union downgrade classpath incl. compat-folia + compat-modern
  supertypes, empty-allowlist `verifyJdk8Api`, distinct tester jvmdg prefix — cloned faithfully
  from mental-build.
