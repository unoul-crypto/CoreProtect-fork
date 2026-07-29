package net.coreprotect.command.lookup;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import net.coreprotect.command.logical.LogicalQuery;
import net.coreprotect.command.logical.LogicalQueryRegistry;
import net.coreprotect.command.logical.LogicalQuerySql;
import net.coreprotect.command.logical.LogicalTable;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Database;
import net.coreprotect.database.lookup.EntityInteractionLookup;
import net.coreprotect.database.statement.UserStatement;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.model.item.ItemTransactionActions;
import net.coreprotect.model.selection.SelectionRegistry;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.ChatUtils;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.MaterialUtils;
import net.coreprotect.utility.WorldUtils;
import net.coreprotect.utility.ErrorReporter;

/**
 * Lookup implementation for boolean expressions. Every log table is queried
 * with the same AST and the resulting rows are merged into one chronological
 * page.
 */
public final class LogicalLookupThread implements Runnable {
    private final CommandSender sender;
    private final Command command;
    private final LogicalQuery query;
    private final Location origin;
    private final int page;
    private final int pageSize;
    private final boolean countOnly;
    private final Integer[] worldEditSelection;

    public LogicalLookupThread(CommandSender sender, Command command, LogicalQuery query, Location origin, Integer[] worldEditSelection, int page, int pageSize, boolean countOnly) {
        this.sender = sender;
        this.command = command;
        this.query = query;
        this.origin = origin;
        this.page = page;
        this.pageSize = pageSize;
        this.countOnly = countOnly;
        this.worldEditSelection = worldEditSelection;
    }

    @Override
    public void run() {
        ConfigHandler.lookupThrottle.put(sender.getName(), new Object[] { true, System.currentTimeMillis() });
        try (Connection connection = Database.getConnection(true); Statement statement = connection == null ? null : connection.createStatement()) {
            if (statement == null) {
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.DATABASE_BUSY));
                return;
            }

            LogicalQueryRegistry.put(sender.getName(), query);
            LogicalQueryRegistry.putOrigin(sender.getName(), origin);
            LogicalQueryRegistry.putSelection(sender.getName(), worldEditSelection);
            ConfigHandler.lookupPage.put(sender.getName(), page);
            ConfigHandler.lookupType.put(sender.getName(), 5);

            LogicalQuerySql compiler = new LogicalQuerySql(query, connection, origin, worldEditSelection);
            boolean exactSelection = SelectionRegistry.hasExactSelection(worldEditSelection);
            int fetchLimit = Math.max(page * pageSize, pageSize);
            long totalRows = 0;
            List<Row> rows = new ArrayList<>();
            for (LogicalTable table : LogicalTable.values()) {
                if (!hasPermission(table)) {
                    continue;
                }
                String predicate = "(" + compiler.compile(table) + ") AND (" + permissionPredicate(table) + ")";
                if (exactSelection) {
                    List<Row> selectedRows = load(statement, table, predicate, -1);
                    selectedRows.removeIf(row -> row.table.hasCoordinates() && !SelectionRegistry.contains(worldEditSelection, row.x, row.y, row.z));
                    totalRows += selectedRows.size();
                    if (!countOnly) {
                        rows.addAll(selectedRows);
                    }
                }
                else {
                    totalRows += count(statement, table, predicate);
                    if (!countOnly) {
                        rows.addAll(load(statement, table, predicate, fetchLimit));
                    }
                }
            }

