package dev.fablemc.factions.kernel.msg;

/**
 * Every rejection reason a reducer step may emit, each mapped 1:1 to a message key.
 *
 * <p><b>Owning thread(s):</b> produced by the writer inside {@code Rejected} effects, read by
 * the feedback fan-out on any thread. <b>Mutability:</b> immutable enum. <b>Reducer rule:</b>
 * the reducer chooses the code; the command layer never invents text (CONTRACTS §6.4) — it
 * renders {@link #messageKey()} with the effect's args.
 *
 * <p>The mapping below is normative: each constant's {@link #messageKey()} is the exact
 * reference message-catalog key (ref-resources.md §9) whose default English string the user
 * sees. Pre-validation (proposal-C §3.7) runs the same rule functions and must surface the
 * same key, so a lost TOCTOU race and a pre-checked failure read identically.
 */
public enum ReasonCode {

    // ── general ──────────────────────────────────────────────────────────────────────────
    /** {@code general.no-permission} — actor lacks the required permission. */
    NO_PERMISSION("general.no-permission"),
    /** {@code general.player-only} — a player-only action attempted by console/system. */
    PLAYER_ONLY("general.player-only"),
    /** {@code general.faction-not-found} — named faction does not exist. */
    FACTION_NOT_FOUND("general.faction-not-found"),
    /** {@code general.player-not-found} — named player does not exist. */
    PLAYER_NOT_FOUND("general.player-not-found"),
    /** {@code general.not-in-faction} — actor is factionless. */
    NOT_IN_FACTION("general.not-in-faction"),
    /** {@code general.must-be-leader} — action requires owner rank. */
    MUST_BE_LEADER("general.must-be-leader"),
    /** {@code general.must-be-officer} — action requires officer rank or above. */
    MUST_BE_OFFICER("general.must-be-officer"),
    /** {@code general.economy-disabled} — economy integration unavailable. */
    ECONOMY_DISABLED("general.economy-disabled"),
    /** A reducer step threw (AM-9): fail-safe rejection, state left at the pre-intent value. */
    INTERNAL_ERROR("general.internal-error"),
    /** Player intent lane is full (AM-9 / proposal-C §3.5). */
    BUSY("general.busy"),
    /** Plugin is shutting down; new player intents rejected (proposal-C §6.4). */
    SHUTTING_DOWN("general.shutting-down"),

    // ── faction lifecycle / naming ───────────────────────────────────────────────────────
    /** {@code faction.name-taken} — folded name already registered (NameIndex authority). */
    NAME_TAKEN("faction.name-taken"),
    /** {@code faction.name-too-short} — below the 3-character minimum. */
    NAME_TOO_SHORT("faction.name-too-short"),
    /** {@code faction.name-too-long} — above the 32-character maximum. */
    NAME_TOO_LONG("faction.name-too-long"),
    /** {@code faction.name-invalid} — outside {@code [A-Za-z0-9_-]} (D-5). */
    NAME_INVALID("faction.name-invalid"),
    /** {@code faction.already-in-faction} — actor already belongs to a faction. */
    ALREADY_IN_FACTION("faction.already-in-faction"),
    /** {@code faction.not-enough-money-create} — insufficient funds to create. */
    NOT_ENOUGH_MONEY_CREATE("faction.not-enough-money-create"),
    /** {@code custom.faction.desc-too-long} — description exceeds 250 chars. */
    DESCRIPTION_TOO_LONG("custom.faction.desc-too-long"),

