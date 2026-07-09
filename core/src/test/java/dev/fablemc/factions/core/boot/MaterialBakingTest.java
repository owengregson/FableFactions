package dev.fablemc.factions.core.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import dev.fablemc.factions.kernel.config.BakedTables;
import dev.fablemc.factions.kernel.config.ConfigImage;

/**
 * Finding #7: container/interact protection is DEAD until the {@link BakedTables} material bitsets are
 * populated. This pins that {@link MaterialBaking} fills them from the RUNTIME {@link Material}
 * registry (by ordinal, classified by name) so
 * {@link dev.fablemc.factions.core.listen.InteractProtectionListener} sees non-empty bitsets — the
 * exact bug that left {@code isContainer}/{@code isInteractable} always false.
 */
final class MaterialBakingTest {

    @Test
    void bakesContainerAndInteractableBitsetsFromRuntimeRegistry() {
        BakedTables baked = MaterialBaking.bake(ConfigImage.defaults(), null);

        assertTrue(MaterialBaking.countBits(baked.containerMaterials()) > 0,
                "container bitset must be populated (protection was dead when empty)");
        assertTrue(MaterialBaking.countBits(baked.interactableMaterials()) > 0,
                "interactable bitset must be populated");

        assertTrue(baked.isContainer(Material.CHEST.ordinal()), "CHEST is a container");
        assertTrue(baked.isContainer(Material.FURNACE.ordinal()), "FURNACE is a container");
        assertTrue(baked.isContainer(Material.HOPPER.ordinal()), "HOPPER is a container");

        assertTrue(baked.isInteractable(Material.LEVER.ordinal()), "LEVER is interactable");
        assertTrue(baked.isInteractable(Material.OAK_DOOR.ordinal()), "OAK_DOOR is interactable");
        assertTrue(baked.isInteractable(Material.ANVIL.ordinal()), "ANVIL is interactable");

        // A plain block is neither — protection must fall through to vanilla for it.
        assertFalse(baked.isContainer(Material.STONE.ordinal()), "STONE is not a container");
        assertFalse(baked.isInteractable(Material.STONE.ordinal()), "STONE is not interactable");
    }

    @Test
    void nameClassifierIsVersionSafeBySuffix() {
        // The classifier never references a possibly-absent enum constant — it matches names.
        assertTrue(MaterialBaking.isContainer("BLAST_FURNACE"), "1.14 container by name");
        assertTrue(MaterialBaking.isContainer("BURNING_FURNACE"), "1.7 lit-furnace name");
        assertTrue(MaterialBaking.isContainer("RED_SHULKER_BOX"), "dyed shulker by suffix");
        assertTrue(MaterialBaking.isInteractable("SPRUCE_TRAPDOOR"), "trapdoor by suffix");
        assertTrue(MaterialBaking.isInteractable("DARK_OAK_FENCE_GATE"), "fence gate by suffix");
        assertTrue(MaterialBaking.isInteractable("STONE_BUTTON"), "button by suffix");
        assertTrue(MaterialBaking.isInteractable("ENCHANTMENT_TABLE"), "1.7 enchant-table name");
        assertFalse(MaterialBaking.isContainer("STONE"), "plain block is not a container");
        assertFalse(MaterialBaking.isInteractable("DIRT"), "plain block is not interactable");
    }
}
