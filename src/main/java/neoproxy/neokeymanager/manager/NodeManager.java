package neoproxy.neokeymanager.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class NodeManager {
    // 【核心修复】: 必须先初始化 MAPPER，再初始化 INSTANCE，否则会引发空指针异常！
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NodeManager INSTANCE = new NodeManager();

    private static final long NODE_TIMEOUT_MS = 60000L;
    private final ConcurrentHashMap<String, Long> nodeLastSeenMap = new ConcurrentHashMap<>();
    private volatile List<Protocol.PublicNodeInfo> publicNodesConfig = new ArrayList<>();
    private volatile boolean isConfigured = false;

    private NodeManager() {
        loadNodesJson();
    }

    public static NodeManager getInstance() {
        return INSTANCE;
    }

    public synchronized void loadNodesJson() {
        if (Config.NODE_JSON_FILE == null || Config.NODE_JSON_FILE.isBlank()) {
            ServerLogger.warnWithSource("NodeManager", "nkm.nodes.jsonInvalid", "NODE_JSON_FILE");
            disableService();
            return;
        }

        File file = new File(Config.NODE_JSON_FILE);
        if (!file.exists()) {
            ServerLogger.warnWithSource("NodeManager", "nkm.nodes.jsonInvalid", file.getAbsolutePath());
            disableService();
            return;
        }

        try {
            List<Protocol.PublicNodeInfo> loaded = MAPPER.readValue(file, new TypeReference<List<Protocol.PublicNodeInfo>>() {
            });
            if (loaded == null || loaded.isEmpty()) {
                ServerLogger.warnWithSource("NodeManager", "nkm.nodes.jsonInvalid", file.getName() + " (Empty)");
                disableService();
                return;
            }

            List<Protocol.PublicNodeInfo> normalizedNodes = new ArrayList<>();
            for (Protocol.PublicNodeInfo info : loaded) {
                Protocol.PublicNodeInfo normalized = normalizePublicNodeInfo(info);
                if (normalized == null) {
                    ServerLogger.warnWithSource("NodeManager", "nkm.nodes.jsonInvalid", file.getName() + " (NodeAuth mismatch)");
                    disableService();
                    return;
                }
                normalizedNodes.add(normalized);
            }

            publicNodesConfig = normalizedNodes;
            isConfigured = true;
            ServerLogger.infoWithSource("NodeManager", "nkm.nodes.jsonLoaded", publicNodesConfig.size(), file.getName());
        } catch (Exception e) {
            ServerLogger.error("NodeManager", "nkm.error.jsonParse", e);
            disableService();
        }
    }

    private Protocol.PublicNodeInfo normalizePublicNodeInfo(Protocol.PublicNodeInfo info) {
        if (info == null) return null;

        NodeAuthManager authManager = NodeAuthManager.getInstance();
        String realId = trimToNull(info.realId);
        if (realId == null) {
            // Legacy configs used the display name as the join key. Keep migration support,
            // then normalize the runtime model to stable realId so display text can change safely.
            realId = authManager.getRealIdByDisplayName(info.name);
        } else {
            realId = authManager.getCanonicalRealId(realId);
        }

        if (realId == null) return null;

        info.realId = realId;
        if (trimToNull(info.name) == null) {
            info.name = authManager.getAlias(realId);
        }
        return info;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void disableService() {
        publicNodesConfig = new ArrayList<>();
        isConfigured = false;
    }

    public boolean isConfigured() {
        return isConfigured;
    }

    public void markNodeOnline(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return;
        nodeLastSeenMap.put(realNodeId.toLowerCase().trim(), System.currentTimeMillis());
    }

    // [新增] 暴露给 Admin API 的查询接口：判断某节点是否在线
    public boolean isNodeOnline(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return false;
        Long lastSeen = nodeLastSeenMap.get(realNodeId.toLowerCase().trim());
        return lastSeen != null && (System.currentTimeMillis() - lastSeen) <= NODE_TIMEOUT_MS;
    }

    public List<Protocol.PublicNodeInfo> getOnlinePublicNodes() {
        List<Protocol.PublicNodeInfo> result = new ArrayList<>();
        if (!isConfigured) return result;

        List<Protocol.PublicNodeInfo> currentConfig = publicNodesConfig;
        long now = System.currentTimeMillis();

        for (Protocol.PublicNodeInfo info : currentConfig) {
            String realNodeId = info.realId;
            if (realNodeId == null || realNodeId.isBlank()) continue;

            Long lastSeen = nodeLastSeenMap.get(realNodeId.toLowerCase().trim());
            if (lastSeen != null && (now - lastSeen) <= NODE_TIMEOUT_MS) {
                result.add(info);
            }
        }
        return result;
    }
}
