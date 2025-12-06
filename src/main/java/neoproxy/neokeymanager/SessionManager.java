package neoproxy.neokeymanager;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();
    // KeyName -> (NodeId -> Timestamp)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Session-Cleaner");
        t.setDaemon(true);
        return t;
    });
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L; // 10分钟

    private SessionManager() {
        cleaner.scheduleAtFixedRate(this::cleanupDeadSessions, 1, 1, TimeUnit.MINUTES);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    // 【重要】方法名确认：tryAcquireOrRefresh
    public synchronized boolean tryAcquireOrRefresh(String keyName, String nodeId, int maxConnections) {
        ConcurrentHashMap<String, Long> nodeMap = sessions.computeIfAbsent(keyName, k -> new ConcurrentHashMap<>());

        // 1. 已存在 -> 刷新
        if (nodeMap.containsKey(nodeId)) {
            nodeMap.put(nodeId, System.currentTimeMillis());
            return true;
        }

        // 2. 新连接 -> 检查容量
        if (nodeMap.size() >= maxConnections) {
            Main.myConsole.warn("Session", "REJECT " + nodeId + " -> " + keyName + " (Limit: " + maxConnections + ")");
            return false;
        }

        // 3. 注册
        nodeMap.put(nodeId, System.currentTimeMillis());
        Main.myConsole.log("Session", "ACQUIRE " + keyName + " by " + nodeId + " (" + nodeMap.size() + "/" + maxConnections + ")");
        return true;
    }

    public void release(String keyName, String nodeId) {
        Map<String, Long> nodeMap = sessions.get(keyName);
        if (nodeMap != null) {
            if (nodeMap.remove(nodeId) != null) {
                Main.myConsole.log("Session", "RELEASE " + keyName + " by " + nodeId);
            }
            if (nodeMap.isEmpty()) {
                sessions.remove(keyName);
            }
        }
    }

    public void forceReleaseKey(String keyName) {
        if (sessions.remove(keyName) != null) {
            Main.myConsole.log("Session", "FORCE CLEARED sessions for " + keyName);
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
                    Main.myConsole.warn("Session", "TTL EXPIRED: " + key + " @ " + e.getKey());
                    nodeIt.remove();
                    cleaned++;
                }
            }
            if (nodes.isEmpty()) it.remove();
        }
        if (cleaned > 0) Main.myConsole.log("Session", "Cleaned " + cleaned + " zombies.");
    }
}