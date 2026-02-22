package neoproxy.neokeymanager.manager;

import neoproxy.neokeymanager.database.Database;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.util.*;
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
     * [最终修复] 智能端口获取 (节点隔离版)
     * 逻辑：端口占用检查仅限于 requestingNodeId 内部。
     * 不同节点的相同端口互不干扰。
     */
    public String findFirstFreePort(String realKeyName, String portRange, String requestingNodeId) {
        if (portRange == null) return null;

        // ==================== 1. 静态端口 (Static) ====================
        // 规则：在该节点上，抢占模式。允许自己重用，拒绝别人。
        if (!portRange.contains("-")) {
            // 检查该节点上，是否被【其他 Key】占用了
            if (isPortOccupiedByOthersOnNode(requestingNodeId, portRange, realKeyName)) {
                return null; // 被别人占了
            }
            return portRange; // 没人占，或者是我自己占的 -> 允许
        }

        // ==================== 2. 动态端口 (Dynamic) ====================
        // 规则：在该节点上，必须分配绝对空闲的端口。
        // 即使是我自己占用了 58000，为了防止端口冲突，也必须分配 58001。
        String[] parts = portRange.split("-");
        int start = Integer.parseInt(parts[0]);
        int end = Integer.parseInt(parts[1]);

        // 获取【该节点】上所有忙碌的端口 (包括我自己占用的)
        Set<String> nodeBusyPorts = getPortsBusyOnNode(requestingNodeId);

        for (int p = start; p <= end; p++) {
            String portStr = String.valueOf(p);
            // 只要在该节点上没被用，就可以分配
            if (!nodeBusyPorts.contains(portStr)) {
                return portStr;
            }
        }

        // 该节点所有端口都满了
        return null;
    }

    /**
     * 辅助：获取特定 NodeID 上所有活跃的端口 (无论属于哪个 Key)
     */
    private Set<String> getPortsBusyOnNode(String targetNodeId) {
        Set<String> busy = new HashSet<>();
        long now = System.currentTimeMillis();
        String targetLower = targetNodeId.toLowerCase();

        // 遍历所有 Key
        sessions.values().forEach(nodeMap -> {
            // 遍历该 Key 下的所有 Session
            nodeMap.forEach((sessionKey, session) -> {
                String ownerNodeId = sessionKey.split("\\|")[0];
                // 只关心目标节点的 Session
                if (ownerNodeId.equalsIgnoreCase(targetLower)) {
                    for (Map.Entry<String, Long> entry : session.activePorts.entrySet()) {
                        if (now - entry.getValue() < Protocol.ZOMBIE_TIMEOUT_MS) {
                            busy.add(entry.getKey());
                        }
                    }
                }
            });
        });
        return busy;
    }

    /**
     * 辅助：检查特定 NodeID 上，端口是否被【其他 Key】占用
     */
    private boolean isPortOccupiedByOthersOnNode(String targetNodeId, String port, String myRealKeyName) {
        long now = System.currentTimeMillis();
        String targetLower = targetNodeId.toLowerCase();

        for (Map.Entry<String, ConcurrentHashMap<String, NodeSession>> keyEntry : sessions.entrySet()) {
            String otherKeyName = keyEntry.getKey();

            // 如果是同一个 Key (自己)，跳过检查 -> 允许重连
            if (otherKeyName.equals(myRealKeyName)) continue;

            ConcurrentHashMap<String, NodeSession> nodeMap = keyEntry.getValue();
            for (Map.Entry<String, NodeSession> sEntry : nodeMap.entrySet()) {
                String sessionKey = sEntry.getKey();
                String ownerNodeId = sessionKey.split("\\|")[0];

                // 只有当是同一个节点时，才存在端口冲突
                if (ownerNodeId.equalsIgnoreCase(targetLower)) {
                    NodeSession s = sEntry.getValue();
                    Long lastBeat = s.activePorts.get(port);
                    // 发现被别人占用且活跃
                    if (lastBeat != null && (now - lastBeat < Protocol.ZOMBIE_TIMEOUT_MS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ... tryRegisterSession, keepAlive (保持鉴权和 connectionDetail 逻辑) ...

    public boolean tryRegisterSession(String realKeyName, String displayKeyName, String nodeId, String port, int maxConnections, boolean isAliasSingle) {
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId) == null) return false;
        return tryRegisterSessionInternal(realKeyName, displayKeyName, nodeId, port, maxConnections, isAliasSingle, null);
    }

    public boolean tryRegisterSession(String realKeyName, String displayKeyName, String nodeId, String port, int maxConnections, boolean isAliasSingle, String connectionDetail) {
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId) == null) return false;
        return tryRegisterSessionInternal(realKeyName, displayKeyName, nodeId, port, maxConnections, isAliasSingle, connectionDetail);
    }

    private boolean tryRegisterSessionInternal(String realKeyName, String displayKeyName, String nodeId, String port, int maxConnections, boolean isAliasSingle, String connectionDetail) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.computeIfAbsent(realKeyName, k -> new ConcurrentHashMap<>());
        String sessionKey = getSessionKey(nodeId, displayKeyName);

        synchronized (nodeMap) {
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
                session.refreshPort(port, connectionDetail);
                return true;
            }

            if (countTotalPorts(nodeMap) >= maxConnections) return false;

            session = new NodeSession(displayKeyName);
            session.refreshPort(port, connectionDetail);
            nodeMap.put(sessionKey, session);
            logConnection(displayKeyName, nodeId, port, session);
            return true;
        }
    }

    public boolean keepAlive(String realKeyName, String incomingSerial, String nodeId, String port, int maxConnections, boolean isAliasSingle) {
        return keepAlive(realKeyName, incomingSerial, nodeId, port, maxConnections, isAliasSingle, null);
    }

    public boolean keepAlive(String realKeyName, String incomingSerial, String nodeId, String port, int maxConnections, boolean isAliasSingle, String connectionDetail) {
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId) == null) return false;

        String sessionKey = getSessionKey(nodeId, incomingSerial);
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null)
            return tryRegisterSession(realKeyName, incomingSerial, nodeId, port, maxConnections, isAliasSingle, connectionDetail);

        NodeSession session = nodeMap.get(sessionKey);
        if (session != null) {
            if (session.containsPort(port)) {
                session.refreshPort(port, connectionDetail);
                return true;
            }
            synchronized (nodeMap) {
                if (!canAcceptMorePorts(nodeMap, session, port, maxConnections, isAliasSingle)) return false;
                session.refreshPort(port, connectionDetail);
                return true;
            }
        }
        return tryRegisterSession(realKeyName, incomingSerial, nodeId, port, maxConnections, isAliasSingle, connectionDetail);
    }

    public boolean isSpecificPortActive(String realKeyName, String nodeId, String port) {
        // 这里的查询逻辑也应改为 Node-Specific，但目前似乎未被深度使用，暂且保持安全
        // 为了严谨，建议改为：
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap == null) return false;
        NodeSession s = nodeMap.get(getSessionKey(nodeId, realKeyName)); // 假设 displayKey=realKeyName, 简化
        // 如果要做精确查询，需要遍历 nodeMap 找匹配 nodeId 的 session
        long now = System.currentTimeMillis();
        String targetLower = nodeId.toLowerCase();
        for (Map.Entry<String, NodeSession> entry : nodeMap.entrySet()) {
            if (entry.getKey().startsWith(targetLower + "|")) {
                Long t = entry.getValue().activePorts.get(port);
                if (t != null && now - t < Protocol.ZOMBIE_TIMEOUT_MS) return true;
            }
        }
        return false;
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

    public void releaseSession(String realKeyName, String nodeId, String displayKey) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap != null) {
            String targetSessionKey = getSessionKey(nodeId, displayKey);
            synchronized (nodeMap) {
                NodeSession removed = nodeMap.remove(targetSessionKey);
                if (removed != null) {
                    ServerLogger.infoWithSource("Session", "nkm.session.released", displayKey, nodeId);
                }
                if (nodeMap.isEmpty()) sessions.remove(realKeyName);
            }
        }
    }

    public void releaseSession(String realKeyName, String nodeId) {
        ConcurrentHashMap<String, NodeSession> nodeMap = sessions.get(realKeyName);
        if (nodeMap != null) {
            synchronized (nodeMap) {
                nodeMap.entrySet().removeIf(e -> e.getKey().startsWith(nodeId + "|"));
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
        final ConcurrentHashMap<String, String> portDetails = new ConcurrentHashMap<>();
        final java.util.Set<String> loggedPorts = ConcurrentHashMap.newKeySet();
        final String displayKey;

        NodeSession(String displayKey) {
            this.displayKey = displayKey;
        }

        boolean containsPort(String p) {
            return activePorts.containsKey(p);
        }

        synchronized void refreshPort(String p, String detail) {
            activePorts.put(p, System.currentTimeMillis());
            if (!"INIT".equals(p)) activePorts.remove("INIT");

            if (detail != null && !detail.isEmpty() && !detail.equals("None")) {
                portDetails.put(p, detail);
            } else {
                portDetails.remove(p);
            }
        }

        synchronized void refreshPort(String p) {
            refreshPort(p, null);
        }

        synchronized int getLivePortCount() {
            long now = System.currentTimeMillis();
            int count = 0;
            for (Long timestamp : activePorts.values()) {
                if (now - timestamp < Protocol.ZOMBIE_TIMEOUT_MS) count++;
            }
            return count;
        }

        boolean hasLogged(String p) {
            return loggedPorts.contains(p);
        }

        void markLogged(String p) {
            loggedPorts.add(p);
        }

        synchronized String getFormattedPorts() {
            return activePorts.keySet().stream().sorted().map(p -> {
                if (p.equals("INIT")) return "(Handshaking)";
                String detail = portDetails.get(p);
                if (detail != null) return p + detail;
                return p;
            }).collect(Collectors.joining("  "));
        }
    }
}