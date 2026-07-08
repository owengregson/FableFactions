package dev.fablemc.factions.platform.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fablemc.factions.platform.gui.MenuModel;
import org.junit.jupiter.api.Test;

/**
 * The {@link Nametags} budget math (the AM-13 / version-deltas Risk #10 truncation) and the
 * {@link MenuModel} size normalisation — the pure, server-free logic W1b owns.
 */
class NametagsTest {

    @Test
    void shortTextUnderBudgetIsUnchanged() {
        assertEquals("§c§lABC", Nametags.truncateToBudget("§c§lABC", 16));
        assertEquals("", Nametags.truncateToBudget("", 16));
    }

    @Test
    void plainTextIsTruncatedToTheRawBudget() {
        assertEquals("ABCDEFGHIJKLMNOP", Nametags.truncateToBudget("ABCDEFGHIJKLMNOPQRST", 16));
        assertEquals(16, Nametags.truncateToBudget("ABCDEFGHIJKLMNOPQRST", 16).length());
    }

    @Test
    void colourCodesCountAsTwoCharactersTowardTheBudget() {
        // "§c" (2) + "ABCD" (4) = 6 raw chars ≤ 6 ⇒ kept whole.
        assertEquals("§cABCD", Nametags.truncateToBudget("§cABCD", 6));
        // budget 5: the §c pair (2) + "ABC" (3) fits exactly at 5.
        assertEquals("§cABC", Nametags.truncateToBudget("§cABCDEF", 5));
    }

    @Test
    void aColourCodePairIsNeverSplit() {
        // Budget lands mid-pair after "AB§c": the trailing "§d" (2) would overflow at budget 5,
        // so it is dropped whole rather than leaving a dangling '§'.
        String result = Nametags.truncateToBudget("AB§cCD§dEF", 6);
        assertTrue(result.length() <= 6, "must not exceed the budget");
        // No dangling section character at the end.
        assertTrue(result.isEmpty() || result.charAt(result.length() - 1) != '§',
                "a trailing lone § must never survive: " + result);
    }

    @Test
    void danglingSectionAtTheBudgetBoundaryIsDropped() {
        // 4 plain chars then a §-code at exactly the boundary: the pair can't fit, so it's dropped.
        assertEquals("ABCD", Nametags.truncateToBudget("ABCD§e", 4));
        assertEquals("ABCD", Nametags.truncateToBudget("ABCD§e", 5));
    }

    @Test
    void zeroOrNegativeBudgetYieldsEmpty() {
        assertEquals("", Nametags.truncateToBudget("anything", 0));
        assertEquals("", Nametags.truncateToBudget("anything", -3));
    }

    @Test
    void the64BudgetKeepsLongerTags() {
        String long70 = "0123456789012345678901234567890123456789012345678901234567890123456789";
        assertEquals(64, Nametags.truncateToBudget(long70, 64).length());
        assertEquals(long70.substring(0, 64), Nametags.truncateToBudget(long70, 64));
    }

    @Test
    void menuSizeNormalisesToChestLegalMultiplesOfNine() {
        assertEquals(9, MenuModel.normalizeSize(1));
        assertEquals(9, MenuModel.normalizeSize(9));
        assertEquals(18, MenuModel.normalizeSize(10));
        assertEquals(27, MenuModel.normalizeSize(19));
        assertEquals(54, MenuModel.normalizeSize(54));
        assertEquals(54, MenuModel.normalizeSize(99));
    }
}
