package neoproxy.neokeymanager.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeManager {
    // 【核心修复】：必须先初始化 MAPPER，再初始化 INSTANCE，否则会引发空指针异常！
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final NodeManager INSTANCE = new NodeManager();

    private static final long NODE_TIMEOUT_MS = 60000L;
    private final ConcurrentHashMap<String, Long> nodeLastSeenMap = new ConcurrentHashMap<>();
    private volatile List<Protocol.PublicNodeInfo> publicNodesConfig = Collections.emptyList();
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
            nodeLastSeenMap.clear();
            for (Protocol.PublicNodeInfo info : loaded) {
                Protocol.PublicNodeInfo normalized = normalizePublicNodeInfo(info);
                if (normalized == null) {
                    ServerLogger.warnWithSource("NodeManager", "nkm.nodes.jsonInvalid", file.getName() + " (NodeAuth mismatch)");
                    disableService();
                    return;
                }
                if (normalized.lastSeen > 0) {
                    nodeLastSeenMap.put(nodeKey(normalized.realId), normalized.lastSeen);
                }
                normalized.online = isTimestampOnline(normalized.lastSeen);
                normalizedNodes.add(normalized);
            }

            publicNodesConfig = Collections.unmodifiableList(normalizedNodes);
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
            // 旧配置使用 display name 作为关联键。这里保留迁移兼容，
            // 然后将运行时模型归一化为稳定的 realId，确保展示文本可以安全变更。
            realId = authManager.getRealIdByDisplayName(info.name);
        } else {
            realId = authManager.getCanonicalRealId(realId);
        }

        if (realId == null) return null;

        Protocol.PublicNodeInfo normalized = copyPublicNodeInfo(info);
        normalized.realId = realId;
        if (trimToNull(normalized.name) == null) {
            normalized.name = authManager.getAlias(realId);
        }
        return normalized;
    }

    public synchronized boolean recordNodeStatus(Protocol.NodeStatusPayload payload) {
        if (payload == null) return false;

        NodeAuthManager authManager = NodeAuthManager.getInstance();
        String realId = authManager.getCanonicalRealId(payload.nodeId);
        String address = trimToNull(payload.address);
        if (realId == null || address == null || !isValidPort(payload.hookPort) || !isValidPort(payload.connectPort)) {
            return false;
        }

        long now = System.currentTimeMillis();
        String nodeKey = nodeKey(realId);
        List<Protocol.PublicNodeInfo> nextConfig = new ArrayList<>();
        Protocol.PublicNodeInfo updatedNode = null;
        Protocol.PublicNodeInfo previousNode = null;

        for (Protocol.PublicNodeInfo current : publicNodesConfig) {
            Protocol.PublicNodeInfo copy = copyPublicNodeInfo(current);
            if (nodeKey(copy.realId).equals(nodeKey)) {
                previousNode = current;
                updatedNode = copy;
            } else {
                copy.online = isNodeOnline(copy.realId);
            }
            nextConfig.add(copy);
        }

        if (updatedNode == null) {
            updatedNode = new Protocol.PublicNodeInfo();
            updatedNode.realId = realId;
            updatedNode.name = authManager.getAlias(realId);
            nextConfig.add(updatedNode);
        }

        updatedNode.address = address;
        updatedNode.HOST_HOOK_PORT = payload.hookPort;
        updatedNode.HOST_CONNECT_PORT = payload.connectPort;
        updatedNode.version = trimToNull(payload.version);
        updatedNode.activeTunnels = Math.max(0, payload.activeTunnels);
        updatedNode.lastSeen = now;
        updatedNode.online = true;

        publicNodesConfig = Collections.unmodifiableList(nextConfig);
        isConfigured = true;
        nodeLastSeenMap.put(nodeKey, now);

        persistNodesJson(nextConfig);
        if (hasRuntimeEndpointChanged(previousNode, address, payload.hookPort, payload.connectPort)) {
            ServerLogger.infoWithSource("NodeManager", "nkm.nodes.statusUpdated",
                    realId, address, payload.hookPort, payload.connectPort);
        }
        return true;
    }

    private void persistNodesJson(List<Protocol.PublicNodeInfo> nodes) {
        if (Config.NODE_JSON_FILE == null || Config.NODE_JSON_FILE.isBlank()) {
            ServerLogger.errorWithSource("NodeManager", "nkm.nodes.jsonInvalid", "NODE_JSON_FILE");
            return;
        }
        File file = new File(Config.NODE_JSON_FILE);
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create directory: " + parent.getAbsolutePath());
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, toPersistedNodeConfig(nodes));
        } catch (Exception e) {
            ServerLogger.error("NodeManager", "nkm.nodes.jsonSaveFail", e, file.getAbsolutePath());
        }
    }

    private List<Map<String, Object>> toPersistedNodeConfig(List<Protocol.PublicNodeInfo> nodes) {
        List<Map<String, Object>> persistedNodes = new ArrayList<>();
        for (Protocol.PublicNodeInfo node : nodes) {
            if (node == null) continue;

            Map<String, Object> persistedNode = new LinkedHashMap<>();
            persistedNode.put("realId", node.realId);
            persistedNode.put("name", node.name);
            persistedNode.put("address", node.address);
            if (trimToNull(node.icon) != null) {
                persistedNode.put("icon", node.icon);
            }
            persistedNode.put("HOST_HOOK_PORT", node.HOST_HOOK_PORT);
            persistedNode.put("HOST_CONNECT_PORT", node.HOST_CONNECT_PORT);
            persistedNodes.add(persistedNode);
        }
        return persistedNodes;
    }

    private boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nodeKey(String realNodeId) {
        return realNodeId == null ? "" : realNodeId.toLowerCase().trim();
    }

    private boolean hasRuntimeEndpointChanged(Protocol.PublicNodeInfo previousNode,
                                              String address,
                                              int hookPort,
                                              int connectPort) {
        if (previousNode == null) {
            return true;
        }
        return !sameText(previousNode.address, address)
                || previousNode.HOST_HOOK_PORT != hookPort
                || previousNode.HOST_CONNECT_PORT != connectPort;
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = trimToNull(left);
        String normalizedRight = trimToNull(right);
        if (normalizedLeft == null) {
            return normalizedRight == null;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private boolean isTimestampOnline(long lastSeen) {
        return lastSeen > 0 && (System.currentTimeMillis() - lastSeen) <= NODE_TIMEOUT_MS;
    }

    private Protocol.PublicNodeInfo copyPublicNodeInfo(Protocol.PublicNodeInfo source) {
        Protocol.PublicNodeInfo copy = new Protocol.PublicNodeInfo();
        if (source == null) return copy;
        copy.realId = source.realId;
        copy.name = source.name;
        copy.address = source.address;
        copy.icon = source.icon;
        copy.HOST_HOOK_PORT = source.HOST_HOOK_PORT;
        copy.HOST_CONNECT_PORT = source.HOST_CONNECT_PORT;
        copy.version = source.version;
        copy.online = source.online;
        copy.lastSeen = source.lastSeen;
        copy.activeTunnels = source.activeTunnels;
        return copy;
    }

    private void disableService() {
        publicNodesConfig = Collections.emptyList();
        nodeLastSeenMap.clear();
        isConfigured = false;
    }

    public boolean isConfigured() {
        return isConfigured;
    }

    public synchronized void markNodeOnline(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return;
        long now = System.currentTimeMillis();
        String canonicalRealId = NodeAuthManager.getInstance().getCanonicalRealId(realNodeId);
        String nodeKey = nodeKey(canonicalRealId == null ? realNodeId : canonicalRealId);
        nodeLastSeenMap.put(nodeKey, now);

        List<Protocol.PublicNodeInfo> nextConfig = new ArrayList<>();
        boolean changed = false;
        for (Protocol.PublicNodeInfo current : publicNodesConfig) {
            Protocol.PublicNodeInfo copy = copyPublicNodeInfo(current);
            if (nodeKey(copy.realId).equals(nodeKey)) {
                copy.lastSeen = now;
                copy.online = true;
                changed = true;
            } else {
                copy.online = isNodeOnline(copy.realId);
            }
            nextConfig.add(copy);
        }
        if (changed) {
            publicNodesConfig = Collections.unmodifiableList(nextConfig);
        }
    }

    // [新增] 暴露给 Admin API 的查询接口：判断某节点是否在线
    public boolean isNodeOnline(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return false;
        Long lastSeen = nodeLastSeenMap.get(nodeKey(realNodeId));
        return lastSeen != null && (System.currentTimeMillis() - lastSeen) <= NODE_TIMEOUT_MS;
    }

    public Protocol.PublicNodeInfo getNodeInfo(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return null;
        String expectedKey = nodeKey(realNodeId);
        for (Protocol.PublicNodeInfo info : publicNodesConfig) {
            if (nodeKey(info.realId).equals(expectedKey)) {
                Protocol.PublicNodeInfo copy = copyPublicNodeInfo(info);
                copy.online = isNodeOnline(copy.realId);
                return copy;
            }
        }
        return null;
    }

    public List<Protocol.PublicNodeInfo> getOnlinePublicNodes() {
        List<Protocol.PublicNodeInfo> result = new ArrayList<>();
        if (!isConfigured) return result;

        for (Protocol.PublicNodeInfo info : publicNodesConfig) {
            if (isNodeOnline(info.realId)) {
                Protocol.PublicNodeInfo copy = copyPublicNodeInfo(info);
                copy.online = true;
                result.add(copy);
            }
        }
        return result;
    }
}
