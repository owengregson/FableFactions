package dev.fablemc.factions.kernel.effect;

import java.util.UUID;

import dev.fablemc.factions.kernel.intent.Origin;
import dev.fablemc.factions.kernel.vocab.FactionAuditAction;

/**
 * Audit effects: the single audit-log entry.
 *
 * <p><b>Owning thread(s):</b> emitted by the writer, fanned out on any thread. <b>Mutability:</b>
 * immutable value records; every record's leading fields are {@code (long seq, Origin origin)}.
 * See {@link Effect} for the hierarchy contract.
 */
public sealed interface AuditEffect extends Effect permits AuditEffect.AuditRecorded {

    record AuditRecorded(long seq, Origin origin, int faction, UUID actor,
                         FactionAuditAction action, String detail) implements AuditEffect {
    }
}
