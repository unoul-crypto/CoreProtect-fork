package net.coreprotect.model.selection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SelectionRegistryTest {

    @Test
    void unregisteredRadiusUsesBoundingBoxOnly() {
        Integer[] radius = radius();

        assertFalse(SelectionRegistry.hasExactSelection(radius));
        assertTrue(SelectionRegistry.contains(radius, 100, 64, 100));
    }

    @Test
    void registeredMatcherFiltersCoordinates() {
        Integer[] radius = radius();
        SelectionRegistry.register(radius, (x, y, z) -> x == 1 && y == 2 && z == 3);

        assertTrue(SelectionRegistry.hasExactSelection(radius));
        assertTrue(SelectionRegistry.contains(radius, 1, 2, 3));
        assertFalse(SelectionRegistry.contains(radius, 1, 2, 4));
    }

    @Test
    void selectionIsBoundToRadiusIdentity() {
        Integer[] registered = radius();
        Integer[] copy = radius();
        SelectionRegistry.register(registered, (x, y, z) -> false);

        assertFalse(SelectionRegistry.contains(registered, 0, 0, 0));
        assertFalse(SelectionRegistry.hasExactSelection(copy));
        assertTrue(SelectionRegistry.contains(copy, 0, 0, 0));
    }

    private static Integer[] radius() {
        return new Integer[] { 10, 0, 10, 0, 10, 0, 10, 1 };
    }
}
