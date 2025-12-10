package neoproxy.neokeymanager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 工业级 Session 管理器 (Final Gold Version)
 * <p>
 * 逻辑闭环验证：
 * 1. 幽灵占位修复：INIT 与真实端口互斥，自动移除。
 * 2. 日志降噪：隐藏 INIT 过程日志。
 * 3. 严格扩容限制：无论通过 Register 还是 Heartbeat 扩容，均需检查 max_conns。
 * 4. 性能优化：仅在新端口接入时触锁检查，常规心跳无性能损耗。
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

    /**
     * [核心] 尝试注册 Session
     * 场景：新连接接入 / 掉线重连
     */
    public boolean tryRegisterSession(String keyName, String nodeId, String port, int maxConnections) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(keyName, k -> new ConcurrentHashMap<>());

        synchronized (nodeMap) {
            NodeSession session = nodeMap.get(nodeId);

            // 如果 Session 已存在，检查是否允许该端口接入
            if (session != null) {
                if (!canAcceptPort(keyName, nodeMap, session, port, maxConnections)) {
                    return false; // 拒绝扩容
                }
                session.refreshPort(port);
                return true;
            }

            // 如果是新节点
            checkZombiesForKey(keyName, nodeMap);
            int currentTotal = countTotalPorts(nodeMap);
            if (currentTotal >= maxConnections) {
                return false; // 总数已满
            }

            // 创建新 Session
            session = new NodeSession();
            session.refreshPort(port);
            nodeMap.put(nodeId, session);

            logConnection(keyName, nodeId, port, session);
            return true;
        }
    }

    /**
     * [核心] 心跳保活
     * 场景：常规保活 / 恶意客户端强行扩容
     */
    public boolean keepAlive(String keyName, String nodeId, String port, int maxConnections) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap == null) {
            return tryRegisterSession(keyName, nodeId, port, maxConnections);
        }

        NodeSession session = nodeMap.get(nodeId);
        if (session != null) {
            // [性能优化] 99.9% 的情况是旧端口保活，直接通过，无需加锁检查
            if (session.containsPort(port)) {
                session.refreshPort(port);
                return true;
            }

            // [逻辑闭环] 这是一个现有 Session 发来的“新端口”，必须检查容量！防止 Heartbeat 绕过限制
            synchronized (nodeMap) {
                // 双重检查：防止在等待锁的过程中端口已经被加上了
                if (session.containsPort(port)) {
                    session.refreshPort(port);
                    return true;
                }

                // 严格检查扩容权限
                if (!canAcceptPort(keyName, nodeMap, session, port, maxConnections)) {
                    // ServerLogger.warnWithSource("Session", "nkm.session.rejectedHeartbeatExpansion", keyName, nodeId, port);
                    return false; // 拒绝该端口的心跳，通知客户端断开
                }

                session.refreshPort(port);
                logConnection(keyName, nodeId, port, session);
                return true;
            }
        } else {
            return tryRegisterSession(keyName, nodeId, port, maxConnections);
        }
    }

    // ==================== 逻辑复用 ====================

    /**
     * 统一判断逻辑：当前 Session 是否可以接纳这个端口？
     * 包含了：INIT转正检查、僵尸清理、最大连接数检查
     */
    private boolean canAcceptPort(String keyName, Map<String, NodeSession> nodeMap, NodeSession session, String port, int maxConnections) {
        // 1. 如果端口已存在，直接允许
        if (session.containsPort(port)) return true;

        // 2. 如果是 INIT 转正 (INIT -> 真实端口)，视为状态切换，允许
        boolean isInitTransition = !"INIT".equals(port) && session.containsPort("INIT");
        if (isInitTransition) return true;

        // 3. 此时确认为“纯新增端口”，必须检查系统容量
        checkZombiesForKey(keyName, nodeMap); // 临死前再抢救一下位置
        int currentTotal = countTotalPorts(nodeMap);

        // 如果满了，拒绝
        return currentTotal < maxConnections;
    }

    private void logConnection(String keyName, String nodeId, String port, NodeSession session) {
        if (!"INIT".equals(port) && !session.hasLogged(port)) {
            ServerLogger.infoWithSource("Session", "nkm.session.connected", keyName, nodeId, port);
            session.markLogged(port);
        }
    }

    // ==================== 辅助方法 ====================

    public void releaseSession(String keyName, String nodeId) {
        if (keyName == null || nodeId == null) return;
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
        sessions.remove(keyName);
    }

    public int getActiveCount(String keyName) {
        Map<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap == null) return 0;
        synchronized (nodeMap) {
            return countTotalPorts(nodeMap);
        }
    }

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

    private int countTotalPorts(Map<String, NodeSession> nodeMap) {
        int sum = 0;
        for (NodeSession s : nodeMap.values()) {
            sum += s.getPortCount();
        }
        return sum;
    }

    private void checkZombies() {
        sessions.forEach((k, v) -> {
            synchronized (v) {
                checkZombiesForKey(k, v);
                if (v.isEmpty()) sessions.remove(k);
            }
        });
    }

    private void checkZombiesForKey(String keyName, Map<String, NodeSession> nodeMap) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, NodeSession>> it = nodeMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, NodeSession> entry = it.next();
            entry.getValue().activePorts.entrySet().removeIf(e -> (now - e.getValue()) > Protocol.ZOMBIE_TIMEOUT_MS);
            if (entry.getValue().activePorts.isEmpty()) {
                ServerLogger.infoWithSource("Session", "nkm.session.timeout", keyName, entry.getKey());
                it.remove();
            }
        }
    }

    // ==================== Inner Class ====================
    private static class NodeSession {
        final ConcurrentHashMap<String, Long> activePorts = new ConcurrentHashMap<>();
        final java.util.Set<String> loggedPorts = ConcurrentHashMap.newKeySet();

        boolean containsPort(String p) {
            return activePorts.containsKey(p);
        }

        synchronized void refreshPort(String p) {
            long now = System.currentTimeMillis();
            activePorts.put(p, now);
            if (!"INIT".equals(p)) {
                activePorts.remove("INIT");
            }
        }

        synchronized int getPortCount() {
            return activePorts.size();
        }

        boolean hasLogged(String p) {
            return loggedPorts.contains(p);
        }

        void markLogged(String p) {
            loggedPorts.add(p);
        }

        synchronized String getFormattedPorts() {
            if (activePorts.isEmpty()) return "";
            return activePorts.keySet().stream()
                    .sorted()
                    .map(p -> p.equals("INIT") ? "Negotiating..." : p)
                    .collect(Collectors.joining(" "));
        }
    }
}