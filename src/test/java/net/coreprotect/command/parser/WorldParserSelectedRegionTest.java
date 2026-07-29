package net.coreprotect.command.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.coreprotect.command.LookupCommand;

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
    }

    @Test
    void preservesWorldEditRadiusSyntax() {
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "r:#worldedit" }));
        assertTrue(WorldParser.parseWorldEdit(new String[] { "lookup", "radius:", "#we" }));
    }

    @Test
    void recognizesExplicitGlobalRadius() {
        assertTrue(WorldParser.parseForceGlobal(new String[] { "lookup", "r:#global" }));
        assertTrue(WorldParser.parseForceGlobal(new String[] { "lookup", "radius:", "#global" }));
        assertTrue(LookupCommand.hasLookupScope(false, 0, 0, null, true));
    }

    @Test
    void recognizesLogicalLookupPaginationCommands() {
        assertTrue(LookupCommand.isLogicalPageLookup(new String[] { "page", "2" }));
        assertTrue(LookupCommand.isLogicalPageLookup(new String[] { "l", "2" }));
        assertTrue(LookupCommand.isLogicalPageLookup(new String[] { "lookup", "page:2" }));
    }

    @Test
    void doesNotTreatCompleteLogicalLookupAsPagination() {
        assertFalse(LookupCommand.isLogicalPageLookup(new String[] { "lookup", "time:2w" }));
        assertFalse(LookupCommand.isLogicalPageLookup(new String[] { "lookup", "user:Slovarik", "and", "time:2w" }));
    }
}
