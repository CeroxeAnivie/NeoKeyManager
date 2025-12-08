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
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.setKeyStatus", e);
            return false;
        }
    }

    public static boolean setWebStatus(String name, boolean enable) {
        String sql = "UPDATE keys SET enable_web = ? WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enable);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.setWebStatus", e);
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

    public static boolean addKey(String name, double balance, double rate, String expireTime, String port, int maxConns) {
        String sql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, is_enable, enable_web) VALUES (?, ?, ?, ?, ?, ?, TRUE, FALSE)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setDouble(2, balance);
            stmt.setDouble(3, rate);
            stmt.setString(4, expireTime);
            stmt.setString(5, port);
            stmt.setInt(6, maxConns);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.addFail", e, name);
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

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
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
            try (PreparedStatement delStmt = conn.prepareStatement(delSql);
                 PreparedStatement insStmt = conn.prepareStatement(insertSql)) {
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
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, nodeId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.delMapFail", e);
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
                ServerLogger.infoWithSource("Database", "nkm.db.clearedMaps", count, name);
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.clearMapFail", e);
        }
    }

    public static void deleteKey(String name) {
        String sql = "DELETE FROM keys WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.delKeyFail", e, name);
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
            ServerLogger.error("Database", "nkm.db.deductFail", e, name);
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
                String mapIndent = " ".repeat(46);
                while (rs.next()) {
                    String name = rs.getString("name");
                    boolean dbEnable = rs.getBoolean("is_enable");
                    boolean webEnable = rs.getBoolean("enable_web");
                    String expireTime = rs.getString("expire_time");
                    double balance = rs.getDouble("balance");

                    boolean isReallyEnabled = dbEnable;
                    if (isReallyEnabled) {
                        if (balance <= 0) {
                            isReallyEnabled = false;
                        } else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                            try {
                                LocalDateTime expDate = LocalDateTime.parse(expireTime, TIME_FORMATTER);
                                if (LocalDateTime.now().isAfter(expDate)) isReallyEnabled = false;
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    String statusIcon = isReallyEnabled ? (ANSI_GREEN + "✔" + ANSI_RESET) : (ANSI_RED + "✘" + ANSI_RESET);
                    String webIcon = webEnable ? "Yes" : "No";

                    list.add(String.format("   %-16s %-12s %-12.2f %-8.2f %-16s %-6d %-18s %-4s",
                            statusIcon, name, balance, rs.getDouble("rate"), rs.getString("default_port"),
                            rs.getInt("max_conns"), expireTime, webIcon));

                    List<String> maps = mapInfo.get(name);
                    if (maps != null) {
                        for (int i = 0; i < maps.size(); i++) {
                            String m = maps.get(i);
                            String prefix = (i == maps.size() - 1) ? "└─" : "├─";
                            list.add(mapIndent + String.format("%s [MAP] %s", prefix, m));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.listFail", e);
        }
        return list;
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
                        ServerLogger.infoWithSource("Database", "nkm.db.keyRejected", name);
                    } else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                        try {
                            LocalDateTime expDate = LocalDateTime.parse(expireTime, TIME_FORMATTER);
                            if (LocalDateTime.now().isAfter(expDate)) {
                                isEnable = false;
                                ServerLogger.infoWithSource("Database", "nkm.db.keyExpired", name, expireTime);
                            }
                        } catch (Exception e) {
                            System.err.println("Time parse error: " + e.getMessage());
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
                        String mappedPort = rs.getString("port");
                        String finalPort = PortUtils.truncateRange(mappedPort, maxConns);
                        result.put("port", finalPort);
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            ServerLogger.error("Database", "nkm.db.getInfoFail", e);
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
            String checkSql = "SELECT name, balance, expire_time FROM keys WHERE name IN (" + inClause + ") AND is_enable = TRUE FOR UPDATE";
            String disableSql = "UPDATE keys SET is_enable = FALSE WHERE name = ?";

            try (PreparedStatement selStmt = conn.prepareStatement(checkSql);
                 PreparedStatement upStmt = conn.prepareStatement(disableSql)) {
                for (int i = 0; i < keysToCheck.size(); i++) selStmt.setString(i + 1, keysToCheck.get(i));
                ResultSet rs = selStmt.executeQuery();
                while (rs.next()) {
                    String name = rs.getString("name");
                    double balance = rs.getDouble("balance");
                    String expireTime = rs.getString("expire_time");
                    boolean kill = false;
                    String reason = "";

                    if (balance <= 0) {
                        kill = true;
                        reason = "Balance depleted";
                    } else if (expireTime != null && !expireTime.isBlank() && !expireTime.equalsIgnoreCase("PERMANENT")) {
                        try {
                            LocalDateTime expDate = LocalDateTime.parse(expireTime, TIME_FORMATTER);
                            if (LocalDateTime.now().isAfter(expDate)) {
                                kill = true;
                                reason = "Expired";
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    if (kill) {
                        upStmt.setString(1, name);
                        upStmt.executeUpdate();
                        invalidKeys.add(name);
                        ServerLogger.infoWithSource("Database", "nkm.db.keyDisabledAuto", name, reason);
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
            ServerLogger.error("Database", "nkm.db.checkFail", e);
        }
        return invalidKeys;
    }
}