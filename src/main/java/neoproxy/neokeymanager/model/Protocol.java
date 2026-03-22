package neoproxy.neokeymanager.model;

import java.io.Serializable;
import java.util.Map;

/**
 * 协议常量定义类
 * 定义与 NeoProxyServer (NPS) 通信的所有协议常量
 */
public class Protocol {

    // API 端点
    public static final String API_GET_KEY = "/api/key";
    public static final String API_HEARTBEAT = "/api/heartbeat";
    public static final String API_SYNC = "/api/sync";
    public static final String API_RELEASE = "/api/release";
    public static final String API_NODE_STATUS = "/api/node/status";
    public static final String API_CLIENT_UPDATE_URL = "/api/node/client/update-url";

    // [新增] 客户端获取节点列表的接口
    public static final String API_CLIENT_NODELIST = "/client/nodelist";

    // 心跳超时相关常量
    // NPS 每 30 秒发送一次心跳，NKM 60 秒判定为超时（两次未收到）
    public static final long HEARTBEAT_INTERVAL_MS = 30000L;  // 心跳发送间隔：30秒
    public static final long NODE_TIMEOUT_MS = 60000L;        // 节点超时时间：60秒（两次心跳周期）
    public static final long ZOMBIE_TIMEOUT_MS = 10000L;      // 僵尸会话超时：10秒

    public static final String STATUS_OK = "ok";
    public static final String STATUS_KILL = "kill";

    /**
     * 心跳请求负载
     * 与 NPS 的 HeartbeatPayload 保持字段一致
     */
    public static class HeartbeatPayload implements Serializable {
        public String serial;           // 密钥序列号
        public String nodeId;           // 节点ID
        public String port;             // 端口
        public long timestamp;          // 时间戳（NPS 特有）
        public int currentConnections;  // 当前连接数（NPS 特有）
        public String connectionDetail; // 连接详情
    }

    /**
     * 节点状态上报负载
     * 与 NPS 的 NodeStatusPayload 保持一致
     */
    public static class NodeStatusPayload implements Serializable {
        public String nodeId;           // 节点ID
        public String version;          // 版本号
        public long timestamp;          // 时间戳
        public int activeTunnels;       // 活跃隧道数
    }

    /**
     * 流量同步请求负载
     */
    public static class SyncPayload implements Serializable {
        public String nodeId;                    // 节点ID
        public Map<String, Double> traffic;      // 流量数据
    }

    /**
     * 密钥释放请求负载
     */
    public static class ReleasePayload implements Serializable {
        public String serial;           // 密钥序列号
        public String nodeId;           // 节点ID
    }

    /**
     * 流量同步响应
     */
    public static class SyncResponse implements Serializable {
        public String status;                          // 状态
        public Map<String, KeyMetadata> metadata;      // 密钥元数据
    }

    /**
     * 密钥元数据
     */
    public static class KeyMetadata implements Serializable {
        public boolean isValid;         // 是否有效
        public String reason;           // 原因
        public double balance;          // 余额
        public double rate;             // 速率
        public String expireTime;       // 过期时间
        public boolean enableWebHTML;   // 是否启用Web
    }

    /**
     * 更新URL响应
     */
    public static class UpdateUrlResponse implements Serializable {
        public String url;              // URL
        public boolean valid;           // 是否有效

        public UpdateUrlResponse() {
        }

        public UpdateUrlResponse(String url, boolean valid) {
            this.url = url;
            this.valid = valid;
        }
    }

    /**
     * 供外部客户端请求的公开节点信息
     */
    public static class PublicNodeInfo implements Serializable {
        public String name;             // 节点名称
        public String address;          // 地址
        public String icon;             // 图标
        public int HOST_HOOK_PORT;      // Hook端口
        public int HOST_CONNECT_PORT;   // 连接端口
    }
}
