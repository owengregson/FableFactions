# Cross-Version Hostile Review — Proposal C ("The Deterministic Kernel")

Reviewer stance: someone who has actually shipped one jar across 1.7.10 → 26.1.2 + Folia.
Every claim below is cross-checked against `version-deltas.md`, `mental-build.md`, `mental-seam.md`.
The kernel/threading thesis is genuinely strong; the failures are all at the **platform seam and the
artifact pipeline**, and several detonate silently on the *most common* half of the matrix.

---

## FATAL

### F-1. Native Adventure (audiences + `ChatRenderer`) is mutually exclusive with the mandatory `net.kyori` relocation — silent break on every Paper ≥ 1.16.5

**Where:** §7.3 ("delivery tiers by probe: **native Adventure audience (Paper 1.16.5+)** / Bungee
components / §-legacy"), §2 compat-modern = `ModernChatRenderer (AsyncChatEvent)`, `AdventureAudiencePort`;
§11 change #2 relocates `net.kyori → …lib.adventure`; §2.2 / §13.5 enforce `verifyRelocation`.

**The contradiction.** `verifyRelocation` (mental-build §6) rewrites **every** `net/kyori` reference
outside `…/lib/` — jar-wide, package-based, not module-scoped. Shadow cannot relocate `net.kyori`
inside `:core` but leave it native inside `:compat-modern`; they live in one shaded jar, so it is
all-or-nothing. But a Paper-native `Audience.sendMessage(Component)` sink and a Paper `ChatRenderer`
interface both require the **server's un-relocated** `net.kyori.adventure.text.Component`. After
relocation:

* `AdventureAudiencePort` compiled against paper-api 1.20.6 carries `net/kyori/...Audience/Component`
  in its bytecode → shadow rewrites them to `dev/fablemc/factions/lib/adventure/...`. At runtime on
  1.20.6 it invokes `player.sendMessage(lib.adventure.Component)`; `Player` is a *native* `net.kyori`
  Audience, not a `lib.adventure` one → `NoSuchMethodError`/`AbstractMethodError`. **`verifyRelocation`
  still passes** (there are no un-relocated `net/kyori` tokens left — they were rewritten), so this is a
  *silent* break, not a build failure.
* `ModernChatRenderer implements ChatRenderer` — after relocation its `render(...)` takes
  `lib.adventure.Component`, so it no longer overrides Paper's
  `net.kyori...ChatRenderer#render(Component,...)`. It is registered as a renderer that never actually
  renders (or fails to instantiate).

**Detonates:** every Paper 1.16.5 → 26.1.2 and Folia 26.1.2 — i.e. the majority of the support matrix,
including the flagship modern targets.

**Why this is the tell:** Mental *deliberately never touches native Adventure* precisely for this
reason (mental-seam §3: "On modern servers the relocated Adventure copy is **inert** — it never touches
Paper's native Adventure, so no dual-Adventure conflicts"; TextPort serializes to `§`-legacy and uses
the universal `String` sinks). Proposal C ignores that lesson three times (native tier, AudiencePort,
native ChatRenderer). The native tier is also **unnecessary**: the Bungee-component path
(`player.spigot().sendMessage(BaseComponent[])`, 1.8+) already delivers hover/click/hex on 1.16+, so
nothing a factions plugin needs is lost by dropping native.

**Fix:** delete the native-audience tier and the native `ChatRenderer`. Do exactly what Mental does —
relocate Adventure, render once, downsample to `§`-legacy (with hex) via the relocated
`LegacyComponentSerializer`, deliver through the universal `String` sinks; use the Bungee-component
path for rich text on 1.8+. If chat-*format* mutation on 1.19.1+ signed-chat truly needs a renderer,
the renderer module must be **compiled and shipped without relocating `net.kyori`** (a separate,
un-shaded compat artifact provided-by-server), which is a different jar strategy than the one proposed
— it cannot coexist with the blanket relocation in one shadow output.

---

## MAJOR

### M-1. The Java-8 base tier is incompatible with the chosen persistence stack (HikariCP 5 / H2 2.x)

**Where:** §2 `:core` impl `HikariCP, H2, mysql-connector-j`; §6.2 `jdbc:h2:file:…`; §11 relocates
`com.zaxxer.hikari`, `org.h2`, `com.mysql`; §11 keeps `verifyJdk8Api` (empty allowlist) +
`verifyDowngrade` (base ≤ v52).

The v52 base tree must load and run on **Java 8** (1.7.10/1.8 hosts per §11 #4, and the
verifyJdk8Api gate validates it against a real JDK-8 `rt.jar`). But:

* **HikariCP ≥ 5.0** is compiled to Java 11 (v55) and uses Java 9+ APIs → `UnsupportedClassVersionError`
  on Java 8 hosts; jvmdg would have to downgrade it and any un-shimmable JDK-9+ call fails
  `verifyJdk8Api`. Last Java-8 line is **4.0.3** (EOL).
* **H2 ≥ 2.0** requires Java 11 and pervasively uses Java 9+ stdlib → same failure. Last Java-8 build
  is **1.4.200** (2019, EOL, known correctness issues).
* mysql-connector-j must be pinned to a Java-8 line (8.0.x).

`verifyDowngrade` only checks *first-party* classes for major ≤ 52 (`isFirstParty` excludes `…/lib/`),
so a v55 pooled-driver class under `…/lib/hikari/` slips that gate and then throws
`UnsupportedClassVersionError` live on Java 8. This whole tension — that shading a modern DB/pool stack
into a Java-8-loadable base tier forces EOL library pins or breaks the entire legacy band — is never
acknowledged. Mental never hit it (no DB). **Fix:** pin HikariCP 4.0.3 / H2 1.4.200 / connector-j 8.0.x
and add them to the CI dependency-lock with a note, OR raise the *host floor* off Java 8 (which forfeits
the 1.7.10/1.8 story). Either way, state it.

### M-2. The live integration matrix cannot reach 1.7.10–1.8.8 — the D-9 console-scan safety net is absent exactly where the new hazards live

**Where:** §11 #4 "Matrix extended down to 1.7.10 … ladder-probed once in CI"; §13.8 "boots real
Paper/Folia at each version"; §15 N-8 "20 server generations … live-asserted."

run-paper (the cloned harness, `xyz.jpenilla.run-paper` 3.0.2) fetches servers from the PaperMC
downloads API, whose **oldest served builds are ~1.8.8/1.9.x** — 1.7.10 "PaperSpigot" predates that
infrastructure and is not downloadable. Corroboration: Mental's own matrix floor is **1.9.4**, and the
grounding research (`version-deltas` Part 4, the legacy-boot doc) *never booted anything below 1.9.4* —
1.7.10 behavior is tagged UNCERTAIN throughout and explicitly deferred to a "ladder-probe like the
1.9.4–1.16.5 run" that has not happened. Proposal C asserts it will boot 1.7.10 in CI without
acknowledging that run-paper can't source it.

Consequence: the entire **1.7.10–1.8.8 band** — the band with the *most* cross-version hazards this
plugin newly takes on (the `getOnlinePlayers` array/Collection split, `GameMode.SPECTATOR` /
`BlockExplodeEvent` / `ArmorStand` sub-floor traps, `Team#addEntry` vs `addPlayer`, no Bukkit
`sendTitle`, Bungee-component presence UNCERTAIN) — is verified only by a **static** descriptor gate,
and the D-9 console-swallow scan (the actual backstop that turns "listener silently registered zero
handlers" into a red build, mental-build §11 / §563) never runs there. The headline "20 server
generations, live-asserted" is undeliverable for its two lowest generations with the stated tooling.
**Fix:** either drop the runtime floor to 1.9.4 (match Mental and the research), or specify a *manual*
legacy-jar provisioning path (archived Spigot 1.7.10/1.8.8 + BuildTools, wired into RunServer by local
file) and prove it boots on Java 8 — and until then mark 1.7.10/1.8 "best-effort, unverified," not
"live-asserted."

### M-3. Probe-gated listeners for 1.14+ event types cannot compile at the 1.13.2 floor and are not placed in a `>`-floor module

**Where:** §11 #1 "Compile floor 1.13.2, not 1.17.1"; §8.1 lists `RaidListener` and
`MountListener (bukkit/spigot dual)` as ordinary "probe-gated separate classes"; §2 compat-modern
inventory = only `ModernChatRenderer, AdventureAudiencePort, BrigadierInstaller, ModernItemCodec`.

Lowering the floor to 1.13.2 (vs Mental's 1.17.1) is sold as pure upside ("shrinks the probe budget for
1.13–1.16 to ~zero"), but it means any listener whose **event type is above 1.13.2** can no longer be
compiled in `:core`/`:platform`:

* `RaidListener` references `org.bukkit.event.raid.RaidTriggerEvent` — **1.15**, absent from paper-api
  1.13.2. Won't compile in core.
* the modern half of `MountListener` references `org.bukkit.event.entity.EntityMountEvent` — moved to
  the `org.bukkit` package at **1.20.3**, absent from 1.13.2. (The `org.spigotmc` variant *is* on the
  1.13.2 classpath, so only the modern half breaks.)

Both must live in `:compat-modern` (paper-api 1.20.6) and be FQN-gated like the chat renderer, but §2's
compat-modern inventory omits them and §8.1 treats them as core classes. This is a **systematic**
consequence: every 1.14–1.20 event-typed probe listener now needs a home above the floor, and the
proposal's module map doesn't account for them. (`getMinHeight` §7.1, 1.17, is the same story on the
method side — it is above the 1.13.2 floor and can only be reached reflectively, so the "shrinks probe
budget" claim is one-sided: it *grows* the 1.17+ reflection/compat surface.) **Fix:** enumerate every
`>1.13.2` event/method the design touches and place each in compat-modern; or accept Mental's 1.17.1
floor and confine the extra work to the 1.7–1.16 runtime band.

### M-4. `ClickType.SWAP_OFFHAND` (1.16) is missing from the enum-probe inventory — sticky `NoSuchFieldError` in GUI/chest click handling pre-1.16

**Where:** §7.1 enum probes list only `spectator, sweepCause, newTeleportCauses, newSpawnReasons`;
§7.5 GUI + §8.1 `GuiListener`/`ChestSessionListener` on `InventoryClick`.

version-deltas §3.7 is explicit: "Number-key + off-hand swaps into GUIs must be cancelled on 1.9+
(hotbar button 40)" and "`SWAP_OFFHAND` ClickType 1.16+ (Enum.valueOf)." A GUI/chest handler that does
`event.getClick() == ClickType.SWAP_OFFHAND` performs a `getstatic` of a 1.16 enum constant → the
sticky `NoSuchFieldError` rethrown on **every** click on 1.7.10–1.15 (mental-seam §2b). This constant
is not in the probe inventory, and a chest GUI *must* guard offhand/number-key swaps to prevent item
dupe/exfiltration out of the shared faction-chest inventory. **Fix:** resolve `SWAP_OFFHAND` (and the
number-key `HOTBAR_SWAP`/button-40 handling) once via `Enum.valueOf` into a nullable field, exactly
like the other post-floor enum constants.

### M-5. Location-bound integration effects are not region-routed on Folia

**Where:** §1 shows `Integration subscribers (dynmap, WG sync, LWC)` consuming the effect stream; §4.3
defines `WgRegionUpsert(worldIdx,key,faction)` and `LwcPurgeRequested(worldIdx,key,newOwner)`; §3.4's
Folia table routes only *feedback* (`runOn`), *broadcasts* (`runGlobal`), and *teleports*.

`WgRegionUpsert`/`LwcPurgeRequested`/dynmap-marker writes act on a **specific `(world, chunk)`**. On
Folia those API calls must run on the region thread that owns that location (`Scheduling.runAt(location,…)`,
mental-seam §4), or Folia throws `IllegalStateException` (wrong region / `ensureTickThread`). The design
routes these off the writer thread but never says *to which region*; WorldGuard region mutation and LWC
protection removal from the writer thread or a global thread will throw on Folia 26.1.2. **Fix:** every
location-scoped external effect must dispatch through `runAt(effectLocation, …)`, and the effect must
carry enough to reconstruct a `Location` (it currently carries `worldIdx`+`chunkKey`, which is fine — but
the routing rule is missing from §3.4).

---

## MEDIUM

### Md-1. `BlockPistonRetractEvent#getBlocks()` (1.8) called from the always-on `GriefListener` → `NoSuchMethodError` on 1.7.10

§8.1 registers `GriefListener` (Piston Extend/Retract, "both-ends check") as **always** on "all
floor-safe events." The *event* is floor-safe, but `getBlocks()` is **1.8+** (version-deltas §3.8:
"1.7.10 fallback `getRetractLocation`"). A body call to `getBlocks()` on 1.7.10 is a sticky
`NoSuchMethodError` on every piston retract. Needs a method probe + `getRetractLocation` fallback, or the
retract branch must be hoisted behind the same discipline as other sub-floor method calls.

### Md-2. `AreaEffectCloud` (1.9) in the always-on `DamageAttribution` helper is a latent descriptor hazard

§5c: "`DamageAttribution` (platform) resolves `Projectile#getShooter`, `TNTPrimed#getSource`,
**`AreaEffectCloud`**, tamed-owner **before** the verdict" — and this runs in the always-on combat path.
`AreaEffectCloud` is a 1.9 type; if it appears in any *descriptor* (param/return/field) of
`DamageAttribution`, merely loading the class on 1.7.10/1.8 is a `NoClassDefFoundError`
(mental-seam §2e: "Body-only references are safe; descriptors are not"). The lingering-cloud handling
is correctly gated into `LingeringListener`, but §5c pulls AEC attribution back into the always-on path.
The proposal never confirms AEC stays body-only. **Fix:** keep every 1.9+ entity type out of
`DamageAttribution`'s descriptors (accept `Entity`, branch on a probed `instanceof`), and state it.

### Md-3. No build gate against flattened-`Material` constant `getstatic`

§2.2 bytecode-scans for `Bukkit.getOnlinePlayers`, `InventoryView` methods, and
`Bukkit.getOfflinePlayer(String)` — but **not** for `getstatic` of post-1.13 `Material` constants. With
the compile floor at 1.13.2, `Material.WHITE_WOOL` / `PLAYER_HEAD` / `LEGACY_*` are all
compile-available, so a single stray constant reference (easy typo/autocomplete) compiles clean and
throws a sticky `NoSuchFieldError` on 1.7.10–1.12. The `LegacyMaterials` string-vocabulary discipline is
sound but unenforced. Given this plugin's GUI/config material surface, add a gate mirroring the
InventoryView scan (ban `getstatic org/bukkit/Material.<postFloorConstant>` outside the icon factory's
guarded branch).

### Md-4. The listener-descriptor gate presupposes a 1.7.10 symbol manifest it never sources

§2.2 final bullet scans listener descriptors "against a **floor-symbol manifest**." The runtime floor is
1.7.10, but the only Bukkit APIs on any build classpath are 1.13.2 / 1.20.4 / 1.20.6. If the manifest is
derived from the 1.13.2 compile API, it **contains** every 1.8–1.13 symbol and therefore gives
false-negatives for exactly the 1.7.10→1.13 band the extension newly opens (a descriptor mentioning a
1.11/1.12 type would pass). A genuine 1.7.10 symbol set has to be extracted from an actual 1.7.10 server
jar — the same jar M-2 shows they have no pipeline for. Same gap undercuts any "resolves on 1.7.10"
static check (there is no 1.7.10 analogue of verifyJdk8Api's real `rt.jar`). Until a real 1.7.10 surface
is sourced, the descriptor gate is only trustworthy down to 1.13, and the live matrix — the only real
check below that — can't run there either (M-2).

### Md-5. Pickup-event dedup unspecified

§7.1 probes `entityPickup`; version-deltas §3.8 / UNCERTAIN #6 warn that *which* of
`PlayerPickupItemEvent` (legacy) and `EntityPickupItemEvent` (1.12) fires for players on modern Paper is
uncertain, so a dual-register needs explicit **dedup**. The proposal registers both (probe-gated modern
class) but never states a dedup rule; if both fire, pickup-in-territory rules double-apply. Low blast
radius but a named research item left unhandled.

---

## What is correct (graft these regardless of C's fate)

* **`getOnlinePlayers` split** — dual-descriptor MethodHandle probe (`onlineCollection`, §7.1) +
  ArchUnit ban on direct calls (§2.2) routed through `Players.online()`. Exactly version-deltas Risk #1.
* **InventoryView 1.21 ICCE** — zero direct view calls, custom `MenuHolder` identity, `rawSlot` math,
  universal `createInventory(holder,int,String)`, `Views` MethodHandles for the unavoidable case
  (§7.5). Exactly version-deltas Risk #2, and correct that `findVirtual` dispatches across class↔interface.
* **Scoreboard 16/64** — budget-truncation after color accounting, tied to the flattening probe,
  prefix-tail color pre-1.13 / `Team#setColor` after, `addEntry` vs `addPlayer(OfflinePlayer)` probes
  (§7.1/§7.2). Exactly Risk #10 / §3.6.
* **`api-version: '1.13'`** — the unique value that loads 1.7.10 → 26.x, dodges the Commodore
  `LEGACY_*` remapper on 1.13+ (which would fight the modern-name kernel), and pairs correctly with the
  1.13.2 compile floor (§7.6). Exactly Risk #5.
* **Chat-signing** — using a `ChatRenderer` (format-only) rather than cancel+resend is the right way to
  avoid the 1.19.1+ signed-chat downgrade (version-deltas Risk #8) — *if* F-1's relocation contradiction
  is resolved. (Note: the proposal never states whether faction *chat channels* exist; if they do,
  cancel+resend there still loses signatures — worth a sentence.)
* **Item serialization across 1.13** — data-version-tagged blobs, `serializeAsBytes` behind a probe in
  compat-modern, legacy `BukkitObjectOutputStream`, one-way explicit cross-flattening migration (§7.2/§6.2).
  Exactly Risk #9, and correctly placed above the floor.
* **MRJAR gotchas** — `multiReleaseOriginal=true` with **never** `multiReleaseVersions` (1.3.6 mutual
  exclusion), downgrade classpath = **union** of core + compat-folia + compat-modern compile classpaths,
  distinct jvmdg prefix + `verifyProbeIsolation` stub-descriptor scan for the probe plugin (§11). All
  cloned correctly from mental-build §4/§5/§6.
* **The core thesis** — single-writer immutable `KernelState` published by `AtomicReference` makes every
  read genuinely wait-free and thread-safe **on Folia** (a `PlayerMoveEvent` on one region reading while
  another region mutates a faction is safe by construction, mental-seam §6), and structurally dissolves
  the RMW/TOCTOU concurrency bug classes. This is the strongest part of the proposal and none of the
  findings above touch it.
