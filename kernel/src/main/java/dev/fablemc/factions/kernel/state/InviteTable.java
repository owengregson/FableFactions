package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.UUID;

/**
 * Pending faction invites.
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> the reducer adds/removes invites and
 * prunes expired ones (TTL from {@code ConfigImage}); accept = remove + join in one step, so the
 * join/invite TOCTOU cannot occur.
 *
 * <p>Invites are low-volume (human-rate), so the backing store is a compact immutable array with
 * linear scans — no per-mutation hashing overhead.
 */
public final class InviteTable {

    /** A single pending invite; {@code factionOrdinal} is the inviting faction. */
    public record Invite(long id, int factionOrdinal, UUID inviter, UUID invitee, long createdAt) {
    }

    private static final Invite[] NONE = new Invite[0];
    private static final InviteTable EMPTY = new InviteTable(NONE);

    private final Invite[] invites;

    private InviteTable(Invite[] invites) {
        this.invites = invites;
    }

    /** The shared empty table. */
    public static InviteTable empty() {
        return EMPTY;
    }

    /** Number of pending invites. */
    public int size() {
        return invites.length;
    }

    /** Invite {@code id}, or {@code null}. */
    public Invite byId(long id) {
        for (Invite in : invites) {
            if (in.id() == id) {
                return in;
            }
        }
        return null;
    }

    /** The pending invite from {@code factionOrdinal} to {@code invitee}, or {@code null}. */
    public Invite find(int factionOrdinal, UUID invitee) {
        for (Invite in : invites) {
            if (in.factionOrdinal() == factionOrdinal && in.invitee().equals(invitee)) {
                return in;
            }
        }
        return null;
    }

    /** {@code true} when an invite from {@code factionOrdinal} to {@code invitee} exists. */
    public boolean has(int factionOrdinal, UUID invitee) {
        return find(factionOrdinal, invitee) != null;
    }

    /** All pending invites to {@code invitee} (fresh array; empty if none). */
    public Invite[] forInvitee(UUID invitee) {
        int n = 0;
        for (Invite in : invites) {
            if (in.invitee().equals(invitee)) {
                n++;
            }
        }
        if (n == 0) {
            return NONE;
        }
        Invite[] out = new Invite[n];
        int j = 0;
        for (Invite in : invites) {
            if (in.invitee().equals(invitee)) {
                out[j++] = in;
            }
        }
        return out;
    }

    /** All pending invites from {@code factionOrdinal} (fresh array; empty if none). */
    public Invite[] forFaction(int factionOrdinal) {
        int n = 0;
        for (Invite in : invites) {
            if (in.factionOrdinal() == factionOrdinal) {
                n++;
            }
        }
        if (n == 0) {
            return NONE;
        }
        Invite[] out = new Invite[n];
        int j = 0;
        for (Invite in : invites) {
            if (in.factionOrdinal() == factionOrdinal) {
                out[j++] = in;
            }
        }
        return out;
    }

    /** Returns a copy with {@code invite} appended. */
    public InviteTable add(Invite invite) {
        Invite[] out = Arrays.copyOf(invites, invites.length + 1);
        out[invites.length] = invite;
        return new InviteTable(out);
    }

    /** Returns a copy with invite {@code id} removed (no-op if absent). */
    public InviteTable remove(long id) {
        int idx = -1;
        for (int i = 0; i < invites.length; i++) {
            if (invites[i].id() == id) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return this;
        }
        return new InviteTable(removeAt(invites, idx));
    }

    /** Returns a copy with every invite matching {@code keep == false} removed. */
    public InviteTable removeIf(InvitePredicate drop) {
        int n = 0;
        for (Invite in : invites) {
            if (!drop.test(in)) {
                n++;
            }
        }
        if (n == invites.length) {
            return this;
        }
        if (n == 0) {
            return EMPTY;
        }
        Invite[] out = new Invite[n];
        int j = 0;
        for (Invite in : invites) {
            if (!drop.test(in)) {
                out[j++] = in;
            }
        }
        return new InviteTable(out);
    }

    /** Predicate over invites for bulk pruning (e.g. TTL expiry, faction disband scrub). */
    @FunctionalInterface
    public interface InvitePredicate {
        boolean test(Invite invite);
    }

    static Invite[] removeAt(Invite[] a, int idx) {
        Invite[] out = new Invite[a.length - 1];
        System.arraycopy(a, 0, out, 0, idx);
        System.arraycopy(a, idx + 1, out, idx, a.length - idx - 1);
        return out;
    }
}
