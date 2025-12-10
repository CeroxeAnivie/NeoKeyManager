package neoproxy.neokeymanager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 工业级 Session 管理器 (Multi-Port Support)
 * 支持单节点多端口并发占用
 */
public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();

    // KeyName -> (NodeId -> NodeSession)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, NodeSession>> sessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NKM-Session-Monitor");
        t.setDaemon(true);
        return t;
    });

    private SessionManager() {
        monitor.scheduleAtFixedRate(this::checkZombies, 3, 3, TimeUnit.SECONDS);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    public boolean tryAcquireOrRefresh(String keyName, String nodeId, int maxConnections) {
        return handleHeartbeat(keyName, nodeId, "INIT", maxConnections);
    }

    /**
     * 处理心跳 (核心逻辑修改：支持多端口)
     */
    public boolean handleHeartbeat(String keyName, String nodeId, String portReported, int maxConnections) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(keyName, k -> new ConcurrentHashMap<>());

        synchronized (nodeMap) {
            NodeSession session = nodeMap.computeIfAbsent(nodeId, k -> new NodeSession());

            // 1. 如果是新端口（该节点尚未记录此端口），检查总连接数限制
            if (!session.hasPort(portReported)) {
                // 计算当前该 Key 下所有节点的端口总和
                int currentTotalPorts = countTotalPorts(nodeMap);

                // 如果已满，且不是刷新已有端口，则拒绝
                if (currentTotalPorts >= maxConnections) {
                    // 尝试清理僵尸释放空间
                    checkZombiesForKey(keyName, nodeMap);
                    if (countTotalPorts(nodeMap) >= maxConnections) {
                        ServerLogger.warnWithSource("Session", "nkm.session.rejected", keyName, nodeId, maxConnections);
                        return false;
                    }
                }
            }

            // 2. 注册/刷新端口
            session.refreshPort(portReported);

            // 3. [优化] 如果上报的是真实端口，移除该节点的 INIT 占位符
            if (!"INIT".equals(portReported)) {
                session.removePort("INIT");
            }

            // 仅在非 INIT 且是新端口时打印日志
            if (!"INIT".equals(portReported) && !session.loggedPorts.contains(portReported)) {
                int total = countTotalPorts(nodeMap);
                ServerLogger.infoWithSource("Session", "nkm.session.connected", keyName, nodeId, portReported, total + "/" + maxConnections);
                session.loggedPorts.add(portReported);
            }

            return true;
        }
    }

    // 仅用于 Traffic Sync
    public void refreshOnTraffic(String keyName, String nodeId) {
        ConcurrentHashMap<String, NodeSession> map = sessions.get(keyName);
        if (map != null && nodeId != null) {
            NodeSession session = map.get(nodeId);
            if (session != null) session.refreshAll();
        }
    }

    public void release(String keyName, String nodeId) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap != null) {
            synchronized (nodeMap) {
                if (nodeMap.remove(nodeId) != null) {
                    ServerLogger.infoWithSource("Session", "nkm.session.released", keyName, nodeId);
                }
                if (nodeMap.isEmpty()) sessions.remove(keyName);
            }
        }
    }

    public void forceReleaseKey(String keyName) {
        if (sessions.remove(keyName) != null) {
            ServerLogger.infoWithSource("Session", "nkm.session.forceClear", keyName);
        }
    }

    /**
     * 获取当前真实的占用数 (端口总数)
     */
    public int getActiveCount(String keyName) {
        Map<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap == null) return 0;
        synchronized (nodeMap) {
            return countTotalPorts(nodeMap);
        }
    }

    private int countTotalPorts(Map<String, NodeSession> nodeMap) {
        int sum = 0;
        for (NodeSession s : nodeMap.values()) {
            sum += s.getPortCount();
        }
        return sum;
    }

    /**
     * 获取快照供 list 指令使用
     * Value 格式: "10086 10087"
     */
    public Map<String, Map<String, String>> getActiveSessionsSnapshot() {
        Map<String, Map<String, String>> snapshot = new HashMap<>();
        sessions.forEach((keyName, nodeMap) -> {
            Map<String, String> nodeDetails = new HashMap<>();
            nodeMap.forEach((nodeId, session) -> {
                String portsStr = session.getFormattedPorts();
                if (!portsStr.isEmpty()) {
                    nodeDetails.put(nodeId, portsStr);
                }
            });
            if (!nodeDetails.isEmpty()) {
                snapshot.put(keyName, nodeDetails);
            }
        });
        return snapshot;
    }

    private void checkZombies() {
        for (String key : sessions.keySet()) {
            ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(key);
            if (nodeMap != null) {
                synchronized (nodeMap) {
                    checkZombiesForKey(key, nodeMap);
                    if (nodeMap.isEmpty()) sessions.remove(key);
                }
            }
        }
    }

    private void checkZombiesForKey(String keyName, Map<String, NodeSession> nodeMap) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, NodeSession>> it = nodeMap.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, NodeSession> entry = it.next();
            NodeSession session = entry.getValue();

            // 移除超时的端口
            session.activePorts.entrySet().removeIf(e -> (now - e.getValue()) > Protocol.ZOMBIE_TIMEOUT_MS);

            // 如果节点没有任何端口了，移除节点
            if (session.activePorts.isEmpty()) {
                ServerLogger.warnWithSource("Session", "nkm.session.timeout", keyName, entry.getKey(), "Timeout");
                it.remove();
            }
        }
    }

    // ==================== Inner Class ====================
    private static class NodeSession {
        // Port -> LastHeartbeatTime
        final ConcurrentHashMap<String, Long> activePorts = new ConcurrentHashMap<>();
        // 仅用于去重日志
        final Set<String> loggedPorts = ConcurrentHashMap.newKeySet();

        void refreshPort(String port) {
            activePorts.put(port, System.currentTimeMillis());
        }

        void removePort(String port) {
            activePorts.remove(port);
        }

        void refreshAll() {
            long now = System.currentTimeMillis();
            for (String k : activePorts.keySet()) {
                activePorts.put(k, now);
            }
        }

        boolean hasPort(String port) {
            return activePorts.containsKey(port);
        }

        int getPortCount() {
            return activePorts.size();
        }

        String getFormattedPorts() {
            if (activePorts.isEmpty()) return "";
            // 排序并拼接
            return activePorts.keySet().stream()
                    .sorted()
                    .map(p -> p.equals("INIT") ? "Negotiating..." : p)
                    .collect(Collectors.joining(" ")); // 使用空格分隔
        }
    }
}