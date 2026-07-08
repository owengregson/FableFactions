package dev.fablemc.factions.kernel.vocab;

/**
 * The 18 audit-log action categories, with the exact reference id strings.
 *
 * <p><b>Owning thread(s):</b> produced by the writer inside {@code AuditRecorded} effects,
 * read on any thread. <b>Mutability:</b> immutable enum. <b>Reducer rule:</b> chosen by the
 * reducer; storage projects {@link #id()} verbatim.
 *
 * <p>Ids are lower-kebab and are the {@code --action=} filter tokens shown to admins
 * (ref-services.md §1.3, ref-commands-admin.md §2.10). {@link #validIds()} renders the
 * comma-joined list used in "unknown action" errors.
 */
public enum FactionAuditAction {

    CLAIM("claim"),
    UNCLAIM("unclaim"),
    RELATION_CHANGE("relation-change"),
    MEMBER_KICK("kick"),
    MEMBER_PROMOTE("promote"),
    MEMBER_DEMOTE("demote"),
    ROLE_CREATE("role-create"),
    ROLE_RENAME("role-rename"),
    ROLE_PRIORITY_SET("role-priority-set"),
    ROLE_PREFIX_SET("role-prefix-set"),
    ROLE_DELETE("role-delete"),
    ROLE_ASSIGN("role-assign"),
    BANK_DEPOSIT("bank-deposit"),
    BANK_WITHDRAW("bank-withdraw"),
    BANK_TRANSFER("bank-transfer"),
    MERGE_REQUEST("merge-request"),
    MERGE_ACCEPT("merge-accept"),
    MOTD_SET("motd-set");

    private final String id;

    FactionAuditAction(String id) {
        this.id = id;
    }

    /** The persisted, filterable id (e.g. {@code "relation-change"}). */
    public String id() {
        return id;
    }

    /** Case-insensitive lookup by id; {@code null} when unknown. */
    public static FactionAuditAction fromId(String id) {
        if (id == null) {
            return null;
        }
        for (FactionAuditAction a : VALUES) {
            if (a.id.equalsIgnoreCase(id.trim())) {
                return a;
            }
        }
        return null;
    }

    /** Comma-joined list of all valid ids, for error messages. */
    public static String validIds() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(VALUES[i].id);
        }
        return sb.toString();
    }

    private static final FactionAuditAction[] VALUES = values();
}
