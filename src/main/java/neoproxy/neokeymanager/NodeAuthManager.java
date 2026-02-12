package neoproxy.neokeymanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点鉴权与隐私管理器
 * 修复：静态变量初始化顺序问题
 * 新增：支持实时热重载与鉴权时二次确认
 */
public class NodeAuthManager {
    private static final String AUTH_FILE = "NodeAuth.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NodeAuthManager INSTANCE = new NodeAuthManager();

    // Key: Lowercase Real NodeID, Value: NodeConfig
    private final ConcurrentHashMap<String, NodeConfig> authMap = new ConcurrentHashMap<>();

    private NodeAuthManager() {
        load();
    }

    public static NodeAuthManager getInstance() {
        return INSTANCE;
    }

    /**
     * 鉴权并获取显示名称
     * 修改逻辑：内存没找到 -> 读盘 -> 再找 -> 还没找到 -> 查库
     */
    public String authenticateAndGetAlias(String realNodeId) {
        if (realNodeId == null) return null;
        String key = realNodeId.toLowerCase().trim();

        // 1. 检查内存白名单
        if (authMap.containsKey(key)) {
            return authMap.get(key).displayName;
        }

        // [新增] 2. 内存没有？可能是刚手动改了文件，强制热重载一次 JSON
        // 这实现了"有新节点进入的时候都要实时查询json确保最新"
        load();

        // 3. 重载后再次检查内存
        if (authMap.containsKey(key)) {
            NodeConfig config = authMap.get(key);
            ServerLogger.info("NodeAuth", "nkm.node.hotLoaded", realNodeId, config.displayName);
            return config.displayName;
        }

        // 4. 检查数据库历史映射 (自动迁移逻辑)
        if (Database.isNodeMappedAnywhere(realNodeId)) {
            String alias = "Auto-" + realNodeId;
            addNodeToAllowlist(realNodeId, alias);
            ServerLogger.infoWithSource("NodeAuth", "nkm.node.autoAdded", realNodeId);
            return alias;
        }

        ServerLogger.warnWithSource("NodeAuth", "nkm.node.rejected", realNodeId);
        return null;
    }

    public String getAlias(String realNodeId) {
        if (realNodeId == null) return "Unknown";
        NodeConfig config = authMap.get(realNodeId.toLowerCase().trim());
        return config != null ? config.displayName : realNodeId;
    }

    public synchronized void addNodeToAllowlist(String realId, String displayName) {
        NodeConfig config = new NodeConfig(realId, displayName);
        authMap.put(realId.toLowerCase().trim(), config);
        save();
    }

    // [修改] 改为 public synchronized，供 reload 命令调用
    // 同时先 clear 内存，确保删除的节点在重载后失效
    public synchronized void load() {
        File file = new File(AUTH_FILE);
        if (!file.exists()) return;
        try {
            Map<String, NodeConfig> loaded = MAPPER.readValue(file, new TypeReference<Map<String, NodeConfig>>() {
            });

            // 如果希望 reload 能删除掉 JSON 里已经移除的节点，需要先清理 Map
            // 注意：这不会清除 addNodeToAllowlist 还没保存的情况，因为 add 操作会立即 save 到磁盘
            // 所以以磁盘为最终真理是安全的。
            authMap.clear(); // 可选：如果你希望 reload 能把内存里多余的踢掉，就取消注释这行

            loaded.forEach((k, v) -> authMap.put(k.toLowerCase().trim(), v));
        } catch (IOException e) {
            ServerLogger.error("NodeAuth", "Failed to load NodeAuth.json", e);
        }
    }

    private synchronized void save() {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(AUTH_FILE), authMap);
        } catch (IOException e) {
            ServerLogger.error("NodeAuth", "Failed to save NodeAuth.json", e);
        }
    }

    public boolean isNodeExplicitlyRegistered(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return false;
        // 这里的 authMap 是 NodeAuthManager 的成员变量
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