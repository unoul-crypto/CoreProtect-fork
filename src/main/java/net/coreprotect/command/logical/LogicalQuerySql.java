package net.coreprotect.command.logical;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.Material;

import net.coreprotect.command.parser.TimeParser;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.statement.UserStatement;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.model.item.ItemTransactionActions;
import net.coreprotect.utility.MaterialUtils;
import net.coreprotect.utility.WorldUtils;

/**
 * Compiles a logical query into a table-specific SQL predicate. Values are
 * resolved to numeric database identifiers before being included in SQL.
 */
public final class LogicalQuerySql {
    private final LogicalQuery query;
    private final Connection connection;
    private final Location origin;
    private final Integer[] worldEditSelection;
    private final long now;

    public LogicalQuerySql(LogicalQuery query, Connection connection, Location origin) {
        this(query, connection, origin, null);
    }

    public LogicalQuerySql(LogicalQuery query, Connection connection, Location origin, Integer[] worldEditSelection) {
        this.query = query;
        this.connection = connection;
        this.origin = origin;
        this.worldEditSelection = worldEditSelection;
        this.now = System.currentTimeMillis() / 1000L;
    }

    public String compile(LogicalTable table) {
        return compile(query.getRoot(), table);
    }

    private String compile(LogicalQuery.Node node, LogicalTable table) {
        switch (node.getOperator()) {
            case TRUE:
                return "1=1";
            case TERM:
                return compileTerm(node.getTerm(), table);
            case NOT:
                return "NOT (" + compile(node.getLeft(), table) + ")";
            case AND:
                return "(" + compile(node.getLeft(), table) + ") AND (" + compile(node.getRight(), table) + ")";
            case OR:
                return "(" + compile(node.getLeft(), table) + ") OR (" + compile(node.getRight(), table) + ")";
            default:
                throw new IllegalArgumentException("Unsupported logical operator");
        }
    }

    private String compileTerm(String rawTerm, LogicalTable table) {
        String term = rawTerm.trim();
        int separator = term.indexOf(':');
        if (separator < 0) {
            String normalized = term.toLowerCase(Locale.ROOT);
            if (normalized.equals("#rolledback") || normalized.equals("#rollbacked")) {
                return rollbackPredicate(table, true);
            }
            if (normalized.equals("#restored")) {
                return rollbackPredicate(table, false);
            }
            return isModifier(term) ? "1=1" : invalid(rawTerm);
        }

        String key = term.substring(0, separator).toLowerCase(Locale.ROOT);
        String value = term.substring(separator + 1).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing value for '" + key + ":'");
        }

