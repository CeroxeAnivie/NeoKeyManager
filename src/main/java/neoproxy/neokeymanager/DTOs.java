package neoproxy.neokeymanager;

import java.io.Serializable;

/**
 * 数据传输对象与状态枚举
 * 职责：定义标准化的数据结构，解耦业务逻辑与底层实现
 */
public class DTOs {

    public enum KeyStatus {
        ENABLED,    // 正常
        PAUSED,     // 暂时停止（因欠费、过期等）- 需写入数据库
        DISABLED    // 管理员手动禁用 - 需写入数据库
    }

    /**
     * API 标准错误响应 (HTTP 40x)
     */
    public record ApiError(
            String error,   // 错误类型
            String reason,  // 具体原因 (显示给 NPS 日志)
            KeyStatus status // 当前状态
    ) implements Serializable {
    }

    /**
     * Key 详细信息响应 (HTTP 200)
     */
    public record KeyInfoResponse(
            String name,
            double balance,
            double rate,
            String expireTime,
            boolean isEnable,       // 兼容旧版逻辑: status == ENABLED
            boolean enableWebHTML,
            String port,
            int max_conns
    ) implements Serializable {
    }

    /**
     * 内部状态检查结果
     */
    public record KeyStateResult(
            KeyStatus status,
            String reason
    ) {
    }
}