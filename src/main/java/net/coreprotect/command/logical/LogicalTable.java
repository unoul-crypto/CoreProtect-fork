package net.coreprotect.command.logical;

public enum LogicalTable {
    BLOCK("block"),
    CONTAINER("container"),
    ENTITY_CONTAINER("entity_container"),
    ITEM("item"),
    ENTITY_INTERACTION("entity_interaction"),
    CHAT("chat"),
    COMMAND("command"),
    SESSION("session"),
    USERNAME("username_log"),
    SIGN("sign");

    private final String tableName;

    LogicalTable(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean hasCoordinates() {
        return this != USERNAME;
    }

    public boolean hasMaterialType() {
        return this == BLOCK || this == CONTAINER || this == ENTITY_CONTAINER || this == ITEM;
    }
}
