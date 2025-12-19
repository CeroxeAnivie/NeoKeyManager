package neoproxy.neokeymanager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    // [核心] 尝试注册 Session (INIT阶段)
    public boolean tryRegisterSession(String keyName, String nodeId, String port, int maxConnections) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(keyName, k -> new ConcurrentHashMap<>());

        synchronized (nodeMap) {
            NodeSession session = nodeMap.get(nodeId);

            if (session != null) {
                if (!canAcceptPort(keyName, nodeMap, session, port, maxConnections)) {
                    return false;
                }
                session.refreshPort(port);
                return true;
            }

            // 新节点接入
            checkZombiesForKey(keyName, nodeMap);
            int currentTotal = countTotalPorts(nodeMap);
            if (currentTotal >= maxConnections) {
                return false; // 全局连接数已满
            }

            session = new NodeSession();
            session.refreshPort(port);
            nodeMap.put(nodeId, session);

            logConnection(keyName, nodeId, port, session);
            return true;
        }
    }

    // [核心] 心跳保活与扩容检查
    public boolean keepAlive(String keyName, String nodeId, String port, int maxConnections) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap == null) {
            return tryRegisterSession(keyName, nodeId, port, maxConnections);
        }

        NodeSession session = nodeMap.get(nodeId);
        if (session != null) {
            if (session.containsPort(port)) {
                session.refreshPort(port);
                return true;
            }

            synchronized (nodeMap) {
                if (session.containsPort(port)) {
                    session.refreshPort(port);
                    return true;
                }

                if (!canAcceptPort(keyName, nodeMap, session, port, maxConnections)) {
                    return false;
                }

                session.refreshPort(port);
                logConnection(keyName, nodeId, port, session);
                return true;
            }
        } else {
            return tryRegisterSession(keyName, nodeId, port, maxConnections);
        }
    }

    /**
     * [新增] 检查特定 Node 上的特定 Port 是否已经被占用
     * 用于 KeyHandler 在握手阶段检测静态端口冲突
     */
    public boolean isSpecificPortActive(String keyName, String nodeId, String port) {
        if (port == null || "INIT".equals(port)) return false;

        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(keyName);
        if (nodeMap == null) return false;

        NodeSession session = nodeMap.get(nodeId);
        if (session == null) return false;

        // 检查端口是否活跃（且不是过期僵尸）
        if (session.containsPort(port)) {
            Long lastBeat = session.activePorts.get(port);
            // 这里做个二次确认，确保它没超时 (20秒)
            return lastBeat != null && (System.currentTimeMillis() - lastBeat < Protocol.ZOMBIE_TIMEOUT_MS);
        }
        return false;
    }

    private boolean canAcceptPort(String keyName, Map<String, NodeSession> nodeMap, NodeSession session, String port, int maxConnections) {
        // 1. 端口已存在 -> 允许
        if (session.containsPort(port)) return true;

        // 2. INIT 转正 -> 允许
        boolean isInitTransition = !"INIT".equals(port) && session.containsPort("INIT");
        if (isInitTransition) return true;

        // 3. 纯新增端口 -> 检查容量
        checkZombiesForKey(keyName, nodeMap);
        int currentTotal = countTotalPorts(nodeMap);

        return currentTotal < maxConnections;
    }

    private void logConnection(String keyName, String nodeId, String port, NodeSession session) {
        if (!"INIT".equals(port) && !session.hasLogged(port)) {
            ServerLogger.infoWithSource("Session", "nkm.session.connected", keyName, nodeId, port);
            session.markLogged(port);
        }
    }

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
            // INIT 占用名额吗？
            // 通常 INIT 占 1 个连接数配额，直到它变成真实端口。
            // 这样防止客户端疯狂发起连接占满 Session 表。
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