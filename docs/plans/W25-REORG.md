# Wave 2.5 — Organization refactor + conventions sweep (NORMATIVE)

This document overrides `CONTRACTS.md` §2/§4 file layouts where they conflict. Goal: no
monolithic files (target ≤~400 lines; hard ceiling 500 except pinned data tables),
domain-aligned packages, enum-first vocabulary (explicit stable codes, never ordinals),
zero scattered string/int codes. Behavior must be byte-identical: this is a structural
refactor — all 128 existing tests must pass unchanged in ASSERTIONS (imports/type names may
be updated), and NO public behavior may change.

## P1 — vocabulary split + enum sweep (one agent, whole-repo ownership for this phase)

### 1a. Split `kernel/intent/Intent.java` into per-domain sealed sub-interfaces (same package)

`Intent.java` keeps ONLY: `public sealed interface Intent permits LifecycleIntent,
MembershipIntent, RoleIntent, ClaimIntent, RelationIntent, PowerIntent, EconomyIntent,
TravelIntent, ChestIntent, PrefIntent, SessionIntent, SystemIntent {}` + shared javadoc.
Each sub-interface is `public sealed interface XxxIntent extends Intent permits <its
records>` in its own file with its records NESTED inside it (reference as
`ClaimIntent.ClaimChunks`). Domain assignment:

- `LifecycleIntent`: CreateFaction, DisbandFaction, DisbandPage, RenameFaction,
  SetDescription, SetMotd, TransferOwnership, SendMergeRequest, AcceptMergeRequest,
  MergePage.
- `MembershipIntent`: JoinFaction, LeaveFaction, KickMember, SendInvite, RevokeInvite,
  DeclineInvite, DeclineAllInvites.
- `RoleIntent`: PromoteMember, DemoteMember, CreateRole, RenameRole, SetRolePriority,
  SetRolePrefix, DeleteRole, AssignRole.
- `ClaimIntent`: ClaimChunks, UnclaimChunks, UnclaimAll, UnclaimAllPage, AdminClaimChunks,
  AdminUnclaimChunks, SetZoneChunks, RemoveZoneChunk, ZonePage.
- `RelationIntent`: DeclareRelation.
- `PowerIntent`: RecordDeath, PowerTick, BuyPower, AdminPowerSet/Add/Remove/Reset,
  SetPowerFrozen.
- `EconomyIntent`: OpenEscrow (if present), CreditBank, RequestBankWithdrawal, SettleEscrow,
  TransferBank, TaxSweep, TaxSweepPage.
- `TravelIntent`: SetHome, UnsetHome, SetWarp, DeleteWarp, SetWarpPassword, SetWarpCost.
- `ChestIntent`: CreateChest, DeleteChest, CommitChestContents.
- `PrefIntent`: SetFactionFlag, SetNotifyPref, SetLocale, SetAutoTerritoryMode,
  SetTerritoryTitles, SetFly, SetOverriding, SetShield, ClearShield.
- `SessionIntent`: PlayerConnected, PlayerDisconnected, AckInbox.
- `SystemIntent`: SwapConfig, SeedPredefined, ImportBaseline, ReconcileSweep (if present),
  RetagPage.

### 1b. Split `kernel/effect/Effect.java` the same way

`Effect.java` = sealed root permitting: `LifecycleEffect, MembershipEffect, RoleEffect,
ClaimEffect, RelationEffect, PowerEffect, EconomyEffect, TravelEffect, ChestEffect,
PrefEffect, SessionEffect, AuditEffect, SystemEffect, FeedbackEffect, ExternalEffect`.
Notable assignments: `FeedbackEffect` = Notify, NotifyFaction, Broadcast, Rejected;
`ExternalEffect` = PayoutRequested, EscrowRefund, WgRegionUpsert, WgRegionRemove,
LwcPurgeRequested; `SystemEffect` = ConfigSwapped, ContinuationRequested (control — keep its
CONTROL exclusion in JournalCodec), ReconcileDrift if present; `AuditEffect` =
AuditRecorded. Consumers may now switch on the sub-interface (projector: domain effects;
feedback router: FeedbackEffect; integrations: ExternalEffect) — update
WriterThread/EffectFanout/StorageProjector/JournalCodec instanceof chains to use the
sub-interfaces where it simplifies them.

### 1c. New `kernel/vocab/` package — enum-first vocabulary (explicit stable codes)

