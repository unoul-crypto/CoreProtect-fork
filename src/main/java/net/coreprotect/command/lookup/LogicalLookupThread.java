package net.coreprotect.command.lookup;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import com.google.common.base.Strings;

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
import net.coreprotect.model.action.SessionActions;
import net.coreprotect.model.selection.SelectionRegistry;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.ChatUtils;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.DatabaseUtils;
import net.coreprotect.utility.EntitySpawnTracking;
import net.coreprotect.utility.EntityUtils;
import net.coreprotect.utility.ItemUtils;
import net.coreprotect.utility.MaterialUtils;
import net.coreprotect.utility.StringUtils;
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
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action,type,data,amount,metadata,rolled_back";
                break;
            case ITEM:
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,action,type,0 AS data,amount,data AS metadata,rolled_back";
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
                columns = "rowid AS id,time," + ConfigHandler.databaseType.getUserColumn() + ",wid,x,y,z,face,line_1,line_2,line_3,line_4,line_5,line_6,line_7,line_8";
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
                    else if (table == LogicalTable.SIGN) {
                        row.message = signMessage(result);
                    }
                    else {
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
                        row.metadata = DatabaseUtils.getBytes(result, "metadata");
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
        switch (row.table) {
            case CHAT:
            case COMMAND:
                Chat.sendComponent(sender, time + " " + Color.WHITE + "- " + Color.DARK_AQUA + userName + ": " + Color.WHITE, row.message);
                return;
            case USERNAME:
                String currentName = UserStatement.getNameByUuid(row.uuid);
                if (currentName == null || currentName.isEmpty()) {
                    currentName = row.userName;
                }
                Chat.sendComponent(sender, time + " " + Color.WHITE + "- "
                        + Phrase.build(Phrase.LOOKUP_USERNAME, Color.DARK_AQUA + currentName + Color.WHITE, Color.DARK_AQUA + row.userName + Color.WHITE));
                return;
            case SIGN:
                Chat.sendComponent(sender, time + " " + Color.WHITE + "- " + Color.DARK_AQUA + userName + ": " + Color.WHITE, row.message);
                outputCoordinates(row, now, null);
                return;
            case SESSION:
                boolean login = row.action != SessionActions.LOGOUT;
                String sessionTag = login ? Color.GREEN + "+" : Color.RED + "-";
                Chat.sendComponent(sender, time + " " + sessionTag + " " + Color.DARK_AQUA
                        + Phrase.build(Phrase.LOOKUP_LOGIN, Color.DARK_AQUA + userName + Color.WHITE, login ? Selector.FIRST : Selector.SECOND));
                outputCoordinates(row, now, null);
                return;
            default:
                outputAction(connection, row, now, userName, time);
        }
    }

    private void outputAction(Connection connection, Row row, int now, String userName, String time) {
        String rollbackFormat = isRolledBack(row) ? Color.STRIKETHROUGH : "";
        String tag = Color.WHITE + "-";
        Phrase phrase;
        String selector;
        String target;
        String action = actionLabel(row.table, row.action);

        if (row.table == LogicalTable.CONTAINER || row.table == LogicalTable.ENTITY_CONTAINER || row.table == LogicalTable.ITEM) {
            InventoryFormat format = inventoryFormat(row.table, row.action);
            phrase = format.phrase;
            selector = format.selector;
            tag = format.tag;
            action = format.action;

            String itemName = itemName(row);
            String tooltip = ItemUtils.getEnchantments(row.metadata, row.type, row.amount);
            Integer itemId = ItemUtils.makeGivableItem(ItemUtils.getItemStack(row.metadata, row.type, row.amount));
            target = ChatUtils.createTooltip(Color.DARK_AQUA + rollbackFormat + itemName, tooltip)
                    + ChatUtils.filterComponent(sender.hasPermission("coreprotect.give"),
                            ChatUtils.createGiveItemComponent(Color.GREY + "(↓)", command.getName(), itemId))
                    + Color.WHITE;

            Chat.sendComponent(sender, time + " " + tag + " "
                    + Phrase.build(phrase, Color.DARK_AQUA + rollbackFormat + userName + Color.WHITE + rollbackFormat,
                            "x" + row.amount, target, selector));
        }
        else {
            if (row.table == LogicalTable.ENTITY_INTERACTION) {
                phrase = Phrase.LOOKUP_ENTITY_INTERACTION;
                selector = EntityInteractionLookup.actionSelector(row.action);
                target = entityName(row.type);
            }
            else if (row.action == LookupActions.ENTITY_SPAWN) {
                boolean placedEntity = EntitySpawnTracking.isPlacedEntityType(EntityUtils.getEntityType(row.type));
                phrase = placedEntity ? Phrase.LOOKUP_BLOCK : Phrase.LOOKUP_ENTITY_SPAWN;
                selector = Selector.FIRST;
                tag = Color.GREEN + "+";
                action = placedEntity ? "a:block" : "a:spawn";
                target = entityName(row.type);
            }
            else if (row.action == LookupActions.INTERACTION || row.action == LookupActions.ENTITY_KILL) {
                boolean placedEntity = row.action == LookupActions.ENTITY_KILL && row.type != 0
                        && EntitySpawnTracking.isPlacedEntityType(EntityUtils.getEntityType(row.type));
                if (placedEntity) {
                    phrase = Phrase.LOOKUP_BLOCK;
                    selector = Selector.SECOND;
                    tag = Color.RED + "-";
                    action = "a:block";
                }
                else {
                    phrase = Phrase.LOOKUP_INTERACTION;
                    selector = row.action == LookupActions.INTERACTION ? Selector.FIRST : Selector.SECOND;
                    tag = row.action == LookupActions.INTERACTION ? Color.WHITE + "-" : Color.RED + "-";
                }
                target = row.action == LookupActions.ENTITY_KILL && row.type == 0
                        ? playerName(connection, row.data)
                        : (row.action == LookupActions.INTERACTION ? materialName(row) : entityName(row.type));
            }
            else {
                phrase = Phrase.LOOKUP_BLOCK;
                selector = row.action == LookupActions.BLOCK_BREAK ? Selector.SECOND : Selector.FIRST;
                tag = row.action == LookupActions.BLOCK_BREAK ? Color.RED + "-" : Color.GREEN + "+";
                target = materialName(row);
            }

            Chat.sendComponent(sender, time + " " + tag + " "
                    + Phrase.build(phrase, Color.DARK_AQUA + rollbackFormat + userName + Color.WHITE + rollbackFormat,
                            Color.DARK_AQUA + rollbackFormat + target + Color.WHITE, selector));
        }

        outputCoordinates(row, now, action);
    }

    private void outputCoordinates(Row row, int now, String action) {
        String suffix = action == null ? "" : " (" + action + ")";
        String coordinates = ChatUtils.getCoordinates(command.getName(), row.worldId, row.x, row.y, row.z, true, true);
        Chat.sendComponent(sender, Color.WHITE + leftPadding(row, now) + Color.GREY + "^ " + coordinates + Color.GREY + Color.ITALIC + suffix);
    }

    private static String leftPadding(Row row, int now) {
        int timeLength = 50 + (ChatUtils.getTimeSince(row.time, now, false).replaceAll("[^0-9]", "").length() * 6);
        String padding = Color.BOLD + Strings.padStart("", 10, ' ');
        if (timeLength % 4 == 0) {
            return Strings.padStart("", timeLength / 4, ' ');
        }
        return padding + Color.WHITE + Strings.padStart("", (timeLength - 50) / 4, ' ');
    }

    private static String playerName(Connection connection, int userId) {
        String name = UserStatement.getName(connection, userId);
        return name == null || name.isEmpty() ? "unknown" : name;
    }

    private static InventoryFormat inventoryFormat(LogicalTable table, int rowAction) {
        Phrase phrase = Phrase.LOOKUP_CONTAINER;
        String selector;
        String tag;
        String action = table == LogicalTable.ITEM ? "a:item" : "a:container";

        switch (rowAction) {
            case ItemTransactionActions.DROP:
                phrase = Phrase.LOOKUP_ITEM;
                selector = Selector.SECOND;
                tag = Color.RED + "-";
                break;
            case ItemTransactionActions.PICKUP:
                phrase = Phrase.LOOKUP_ITEM;
                selector = Selector.FIRST;
                tag = Color.GREEN + "+";
                break;
            case ItemTransactionActions.REMOVE_ENDER:
                phrase = Phrase.LOOKUP_STORAGE;
                selector = Selector.SECOND;
                tag = Color.GREEN + "+";
                break;
            case ItemTransactionActions.ADD_ENDER:
                phrase = Phrase.LOOKUP_STORAGE;
                selector = Selector.FIRST;
                tag = Color.RED + "-";
                break;
            case ItemTransactionActions.THROW:
                phrase = Phrase.LOOKUP_PROJECTILE;
                selector = Selector.FIRST;
                tag = Color.RED + "-";
                break;
            case ItemTransactionActions.SHOOT:
                phrase = Phrase.LOOKUP_PROJECTILE;
                selector = Selector.SECOND;
                tag = Color.RED + "-";
                break;
            case ItemTransactionActions.CREATE:
            case ItemTransactionActions.BUY:
                selector = Selector.FIRST;
                tag = Color.GREEN + "+";
                break;
            case ItemTransactionActions.BREAK:
            case ItemTransactionActions.DESTROY:
            case ItemTransactionActions.SELL:
                selector = Selector.SECOND;
                tag = Color.RED + "-";
                break;
            case ItemTransactionActions.CRAFTED:
                phrase = Phrase.LOOKUP_CRAFT;
                selector = Selector.FIRST;
                tag = Color.GREEN + "+";
                action = "a:craft";
                break;
            case ItemTransactionActions.USED_TO_CRAFT:
                phrase = Phrase.LOOKUP_CRAFT;
                selector = Selector.SECOND;
                tag = Color.RED + "-";
                action = "a:craft";
                break;
            case ItemTransactionActions.EXTERNAL_ADD:
                phrase = Phrase.LOOKUP_INVENTORY_CHANGE;
                selector = Selector.FIRST;
                tag = Color.GREEN + "+";
                action = "a:inventorychange";
                break;
            case ItemTransactionActions.EXTERNAL_REMOVE:
                phrase = Phrase.LOOKUP_INVENTORY_CHANGE;
                selector = Selector.SECOND;
                tag = Color.RED + "-";
                action = "a:inventorychange";
                break;
            case ItemTransactionActions.REMOVE:
                selector = Selector.SECOND;
                tag = Color.RED + "-";
                break;
            case ItemTransactionActions.ADD:
            default:
                selector = Selector.FIRST;
                tag = Color.GREEN + "+";
                break;
        }
        return new InventoryFormat(phrase, selector, tag, action);
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

    private static String entityName(int type) {
        return EntityInteractionLookup.entityName(type);
    }

    private static String itemName(Row row) {
        Material material = ItemUtils.itemFilter(MaterialUtils.getType(row.type), false);
        if (material == null) {
            return materialName(row);
        }
        return StringUtils.nameFilter(material.name().toLowerCase(Locale.ROOT), row.data);
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

    private static String signMessage(ResultSet result) throws Exception {
        boolean front = result.getInt("face") == 0;
        int start = front ? 1 : 5;
        int end = front ? 4 : 8;
        StringBuilder message = new StringBuilder();
        for (int line = start; line <= end; line++) {
            String value = result.getString("line_" + line);
            if (value == null || value.isEmpty()) {
                continue;
            }
            message.append(value);
            if (!value.endsWith(" ")) {
                message.append(' ');
            }
        }
        return message.toString().trim();
    }

    private static final class InventoryFormat {
        private final Phrase phrase;
        private final String selector;
        private final String tag;
        private final String action;

        private InventoryFormat(Phrase phrase, String selector, String tag, String action) {
            this.phrase = phrase;
            this.selector = selector;
            this.tag = tag;
            this.action = action;
        }
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
        private byte[] metadata;

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
