package dev.fablemc.factions.kernel.rules;

import java.util.UUID;

import dev.fablemc.factions.kernel.state.Faction;
import dev.fablemc.factions.kernel.state.FactionClaimList;
import dev.fablemc.factions.kernel.state.Home;
import dev.fablemc.factions.kernel.state.Rank;

/**
 * Whole-record copy-with helpers for the immutable {@link Faction} value (it carries no wither
 * methods). Every faction-scoped mutation the reducer makes goes through one of these, keeping
 * the 26-field reconstruction in one audited place.
 *
 * <p><b>Owning thread(s):</b> the reducer only. <b>Mutability:</b> stateless — each method returns
 * a fresh {@link Faction}. <b>Reducer rule:</b> clone-then-wrap-in-new-record (AM-8); the arena
 * then {@code replace}s the slot.
 */
public final class FactionEdit {

    private FactionEdit() {
    }

    public static Faction withName(Faction f, String name, String nameFolded, String tagLegacy,
                                   String tagMini) {
        return new Faction(f.idx(), f.id(), name, nameFolded, f.ownerId(), f.description(), f.motd(),
                f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(), f.relOutKind(),
                f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(), f.shieldDurationHours(),
                f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(), f.powerCacheTick(),
                f.claims(), tagLegacy, tagMini);
    }

    public static Faction withDescription(Faction f, String description) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), description,
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withMotd(Faction f, String motd) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                motd, f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withOwner(Faction f, UUID ownerId) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), ownerId, f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withBank(Faction f, double bank) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), bank, f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withFlagBits(Faction f, long flagBits) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), flagBits, f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withRelations(Faction f, int[] relOut, byte[] relOutKind, int[] relEff,
                                        byte[] relEffKind) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), relOut, relOutKind,
                relEff, relEffKind, f.home(), f.shieldStartHour(), f.shieldDurationHours(),
                f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(), f.powerCacheTick(),
                f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withHome(Faction f, Home home) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), home, f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withShield(Faction f, int startHour, int durationHours) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), startHour, durationHours,
                f.raidable(), f.ranks(), f.landCount(), f.powerCacheSum(), f.powerCacheTick(),
                f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withRaidable(Faction f, boolean raidable) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), raidable, f.ranks(), f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withRanks(Faction f, Rank[] ranks) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), ranks, f.landCount(), f.powerCacheSum(),
                f.powerCacheTick(), f.claims(), f.tagLegacy(), f.tagMini());
    }

    public static Faction withLand(Faction f, int landCount, FactionClaimList claims) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), landCount, f.powerCacheSum(),
                f.powerCacheTick(), claims, f.tagLegacy(), f.tagMini());
    }

    public static Faction withPowerCache(Faction f, double powerCacheSum, long powerCacheTick) {
        return new Faction(f.idx(), f.id(), f.name(), f.nameFolded(), f.ownerId(), f.description(),
                f.motd(), f.createdAt(), f.powerBoost(), f.bank(), f.flagBits(), f.relOut(),
                f.relOutKind(), f.relEff(), f.relEffKind(), f.home(), f.shieldStartHour(),
                f.shieldDurationHours(), f.raidable(), f.ranks(), f.landCount(), powerCacheSum,
                powerCacheTick, f.claims(), f.tagLegacy(), f.tagMini());
    }
}
