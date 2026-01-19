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
    private static final String DB_DRIVER = "org.h2.Driver";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");

    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_PAUSED = "PAUSED";

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";

    // [读缓存] TTL 3000ms，拦截绝大多数心跳读请求
    private static final ConcurrentHashMap<String, CachedState> stateCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3000L;

    // [写缓冲] 流量扣费缓冲区，防止高IO
    private static final ConcurrentHashMap<String, Double> trafficBuffer = new ConcurrentHashMap<>();

    // [连接保持] 持有一个连接以保持 H2 引擎常驻，避免反复冷启动
    private static Connection keepAliveConn;

    private static String getDbUrl() {
        return "jdbc:h2:" + Config.DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;DEFAULT_LOCK_TIMEOUT=10000";
    }

    public static void init() {
        try {
            ServerLogger.infoWithSource("Database", "nkm.db.loadingDriver");
            Class.forName(DB_DRIVER);

            // [CRITICAL FIX] 建立一个常驻连接，防止 H2 引擎反复关闭/重启造成的极高 CPU/IO
            keepAliveConn = DriverManager.getConnection(getDbUrl(), DB_USER, DB_PASSWORD);

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
                                enable_web BOOLEAN DEFAULT FALSE,
                                is_single BOOLEAN DEFAULT FALSE
                            )
                        """);
                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS node_ports (
                                id INT AUTO_INCREMENT PRIMARY KEY,
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
                                is_single BOOLEAN DEFAULT FALSE,
                                FOREIGN KEY (target_name) REFERENCES keys(name) ON DELETE CASCADE
                            )
                        """);

                migrateLegacySchema(conn);

                // Add columns safely
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS max_conns INT DEFAULT 1");
                } catch (SQLException ignored) {
                }
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS enable_web BOOLEAN DEFAULT FALSE");
                } catch (SQLException ignored) {
                }
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS is_single BOOLEAN DEFAULT FALSE");
                } catch (SQLException ignored) {
                }
                try {
                    stmt.execute("ALTER TABLE key_aliases ADD COLUMN IF NOT EXISTS is_single BOOLEAN DEFAULT FALSE");
                } catch (SQLException ignored) {
                }

                ServerLogger.infoWithSource("Database", "nkm.db.schemaInit");
            }

            // [IO FIX] 启动定时任务，每5秒将缓冲区流量写入数据库
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NKM-DB-Flusher");
                t.setDaemon(true);
                return t;
            }).scheduleAtFixedRate(Database::flushTrafficBuffer, 5, 5, TimeUnit.SECONDS);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void migrateLegacySchema(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = conn.getMetaData().getColumns(null, null, "KEYS", "IS_ENABLE");
            if (rs.next()) {
                ServerLogger.warnWithSource("Database", "nkm.db.migrating", "Upgrading: is_enable -> status");
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'ENABLED'");
                } catch (SQLException ignored) {
                }
                stmt.executeUpdate("UPDATE keys SET status = '" + STATUS_DISABLED + "' WHERE is_enable = FALSE");
                stmt.executeUpdate("UPDATE keys SET status = '" + STATUS_ENABLED + "' WHERE is_enable = TRUE");
                stmt.execute("ALTER TABLE keys DROP COLUMN is_enable");
                ServerLogger.infoWithSource("Database", "nkm.db.migrated");
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.migrationFail", e);
        }
    }

    private static Connection getConnection() throws SQLException {
        // 由于 keepAliveConn 的存在，这里创建连接非常快
        return DriverManager.getConnection(getDbUrl(), DB_USER, DB_PASSWORD);
    }

    /**
     * [IO FIX] 批量扣费入口：不再直接写库，而是写入内存 Buffer
     */
    public static void deductBalanceBatch(Map<String, Double> trafficMap) {
        if (trafficMap == null || trafficMap.isEmpty()) return;
        trafficMap.forEach((key, val) ->
                trafficBuffer.merge(key, val, Double::sum)
        );
    }

    // ==================== Traffic Buffer Logic ====================

    /**
     * [IO FIX] 真实的写库操作，由后台线程周期性调用
     */
    private static void flushTrafficBuffer() {
        if (trafficBuffer.isEmpty()) return;

        Map<String, Double> snapshot = new HashMap<>();
        // 移动数据到快照，清空缓冲区，减少锁占用
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

            // 余额变动后，强制废弃缓存
            for (String key : snapshot.keySet()) {
                stateCache.remove(key);
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.deductFail", e);
            // 如果写库失败，尝试将流量加回缓冲区，避免丢失（简单重试机制）
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

    // ==================== Existing Methods (Optimized) ====================

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
        String copyKeySql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single) SELECT ?, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single FROM keys WHERE name = ?";
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

    // ... Other CRUD methods (renameKey, deleteKey, etc.) need minimal changes, just cache clearing ...

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

    // Queries below this line are mostly reads, fine to keep as is (they use getConnection which is now fast)

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
        String sql = "INSERT INTO key_aliases (alias_name, target_name, is_single) VALUES (?, ?, FALSE)";
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
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT name FROM keys WHERE is_single = TRUE")) {
                while (rs.next()) list.add(rs.getString("name") + " (RealKey)");
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT alias_name, target_name FROM key_aliases WHERE is_single = TRUE")) {
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
        String sql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single) VALUES (?, ?, ?, ?, ?, ?, 'ENABLED', FALSE, FALSE)";
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

    public static int getKeyMaxConns(String realName) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement("SELECT max_conns FROM keys WHERE name = ?")) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("max_conns");
            }
        } catch (SQLException ignored) {
        }
        return -1;
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

    private record CachedState(KeyStateResult result, long expireTime) {
    }
}