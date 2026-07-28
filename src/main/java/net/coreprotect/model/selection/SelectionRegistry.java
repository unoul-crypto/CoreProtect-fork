package net.coreprotect.model.selection;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class SelectionRegistry {

    @FunctionalInterface
    public interface Matcher {
        boolean contains(int x, int y, int z);
    }

    private static final Map<Integer[], Matcher> MATCHERS = Collections.synchronizedMap(new WeakHashMap<>());

    private SelectionRegistry() {
    }

    public static void register(Integer[] radius, Matcher matcher) {
        if (radius != null && matcher != null) {
            MATCHERS.put(radius, matcher);
        }
    }

    public static boolean hasExactSelection(Integer[] radius) {
        return radius != null && MATCHERS.containsKey(radius);
    }

    public static boolean contains(Integer[] radius, int x, int y, int z) {
        Matcher matcher = radius == null ? null : MATCHERS.get(radius);
        return matcher == null || matcher.contains(x, y, z);
    }
}
