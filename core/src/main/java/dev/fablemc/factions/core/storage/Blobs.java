package dev.fablemc.factions.core.storage;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The chest-content blob store (proposal-C {@code ChestContentsChanged} carries only a
 * {@code long blobRef}; the bytes live here). A team chest's serialized inventory is stored in
 * the {@code ff_blobs} table keyed by {@code ref}, and {@code team_chests.blob_ref} points at it.
 *
 * <p>Every stored blob carries a small self-describing header mirroring the platform
 * {@link dev.fablemc.factions.platform.resolve.ItemCodec} tag convention ({@code FF1} legacy /
 * {@code FF2} modern + data version): the header records which item codec wrote the payload and
 * the server's data version, so a cross-flattening migration is an explicit, detectable step
 * rather than a silent broken deserialize. This layer only frames and persists the bytes — it
 * never touches Bukkit item types (that is {@code ItemCodec}'s job, one layer up), which keeps
 * {@code core.storage} free of any server-API dependency.
 *
 * <p><b>Owning thread(s):</b> the {@code fable-storage} thread (and boot). <b>Mutability:</b>
 * stateless static helpers; the header framing is immutable.
 */
public final class Blobs {

    /** Header magic: {@code 'F','F','B','1'}. */
    private static final int MAGIC = 0x46464231;   // "FFB1"
    /** Fixed header length: magic(4) + itemFormat(4) + dataVersion(4). */
    private static final int HEADER_LEN = 12;

    /** Item-codec format tag for the legacy Bukkit-serialization path ({@code FF1}). */
    public static final int FORMAT_LEGACY = 1;
    /** Item-codec format tag for the modern {@code serializeAsBytes} path ({@code FF2}). */
    public static final int FORMAT_MODERN = 2;

    /** Sentinel {@code dataVersion} for servers without {@code Bukkit.getUnsafe().getDataVersion()}. */
    public static final int NO_DATA_VERSION = -1;

    private Blobs() {
    }

    /** A framed blob: the codec that wrote it, the writer's data version, and the raw payload. */
    public record Blob(int itemFormat, int dataVersion, byte[] payload) {
        public Blob {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /**
     * Frames {@code payload} with the version-tag header. {@code itemFormat} is
     * {@link #FORMAT_LEGACY}/{@link #FORMAT_MODERN}; {@code dataVersion} is the writing server's
     * data version (or {@link #NO_DATA_VERSION}).
     */
    public static byte[] wrap(int itemFormat, int dataVersion, byte[] payload) {
        byte[] body = payload == null ? new byte[0] : payload;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + body.length);
        buf.putInt(MAGIC);
        buf.putInt(itemFormat);
        buf.putInt(dataVersion);
        buf.put(body);
        return buf.array();
    }

    /**
     * Reverses {@link #wrap}. Throws {@link IllegalArgumentException} if the magic is wrong or the
     * frame is truncated (corruption / a foreign blob), so a bad blob fails loudly rather than
     * decoding garbage.
     */
    public static Blob unwrap(byte[] framed) {
        if (framed == null || framed.length < HEADER_LEN) {
            throw new IllegalArgumentException("blob is truncated (missing header)");
        }
        ByteBuffer buf = ByteBuffer.wrap(framed);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "blob magic mismatch (0x" + Integer.toHexString(magic) + ") — not an ff_blob");
        }
        int itemFormat = buf.getInt();
        int dataVersion = buf.getInt();
        byte[] payload = new byte[framed.length - HEADER_LEN];
        buf.get(payload);
        return new Blob(itemFormat, dataVersion, payload);
    }

    /**
     * Upserts a framed blob into {@code ff_blobs} under {@code ref}. Idempotent (whole-row upsert
     * keyed on {@code ref}), so a replay re-persists the same bytes harmlessly.
     */
    public static void store(DataSource dataSource, SqlDialect dialect, long ref, byte[] framed,
                             long createdAt) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(framed, "framed");
        String sql = dialect.upsert("ff_blobs",
                new String[] {"ref", "data", "created_at"}, new String[] {"ref"});
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, ref);
            ps.setBytes(2, framed);
            ps.setLong(3, createdAt);
            ps.executeUpdate();
        }
    }

    /** Loads the framed bytes stored under {@code ref}, or {@code null} if the ref is absent. */
    public static byte[] load(DataSource dataSource, long ref) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps =
                     conn.prepareStatement("SELECT `data` FROM `ff_blobs` WHERE `ref`=?")) {
            ps.setLong(1, ref);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** Loads and unwraps the blob stored under {@code ref}, or {@code null} if absent. */
    public static Blob read(DataSource dataSource, long ref) throws SQLException {
        byte[] framed = load(dataSource, ref);
        return framed == null ? null : unwrap(framed);
    }

    /** Deletes the blob under {@code ref} (chest deletion / disband cascade). */
    public static void delete(DataSource dataSource, long ref) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps =
                     conn.prepareStatement("DELETE FROM `ff_blobs` WHERE `ref`=?")) {
            ps.setLong(1, ref);
            ps.executeUpdate();
        }
    }
}
