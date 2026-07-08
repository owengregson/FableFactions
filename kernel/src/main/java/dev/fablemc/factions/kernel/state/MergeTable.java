package dev.fablemc.factions.kernel.state;

import java.util.Arrays;
import java.util.UUID;

/**
 * Pending merge requests.
 *
 * <p><b>Owning thread(s):</b> queries on any reader thread; mutators on the writer only.
 * <b>Mutability:</b> immutable, COW. <b>Reducer rule:</b> the reducer adds requests and, on
 * accept, executes the merge and scrubs the request; disband scrub (AM-6) also drops any
 * request naming a vanished faction.
 */
public final class MergeTable {

    /** A pending merge request from {@code senderOrdinal} into {@code targetOrdinal}. */
    public record MergeRequest(long id, int senderOrdinal, int targetOrdinal, UUID actor,
                               long createdAt) {
    }

    private static final MergeRequest[] NONE = new MergeRequest[0];
    private static final MergeTable EMPTY = new MergeTable(NONE);

    private final MergeRequest[] requests;

    private MergeTable(MergeRequest[] requests) {
        this.requests = requests;
    }

    /** The shared empty table. */
    public static MergeTable empty() {
        return EMPTY;
    }

    /** Number of pending requests. */
    public int size() {
        return requests.length;
    }

    /** The request from {@code senderOrdinal} to {@code targetOrdinal}, or {@code null}. */
    public MergeRequest find(int senderOrdinal, int targetOrdinal) {
        for (MergeRequest r : requests) {
            if (r.senderOrdinal() == senderOrdinal && r.targetOrdinal() == targetOrdinal) {
                return r;
            }
        }
        return null;
    }

    /** {@code true} when a request from {@code senderOrdinal} to {@code targetOrdinal} exists. */
    public boolean has(int senderOrdinal, int targetOrdinal) {
        return find(senderOrdinal, targetOrdinal) != null;
    }

    /** Returns a copy with {@code request} appended. */
    public MergeTable add(MergeRequest request) {
        MergeRequest[] out = Arrays.copyOf(requests, requests.length + 1);
        out[requests.length] = request;
        return new MergeTable(out);
    }

    /** Returns a copy with every request touching {@code factionOrdinal} (either side) removed. */
    public MergeTable removeInvolving(int factionOrdinal) {
        return removeIf(r -> r.senderOrdinal() == factionOrdinal
                || r.targetOrdinal() == factionOrdinal);
    }

    /** Returns a copy with the request from {@code senderOrdinal}→{@code targetOrdinal} removed. */
    public MergeTable remove(int senderOrdinal, int targetOrdinal) {
        return removeIf(r -> r.senderOrdinal() == senderOrdinal
                && r.targetOrdinal() == targetOrdinal);
    }

    /** Returns a copy with every request matching {@code drop} removed. */
    public MergeTable removeIf(MergePredicate drop) {
        int n = 0;
        for (MergeRequest r : requests) {
            if (!drop.test(r)) {
                n++;
            }
        }
        if (n == requests.length) {
            return this;
        }
        if (n == 0) {
            return EMPTY;
        }
        MergeRequest[] out = new MergeRequest[n];
        int j = 0;
        for (MergeRequest r : requests) {
            if (!drop.test(r)) {
                out[j++] = r;
            }
        }
        return new MergeTable(out);
    }

    /** Predicate over merge requests for bulk pruning. */
    @FunctionalInterface
    public interface MergePredicate {
        boolean test(MergeRequest request);
    }
}
