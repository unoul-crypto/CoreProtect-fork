package net.coreprotect.command.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.coreprotect.model.action.LookupActions;

class ActionParserCraftingTest {

    @Test
    void parsesBothCraftingSides() {
        assertActions("craft", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.CRAFT);
        assertActions("crafting", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.CRAFT);
    }

    @Test
    void parsesCraftedAliases() {
        assertActions("+craft", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.CRAFTED);
        assertActions("crafted", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.CRAFTED);
    }

    @Test
    void parsesUsedToCraftAliases() {
        assertActions("-craft", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.USED_TO_CRAFT);
        assertActions("usedtocraft", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.USED_TO_CRAFT);
        assertActions("used-to-craft", LookupActions.CONTAINER, LookupActions.ITEM, LookupActions.USED_TO_CRAFT);
    }

    private static void assertActions(String action, Integer... expected) {
        List<Integer> actual = ActionParser.parseAction(new String[] { "lookup", "a:" + action });
        assertEquals(Arrays.asList(expected), actual);
    }
}
