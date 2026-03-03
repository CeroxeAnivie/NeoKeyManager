package neoproxy.neokeymanager.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点权限管理器
 * 严格基于 NodeAuth.json 文件进行身份验证和别名映射
 */
public class NodeAuthManager {

    private static final String AUTH_FILE = "NodeAuth.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NodeAuthManager INSTANCE = new NodeAuthManager();

    // Key: Lowercase Real NodeID, Value: NodeConfig
    private final ConcurrentHashMap<String, NodeConfig> authMap = new ConcurrentHashMap<>();

    // 反向映射字典: DisplayName -> Lowercase Real NodeID
    private final ConcurrentHashMap<String, String> displayNameToRealIdMap = new ConcurrentHashMap<>();

    private NodeAuthManager() {
        load();
    }

    public static NodeAuthManager getInstance() {
        return INSTANCE;
    }

    /**
     * 验证节点身份并获取别名
     * 逻辑：先从缓存查，没有则重载文件查。若文件中不存在，则拒绝。
     */
    public String authenticateAndGetAlias(String realNodeId) {
        if (realNodeId == null) return null;
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
        return displayNameToRealIdMap.get(displayName);
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
        NodeConfig config = new NodeConfig(realId, displayName);
        String lowerKey = realId.toLowerCase().trim();
        authMap.put(lowerKey, config);
        if (displayName != null) {
            displayNameToRealIdMap.put(displayName, lowerKey);
        }
        save();
    }

    /**
     * 从 NodeAuth.json 加载配置
     */
    public synchronized void load() {
        File file = new File(AUTH_FILE);
        if (!file.exists()) {
            ServerLogger.warn("NodeAuth", "NodeAuth.json not found, allowlist is empty.");
            authMap.clear();
            displayNameToRealIdMap.clear();
            return;
        }
        try {
            Map<String, NodeConfig> loaded = MAPPER.readValue(file, new TypeReference<Map<String, NodeConfig>>() {
            });

            authMap.clear();
            displayNameToRealIdMap.clear();

            if (loaded != null) {
                loaded.forEach((k, v) -> {
                    String lowerKey = k.toLowerCase().trim();
                    authMap.put(lowerKey, v);
                    if (v.displayName != null) {
                        displayNameToRealIdMap.put(v.displayName, lowerKey);
                    }
                });
            }
        } catch (Exception e) {
            ServerLogger.error("NodeAuth", "Failed to load NodeAuth.json. Clearing allowlist.", e);
            authMap.clear();
            displayNameToRealIdMap.clear();
        }
    }

    /**
     * 将当前配置保存到 NodeAuth.json
     */
    private synchronized void save() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(AUTH_FILE), authMap);
        } catch (IOException e) {
            ServerLogger.error("NodeAuth", "Failed to save NodeAuth.json", e);
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
