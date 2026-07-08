package dev.fablemc.factions.core.storage.legacy;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import dev.fablemc.factions.kernel.state.NameIndex;

/**
 * Fold-case name-collision sanitization (pvp-data critique rule 2): the reference schema allowed
 * {@code "Wolves"} and {@code "wolves"} to coexist, but the kernel's {@link NameIndex} is case-folded
 * and unique, so a colliding faction is renamed {@code name2}, {@code name3}, … and the rename is
 * logged loudly.
 *
 * <p><b>Owning thread(s):</b> the boot/migration thread only. <b>Mutability:</b> stateless static
 * helper; the caller owns the {@code usedFolded} set.
 */
final class LegacySanitizer {

    private LegacySanitizer() {
    }

    /**
     * If {@code name}'s fold-case is already used, appends the smallest numeric suffix that makes it
     * unique and logs the rename loudly. Returns the (possibly suffixed) name.
     */
    static String sanitizeName(String name, UUID id, Set<String> usedFolded, Logger log) {
        String base = name == null ? ("faction-" + id) : name;
        String folded = NameIndex.fold(base);
        if (usedFolded.add(folded)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + n;
            String candidateFolded = NameIndex.fold(candidate);
            if (usedFolded.add(candidateFolded)) {
                log.warning("[pvp-import] fold-case name collision: faction " + id + " '" + base
                        + "' renamed to '" + candidate + "' (case-insensitive uniqueness).");
                return candidate;
            }
        }
    }
}
