package neoproxy.neokeymanager;

import neoproxy.neokeymanager.DTOs.KeyStateResult;
import neoproxy.neokeymanager.DTOs.KeyStatus;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库操作层 (完整版)
 * 包含了 Main.java CLI 所需的所有管理方法，以及 API 所需的状态机逻辑。
 */
public class Database {
    private static final String DB_DRIVER = "org.h2.Driver";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");

    // DB 状态常量
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_PAUSED = "PAUSED";

    // ANSI 颜色代码 (用于 CLI 输出)
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";

    private static String getDbUrl() {
        return "jdbc:h2:" + Config.DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;DEFAULT_LOCK_TIMEOUT=10000";
    }

    public static void init() {
        try {
            ServerLogger.infoWithSource("Database", "nkm.db.loadingDriver");
            Class.forName(DB_DRIVER);
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

                // 1. 建表
                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS keys (
                                name VARCHAR(50) PRIMARY KEY,
                                balance DOUBLE NOT NULL,
                                rate DOUBLE NOT NULL,
                                expire_time VARCHAR(50),
                                default_port VARCHAR(50) NOT NULL,
                                max_conns INT NOT NULL DEFAULT 1,
                                status VARCHAR(20) DEFAULT 'ENABLED', 
                                enable_web BOOLEAN DEFAULT FALSE
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

                // 2. 无损升级检测
                migrateLegacySchema(conn);

                // 3. 补全列
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS max_conns INT DEFAULT 1");
                } catch (SQLException ignored) {
                }
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS enable_web BOOLEAN DEFAULT FALSE");
                } catch (SQLException ignored) {
                }

                ServerLogger.infoWithSource("Database", "nkm.db.schemaInit");
            }
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
        return DriverManager.getConnection(getDbUrl(), DB_USER, DB_PASSWORD);
    }

    // ==================== 核心状态机逻辑 ====================

    /**
     * 获取 Key 状态（包含自动写入 PAUSED 的逻辑）
     */
    public static KeyStateResult getKeyStatus(String name) {
        String sql = "SELECT status, balance, expire_time FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;

                String dbStatus = rs.getString("status");
                double balance = rs.getDouble("balance");
                String expireTime = rs.getString("expire_time");

                if (STATUS_DISABLED.equalsIgnoreCase(dbStatus)) {
                    return new KeyStateResult(KeyStatus.DISABLED, "Disabled by admin");
                }

                String pauseReason = checkConditions(balance, expireTime);
                if (pauseReason != null) {
                    if (!STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                        updateKeyStatusColumn(name, STATUS_PAUSED);
                    }
                    return new KeyStateResult(KeyStatus.PAUSED, pauseReason);
                }

                if (STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                    updateKeyStatusColumn(name, STATUS_ENABLED);
                }

                return new KeyStateResult(KeyStatus.ENABLED, "OK");
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.queryFail", e);
            return null;
        }
    }

    /**
     * CLI Enable 命令调用的严格模式
     */
    public static boolean setKeyStatusStrict(String name, boolean enable) {
        if (!enable) {
            return updateKeyStatusColumn(name, STATUS_DISABLED);
        }
        Map<String, Object> raw = getKeyRaw(name);
        if (raw == null) return false;

        String failReason = checkConditions((Double) raw.get("balance"), (String) raw.get("expire_time"));
        if (failReason != null) {
            updateKeyStatusColumn(name, STATUS_PAUSED);
            ServerLogger.warnWithSource("KeyManager", "nkm.warn.enableRejected", name, failReason);
            return false;
        }
        return updateKeyStatusColumn(name, STATUS_ENABLED);
    }

    private static boolean updateKeyStatusColumn(String name, String status) {
        String sql = "UPDATE keys SET status = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, name);
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

    // ==================== CLI 需要的缺失方法 (Restored) ====================

    // 1. 设置最大连接数
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

    // 2. 设置 Web 面板开关
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

    // 3. 获取单个 Key 的基础信息 (用于 handleMapKey 校验)
    public static Map<String, Object> getKeyPortInfo(String name) {
        String sql = "SELECT default_port, max_conns FROM keys WHERE name = ?";
        Map<String, Object> info = new HashMap<>();
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
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

    // 4. 获取所有 Keys (用于 printKeyTable)
    public static List<Map<String, String>> getAllKeysRaw() {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, List<String>> mapInfo = new HashMap<>();

        try (Connection conn = getConnection()) {
            // 获取映射信息
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
                while (rs.next()) {
                    String k = rs.getString("key_name");
                    String n = rs.getString("node_id");
                    String p = rs.getString("port");
                    mapInfo.computeIfAbsent(k, key -> new ArrayList<>()).add(String.format("%s -> %s", n, p));
                }
            }

            // 获取 Key 信息并格式化
            String sql = "SELECT * FROM keys ORDER BY name ASC";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    String dbStatus = rs.getString("status");
                    double balance = rs.getDouble("balance");
                    String expireTime = rs.getString("expire_time");

                    // 计算显示状态图标
                    String icon;
                    if (STATUS_DISABLED.equalsIgnoreCase(dbStatus)) {
                        icon = ANSI_RED + "✘" + ANSI_RESET;
                    } else {
                        // 即使 DB 是 ENABLED，也检查一下条件以显示准确的实时状态
                        String reason = checkConditions(balance, expireTime);
                        if (reason != null || STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            icon = ANSI_YELLOW + "⏸" + ANSI_RESET; // Pause Icon
                        } else {
                            icon = ANSI_GREEN + "✔" + ANSI_RESET;
                        }
                    }

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

                    if (maps != null) {
                        for (int i = 0; i < maps.size(); i++) {
                            String prefix = (i == maps.size() - 1) ? "└─" : "├─";
                            Map<String, String> mapRow = new HashMap<>();
                            mapRow.put("type", "MAP");
                            mapRow.put("parent_key", name);
                            mapRow.put("map_str", String.format("%s [MAP] %s", prefix, maps.get(i)));
                            result.add(mapRow);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.listFail", e);
        }
        return result;
    }

    // 5. 映射相关
    public static void addNodePort(String name, String nodeId, String port) {
        String delSql = "DELETE FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        String insertSql = "INSERT INTO node_ports (key_name, node_id, port) VALUES (?, ?, ?)";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delStmt = conn.prepareStatement(delSql); PreparedStatement insStmt = conn.prepareStatement(insertSql)) {
                delStmt.setString(1, name);
                delStmt.setString(2, nodeId);
                delStmt.executeUpdate();
                insStmt.setString(1, name);
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

    public static boolean deleteNodeMap(String name, String nodeId) {
        String sql = "DELETE FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, nodeId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 原有的通用 CRUD ====================

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
        String sql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, status, enable_web) VALUES (?, ?, ?, ?, ?, ?, 'ENABLED', FALSE)";
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

    public static void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb, Integer maxConns) {
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

            // 自动恢复逻辑
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

    public static void deleteKey(String name) {
        String sql = "DELETE FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public static int getKeyMaxConns(String name) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement("SELECT max_conns FROM keys WHERE name = ?")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("max_conns");
            }
        } catch (SQLException ignored) {
        }
        return -1;
    }

    // 批量扣费
    public static void deductBalanceBatch(Map<String, Double> trafficMap) {
        if (trafficMap == null || trafficMap.isEmpty()) return;
        String sqlDeduct = "UPDATE keys SET balance = balance - ? WHERE name = ?";
        // 余额不足且当前为ENABLED时，改为PAUSED
        String sqlCheckPause = "UPDATE keys SET status = '" + STATUS_PAUSED + "' WHERE name = ? AND balance <= 0 AND status = '" + STATUS_ENABLED + "'";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sqlDeduct)) {
                for (Map.Entry<String, Double> entry : trafficMap.entrySet()) {
                    stmt.setDouble(1, entry.getValue());
                    stmt.setString(2, entry.getKey());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            try (PreparedStatement stmt = conn.prepareStatement(sqlCheckPause)) {
                for (String key : trafficMap.keySet()) {
                    stmt.setString(1, key);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.deductFail", e);
        }
    }

    // 获取完整信息 (API用)
    public static Map<String, Object> getKeyInfoFull(String name, String nodeId) {
        String sqlKey = "SELECT * FROM keys WHERE name = ?";
        String sqlNode = "SELECT port FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";

        try (Connection conn = getConnection()) {
            Map<String, Object> result = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement(sqlKey)) {
                stmt.setString(1, name);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) return null;

                String dbStatus = rs.getString("status");
                double balance = rs.getDouble("balance");
                String expireTime = rs.getString("expire_time");

                KeyStateResult stateResult;
                if (STATUS_DISABLED.equalsIgnoreCase(dbStatus)) {
                    stateResult = new KeyStateResult(KeyStatus.DISABLED, "Disabled by admin");
                } else {
                    String reason = checkConditions(balance, expireTime);
                    if (reason != null) {
                        stateResult = new KeyStateResult(KeyStatus.PAUSED, reason);
                        if (!STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            updateKeyStatusColumn(name, STATUS_PAUSED);
                        }
                    } else {
                        stateResult = new KeyStateResult(KeyStatus.ENABLED, "OK");
                        if (STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            updateKeyStatusColumn(name, STATUS_ENABLED);
                        }
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
                    stmt.setString(1, name);
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

    // Sync Metadata (API用)
    public static Map<String, Protocol.KeyMetadata> getBatchKeyMetadata(List<String> keys) {
        Map<String, Protocol.KeyMetadata> result = new HashMap<>();
        if (keys == null || keys.isEmpty()) return result;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) sb.append(i == 0 ? "?" : ",?");
        String sql = "SELECT name,balance,expire_time,status,rate,enable_web FROM keys WHERE name IN (" + sb + ")";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < keys.size(); i++) stmt.setString(i + 1, keys.get(i));
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
}