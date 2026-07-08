package dev.fablemc.factions.kernel.rules;

import dev.fablemc.factions.kernel.state.InviteTable;

/**
 * Invite time-to-live rules (pvp-services.md §7.2, pvp-bugs-concurrency Appendix).
 *
 * <p><b>Owning thread(s):</b> pure static. <b>Mutability:</b> stateless. <b>Reducer rule:</b>
 * an invite is active while {@code now <= createdAt + max(1, ttlHours)*3600_000}; the reducer
 * prunes expired invites when it reads them and treats accept = remove + join in one step so the
 * join/invite TOCTOU cannot occur.
 */
public final class InviteRules {

    private InviteRules() {
    }

    private static final long MS_PER_HOUR = 3_600_000L;

    /** The TTL in millis for {@code ttlHours} (floored at 1 hour, per reference). */
    public static long ttlMillis(int ttlHours) {
        return (long) Math.max(1, ttlHours) * MS_PER_HOUR;
    }

    /** {@code true} when an invite created at {@code createdAt} is still active at {@code now}. */
    public static boolean isActive(long createdAt, int ttlHours, long now) {
        return now <= createdAt + ttlMillis(ttlHours);
    }

    /** {@code true} when {@code invite} is still active at {@code now}. */
    public static boolean isActive(InviteTable.Invite invite, int ttlHours, long now) {
        return invite != null && isActive(invite.createdAt(), ttlHours, now);
    }
}
