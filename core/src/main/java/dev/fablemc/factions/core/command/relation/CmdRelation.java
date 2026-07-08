package dev.fablemc.factions.core.command.relation;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.admin.CommandKit;
import dev.fablemc.factions.kernel.intent.RelationIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionArena;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.RelationKind;
import dev.fablemc.factions.kernel.vocab.Relation;

/**
 * {@code /f relation <faction> <ally|truce|neutral|enemy>} plus the {@code list} and {@code wishes}
 * children (ref-commands-admin.md §6). The parent declares a relation wish toward another faction:
 * it pre-validates rank / target / self / relation kind and submits a
 * {@link RelationIntent.DeclareRelation}; the reducer runs the ally/truce-limit and mutual-consent
 * state machine and emits the announcement + per-faction notifications, so this command never sends
 * a success message (CONTRACTS §6.4).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread — snapshot reads +
 * one intent submit only. <b>Mutability:</b> configured once at construction (a group node), then
 * effectively immutable.
 */
public final class CmdRelation extends CommandNode {

    private static final String[] RELATION_WORDS = {"ally", "truce", "neutral", "enemy"};

    /** Builds the relation group with its {@code list} and {@code wishes} children. */
    public CmdRelation() {
        super("relation", "relationship");
        setPermission("factions.cmd.relation");
        setRequiresPlayer(true);
        setDescription("Set your faction's relation toward another faction.");
        setRequiredArgs("faction", "relation");
        addChild(new CmdRelationList());
        addChild(new CmdRelationWishes());
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        UUID actor = ctx.player().getUniqueId();
        ReasonCode notInFaction = CommandGuards.requireFaction(snap, actor);
        if (notInFaction != null) {
            ctx.sendReason(notInFaction);
            return;
        }
        ReasonCode notOfficer = CommandGuards.requireOfficerOrAbove(snap, actor);
        if (notOfficer != null) {
            ctx.sendReason(notOfficer);
            return;
        }
        Faction mine = CommandGuards.factionOf(snap, actor);
        Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
        if (target == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        if (mine.idx() == target.idx()) {
            ctx.sendReason(ReasonCode.RELATION_SELF);
            return;
        }
        Relation relation = ArgParsers.parseRelation(ctx.arg(1));
        if (relation == null || relation == Relation.MEMBER) {
            ctx.sendReason(ReasonCode.RELATION_SET_FAILED);
            return;
        }
        CommandKit.submit(ctx, new RelationIntent.DeclareRelation(
                CommandKit.handleOf(snap, mine), CommandKit.handleOf(snap, target), relation, actor),
                CommandKit.playerOrigin(ctx.player()));
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        if (argIndex == 0) {
            return Completions.factionNames(ctx.snap(), ctx.argOrEmpty(argIndex));
        }
        if (argIndex == 1) {
            return Completions.matching(RELATION_WORDS, ctx.argOrEmpty(argIndex));
        }
        return List.of();
    }

    /** A capitalized display name for {@code relation} (e.g. {@code "Ally"}). */
    static String displayName(Relation relation) {
        String lower = relation.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * {@code /f relation list [ally|truce|neutral|enemy]} — the declared-relation table with each
     * entry's mutual/pending/active status (ref-commands-admin.md §6.6). A pure snapshot read.
     */
    static final class CmdRelationList extends CommandNode {

        private static final MessageKey HEADER = MessageKey.of("relations.list-header");
        private static final MessageKey ENTRY = MessageKey.of("relations.list-entry");
        private static final MessageKey EMPTY = MessageKey.of("relations.list-empty");

        CmdRelationList() {
            super("list");
            setPermission("factions.cmd.relation");
            setRequiresPlayer(true);
            setDescription("List your faction's relations.");
            setOptionalArgs("relation");
        }

        @Override
        protected void perform(CommandContext ctx) {
            KernelSnapshot snap = ctx.snap();
            Faction mine = CommandGuards.factionOf(snap, ctx.player().getUniqueId());
            if (mine == null) {
                ctx.sendReason(ReasonCode.NOT_IN_FACTION);
                return;
            }
            Relation filter = ArgParsers.parseRelation(ctx.arg(0));
            int[] out = mine.relOut();
            byte[] kinds = mine.relOutKind();
            FactionArena arena = snap.state().factions();
            ctx.send(HEADER);
            int shown = 0;
            for (int i = 0; i < out.length; i++) {
                byte kind = kinds[i];
                if (filter != null && kind != filter.code()) {
                    continue;
                }
                Faction other = arena.at(out[i]);
                String targetName = other != null ? other.name() : "unknown";
                String status = statusOf(mine, other, kind);
                ctx.send(ENTRY, targetName, displayName(Relation.fromCode(kind)), status);
                shown++;
            }
            if (shown == 0) {
                ctx.send(EMPTY);
            }
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0
                    ? Completions.matching(RELATION_WORDS, ctx.argOrEmpty(0)) : List.of();
        }

        private static String statusOf(Faction mine, Faction other, byte kind) {
            if (other == null) {
                return "unknown";
            }
            if (kind != RelationKind.ALLY && kind != RelationKind.TRUCE) {
                return "active";
            }
            return other.relationDeclared(mine.idx()) == kind ? "mutual" : "pending";
        }
    }

    /**
     * {@code /f relation wishes} — a pointer to {@code /f relation list} when any wish is set, else a
     * "none set" line (ref-commands-admin.md §6.7). A pure snapshot read.
     */
    static final class CmdRelationWishes extends CommandNode {

        private static final MessageKey NONE = MessageKey.of("relations.wishes-none");
        private static final MessageKey SET = MessageKey.of("relations.wishes-set");

        CmdRelationWishes() {
            super("wishes");
            setPermission("factions.cmd.relation");
            setRequiresPlayer(true);
            setDescription("Show whether relation wishes are set.");
        }

        @Override
        protected void perform(CommandContext ctx) {
            Faction mine = CommandGuards.factionOf(ctx.snap(), ctx.player().getUniqueId());
            if (mine == null) {
                ctx.sendReason(ReasonCode.NOT_IN_FACTION);
                return;
            }
            ctx.send(mine.relOut().length == 0 ? NONE : SET);
        }
    }
}
