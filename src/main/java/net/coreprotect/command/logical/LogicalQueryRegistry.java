package net.coreprotect.command.logical;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;

public final class LogicalQueryRegistry {
    private static final Map<String, LogicalQuery> QUERIES = new ConcurrentHashMap<>();
    private static final Map<String, Location> ORIGINS = new ConcurrentHashMap<>();
    private static final Map<String, Integer[]> SELECTIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<LogicalQuery> ACTIVE_QUERY = new ThreadLocal<>();
    private static final ThreadLocal<Integer[]> ACTIVE_SELECTION = new ThreadLocal<>();

    private LogicalQueryRegistry() {
        throw new IllegalStateException("Registry class");
    }

    public static LogicalQuery get(String senderName) {
        return QUERIES.get(senderName);
    }

    public static void put(String senderName, LogicalQuery query) {
        if (query == null) {
            QUERIES.remove(senderName);
        }
        else {
            QUERIES.put(senderName, query);
        }
    }

    public static Location getOrigin(String senderName) {
        Location origin = ORIGINS.get(senderName);
        return origin == null ? null : origin.clone();
    }

    public static void putOrigin(String senderName, Location origin) {
        if (origin == null) {
            ORIGINS.remove(senderName);
        }
        else {
            ORIGINS.put(senderName, origin.clone());
        }
    }

    public static Integer[] getSelection(String senderName) {
        Integer[] selection = SELECTIONS.get(senderName);
        return selection == null ? null : selection.clone();
    }

    public static void putSelection(String senderName, Integer[] selection) {
        if (selection == null) {
            SELECTIONS.remove(senderName);
        }
        else {
            SELECTIONS.put(senderName, selection.clone());
        }
    }

    public static void remove(String senderName) {
        QUERIES.remove(senderName);
        ORIGINS.remove(senderName);
        SELECTIONS.remove(senderName);
    }

    public static void activate(LogicalQuery query) {
        activate(query, null);
    }

    public static void activate(LogicalQuery query, Integer[] selection) {
        if (query == null) {
            ACTIVE_QUERY.remove();
            ACTIVE_SELECTION.remove();
        }
        else {
            ACTIVE_QUERY.set(query);
            if (selection == null) {
                ACTIVE_SELECTION.remove();
            }
            else {
                ACTIVE_SELECTION.set(selection.clone());
            }
        }
    }

    public static LogicalQuery getActive() {
        return ACTIVE_QUERY.get();
    }

    public static Integer[] getActiveSelection() {
        Integer[] selection = ACTIVE_SELECTION.get();
        return selection == null ? null : selection.clone();
    }

    public static void deactivate() {
        ACTIVE_QUERY.remove();
        ACTIVE_SELECTION.remove();
    }
}
