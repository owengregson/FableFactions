package dev.fablemc.factions.core.update;

/**
 * The immutable result of an update check (work order W3f). Published by {@link UpdateChecker} and
 * read by the op-join notice listener through the checker's supplier.
 *
 * <p><b>Owning thread(s):</b> built on the async check thread, read on any thread (published through
 * a volatile field). <b>Mutability:</b> immutable value.
 */
public record UpdateStatus(
        boolean available,
        String currentVersion,
        String latestVersion,
        String downloadUrl,
        String source) {
}
