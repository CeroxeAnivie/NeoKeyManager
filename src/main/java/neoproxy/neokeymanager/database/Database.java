package neoproxy.neokeymanager.database;

import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.manager.NodeAuthManager;
import neoproxy.neokeymanager.model.AdminDTOs;
import neoproxy.neokeymanager.model.DTOs;
import neoproxy.neokeymanager.model.DTOs.KeyStatus;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.repository.KeyRepository;
import neoproxy.neokeymanager.repository.NodeMappingRepository;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Database 门面类
 * 提供向后兼容的静态 API，内部委托给 Repository 层
 */
public class Database {
    private static final String DB_DRIVER = "org.sqlite.JDBC";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_PAUSED = "PAUSED";

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";

    // 应用层缓存和缓冲池
    private static final ConcurrentHashMap<String, CachedState> stateCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3000L;
    private static final ConcurrentHashMap<String, Double> trafficBuffer = new ConcurrentHashMap<>();

    // Repository 实例
    private static KeyRepository keyRepository;
    private static NodeMappingRepository nodeMappingRepository;

    // 内存数据库连接保持器
    private static Connection memoryDbConnection;
    private static String currentMemoryDbName;

    private static String getDbUrl() {
        if (Config.DB_PATH.startsWith(":memory:")) {
            String dbName = Config.DB_PATH.equals(":memory:") ? "default" : Config.DB_PATH.substring(8);
            return "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        }
        return "jdbc:sqlite:" + Config.DB_PATH;
    }

    private static boolean isTestEnvironment() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("org.junit.") ||
                    className.contains("org.testng.") ||
                    className.contains("org.junit.jupiter.")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void init() {
        try {
            boolean isMemoryDb = Config.DB_PATH.startsWith(":memory:");
            if (!isTestEnvironment() && !isMemoryDb) {
                ServerLogger.infoWithSource("Database", "nkm.db.loadingDriver");
            }
            Class.forName(DB_DRIVER);

            String dbUrl = getDbUrl();
            Connection keepAliveConn = DriverManager.getConnection(dbUrl);

            if (Config.DB_PATH.startsWith(":memory:")) {
                memoryDbConnection = keepAliveConn;
                currentMemoryDbName = Config.DB_PATH;
            }

            // 初始化 Repository
            keyRepository = new KeyRepository(dbUrl);
            nodeMappingRepository = new NodeMappingRepository(dbUrl);

            try (Statement stmt = keepAliveConn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL;");
                stmt.execute("PRAGMA synchronous = NORMAL;");
                stmt.execute("PRAGMA busy_timeout = 5000;");
                stmt.execute("PRAGMA temp_store = MEMORY;");
                stmt.execute("PRAGMA foreign_keys = ON;");
            }

            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS keys (
                                name VARCHAR(50) PRIMARY KEY,
                                balance DOUBLE NOT NULL,
                                rate DOUBLE NOT NULL,
                                expire_time VARCHAR(50),
                                default_port VARCHAR(50) NOT NULL,
                                max_conns INT NOT NULL DEFAULT 1,
                                status VARCHAR(20) DEFAULT 'ENABLED',
                                enable_web BOOLEAN DEFAULT 0,
                                is_single BOOLEAN DEFAULT 0,
                                custom_blocking_msg VARCHAR(255) DEFAULT NULL
                            )
                        """);

                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS node_ports (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                key_name VARCHAR(50),
                                node_id VARCHAR(50),
                                port VARCHAR(50),
                                FOREIGN KEY (key_name) REFERENCES keys(name) ON DELETE CASCADE,
                                UNIQUE(key_name, node_id)
                            )
                        """);

                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS key_aliases (
                                alias_name VARCHAR(50) PRIMARY KEY,
                                target_name VARCHAR(50) NOT NULL,
                                is_single BOOLEAN DEFAULT 0,
                                FOREIGN KEY (target_name) REFERENCES keys(name) ON DELETE CASCADE
                            )
                        """);

                migrateLegacySchema(conn);

                safeAddColumn(stmt, "keys", "max_conns", "INT DEFAULT 1");
                safeAddColumn(stmt, "keys", "enable_web", "BOOLEAN DEFAULT 0");
                safeAddColumn(stmt, "keys", "is_single", "BOOLEAN DEFAULT 0");
                safeAddColumn(stmt, "keys", "custom_blocking_msg", "VARCHAR(255) DEFAULT NULL");
                safeAddColumn(stmt, "key_aliases", "is_single", "BOOLEAN DEFAULT 0");

                if (!isTestEnvironment() && !isMemoryDb) {
                    ServerLogger.infoWithSource("Database", "nkm.db.schemaInit", "Engine: SQLite WAL");
                }
            }

            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NKM-DB-Flusher");
                t.setDaemon(true);
                return t;
            }).scheduleAtFixedRate(Database::flushTrafficBuffer, 5, 5, TimeUnit.SECONDS);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Database Init Failed. Missing sqlite-jdbc driver?");
            System.exit(1);
        }
    }

    private static void safeAddColumn(Statement stmt, String table, String col, String definition) {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + definition);
        } catch (SQLException e) {
            // 忽略 Duplicate column name 错误
        }
    }

    private static void migrateLegacySchema(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            boolean hasOldCol = false;
            try {
                stmt.executeQuery("SELECT is_enable FROM keys LIMIT 1");
                hasOldCol = true;
            } catch (SQLException ignored) {
            }

            if (hasOldCol) {
                ServerLogger.warnWithSource("Database", "nkm.db.migrating", "Upgrading: is_enable -> status");
                safeAddColumn(stmt, "keys", "status", "VARCHAR(20) DEFAULT 'ENABLED'");

                stmt.executeUpdate("UPDATE keys SET status = '" + STATUS_DISABLED + "' WHERE is_enable = 0");
                stmt.executeUpdate("UPDATE keys SET status = '" + STATUS_ENABLED + "' WHERE is_enable = 1");

                try {
                    stmt.execute("ALTER TABLE keys DROP COLUMN is_enable");
                } catch (SQLException ignored) {
                    ServerLogger.warnWithSource("Database", "nkm.db.migration", "Old column retained (Legacy SQLite)");
                }
                ServerLogger.infoWithSource("Database", "nkm.db.migrated");
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.migrationFail", e);
        }
    }

    static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(getDbUrl());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    // ==================== 流量缓冲逻辑 ====================

    public static void deductBalanceBatch(Map<String, Double> trafficMap) {
        if (trafficMap == null || trafficMap.isEmpty()) return;
        trafficMap.forEach((key, val) ->
                trafficBuffer.merge(key, val, Double::sum)
        );
    }

    private static void flushTrafficBuffer() {
        if (trafficBuffer.isEmpty()) return;

        Map<String, Double> snapshot = new HashMap<>();
        trafficBuffer.forEach((k, v) -> {
            snapshot.put(k, v);
            trafficBuffer.remove(k);
        });

        if (snapshot.isEmpty()) return;

        String sqlDeduct = "UPDATE keys SET balance = balance - ? WHERE name = ?";
        String sqlCheckPause = "UPDATE keys SET status = '" + STATUS_PAUSED + "' WHERE name = ? AND balance <= 0 AND status = '" + STATUS_ENABLED + "'";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sqlDeduct)) {
                for (Map.Entry<String, Double> entry : snapshot.entrySet()) {
                    stmt.setDouble(1, entry.getValue());
                    stmt.setString(2, entry.getKey());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            try (PreparedStatement stmt = conn.prepareStatement(sqlCheckPause)) {
                for (String key : snapshot.keySet()) {
                    stmt.setString(1, key);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            conn.commit();

            for (String key : snapshot.keySet()) {
                stateCache.remove(key);
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.deductFail", e);
            snapshot.forEach((k, v) -> trafficBuffer.merge(k, v, Double::sum));
        }
    }

    // ==================== 委托给 KeyRepository ====================

    public static boolean addKey(String name, double balance, double rate, String expireTime, String port, int maxConns) {
        return keyRepository.addKey(name, balance, rate, expireTime, port, maxConns);
    }

    public static void deleteKey(String realName) {
        keyRepository.deleteKey(realName);
    }

    public static boolean keyExists(String name) {
        return keyRepository.keyExists(name);
    }

    public static boolean renameKey(String oldName, String newName) {
        stateCache.remove(oldName);
        stateCache.remove(newName);
        return keyRepository.renameKey(oldName, newName);
    }

    public static DTOs.KeyStateResult getKeyStatus(String realName) {
        CachedState cached = stateCache.get(realName);
        if (cached != null && System.currentTimeMillis() < cached.expireTime) {
            return cached.result;
        }

        DTOs.KeyStateResult result = keyRepository.getKeyStatus(realName);
        if (result != null) {
            stateCache.put(realName, new CachedState(result, System.currentTimeMillis() + CACHE_TTL_MS));
        }
        return result;
    }

    public static boolean setKeyStatusStrict(String name, boolean enable) {
        String realName = getRealKeyName(name);
        if (realName == null) return false;
        stateCache.remove(realName);
        return keyRepository.setKeyStatusStrict(realName, enable);
    }

    public static void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb, Integer maxConns) {
        stateCache.remove(name);
        keyRepository.updateKey(name, balance, rate, port, expireTime, enableWeb, maxConns);
    }

    public static boolean setKeyMaxConns(String name, int maxConns) {
        return keyRepository.setKeyMaxConns(name, maxConns);
    }

    public static boolean setWebStatus(String name, boolean enable) {
        return keyRepository.setWebStatus(name, enable);
    }

    public static String getRealKeyName(String input) {
        return keyRepository.getRealKeyName(input);
    }

    public static boolean isAlias(String name) {
        return keyRepository.isAlias(name);
    }

    public static void addLink(String alias, String target) {
        keyRepository.addLink(alias, target);
    }

    public static boolean deleteAlias(String alias) {
        return keyRepository.deleteAlias(alias);
    }

    public static Map<String, String> getAllLinks() {
        return keyRepository.getAllLinks();
    }

    public static boolean setKeySingle(String name, boolean isSingle) {
        return keyRepository.setKeySingle(name, isSingle);
    }

    public static boolean isNameSingle(String name) {
        return keyRepository.isNameSingle(name);
    }

    public static List<String> getSingleKeys() {
        return keyRepository.getSingleKeys();
    }

    public static boolean setCustomBlockingMsg(String realName, String msg) {
        return keyRepository.setCustomBlockingMsg(realName, msg);
    }

    public static String getCustomBlockingMsg(String realName) {
        return keyRepository.getCustomBlockingMsg(realName);
    }

    public static Map<String, String> getAllCustomBlockingMsgs() {
        return keyRepository.getAllCustomBlockingMsgs();
    }

    public static Map<String, Object> getKeyPortInfo(String realName) {
        return keyRepository.getKeyPortInfo(realName);
    }

    public static Map<String, Object> getKeyInfoFull(String realName, String nodeId) {
        return keyRepository.getKeyInfoFull(realName, nodeId);
    }

    public static int getKeyMaxConns(String realName) {
        return keyRepository.getKeyMaxConns(realName);
    }

    public static Map<String, Protocol.KeyMetadata> getBatchKeyMetadata(List<String> realKeys) {
        return keyRepository.getBatchKeyMetadata(realKeys);
    }

    public static List<AdminDTOs.KeyDetail> getAllKeysStructured(boolean includeMaps, String targetKeyName) {
        Map<String, List<AdminDTOs.MapNode>> mapInfo = new HashMap<>();
        if (includeMaps) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
                while (rs.next()) {
                    String nodeId = rs.getString("node_id");
                    if (NodeAuthManager.getInstance().isNodeExplicitlyRegistered(nodeId)) {
                        mapInfo.computeIfAbsent(rs.getString("key_name"), k -> new ArrayList<>())
                                .add(new AdminDTOs.MapNode(nodeId, rs.getString("port")));
                    }
                }
            } catch (SQLException ignored) {
            }
        }
        return keyRepository.getAllKeysStructured(includeMaps, targetKeyName, mapInfo);
    }

    public static List<Map<String, String>> getAllKeysRaw() {
        Map<String, List<String>> mapInfo = new HashMap<>();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
            while (rs.next()) {
                String nodeId = rs.getString("node_id");
                if (NodeAuthManager.getInstance().isNodeExplicitlyRegistered(nodeId)) {
                    mapInfo.computeIfAbsent(rs.getString("key_name"), k -> new ArrayList<>())
                            .add(String.format("%s -> %s", nodeId, rs.getString("port")));
                }
            }
        } catch (SQLException ignored) {
        }
        return keyRepository.getAllKeysRaw(mapInfo);
    }

    // ==================== 委托给 NodeMappingRepository ====================

    public static void addNodePort(String realName, String nodeId, String port) {
        nodeMappingRepository.addNodePort(realName, nodeId, port);
    }

    public static boolean deleteNodeMap(String realName, String nodeId) {
        return nodeMappingRepository.deleteNodeMap(realName, nodeId);
    }

    public static int deleteNodeMapByNode(String nodeId) {
        return nodeMappingRepository.deleteNodeMapByNode(nodeId);
    }

    public static boolean hasSpecificMap(String realKeyName, String nodeId) {
        return nodeMappingRepository.hasSpecificMap(realKeyName, nodeId);
    }

    public static boolean isNodeMappedAnywhere(String nodeId) {
        return nodeMappingRepository.isNodeMappedAnywhere(nodeId);
    }

    private record CachedState(DTOs.KeyStateResult result, long expireTime) {
    }
}
