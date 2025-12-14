package neoproxy.neokeymanager;

import java.io.Serializable;
import java.util.Map;

public class Protocol {
    public static final String API_GET_KEY = "/api/key";
    public static final String API_HEARTBEAT = "/api/heartbeat";
    public static final String API_SYNC = "/api/sync";
    public static final String API_RELEASE = "/api/release";
    public static final long ZOMBIE_TIMEOUT_MS = 20000L;
    public static final String STATUS_OK = "ok";
    public static final String STATUS_KILL = "kill";

    public static class HeartbeatPayload implements Serializable {
        public String serial;
        public String nodeId;
        public String port;
    }

    public static class SyncPayload implements Serializable {
        public String nodeId;
        public Map<String, Double> traffic;
    }

    public static class ReleasePayload implements Serializable {
        public String serial;
        public String nodeId;
    }

    public static class SyncResponse implements Serializable {
        public String status;
        public Map<String, KeyMetadata> metadata;
    }

    /**
     * 【变更】全量元数据，支持 NPS 动态热更新
     */
    public static class KeyMetadata implements Serializable {
        public boolean isValid;
        public String reason;

        // 新增同步字段
        public double balance;
        public double rate;
        public String expireTime;
        public boolean enableWebHTML;
    }
}