package dev.fablemc.factions.compat.modern;

import dev.fablemc.factions.platform.resolve.ItemBytesCodec;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The modern item byte codec (CONTRACTS §3, version-deltas Risk #9). Uses Paper's
 * {@code ItemStack#serializeAsBytes}/{@code deserializeBytes}, which embed the writing
 * DataVersion and upgrade item components across server versions on read — the only codec
 * that round-trips a chest across a Minecraft upgrade.
 *
 * <p>Compiled against paper-api 1.20.6 and loaded by {@code ItemCodec} via FQN string ONLY
 * when the {@code serializeAsBytes} capability probes true, so its modern methods never link
 * on a server that lacks them. Requires a public no-arg constructor for the reflective load.
 *
 * <p>Owning thread(s): the caller's. Mutability class: stateless.
 */
public final class ModernItemCodec implements ItemBytesCodec {

    public ModernItemCodec() {}

    @Override
    public byte @NotNull [] encode(@NotNull ItemStack item) {
        return item.serializeAsBytes();
    }

    @Override
    public @NotNull ItemStack decode(byte @NotNull [] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }
}
