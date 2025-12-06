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

    // 【核心修复】将 MM 改为 M，兼容输入 "2026/1/1" 和 "2026/12/05"
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");

    private static String getDbUrl() {
        return "jdbc:h2:" + Config.DB_PATH + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    public static void init() {
        try {
            if (Main.myConsole != null) Main.myConsole.log("Database", "Loading H2 Driver...");
            Class.forName(DB_DRIVER);

            try (Connection conn = getConnection()) {
                Statement stmt = conn.createStatement();

                // 1. Key 表
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

                // 2. 向上兼容字段
                try { stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS max_conns INT DEFAULT 1"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS is_enable BOOLEAN DEFAULT TRUE"); } catch (SQLException ignored) {}
                try { stmt.execute("ALTER TABLE keys ADD COLUMN IF NOT EXISTS enable_web BOOLEAN DEFAULT FALSE"); } catch (SQLException ignored) {}

                // 3. Node Map 表
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
                if (Main.myConsole != null) Main.myConsole.log("Database", "Schema initialized and checked.");
            }
        } catch (Exception e) {
            System.err.println("[CRITICAL] Database initialization failed!");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getDbUrl(), DB_USER, DB_PASSWORD);
    }

    // ---------------- Status Control ----------------

    public static boolean setKeyStatus(String name, boolean enable) {
        String sql = "UPDATE keys SET is_enable = ? WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enable);
            stmt.setString(2, name);
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to set key status", e);
            return false;
        }
    }

    public static boolean setWebStatus(String name, boolean enable) {
        String sql = "UPDATE keys SET enable_web = ? WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enable);
            stmt.setString(2, name);
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to set web status", e);
            return false;
        }
    }

    // ---------------- CRUD ----------------

    public static boolean keyExists(String name) {
        String sql = "SELECT 1 FROM keys WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static void addKey(String name, double balance, double rate, String expireTime, String port, int maxConns) {
        String sql = "MERGE INTO keys KEY(name) VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setDouble(2, balance);
            stmt.setDouble(3, rate);
            stmt.setString(4, expireTime);
            stmt.setString(5, port);
            stmt.setInt(6, maxConns);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to add/update key " + name, e);
        }
    }

    public static void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb) {
        StringBuilder sql = new StringBuilder("UPDATE keys SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;

        if (balance != null) { if (!first) sql.append(", "); sql.append("balance = ?"); params.add(balance); first = false; }
        if (rate != null) { if (!first) sql.append(", "); sql.append("rate = ?"); params.add(rate); first = false; }
        if (expireTime != null) { if (!first) sql.append(", "); sql.append("expire_time = ?"); params.add(expireTime); first = false; }
        if (enableWeb != null) { if (!first) sql.append(", "); sql.append("enable_web = ?"); params.add(enableWeb); first = false; }

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

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to update key " + name, e);
        }
    }

    public static void addNodePort(String name, String nodeId, String port) {
        String sql = "MERGE INTO node_ports KEY(key_name, node_id) VALUES (DEFAULT, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, nodeId);
            stmt.setString(3, port);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to map port", e);
        }
    }

    public static boolean deleteNodeMap(String name, String nodeId) {
        String sql = "DELETE FROM node_ports WHERE key_name = ? AND node_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, nodeId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to delete node map", e);
            return false;
        }
    }

    public static void deleteNodeMapsByKey(String name) {
        String sql = "DELETE FROM node_ports WHERE key_name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            int count = stmt.executeUpdate();
            if (count > 0) {
                Main.myConsole.log("Database", "Cleared " + count + " incompatible mappings for " + name);
            }
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to clear mappings", e);
        }
    }

    public static void deleteKey(String name) {
        String sql = "DELETE FROM keys WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to delete key " + name, e);
        }
    }

    public static void deductBalance(String name, double amount) {
        String sql = "UPDATE keys SET balance = balance - ? WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, amount);
            stmt.setString(2, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to deduct balance for " + name, e);
        }
    }

    // ---------------- Query ----------------

    public static Map<String, Object> getKeyPortInfo(String name) {
        String sql = "SELECT default_port, max_conns FROM keys WHERE name = ?";
        Map<String, Object> info = new HashMap<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    info.put("default_port", rs.getString("default_port"));
                    info.put("max_conns", rs.getInt("max_conns"));
                    return info;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static List<String> getAllKeysFormatted() {
        List<String> list = new ArrayList<>();
        Map<String, List<String>> mapInfo = new HashMap<>();

        try (Connection conn = getConnection()) {
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

            String sql = "SELECT * FROM keys ORDER BY name ASC";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    boolean isEnable = rs.getBoolean("is_enable");
                    boolean webEnable = rs.getBoolean("enable_web");
                    String expireTime = rs.getString("expire_time");

                    boolean isExpired = false;
                    try {
                        if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                            LocalDateTime expDate = LocalDateTime.parse(expireTime, TIME_FORMATTER);
                            if (LocalDateTime.now().isAfter(expDate)) {
                                isExpired = true;
                            }
                        }
                    } catch (Exception ignored) {}

                    String statusFlag = isEnable ? (isExpired ? "[EXP]" : "[OK]") : "[DIS]";
                    String webFlag = webEnable ? "✓" : "✗";

                    list.add(String.format("%-6s %-12s %-12.2f %-8.2f %-16s %-6d %-18s %-4s",
                            statusFlag,
                            name,
                            rs.getDouble("balance"),
                            rs.getDouble("rate"),
                            rs.getString("default_port"),
                            rs.getInt("max_conns"),
                            expireTime,
                            webFlag
                    ));

                    List<String> maps = mapInfo.get(name);
                    if (maps != null) {
                        for (int i = 0; i < maps.size(); i++) {
                            String m = maps.get(i);
                            String prefix = (i == maps.size() - 1) ? "└─" : "├─";
                            list.add(String.format("       %s [MAP] %s", prefix, m));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Failed to list keys", e);
        }
        return list;
    }

    public static Map<String, Object> getKeyInfo(String name, String nodeId) {
        String sqlKey = "SELECT * FROM keys WHERE name = ?";
        String sqlNode = "SELECT port FROM node_ports WHERE key_name = ? AND node_id = ?";
        Map<String, Object> result = new HashMap<>();

        try (Connection conn = getConnection()) {
            int maxConns = 1;

            try (PreparedStatement stmt = conn.prepareStatement(sqlKey)) {
                stmt.setString(1, name);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) return null;

                boolean isEnable = rs.getBoolean("is_enable");
                String expireTime = rs.getString("expire_time");

                if (isEnable && expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                    try {
                        LocalDateTime expDate = LocalDateTime.parse(expireTime, TIME_FORMATTER);
                        if (LocalDateTime.now().isAfter(expDate)) {
                            isEnable = false;
                            Main.myConsole.log("Database", "Key " + name + " expired dynamically (" + expireTime + ")");
                        }
                    } catch (Exception e) {
                        Main.myConsole.warn("Database", "Parse time error for " + name + ": " + e.getMessage());
                    }
                }

                if (!isEnable) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("ERROR_CODE", 403);
                    error.put("MSG", "Key is disabled or expired");
                    return error;
                }

                result.put("name", rs.getString("name"));
                result.put("balance", rs.getDouble("balance"));
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
                        String mappedPort = rs.getString("port");
                        String finalPort = PortUtils.truncateRange(mappedPort, maxConns);
                        result.put("port", finalPort);
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Error getting key info", e);
            return null;
        }
    }

    public static List<String> checkInvalidKeys(List<String> keysToCheck) {
        List<String> invalidKeys = new ArrayList<>();
        if (keysToCheck == null || keysToCheck.isEmpty()) return invalidKeys;

        String selectSql = "SELECT name, balance, expire_time FROM keys WHERE name = ?";
        String disableSql = "UPDATE keys SET is_enable = FALSE WHERE name = ?";

        try (Connection conn = getConnection()) {
            for (String key : keysToCheck) {
                boolean shouldDisable = false;

                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setString(1, key);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        double balance = rs.getDouble("balance");
                        String expireTimeStr = rs.getString("expire_time");

                        if (balance <= 0) {
                            shouldDisable = true;
                            Main.myConsole.log("Database", "Key " + key + " disabled (Balance depleted)");
                        }

                        if (!shouldDisable && expireTimeStr != null && !expireTimeStr.isBlank() && !expireTimeStr.equalsIgnoreCase("PERMANENT")) {
                            try {
                                LocalDateTime expDate = LocalDateTime.parse(expireTimeStr, TIME_FORMATTER);
                                if (LocalDateTime.now().isAfter(expDate)) {
                                    shouldDisable = true;
                                    Main.myConsole.log("Database", "Key " + key + " disabled (Expired: " + expireTimeStr + ")");
                                }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        invalidKeys.add(key);
                        continue;
                    }
                }

                if (shouldDisable) {
                    try (PreparedStatement updateStmt = conn.prepareStatement(disableSql)) {
                        updateStmt.setString(1, key);
                        updateStmt.executeUpdate();
                    }
                    invalidKeys.add(key);
                }
            }
        } catch (SQLException e) {
            Main.myConsole.error("Database", "Error checking invalid keys", e);
        }

        return invalidKeys;
    }
}