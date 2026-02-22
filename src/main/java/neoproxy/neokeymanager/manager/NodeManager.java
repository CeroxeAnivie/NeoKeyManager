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
        loadNodeJson();
    }

    public static NodeManager getInstance() {
        return INSTANCE;
    }

    public synchronized void loadNodeJson() {
        if (Config.NODE_JSON_FILE == null || Config.NODE_JSON_FILE.isBlank()) {
            ServerLogger.warnWithSource("NodeManager", "nkm.node.jsonInvalid", "NODE_JSON_FILE");
            disableService();
            return;
        }

        File file = new File(Config.NODE_JSON_FILE);
        if (!file.exists()) {
            ServerLogger.warnWithSource("NodeManager", "nkm.node.jsonInvalid", file.getAbsolutePath());
            disableService();
            return;
        }

        try {
            List<Protocol.PublicNodeInfo> loaded = MAPPER.readValue(file, new TypeReference<List<Protocol.PublicNodeInfo>>() {
            });
            if (loaded == null || loaded.isEmpty()) {
                ServerLogger.warnWithSource("NodeManager", "nkm.node.jsonInvalid", file.getName() + " (Empty)");
                disableService();
                return;
            }

            publicNodesConfig = loaded;
            isConfigured = true;
            ServerLogger.infoWithSource("NodeManager", "nkm.node.jsonLoaded", publicNodesConfig.size(), file.getName());
        } catch (Exception e) {
            ServerLogger.error("NodeManager", "nkm.error.jsonParse", e);
            disableService();
        }
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

    public List<Protocol.PublicNodeInfo> getOnlinePublicNodes() {
        List<Protocol.PublicNodeInfo> result = new ArrayList<>();
        if (!isConfigured) return result;

        List<Protocol.PublicNodeInfo> currentConfig = publicNodesConfig;
        long now = System.currentTimeMillis();

        for (Protocol.PublicNodeInfo info : currentConfig) {
            if (info.name == null) continue;
            String realNodeId = NodeAuthManager.getInstance().getRealIdByDisplayName(info.name);
            if (realNodeId == null) continue;

            Long lastSeen = nodeLastSeenMap.get(realNodeId.toLowerCase());
            if (lastSeen != null && (now - lastSeen) <= NODE_TIMEOUT_MS) {
                result.add(info);
            }
        }
        return result;
    }
}
