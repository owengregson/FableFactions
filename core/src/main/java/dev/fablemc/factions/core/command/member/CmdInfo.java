package dev.fablemc.factions.core.command.member;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.fablemc.factions.core.command.ArgParsers;
import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.rules.FactionAggregates;
import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.KernelSnapshot;
import dev.fablemc.factions.kernel.state.MemberView;
import dev.fablemc.factions.kernel.state.RelationKind;

/**
 * {@code /f info [name]} (aliases {@code i}, {@code show}) — the faction summary card
 * (ref-commands-core.md §7.29). Open (no permission node) and console-safe; a pure snapshot render
 * of members / power / land / bank / home / allies.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread.
 * <b>Mutability:</b> configured once at construction.
 */
final class CmdInfo extends CommandNode {

    private static final MessageKey HEADER = MessageKey.of("info.header");
    private static final MessageKey LEADER = MessageKey.of("info.leader");
    private static final MessageKey MEMBERS = MessageKey.of("info.members");
    private static final MessageKey POWER = MessageKey.of("info.power");
    private static final MessageKey LAND = MessageKey.of("info.land");
    private static final MessageKey BALANCE = MessageKey.of("info.balance");
    private static final MessageKey HOME = MessageKey.of("info.home");
    private static final MessageKey ALLIES = MessageKey.of("info.allies");
    private static final MessageKey DESCRIPTION = MessageKey.of("info.description");
    private static final MessageKey USAGE = MessageKey.of("general.invalid-args");

    CmdInfo() {
        super("info", "i", "show");
        setDescription("Show faction information");
        setOptionalArgs("name");
    }

    @Override
    protected void perform(CommandContext ctx) {
        KernelSnapshot snap = ctx.snap();
        Faction faction;
        if (ctx.argCount() > 0) {
            faction = ArgParsers.factionByName(snap, ctx.arg(0));
        } else if (ctx.isPlayer()) {
            faction = CommandGuards.factionOf(snap, ctx.player().getUniqueId());
        } else {
            ctx.send(USAGE, getUsage());
            return;
        }
        if (faction == null) {
            ctx.sendReason(ReasonCode.FACTION_NOT_FOUND, ctx.argOrEmpty(0));
            return;
        }
        render(ctx, snap, faction);
    }

    private static void render(CommandContext ctx, KernelSnapshot snap, Faction faction) {
        int handle = CommandFlow.handleOf(snap, faction);
        int members = FactionAggregates.memberCount(snap.state(), handle);
        int maxMembers = snap.config().limits().maxMembers();
        double totalPower = FactionAggregates.totalPower(snap.state(), faction, snap.tick(),
                System.currentTimeMillis());
        double maxPower = members * snap.config().power().maxPower();

        ctx.send(HEADER, faction.name());
        ctx.send(LEADER, leaderName(snap, faction.ownerId()));
        ctx.send(MEMBERS, Integer.toString(members), Integer.toString(maxMembers));
        ctx.send(POWER, CommandFlow.fmt1(totalPower), CommandFlow.fmt1(maxPower));
        ctx.send(LAND, Integer.toString(faction.landCount()));
        ctx.send(BALANCE, CommandFlow.money(faction.bank()));
        ctx.send(HOME, homeText(faction.home()));
        ctx.send(ALLIES, Integer.toString(allyCount(faction)));
        if (faction.description() != null && !faction.description().isBlank()) {
            ctx.send(DESCRIPTION, faction.description());
        }
    }

    private static String leaderName(KernelSnapshot snap, UUID owner) {
        if (owner == null) {
            return "Unknown";
        }
        int ordinal = snap.memberOrdinal(owner);
        if (ordinal >= 0) {
            MemberView member = snap.member(ordinal);
            if (member != null && member.nameLast() != null) {
                return member.nameLast();
            }
        }
        return owner.toString();
    }

    private static String homeText(Home home) {
        if (home == null) {
            return "Not set";
        }
        return "(" + CommandFlow.fmt1(home.x()) + ", " + CommandFlow.fmt1(home.y())
                + ", " + CommandFlow.fmt1(home.z()) + ")";
    }

    private static int allyCount(Faction faction) {
        byte[] kinds = faction.relEffKind();
        int count = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == RelationKind.ALLY) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected List<String> complete(CommandContext ctx, int argIndex) {
        return argIndex == 0
                ? Completions.factionNames(ctx.snap(), ctx.argOrEmpty(0).toLowerCase(Locale.ROOT))
                : List.of();
    }
}
