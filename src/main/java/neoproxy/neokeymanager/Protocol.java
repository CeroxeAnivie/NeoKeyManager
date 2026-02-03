package neoproxy.neokeymanager;

import java.io.Serializable;
import java.util.Map;

public class Protocol {
    public static final String API_GET_KEY = "/api/key";
    public static final String API_HEARTBEAT = "/api/heartbeat";
    public static final String API_SYNC = "/api/sync";
    public static final String API_RELEASE = "/api/release";
    public static final String API_NODE_STATUS = "/api/node/status"; // NPS 新增接口

    // [新增] 获取客户端更新 URL 接口
    public static final String API_CLIENT_UPDATE_URL = "/api/node/client/update-url";

    public static final long ZOMBIE_TIMEOUT_MS = 10000L;
    public static final String STATUS_OK = "ok";
    public static final String STATUS_KILL = "kill";

    public static class HeartbeatPayload implements Serializable {
        public String serial;
        public String nodeId;
        public String port;
        // [新增] 接收 NPS 上报的连接详情 (T:x U:x)
        public String connectionDetail;
    }

    // [新增] 节点状态 Payload (预留给 /api/node/status)
    public static class NodeStatusPayload implements Serializable {
        public String nodeId;
        public String version;
        public long timestamp;
        public int activeTunnels;
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
     * 全量元数据，支持 NPS 动态热更新
     */
    public static class KeyMetadata implements Serializable {
        public boolean isValid;
        public String reason;
        public double balance;
        public double rate;
        public String expireTime;
        public boolean enableWebHTML;
    }

    // [新增] 更新 URL 响应结构
    public static class UpdateUrlResponse implements Serializable {
        public String url;
        public boolean valid;

        public UpdateUrlResponse() {
        }

        public UpdateUrlResponse(String url, boolean valid) {
            this.url = url;
            this.valid = valid;
        }
    }
}