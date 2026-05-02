package neoproxy.neokeymanager.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点权限管理器
 * 严格基于 NodeAuth.json 文件进行身份验证和别名映射
 */
public class NodeAuthManager {

    private static final String AUTH_FILE = "NodeAuth.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static NodeAuthManager INSTANCE = new NodeAuthManager();

    // Key：小写的 real NodeID，Value：NodeConfig
    private final ConcurrentHashMap<String, NodeConfig> authMap = new ConcurrentHashMap<>();

    // 反向映射字典：DisplayName -> 小写的 real NodeID
    private final ConcurrentHashMap<String, String> displayNameToRealIdMap = new ConcurrentHashMap<>();
    private final Set<String> ambiguousDisplayNames = ConcurrentHashMap.newKeySet();

    private NodeAuthManager() {
        load();
    }

    public static NodeAuthManager getInstance() {
        return INSTANCE;
    }

    /**
     * 重置单例实例（仅用于测试）
     */
    public static synchronized void resetInstance() {
        INSTANCE = new NodeAuthManager();
    }

    /**
     * 验证节点身份并获取别名。
     * 逻辑：先从缓存查，没有则重载文件再查。若文件中仍不存在，则拒绝。
     */
    public String authenticateAndGetAlias(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return null;
        String key = realNodeId.toLowerCase().trim();

        // 1. 尝试从当前缓存获取
        if (authMap.containsKey(key)) {
            return authMap.get(key).displayName;
        }

        // 2. 缓存未命中，尝试重新从文件加载（支持手动编辑文件后的热加载）
        load();

        // 3. 再次检查
        if (authMap.containsKey(key)) {
            NodeConfig config = authMap.get(key);
            ServerLogger.info("NodeAuth", "nkm.node.hotLoaded", realNodeId, config.displayName);
            return config.displayName;
        }

        // 4. 严格校验：不在配置文件中的节点一律拒绝
        ServerLogger.warnWithSource("NodeAuth", "nkm.node.rejected", realNodeId);
        return null;
    }

    /**
     * 获取别名（不触发认证逻辑，仅用于显示）
     */
    public String getAlias(String realNodeId) {
        if (realNodeId == null) return "Unknown";
        NodeConfig config = authMap.get(realNodeId.toLowerCase().trim());
        return config != null ? config.displayName : realNodeId;
    }

    /**
     * O(1) 效率反向查询真实 ID
     */
    public String getRealIdByDisplayName(String displayName) {
        if (displayName == null) return null;
        String normalized = displayName.trim();
        if (normalized.isEmpty() || ambiguousDisplayNames.contains(normalized)) return null;
        return displayNameToRealIdMap.get(normalized);
    }

    /**
     * 返回 NodeAuth 中登记的规范 realId，避免不同入口用大小写或空格创建出多套节点身份。
     */
    public String getCanonicalRealId(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return null;
        NodeConfig config = authMap.get(realNodeId.toLowerCase().trim());
        return config == null ? null : config.realId;
    }

    /**
     * 获取所有已注册的节点配置列表
     */
    public List<NodeConfig> getAllRegisteredNodes() {
        return new ArrayList<>(authMap.values());
    }

    /**
     * 手动添加节点到白名单并持久化
     */
    public synchronized void addNodeToAllowlist(String realId, String displayName) {
        if (realId == null || realId.isBlank()) {
            ServerLogger.warnWithSource("NodeAuth", "nkm.node.invalidRealId");
            return;
        }
        String normalizedRealId = realId.trim();
        String normalizedDisplayName = (displayName == null || displayName.isBlank()) ? normalizedRealId : displayName.trim();
        NodeConfig config = new NodeConfig(normalizedRealId, normalizedDisplayName);
        String lowerKey = normalizedRealId.toLowerCase().trim();
        NodeConfig previous = authMap.put(lowerKey, config);
        if (previous != null && previous.displayName != null && !previous.displayName.equals(normalizedDisplayName)) {
            displayNameToRealIdMap.remove(previous.displayName, lowerKey);
        }
        registerDisplayName(normalizedDisplayName, lowerKey);
        save();
    }

    /**
     * 从 NodeAuth.json 加载配置
     */
    public synchronized void load() {
        // 优先使用系统属性指定的文件路径，否则使用默认路径
        String authFilePath = System.getProperty("node.auth.file", AUTH_FILE);
        File file = new File(authFilePath);
        if (!file.exists()) {
            ServerLogger.warnWithSource("NodeAuth", "nkm.node.authFileMissing", authFilePath);
            authMap.clear();
            displayNameToRealIdMap.clear();
            ambiguousDisplayNames.clear();
            return;
        }
        try {
            Map<String, NodeConfig> loaded = MAPPER.readValue(file, new TypeReference<Map<String, NodeConfig>>() {
            });

            authMap.clear();
            displayNameToRealIdMap.clear();
            ambiguousDisplayNames.clear();

            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    NodeConfig sanitized = sanitizeNodeConfig(k, v);
                    if (sanitized == null) return;

                    String lowerKey = sanitized.realId.toLowerCase().trim();
                    authMap.put(lowerKey, sanitized);
                    registerDisplayName(sanitized.displayName, lowerKey);
                });
            }
        } catch (Exception e) {
            ServerLogger.error("NodeAuth", "nkm.node.authLoadFail", e);
            authMap.clear();
            displayNameToRealIdMap.clear();
            ambiguousDisplayNames.clear();
        }
    }

    private void registerDisplayName(String displayName, String lowerRealId) {
        if (displayName == null || displayName.isBlank() || lowerRealId == null || lowerRealId.isBlank()) return;
        String normalizedDisplayName = displayName.trim();
        if (ambiguousDisplayNames.contains(normalizedDisplayName)) return;

        String existing = displayNameToRealIdMap.putIfAbsent(normalizedDisplayName, lowerRealId);
        if (existing != null && !existing.equals(lowerRealId)) {
            displayNameToRealIdMap.remove(normalizedDisplayName);
            ambiguousDisplayNames.add(normalizedDisplayName);
            ServerLogger.warnWithSource("NodeAuth", "nkm.node.duplicateDisplayName", normalizedDisplayName);
        }
    }

    private NodeConfig sanitizeNodeConfig(String jsonKey, NodeConfig config) {
        String keyCandidate = jsonKey == null ? "" : jsonKey.trim();
        String realIdCandidate = config == null || config.realId == null ? "" : config.realId.trim();
        String realId = !realIdCandidate.isBlank() ? realIdCandidate : keyCandidate;

        if (realId.isBlank()) {
            ServerLogger.warnWithSource("NodeAuth", "nkm.node.invalidEntry", jsonKey);
            return null;
        }

        String displayName = config == null || config.displayName == null || config.displayName.isBlank()
                ? realId
                : config.displayName.trim();
        return new NodeConfig(realId, displayName);
    }

    /**
     * 将当前配置保存到 NodeAuth.json
     */
    private synchronized void save() {
        try {
            String authFilePath = System.getProperty("node.auth.file", AUTH_FILE);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(authFilePath), authMap);
        } catch (IOException e) {
            ServerLogger.error("NodeAuth", "nkm.node.authSaveFail", e);
        }
    }

    /**
     * 检查节点是否显式存在于白名单中
     */
    public boolean isNodeExplicitlyRegistered(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return false;
        return authMap.containsKey(nodeId.toLowerCase().trim());
    }

    public static class NodeConfig {
        public String realId;
        public String displayName;

        public NodeConfig() {
        }

        public NodeConfig(String realId, String displayName) {
            this.realId = realId;
            this.displayName = displayName;
        }
    }
}
