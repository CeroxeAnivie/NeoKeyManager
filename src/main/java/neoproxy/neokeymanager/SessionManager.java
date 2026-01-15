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

    public boolean tryRegisterSession(String realKeyName, String displayKeyName, String nodeId, String port, int maxConnections, boolean isSingle) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(realKeyName, k -> new ConcurrentHashMap<>());

        synchronized (nodeMap) {
            NodeSession session = nodeMap.get(nodeId);

            if (session != null) {
                session.updateDisplayKey(displayKeyName, realKeyName);
                if (!canAcceptPort(realKeyName, nodeMap, session, port, maxConnections, isSingle, nodeId)) {
                    return false;
                }
                session.refreshPort(port);
                return true;
            }

            checkZombiesForKey(realKeyName, nodeMap);

            if (isSingle) {
                if (isDisplayKeyInUse(nodeMap, displayKeyName, nodeId)) {
                    ServerLogger.warnWithSource("Session", "nkm.session.blockSingle", displayKeyName, nodeId);
                    return false;
                }
            }

            int currentTotal = countTotalPorts(nodeMap);
            if (currentTotal >= maxConnections) {
                return false;
            }

            session = new NodeSession(displayKeyName);
            session.refreshPort(port);
            nodeMap.put(nodeId, session);

            logConnection(session.displayKey, nodeId, port, session);
            return true;
        }
    }

    public boolean keepAlive(String realKeyName, String incomingSerial, String nodeId, String port, int maxConnections, boolean isSingle) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null)
            return tryRegisterSession(realKeyName, incomingSerial, nodeId, port, maxConnections, isSingle);

        NodeSession session = nodeMap.get(nodeId);
        if (session != null) {
            session.updateDisplayKey(incomingSerial, realKeyName);
            if (session.containsPort(port)) {
                session.refreshPort(port);
                return true;
            }
            synchronized (nodeMap) {
                if (session.containsPort(port)) {
                    session.refreshPort(port);
                    return true;
                }
                if (!canAcceptPort(realKeyName, nodeMap, session, port, maxConnections, isSingle, nodeId)) {
                    return false;
                }
                session.refreshPort(port);
                logConnection(session.displayKey, nodeId, port, session);
                return true;
            }
        } else {
            return tryRegisterSession(realKeyName, incomingSerial, nodeId, port, maxConnections, isSingle);
        }
    }

    public boolean isSpecificPortActive(String realKeyName, String nodeId, String port) {
        if (port == null || "INIT".equals(port)) return false;
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null) return false;
        NodeSession session = nodeMap.get(nodeId);
        if (session == null) return false;
        if (session.containsPort(port)) {
            Long lastBeat = session.activePorts.get(port);
            return lastBeat != null && (System.currentTimeMillis() - lastBeat < Protocol.ZOMBIE_TIMEOUT_MS);
        }
        return false;
    }

    private boolean canAcceptPort(String keyName, Map<String, NodeSession> nodeMap, NodeSession session, String port, int maxConnections, boolean isSingle, String currentNodeId) {
        if (session.containsPort(port)) return true;
        boolean isInitTransition = !"INIT".equals(port) && session.containsPort("INIT");
        if (isInitTransition) return true;

        checkZombiesForKey(keyName, nodeMap);

        if (isSingle) {
            if (isDisplayKeyInUse(nodeMap, session.displayKey, currentNodeId)) return false;
            if (session.getPortCount() > 0) return false;
        }

        int currentTotal = countTotalPorts(nodeMap);
        return currentTotal < maxConnections;
    }

    private boolean isDisplayKeyInUse(Map<String, NodeSession> nodeMap, String targetDisplayKey, String currentNodeId) {
        if (targetDisplayKey == null) return false;
        for (Map.Entry<String, NodeSession> entry : nodeMap.entrySet()) {
            String nid = entry.getKey();
            NodeSession sess = entry.getValue();
            if (nid.equals(currentNodeId)) continue;
            if (targetDisplayKey.equals(sess.displayKey)) return true;
        }
        return false;
    }

    private void logConnection(String displayKey, String nodeId, String port, NodeSession session) {
        if (!"INIT".equals(port) && !session.hasLogged(port)) {
            ServerLogger.infoWithSource("Session", "nkm.session.connected", displayKey, nodeId, port);
            session.markLogged(port);
        }
    }

    public void releaseSession(String realKeyName, String nodeId) {
        if (realKeyName == null || nodeId == null) return;
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap != null) {
            synchronized (nodeMap) {
                NodeSession removed = nodeMap.remove(nodeId);
                if (removed != null) {
                    ServerLogger.infoWithSource("Session", "nkm.session.released", removed.displayKey, nodeId);
                }
                if (nodeMap.isEmpty()) sessions.remove(realKeyName);
            }
        }
    }

    public void forceReleaseKey(String realKeyName) {
        sessions.remove(realKeyName);
    }

    public int getActiveCount(String realKeyName) {
        Map<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null) return 0;
        synchronized (nodeMap) {
            return countTotalPorts(nodeMap);
        }
    }

    public Map<String, Map<String, String>> getActiveSessionsSnapshot() {
        Map<String, Map<String, String>> snapshot = new HashMap<>();
        sessions.forEach((realKey, nodeMap) -> {
            nodeMap.forEach((nodeId, session) -> {
                String dKey = session.displayKey;
                String portsStr = session.getFormattedPorts();
                if (!portsStr.isEmpty()) {
                    snapshot.computeIfAbsent(dKey, k -> new HashMap<>()).put(nodeId, portsStr);
                }
            });
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
                ServerLogger.infoWithSource("Session", "nkm.session.timeout", entry.getValue().displayKey, entry.getKey());
                it.remove();
            }
        }
    }

    private static class NodeSession {
        final ConcurrentHashMap<String, Long> activePorts = new ConcurrentHashMap<>();
        final java.util.Set<String> loggedPorts = ConcurrentHashMap.newKeySet();
        volatile String displayKey;

        NodeSession(String displayKey) {
            this.displayKey = displayKey;
        }

        void updateDisplayKey(String newDisplay, String realKey) {
            if (newDisplay == null || newDisplay.isBlank()) return;
            boolean isNewIsReal = newDisplay.equals(realKey);
            boolean isCurrentIsReal = this.displayKey.equals(realKey);
            if (!isNewIsReal) {
                this.displayKey = newDisplay;
            } else if (isCurrentIsReal) {
                this.displayKey = newDisplay;
            }
        }

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