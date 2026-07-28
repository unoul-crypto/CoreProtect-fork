package net.coreprotect.model.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ItemTransactionActionsCraftingTest {

    @Test
    void mapsCraftedItemsIntoPlayerInventory() {
        assertEquals(ItemTransactionActions.ADD, ItemTransactionActions.getInventoryActionId(ItemTransactionActions.CRAFTED));
        assertEquals("crafted", ItemTransactionActions.getActionString(ItemTransactionActions.CRAFTED));
    }

    @Test
    void mapsIngredientsOutOfPlayerInventory() {
        assertEquals(ItemTransactionActions.REMOVE, ItemTransactionActions.getInventoryActionId(ItemTransactionActions.USED_TO_CRAFT));
        assertEquals("used_to_craft", ItemTransactionActions.getActionString(ItemTransactionActions.USED_TO_CRAFT));
    }
}