    // ── membership / invites ─────────────────────────────────────────────────────────────
    /** {@code member.not-member} — target is not a member of the faction. */
    NOT_MEMBER("member.not-member"),
    /** {@code member.cannot-kick-self} — cannot kick yourself. */
    CANNOT_KICK_SELF("member.cannot-kick-self"),
    /** {@code member.cannot-kick-leader} — cannot kick the owner. */
    CANNOT_KICK_LEADER("member.cannot-kick-leader"),
    /** Member cap reached; join / accept blocked. */
    FACTION_FULL("member.faction-full"),
    /** {@code invite.already-invited} — a pending invite already exists. */
    ALREADY_INVITED("invite.already-invited"),
    /** {@code invite.not-invited} — no pending invite for this invitee. */
    NOT_INVITED("invite.not-invited"),
    /** {@code invite.no-invite-pending} — join attempted with no pending invite (non-OPEN). */
    NO_INVITE_PENDING("invite.no-invite-pending"),

    // ── claims / territory ───────────────────────────────────────────────────────────────
    /** {@code claim.already-claimed} — chunk already owned (non-overclaim path). */
    ALREADY_CLAIMED("claim.already-claimed"),
    /** {@code claim.not-your-land} — unclaim target is not owned by the actor's faction. */
    NOT_YOUR_LAND("claim.not-your-land"),
    /** {@code claim.not-enough-power} — faction is at its max-land cap. */
    NOT_ENOUGH_POWER("claim.not-enough-power"),
    /** {@code claim.no-border} — chunk does not border own territory or wilderness. */
    NO_BORDER("claim.no-border"),
    /** {@code claim.cannot-claim-safezone} — cannot normal-claim a system zone. */
    CANNOT_CLAIM_SAFEZONE("claim.cannot-claim-safezone"),
    /** {@code claim.enemy-not-raidable} — victim still holds enough power to defend. */
    ENEMY_NOT_RAIDABLE("claim.enemy-not-raidable"),
    /** {@code claim.enemy-offline-protected} — F5: all defenders offline. */
    ENEMY_OFFLINE_PROTECTED("claim.enemy-offline-protected"),
    /** {@code claim.shield-active} — F6: victim's war shield window is active. */
    SHIELD_ACTIVE("claim.shield-active"),
    /** {@code custom.warp.world-not-loaded} — destination world not loaded. */
    WORLD_NOT_LOADED("custom.warp.world-not-loaded"),

    // ── bank / economy ───────────────────────────────────────────────────────────────────
    /** {@code bank.insufficient-funds} — bank balance too low. */
    INSUFFICIENT_FUNDS("bank.insufficient-funds"),
    /** {@code bank.invalid-amount} — non-positive / unparseable amount. */
    INVALID_AMOUNT("bank.invalid-amount"),

    // ── power ────────────────────────────────────────────────────────────────────────────
    /** {@code power.buy-disabled} — power buying is disabled. */
    POWER_BUY_DISABLED("power.buy-disabled"),
    /** {@code power.buy-no-vault} — Vault required for power buying. */
    POWER_BUY_NO_VAULT("power.buy-no-vault"),
    /** {@code power.buy-invalid-amount} — buy amount ≤ 0 or above per-purchase cap. */
    POWER_BUY_INVALID_AMOUNT("power.buy-invalid-amount"),
    /** {@code power.buy-already-max} — player already at max power. */
    POWER_BUY_ALREADY_MAX("power.buy-already-max"),
    /** {@code power.buy-insufficient-funds} — cannot afford the purchase. */
    POWER_BUY_INSUFFICIENT_FUNDS("power.buy-insufficient-funds"),
    /** {@code power.blocked-frozen} — an automatic/regen source blocked by freeze. */
    POWER_FROZEN("power.blocked-frozen"),

    // ── relations ────────────────────────────────────────────────────────────────────────
    /** {@code relation.cannot-set-self} — cannot set a relation with your own faction. */
    RELATION_SELF("relation.cannot-set-self"),
    /** {@code relation.set-failed} — invalid relation or ally/truce limit reached. */
    RELATION_SET_FAILED("relation.set-failed"),

