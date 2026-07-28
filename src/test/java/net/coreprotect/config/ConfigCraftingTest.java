package net.coreprotect.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ConfigCraftingTest {

    @Test
    void enablesCraftingTransactionsByDefault() {
        Config config = new Config();
        config.loadDefaults();

        assertTrue(config.CRAFTING_TRANSACTIONS);
    }

    @Test
    void allowsCraftingTransactionsToBeDisabled() throws Exception {
        Config config = new Config();
        config.load(new ByteArrayInputStream("crafting-transactions: false".getBytes(StandardCharsets.UTF_8)));

        assertFalse(config.CRAFTING_TRANSACTIONS);
    }
}
