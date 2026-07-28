package net.coreprotect.command.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldParserSelectedRegionTest {

    @Test
    void defaultsToFalse() {
        assertFalse(WorldParser.parseWorldEdit(new String[] { "lookup" }));
        assertFalse(WorldParser.parseWorldEdit(new String[] { "lookup", "inselectedregion:false" }));
    }

    @Test
    void acceptsInlineAndSeparatedTrueValues() {
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "inselectedregion:true" }));
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "inselectedregion:", "true" }));
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "INSELECTEDREGION:TRUE" }));
        assertTrue(UserParser.parseUsers(new String[] { "lookup", "inselectedregion:", "true" }).isEmpty());
    }

    @Test
    void preservesWorldEditRadiusSyntax() {
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "r:#worldedit" }));
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "radius:", "#we" }));
    }
}