            if (countOnly) {
                String formatted = NumberFormat.getInstance().format(totalRows);
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + Phrase.build(Phrase.LOOKUP_ROWS_FOUND, formatted, totalRows == 1 ? Selector.FIRST : Selector.SECOND));
                return;
            }

            rows.sort(LogicalLookupThread::compareRows);
            int start = Math.max(0, (page - 1) * pageSize);
            if (start >= rows.size()) {
                Phrase noResults = totalRows > 0 ? Phrase.NO_RESULTS_PAGE : Phrase.NO_RESULTS;
                String message = totalRows > 0 ? Phrase.build(noResults, Selector.FIRST) : Phrase.build(noResults);
                Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + message);
                return;
            }

            int end = Math.min(rows.size(), start + pageSize);
            Chat.sendMessage(sender, Color.WHITE + "----- " + Color.DARK_AQUA + Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect" + Color.WHITE + " | " + Color.DARK_AQUA) + Color.WHITE + " -----");
            int now = (int) (System.currentTimeMillis() / 1000L);
            for (Row row : rows.subList(start, end)) {
                output(connection, row, now);
            }

            if (totalRows > pageSize) {
                int pages = (int) Math.ceil(totalRows / (double) pageSize);
                Chat.sendComponent(sender, ChatUtils.getPageNavigation(command.getName(), page, pages));
            }
        }
        catch (IllegalArgumentException e) {
            Chat.sendMessage(sender, Color.DARK_AQUA + "CoreProtect " + Color.WHITE + "- " + e.getMessage());
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        finally {
            ConfigHandler.lookupThrottle.put(sender.getName(), new Object[] { false, System.currentTimeMillis() });
        }
    }

    private boolean hasPermission(LogicalTable table) {
        switch (table) {
            case BLOCK:
                return sender.hasPermission("coreprotect.lookup.block") || sender.hasPermission("coreprotect.lookup.click") || sender.hasPermission("coreprotect.lookup.kill") || sender.hasPermission("coreprotect.lookup.spawn");
            case CONTAINER:
            case ENTITY_CONTAINER:
                return sender.hasPermission("coreprotect.lookup.container");
            case ITEM:
                return sender.hasPermission("coreprotect.lookup.item") || sender.hasPermission("coreprotect.lookup.inventory");
            case ENTITY_INTERACTION:
                return sender.hasPermission("coreprotect.lookup.click");
            case CHAT:
                return sender.hasPermission("coreprotect.lookup.chat");
            case COMMAND:
                return sender.hasPermission("coreprotect.lookup.command");
            case SESSION:
                return sender.hasPermission("coreprotect.lookup.session");
            case USERNAME:
                return sender.hasPermission("coreprotect.lookup.username");
            case SIGN:
                return sender.hasPermission("coreprotect.lookup.sign");
            default:
                return false;
        }
    }

    private String permissionPredicate(LogicalTable table) {
        if (table == LogicalTable.BLOCK) {
            List<String> actions = new ArrayList<>();
            if (sender.hasPermission("coreprotect.lookup.block")) {
                actions.add(Integer.toString(LookupActions.BLOCK_BREAK));
                actions.add(Integer.toString(LookupActions.BLOCK_PLACE));
            }
            if (sender.hasPermission("coreprotect.lookup.click")) {
                actions.add(Integer.toString(LookupActions.INTERACTION));
            }
            if (sender.hasPermission("coreprotect.lookup.kill")) {
                actions.add(Integer.toString(LookupActions.ENTITY_KILL));
            }
            if (sender.hasPermission("coreprotect.lookup.spawn")) {
                actions.add(Integer.toString(LookupActions.ENTITY_SPAWN));
            }
            return actions.isEmpty() ? "1=0" : "action IN(" + String.join(",", actions) + ")";
        }
        if (table == LogicalTable.ITEM && !sender.hasPermission("coreprotect.lookup.inventory")) {
            return "action NOT IN(" + ItemTransactionActions.CRAFTED + "," + ItemTransactionActions.USED_TO_CRAFT + ","
                    + ItemTransactionActions.EXTERNAL_ADD + "," + ItemTransactionActions.EXTERNAL_REMOVE + ")";
        }
        return "1=1";
    }

    private static long count(Statement statement, LogicalTable table, String predicate) throws Exception {
        String sql = "SELECT COUNT(*) AS count FROM " + ConfigHandler.prefix + table.getTableName() + " WHERE " + predicate;
        try (ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong("count") : 0;
        }
    }

    private static List<Row> load(Statement statement, LogicalTable table, String predicate, int limit) throws Exception {
        List<Row> rows = new ArrayList<>();
        String columns;
        switch (table) {
            case BLOCK:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action,type,data,rolled_back";
                break;
            case CONTAINER:
            case ENTITY_CONTAINER:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action,type,data,amount,rolled_back";
                break;
            case ITEM:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action,type,0 AS data,amount,rolled_back";
                break;
            case ENTITY_INTERACTION:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action,type,rolled_back";
                break;
            case CHAT:
            case COMMAND:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,message";
                break;
            case SESSION:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action";
                break;
            case USERNAME:
                columns = "rowid AS id,time,uuid," + ConfigHandler.databaseType.getUserColumn();
                break;
            case SIGN:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z";
                break;
            default:
                throw new IllegalArgumentException("Unsupported logical lookup table");
        }

        String sql = "SELECT " + columns + " FROM " + ConfigHandler.prefix + table.getTableName() + " WHERE " + predicate + " ORDER BY time DESC,rowid DESC" + (limit > -1 ? " LIMIT " + limit : "");
        try (ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Row row = new Row(table, result.getLong("id"), result.getLong("time"));
                if (table == LogicalTable.USERNAME) {
                    row.uuid = result.getString("uuid");
                    row.userName = result.getString(ConfigHandler.databaseType.getUserColumn());
                }
                else {
                    row.userId = result.getInt(ConfigHandler.databaseType.getUserColumn());
                    if (table.hasCoordinates()) {
                        row.worldId = result.getInt("wid");
                        row.x = result.getInt("x");
                        row.y = result.getInt("y");
                        row.z = result.getInt("z");
                    }
                    if (table == LogicalTable.CHAT || table == LogicalTable.COMMAND) {
                        row.message = result.getString("message");
                    }
                    else if (table != LogicalTable.SIGN) {
                        row.action = result.getInt("action");
                    }
                    if (table.hasMaterialType() || table == LogicalTable.ENTITY_INTERACTION) {
                        row.type = result.getInt("type");
                    }
                    if (table == LogicalTable.BLOCK || table == LogicalTable.CONTAINER || table == LogicalTable.ENTITY_CONTAINER || table == LogicalTable.ITEM) {
                        row.data = result.getInt("data");
                    }
                    if (table == LogicalTable.CONTAINER || table == LogicalTable.ENTITY_CONTAINER || table == LogicalTable.ITEM) {
                        row.amount = result.getInt("amount");
                    }
                    if (table == LogicalTable.BLOCK || table == LogicalTable.CONTAINER || table == LogicalTable.ENTITY_CONTAINER
                            || table == LogicalTable.ITEM || table == LogicalTable.ENTITY_INTERACTION) {
                        row.rolledBack = result.getInt("rolled_back");
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private void output(Connection connection, Row row, int now) {
        String userName = row.userName;
        if (userName == null) {
            userName = UserStatement.getName(connection, row.userId);
        }
        if (userName == null || userName.isEmpty()) {
            userName = "unknown";
        }

        String time = ChatUtils.getTimeSince((int) row.time, now, true);
        String rollbackFormat = isRolledBack(row) ? Color.STRIKETHROUGH : "";
        String description = rollbackFormat + describe(connection, row)
                .replace(Color.GREY, Color.GREY + rollbackFormat)
                .replace(Color.DARK_AQUA, Color.DARK_AQUA + rollbackFormat)
                .replace(Color.WHITE, Color.WHITE + rollbackFormat);
        String coordinates = "";
        if (row.table.hasCoordinates()) {
            coordinates = " " + Color.GREY + ChatUtils.getCoordinates(command.getName(), row.worldId, row.x, row.y, row.z, true, true);
        }
        String action = " " + Color.GREY + Color.ITALIC + "(" + actionLabel(row.table, row.action) + ")";
        Chat.sendComponent(sender, time + " " + Color.DARK_AQUA + rollbackFormat + userName + Color.WHITE + rollbackFormat + " "
                + description + Color.RESET + coordinates + action);
    }

    private static int compareRows(Row first, Row second) {
        int result = Long.compare(second.time, first.time);
        if (result != 0) {
            return result;
        }
        if (first.table == second.table) {
            return Long.compare(second.id, first.id);
        }

        result = Integer.compare(chronologyPriority(second.table, second.action), chronologyPriority(first.table, first.action));
        if (result != 0) {
            return result;
        }
        result = Long.compare(second.id, first.id);
        if (result != 0) {
            return result;
        }
        return Integer.compare(first.table.ordinal(), second.table.ordinal());
    }

    private static int chronologyPriority(LogicalTable table, int action) {
        if (table == LogicalTable.CONTAINER || table == LogicalTable.ENTITY_CONTAINER) {
            return action == ItemTransactionActions.ADD ? 4 : 1;
        }
        if (table == LogicalTable.ITEM) {
            return ItemTransactionActions.getInventoryActionId(action) == ItemTransactionActions.REMOVE ? 5 : 3;
        }
        if (table == LogicalTable.BLOCK && action == LookupActions.BLOCK_PLACE) {
            return 4;
        }
        return 2;
    }

    private static boolean isRolledBack(Row row) {
        return MaterialUtils.rolledBack(row.rolledBack, row.table == LogicalTable.ITEM) == 1;
    }

    private static String actionLabel(LogicalTable table, int action) {
        switch (table) {
            case CHAT:
                return "a:chat";
            case COMMAND:
                return "a:command";
            case SESSION:
                return "a:session";
            case USERNAME:
                return "a:username";
            case SIGN:
                return "a:sign";
            case ENTITY_INTERACTION:
                return "a:click";
            case CONTAINER:
            case ENTITY_CONTAINER:
                return "a:container";
            case ITEM:
                if (action == ItemTransactionActions.CRAFTED || action == ItemTransactionActions.USED_TO_CRAFT) {
                    return "a:craft";
                }
                if (action == ItemTransactionActions.EXTERNAL_ADD || action == ItemTransactionActions.EXTERNAL_REMOVE) {
                    return "a:inventorychange";
                }
                return "a:item";
            case BLOCK:
                if (action == LookupActions.INTERACTION) {
                    return "a:click";
                }
                if (action == LookupActions.ENTITY_KILL) {
                    return "a:kill";
                }
                if (action == LookupActions.ENTITY_SPAWN) {
                    return "a:spawn";
                }
                return "a:block";
            default:
                return "a:" + table.getTableName();
        }
    }

    private static String describe(Connection connection, Row row) {
        switch (row.table) {
            case CHAT:
                return "chat: " + Color.GREY + row.message;
            case COMMAND:
                return "command: " + Color.GREY + row.message;
            case SESSION:
                return row.action == 1 ? "logged in" : "logged out";
            case USERNAME:
                return "used username " + Color.DARK_AQUA + row.userName + Color.WHITE + " (" + row.uuid + ")";
            case SIGN:
                return "changed a sign";
            case ENTITY_INTERACTION:
                return "interacted with " + entityName(row.type);
            case BLOCK:
                return blockDescription(connection, row);
            case CONTAINER:
            case ENTITY_CONTAINER:
                return (row.action == ItemTransactionActions.REMOVE ? "removed " : "added ") + "x" + row.amount + " " + materialName(row);
            case ITEM:
                return itemDescription(row);
            default:
                return row.table.getTableName();
        }
    }

    private static String blockDescription(Connection connection, Row row) {
        if (row.action == LookupActions.BLOCK_BREAK) {
            return "broke " + materialName(row);
        }
        if (row.action == LookupActions.BLOCK_PLACE) {
            return "placed " + materialName(row);
        }
        if (row.action == LookupActions.INTERACTION) {
            return "clicked " + materialName(row);
        }
        if (row.action == LookupActions.ENTITY_KILL) {
            if (row.type == 0) {
                String playerName = UserStatement.getName(connection, row.data);
                return "killed " + (playerName == null || playerName.isEmpty() ? "unknown player" : playerName);
            }
            return "killed " + entityName(row.type);
        }
        if (row.action == LookupActions.ENTITY_SPAWN) {
            return "spawned " + entityName(row.type);
        }
        return "changed " + materialName(row);
    }

    private static String entityName(int type) {
        return EntityInteractionLookup.entityName(type);
    }

    private static String itemDescription(Row row) {
        String name = materialName(row);
        switch (row.action) {
            case ItemTransactionActions.CRAFTED:
                return "crafted x" + row.amount + " " + name;
            case ItemTransactionActions.USED_TO_CRAFT:
                return "used x" + row.amount + " " + name + " for crafting";
            case ItemTransactionActions.EXTERNAL_ADD:
                return "received x" + row.amount + " " + name + " externally";
            case ItemTransactionActions.EXTERNAL_REMOVE:
                return "lost x" + row.amount + " " + name + " externally";
            case ItemTransactionActions.PICKUP:
                return "picked up x" + row.amount + " " + name;
            case ItemTransactionActions.DROP:
                return "dropped x" + row.amount + " " + name;
            default:
                return "item action " + row.action + ": x" + row.amount + " " + name;
        }
    }

    private static String materialName(Row row) {
        String name = MaterialUtils.getBlockDisplayName(row.type, row.data);
        if (name == null || name.isEmpty()) {
            return "#" + row.type;
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            return name.substring("minecraft:".length());
        }
        return name;
    }

    private static final class Row {
        private final LogicalTable table;
        private final long id;
        private final long time;
        private int userId;
        private String userName;
        private String uuid;
        private int worldId;
        private int x;
        private int y;
        private int z;
        private int action;
        private int type;
        private int data;
        private int amount;
        private int rolledBack;
        private String message;

        private Row(LogicalTable table, long id, long time) {
            this.table = table;
            this.id = id;
            this.time = time;
        }

        private long getId() {
            return id;
        }

        private long getTime() {
            return time;
        }
    }
}