        switch (key) {
            case "u":
            case "user":
            case "users":
                return userPredicate(value, table);
            case "t":
            case "time":
                return timePredicate(value);
            case "a":
            case "action":
                return actionPredicate(value, table);
            case "i":
            case "include":
                return materialPredicate(value, table, false);
            case "e":
            case "exclude":
                if (value.startsWith("#")) {
                    return "NOT (" + userPredicate(value.substring(1), table) + ")";
                }
                return materialPredicate(value, table, true);
            case "r":
            case "radius":
                return radiusPredicate(value, table);
            case "world":
            case "w":
                return worldPredicate(value, table);
            case "message":
            case "msg":
            case "f":
            case "filter":
                return messagePredicate(value, table);
            case "rows":
            case "page":
                return "1=1";
            case "inselectedregion":
                return Boolean.parseBoolean(value) ? radiusPredicate("#worldedit", table) : "1=1";
            default:
                return invalid(rawTerm);
        }
    }

    private String userPredicate(String value, LogicalTable table) {
        List<String> predicates = new ArrayList<>();
        try {
            for (String name : splitValues(value)) {
                if (name.equalsIgnoreCase("#global")) {
                    return "1=1";
                }
                if (table == LogicalTable.USERNAME) {
                    String uuid = UserStatement.getUuid(connection, name);
                    predicates.add(uuid == null ? "1=0" : "uuid=" + quote(uuid));
                }
                else {
                    int userId = UserStatement.findId(connection, name);
                    predicates.add(ConfigHandler.databaseType.getUserColumn() + "=" + userId);
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalArgumentException("Unable to resolve query user", e);
        }
        return joinOr(predicates);
    }

    private String timePredicate(String value) {
        long[] duration = TimeParser.parseTime(new String[] { "lookup", "t:" + value });
        if (duration[0] <= 0) {
            throw new IllegalArgumentException("Invalid time value '" + value + "'");
        }
        long newestBoundary = now - duration[0];
        if (duration[1] > 0) {
            long oldestBoundary = now - duration[1];
            long lower = Math.min(oldestBoundary, newestBoundary);
            long upper = Math.max(oldestBoundary, newestBoundary);
            return "time>" + lower + " AND time<=" + upper;
        }
        return "time>" + newestBoundary;
    }

    private String actionPredicate(String value, LogicalTable table) {
        List<String> predicates = new ArrayList<>();
        for (String action : splitValues(value)) {
            String normalized = action.toLowerCase(Locale.ROOT);
            boolean match;
            switch (table) {
                case BLOCK:
                    match = blockActionPredicate(normalized, predicates);
                    break;
                case CONTAINER:
                case ENTITY_CONTAINER:
                    match = containerActionPredicate(normalized, predicates);
                    break;
                case ITEM:
                    match = itemActionPredicate(normalized, predicates);
                    break;
                case ENTITY_INTERACTION:
                    match = isAny(normalized, "click", "clicks", "interact", "interaction");
                    if (match) {
                        predicates.add("1=1");
                    }
                    break;
                case CHAT:
                    match = isAny(normalized, "chat", "chats", "message", "messages");
                    if (match) {
                        predicates.add("1=1");
                    }
                    break;
                case COMMAND:
                    match = isAny(normalized, "command", "commands");
                    if (match) {
                        predicates.add("1=1");
                    }
                    break;
                case SESSION:
                    match = sessionActionPredicate(normalized, predicates);
                    break;
                case USERNAME:
                    match = isAny(normalized, "username", "usernames", "name", "names", "uuid", "uuids");
                    if (match) {
                        predicates.add("1=1");
                    }
                    break;
                case SIGN:
                    match = isAny(normalized, "sign", "signs");
                    if (match) {
                        predicates.add("1=1");
                    }
                    break;
                default:
                    match = false;
            }
            if (!match) {
                predicates.add("1=0");
            }
        }
        return joinOr(predicates);
    }

    private static boolean blockActionPredicate(String action, List<String> predicates) {
        if (isAny(action, "block", "blocks", "change", "changes")) {
            predicates.add("action IN(" + LookupActions.BLOCK_BREAK + "," + LookupActions.BLOCK_PLACE + ")");
        }
        else if (isAny(action, "-block", "block-", "break", "broke", "remove", "destroy")) {
            predicates.add("action=" + LookupActions.BLOCK_BREAK);
        }
        else if (isAny(action, "+block", "block+", "place", "placed")) {
            predicates.add("action=" + LookupActions.BLOCK_PLACE);
        }
        else if (isAny(action, "click", "clicks", "interact", "interaction")) {
            predicates.add("action=" + LookupActions.INTERACTION);
        }
        else if (isAny(action, "kill", "kills", "death", "deaths")) {
            predicates.add("action=" + LookupActions.ENTITY_KILL);
        }
        else if (isAny(action, "spawn", "spawns")) {
            predicates.add("action=" + LookupActions.ENTITY_SPAWN);
        }
        else {
            return false;
        }
        return true;
    }

    private static boolean containerActionPredicate(String action, List<String> predicates) {
        if (isAny(action, "container", "containers", "transaction", "transactions", "inventory", "inv")) {
            predicates.add("1=1");
        }
        else if (isAny(action, "+container", "container+")) {
            predicates.add("action=" + ItemTransactionActions.ADD);
        }
        else if (isAny(action, "-container", "container-")) {
            predicates.add("action=" + ItemTransactionActions.REMOVE);
        }
        else {
            return false;
        }
        return true;
    }

    private static boolean itemActionPredicate(String action, List<String> predicates) {
        if (isAny(action, "item", "items", "inventory", "inv")) {
            predicates.add("1=1");
        }
        else if (isAny(action, "craft", "crafts", "crafting")) {
            predicates.add("action IN(" + ItemTransactionActions.CRAFTED + "," + ItemTransactionActions.USED_TO_CRAFT + ")");
        }
        else if (isAny(action, "+craft", "craft+", "crafted")) {
            predicates.add("action=" + ItemTransactionActions.CRAFTED);
        }
        else if (isAny(action, "-craft", "craft-", "usedtocraft", "used-to-craft")) {
            predicates.add("action=" + ItemTransactionActions.USED_TO_CRAFT);
        }
        else if (isAny(action, "inventorychange", "inventory-change", "externalinventory", "external-inventory")) {
            predicates.add("action IN(" + ItemTransactionActions.EXTERNAL_ADD + "," + ItemTransactionActions.EXTERNAL_REMOVE + ")");
        }
        else if (isAny(action, "+inventorychange", "inventorychange+", "+externalinventory", "externalinventory+")) {
            predicates.add("action=" + ItemTransactionActions.EXTERNAL_ADD);
        }
        else if (isAny(action, "-inventorychange", "inventorychange-", "-externalinventory", "externalinventory-")) {
            predicates.add("action=" + ItemTransactionActions.EXTERNAL_REMOVE);
        }
        else if (isAny(action, "+item", "item+", "pickup", "pickups")) {
            predicates.add("action IN(" + ItemTransactionActions.PICKUP + "," + ItemTransactionActions.REMOVE_ENDER + ")");
        }
        else if (isAny(action, "-item", "item-", "drop", "drops")) {
            predicates.add("action IN(" + ItemTransactionActions.DROP + "," + ItemTransactionActions.ADD_ENDER + "," + ItemTransactionActions.THROW + "," + ItemTransactionActions.SHOOT + ")");
        }
        else {
            return false;
        }
        return true;
    }

    private static boolean sessionActionPredicate(String action, List<String> predicates) {
        if (isAny(action, "session", "sessions", "connection", "connections")) {
            predicates.add("1=1");
        }
        else if (isAny(action, "+session", "session+", "login", "logins")) {
            predicates.add("action=1");
        }
        else if (isAny(action, "-session", "session-", "logout", "logouts")) {
            predicates.add("action=0");
        }
        else {
            return false;
        }
        return true;
    }

    private String materialPredicate(String value, LogicalTable table, boolean exclude) {
        if (!table.hasMaterialType()) {
            return exclude ? "1=1" : "1=0";
        }
        List<String> ids = new ArrayList<>();
        for (String materialName : splitValues(value)) {
            Material material = Material.matchMaterial(materialName);
            int id = material == null ? MaterialUtils.getBlockId(materialName, false) : MaterialUtils.getBlockId(material.name(), false);
            ids.add(Integer.toString(id));
        }
        return "type " + (exclude ? "NOT " : "") + "IN(" + String.join(",", ids) + ")";
    }

    private String radiusPredicate(String value, LogicalTable table) {
        if (isAny(value.toLowerCase(Locale.ROOT), "#global", "global", "off", "none", "false", "-1")) {
            return "1=1";
        }
        if (value.startsWith("#")) {
            if (isAny(value.toLowerCase(Locale.ROOT), "#worldedit", "#we")) {
                if (!table.hasCoordinates() || worldEditSelection == null) {
                    return "1=0";
                }
                String bounds = "x>=" + worldEditSelection[1] + " AND x<=" + worldEditSelection[2]
                        + " AND z>=" + worldEditSelection[5] + " AND z<=" + worldEditSelection[6];
                if (worldEditSelection[3] != null && worldEditSelection[4] != null) {
                    bounds += " AND y>=" + worldEditSelection[3] + " AND y<=" + worldEditSelection[4];
                }
                if (origin != null && origin.getWorld() != null) {
                    bounds = "wid=" + WorldUtils.getWorldId(origin.getWorld().getName()) + " AND " + bounds;
                }
                return bounds;
            }
            return worldPredicate(value.substring(1), table);
        }
        if (!table.hasCoordinates() || origin == null || origin.getWorld() == null) {
            return "1=0";
        }
        String[] dimensions = value.toLowerCase(Locale.ROOT).split("x");
        int radiusX;
        int radiusZ;
        try {
            radiusX = Integer.parseInt(dimensions[0]);
            radiusZ = dimensions.length > 1 ? Integer.parseInt(dimensions[dimensions.length - 1]) : radiusX;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid radius value '" + value + "'");
        }
        int worldId = WorldUtils.getWorldId(origin.getWorld().getName());
        return "wid=" + worldId + " AND x>=" + (origin.getBlockX() - radiusX) + " AND x<=" + (origin.getBlockX() + radiusX)
                + " AND z>=" + (origin.getBlockZ() - radiusZ) + " AND z<=" + (origin.getBlockZ() + radiusZ);
    }

    private String worldPredicate(String value, LogicalTable table) {
        if (!table.hasCoordinates()) {
            return "1=0";
        }
        int worldId = WorldUtils.matchWorld(value.startsWith("#") ? value : "#" + value);
        return worldId < 0 ? "1=0" : "wid=" + worldId;
    }

    private String messagePredicate(String value, LogicalTable table) {
        String escaped = escapeLike(value.toLowerCase(Locale.ROOT));
        String escapeClause = ConfigHandler.databaseType.isClickHouse() ? "" : " ESCAPE '~'";
        if (table == LogicalTable.CHAT || table == LogicalTable.COMMAND) {
            return "LOWER(message) LIKE " + quote("%" + escaped + "%") + escapeClause;
        }
        if (table == LogicalTable.SIGN) {
            List<String> lines = new ArrayList<>();
            for (int index = 1; index <= 8; index++) {
                lines.add("LOWER(line_" + index + ") LIKE " + quote("%" + escaped + "%") + escapeClause);
            }
            return "(" + String.join(" OR ", lines) + ")";
        }
        return "1=0";
    }

    private static String rollbackPredicate(LogicalTable table, boolean rolledBack) {
        if (table == LogicalTable.ITEM) {
            return rolledBack ? "rolled_back IN(2,3)" : "rolled_back IN(0,1)";
        }
        if (table == LogicalTable.BLOCK || table == LogicalTable.CONTAINER || table == LogicalTable.ENTITY_CONTAINER || table == LogicalTable.ENTITY_INTERACTION) {
            return rolledBack ? "rolled_back IN(1,3)" : "rolled_back IN(0,2)";
        }
        return rolledBack ? "1=0" : "1=1";
    }

    private static boolean isModifier(String term) {
        String lower = term.toLowerCase(Locale.ROOT);
        return lower.matches("[0-9]+") || lower.startsWith("#") || isAny(lower, "lookup", "l", "page", "near", "rollback", "rb", "restore", "rs", "purge");
    }

    private static String invalid(String term) {
        throw new IllegalArgumentException("Unsupported logical query parameter '" + term + "'");
    }

    private static List<String> splitValues(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Empty query value");
        }
        return result;
    }

    private static String joinOr(List<String> predicates) {
        return predicates.size() == 1 ? predicates.get(0) : "(" + String.join(" OR ", predicates) + ")";
    }

    private static boolean isAny(String value, String... variants) {
        for (String variant : variants) {
            if (value.equalsIgnoreCase(variant)) {
                return true;
            }
        }
        return false;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String escapeLike(String value) {
        return value.replace("~", "~~").replace("%", "~%").replace("_", "~_");
    }
}
