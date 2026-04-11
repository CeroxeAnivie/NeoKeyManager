package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工业级 IP 限流器
 * 自动识别 Nginx/CDN 透传的真实 IP，并以天为单位限制请求次数
 */
public class IpRateLimiter {
    private static final int MAX_REQUESTS_PER_DAY = 10;
    private static final ConcurrentHashMap<String, RateRecord> records = new ConcurrentHashMap<>();

    /**
     * 检查 IP 是否是内网地址
     * 支持：10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8
     */
    private static boolean isPrivateIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 验证 IP 地址格式是否合法
     */
    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        // 简单验证：不能包含非法字符
        if (!ip.matches("^[0-9a-fA-F.:]+$")) return false;
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static String getClientIp(HttpExchange exchange) {
        // TCP 物理层的真实 IP (无法伪造)
        String physicalIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        // 只有当你确认请求是由你自己的 Nginx 或 CF 转发过来的（物理 IP 是内网地址），
        // 才去信任 Header 里的 IP。否则直接返回 physicalIp。
        if (isPrivateIp(physicalIp)) {
            String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // X-Forwarded-For 可能包含多个 IP，取第一个（最原始的客户端 IP）
                String clientIp = xff.split(",")[0].trim();
                // 验证 IP 格式合法性，防止 Header 注入攻击
                if (isValidIp(clientIp)) {
                    return clientIp;
                }
            }
            String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank() && isValidIp(realIp)) {
                return realIp;
            }
        }
        return physicalIp;
    }


    public static boolean allow(String ip) {
        if (ip == null || ip.isBlank()) return false;
        LocalDate today = LocalDate.now();

        RateRecord record = records.computeIfAbsent(ip, k -> new RateRecord(today, 0));

        synchronized (record) {
            if (!record.date.equals(today)) {
                record.date = today;
                record.count = 0;
            }
            if (record.count >= MAX_REQUESTS_PER_DAY) {
                return false;
            }
            record.count++;
            return true;
        }
    }

    private static class RateRecord {
        LocalDate date;
        int count;

        RateRecord(LocalDate date, int count) {
            this.date = date;
            this.count = count;
        }
    }
}
