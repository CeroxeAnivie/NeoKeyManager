package neoproxy.neokeymanager.admin;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端 API 专用的数据传输对象
 */
public class AdminDTOs {

    // 通用响应
    public record AdminResponse(
            boolean success,
            String message,
            Object data // 可以是 null, List<KeyDetail>, 或其他对象
    ) implements Serializable {
    }

    // 完整的 Key 详情 (用于 Query)
    public static class KeyDetail implements Serializable {
        public String name;
        public double balance;
        public double rate;
        public String port;
        public int maxConns;
        public String expireTime;
        public boolean enableWeb;
        public String status; // ENABLED, PAUSED, DISABLED
        public List<MapNode> maps; // 允许为 null (nomap模式)
    }

    // 映射节点详情
    public static class MapNode implements Serializable {
        public String nodeId;
        public String port;

        public MapNode(String nodeId, String port) {
            this.nodeId = nodeId;
            this.port = port;
        }
    }

    // Exec 请求参数 (模拟 CLI 参数)
    public static class ExecRequest implements Serializable {
        public List<String> args; // 对应 CLI 的参数列表，例如 ["user1", "b=20"]
    }
}