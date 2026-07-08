package dev.fablemc.factions.core.listen;

import org.bukkit.Location;

/**
 * The WorldGuard-sync seam consulted by {@link BuildProtectionListener} and
 * {@link AllyUnlockListener} (proposal-C §8.1, ref-engines.md §3.1.1/§3.1.2). When a WorldGuard
 * bridge is present and configured to mirror faction claims as regions, build protection is
 * enforced by WorldGuard at {@code NORMAL} priority and our {@code HIGH} handler only needs to
 * <em>allow</em> inside a mirrored region; when it is absent this seam reports no sync and the
 * build handler falls back to the pure-DB {@code Verdicts} path.
 *
 * <p><b>Owning thread(s):</b> {@link #isFactionRegion} is called from a build handler on the
 * region/main thread of the event; {@link #syncsBuildProtection} is a boot-resolved constant read
 * on the boot thread (it gates whether {@link AllyUnlockListener} registers at all).
 * <b>Mutability:</b> implementations are immutable after construction.
 *
 * <p>Wave 4 wires the WorldGuard-backed implementation; until then {@link #NONE} keeps every server
 * in pure-DB mode (no sync, no ally-unlock listener) — the reference-default behavior.
 */
public interface TerritorySync {

    /** The no-sync default: pure-DB build protection, no WorldGuard mirroring, no ally unlock. */
    TerritorySync NONE = new TerritorySync() {
        @Override
        public boolean syncsBuildProtection() {
            return false;
        }

        @Override
        public boolean isFactionRegion(Location location) {
            return false;
        }
    };

    /** Whether faction claims are mirrored to WorldGuard regions (the ally-unlock registration gate). */
    boolean syncsBuildProtection();

    /** Whether {@code location} sits inside a WorldGuard region mirroring a faction claim. */
    boolean isFactionRegion(Location location);
}
