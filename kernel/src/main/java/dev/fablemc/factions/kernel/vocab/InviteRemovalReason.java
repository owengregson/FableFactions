package dev.fablemc.factions.kernel.vocab;

/**
 * Why a pending invite was removed, carried by {@code Effect.InviteRemoved} (formerly the
 * {@code INVITE_*} byte constants on {@code Effect}).
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer's membership branches; read on any thread.
 * <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> n/a — a classification value.
 *
 * <p>{@link #code()} is the stable wire/DB code (the historical {@code INVITE_*} value) the
 * journal codec persists, never the ordinal.
 */
public enum InviteRemovalReason {

    /** The invitee accepted the invite. */
    ACCEPTED(0),
    /** The invite was revoked by the faction. */
    REVOKED(1),
    /** The invitee declined. */
    DECLINED(2),
    /** The invite expired (TTL). */
    EXPIRED(3);

    private final int code;

    InviteRemovalReason(int code) {
        this.code = code;
    }

    /** The stable wire/DB code (historical {@code INVITE_*} value). */
    public int code() {
        return code;
    }

    private static final InviteRemovalReason[] VALUES = values();

    /** The reason with the given stable {@link #code()}; throws for an unknown code. */
    public static InviteRemovalReason fromCode(int code) {
        for (InviteRemovalReason r : VALUES) {
            if (r.code == code) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown InviteRemovalReason code: " + code);
    }
}
