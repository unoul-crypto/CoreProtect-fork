package net.coreprotect.database;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;

import net.coreprotect.consumer.Consumer;
import net.coreprotect.consumer.Queue;
import net.coreprotect.model.action.EntityActionFilter;
import net.coreprotect.model.action.LookupActions;
import net.coreprotect.model.lookup.LookupRollbackState;
import net.coreprotect.model.lookup.LookupSummaryPage;
import net.coreprotect.model.lookup.LookupSummaryRow;
import net.coreprotect.model.item.ItemTransactionActions;
import net.coreprotect.model.selection.SelectionRegistry;
import net.coreprotect.utility.ErrorReporter;

public class Lookup extends Queue {

    public static long countLookupRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, boolean restrictWorld, boolean lookup) {
        return countLookupRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, Collections.emptyList(), location, radius, rowData, startTime, endTime, restrictWorld, lookup);
    }

    public static long countLookupRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, List<String> messageFilters, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, boolean restrictWorld, boolean lookup) {
        return countLookupRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, messageFilters, Collections.emptySet(), Collections.emptySet(), location, radius, rowData, startTime, endTime, restrictWorld, lookup);
    }

    public static long countLookupRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, boolean restrictWorld, boolean lookup) {
        return countLookupRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, EntityActionFilter.DEFAULT, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, restrictWorld, lookup);
    }

    public static long countLookupRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, boolean restrictWorld, boolean lookup) {
        return countLookupRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, restrictWorld, lookup, null);
    }

    public static long countLookupRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, boolean restrictWorld, boolean lookup, Integer entityContainerId) {
        return countLookupRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, restrictWorld, lookup, entityContainerId, LookupRollbackState.ANY);
    }

    public static long countLookupRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, boolean restrictWorld, boolean lookup, Integer entityContainerId, LookupRollbackState rollbackState) {
        if (SelectionRegistry.hasExactSelection(radius)) {
            List<Object[]> exactRows = LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, null, startTime, endTime, -1, -1, restrictWorld, lookup, entityContainerId, rollbackState);
            return exactRows == null ? 0L : exactRows.size();
        }

        Long rows = 0L;
        boolean paused = false;

        try {
            while (Consumer.isPaused && !Consumer.isPersistenceHalted()) {
                Thread.sleep(1);
            }
            Consumer.isPaused = true;
            paused = true;

            ResultSet results = LookupRaw.rawLookupResultSet(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, null, startTime, endTime, -1, -1, restrictWorld, lookup, true, entityContainerId, rollbackState);
            while (results.next()) {
                int resultTable = results.getInt("tbl");
                long count = results.getLong("count");
                if (rowData != null && resultTable >= 0 && resultTable < rowData.length) {
                    rowData[resultTable] = count;
                }
                rows += count;
            }
            results.close();
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        finally {
            if (paused && !Consumer.isPersistenceHalted()) {
                Consumer.isPaused = false;
            }
        }

        return rows;
    }

    public static long countSummaryRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, boolean restrictWorld, Integer entityContainerId) {
        return countSummaryRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, restrictWorld, entityContainerId, LookupRollbackState.ANY);
    }

    public static long countSummaryRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, boolean restrictWorld, Integer entityContainerId, LookupRollbackState rollbackState) {
        if (!hasSummaryActions(actionList)) {
            return 0L;
        }
        if (SelectionRegistry.hasExactSelection(radius)) {
            return exactSummaryRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, restrictWorld, entityContainerId, rollbackState).size();
        }

        boolean paused = false;
        try {
            while (Consumer.isPaused && !Consumer.isPersistenceHalted()) {
                Thread.sleep(1);
            }
            Consumer.isPaused = true;
            paused = true;
            try (ResultSet results = LookupRaw.rawSummaryResultSet(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, summaryActions(actionList), entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, -1, -1, restrictWorld, entityContainerId, true, rollbackState)) {
                return results.next() ? results.getLong("count") : 0L;
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
            return 0L;
        }
        finally {
            if (paused && !Consumer.isPersistenceHalted()) {
                Consumer.isPaused = false;
            }
        }
    }

    public static List<LookupSummaryRow> performSummaryLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, Integer entityContainerId) {
        return performSummaryLookup(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, limitOffset, limitCount, restrictWorld, entityContainerId, LookupRollbackState.ANY);
    }

    public static List<LookupSummaryRow> performSummaryLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, Integer entityContainerId, LookupRollbackState rollbackState) {
        if (!hasSummaryActions(actionList)) {
            return Collections.emptyList();
        }
        if (SelectionRegistry.hasExactSelection(radius)) {
            return page(exactSummaryRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, restrictWorld, entityContainerId, rollbackState), limitOffset, limitCount);
        }

        List<LookupSummaryRow> rows = new ArrayList<>();
        boolean paused = false;
        try {
            while (Consumer.isPaused && !Consumer.isPersistenceHalted()) {
                Thread.sleep(1);
            }
            Consumer.isPaused = true;
            paused = true;
            try (ResultSet results = LookupRaw.rawSummaryResultSet(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, summaryActions(actionList), entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, limitOffset, limitCount, restrictWorld, entityContainerId, false, rollbackState)) {
                while (results.next()) {
                    rows.add(summaryRow(results));
                }
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        finally {
            if (paused && !Consumer.isPersistenceHalted()) {
                Consumer.isPaused = false;
            }
        }
        return rows;
    }

    public static LookupSummaryPage performSummaryLookupPage(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, Integer entityContainerId) {
        return performSummaryLookupPage(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, limitOffset, limitCount, restrictWorld, entityContainerId, LookupRollbackState.ANY);
    }

    public static LookupSummaryPage performSummaryLookupPage(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, Integer entityContainerId, LookupRollbackState rollbackState) {
        if (!hasSummaryActions(actionList)) {
            return new LookupSummaryPage(0L, Collections.emptyList());
        }
        if (SelectionRegistry.hasExactSelection(radius)) {
            List<LookupSummaryRow> exactRows = exactSummaryRows(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, restrictWorld, entityContainerId, rollbackState);
            return new LookupSummaryPage(exactRows.size(), page(exactRows, limitOffset, limitCount));
        }

        List<LookupSummaryRow> rows = new ArrayList<>();
        long totalRows = 0L;
        boolean paused = false;
        try {
            while (Consumer.isPaused && !Consumer.isPersistenceHalted()) {
                Thread.sleep(1);
            }
            Consumer.isPaused = true;
            paused = true;
            try (ResultSet results = LookupRaw.rawSummaryPageResultSet(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, summaryActions(actionList), entityActionFilter, loadedEntityUuids, loadedEntityCandidates, location, radius, startTime, endTime, limitOffset, limitCount, restrictWorld, entityContainerId, rollbackState)) {
                while (results.next()) {
                    if (rows.isEmpty()) {
                        totalRows = results.getLong("total_count");
                    }
                    rows.add(summaryRow(results));
                }
            }
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
        finally {
            if (paused && !Consumer.isPersistenceHalted()) {
                Consumer.isPaused = false;
            }
        }
        return new LookupSummaryPage(totalRows, rows);
    }

    public static boolean supportsSummaryWindowFunctions(Statement statement) {
        try {
            DatabaseMetaData metadata = statement.getConnection().getMetaData();
            String product = metadata.getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            if (product.contains("clickhouse") || product.contains("duckdb")) {
                return true;
            }
            int major = metadata.getDatabaseMajorVersion();
            int minor = metadata.getDatabaseMinorVersion();
            if (product.contains("sqlite")) {
                return major > 3 || (major == 3 && minor >= 25);
            }
            if (product.contains("mariadb")) {
                return major > 10 || (major == 10 && minor >= 2);
            }
            return product.contains("mysql") && major >= 8;
        }
        catch (Exception e) {
            return false;
        }
    }

    private static LookupSummaryRow summaryRow(ResultSet results) throws Exception {
        return new LookupSummaryRow(results.getInt("user"), results.getInt("type"), results.getLong("removed_amount"), results.getLong("placed_amount"), results.getLong("amount"));
    }

    private static boolean hasSummaryActions(List<Integer> actionList) {
        return actionList.isEmpty() || actionList.contains(LookupActions.BLOCK_BREAK) || actionList.contains(LookupActions.BLOCK_PLACE) || actionList.contains(LookupActions.CONTAINER) || actionList.contains(LookupActions.ITEM);
    }

    private static List<Integer> summaryActions(List<Integer> actionList) {
        if (actionList.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> actions = new ArrayList<>();
        for (Integer action : actionList) {
            if ((action == LookupActions.BLOCK_BREAK || action == LookupActions.BLOCK_PLACE || action == LookupActions.CONTAINER || action == LookupActions.ITEM) && !actions.contains(action)) {
                actions.add(action);
            }
        }
        return actions;
    }

    private static List<LookupSummaryRow> exactSummaryRows(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, long startTime, long endTime, boolean restrictWorld, Integer entityContainerId, LookupRollbackState rollbackState) {
        List<Object[]> rawRows = LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, summaryActions(actionList), entityActionFilter, Collections.emptyList(), loadedEntityUuids, loadedEntityCandidates, location, radius, null, startTime, endTime, -1, -1, restrictWorld, true, entityContainerId, rollbackState);
        if (rawRows == null || rawRows.isEmpty()) {
            return Collections.emptyList();
        }

        boolean inventoryQuery = LookupActions.isInventoryLookup(actionList);
        int blockPositiveAction = inventoryQuery ? LookupActions.BLOCK_BREAK : LookupActions.BLOCK_PLACE;
        int transactionPositiveAction = inventoryQuery ? ItemTransactionActions.REMOVE : ItemTransactionActions.ADD;
        Map<Long, long[]> grouped = new HashMap<>();

        for (Object[] row : rawRows) {
            if (row.length < 12) {
                continue;
            }
            int userId = (Integer) row[2];
            int materialId = (Integer) row[6];
            int action = (Integer) row[8];
            int amount = (Integer) row[11];
            boolean blockContribution = amount == -1 && (action == LookupActions.BLOCK_BREAK || action == LookupActions.BLOCK_PLACE);
            boolean transactionContribution = amount != -1 && action >= ItemTransactionActions.REMOVE && action <= ItemTransactionActions.USED_TO_CRAFT;
            if (!blockContribution && !transactionContribution) {
                continue;
            }

            long delta;
            if (amount == -1) {
                delta = action == blockPositiveAction ? 1L : -1L;
            }
            else {
                boolean positive = action == transactionPositiveAction
                        || action == ItemTransactionActions.PICKUP
                        || action == ItemTransactionActions.REMOVE_ENDER
                        || action == ItemTransactionActions.CREATE
                        || action == ItemTransactionActions.BUY
                        || action == ItemTransactionActions.CRAFTED;
                delta = positive ? amount : -amount;
            }

            long key = ((long) userId << 32) ^ (materialId & 0xffffffffL);
            long[] amounts = grouped.computeIfAbsent(key, ignored -> new long[3]);
            if (delta < 0) {
                amounts[0] -= delta;
            }
            else {
                amounts[1] += delta;
            }
            amounts[2] += delta;
        }

        List<LookupSummaryRow> rows = new ArrayList<>(grouped.size());
        for (Map.Entry<Long, long[]> entry : grouped.entrySet()) {
            int userId = (int) (entry.getKey() >> 32);
            int materialId = entry.getKey().intValue();
            long[] amounts = entry.getValue();
            rows.add(new LookupSummaryRow(userId, materialId, amounts[0], amounts[1], amounts[2]));
        }
        rows.sort(Comparator.comparingLong((LookupSummaryRow row) -> Math.abs(row.getAmount())).reversed()
                .thenComparingInt(LookupSummaryRow::getUserId)
                .thenComparingInt(LookupSummaryRow::getMaterialId));
        return rows;
    }

    private static List<LookupSummaryRow> page(List<LookupSummaryRow> rows, int limitOffset, int limitCount) {
        if (limitOffset < 0 || limitCount < 0) {
            return rows;
        }
        int fromIndex = Math.min(limitOffset, rows.size());
        int toIndex = Math.min(fromIndex + limitCount, rows.size());
        return new ArrayList<>(rows.subList(fromIndex, toIndex));
    }

    public static List<String[]> performLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, Location location, Integer[] radius, long startTime, long endTime, boolean restrictWorld, boolean lookup) {
        List<String[]> newList = new ArrayList<>();

        try {
            List<Object[]> lookupList = LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, location, radius, null, startTime, endTime, -1, -1, restrictWorld, lookup);
            newList = LookupConverter.convertRawLookup(statement, lookupList);
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }

        return newList;
    }

    public static List<String[]> performPartialLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return performPartialLookup(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, Collections.emptyList(), location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup);
    }

    public static List<String[]> performPartialLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, List<String> messageFilters, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return performPartialLookup(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, messageFilters, Collections.emptySet(), Collections.emptySet(), location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup);
    }

    public static List<String[]> performPartialLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return performPartialLookup(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, EntityActionFilter.DEFAULT, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup);
    }

    public static List<String[]> performPartialLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return performPartialLookup(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup, null);
    }

    public static List<String[]> performPartialLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup, Integer entityContainerId) {
        return performPartialLookup(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup, entityContainerId, LookupRollbackState.ANY);
    }

    public static List<String[]> performPartialLookup(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, List<String> messageFilters, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup, Integer entityContainerId, LookupRollbackState rollbackState) {
        List<String[]> newList = new ArrayList<>();

        try {
            List<Object[]> lookupList = LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, messageFilters, loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup, entityContainerId, rollbackState);
            newList = LookupConverter.convertRawLookup(statement, lookupList);
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }

        return newList;
    }

    public static String whoPlaced(Statement statement, BlockState block) {
        return BlockLookup.whoPlaced(statement, block);
    }

    public static String whoPlacedCache(Block block) {
        return BlockLookup.whoPlacedCache(block);
    }

    public static String whoPlacedCache(BlockState block) {
        return BlockLookup.whoPlacedCache(block);
    }

    public static String whoRemovedCache(BlockState block) {
        return BlockLookup.whoRemovedCache(block);
    }

    // Maintain backward compatibility
    protected static List<Object[]> performLookupRaw(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup);
    }

    protected static List<Object[]> performLookupRaw(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, Collections.emptyList(), loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup);
    }

    protected static List<Object[]> performLookupRaw(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup) {
        return LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, Collections.emptyList(), loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup);
    }

    protected static List<Object[]> performLookupRaw(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, EntityActionFilter entityActionFilter, Set<UUID> loadedEntityUuids, Set<UUID> loadedEntityCandidates, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup, Integer entityContainerId) {
        return LookupRaw.performLookupRaw(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, entityActionFilter, Collections.emptyList(), loadedEntityUuids, loadedEntityCandidates, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup, entityContainerId);
    }

    // Maintain backward compatibility
    private static ResultSet rawLookupResultSet(Statement statement, CommandSender user, List<String> checkUuids, List<String> checkUsers, List<Object> restrictList, Map<Object, Boolean> excludeList, List<String> excludeUserList, List<Integer> actionList, Location location, Integer[] radius, Long[] rowData, long startTime, long endTime, int limitOffset, int limitCount, boolean restrictWorld, boolean lookup, boolean count) {
        return LookupRaw.rawLookupResultSet(statement, user, checkUuids, checkUsers, restrictList, excludeList, excludeUserList, actionList, location, radius, rowData, startTime, endTime, limitOffset, limitCount, restrictWorld, lookup, count);
    }
}
