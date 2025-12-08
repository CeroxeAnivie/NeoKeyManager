package neoproxy.neokeymanager;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L; // 10分钟
    // KeyName -> (NodeId -> Timestamp)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Session-Cleaner");
        t.setDaemon(true);
        return t;
    });

    private SessionManager() {
        cleaner.scheduleAtFixedRate(this::cleanupDeadSessions, 1, 1, TimeUnit.MINUTES);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean tryAcquireOrRefresh(String keyName, String nodeId, int maxConnections) {
        ConcurrentHashMap<String, Long> nodeMap = sessions.computeIfAbsent(keyName, k -> new ConcurrentHashMap<>());

        // 1. 已存在 -> 刷新
        if (nodeMap.containsKey(nodeId)) {
            nodeMap.put(nodeId, System.currentTimeMillis());
            return true;
        }

        // 2. 新连接 -> 检查容量
        if (nodeMap.size() >= maxConnections) {
            ServerLogger.warnWithSource("Session", "nkm.session.reject", nodeId, keyName, maxConnections);
            return false;
        }

        // 3. 注册
        nodeMap.put(nodeId, System.currentTimeMillis());
        ServerLogger.infoWithSource("Session", "nkm.session.acquire", keyName, nodeId, nodeMap.size(), maxConnections);
        return true;
    }

    public void release(String keyName, String nodeId) {
        Map<String, Long> nodeMap = sessions.get(keyName);
        if (nodeMap != null) {
            if (nodeMap.remove(nodeId) != null) {
                ServerLogger.infoWithSource("Session", "nkm.session.release", keyName, nodeId);
            }
            if (nodeMap.isEmpty()) {
                sessions.remove(keyName);
            }
        }
    }

    public void forceReleaseKey(String keyName) {
        if (sessions.remove(keyName) != null) {
            ServerLogger.infoWithSource("Session", "nkm.session.forceClear", keyName);
        }
    }

    public int getActiveCount(String keyName) {
        Map<String, Long> nodeMap = sessions.get(keyName);
        return nodeMap == null ? 0 : nodeMap.size();
    }

    private void cleanupDeadSessions() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        Iterator<Map.Entry<String, ConcurrentHashMap<String, Long>>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConcurrentHashMap<String, Long>> entry = it.next();
            String key = entry.getKey();
            ConcurrentHashMap<String, Long> nodes = entry.getValue();
            Iterator<Map.Entry<String, Long>> nodeIt = nodes.entrySet().iterator();
            while (nodeIt.hasNext()) {
                Map.Entry<String, Long> e = nodeIt.next();
                if (now - e.getValue() > SESSION_TTL_MS) {
                    ServerLogger.warnWithSource("Session", "nkm.session.ttlExpired", key, e.getKey());
                    nodeIt.remove();
                    cleaned++;
                }
            }
            if (nodes.isEmpty()) it.remove();
        }
        if (cleaned > 0) ServerLogger.infoWithSource("Session", "nkm.session.cleaned", cleaned);
    }
}