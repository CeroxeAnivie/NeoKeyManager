package neoproxy.neokeymanager.repository;

import neoproxy.neokeymanager.utils.ServerLogger;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 节点映射数据访问层
 * 负责 node_ports 表的所有操作
 */
public class NodeMappingRepository {
    private final String dbUrl;

    public NodeMappingRepository(String dbUrl) {
        this.dbUrl = dbUrl;
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    // ==================== 节点映射 CRUD ====================

    public void addNodePort(String realName, String nodeId, String port) {
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
            ServerLogger.error("NodeMappingRepository", "nkm.db.mapFail", e);
        }
    }

    public boolean deleteNodeMap(String realName, String nodeId) {
        String sql = "DELETE FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realName);
            stmt.setString(2, nodeId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public int deleteNodeMapByNode(String nodeId) {
        String sql = "DELETE FROM node_ports WHERE LOWER(node_id) = LOWER(?)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nodeId);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            ServerLogger.error("NodeMappingRepository", "nkm.db.mapFail", e);
            return 0;
        }
    }

    // ==================== 查询方法 ====================

    public boolean hasSpecificMap(String realKeyName, String nodeId) {
        String sql = "SELECT 1 FROM node_ports WHERE key_name = ? AND LOWER(node_id) = LOWER(?) LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, realKeyName);
            stmt.setString(2, nodeId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean isNodeMappedAnywhere(String nodeId) {
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

    public Map<String, Map<String, String>> getAllNodePorts() {
        Map<String, Map<String, String>> result = new HashMap<>();
        String sql = "SELECT key_name, node_id, port FROM node_ports";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String keyName = rs.getString("key_name");
                String nodeId = rs.getString("node_id");
                String port = rs.getString("port");
                result.computeIfAbsent(keyName, k -> new HashMap<>()).put(nodeId, port);
            }
        } catch (SQLException ignored) {
        }
        return result;
    }
}
