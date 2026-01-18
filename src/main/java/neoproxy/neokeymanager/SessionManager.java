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

    private String getSessionKey(String nodeId, String displayKey) {
        return nodeId + "|" + displayKey;
    }

    /**
     * 智能端口获取：在资源池范围内寻找一个当前全球未被占用的端口
     */
    public String findFirstFreePort(String realKeyName, String portRange) {
        if (portRange == null) return null;
        if (!portRange.contains("-")) return isPortGlobalActive(realKeyName, portRange) ? null : portRange;

        String[] parts = portRange.split("-");
        int start = Integer.parseInt(parts[0]);
        int end = Integer.parseInt(parts[1]);

        for (int p = start; p <= end; p++) {
            String portStr = String.valueOf(p);
            if (!isPortGlobalActive(realKeyName, portStr)) return portStr;
        }
        return null;
    }

    private boolean isPortGlobalActive(String realKeyName, String port) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null) return false;
        long now = System.currentTimeMillis();
        for (NodeSession s : nodeMap.values()) {
            Long lastBeat = s.activePorts.get(port);
            if (lastBeat != null && (now - lastBeat < Protocol.ZOMBIE_TIMEOUT_MS)) return true;
        }
        return false;
    }

    /**
     * 核心：注册会话
     */
    public boolean tryRegisterSession(String realKeyName, String displayKeyName, String nodeId, String port, int maxConnections, boolean isAliasSingle) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(realKeyName, k -> new ConcurrentHashMap<>());
        String sessionKey = getSessionKey(nodeId, displayKeyName);

        synchronized (nodeMap) {
            checkZombiesForKey(realKeyName, nodeMap);

            if (isAliasSingle && isAliasActiveGlobally(nodeMap, displayKeyName, sessionKey)) {
                ServerLogger.warnWithSource("Session", "nkm.session.blockSingle", displayKeyName, nodeId);
                return false;
            }

            if (Database.isNameSingle(realKeyName) && isPoolActiveGlobally(nodeMap, sessionKey)) {
                ServerLogger.warnWithSource("Session", "nkm.session.blockSingle", "Pool:" + realKeyName, nodeId);
                return false;
            }

            NodeSession session = nodeMap.get(sessionKey);
            if (session != null) {
                if (!canAcceptMorePorts(nodeMap, session, port, maxConnections, isAliasSingle)) return false;
                session.refreshPort(port);
                return true;
            }

            if (countTotalPorts(nodeMap) >= maxConnections) return false;

            session = new NodeSession(displayKeyName);
            session.refreshPort(port);
            nodeMap.put(sessionKey, session);
            logConnection(displayKeyName, nodeId, port, session);
            return true;
        }
    }

    /**
     * 核心：心跳维持 (修复了参数对不上的问题)
     */
    public boolean keepAlive(String realKeyName, String incomingSerial, String nodeId, String port, int maxConnections, boolean isAliasSingle) {
        String sessionKey = getSessionKey(nodeId, incomingSerial);
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null)
            return tryRegisterSession(realKeyName, incomingSerial, nodeId, port, maxConnections, isAliasSingle);

        NodeSession session = nodeMap.get(sessionKey);
        if (session != null) {
            if (session.containsPort(port)) {
                session.refreshPort(port);
                return true;
            }
            synchronized (nodeMap) {
                checkZombiesForKey(realKeyName, nodeMap);
                // 修复：这里之前传了 6 个参数，导致编译报错，现在改为正确的 5 个
                if (!canAcceptMorePorts(nodeMap, session, port, maxConnections, isAliasSingle)) return false;
                session.refreshPort(port);
                return true;
            }
        }
        return tryRegisterSession(realKeyName, incomingSerial, nodeId, port, maxConnections, isAliasSingle);
    }

    public boolean isSpecificPortActive(String realKeyName, String nodeId, String port) {
        return isPortGlobalActive(realKeyName, port);
    }

    private boolean isAliasActiveGlobally(Map<String, NodeSession> nodeMap, String alias, String currentSessionKey) {
        for (Map.Entry<String, NodeSession> entry : nodeMap.entrySet()) {
            if (entry.getKey().equals(currentSessionKey)) continue;
            if (alias.equals(entry.getValue().displayKey) && entry.getValue().getLivePortCount() > 0) return true;
        }
        return false;
    }

    private boolean isPoolActiveGlobally(Map<String, NodeSession> nodeMap, String currentSessionKey) {
        for (Map.Entry<String, NodeSession> entry : nodeMap.entrySet()) {
            if (entry.getKey().equals(currentSessionKey)) continue;
            if (entry.getValue().getLivePortCount() > 0) return true;
        }
        return false;
    }

    // 辅助方法：参数为 5 个
    private boolean canAcceptMorePorts(Map<String, NodeSession> nodeMap, NodeSession session, String port, int maxConnections, boolean isAliasS) {
        if (session.containsPort(port)) return true;
        if (!"INIT".equals(port) && session.containsPort("INIT")) return true;
        if (isAliasS && session.getLivePortCount() >= 1) return false;
        return countTotalPorts(nodeMap) < maxConnections;
    }

    public int getActiveCount(String realKeyName) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        return (nodeMap == null) ? 0 : countTotalPorts(nodeMap);
    }

    private int countTotalPorts(Map<String, NodeSession> nodeMap) {
        int sum = 0;
        for (NodeSession s : nodeMap.values()) sum += s.getLivePortCount();
        return sum;
    }

    public void releaseSession(String realKeyName, String nodeId) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap != null) {
            synchronized (nodeMap) {
                Iterator<Map.Entry<String, NodeSession>> it = nodeMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, NodeSession> entry = it.next();
                    if (entry.getKey().startsWith(nodeId + "|")) {
                        ServerLogger.infoWithSource("Session", "nkm.session.released", entry.getValue().displayKey, nodeId);
                        it.remove();
                    }
                }
                if (nodeMap.isEmpty()) sessions.remove(realKeyName);
            }
        }
    }

    public void forceReleaseKey(String realKeyName) {
        sessions.remove(realKeyName);
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
            String sKey = entry.getKey();
            NodeSession s = entry.getValue();
            s.activePorts.entrySet().removeIf(e -> (now - e.getValue()) > Protocol.ZOMBIE_TIMEOUT_MS);
            if (s.activePorts.isEmpty()) {
                String nodeId = sKey.contains("|") ? sKey.split("\\|")[0] : "unknown";
                ServerLogger.infoWithSource("Session", "nkm.session.timeout", s.displayKey, nodeId);
                it.remove();
            }
        }
    }

    public Map<String, Map<String, String>> getActiveSessionsSnapshot() {
        Map<String, Map<String, String>> snapshot = new HashMap<>();
        sessions.forEach((realKey, nodeMap) -> {
            nodeMap.forEach((sessionKey, session) -> {
                String nodeId = sessionKey.contains("|") ? sessionKey.split("\\|")[0] : sessionKey;
                snapshot.computeIfAbsent(session.displayKey, k -> new HashMap<>()).put(nodeId, session.getFormattedPorts());
            });
        });
        return snapshot;
    }

    private void logConnection(String displayKey, String nodeId, String port, NodeSession session) {
        if (!"INIT".equals(port) && !session.hasLogged(port)) {
            ServerLogger.infoWithSource("Session", "nkm.session.connected", displayKey, nodeId, port);
            session.markLogged(port);
        }
    }

    private static class NodeSession {
        final ConcurrentHashMap<String, Long> activePorts = new ConcurrentHashMap<>();
        final java.util.Set<String> loggedPorts = ConcurrentHashMap.newKeySet();
        final String displayKey;

        NodeSession(String displayKey) {
            this.displayKey = displayKey;
        }

        boolean containsPort(String p) {
            return activePorts.containsKey(p);
        }

        synchronized void refreshPort(String p) {
            activePorts.put(p, System.currentTimeMillis());
            if (!"INIT".equals(p)) activePorts.remove("INIT");
        }

        synchronized int getLivePortCount() {
            long now = System.currentTimeMillis();
            return (int) activePorts.values().stream().filter(t -> (now - t) < Protocol.ZOMBIE_TIMEOUT_MS).count();
        }

        boolean hasLogged(String p) {
            return loggedPorts.contains(p);
        }

        void markLogged(String p) {
            loggedPorts.add(p);
        }

        synchronized String getFormattedPorts() {
            return activePorts.keySet().stream().sorted().map(p -> p.equals("INIT") ? "..." : p).collect(Collectors.joining(" "));
        }
    }
}