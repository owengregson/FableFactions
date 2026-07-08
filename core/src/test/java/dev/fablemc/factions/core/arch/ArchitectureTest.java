package dev.fablemc.factions.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The ArchUnit resolution-firewall suite (work order W2b §3). Each rule pins a boundary from
 * ARCHITECTURE / CONTRACTS and is documented inline. Rules are checked over the compiled
 * production classes of every FableFactions module on the classpath (tests excluded).
 */
final class ArchitectureTest {

    /**
     * All FableFactions production classes (kernel/api/platform/core), from both the {@code core}
     * classes dir and the kernel/api/platform dependency jars; only this module's test classes are
     * excluded (so the rules see every module but not the tests themselves). Third-party packages
     * are never imported — {@code importPackages} scopes the scan to {@code dev.fablemc.factions}.
     */
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.fablemc.factions");

    /**
     * CONTRACTS §4 / §7: JDBC is a storage-only concern. No {@code core} class outside
     * {@code core.storage} may touch {@code java.sql}/{@code javax.sql} — listeners, commands and
     * the pipeline read the immutable snapshot, never the database.
     */
    @Test
    void jdbcIsConfinedToCoreStorage() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..core..")
                .and().resideOutsideOfPackage("..core.storage..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..")
                .because("JDBC lives only in core.storage; the rest of core reads the snapshot");
        rule.check(PRODUCTION);
    }

    /**
     * AM-1: the relocated Adventure ({@code net.kyori}) type may appear ONLY at the single
     * text boundary — {@code platform.text} (TextPort) plus {@code core.text}/{@code core.messages}
     * (the catalog + renderer). Anywhere else is a relocation-casualty / AbstractMethodError risk.
     */
    @Test
    void adventureIsConfinedToTheTextBoundary() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages("..platform.text..", "..core.text..",
                        "..core.messages..")
                .should().dependOnClassesThat().resideInAnyPackage("net.kyori..")
                .because("net.kyori is relocated; only the TextPort/catalog boundary may touch it (AM-1)");
        rule.check(PRODUCTION);
    }

    /**
     * AM-9 / AM-14: the write pipeline, journal and storage carry NO server-API dependency — the
     * writer must never call a Bukkit API, and the plugin-disable hook is a plain {@code Runnable}/
     * {@code FailureHandler} seam, not a Bukkit type. This keeps the kernel-adjacent core pure.
     */
    @Test
    void pipelineJournalStorageHaveNoBukkitDependency() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..core.pipeline..", "..core.journal..", "..core.storage..")
                .should().dependOnClassesThat().resideInAnyPackage("org.bukkit..")
                .because("the writer/journal/storage never touch a server API; the disable hook is a "
                        + "Runnable seam (AM-9)");
        rule.check(PRODUCTION);
    }

    /**
     * CONTRACTS §2 backstop: the kernel is pure JDK — no kernel class may reference a Bukkit type
     * (the bytecode gate verifyKernelPurity enforces this on the mega jar; this asserts it in unit
     * scope too).
     */
    @Test
    void kernelIsPureOfBukkit() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..kernel..")
                .should().dependOnClassesThat().resideInAnyPackage("org.bukkit..")
                .because("the kernel is pure JDK (CONTRACTS §2)");
        rule.check(PRODUCTION);
    }
}
