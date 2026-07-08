package dev.fablemc.factions.core.command.predefined;

import java.util.List;
import java.util.Objects;

import org.bukkit.Location;

import dev.fablemc.factions.core.command.CommandContext;
import dev.fablemc.factions.core.command.CommandGuards;
import dev.fablemc.factions.core.command.CommandNode;
import dev.fablemc.factions.core.command.Completions;
import dev.fablemc.factions.core.command.admin.CommandKit;
import dev.fablemc.factions.kernel.config.PredefinedConfig;
import dev.fablemc.factions.kernel.intent.LifecycleIntent;
import dev.fablemc.factions.kernel.msg.MessageKey;
import dev.fablemc.factions.kernel.msg.ReasonCode;
import dev.fablemc.factions.kernel.state.KernelSnapshot;

/**
 * {@code /f predefined …} — the predefined-faction admin group (ref-commands-misc.md §6):
 * create / claim / sethome / reload / list. {@code create} submits a {@code CreateFaction} intent
 * whose reducer seeds the preset once; {@code claim} / {@code sethome} / {@code reload} edit
 * {@code pre-defined.yml} through the {@link Predefined} seam; {@code list} reads the presets from
 * the snapshot config. All but {@code reload} require the subsystem enabled.
 *
 * <p><b>Owning thread(s):</b> {@code perform} on the sender's region/main thread. <b>Mutability:</b>
 * a group node configured once at construction, holding the injected {@link Predefined} seam.
 */
public final class CmdPredefined extends CommandNode {

    private static final MessageKey UNKNOWN = MessageKey.of("predefined.unknown");

    /** Builds the predefined group with all five children, injecting the authoring seam. */
    public CmdPredefined(Predefined authoring) {
        super("predefined", "prefined");
        setPermission("factions.cmd.predefined");
        setDescription("Manage predefined factions.");
        Predefined seam = Objects.requireNonNull(authoring, "authoring");
        addChild(new CmdPredefinedCreate());
        addChild(new CmdPredefinedClaim(seam));
        addChild(new CmdPredefinedSethome(seam));
        addChild(new CmdPredefinedReload(seam));
        addChild(new CmdPredefinedList());
    }

    /** {@code false} (after sending {@code predefined.disabled}) unless the subsystem is enabled. */
    private static boolean requireEnabled(CommandContext ctx) {
        if (!ctx.snap().config().predefined().enabled()) {
            ctx.sendReason(ReasonCode.PREDEFINED_DISABLED);
            return false;
        }
        return true;
    }