    // ── roles ────────────────────────────────────────────────────────────────────────────
    /** {@code custom.role.create-disabled} — role feature gates off. */
    ROLE_FEATURE_DISABLED("custom.role.create-disabled"),
    /** {@code custom.role.prefix-disabled} — prefix feature gates off. */
    ROLE_PREFIX_DISABLED("custom.role.prefix-disabled"),
    /** {@code custom.role.invalid-priority} — non-numeric priority. */
    ROLE_INVALID_PRIORITY("custom.role.invalid-priority"),
    /** {@code custom.role.priority-out-of-range} — priority outside [min,max]. */
    ROLE_PRIORITY_OUT_OF_RANGE("custom.role.priority-out-of-range"),
    /** {@code custom.role.actor-rank-insufficient} — cannot create/modify at/above own rank. */
    ROLE_ACTOR_RANK_INSUFFICIENT("custom.role.actor-rank-insufficient"),
    /** {@code custom.role.name-taken} — a role of that name already exists. */
    ROLE_NAME_TAKEN("custom.role.name-taken"),
    /** {@code custom.role.limit-reached} — custom role count cap reached. */
    ROLE_LIMIT_REACHED("custom.role.limit-reached"),
    /** {@code custom.role.create-failed} — generic role mutation failure. */
    ROLE_FAILED("custom.role.create-failed"),

    // ── flags ────────────────────────────────────────────────────────────────────────────
    /** {@code flag.invalid} — unknown flag id. */
    FLAG_INVALID("flag.invalid"),
    /** {@code flag.not-editable} — flag locked by the server administrator. */
    FLAG_NOT_EDITABLE("flag.not-editable"),

    // ── shield / merge / predefined ──────────────────────────────────────────────────────
    /** {@code shield.feature-disabled} — war shields not enabled. */
    SHIELD_FEATURE_DISABLED("shield.feature-disabled"),
    /** {@code shield.invalid-hour} — start hour outside 0..23. */
    SHIELD_INVALID_HOUR("shield.invalid-hour"),
    /** {@code shield.invalid-duration} — duration outside 1..max. */
    SHIELD_INVALID_DURATION("shield.invalid-duration"),
    /** {@code merge.disabled} — merging disabled. */
    MERGE_DISABLED("merge.disabled"),
    /** {@code merge.self-merge} — cannot merge a faction with itself. */
    MERGE_SELF("merge.self-merge"),
    /** {@code merge.already-requested} — a merge request already exists. */
    MERGE_ALREADY_REQUESTED("merge.already-requested"),
    /** {@code merge.no-request-found} — no matching merge request to accept. */
    MERGE_NO_REQUEST("merge.no-request-found"),
    /** {@code predefined.disabled} — predefined subsystem disabled. */
    PREDEFINED_DISABLED("predefined.disabled"),
    /** {@code predefined.disband-blocked} — predefined factions cannot be disbanded. */
    PREDEFINED_DISBAND_BLOCKED("predefined.disband-blocked"),
    /** {@code predefined.create-not-allowed} — creation restricted by a predefined preset. */
    PREDEFINED_CREATE_NOT_ALLOWED("predefined.create-not-allowed"),

    // ── travel / fly ─────────────────────────────────────────────────────────────────────
    /** {@code warp.not-found} — no warp of that name. */
    WARP_NOT_FOUND("warp.not-found"),
    /** {@code warp.limit-reached} — faction warp cap reached. */
    WARP_LIMIT_REACHED("warp.limit-reached"),
    /** {@code home.no-home} — faction has no home set. */
    NO_HOME("home.no-home"),
    /** {@code custom.fly.own-territory-required} — fly restricted to own territory. */
    FLY_OWN_TERRITORY_REQUIRED("custom.fly.own-territory-required"),
    /** {@code custom.fly.disabled-global} — fly globally disabled. */
    FLY_DISABLED_GLOBAL("custom.fly.disabled-global");

    private final MessageKey messageKey;

    ReasonCode(String messageKey) {
        this.messageKey = MessageKey.of(messageKey);
    }

    /** The message-catalog key this rejection maps to (1:1), interned once at class initialization. */
    public MessageKey messageKey() {
        return messageKey;
    }
}
