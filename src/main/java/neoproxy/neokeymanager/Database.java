package neoproxy.neokeymanager;

import neoproxy.neokeymanager.DTOs.KeyStateResult;
import neoproxy.neokeymanager.DTOs.KeyStatus;
import neoproxy.neokeymanager.admin.AdminDTOs;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Database {
    // [CONFIG] 切换为 SQLite 驱动
    private static final String DB_DRIVER = "org.sqlite.JDBC";
    private static final String DB_USER = "";     // SQLite 不需要
    private static final String DB_PASSWORD = ""; // SQLite 不需要
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_PAUSED = "PAUSED";

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";

    // [应用层缓存] 保持原有逻辑，减少 DB 读压力
    private static final ConcurrentHashMap<String, CachedState> stateCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3000L;

    // [应用层缓冲] 保持原有逻辑，流量扣费内存聚合，极大降低 IO
    private static final ConcurrentHashMap<String, Double> trafficBuffer = new ConcurrentHashMap<>();

    // [性能优化] 保持一个连接以维持 WAL 共享内存映射 (Shared-Memory)，减少系统调用开销
    private static Connection keepAliveConn;

    private static String getDbUrl() {
        return "jdbc:sqlite:" + Config.DB_PATH;
    }

    public static void init() {
        try {
            ServerLogger.infoWithSource("Database", "nkm.db.loadingDriver");
            Class.forName(DB_DRIVER);

            // [CRITICAL] 建立常驻连接，并进行 SQLite 核心性能调优
            // 这里对应参考代码的 DatabaseContext 构造函数逻辑
            keepAliveConn = DriverManager.getConnection(getDbUrl());
            try (Statement stmt = keepAliveConn.createStatement()) {
                // 1. 开启 WAL 模式：实现一写多读，消除锁竞争
                stmt.execute("PRAGMA journal_mode = WAL;");
                // 2. 同步模式 NORMAL：在断电保护和写入速度间取得最佳平衡
                stmt.execute("PRAGMA synchronous = NORMAL;");
                // 3. 忙碌等待：防止高并发下的 database is locked
                stmt.execute("PRAGMA busy_timeout = 5000;");
                // 4. 临时文件存内存：进一步减少 IO
                stmt.execute("PRAGMA temp_store = MEMORY;");
                // 5. 强制开启外键 (虽然 getConnection 会做，但 init 也需要)
                stmt.execute("PRAGMA foreign_keys = ON;");
            }

            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                // [SCHEMA] 初始化表结构
                // SQLite 兼容性处理：使用 0/1 代替 FALSE/TRUE 以确保最大兼容性

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

                // [DIALECT] SQLite 自增主键的特殊写法: INTEGER PRIMARY KEY AUTOINCREMENT
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

                // [SCHEMA PATCH] 热更新：无损添加字段
                // 采用 try-catch 忽略错误的方式，这是最稳健的跨版本兼容写法
                safeAddColumn(stmt, "keys", "max_conns", "INT DEFAULT 1");
                safeAddColumn(stmt, "keys", "enable_web", "BOOLEAN DEFAULT 0");
                safeAddColumn(stmt, "keys", "is_single", "BOOLEAN DEFAULT 0");
                safeAddColumn(stmt, "keys", "custom_blocking_msg", "VARCHAR(255) DEFAULT NULL");
                safeAddColumn(stmt, "key_aliases", "is_single", "BOOLEAN DEFAULT 0");

                ServerLogger.infoWithSource("Database", "nkm.db.schemaInit", "Engine: SQLite WAL");
            }

            // [IO BUFFER] 启动定时刷库任务
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

    /**
     * [工具] 安全添加列，忽略已存在的错误
     */
    private static void safeAddColumn(Statement stmt, String table, String col, String definition) {
        try {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + definition);
        } catch (SQLException e) {
            // 忽略 Duplicate column name 错误
        }
    }

    private static void migrateLegacySchema(Connection conn) {
        // 简化的迁移逻辑，利用 SQLite 的宽容性
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

                // SQLite 新版支持 DROP COLUMN，但在旧版可能会失败。
                // 工业级实践：只做数据迁移，不强求物理删除旧列，避免表重写带来的风险。
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

    private static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(getDbUrl());
        // [CRITICAL] 对应参考代码：SQLite 每次建立连接都要手动开启外键约束，它不持久化
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    // ==================== 以下业务逻辑保持原样，仅做 SQL 微调 ====================

    public static boolean setCustomBlockingMsg(String realName, String msg) {
        String sql = "UPDATE keys SET custom_blocking_msg = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, msg);
            stmt.setString(2, realName);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static String getCustomBlockingMsg(String realName) {
        String sql = "SELECT custom_blocking_msg FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("custom_blocking_msg");
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public static Map<String, String> getAllCustomBlockingMsgs() {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT name, custom_blocking_msg FROM keys WHERE custom_blocking_msg IS NOT NULL";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String msg = rs.getString("custom_blocking_msg");
                if (msg != null && !msg.isEmpty()) {
                    result.put(rs.getString("name"), msg);
                }
            }
        } catch (SQLException ignored) {
        }
        return result;
    }

    public static boolean isNodeMappedAnywhere(String nodeId) {
        String sql = "SELECT 1 FROM node_ports WHERE LOWER(node_id) = LOWER(?) LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nodeId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static void deductBalanceBatch(Map<String, Double> trafficMap) {
        if (trafficMap == null || trafficMap.isEmpty()) return;
        trafficMap.forEach((key, val) ->
                trafficBuffer.merge(key, val, Double::sum)
        );
    }

    // ==================== Traffic Buffer Logic ====================

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
            // 回滚逻辑：写库失败，将流量加回缓冲区
            snapshot.forEach((k, v) -> trafficBuffer.merge(k, v, Double::sum));
        }
    }

    public static KeyStateResult getKeyStatus(String realName) {
        CachedState cached = stateCache.get(realName);
        if (cached != null && System.currentTimeMillis() < cached.expireTime) {
            return cached.result;
        }

        String sql = "SELECT status, balance, expire_time FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;

                String dbStatus = rs.getString("status");
                double balance = rs.getDouble("balance");
                String expireTime = rs.getString("expire_time");

                KeyStateResult result;
                if (STATUS_DISABLED.equalsIgnoreCase(dbStatus)) {
                    result = new KeyStateResult(KeyStatus.DISABLED, "Disabled by admin");
                } else {
                    String pauseReason = checkConditions(balance, expireTime);
                    if (pauseReason != null) {
                        if (!STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            updateKeyStatusColumn(realName, STATUS_PAUSED);
                        }
                        result = new KeyStateResult(KeyStatus.PAUSED, pauseReason);
                    } else {
                        if (STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            updateKeyStatusColumn(realName, STATUS_ENABLED);
                        }
                        result = new KeyStateResult(KeyStatus.ENABLED, "OK");
                    }
                }
                stateCache.put(realName, new CachedState(result, System.currentTimeMillis() + CACHE_TTL_MS));
                return result;
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.queryFail", e);
            return null;
        }
    }

    public static boolean setKeyStatusStrict(String name, boolean enable) {
        String realName = getRealKeyName(name);
        if (realName == null) return false;
        stateCache.remove(realName);

        if (!enable) {
            return updateKeyStatusColumn(realName, STATUS_DISABLED);
        }
        Map<String, Object> raw = getKeyRaw(realName);
        if (raw == null) return false;

        String failReason = checkConditions((Double) raw.get("balance"), (String) raw.get("expire_time"));
        if (failReason != null) {
            updateKeyStatusColumn(realName, STATUS_PAUSED);
            ServerLogger.warnWithSource("KeyManager", "nkm.warn.enableRejected", realName, failReason);
            return false;
        }
        return updateKeyStatusColumn(realName, STATUS_ENABLED);
    }

    private static boolean updateKeyStatusColumn(String name, String status) {
        String sql = "UPDATE keys SET status = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, name);
            stateCache.remove(name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private static String checkConditions(double balance, String expireTime) {
        if (balance <= 0) return "Balance Depleted";
        if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
            try {
                if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER))) {
                    return "Expired";
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static boolean setKeyMaxConns(String name, int maxConns) {
        String sql = "UPDATE keys SET max_conns = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, maxConns);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean setWebStatus(String name, boolean enable) {
        String sql = "UPDATE keys SET enable_web = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enable);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb, Integer maxConns) {
        stateCache.remove(name);
        StringBuilder sql = new StringBuilder("UPDATE keys SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;

        if (balance != null) {
            sql.append(first ? "" : ", ").append("balance = ?");
            params.add(balance);
            first = false;
        }
        if (rate != null) {
            sql.append(first ? "" : ", ").append("rate = ?");
            params.add(rate);
            first = false;
        }
        if (expireTime != null) {
            sql.append(first ? "" : ", ").append("expire_time = ?");
            params.add(expireTime);
            first = false;
        }
        if (enableWeb != null) {
            sql.append(first ? "" : ", ").append("enable_web = ?");
            params.add(enableWeb);
            first = false;
        }
        if (maxConns != null) {
            sql.append(first ? "" : ", ").append("max_conns = ?");
            params.add(maxConns);
            first = false;
        }
        if (port != null) {
            sql.append(first ? "" : ", ").append("default_port = ?");
            params.add(port);
            first = false;
        }

        sql.append(" WHERE name = ?");
        params.add(name);

        if (first) return;

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));
            stmt.executeUpdate();

            Map<String, Object> raw = getKeyRaw(name);
            if (raw != null) {
                String reason = checkConditions((Double) raw.get("balance"), (String) raw.get("expire_time"));
                String currentStatus = (String) raw.get("status");
                if (reason == null && STATUS_PAUSED.equalsIgnoreCase(currentStatus)) {
                    updateKeyStatusColumn(name, STATUS_ENABLED);
                    ServerLogger.infoWithSource("KeyManager", "nkm.info.autoEnabled", name);
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.updateFail", e, name);
        }
    }

    public static boolean renameKey(String oldName, String newName) {
        // [COMPATIBILITY] SQLite 支持 INSERT INTO ... SELECT
        String copyKeySql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single, custom_blocking_msg) SELECT ?, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single, custom_blocking_msg FROM keys WHERE name = ?";
        String updatePortsSql = "UPDATE node_ports SET key_name = ? WHERE key_name = ?";
        String updateAliasesSql = "UPDATE key_aliases SET target_name = ? WHERE target_name = ?";
        String delOldKeySql = "DELETE FROM keys WHERE name = ?";

        stateCache.remove(oldName);
        stateCache.remove(newName);

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmtCopy = conn.prepareStatement(copyKeySql);
                 PreparedStatement stmtPorts = conn.prepareStatement(updatePortsSql);
                 PreparedStatement stmtAliases = conn.prepareStatement(updateAliasesSql);
                 PreparedStatement stmtDel = conn.prepareStatement(delOldKeySql)) {

                stmtCopy.setString(1, newName);
                stmtCopy.setString(2, oldName);
                if (stmtCopy.executeUpdate() == 0) throw new SQLException("Failed to copy key");

                stmtPorts.setString(1, newName);
                stmtPorts.setString(2, oldName);
                stmtPorts.executeUpdate();

                stmtAliases.setString(1, newName);
                stmtAliases.setString(2, oldName);
                stmtAliases.executeUpdate();

                stmtDel.setString(1, oldName);
                stmtDel.executeUpdate();

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                ServerLogger.error("Database", "nkm.db.renameFail", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static void deleteKey(String realName) {
        stateCache.remove(realName);
        String sql = "DELETE FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            stmt.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public static String getRealKeyName(String input) {
        if (input == null) return null;
        if (keyExists(input)) return input;
        String sql = "SELECT target_name FROM key_aliases WHERE alias_name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, input);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("target_name");
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public static boolean isAlias(String name) {
        String sql = "SELECT 1 FROM key_aliases WHERE alias_name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    public static void addLink(String alias, String target) {
        String sql = "INSERT INTO key_aliases (alias_name, target_name, is_single) VALUES (?, ?, 0)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, alias);
            stmt.setString(2, target);
            stmt.executeUpdate();
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.updateFail", e, alias);
            throw new RuntimeException(e.getMessage());
        }
    }

    public static boolean deleteAlias(String alias) {
        String sql = "DELETE FROM key_aliases WHERE alias_name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, alias);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static Map<String, String> getAllLinks() {
        Map<String, String> map = new HashMap<>();
        String sql = "SELECT alias_name, target_name, is_single FROM key_aliases";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String val = rs.getString("target_name");
                if (rs.getBoolean("is_single")) val += " [Single]";
                map.put(rs.getString("alias_name"), val);
            }
        } catch (SQLException ignored) {
        }
        return map;
    }

    public static boolean setKeySingle(String name, boolean isSingle) {
        if (isAlias(name)) {
            String sql = "UPDATE key_aliases SET is_single = ? WHERE alias_name = ?";
            try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, isSingle);
                stmt.setString(2, name);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
        if (keyExists(name)) {
            String sql = "UPDATE keys SET is_single = ? WHERE name = ?";
            try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, isSingle);
                stmt.setString(2, name);
                return stmt.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
        return false;
    }

    public static boolean isNameSingle(String name) {
        String aliasSql = "SELECT is_single FROM key_aliases WHERE alias_name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(aliasSql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getBoolean("is_single");
            }
        } catch (SQLException ignored) {
        }
        String keySql = "SELECT is_single FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(keySql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getBoolean("is_single");
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    public static List<String> getSingleKeys() {
        List<String> list = new ArrayList<>();
        try (Connection conn = getConnection()) {
            // SQLite boolean maps to 1 for TRUE
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT name FROM keys WHERE is_single = 1")) {
                while (rs.next()) list.add(rs.getString("name") + " (RealKey)");
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT alias_name, target_name FROM key_aliases WHERE is_single = 1")) {
                while (rs.next())
                    list.add(rs.getString("alias_name") + " (Alias -> " + rs.getString("target_name") + ")");
            }
        } catch (SQLException ignored) {
        }
        return list;
    }

    public static Map<String, Object> getKeyPortInfo(String realName) {
        String sql = "SELECT default_port, max_conns FROM keys WHERE name = ?";
        Map<String, Object> info = new HashMap<>();
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    info.put("default_port", rs.getString("default_port"));
                    info.put("max_conns", rs.getInt("max_conns"));
                    return info;
                }
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public static List<Map<String, String>> getAllKeysRaw() {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, List<String>> mapInfo = new HashMap<>();
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
                while (rs.next()) mapInfo.computeIfAbsent(rs.getString("key_name"), k -> new ArrayList<>())
                        .add(String.format("%s -> %s", rs.getString("node_id"), rs.getString("port")));
            }
            String sql = "SELECT * FROM keys ORDER BY name ASC";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String dbStatus = rs.getString("status");
                    double balance = rs.getDouble("balance");
                    String expireTime = rs.getString("expire_time");
                    boolean isSingle = rs.getBoolean("is_single");

                    String icon;
                    if (STATUS_DISABLED.equalsIgnoreCase(dbStatus)) icon = ANSI_RED + "✘" + ANSI_RESET;
                    else if (checkConditions(balance, expireTime) != null || STATUS_PAUSED.equalsIgnoreCase(dbStatus))
                        icon = ANSI_YELLOW + "⏸" + ANSI_RESET;
                    else icon = ANSI_GREEN + "✔" + ANSI_RESET;
                    if (isSingle) icon += " " + ANSI_BLUE + "[S]" + ANSI_RESET;

                    Map<String, String> row = new HashMap<>();
                    row.put("type", "KEY");
                    row.put("name", name);
                    row.put("status_icon", icon);
                    row.put("balance", String.format("%.2f", balance));
                    row.put("rate", String.format("%.2f", rs.getDouble("rate")));
                    row.put("port", rs.getString("default_port"));
                    row.put("conns", String.valueOf(rs.getInt("max_conns")));
                    row.put("expire", expireTime == null ? "PERMANENT" : expireTime);
                    row.put("web", rs.getBoolean("enable_web") ? "Yes" : "No");
                    List<String> maps = mapInfo.get(name);
                    row.put("map_count", String.valueOf(maps != null ? maps.size() : 0));
                    result.add(row);
                    if (maps != null) for (int i = 0; i < maps.size(); i++) {
                        String prefix = (i == maps.size() - 1) ? "└─" : "├─";
                        Map<String, String> mapRow = new HashMap<>();
                        mapRow.put("type", "MAP");
                        mapRow.put("parent_key", name);
                        mapRow.put("map_str", String.format("%s [MAP] %s", prefix, maps.get(i)));
                        result.add(mapRow);
                    }
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.listFail", e);
        }
        return result;
    }

    public static void addNodePort(String realName, String nodeId, String port) {
        String delSql = "DELETE FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        String insertSql = "INSERT INTO node_ports (key_name, node_id, port) VALUES (?, ?, ?)";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delStmt = conn.prepareStatement(delSql); PreparedStatement insStmt = conn.prepareStatement(insertSql)) {
                delStmt.setString(1, realName);
                delStmt.setString(2, nodeId);
                delStmt.executeUpdate();
                insStmt.setString(1, realName);
                insStmt.setString(2, nodeId);
                insStmt.setString(3, port);
                insStmt.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.mapFail", e);
        }
    }

    public static boolean deleteNodeMap(String realName, String nodeId) {
        String sql = "DELETE FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            stmt.setString(2, nodeId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean keyExists(String name) {
        String sql = "SELECT 1 FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean addKey(String name, double balance, double rate, String expireTime, String port, int maxConns) {
        String sql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single) VALUES (?, ?, ?, ?, ?, ?, 'ENABLED', 0, 0)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setDouble(2, balance);
            stmt.setDouble(3, rate);
            stmt.setString(4, expireTime);
            stmt.setString(5, port);
            stmt.setInt(6, maxConns);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public static Map<String, Object> getKeyInfoFull(String realName, String nodeId) {
        String sqlKey = "SELECT * FROM keys WHERE name = ?";
        String sqlNode = "SELECT port FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        try (Connection conn = getConnection()) {
            Map<String, Object> result = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(sqlKey)) {
                stmt.setString(1, realName);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) return null;
                String dbStatus = rs.getString("status");
                double balance = rs.getDouble("balance");
                String expireTime = rs.getString("expire_time");
                KeyStateResult stateResult;
                if (STATUS_DISABLED.equalsIgnoreCase(dbStatus))
                    stateResult = new KeyStateResult(KeyStatus.DISABLED, "Disabled by admin");
                else {
                    String reason = checkConditions(balance, expireTime);
                    if (reason != null) {
                        stateResult = new KeyStateResult(KeyStatus.PAUSED, reason);
                        if (!STATUS_PAUSED.equalsIgnoreCase(dbStatus)) updateKeyStatusColumn(realName, STATUS_PAUSED);
                    } else {
                        stateResult = new KeyStateResult(KeyStatus.ENABLED, "OK");
                        if (STATUS_PAUSED.equalsIgnoreCase(dbStatus)) updateKeyStatusColumn(realName, STATUS_ENABLED);
                    }
                }
                result.put("STATE_RESULT", stateResult);
                result.put("name", rs.getString("name"));
                result.put("balance", balance);
                result.put("rate", rs.getDouble("rate"));
                result.put("expireTime", expireTime);
                result.put("enableWebHTML", rs.getBoolean("enable_web"));
                result.put("default_port", rs.getString("default_port"));
                result.put("max_conns", rs.getInt("max_conns"));
            }
            if (nodeId != null && !nodeId.isBlank()) {
                try (PreparedStatement stmt = conn.prepareStatement(sqlNode)) {
                    stmt.setString(1, realName);
                    stmt.setString(2, nodeId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) result.put("default_port", rs.getString("port"));
                }
            }
            return result;
        } catch (SQLException e) {
            return null;
        }
    }

    public static Map<String, Protocol.KeyMetadata> getBatchKeyMetadata(List<String> realKeys) {
        Map<String, Protocol.KeyMetadata> result = new HashMap<>();
        if (realKeys == null || realKeys.isEmpty()) return result;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < realKeys.size(); i++) sb.append(i == 0 ? "?" : ",?");
        String sql = "SELECT name,balance,expire_time,status,rate,enable_web FROM keys WHERE name IN (" + sb + ")";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < realKeys.size(); i++) stmt.setString(i + 1, realKeys.get(i));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Protocol.KeyMetadata m = new Protocol.KeyMetadata();
                    m.balance = rs.getDouble("balance");
                    m.rate = rs.getDouble("rate");
                    m.expireTime = rs.getString("expire_time");
                    m.enableWebHTML = rs.getBoolean("enable_web");
                    String s = rs.getString("status");
                    String r = checkConditions(m.balance, m.expireTime);
                    if (STATUS_DISABLED.equalsIgnoreCase(s)) {
                        m.isValid = false;
                        m.reason = "Disabled";
                    } else if (r != null) {
                        m.isValid = false;
                        m.reason = r;
                    } else {
                        m.isValid = true;
                        m.reason = "OK";
                    }
                    result.put(rs.getString("name"), m);
                }
            }
        } catch (SQLException e) {
        }
        return result;
    }

    public static List<AdminDTOs.KeyDetail> getAllKeysStructured(boolean includeMaps, String targetKeyName) {
        List<AdminDTOs.KeyDetail> result = new ArrayList<>();
        Map<String, List<AdminDTOs.MapNode>> mapInfo = new HashMap<>();
        try (Connection conn = getConnection()) {
            if (includeMaps) {
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
                    while (rs.next()) mapInfo.computeIfAbsent(rs.getString("key_name"), k -> new ArrayList<>())
                            .add(new AdminDTOs.MapNode(rs.getString("node_id"), rs.getString("port")));
                }
            }
            String sql = "SELECT * FROM keys";
            if (targetKeyName != null) sql += " WHERE name = ?";
            else sql += " ORDER BY name ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (targetKeyName != null) stmt.setString(1, targetKeyName);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        AdminDTOs.KeyDetail k = new AdminDTOs.KeyDetail();
                        k.name = rs.getString("name");
                        k.balance = rs.getDouble("balance");
                        k.rate = rs.getDouble("rate");
                        k.port = rs.getString("default_port");
                        k.maxConns = rs.getInt("max_conns");
                        k.expireTime = rs.getString("expire_time");
                        k.enableWeb = rs.getBoolean("enable_web");
                        k.status = rs.getString("status");
                        if (includeMaps) k.maps = mapInfo.getOrDefault(k.name, new ArrayList<>());
                        result.add(k);
                    }
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.queryFail", e);
        }
        return result;
    }

    private static Map<String, Object> getKeyRaw(String name) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement("SELECT balance, expire_time, status FROM keys WHERE name=?")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("balance", rs.getDouble("balance"));
                    m.put("expire_time", rs.getString("expire_time"));
                    m.put("status", rs.getString("status"));
                    return m;
                }
            }
        } catch (SQLException e) {
        }
        return null;
    }

    public static int getKeyMaxConns(String realName) {
        String sql = "SELECT max_conns FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("max_conns");
            }
        } catch (SQLException ignored) {
        }
        return 1; // 默认返回 1
    }

    private record CachedState(KeyStateResult result, long expireTime) {
    }
}