Every enum: `private final int code;` + `code()` + static `fromCode(int)`; codes are the
CURRENT int/byte constant values (wire/DB compatibility); javadoc carries the reference
semantics. Create:
- `PowerSource` (from PowerMath SRC_*: REGEN_ONLINE(0)…ADMIN_RESET(8); move isAdmin/
  isAutomatic/sourceName onto the enum; `Effect.PowerChanged.source` becomes PowerSource…
  wait — record components must stay journal-codable: keep the component as the ENUM (codec
  writes `source.code()`)).
- `BankTxType` (DEPOSIT 0, WITHDRAW 1, TRANSFER 2, TAX 3) — replaces Effect.TX_*.
- `InviteRemovalReason` (ACCEPTED 0, REVOKED 1, DECLINED 2, EXPIRED 3).
- `NotifyPredicate` (MEMBERS_ALL 0, MEMBERS_OFFICERS 1).
- `BroadcastScope` (SERVER 0, STAFF 1).
- `EscrowKind` / `EscrowOutcome` (from EscrowTable/SettleEscrow codes).
- `ClaimMode` (from ClaimChunks.mode codes: SINGLE/AUTO/SQUARE/CIRCLE/FILL/NEARBY/AT — match
  existing codes).
- `ZoneKind` (SAFEZONE 0, WARZONE 1 — ordinals match sentinel faction ordinals; document).
- `PagePhase` (from DisbandPage/MergePage phase ints).
- `Relation` — enum companion of the byte codes (MEMBER 0…ENEMY 4) with
  `byte code()`/`fromCode`; **`RelationKind` byte constants STAY** for hot arrays
  (CONTRACTS hot-path pin); `Relation` is for cold paths (effects, storage, API).
- `VerdictKind` / `ActionKind` — cold-path companions of Verdict/Action int codes, each
  with its `MessageKey` mapping (VerdictKind carries the deny message key). The int
  constants and `Verdicts.decide(int...)` signature DO NOT change.
- Move `audit/FactionAuditAction.java` → `vocab/FactionAuditAction.java` (delete `audit/`);
  ensure it is an enum with explicit `id()` strings (the 18 reference ids).
- `msg/ReasonCode` → make it an enum if it is not already (explicit codes + messageKey()).

Update ALL producers/consumers of the replaced constants (kernel rules/reduce/state/tests,
core pipeline/journal/storage/session/tests, api where surfaced — api's RelationType/
BankTransactionType may now delegate to kernel vocab via mapping, but do NOT leak kernel
enums through :api public signatures — CONTRACTS §5 rule stands; api keeps its own enums
with explicit mapping functions in core).

Effect/intent record components that carried these raw ints/bytes now carry the enums
(records are cold-path). EXCEPTIONS (stay primitive): RelationKind bytes inside state
arrays, Verdict/Action ints, ActorBits packed longs, faction handles/ordinals, chunk keys,
prefsBits bitfields (document each at the declaration).

### 1d. `platform/probe/CompatClass.java`

Enum centralizing every reflective FQN: `FOLIA_SCHEDULING("dev.fablemc.factions.compat.folia.FoliaScheduling")`,
`MODERN_ITEM_CODEC(...)`, `BRIGADIER_INSTALLER(...)`, `ASYNC_CHUNKS(...)` with
`fqn()` + `load(ClassLoader)` helper. SchedulingFactory/ItemCodec switch to it. Future
compat classes MUST be added here (note in javadoc). Bukkit probe target names
(e.g. "io.papermc.paper.threadedregions.RegionizedServer") move to a
`platform/probe/ProbeTarget.java` enum with `className()` — Capabilities.detect() reads it.

