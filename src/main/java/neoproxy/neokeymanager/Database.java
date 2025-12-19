package neoproxy.neokeymanager;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Database {
    private static final String DB_DRIVER = "org.h2.Driver";
    private static final String DB_USER = "sa";
    private static final String DB_PASSWORD = "";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";

    private static String getDbUrl() {
        return "jdbc:h2:" + Config.DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;DEFAULT_LOCK_TIMEOUT=10000";
    }

    public static void init() {
        try {
            ServerLogger.infoWithSource("Database", "nkm.db.loadingDriver");
            Class.forName(DB_DRIVER);
            try (Connection conn = getConnection()) {
                Statement stmt = conn.createStatement();
                // 确保 max_conns 列存在
                stmt.execute("""
                            CREATE TABLE IF NOT EXISTS keys (
                                name VARCHAR(50) PRIMARY KEY,
                                balance DOUBLE NOT NULL,
                                rate DOUBLE NOT NULL,
                                expire_time VARCHAR(50),
                                default_port VARCHAR(50) NOT NULL,
                                max_conns INT NOT NULL DEFAULT 1,
                                is_enable BOOLEAN DEFAULT TRUE,
                                enable_web BOOLEAN DEFAULT FALSE
                            )
                        """);
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS max_conns INT DEFAULT 1");
                } catch (SQLException ignored) {
                }
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS is_enable BOOLEAN DEFAULT TRUE");
                } catch (SQLException ignored) {
                }
                try {
                    stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS enable_web BOOLEAN DEFAULT FALSE");
                } catch (SQLException ignored) {
                }

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
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_keys_name ON keys(name)");
                ServerLogger.infoWithSource("Database", "nkm.db.schemaInit");
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getDbUrl(), DB_USER, DB_PASSWORD);
    }

    public static boolean setKeyStatus(String name, boolean enable) {
        String sql = "UPDATE keys SET is_enable = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enable);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    // [新增] 独立设置连接数
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
        String sql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, is_enable, enable_web) VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE)";
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

    // [修改] 支持 maxConns 更新
    public static void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb, Integer maxConns) {
        StringBuilder sql = new StringBuilder("UPDATE keys SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        if (balance != null) {
            if (!first) sql.append(", ");
            sql.append("balance = ?");
            params.add(balance);
            first = false;
        }
        if (rate != null) {
            if (!first) sql.append(", ");
            sql.append("rate = ?");
            params.add(rate);
            first = false;
        }
        if (expireTime != null) {
            if (!first) sql.append(", ");
            sql.append("expire_time = ?");
            params.add(expireTime);
            first = false;
        }
        if (enableWeb != null) {
            if (!first) sql.append(", ");
            sql.append("enable_web = ?");
            params.add(enableWeb);
            first = false;
        }
        if (maxConns != null) {
            if (!first) sql.append(", ");
            sql.append("max_conns = ?");
            params.add(maxConns);
            first = false;
        }
        if (port != null) {
            if (!first) sql.append(", ");
            sql.append("default_port = ?");
            params.add(port);
            // 注意：现在 updateKey 不再自动重置 max_conns，除非显式指定了 c=
            // 如果用户只改端口，连接数配额保持不变，这是解耦后的正确逻辑。
            first = false;
        }
        sql.append(" WHERE name = ?");
        params.add(name);
        if (first) return;
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) stmt.setObject(i + 1, params.get(i));
            stmt.executeUpdate();
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.updateFail", e, name);
        }
    }

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

    public static void deleteNodeMapsByKey(String name) {
        String sql = "DELETE FROM node_ports WHERE key_name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
        }
    }

    public static void deleteKey(String name) {
        String sql = "DELETE FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
        }
    }

    public static void deductBalanceBatch(Map<String, Double> trafficMap) {
        if (trafficMap == null || trafficMap.isEmpty()) return;
        String sql = "UPDATE keys SET balance = balance - ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (Map.Entry<String, Double> entry : trafficMap.entrySet()) {
                stmt.setDouble(1, entry.getValue());
                stmt.setString(2, entry.getKey());
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.deductFail", e);
        }
    }

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
        } catch (SQLException e) {
        }
        return null;
    }

    public static List<Map<String, String>> getAllKeysRaw() {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, List<String>> mapInfo = new HashMap<>();
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
                while (rs.next()) {
                    String k = rs.getString("key_name");
                    String n = rs.getString("node_id");
                    String p = rs.getString("port");
                    mapInfo.computeIfAbsent(k, key -> new ArrayList<>()).add(String.format("%s -> %s", n, p));
                }
            }
            String sql = "SELECT * FROM keys ORDER BY name ASC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    boolean dbEnable = rs.getBoolean("is_enable");
                    boolean webEnable = rs.getBoolean("enable_web");
                    String expireTime = rs.getString("expire_time");
                    double balance = rs.getDouble("balance");
                    String port = rs.getString("default_port");
                    int conns = rs.getInt("max_conns");
                    boolean isReallyEnabled = dbEnable;
                    if (isReallyEnabled) {
                        if (balance <= 0) isReallyEnabled = false;
                        else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                            try {
                                if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER)))
                                    isReallyEnabled = false;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    Map<String, String> row = new HashMap<>();
                    row.put("type", "KEY");
                    row.put("name", name);
                    row.put("status_icon", isReallyEnabled ? (ANSI_GREEN + "✔" + ANSI_RESET) : (ANSI_RED + "✘" + ANSI_RESET));
                    row.put("balance", String.format("%.2f", balance));
                    row.put("rate", String.format("%.2f", rs.getDouble("rate")));
                    row.put("port", port);
                    row.put("conns", String.valueOf(conns));
                    row.put("expire", expireTime == null ? "PERMANENT" : expireTime);
                    row.put("web", webEnable ? "Yes" : "No");
                    List<String> maps = mapInfo.get(name);
                    int mapCount = (maps != null) ? maps.size() : 0;
                    row.put("map_count", String.valueOf(mapCount));
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

    public static int getKeyMaxConns(String name) {
        String sql = "SELECT max_conns FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("max_conns");
            }
        } catch (SQLException ignored) {
        }
        return -1;
    }

    public static Map<String, Object> getKeyInfoFull(String name, String nodeId) {
        String sqlKey = "SELECT * FROM keys WHERE name = ?";
        String sqlNode = "SELECT port FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        Map<String, Object> result = new HashMap<>();
        try (Connection conn = getConnection()) {
            int maxConns = 1;
            try (PreparedStatement stmt = conn.prepareStatement(sqlKey)) {
                stmt.setString(1, name);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) return null;

                String msg = checkKeyValid(rs.getBoolean("is_enable"), rs.getDouble("balance"), rs.getString("expire_time"));
                if (!"OK".equals(msg)) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("ERROR_CODE", 403);
                    error.put("MSG", msg);
                    return error;
                }
                result.put("name", rs.getString("name"));
                result.put("balance", rs.getDouble("balance"));
                result.put("rate", rs.getDouble("rate"));
                result.put("expireTime", rs.getString("expire_time"));
                result.put("isEnable", true);
                result.put("enableWebHTML", rs.getBoolean("enable_web"));
                result.put("port", rs.getString("default_port"));
                maxConns = rs.getInt("max_conns");
                result.put("max_conns", maxConns);
            }
            if (nodeId != null && !nodeId.isBlank()) {
                try (PreparedStatement stmt = conn.prepareStatement(sqlNode)) {
                    stmt.setString(1, name);
                    stmt.setString(2, nodeId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        // [关键修正] 不再截断端口范围！
                        // 端口范围不再受 max_conns 物理限制，而是逻辑限制。
                        // 如果映射了 10000-10005，就返回 10000-10005，NPS 自行挑选。
                        // 真正的数量限制在 SessionManager 中控制。
                        result.put("port", rs.getString("port"));
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            return null;
        }
    }

    public static Map<String, Protocol.KeyMetadata> getBatchKeyMetadata(List<String> keys) {
        Map<String, Protocol.KeyMetadata> result = new HashMap<>();
        if (keys == null || keys.isEmpty()) return result;

        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            inClause.append("?");
            if (i < keys.size() - 1) inClause.append(",");
        }

        String sql = "SELECT name, balance, expire_time, is_enable, rate, enable_web FROM keys WHERE name IN (" + inClause + ")";

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < keys.size(); i++) stmt.setString(i + 1, keys.get(i));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Protocol.KeyMetadata meta = new Protocol.KeyMetadata();
                    String name = rs.getString("name");
                    double bal = rs.getDouble("balance");
                    String exp = rs.getString("expire_time");
                    boolean en = rs.getBoolean("is_enable");

                    meta.balance = bal;
                    meta.rate = rs.getDouble("rate");
                    meta.expireTime = exp;
                    meta.enableWebHTML = rs.getBoolean("enable_web");

                    String msg = checkKeyValid(en, bal, exp);
                    if ("OK".equals(msg)) {
                        meta.isValid = true;
                        meta.reason = "OK";
                    } else {
                        meta.isValid = false;
                        meta.reason = msg;
                    }
                    result.put(name, meta);
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.metaBatchFail", e);
        }
        return result;
    }

    private static String checkKeyValid(boolean isEnable, double balance, String expireTime) {
        if (!isEnable) return "Disabled by admin";
        if (balance <= 0) return "Balance depleted";
        if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
            try {
                if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER))) return "Expired";
            } catch (Exception ignored) {
            }
        }
        return "OK";
    }
}