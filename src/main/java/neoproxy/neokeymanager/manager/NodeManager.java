package neoproxy.neokeymanager.manager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 节点管理器
 * 管理公开节点列表和节点在线状态检测
 */
public class NodeManager {
    // 【核心修复】: 必须先初始化 GSON，再初始化 INSTANCE，否则会引发空指针异常！
    private static final Gson GSON = new Gson();
    private static final NodeManager INSTANCE = new NodeManager();

    // 节点超时时间：60秒（两次心跳周期，NPS每30秒发送一次心跳）
    private static final long NODE_TIMEOUT_MS = 60000L;
    // 心跳检测间隔：30秒（与 NPS 的心跳发送间隔保持一致）
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 30000L;

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

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<Protocol.PublicNodeInfo> loaded = GSON.fromJson(reader, new TypeToken<List<Protocol.PublicNodeInfo>>() {}.getType());
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

    /**
     * 标记节点在线（收到心跳时调用）
     */
    public void markNodeOnline(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return;
        nodeLastSeenMap.put(realNodeId.toLowerCase().trim(), System.currentTimeMillis());
    }

    /**
     * 获取心跳检测间隔（毫秒）
     */
    public long getHeartbeatCheckIntervalMs() {
        return HEARTBEAT_CHECK_INTERVAL_MS;
    }

    /**
     * 获取节点超时时间（毫秒）
     */
    public long getNodeTimeoutMs() {
        return NODE_TIMEOUT_MS;
    }

    /**
     * 暴露给 Admin API 的查询接口：判断某节点是否在线
     * 逻辑：60秒内收到过心跳视为在线（两次心跳周期）
     */
    public boolean isNodeOnline(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return false;
        Long lastSeen = nodeLastSeenMap.get(realNodeId.toLowerCase().trim());
        return lastSeen != null && (System.currentTimeMillis() - lastSeen) <= NODE_TIMEOUT_MS;
    }

    /**
     * 获取节点的最后活跃时间
     */
    public Long getNodeLastSeen(String realNodeId) {
        if (realNodeId == null || realNodeId.isBlank()) return null;
        return nodeLastSeenMap.get(realNodeId.toLowerCase().trim());
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
