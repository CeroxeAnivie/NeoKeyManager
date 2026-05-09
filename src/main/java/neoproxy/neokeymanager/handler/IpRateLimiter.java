package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;
import neoproxy.neokeymanager.config.Config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工业级 IP 限流器
 * 自动识别 Nginx/CDN 透传的真实 IP，并以天为单位限制请求次数
 */
public class IpRateLimiter {
    private static final long WINDOW_MILLIS = 24L * 60L * 60L * 1000L;
    private static final ConcurrentHashMap<String, RateRecord> records = new ConcurrentHashMap<>();

    public static String getClientIp(HttpExchange exchange) {
        // TCP 物理层的真实 IP (无法伪造)
        String physicalIp = exchange.getRemoteAddress().getAddress().getHostAddress();

        // 只有当你确认请求是由你自己的 Nginx 或 CF 转发过来的（例如物理 IP 是 127.0.0.1 或是内网 IP），
        // 才去信任 Header 里的 IP。否则直接返回 physicalIp。
        if (isTrustedProxyAddress(physicalIp)) {
            String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp;
            }
        }
        return physicalIp;
    }

    private static boolean isTrustedProxyAddress(String ip) {
        if (ip == null) return false;
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    public static boolean allow(String ip) {
        if (ip == null || ip.isBlank()) return false;
        int maxRequestsPerDay = Config.NODELIST_RATE_LIMIT_PER_DAY;
        if (maxRequestsPerDay == 0) return true;
        long now = System.currentTimeMillis();

        RateRecord record = records.computeIfAbsent(ip, k -> new RateRecord());

        synchronized (record) {
            while (!record.requestTimes.isEmpty() && now - record.requestTimes.peekFirst() >= WINDOW_MILLIS) {
                record.requestTimes.removeFirst();
            }
            if (record.requestTimes.size() >= maxRequestsPerDay) {
                return false;
            }
            record.requestTimes.addLast(now);
            return true;
        }
    }

    static void resetForTesting() {
        records.clear();
    }

    private static class RateRecord {
        private final Deque<Long> requestTimes = new ArrayDeque<>();
    }
}
