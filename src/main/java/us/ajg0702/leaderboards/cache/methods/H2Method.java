package us.ajg0702.leaderboards.cache.methods;

import org.h2.jdbc.JdbcConnection;
import org.h2.message.DbException;
import us.ajg0702.leaderboards.LeaderboardPlugin;
import us.ajg0702.leaderboards.boards.TimedType;
import us.ajg0702.leaderboards.cache.Cache;
import us.ajg0702.leaderboards.cache.CacheMethod;
import us.ajg0702.leaderboards.utils.UnClosableConnection;
import us.ajg0702.utils.common.ConfigFile;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

public class H2Method implements CacheMethod {
    private Connection conn;
    private LeaderboardPlugin plugin;
    private ConfigFile config;
    private Cache cacheInstance;
    private boolean initialized = false;
    @Override
    public Connection getConnection() throws SQLException {
        try {
            if(conn == null || conn.isClosed()) {
                plugin.getLogger().warning("H2 connection is dead, making a new one");
                init(plugin, config, cacheInstance);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (!initialized) {
            throw new SQLException("H2 database not initialized");
        }
        return new UnClosableConnection(conn);
    }

    @Override
    public void init(LeaderboardPlugin plugin, ConfigFile config, Cache cacheInstance) {
        this.plugin = plugin;
        this.config = config;
        this.cacheInstance = cacheInstance;

        // fix h2 error messages not being found (due to relocation)
        try {
            Field field = DbException.class.getDeclaredField("MESSAGES");
            field.setAccessible(true);
            ((Properties) (field.get(new Properties())))
                    .load(getClass().getResourceAsStream("/h2_messages.prop"));
        } catch (IllegalAccessException | NoSuchFieldException | IOException e) {
            plugin.getLogger().log(Level.WARNING, "Unable to set h2 messages file! Error messages from h2 might not be very useful!", e);
        }

        File file = new File(plugin.getDataFolder(), "cache.trace.db");
        if(file.exists()) {
            plugin.getLogger().info("Deleting junk trace file");
            try {
                if(!file.delete()) {
                    plugin.getLogger().warning("Failed to delete junk trace file!");
                }
            } catch(SecurityException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete junk trace file: ", e);
            }
        }

        String url = "jdbc:h2:"+plugin.getDataFolder().getAbsolutePath()+File.separator+"cache;DATABASE_TO_UPPER=false;TRACE_LEVEL_FILE=0";
        try {
            //conn = DriverManager.getConnection(url);
            conn = new JdbcConnection(url, new Properties(), null, null, false);
        } catch (SQLException e) {
            plugin.getLogger().severe("Unnable to create cache file! The plugin will not work correctly!");
            e.printStackTrace();
            return;
        }
        initialized = true;
        List<String> tables = cacheInstance.getDbTableList();

        for(String tableName : tables) {
            if(!tableName.startsWith(cacheInstance.getTablePrefix())) continue;
            try {
                migrateTable(tableName);
            } catch(SQLException e) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "Unable to migrate H2 table \""+tableName+"\". Continuing with the remaining tables.",
                        e
                );
            }
        }
    }

    private void migrateTable(String tableName) throws SQLException {
        String table = quoteIdentifier(tableName);

        try(Statement statement = conn.createStatement()) {
            // Inspect the actual schema instead of trusting the table comment. Older
            // versions could leave the comment ahead of the real schema after a
            // partially failed migration.
            String yearly = TimedType.YEARLY.lowerName();
            ensureColumn(statement, tableName, yearly+"_delta", "BIGINT");
            ensureColumn(statement, tableName, yearly+"_lasttotal", "BIGINT");
            ensureColumn(statement, tableName, yearly+"_timestamp", "BIGINT");

            long migrationTime = System.currentTimeMillis();
            boolean addedTimestamp = false;
            for(TimedType type : TimedType.values()) {
                String column = Cache.reachedAtColumn(type);
                if(!columnExists(tableName, column)) {
                    if(!addedTimestamp) {
                        plugin.getLogger().info("Adding score achievement timestamps to H2 table "+tableName);
                        addedTimestamp = true;
                    }
                    ensureColumn(
                            statement,
                            tableName,
                            column,
                            "BIGINT DEFAULT "+migrationTime+" NOT NULL"
                    );
                }
            }

            // Index creation is an optimization and must never prevent schema repair.
            for(TimedType type : TimedType.values()) {
                String index = type == TimedType.ALLTIME ? "value" : type.lowerName()+"_delta";
                try {
                    statement.executeUpdate(
                            "CREATE INDEX IF NOT EXISTS "+quoteIdentifier(index)+
                                    " ON "+table+" ("+quoteIdentifier(index)+")"
                    );
                } catch(SQLException e) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "Unable to create H2 index \""+index+"\" on table \""+tableName+
                                    "\". The table migration will continue.",
                            e
                    );
                }
            }
            statement.executeUpdate("COMMENT ON TABLE "+table+" IS '4'");
        }
    }

    private void ensureColumn(Statement statement, String tableName, String columnName, String definition)
            throws SQLException {
        if(columnExists(tableName, columnName)) return;
        statement.executeUpdate(
                "ALTER TABLE "+quoteIdentifier(tableName)+" ADD COLUMN "+
                        quoteIdentifier(columnName)+" "+definition
        );
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try(PreparedStatement statement = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "+
                        "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?"
        )) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try(ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\""+identifier.replace("\"", "\"\"")+"\"";
    }

    @Override
    public void close(Connection connection) {}

    @Override
    public int getMaxConnections() {
        return 1;
    }

    @Override
    public void shutdown() {
        try {
            conn.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String formatStatement(String s) {
        return s.replaceAll("'", "\"");
    }

    @Override
    public String getName() {
        return "h2";
    }

    @Override
    public boolean requiresClose() {
        return false;
    }

    public void newConnection() {
        shutdown();
        init(plugin, config, cacheInstance);
    }
}
