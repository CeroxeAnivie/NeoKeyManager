package neoproxy.neokeymanager.repository;

import neoproxy.neokeymanager.database.Database;
import neoproxy.neokeymanager.model.AdminDTOs;
import neoproxy.neokeymanager.model.DTOs;
import neoproxy.neokeymanager.model.DTOs.KeyStatus;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 密钥数据访问层
 * 负责 keys 表和 key_aliases 表的所有操作
 */
public class KeyRepository {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm");
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_PAUSED = "PAUSED";

    private final String dbUrl;

    public KeyRepository(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    // ==================== 密钥基础 CRUD ====================

    public boolean addKey(String name, double balance, double rate, String expireTime, String port, int maxConns) {
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

    public void deleteKey(String realName) {
        String sql = "DELETE FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            stmt.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public boolean keyExists(String name) {
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

    public boolean renameKey(String oldName, String newName) {
        String copyKeySql = "INSERT INTO keys (name, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single, custom_blocking_msg) SELECT ?, balance, rate, expire_time, default_port, max_conns, status, enable_web, is_single, custom_blocking_msg FROM keys WHERE name = ?";
        String updatePortsSql = "UPDATE node_ports SET key_name = ? WHERE key_name = ?";
        String updateAliasesSql = "UPDATE key_aliases SET target_name = ? WHERE target_name = ?";
        String delOldKeySql = "DELETE FROM keys WHERE name = ?";

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
                ServerLogger.error("KeyRepository", "nkm.db.renameFail", e);
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 密钥状态管理 ====================

    public DTOs.KeyStateResult getKeyStatus(String realName) {
        String sql = "SELECT status, balance, expire_time FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;

                String dbStatus = rs.getString("status");
                double balance = rs.getDouble("balance");
                String expireTime = rs.getString("expire_time");

                if (STATUS_DISABLED.equalsIgnoreCase(dbStatus)) {
                    return new DTOs.KeyStateResult(KeyStatus.DISABLED, "Disabled by admin");
                } else {
                    String pauseReason = checkConditions(balance, expireTime);
                    if (pauseReason != null) {
                        if (!STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            updateKeyStatusColumn(realName, STATUS_PAUSED);
                        }
                        return new DTOs.KeyStateResult(KeyStatus.PAUSED, pauseReason);
                    } else {
                        if (STATUS_PAUSED.equalsIgnoreCase(dbStatus)) {
                            updateKeyStatusColumn(realName, STATUS_ENABLED);
                        }
                        return new DTOs.KeyStateResult(KeyStatus.ENABLED, "OK");
                    }
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("KeyRepository", "nkm.db.queryFail", e);
            return null;
        }
    }

    public boolean setKeyStatusStrict(String name, boolean enable) {
        if (!enable) {
            return updateKeyStatusColumn(name, STATUS_DISABLED);
        }
        Map<String, Object> raw = getKeyRaw(name);
        if (raw == null) return false;

        String failReason = checkConditions((Double) raw.get("balance"), (String) raw.get("expire_time"));
        if (failReason != null) {
            updateKeyStatusColumn(name, STATUS_PAUSED);
            ServerLogger.warnWithSource("KeyRepository", "nkm.warn.enableRejected", name, failReason);
            return false;
        }
        return updateKeyStatusColumn(name, STATUS_ENABLED);
    }

    private boolean updateKeyStatusColumn(String name, String status) {
        String sql = "UPDATE keys SET status = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private String checkConditions(double balance, String expireTime) {
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

    // ==================== 密钥属性更新 ====================

    public void updateKey(String name, Double balance, Double rate, String port, String expireTime, Boolean enableWeb, Integer maxConns) {
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
                    ServerLogger.infoWithSource("KeyRepository", "nkm.info.autoEnabled", name);
                }
            }
        } catch (SQLException e) {
            ServerLogger.error("KeyRepository", "nkm.db.updateFail", e, name);
        }
    }

    public boolean setKeyMaxConns(String name, int maxConns) {
        String sql = "UPDATE keys SET max_conns = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, maxConns);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean setWebStatus(String name, boolean enable) {
        String sql = "UPDATE keys SET enable_web = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enable);
            stmt.setString(2, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 别名管理 ====================

    public String getRealKeyName(String input) {
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

    public boolean isAlias(String name) {
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

    public void addLink(String alias, String target) {
        String sql = "INSERT INTO key_aliases (alias_name, target_name, is_single) VALUES (?, ?, 0)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, alias);
            stmt.setString(2, target);
            stmt.executeUpdate();
        } catch (SQLException e) {
            ServerLogger.error("KeyRepository", "nkm.db.updateFail", e, alias);
            throw new RuntimeException(e.getMessage());
        }
    }

    public boolean deleteAlias(String alias) {
        String sql = "DELETE FROM key_aliases WHERE alias_name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, alias);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public Map<String, String> getAllLinks() {
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

    // ==================== 单例模式管理 ====================

    public boolean setKeySingle(String name, boolean isSingle) {
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

    public boolean isNameSingle(String name) {
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

    public List<String> getSingleKeys() {
        List<String> list = new ArrayList<>();
        try (Connection conn = getConnection()) {
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

    // ==================== 自定义屏蔽消息 ====================

    public boolean setCustomBlockingMsg(String realName, String msg) {
        String sql = "UPDATE keys SET custom_blocking_msg = ? WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, msg);
            stmt.setString(2, realName);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public String getCustomBlockingMsg(String realName) {
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

    public Map<String, String> getAllCustomBlockingMsgs() {
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

    // ==================== 查询方法 ====================

    public Map<String, Object> getKeyPortInfo(String realName) {
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

    public Map<String, Object> getKeyInfoFull(String realName, String nodeId) {
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
                DTOs.KeyStateResult stateResult;
                if (STATUS_DISABLED.equalsIgnoreCase(dbStatus))
                    stateResult = new DTOs.KeyStateResult(KeyStatus.DISABLED, "Disabled by admin");
                else {
                    String reason = checkConditions(balance, expireTime);
                    if (reason != null) {
                        stateResult = new DTOs.KeyStateResult(KeyStatus.PAUSED, reason);
                        if (!STATUS_PAUSED.equalsIgnoreCase(dbStatus)) updateKeyStatusColumn(realName, STATUS_PAUSED);
                    } else {
                        stateResult = new DTOs.KeyStateResult(KeyStatus.ENABLED, "OK");
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

    public int getKeyMaxConns(String realName) {
        String sql = "SELECT max_conns FROM keys WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("max_conns");
            }
        } catch (SQLException ignored) {
        }
        return 1;
    }

    public Map<String, Object> getKeyRaw(String name) {
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

    // ==================== 批量查询 ====================

    public Map<String, Protocol.KeyMetadata> getBatchKeyMetadata(List<String> realKeys) {
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

    public List<AdminDTOs.KeyDetail> getAllKeysStructured(boolean includeMaps, String targetKeyName, Map<String, List<AdminDTOs.MapNode>> mapInfo) {
        List<AdminDTOs.KeyDetail> result = new ArrayList<>();
        try (Connection conn = getConnection()) {
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
            ServerLogger.error("KeyRepository", "nkm.db.queryFail", e);
        }
        return result;
    }

    public List<Map<String, String>> getAllKeysRaw(Map<String, List<String>> mapInfo) {
        List<Map<String, String>> result = new ArrayList<>();
        final String ANSI_RESET = "\u001B[0m";
        final String ANSI_GREEN = "\u001B[32m";
        final String ANSI_RED = "\u001B[31m";
        final String ANSI_YELLOW = "\u001B[33m";
        final String ANSI_BLUE = "\u001B[34m";

        try (Connection conn = getConnection()) {
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
            ServerLogger.error("KeyRepository", "nkm.db.listFail", e);
        }
        return result;
    }
}
