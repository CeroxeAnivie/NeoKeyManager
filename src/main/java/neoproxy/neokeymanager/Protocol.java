package neoproxy.neokeymanager;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 通信协议定义类 (Final Version)
 * 包含了：API路径、时间常量、心跳包结构、状态查询结构
 */
public class Protocol {

    // ==================== API Paths ====================
    public static final String API_HEARTBEAT = "/api/heartbeat";
    public static final String API_SYNC = "/api/sync";
    public static final String API_GET_KEY = "/api/key";
    public static final String API_STATUS = "/api/status"; // 新增的状态查询接口

    // ==================== Time Constants ====================
    // NPS 发送心跳的间隔 (5秒)
    public static final long HEARTBEAT_INTERVAL_MS = 5000L;

    // [修复报错的关键] NKM 判定节点离线的超时时间 (20秒)
    public static final long ZOMBIE_TIMEOUT_MS = 20000L;

    // ==================== Payloads (Request) ====================

    /**
     * [POST] /api/heartbeat 请求体
     */
    public static class HeartbeatPayload implements Serializable {
        public String serial;           // 序列号 (Key)
        public String nodeId;           // 节点唯一标识
        public String port;             // 当前分配的端口
        public long timestamp;          // 客户端时间戳
        public int currentConnections;  // (可选) 当前连接数负载
    }

    /**
     * [POST] /api/status 请求体
     */
    public static class StatusCheckPayload implements Serializable {
        public List<String> keys;       // 想要查询状态的序列号列表
    }

    // ==================== Responses (Response) ====================

    /**
     * [POST] /api/heartbeat 响应体
     */
    public static class HeartbeatResponse implements Serializable {
        public String status;   // "ok" 或 "kill"
        public String message;  // 如果 status="kill" 的原因
    }

    /**
     * [POST] /api/status 响应中的详情对象
     */
    public static class KeyStatusDetail implements Serializable {
        public boolean isValid;      // 是否可用
        public String reason;        // 原因 (Expired/NoBalance/Disabled/NotFound/OK)
        public double balance;       // 余额
        public String expireTime;    // 过期时间字符串
    }

    /**
     * [POST] /api/status 响应体
     */
    public static class StatusCheckResponse implements Serializable {
        // Key -> 状态详情
        public Map<String, KeyStatusDetail> statuses;
    }
}