package dev.fablemc.factions.core.command.merge;

import java.util.List;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.admin.CommandKit;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f merge [send|accept]} — the faction-merge group (ref-commands-misc.md §4). The bare
 * command prints the two-line help; {@code send} and {@code accept} pre-validate the merge feature
 * gate, the officer guard, the named counterpart, and self-merge, then submit a
 * {@link LifecycleIntent.SendMergeRequest} / {@link LifecycleIntent.AcceptMergeRequest}. The reducer
 * runs the duplicate/no-request checks and the paged absorb, emitting the notifications, so a merge
 * command never sends a success message (CONTRACTS §6.4).
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a group node configured once at construction.
 */
public final class CmdMerge extends CommandNode {

    private static final MessageKey HELP_TITLE = MessageKey.of("custom.merge.help-title");
    private static final MessageKey HELP_SEND = MessageKey.of("custom.merge.help-send");
    private static final MessageKey HELP_ACCEPT = MessageKey.of("custom.merge.help-accept");
    private static final MessageKey TARGET_NOT_FOUND = MessageKey.of("merge.target-not-found");

    /** Builds the merge group with its {@code send} and {@code accept} children. */
    public CmdMerge() {
        super("merge");
        setPermission("factions.cmd.merge");
        setRequiresPlayer(true);
        setDescription("Send or accept a faction merge.");
        setOptionalArgs("send|accept");
        addChild(new CmdMergeSend());
        addChild(new CmdMergeAccept());
    }

    @Override
    protected void perform(CommandContext ctx) {
        ctx.send(HELP_TITLE);
        ctx.send(HELP_SEND);
        ctx.send(HELP_ACCEPT);
    }

    /** The actor's own faction handle after the merge-enabled + officer guards, or -1 on rejection. */
    private static int mergeActorHandle(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        if (!snap.config().mergeEnabled()) {
            ctx.sendReason(ReasonCode.MERGE_DISABLED);
            return -1;
        }
        UUID actor = ctx.player().getUniqueId();
        ReasonCode notInFaction = CommandGuards.requireFaction(snap, actor);
        if (notInFaction != null) {
            ctx.sendReason(notInFaction);
            return -1;
        }
        ReasonCode notOfficer = CommandGuards.requireOfficerOrAbove(snap, actor);
        if (notOfficer != null) {
            ctx.sendReason(notOfficer);
            return -1;
        }
        return CommandKit.handleOf(snap, CommandGuards.factionOf(snap, actor));
    }

    /**
     * {@code /f merge send <faction>} — request to merge the sender's faction into {@code faction}.
     */
    static final class CmdMergeSend extends CommandNode {

        CmdMergeSend() {
            super("send");
            setPermission("factions.cmd.merge");
            setRequiresPlayer(true);
            setDescription("Send a merge request.");
            setRequiredArgs("faction");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int mine = mergeActorHandle(ctx);
            if (mine < 0) {
                return;
            }
            KernelSnapshot snap = ctx.snap();
            Faction target = ArgParsers.factionByName(snap, ctx.arg(0));
            if (target == null) {
                ctx.send(TARGET_NOT_FOUND, ctx.argOrEmpty(0));
                return;
            }
            int targetHandle = CommandKit.handleOf(snap, target);
            if (targetHandle == mine) {
                ctx.sendReason(ReasonCode.MERGE_SELF);
                return;
            }
            CommandKit.submit(ctx, new LifecycleIntent.SendMergeRequest(mine, targetHandle,
                    ctx.player().getUniqueId()), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0)) : List.of();
        }
    }

    /**
     * {@code /f merge accept <faction>} — accept a merge from the SENDER faction {@code faction}.
     */
    static final class CmdMergeAccept extends CommandNode {

        CmdMergeAccept() {
            super("accept");
            setPermission("factions.cmd.merge");
            setRequiresPlayer(true);
            setDescription("Accept a merge request.");
            setRequiredArgs("faction");
        }

        @Override
        protected void perform(CommandContext ctx) {
            int mine = mergeActorHandle(ctx);
            if (mine < 0) {
                return;
            }
            KernelSnapshot snap = ctx.snap();
            Faction sender = ArgParsers.factionByName(snap, ctx.arg(0));
            if (sender == null) {
                ctx.send(TARGET_NOT_FOUND, ctx.argOrEmpty(0));
                return;
            }
            CommandKit.submit(ctx, new LifecycleIntent.AcceptMergeRequest(
                    CommandKit.handleOf(snap, sender), mine, ctx.player().getUniqueId()),
                    CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0)) : List.of();
        }
    }
}
