package dev.fablemc.factions.platform.resolve;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.fablemc.factions.platform.probe.CompatClass;

/**
 * Version-tagged item persistence (CONTRACTS §3, version-deltas Risk #9). Every stored
 * item blob is tagged with the writing server's data version and the codec that wrote it,
 * so a cross-flattening migration (1.12 → 1.13+) is an explicit one-way step, never a
 * silent broken deserialize.
 *
 * <ul>
 *   <li><b>Modern</b> ({@code FF2}): delegates to {@link ItemBytesCodec} — the
 *       {@code serializeAsBytes} implementation in {@code :compat-modern}, loaded by FQN
 *       string only when the capability probes true.</li>
 *   <li><b>Legacy</b> ({@code FF1}): {@code BukkitObjectOutputStream} → Base64.</li>
 * </ul>
 *
 * <p>Owning thread(s): the storage/main thread. Mutability class: immutable (the delegate
 * and data version are fixed at construction).
 */
public final class ItemCodec {

    private static final String TAG_LEGACY = "FF1";
    private static final String TAG_MODERN = "FF2";

    /** The writing server's data version (for cross-version migration awareness); -1 if unknown. */
    private static final int DATA_VERSION = probeDataVersion();

    private final @Nullable ItemBytesCodec delegate;

    private ItemCodec(@Nullable ItemBytesCodec delegate) {
        this.delegate = delegate;
    }

    /**
     * Builds the codec for this server. When {@code serializeAsBytes} is true, the modern
     * byte delegate is loaded via {@link CompatClass#MODERN_ITEM_CODEC}; if that fails (or
     * the capability is false), the legacy Bukkit-serialization path is used.
     */
    public static @NotNull ItemCodec create(boolean serializeAsBytes) {
        if (!serializeAsBytes) {
            return new ItemCodec(null);
        }
        try {
            return new ItemCodec(CompatClass.MODERN_ITEM_CODEC.instance(ItemBytesCodec.class));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            // Modern probe said yes but the delegate would not load — degrade to legacy loudly at boot.
            return new ItemCodec(null);
        }
    }

    /** Whether the modern byte delegate is active (else the legacy Bukkit path). */
    public boolean modern() {
        return delegate != null;
    }

    /** Serialises {@code item} to a tagged, Base64 blob (modern bytes if available, else legacy). */
    public @NotNull String encode(@NotNull ItemStack item) {
        if (delegate != null) {
            byte[] bytes = delegate.encode(item);
            return TAG_MODERN + ';' + DATA_VERSION + ';' + Base64.getEncoder().encodeToString(bytes);
        }
        return TAG_LEGACY + ';' + DATA_VERSION + ';' + legacyEncode(item);
    }

    /**
     * Deserialises a blob produced by {@link #encode}. A modern ({@code FF2}) blob read on
     * a server without the byte delegate throws {@link IllegalStateException} — cross-boundary
     * chest migration is an explicit one-way step, not an implicit load (Risk #9).
     */
    public @NotNull ItemStack decode(@NotNull String blob) {
        int firstSep = blob.indexOf(';');
        int secondSep = firstSep < 0 ? -1 : blob.indexOf(';', firstSep + 1);
        if (firstSep < 0 || secondSep < 0) {
            throw new IllegalArgumentException("malformed item blob (missing tag/version separators)");
        }
        String tag = blob.substring(0, firstSep);
        String payload = blob.substring(secondSep + 1);
        byte[] bytes = Base64.getDecoder().decode(payload);
        switch (tag) {
            case TAG_MODERN:
                if (delegate == null) {
                    throw new IllegalStateException(
                            "item blob was written with the modern byte codec but this server has no "
                                    + "serializeAsBytes delegate — cross-version migration is an explicit step.");
                }
                return delegate.decode(bytes);
            case TAG_LEGACY:
                return legacyDecode(bytes);
            default:
                throw new IllegalArgumentException("unknown item blob tag '" + tag + "'");
        }
    }

    /** The writing data version recorded on blobs this codec emits (-1 if the server has none). */
    public int dataVersion() {
        return DATA_VERSION;
    }

    private static String legacyEncode(@NotNull ItemStack item) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                BukkitObjectOutputStream out = new BukkitObjectOutputStream(buffer)) {
            out.writeObject(item);
            out.flush();
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("legacy item serialization failed", failure);
        }
    }

    private static @NotNull ItemStack legacyDecode(byte[] bytes) {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            Object read = in.readObject();
            if (!(read instanceof ItemStack item)) {
                throw new IllegalStateException("legacy item blob did not contain an ItemStack");
            }
            return item;
        } catch (Exception failure) {
            throw new IllegalStateException("legacy item deserialization failed", failure);
        }
    }

    /** Best-effort {@code Bukkit.getUnsafe().getDataVersion()} via reflection (no descriptor); -1 pre-1.13. */
    private static int probeDataVersion() {
        try {
            Object unsafe = Bukkit.class.getMethod("getUnsafe").invoke(null);
            if (unsafe == null) {
                return -1;
            }
            Object version = unsafe.getClass().getMethod("getDataVersion").invoke(unsafe);
            return version instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return -1;
        }
    }
}
