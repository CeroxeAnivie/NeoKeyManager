package neoproxy.neokeymanager.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import neoproxy.neokeymanager.database.Database;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeAuthManager {

    private static final String AUTH_FILE = "NodeAuth.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NodeAuthManager INSTANCE = new NodeAuthManager();

    // Key: Lowercase Real NodeID, Value: NodeConfig
    private final ConcurrentHashMap<String, NodeConfig> authMap = new ConcurrentHashMap<>();

    // [新增] 反向映射字典: DisplayName -> Lowercase Real NodeID
    private final ConcurrentHashMap<String, String> displayNameToRealIdMap = new ConcurrentHashMap<>();

    private NodeAuthManager() {
        load();
    }

    public static NodeAuthManager getInstance() {
        return INSTANCE;
    }

    public String authenticateAndGetAlias(String realNodeId) {
        if (realNodeId == null) return null;
        String key = realNodeId.toLowerCase().trim();

        if (authMap.containsKey(key)) {
            return authMap.get(key).displayName;
        }

        load();

        if (authMap.containsKey(key)) {
            NodeConfig config = authMap.get(key);
            ServerLogger.info("NodeAuth", "nkm.node.hotLoaded", realNodeId, config.displayName);
            return config.displayName;
        }

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

    // [新增] O(1) 效率反向查询真实 ID
    public String getRealIdByDisplayName(String displayName) {
        if (displayName == null) return null;
        return displayNameToRealIdMap.get(displayName);
    }

    public synchronized void addNodeToAllowlist(String realId, String displayName) {
        NodeConfig config = new NodeConfig(realId, displayName);
        String lowerKey = realId.toLowerCase().trim();
        authMap.put(lowerKey, config);
        if (displayName != null) {
            displayNameToRealIdMap.put(displayName, lowerKey);
        }
        save();
    }

    public synchronized void load() {
        File file = new File(AUTH_FILE);
        if (!file.exists()) return;
        try {
            Map<String, NodeConfig> loaded = MAPPER.readValue(file, new TypeReference<Map<String, NodeConfig>>() {
            });

            authMap.clear();
            displayNameToRealIdMap.clear();

            loaded.forEach((k, v) -> {
                String lowerKey = k.toLowerCase().trim();
                authMap.put(lowerKey, v);
                if (v.displayName != null) {
                    displayNameToRealIdMap.put(v.displayName, lowerKey);
                }
            });
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
