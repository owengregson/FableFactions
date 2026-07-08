package dev.fablemc.factions.platform.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The boot-probe result records (CONTRACTS §3) — the pure, server-free half: the
 * {@link Capabilities} value + {@code describe()}, the {@link ManifestEntry} typed outcomes,
 * and {@link PlatformProfile#resolveDisabled} over a synthetic manifest.
 */
class ProbeRecordsTest {

    @Test
    void capabilitiesRecordExposesEveryProbeAndDescribes() {
        Capabilities caps = new Capabilities(
                true, true, false, true, false, false, false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, false);
        assertTrue(caps.folia());
        assertTrue(caps.foliaSchedulers());
        assertFalse(caps.bungeeChat());
        assertTrue(caps.onlineCollection());
        assertFalse(caps.clickedInventory());

        String described = caps.describe();
        assertTrue(described.contains("folia=true"), described);
        assertTrue(described.contains("bungeeChat=false"), described);
        assertTrue(described.contains("clickedInventory=false"), described);
    }

    @Test
    void requiredPresentAndAbsentDescribeTheirOutcome() {
        Required<Boolean> present = Required.owned("world:uid", "worlds", () -> Boolean.TRUE);
        Required<Boolean> absent = Required.owned("world:uid", "worlds", () -> null);
        assertTrue(present.present());
        assertFalse(absent.present());
        assertEquals(Boolean.TRUE, present.get());
        assertTrue(present.describe().contains("present"));
        assertTrue(absent.describe().contains("disables worlds"), absent.describe());
        assertFalse(absent.engineCritical());
    }

    @Test
    void engineCriticalRequiredIsMarkedAndThrowsOnGet() {
        Required<Boolean> critical = Required.engineCritical("scheduler", () -> null);
        assertTrue(critical.engineCritical());
        assertFalse(critical.present());
        assertThrows(IllegalStateException.class, critical::get);
        assertTrue(critical.describe().contains("engine-critical"), critical.describe());
    }

    @Test
    void optionalSinceFallsBackWhenAbsentAndNeverDisables() {
        OptionalSince<Boolean> absent = OptionalSince.resolve(
                "capability:folia", "1.19.4", Boolean.FALSE, "single-region scheduling", () -> null);
        assertFalse(absent.present());
        assertEquals(Boolean.FALSE, absent.orFallback());
        assertEquals("1.19.4", absent.since());
        assertTrue(absent.describe().contains("absent(since 1.19.4"), absent.describe());

        OptionalSince<Boolean> present = OptionalSince.resolve(
                "capability:folia", "1.19.4", Boolean.FALSE, "single-region scheduling", () -> Boolean.TRUE);
        assertTrue(present.present());
        assertEquals(Boolean.TRUE, present.orFallback());
    }

    @Test
    void resolveDisabledCollectsOwnedMissesAndIgnoresOptionalMisses() {
        List<String> logs = new ArrayList<>();
        List<ManifestEntry> entries = List.of(
                Required.owned("present", "kept", () -> Boolean.TRUE),
                Required.owned("missing", "membership", () -> null),
                OptionalSince.resolve("opt", "1.13", Boolean.FALSE, "fallback", () -> null));

        var disabled = PlatformProfile.resolveDisabled(entries, logs::add);

        assertEquals(1, disabled.size());
        assertTrue(disabled.contains("membership"));
        assertFalse(disabled.contains("kept"));
        assertEquals(1, logs.size(), "exactly one loud line per Required miss");
        assertTrue(logs.get(0).contains("membership"), logs.get(0));
    }

    @Test
    void resolveDisabledThrowsOnEngineCriticalMiss() {
        List<ManifestEntry> entries = List.of(Required.engineCritical("scheduler", () -> null));
        assertThrows(IllegalStateException.class, () -> PlatformProfile.resolveDisabled(entries, msg -> {}));
    }
}
