package neoproxy.neokeymanager;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Database {
    // ... [保留原有 import, 常量, init, getConnection 等代码不变] ...
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
        // ... [保留原有 init 内容不变] ...
        try {
            ServerLogger.infoWithSource("Database", "nkm.db.loadingDriver");
            Class.forName(DB_DRIVER);
            try (Connection conn = getConnection()) {
                Statement stmt = conn.createStatement();
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

    // ... [保留 setKeyStatus, setWebStatus, keyExists, addKey, updateKey, addNodePort 等 CRUD 方法不变] ...
    // ... [保留 deleteNodeMap, deleteNodeMapsByKey, deleteKey, deductBalance 不变] ...

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

    public static void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb) {
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
        if (port != null) {
            if (!first) sql.append(", ");
            sql.append("default_port = ?, max_conns = ?");
            params.add(port);
            params.add(PortUtils.calculateSize(port));
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

    public static void deductBalance(String name, double amount) {
        String sql = "UPDATE keys SET balance = balance - ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, amount);
            stmt.setString(2, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
        }
    }

    // ... [保留 getKeyPortInfo 不变] ...
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

    /**
     * 【核心修改】获取原始数据供 Main 处理
     * 1. 增加了 "map_count" 字段，用于 `list nomap`
     * 2. 增加了 "parent_key" 字段，用于 `lp` 过滤
     */
    public static List<Map<String, String>> getAllKeysRaw() {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, List<String>> mapInfo = new HashMap<>();

        try (Connection conn = getConnection()) {
            // 1. 获取所有 Map 信息
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT key_name, node_id, port FROM node_ports")) {
                while (rs.next()) {
                    String k = rs.getString("key_name");
                    String n = rs.getString("node_id");
                    String p = rs.getString("port");
                    mapInfo.computeIfAbsent(k, key -> new ArrayList<>())
                            .add(String.format("%s -> %s", n, p));
                }
            }

            // 2. 获取 Key 信息
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
                        if (balance <= 0) {
                            isReallyEnabled = false;
                        } else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
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

                    // [Add] 计算 Map 数量
                    List<String> maps = mapInfo.get(name);
                    int mapCount = (maps != null) ? maps.size() : 0;
                    row.put("map_count", String.valueOf(mapCount));

                    result.add(row);

                    // 添加 Map 行
                    if (maps != null) {
                        for (int i = 0; i < maps.size(); i++) {
                            String m = maps.get(i);
                            String prefix = (i == maps.size() - 1) ? "└─" : "├─";
                            Map<String, String> mapRow = new HashMap<>();
                            mapRow.put("type", "MAP");
                            // [Add] 记录父 Key 以便过滤
                            mapRow.put("parent_key", name);
                            mapRow.put("map_str", String.format("%s [MAP] %s", prefix, m));
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

    // ... [保留 getKeyInfoSimple, getKeyInfo, checkInvalidKeys, getBatchKeyStatus 不变] ...
    public static Map<String, Object> getKeyInfoSimple(String name) {
        String sql = "SELECT name, max_conns, is_enable, expire_time, balance FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                boolean isEnable = rs.getBoolean("is_enable");
                double balance = rs.getDouble("balance");
                String expireTime = rs.getString("expire_time");
                int maxConns = rs.getInt("max_conns");
                String msg = "OK";
                if (!isEnable) msg = "Disabled by admin";
                else if (balance <= 0) {
                    isEnable = false;
                    msg = "Balance depleted";
                } else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                    try {
                        if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER))) {
                            isEnable = false;
                            msg = "Expired";
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!isEnable) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("ERROR_CODE", 403);
                    err.put("MSG", msg);
                    return err;
                }
                Map<String, Object> res = new HashMap<>();
                res.put("name", rs.getString("name"));
                res.put("max_conns", maxConns);
                res.put("is_enable", true);
                return res;
            }
        } catch (SQLException e) {
        }
        return null;
    }

    public static Map<String, Object> getKeyInfo(String name, String nodeId) {
        String sqlKey = "SELECT * FROM keys WHERE name = ?";
        String sqlNode = "SELECT port FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        Map<String, Object> result = new HashMap<>();
        try (Connection conn = getConnection()) {
            int maxConns = 1;
            try (PreparedStatement stmt = conn.prepareStatement(sqlKey)) {
                stmt.setString(1, name);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) return null;
                boolean isEnable = rs.getBoolean("is_enable");
                String expireTime = rs.getString("expire_time");
                double balance = rs.getDouble("balance");
                if (isEnable) {
                    if (balance <= 0) {
                        isEnable = false;
                    } else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                        try {
                            if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER)))
                                isEnable = false;
                        } catch (Exception e) {
                        }
                    }
                }
                if (!isEnable) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("ERROR_CODE", 403);
                    error.put("MSG", "Key is disabled or expired");
                    return error;
                }
                result.put("name", rs.getString("name"));
                result.put("balance", balance);
                result.put("rate", rs.getDouble("rate"));
                result.put("expireTime", expireTime);
                result.put("isEnable", true);
                result.put("enableWebHTML", rs.getBoolean("enable_web"));
                result.put("port", rs.getString("default_port"));
                maxConns = rs.getInt("max_conns");
            }
            if (!SessionManager.getInstance().tryAcquireOrRefresh(name, nodeId, maxConns)) {
                Map<String, Object> error = new HashMap<>();
                error.put("ERROR_CODE", 409);
                error.put("MSG", "Max connections reached (" + maxConns + ")");
                return error;
            }
            if (nodeId != null && !nodeId.isBlank()) {
                try (PreparedStatement stmt = conn.prepareStatement(sqlNode)) {
                    stmt.setString(1, name);
                    stmt.setString(2, nodeId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        String finalPort = PortUtils.truncateRange(rs.getString("port"), maxConns);
                        result.put("port", finalPort);
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            return null;
        }
    }

    public static List<String> checkInvalidKeys(List<String> keysToCheck) {
        List<String> invalidKeys = new ArrayList<>();
        if (keysToCheck == null || keysToCheck.isEmpty()) return invalidKeys;
        try (Connection conn = getConnection()) {
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < keysToCheck.size(); i++) {
                inClause.append("?");
                if (i < keysToCheck.size() - 1) inClause.append(",");
            }
            conn.setAutoCommit(false);
            try (PreparedStatement selStmt = conn.prepareStatement("SELECT name, balance, expire_time FROM keys WHERE name IN (" + inClause + ") AND is_enable = TRUE FOR UPDATE");
                 PreparedStatement upStmt = conn.prepareStatement("UPDATE keys SET is_enable = FALSE WHERE name = ?")) {
                for (int i = 0; i < keysToCheck.size(); i++) selStmt.setString(i + 1, keysToCheck.get(i));
                ResultSet rs = selStmt.executeQuery();
                while (rs.next()) {
                    String name = rs.getString("name");
                    double balance = rs.getDouble("balance");
                    String expireTime = rs.getString("expire_time");
                    boolean kill = false;
                    if (balance <= 0) kill = true;
                    else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                        try {
                            if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER)))
                                kill = true;
                        } catch (Exception ignored) {
                        }
                    }
                    if (kill) {
                        upStmt.setString(1, name);
                        upStmt.executeUpdate();
                        invalidKeys.add(name);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
        }
        return invalidKeys;
    }

    public static Map<String, Protocol.KeyStatusDetail> getBatchKeyStatus(List<String> keys) {
        Map<String, Protocol.KeyStatusDetail> result = new HashMap<>();
        if (keys == null || keys.isEmpty()) return result;
        for (String k : keys) {
            Protocol.KeyStatusDetail d = new Protocol.KeyStatusDetail();
            d.isValid = false;
            d.reason = "NotFound";
            result.put(k, d);
        }
        try (Connection conn = getConnection()) {
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < keys.size(); i++) {
                inClause.append("?");
                if (i < keys.size() - 1) inClause.append(",");
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT name, balance, expire_time, is_enable FROM keys WHERE name IN (" + inClause + ")")) {
                for (int i = 0; i < keys.size(); i++) stmt.setString(i + 1, keys.get(i));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        double balance = rs.getDouble("balance");
                        String expireTime = rs.getString("expire_time");
                        boolean isEnable = rs.getBoolean("is_enable");
                        Protocol.KeyStatusDetail detail = new Protocol.KeyStatusDetail();
                        detail.balance = balance;
                        detail.expireTime = expireTime;
                        if (!isEnable) {
                            detail.isValid = false;
                            detail.reason = "Disabled";
                        } else if (balance <= 0) {
                            detail.isValid = false;
                            detail.reason = "NoBalance";
                        } else {
                            boolean expired = false;
                            if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                                try {
                                    if (LocalDateTime.now().isAfter(LocalDateTime.parse(expireTime, TIME_FORMATTER)))
                                        expired = true;
                                } catch (Exception ignored) {
                                }
                            }
                            if (expired) {
                                detail.isValid = false;
                                detail.reason = "Expired";
                            } else {
                                detail.isValid = true;
                                detail.reason = "OK";
                            }
                        }
                        result.put(name, detail);
                    }
                }
            }
        } catch (SQLException e) {
        }
        return result;
    }
}