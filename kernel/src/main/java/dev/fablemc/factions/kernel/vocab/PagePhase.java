package dev.fablemc.factions.kernel.vocab;

/**
 * The phase of a multi-phase paged bulk operation (AM-5), carried by {@code DisbandPage} and
 * {@code MergePage}.
 *
 * <p><b>Owning thread(s):</b> chosen by the reducer's paged branches. <b>Mutability:</b> immutable
 * enum. <b>Reducer rule:</b> n/a — a progress marker.
 *
 * <p>{@link #code()} is the stable code (the historical phase int): {@code 0} = claims (disband) /
 * claims and warps (merge), {@code 1} = members, {@code 2} = final. {@link #next()} advances one
 * phase (FINAL is terminal and returns itself).
 */
public enum PagePhase {

    /** Phase 0: claims (disband) / claims and warps (merge). */
    CLAIMS(0),
    /** Phase 1: members. */
    MEMBERS(1),
    /** Phase 2: final teardown. */
    FINAL(2);

    private final int code;

    PagePhase(int code) {
        this.code = code;
    }

    /** The stable code (historical phase int). */
    public int code() {
        return code;
    }

    /** The next phase; FINAL is terminal and returns itself (exhaustive — the compiler proves it). */
    public PagePhase next() {
        return switch (this) {
            case CLAIMS -> MEMBERS;
            case MEMBERS, FINAL -> FINAL;
        };
    }

    private static final PagePhase[] VALUES = values();

    /** The phase with the given stable {@link #code()}; throws for an unknown code. */
    public static PagePhase fromCode(int code) {
        for (PagePhase p : VALUES) {
            if (p.code == code) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown PagePhase code: " + code);
    }
}
