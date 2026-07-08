package dev.fablemc.factions.kernel.state;

import java.util.Locale;

/**
 * A faction rank (built-in or custom).
 *
 * <p><b>Owning thread(s):</b> constructed by the writer, published in {@link Faction}.
 * <b>Mutability:</b> immutable value. <b>Reducer rule:</b> created only by the reducer /
 * boot builders; a rank change replaces the whole {@link Faction} record.
 *
 * <p>Built-in priorities are fixed: Member 10, Officer 50, Owner 100. Custom roles occupy
 * {@code [11, 99]} (roles.yml {@code min/max-priority}). {@code prefix} is nullable (empty /
 * unset). Authority is a strict priority comparison: {@code canManage = actor.priority >
 * target.priority}.
 */
public record Rank(String id, String name, String prefix, int priority) {

    /** Built-in Member priority. */
    public static final int PRIORITY_MEMBER = 10;
    /** Built-in Officer priority. */
    public static final int PRIORITY_OFFICER = 50;
    /** Built-in Owner priority. */
    public static final int PRIORITY_OWNER = 100;

    /** Built-in Member rank name. */
    public static final String NAME_MEMBER = "member";
    /** Built-in Officer rank name. */
    public static final String NAME_OFFICER = "officer";
    /** Built-in Owner rank name. */
    public static final String NAME_OWNER = "owner";

    /** {@code true} when this rank's priority is at least the Owner floor. */
    public boolean isOwner() {
        return priority >= PRIORITY_OWNER;
    }

    /** {@code true} when this rank's priority is at least the Officer floor. */
    public boolean isOfficerOrAbove() {
        return priority >= PRIORITY_OFFICER;
    }

    /** {@code true} when this rank strictly outranks {@code other} and may manage it. */
    public boolean canManage(Rank other) {
        return other != null && this.priority > other.priority;
    }

    /** {@code true} when {@code name} is a protected built-in (owner/officer/member). */
    public static boolean isProtectedBuiltin(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.equals(NAME_OWNER) || n.equals(NAME_OFFICER) || n.equals(NAME_MEMBER);
    }
}
