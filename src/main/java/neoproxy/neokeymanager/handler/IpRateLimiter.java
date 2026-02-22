package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工业级 IP 限流器
 * 自动识别 Nginx/CDN 透传的真实 IP，并以天为单位限制请求次数
 */
public class IpRateLimiter {
    private static final int MAX_REQUESTS_PER_DAY = 10;
    private static final ConcurrentHashMap<String, RateRecord> records = new ConcurrentHashMap<>();

    public static String getClientIp(HttpExchange exchange) {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = exchange.getRequestHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
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
