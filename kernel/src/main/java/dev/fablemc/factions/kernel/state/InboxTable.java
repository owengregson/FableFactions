package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.UUID;

import dev.fablemc.factions.kernel.msg.MessageKey;

/**
 * Offline player inbox: missed faction notifications queued for delivery on next login.
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> the reducer queues entries
 * ({@code InboxQueued}) and, on login, emits the exact delivered id set and removes only those
 * via {@code AckInbox} — so nothing is double-delivered and overflow beyond {@code max-per-login}
 * stays queued (fixes the inbox-loss bug).
 */
public final class InboxTable {

    /** One queued inbox entry: a message key plus its substitution args. */
    public record InboxEntry(long id, UUID player, MessageKey key, String[] args, long createdAt) {
    }

    private static final InboxEntry[] NONE = new InboxEntry[0];
    private static final InboxTable EMPTY = new InboxTable(NONE);

    private final InboxEntry[] entries;

    private InboxTable(InboxEntry[] entries) {
        this.entries = entries;
    }

    /** The shared empty inbox. */
    public static InboxTable empty() {
        return EMPTY;
    }

    /** Total queued entries across all players. */
    public int size() {
        return entries.length;
    }

    /** Queued entries for {@code player}, oldest first (fresh array; empty if none). */
    public InboxEntry[] forPlayer(UUID player) {
        int n = 0;
        for (InboxEntry e : entries) {
            if (e.player().equals(player)) {
                n++;
            }
        }
        if (n == 0) {
            return NONE;
        }
        InboxEntry[] out = new InboxEntry[n];
        int j = 0;
        for (InboxEntry e : entries) {
            if (e.player().equals(player)) {
                out[j++] = e;
            }
        }
        return out;
    }

    /** Number of queued entries for {@code player}. */
    public int countForPlayer(UUID player) {
        int n = 0;
        for (InboxEntry e : entries) {
            if (e.player().equals(player)) {
                n++;
            }
        }
        return n;
    }

    /** Returns a copy with {@code entry} appended. */
    public InboxTable add(InboxEntry entry) {
        InboxEntry[] out = Arrays.copyOf(entries, entries.length + 1);
        out[entries.length] = entry;
        return new InboxTable(out);
    }

    /** Returns a copy with the given entry ids removed (the {@code AckInbox} delivered set). */
    public InboxTable removeIds(long[] ids) {
        if (ids == null || ids.length == 0) {
            return this;
        }
        long[] sorted = ids.clone();
        Arrays.sort(sorted);
        int n = 0;
        for (InboxEntry e : entries) {
            if (Arrays.binarySearch(sorted, e.id()) < 0) {
                n++;
            }
        }
        if (n == entries.length) {
            return this;
        }
        if (n == 0) {
            return EMPTY;
        }
        InboxEntry[] out = new InboxEntry[n];
        int j = 0;
        for (InboxEntry e : entries) {
            if (Arrays.binarySearch(sorted, e.id()) < 0) {
                out[j++] = e;
            }
        }
        return new InboxTable(out);
    }

    /** Returns a copy with every entry for {@code player} removed. */
    public InboxTable removeForPlayer(UUID player) {
        int n = 0;
        for (InboxEntry e : entries) {
            if (!e.player().equals(player)) {
                n++;
            }
        }
        if (n == entries.length) {
            return this;
        }
        if (n == 0) {
            return EMPTY;
        }
        InboxEntry[] out = new InboxEntry[n];
        int j = 0;
        for (InboxEntry e : entries) {
            if (!e.player().equals(player)) {
                out[j++] = e;
            }
        }
        return new InboxTable(out);
    }
}