    private static List<String> presetCompletions(CommandContext ctx) {
        PredefinedConfig cfg = ctx.snap().config().predefined();
        PredefinedConfig.Preset[] presets = cfg.presets();
        String[] names = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            names[i] = presets[i].name();
        }
        return Completions.matching(names, ctx.argOrEmpty(0));
    }

    /**
     * {@code /f predefined create <faction>} — create an allowed predefined faction (§6.5).
     */
    static final class CmdPredefinedCreate extends CommandNode {

        CmdPredefinedCreate() {
            super("create");
            setPermission("factions.cmd.predefined.create");
            setRequiresPlayer(true);
            setDescription("Create a predefined faction.");
            setRequiredArgs("faction");
        }

        @Override
        protected void perform(CommandContext ctx) {
            if (!requireEnabled(ctx)) {
                return;
            }
            KernelSnapshot snap = ctx.snap();
            if (CommandGuards.factionOf(snap, ctx.player().getUniqueId()) != null) {
                ctx.sendReason(ReasonCode.ALREADY_IN_FACTION);
                return;
            }
            if (!snap.config().predefined().isPredefinedName(ctx.arg(0))) {
                ctx.send(UNKNOWN, ctx.argOrEmpty(0));
                return;
            }
            CommandKit.submit(ctx, new LifecycleIntent.CreateFaction(ctx.arg(0),
                    ctx.player().getUniqueId()), CommandKit.playerOrigin(ctx.player()));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? presetCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f predefined claim <faction>} — record the current chunk as a preset claim (§6.6).
     */
    static final class CmdPredefinedClaim extends CommandNode {

        private static final MessageKey SAVED = MessageKey.of("predefined.claim-saved");

        private final Predefined seam;

        CmdPredefinedClaim(Predefined seam) {
            super("claim");
            this.seam = seam;
            setPermission("factions.cmd.predefined.claim");
            setRequiresPlayer(true);
            setDescription("Save the current chunk as a predefined claim.");
            setRequiredArgs("faction");
        }

        @Override
        protected void perform(CommandContext ctx) {
            if (!requireEnabled(ctx)) {
                return;
            }
            if (!ctx.snap().config().predefined().isPredefinedName(ctx.arg(0))) {
                ctx.send(UNKNOWN, ctx.argOrEmpty(0));
                return;
            }
            Location loc = ctx.player().getLocation();
            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            seam.saveClaim(ctx.arg(0), loc.getWorld(), chunkX, chunkZ);
            ctx.send(SAVED, ctx.arg(0), Integer.toString(chunkX), Integer.toString(chunkZ));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? presetCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f predefined sethome <faction>} — record the current location as the preset home (§6.7).
     */
    static final class CmdPredefinedSethome extends CommandNode {

        private static final MessageKey SAVED = MessageKey.of("predefined.home-saved");

        private final Predefined seam;

        CmdPredefinedSethome(Predefined seam) {
            super("sethome");
            this.seam = seam;
            setPermission("factions.cmd.predefined.sethome");
            setRequiresPlayer(true);
            setDescription("Save the current location as a predefined home.");
            setRequiredArgs("faction");
        }

        @Override
        protected void perform(CommandContext ctx) {
            if (!requireEnabled(ctx)) {
                return;
            }
            if (!ctx.snap().config().predefined().isPredefinedName(ctx.arg(0))) {
                ctx.send(UNKNOWN, ctx.argOrEmpty(0));
                return;
            }
            seam.saveHome(ctx.arg(0), ctx.player().getLocation());
            ctx.send(SAVED, ctx.arg(0));
        }

        @Override
        protected List<String> complete(CommandContext ctx, int argIndex) {
            return argIndex == 0 ? presetCompletions(ctx) : List.of();
        }
    }

    /**
     * {@code /f predefined reload} — reparse {@code pre-defined.yml} (console-allowed, §6.9).
     */
    static final class CmdPredefinedReload extends CommandNode {

        private static final MessageKey SUCCESS = MessageKey.of("predefined.reload-success");
        private static final MessageKey FAILED = MessageKey.of("predefined.reload-failed");

        private final Predefined seam;

        CmdPredefinedReload(Predefined seam) {
            super("reload");
            this.seam = seam;
            setPermission("factions.cmd.predefined.reload");
            setDescription("Reload predefined factions from disk.");
        }

        @Override
        protected void perform(CommandContext ctx) {
            if (!seam.available()) {
                ctx.send(FAILED);
                return;
            }
            seam.reload();
            ctx.send(SUCCESS);
        }
    }

    /**
     * {@code /f predefined list} — the configured preset names (console-allowed, §6.8).
     */
    static final class CmdPredefinedList extends CommandNode {

        private static final MessageKey NONE = MessageKey.of("predefined.none");
        private static final MessageKey LIST = MessageKey.of("predefined.list");

        CmdPredefinedList() {
            super("list");
            setPermission("factions.cmd.predefined.list");
            setDescription("List predefined factions.");
        }

        @Override
        protected void perform(CommandContext ctx) {
            if (!requireEnabled(ctx)) {
                return;
            }
            PredefinedConfig.Preset[] presets = ctx.snap().config().predefined().presets();
            if (presets.length == 0) {
                ctx.send(NONE);
                return;
            }
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < presets.length; i++) {
                if (i > 0) {
                    names.append(", ");
                }
                names.append(presets[i].name());
            }
            ctx.send(LIST, names.toString());
        }
    }
}
