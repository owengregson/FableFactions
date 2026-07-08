package dev.fablemc.factions.core.journal;

/**
 * A small, dependency-free CRC-32C (Castagnoli, polynomial {@code 0x1EDC6F41}) checksum over the
 * journal record body. Implemented in-house rather than using {@code java.util.zip.CRC32C}
 * (a Java-9 API) so the downgraded Java-8 base tier links cleanly (AM-10; {@code verifyJdk8Api}).
 *
 * <p><b>Owning thread(s):</b> stateless static helper, any thread. <b>Mutability:</b> the lookup
 * table is a shared immutable {@code int[]}.
 */
final class Crc32c {

    private static final int[] TABLE = buildTable();

    private Crc32c() {
    }

    /** CRC-32C over {@code data[off..off+len)}. */
    static int compute(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        int end = off + len;
        for (int i = off; i < end; i++) {
            crc = (crc >>> 8) ^ TABLE[(crc ^ data[i]) & 0xFF];
        }
        return crc ^ 0xFFFFFFFF;
    }

    /** CRC-32C over the whole array. */
    static int compute(byte[] data) {
        return compute(data, 0, data.length);
    }

    private static int[] buildTable() {
        // Reflected Castagnoli polynomial.
        final int poly = 0x82F63B78;
        int[] table = new int[256];
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (poly ^ (c >>> 1)) : (c >>> 1);
            }
            table[n] = c;
        }
        return table;
    }
}
