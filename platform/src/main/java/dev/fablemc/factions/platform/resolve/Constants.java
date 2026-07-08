package dev.fablemc.factions.platform.resolve;

import org.bukkit.GameMode;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.Probes;

/**
 * Every post-1.7.10 Bukkit enum constant a factions plugin touches, resolved ONCE at
 * class load via {@link Probes#enumConstant} into a nullable static (CONTRACTS §3,
 * AM-16, version-deltas GAP 2). A direct {@code getstatic} of a sub-floor enum constant
 * is a <b>sticky</b> {@code NoSuchFieldError} re-thrown on every event; these fields
 * flow through {@code Enum.valueOf} so an absent constant is a plain {@code null} that
 * callers guard, never a crash.
 *
 * <p>The enum TYPES here are all floor-present (per {@code scripts/floor-symbols.txt});
 * only the CONSTANTS are post-floor. Reading e.g. {@link #SPECTATOR} is a getstatic of
 * THIS class's field, never of {@code GameMode.SPECTATOR}, so no sticky hazard reaches
 * the caller.
 *
 * <p>Owning thread(s): any (final fields). Mutability class: static-only, immutable.
 */
public final class Constants {

    /** {@code GameMode.SPECTATOR} — 1.8+, {@code null} on 1.7.10. */
    public static final @Nullable GameMode SPECTATOR = Probes.enumConstant(GameMode.class, "SPECTATOR");

    /** {@code DamageCause.ENTITY_SWEEP_ATTACK} — 1.11+, {@code null} below. */
    public static final @Nullable EntityDamageEvent.DamageCause ENTITY_SWEEP_ATTACK =
            Probes.enumConstant(EntityDamageEvent.DamageCause.class, "ENTITY_SWEEP_ATTACK");

    /** {@code DamageCause.FLY_INTO_WALL} — 1.9+, {@code null} below. */
    public static final @Nullable EntityDamageEvent.DamageCause FLY_INTO_WALL =
            Probes.enumConstant(EntityDamageEvent.DamageCause.class, "FLY_INTO_WALL");

    /** {@code DamageCause.HOT_FLOOR} — 1.10+, {@code null} below. */
    public static final @Nullable EntityDamageEvent.DamageCause HOT_FLOOR =
            Probes.enumConstant(EntityDamageEvent.DamageCause.class, "HOT_FLOOR");

    /** {@code TeleportCause.CHORUS_FRUIT} — 1.9+, {@code null} below. */
    public static final @Nullable PlayerTeleportEvent.TeleportCause CHORUS_FRUIT =
            Probes.enumConstant(PlayerTeleportEvent.TeleportCause.class, "CHORUS_FRUIT");

    /** {@code TeleportCause.END_GATEWAY} — 1.9+, {@code null} below. */
    public static final @Nullable PlayerTeleportEvent.TeleportCause END_GATEWAY =
            Probes.enumConstant(PlayerTeleportEvent.TeleportCause.class, "END_GATEWAY");

    /** {@code ClickType.SWAP_OFFHAND} — 1.16+, {@code null} below. */
    public static final @Nullable ClickType SWAP_OFFHAND = Probes.enumConstant(ClickType.class, "SWAP_OFFHAND");

    /** {@code ClickType.HOTBAR_SWAP} — the number-key swap; {@code null} where absent. */
    public static final @Nullable ClickType HOTBAR_SWAP = Probes.enumConstant(ClickType.class, "HOTBAR_SWAP");

    /** {@code EquipmentSlot.OFF_HAND} — 1.9+, {@code null} below (the type is floor-present). */
    public static final @Nullable EquipmentSlot OFF_HAND = Probes.enumConstant(EquipmentSlot.class, "OFF_HAND");

    /** {@code SpawnReason.RAID} — 1.14+, {@code null} below. */
    public static final @Nullable CreatureSpawnEvent.SpawnReason RAID =
            Probes.enumConstant(CreatureSpawnEvent.SpawnReason.class, "RAID");

    private Constants() {}

    /** Whether {@code slot} is the resolved off-hand slot (never true when off-hand is absent). */
    public static boolean isOffHand(@Nullable EquipmentSlot slot) {
        return OFF_HAND != null && OFF_HAND == slot;
    }

    /** Whether {@code click} is an off-hand / hotbar swap into an inventory (1.9+ GUI hazard, AM-16). */
    public static boolean isSwapClick(@Nullable ClickType click) {
        return click != null && (click == SWAP_OFFHAND || click == HOTBAR_SWAP);
    }

    /** For the boot report: which post-floor constants resolved on this server. */
    public static String describe() {
        return "spectator=" + (SPECTATOR != null) + " sweep=" + (ENTITY_SWEEP_ATTACK != null)
                + " offHand=" + (OFF_HAND != null) + " swapOffHand=" + (SWAP_OFFHAND != null)
                + " raid=" + (RAID != null);
    }
}
