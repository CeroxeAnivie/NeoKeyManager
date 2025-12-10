package neoproxy.neokeymanager;

import java.io.Serializable;
import java.util.Map;

/**
 * 通信协议定义类 (Final Fixed Version)
 */
public class Protocol {

    // ==================== API Paths ====================
    public static final String API_GET_KEY = "/api/key";
    public static final String API_HEARTBEAT = "/api/heartbeat";
    public static final String API_SYNC = "/api/sync";
    public static final String API_RELEASE = "/api/release";

    // ==================== Constants ====================
    public static final long ZOMBIE_TIMEOUT_MS = 20000L;

    // ==================== Status Codes ====================
    public static final String STATUS_OK = "ok";
    public static final String STATUS_KILL = "kill";

    // ==================== Payloads (Request) ====================

    // [POST] /api/heartbeat
    public static class HeartbeatPayload implements Serializable {
        public String serial;
        public String nodeId;
        public String port;
    }

    // [POST] /api/sync
    public static class SyncPayload implements Serializable {
        public String nodeId;
        public Map<String, Double> traffic;
    }

    // [POST] /api/release
    public static class ReleasePayload implements Serializable {
        public String serial;
        public String nodeId;
    }

    // ==================== Responses (Response) ====================

    // [POST] /api/sync 响应 (包含元数据)
    public static class SyncResponse implements Serializable {
        public String status;
        public Map<String, KeyMetadata> metadata;
    }

    // 业务元数据 (用于 Sync 返回)
    public static class KeyMetadata implements Serializable {
        public boolean isValid;
        public double balance;
        public String reason;
    }

    // 注意：/api/key 的响应直接使用 Map<String, Object>，不需要在此定义类
}