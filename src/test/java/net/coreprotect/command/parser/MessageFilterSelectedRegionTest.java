package net.coreprotect.command.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessageFilterSelectedRegionTest {

    @Test
    void selectedRegionTerminatesMessageFilter() {
        MessageFilterParser.ParseResult result = MessageFilterParser.parse(
                new String[] { "lookup", "a:chat", "f:diamond", "inselectedregion:", "true" });

        assertEquals(1, result.getFilters().size());
        assertEquals("diamond", result.getFilters().get(0));
        assertArrayEquals(
                new String[] { "lookup", "a:chat", "f:diamond", "inselectedregion:", "true" },
                result.getArguments());
    }
}
