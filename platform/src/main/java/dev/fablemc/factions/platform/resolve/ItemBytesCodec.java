package dev.fablemc.factions.platform.resolve;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The item byte-codec SPI (CONTRACTS §3). The modern implementation
 * ({@code dev.fablemc.factions.compat.modern.ModernItemCodec}) uses Paper's
 * {@code ItemStack#serializeAsBytes}/{@code deserializeBytes} (which embed a DataVersion
 * and upgrade on read); it is compiled against the modern API and loaded by {@link ItemCodec}
 * via FQN string ONLY when the {@code serializeAsBytes} capability probes true.
 *
 * <p>Living in {@code :platform} lets {@code :compat-modern} implement it without depending
 * on {@code :core}; only floor-present {@code ItemStack} appears in its signatures.
 *
 * <p>Owning thread(s): the caller's. Mutability class: implementations are stateless.
 */
public interface ItemBytesCodec {

    /** Serialises {@code item} to a self-describing, version-upgradable byte array. */
    byte @NotNull [] encode(@NotNull ItemStack item);

    /** Deserialises bytes produced by {@link #encode}, upgrading across data versions. */
    @NotNull ItemStack decode(byte @NotNull [] bytes);
}
