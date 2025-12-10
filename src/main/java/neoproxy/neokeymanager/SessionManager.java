package neoproxy.neokeymanager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 工业级 Session 管理器 (Fixed)
 * 修复了接口兼容性问题，增强了并发安全性
 */
public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();

    // KeyName -> (NodeId -> HeartbeatInfo)
    // 外层 Map 使用 ConcurrentHashMap 保证 Key 级别的并发安全
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, NodeSession>> sessions = new ConcurrentHashMap<>();

    // 僵尸节点检测线程
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NKM-Session-Monitor");
        t.setDaemon(true);
        return t;
    });

    private SessionManager() {
        // 每 3 秒执行一次检查，清理超时超过 20 秒的节点
        monitor.scheduleAtFixedRate(this::checkZombies, 3, 3, TimeUnit.SECONDS);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * [修复] 供 Database.getKeyInfo 调用
     * 处理初始连接请求，此时客户端尚未绑定最终端口，使用 "INIT" 占位
     */
    public boolean tryAcquireOrRefresh(String keyName, String nodeId, int maxConnections) {
        return handleHeartbeat(keyName, nodeId, "INIT", maxConnections);
    }

    /**
     * 核心方法：处理心跳与连接注册
     * 使用 synchronized 块解决 "检查-执行" 的竞态条件
     */
    public boolean handleHeartbeat(String keyName, String nodeId, String portReported, int maxConnections) {
        // 获取或创建该 Key 的节点映射表
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(keyName, k -> new ConcurrentHashMap<>());

        // 锁定该 Key 的 Map，确保连接数检查和插入操作的原子性
        synchronized (nodeMap) {
            NodeSession session = nodeMap.get(nodeId);

            if (session != null) {
                // [更新] 已存在节点
                session.refresh();

                // 端口变更检测 (忽略 INIT 状态的变更)
                if (portReported != null && !portReported.equals("INIT")
                        && !portReported.equals(session.assignedPort)
                        && !"INIT".equals(session.assignedPort)) {
                    ServerLogger.infoWithSource("Session", "nkm.session.portChanged", keyName, nodeId, portReported);
                    session.assignedPort = portReported;
                } else if ("INIT".equals(session.assignedPort) && portReported != null && !portReported.equals("INIT")) {
                    // 从初始状态变为正式端口，静默更新
                    session.assignedPort = portReported;
                }
                return true;
            } else {
                // [新建] 新节点接入

                // 再次检查僵尸节点，尝试腾出空间
                if (nodeMap.size() >= maxConnections) {
                    checkZombiesForKey(keyName, nodeMap);
                    // 清理后再次检查
                    if (nodeMap.size() >= maxConnections) {
                        ServerLogger.warnWithSource("Session", "nkm.session.rejected", keyName, nodeId, maxConnections);
                        return false;
                    }
                }

                // 注册新节点
                nodeMap.put(nodeId, new NodeSession(portReported));

                // 只有当端口不是 INIT 时才打印详细连接日志，或者是第一次连接
                String portDisplay = (portReported == null || portReported.equals("INIT")) ? "Negotiating" : portReported;
                ServerLogger.infoWithSource("Session", "nkm.session.connected", keyName, nodeId, portDisplay, nodeMap.size() + "/" + maxConnections);
                return true;
            }
        }
    }

    /**
     * 仅用于 Traffic Sync 时的保活刷新
     */
    public void refreshOnTraffic(String keyName, String nodeId) {
        ConcurrentHashMap<String, NodeSession> map = sessions.get(keyName);
        if (map != null && nodeId != null) {
            NodeSession session = map.get(nodeId);
            if (session != null) session.refresh();
        }
    }

    public void release(String keyName, String nodeId) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap != null) {
            synchronized (nodeMap) {
                if (nodeMap.remove(nodeId) != null) {
                    ServerLogger.infoWithSource("Session", "nkm.session.released", keyName, nodeId);
                }
                if (nodeMap.isEmpty()) {
                    sessions.remove(keyName);
                }
            }
        }
    }

    public void forceReleaseKey(String keyName) {
        if (sessions.remove(keyName) != null) {
            ServerLogger.infoWithSource("Session", "nkm.session.forceClear", keyName);
        }
    }

    public int getActiveCount(String keyName) {
        Map<String, NodeSession> nodeMap = sessions.get(keyName);
        return nodeMap == null ? 0 : nodeMap.size();
    }

    private void checkZombies() {
        for (String key : sessions.keySet()) {
            ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(key);
            if (nodeMap != null) {
                synchronized (nodeMap) {
                    checkZombiesForKey(key, nodeMap);
                    if (nodeMap.isEmpty()) {
                        sessions.remove(key);
                    }
                }
            }
        }
    }

    private void checkZombiesForKey(String keyName, ConcurrentHashMap<String, NodeSession> nodeMap) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, NodeSession>> it = nodeMap.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, NodeSession> entry = it.next();
            String nodeId = entry.getKey();
            NodeSession session = entry.getValue();

            // 超时判定：Protocol.ZOMBIE_TIMEOUT_MS (默认20秒)
            // 注意：这里引用了 Protocol 类，请确保 Protocol.java 已包含在项目中
            if (now - session.lastHeartbeatTime > Protocol.ZOMBIE_TIMEOUT_MS) {
                ServerLogger.warnWithSource("Session", "nkm.session.timeout", keyName, nodeId, (now - session.lastHeartbeatTime) / 1000 + "s");
                it.remove();
            }
        }
    }
// 请添加到 SessionManager.java 类中
    /**
     * 获取当前活跃会话的快照 (线程安全)
     * 返回结构: KeyName -> { NodeId -> Port }
     */
    public Map<String, Map<String, String>> getActiveSessionsSnapshot() {
        Map<String, Map<String, String>> snapshot = new HashMap<>();

        // 遍历 ConcurrentHashMap
        sessions.forEach((keyName, nodeMap) -> {
            Map<String, String> nodeDetails = new HashMap<>();
            nodeMap.forEach((nodeId, session) -> {
                // 如果端口是 INIT，显示为 Negotiating
                String port = (session.assignedPort == null || session.assignedPort.equals("INIT"))
                        ? "Negotiating..."
                        : session.assignedPort;
                nodeDetails.put(nodeId, port);
            });
            if (!nodeDetails.isEmpty()) {
                snapshot.put(keyName, nodeDetails);
            }
        });

        return snapshot;
    }

    private static class NodeSession {
        long lastHeartbeatTime;
        String assignedPort;

        NodeSession(String port) {
            this.lastHeartbeatTime = System.currentTimeMillis();
            this.assignedPort = port;
        }

        void refresh() {
            this.lastHeartbeatTime = System.currentTimeMillis();
        }
    }
}