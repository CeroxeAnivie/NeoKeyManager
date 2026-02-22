package neoproxy.neokeymanager.model;

import java.io.Serializable;
import java.util.Map;

public class Protocol {

    public static final String API_GET_KEY = "/api/key";
    public static final String API_HEARTBEAT = "/api/heartbeat";
    public static final String API_SYNC = "/api/sync";
    public static final String API_RELEASE = "/api/release";
    public static final String API_NODE_STATUS = "/api/node/status";
    public static final String API_CLIENT_UPDATE_URL = "/api/node/client/update-url";

    // [新增] 客户端获取节点列表的接口
    public static final String API_CLIENT_NODELIST = "/client/nodelist";

    public static final long ZOMBIE_TIMEOUT_MS = 10000L;
    public static final String STATUS_OK = "ok";
    public static final String STATUS_KILL = "kill";

    public static class HeartbeatPayload implements Serializable {
        public String serial;
        public String nodeId;
        public String port;
        public String connectionDetail;
    }

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

    public static class KeyMetadata implements Serializable {
        public boolean isValid;
        public String reason;
        public double balance;
        public double rate;
        public String expireTime;
        public boolean enableWebHTML;
    }

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

    // [新增] 供外部客户端请求的数据结构
    public static class PublicNodeInfo implements Serializable {
        public String name;
        public String address;
        public String icon;
        public int HOST_HOOK_PORT;
        public int HOST_CONNECT_PORT;
    }
}