### P1 acceptance
`./gradlew build` green (all tests pass; test sources' imports updated). `grep -rn
"SRC_\|TX_DEPOSIT\|MEMBERS_ALL\|SCOPE_SERVER" kernel core --include=*.java` → only enum
definitions. No file in kernel/intent, kernel/effect > 250 lines.

## P2 — monolith splits (two parallel agents, disjoint)

### P2a — `:kernel` reducer split (owns kernel/reduce/** + kernel tests it must touch)
- `Reducer.java` becomes a ≤120-line dispatcher: exhaustive switch on the intent
  SUB-INTERFACE delegating to per-domain reducers; signature `apply(KernelState,
  IntentEnvelope)` unchanged.
- New package-private classes in reduce/: `LifecycleReducer, MembershipReducer, RoleReducer,
  ClaimReducer, RelationReducer, PowerReducer, EconomyReducer, TravelReducer, ChestReducer,
  PrefReducer, SessionReducer, SystemReducer` — each a utility class of static methods,
  MOVED code (no behavior change), ≤~300 lines each; shared helpers (effect-buffer
  utilities, envelope rng, common lookups currently private in Reducer) go to
  `ReduceSupport.java` (package-private).
- Keep every existing comment; add per-class javadoc headers (owning thread = fable-kernel
  writer; pure).

### P2b — `:core` journal/storage split (owns core/journal/**, core/storage/**, core tests it must touch)
- `journal/EffectTag.java`: enum with EXPLICIT u16 codes for every journaled effect record,
  grouped in per-domain ranges (0x0100 lifecycle, 0x0200 membership, 0x0300 role,
  0x0400 claim, 0x0500 relation, 0x0600 power, 0x0700 economy, 0x0800 travel,
  0x0900 chest, 0x0A00 pref, 0x0B00 session, 0x0C00 audit/system, 0x0D00 feedback,
  0x0E00 external). MUST preserve the CURRENT tag numbers if any journal format test pins
  them; otherwise renumber into these ranges NOW (pre-beta, no deployed journals — note the
  renumbering in the commit).
- Split `JournalCodec.java` (883) → `journal/JournalCodec.java` (framing, dispatch,
  completeness check, ≤200) + `journal/codec/` per-domain codecs (`LifecycleCodec`,
  `ClaimCodec`, …) each owning encode+decode for its EffectTag range + a shared
  `journal/codec/Wire.java` (primitive/UUID/string/array read-write helpers).
- Split `StorageProjector.java` (472) → orchestrator (flush loop, tx, checkpoint, acks,
  ≤250) + `storage/project/` per-domain appliers (`ClaimProjection`, `FactionProjection`,
  `MemberProjection`, `EconomyProjection`, `HistoryProjection`, `ChestProjection`,
  `SessionProjection`) each `void apply(Connection, SqlDialect, XxxEffect)`.
- Move `BaselineLoader` → `storage/load/BaselineLoader.java` (orchestrator ≤200) +
  `storage/load/` per-table readers (`FactionRows`, `MemberRows`, `BoardRows`,
  `AncillaryRows` — warps/invites/chests/ranks/inbox/escrows).
- Move `the (removed) legacy importer` → `storage/legacy/the (removed) legacy importer.java` (orchestrator) +
  `storage/legacy/` readers/sanitizers (`LegacyBoardReader`, `LegacyJsonBlobs`,
  `LegacySanitizer`).
- Update ArchUnit rules if package moves affect them (java.sql allowed in storage.* —
  verify subpackages covered).

### P2 acceptance
`./gradlew build` green. No file in reduce/, journal/, storage/ > 500 lines (Reducer
dispatcher ≤120; report actual sizes).

## P3 — conventions sweep (three parallel agents: kernel, platform+compat+api, core; no structural moves)

Checklist per module (fix in place, note each category count):
1. Javadoc headers: every class states owning thread(s) + mutability class (CONTRACTS rule);
   add where missing.
2. No `*/` sequences inside javadoc/comment PROSE (the PowerMath trap) — reword.
3. No byte/short record components (grep-verify), no ordinals used as wire/DB codes
   (every enum persisted via explicit code()).
4. No raw FQN strings outside CompatClass/ProbeTarget; no magic string keys duplicated
   inline (message keys via MessageKey/ReasonCode; SQL only in storage; config paths —
   defer to W3d's ConfigKeys, but flag any already-scattered literals in the report).
5. Import order (java, javax, third-party, dev.fablemc), no wildcard imports, no unused
   imports; UTF-8 prose punctuation allowed but no invisible/bidi chars.
6. Naming: no abbreviations in public API (cfg→config, mgr→manager); constants
   UPPER_SNAKE; test names behavior-descriptive.
7. `final` fields where possible in non-record classes; no public mutable fields.
8. Dead code / unused package-private members deleted (compiler + IDE-less grep pass).
9. TODO/FIXME: each must name its wave (e.g. `// W4:`) or be resolved.
10. Hot-path files (Verdicts, ClaimAtlas, RegionTable, PlayerLedger, MemberDirectory,
    RelationEdges): verify zero streams/iterators/boxing; document any allocation with a
    justification comment.

### P3 acceptance
`./gradlew build` green; per-module report of changes by category.

## Post-refactor (Fable)
CONTRACTS.md §7 gains: layout pointer to this doc, enum-first rule, ≤400-line guideline,
CompatClass/ProbeTarget/EffectTag/ConfigKeys requirements for Wave 3+ agents.
