package net.coreprotect.command.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.coreprotect.command.logical.LogicalQuery;
import net.coreprotect.command.logical.LogicalQuery.Operator;
import net.coreprotect.command.logical.LogicalQuerySql;
import net.coreprotect.command.logical.LogicalTable;
import net.coreprotect.command.lookup.LogicalLookupThread;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.DatabaseType;
import net.coreprotect.model.item.ItemTransactionActions;

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

    @Test
    void parsesNestedBooleanQueryWithSeparatedValue() {
        LogicalQuery query = LogicalQuery.parse(new String[] {
                "lookup", "time:2h", "and", "not(user:player1", "or", "user:", "player2)"
        });

        assertEquals(Operator.AND, query.getRoot().getOperator());
        assertEquals("time:2h", query.getRoot().getLeft().getTerm());
        assertEquals(Operator.NOT, query.getRoot().getRight().getOperator());
        assertEquals(Operator.OR, query.getRoot().getRight().getLeft().getOperator());
        assertEquals("user:player2", query.getRoot().getRight().getLeft().getRight().getTerm());
    }

    @Test
    void givesAndHigherPrecedenceThanOr() {
        LogicalQuery query = LogicalQuery.parse(new String[] {
                "lookup", "user:a", "or", "user:b", "and", "not", "user:c"
        });

        assertEquals(Operator.OR, query.getRoot().getOperator());
        assertEquals(Operator.AND, query.getRoot().getRight().getOperator());
        assertEquals(Operator.NOT, query.getRoot().getRight().getRight().getOperator());
    }

    @Test
    void rejectsUnclosedParentheses() {
        assertThrows(IllegalArgumentException.class, () -> LogicalQuery.parse(
                new String[] { "lookup", "time:2h", "and", "(user:a", "or", "user:b" }));
    }

    @Test
    void compilesNegatedUserGroupAsOneSqlPredicate() {
        ConfigHandler.playerIdCache.put("player1", 41);
        ConfigHandler.playerIdCache.put("player2", 42);
        try {
            LogicalQuery query = LogicalQuery.parse(new String[] {
                    "lookup", "time:2h", "and", "not(user:player1", "or", "user:player2)"
            });

            String sql = new LogicalQuerySql(query, null, null).compile(LogicalTable.BLOCK);

            assertTrue(sql.contains("user=41"));
            assertTrue(sql.contains("user=42"));
            assertTrue(sql.contains("NOT ("));
            assertTrue(sql.contains(" OR "));
            assertTrue(sql.contains("time>"));
        }
        finally {
            ConfigHandler.playerIdCache.remove("player1");
            ConfigHandler.playerIdCache.remove("player2");
        }
    }

    @Test
    void executesExampleExpressionAgainstSqlite() throws Exception {
        String previousPrefix = ConfigHandler.prefix;
        DatabaseType previousType = ConfigHandler.databaseType;
        ConfigHandler.prefix = "co_";
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        ConfigHandler.playerIdCache.clear();
        ConfigHandler.playerIdCacheReversed.clear();
        ConfigHandler.uuidCache.clear();
        ConfigHandler.uuidCacheReversed.clear();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE co_user (id INTEGER PRIMARY KEY, time INTEGER, user TEXT, uuid TEXT)");
            statement.execute("CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, action INTEGER, rolled_back INTEGER)");
            statement.execute("INSERT INTO co_user(id,time,user,uuid) VALUES (1,0,'player1','uuid-1'),(2,0,'player2','uuid-2'),(3,0,'player3','uuid-3')");
            long now = System.currentTimeMillis() / 1000L;
            statement.execute("INSERT INTO co_block(time,user,wid,x,y,z,type,data,action,rolled_back) VALUES "
                    + "(" + (now - 60) + ",1,1,0,0,0,1,0,1,0),"
                    + "(" + (now - 60) + ",2,1,0,0,0,1,0,1,0),"
                    + "(" + (now - 60) + ",3,1,0,0,0,1,0,1,0)");

            LogicalQuery query = LogicalQuery.parse(new String[] {
                    "lookup", "time:2h", "and", "not(user:player1", "or", "user:", "player2)"
            });
            String predicate = new LogicalQuerySql(query, connection, null).compile(LogicalTable.BLOCK);

            try (ResultSet rows = statement.executeQuery("SELECT user FROM co_block WHERE " + predicate)) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt("user"));
                assertFalse(rows.next());
            }
        }
        finally {
            ConfigHandler.prefix = previousPrefix;
            ConfigHandler.databaseType = previousType;
            ConfigHandler.playerIdCache.clear();
            ConfigHandler.playerIdCacheReversed.clear();
            ConfigHandler.uuidCache.clear();
            ConfigHandler.uuidCacheReversed.clear();
        }
    }

    @Test
    void loadsSqliteRowsUsingExplicitRowIdAlias() throws Exception {
        String previousPrefix = ConfigHandler.prefix;
        DatabaseType previousType = ConfigHandler.databaseType;
        ConfigHandler.prefix = "co_";
        ConfigHandler.databaseType = DatabaseType.SQLITE;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, action INTEGER, rolled_back INTEGER)");
            statement.execute("INSERT INTO co_block(time,user,wid,x,y,z,type,data,action,rolled_back) VALUES (1,1,1,2,3,4,5,0,1,3)");

            Method load = LogicalLookupThread.class.getDeclaredMethod("load", Statement.class, LogicalTable.class, String.class, int.class);
            load.setAccessible(true);
            List<?> rows = (List<?>) load.invoke(null, statement, LogicalTable.BLOCK, "1=1", -1);

            assertEquals(1, rows.size());
            Field rolledBack = rows.get(0).getClass().getDeclaredField("rolledBack");
            rolledBack.setAccessible(true);
            assertEquals(3, rolledBack.getInt(rows.get(0)));
            Method isRolledBack = LogicalLookupThread.class.getDeclaredMethod("isRolledBack", rows.get(0).getClass());
            isRolledBack.setAccessible(true);
            assertTrue((boolean) isRolledBack.invoke(null, rows.get(0)));
        }
        finally {
            ConfigHandler.prefix = previousPrefix;
            ConfigHandler.databaseType = previousType;
        }
    }

    @Test
    void ordersSameSecondInventoryActionsByCausality() throws Exception {
        Method priority = LogicalLookupThread.class.getDeclaredMethod("chronologyPriority", LogicalTable.class, int.class);
        priority.setAccessible(true);

        int command = (int) priority.invoke(null, LogicalTable.COMMAND, 0);
        int externalReceipt = (int) priority.invoke(null, LogicalTable.ITEM, ItemTransactionActions.EXTERNAL_ADD);
        int containerDeposit = (int) priority.invoke(null, LogicalTable.CONTAINER, ItemTransactionActions.ADD);

        assertTrue(externalReceipt > command);
        assertTrue(containerDeposit > externalReceipt);
    }

    @Test
    void exposesOriginalActionLabels() throws Exception {
        Method label = LogicalLookupThread.class.getDeclaredMethod("actionLabel", LogicalTable.class, int.class);
        label.setAccessible(true);

        assertEquals("a:block", label.invoke(null, LogicalTable.BLOCK, 1));
        assertEquals("a:container", label.invoke(null, LogicalTable.CONTAINER, ItemTransactionActions.ADD));
        assertEquals("a:craft", label.invoke(null, LogicalTable.ITEM, ItemTransactionActions.CRAFTED));
        assertEquals("a:inventorychange", label.invoke(null, LogicalTable.ITEM, ItemTransactionActions.EXTERNAL_ADD));
        assertEquals("a:command", label.invoke(null, LogicalTable.COMMAND, 0));
    }

    @Test
    void resolvesInternalEntityIdsToNames() throws Exception {
        int entityId = 700;
        ConfigHandler.entitiesReversed.put(entityId, "minecraft:zombie");
        try {
            Method entityName = LogicalLookupThread.class.getDeclaredMethod("entityName", int.class);
            entityName.setAccessible(true);

            assertEquals("zombie", entityName.invoke(null, entityId));
        }
        finally {
            ConfigHandler.entitiesReversed.remove(entityId);
        }
    }
}
