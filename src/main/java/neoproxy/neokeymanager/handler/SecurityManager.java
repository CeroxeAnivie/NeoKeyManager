package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.utils.ServerLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 安全管理器
 * 【安全增强】提供请求签名验证和账户锁定机制
 */
public class SecurityManager {

    private static final long SIGNATURE_VALIDITY_SECONDS = 300; // 签名有效期 5 分钟
    private static final int MAX_AUTH_FAILURES = 5; // 最大失败次数
    private static final long LOCK_DURATION_MINUTES = 15; // 锁定时间 15 分钟

    // 已使用的签名集合（防止重放攻击）
    private final Set<String> usedSignatures = ConcurrentHashMap.newKeySet();

    // 认证失败记录：IP -> 失败信息
    private final ConcurrentHashMap<String, AuthFailureRecord> authFailures = new ConcurrentHashMap<>();

    // 定时清理任务
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NKM-Security-Cleanup");
        t.setDaemon(true);
        return t;
    });

    private static final SecurityManager INSTANCE = new SecurityManager();

    public static SecurityManager getInstance() {
        return INSTANCE;
    }

    private SecurityManager() {
        // 每 10 分钟清理一次过期的签名记录
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSignatures, 10, 10, TimeUnit.MINUTES);
    }

    /**
     * 验证请求签名
     * @param exchange HTTP 交换对象
     * @param body 请求体内容
     * @return 验证结果
     */
    public SignatureValidationResult validateSignature(HttpExchange exchange, String body) {
        String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
        String signature = exchange.getRequestHeaders().getFirst("X-Signature");
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // 1. 检查必要参数
        if (timestamp == null || signature == null) {
            return SignatureValidationResult.fail("Missing signature headers");
        }

        // 2. 检查时间戳是否在有效期内
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return SignatureValidationResult.fail("Invalid timestamp format");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - requestTime) > SIGNATURE_VALIDITY_SECONDS) {
            return SignatureValidationResult.fail("Request expired or timestamp invalid");
        }

        // 3. 检查签名是否已被使用（防止重放攻击）
        if (usedSignatures.contains(signature)) {
            return SignatureValidationResult.fail("Signature has been used");
        }

        // 4. 验证签名
        String expectedSignature = generateSignature(method, path, timestamp, body);
        if (!MessageDigest.isEqual(
            signature.getBytes(StandardCharsets.UTF_8),
            expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            return SignatureValidationResult.fail("Invalid signature");
        }

        // 5. 记录已使用的签名
        usedSignatures.add(signature);

        return SignatureValidationResult.success();
    }

    /**
     * 生成请求签名
     * @param method HTTP 方法
     * @param path 请求路径
     * @param timestamp 时间戳
     * @param body 请求体
     * @return 签名
     */
    public String generateSignature(String method, String path, String timestamp, String body) {
        try {
            String data = method + "|" + path + "|" + timestamp + "|" + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            // 优先使用 API_SECRET，如果没有配置则使用 AUTH_TOKEN
            String secretKey = Config.API_SECRET != null && !Config.API_SECRET.isEmpty() 
                ? Config.API_SECRET 
                : Config.AUTH_TOKEN;
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            ServerLogger.error("SecurityManager", "Failed to generate signature", e);
            return "";
        }
    }

    /**
     * 重置安全状态（仅用于测试）
     */
    public void resetForTesting() {
        authFailures.clear();
        usedSignatures.clear();
    }

    /**
     * 检查 IP 是否被锁定
     * @param ip 客户端 IP
     * @return 是否被锁定
     */
    public boolean isIpLocked(String ip) {
        if (ip == null) return false;
        AuthFailureRecord record = authFailures.get(ip);
        if (record == null) return false;

        // 检查是否超过锁定时间
        if (record.isLocked()) {
            long lockEndTime = record.lastFailureTime + TimeUnit.MINUTES.toMillis(LOCK_DURATION_MINUTES);
            if (System.currentTimeMillis() > lockEndTime) {
                // 锁定已过期，清除记录
                authFailures.remove(ip);
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 记录认证失败
     * @param ip 客户端 IP
     */
    public void recordAuthFailure(String ip) {
        if (ip == null) return;
        authFailures.compute(ip, (key, record) -> {
            if (record == null) {
                return new AuthFailureRecord(1, System.currentTimeMillis());
            }
            record.failureCount++;
            record.lastFailureTime = System.currentTimeMillis();
            if (record.failureCount >= MAX_AUTH_FAILURES) {
                ServerLogger.warnWithSource("SecurityManager", "IP locked due to too many failures", ip);
            }
            return record;
        });
    }

    /**
     * 记录认证成功（清除失败记录）
     * @param ip 客户端 IP
     */
    public void recordAuthSuccess(String ip) {
        if (ip == null) return;
        authFailures.remove(ip);
    }

    /**
     * 获取剩余锁定时间（秒）
     * @param ip 客户端 IP
     * @return 剩余锁定时间，如果未锁定返回 0
     */
    public long getRemainingLockTime(String ip) {
        AuthFailureRecord record = authFailures.get(ip);
        if (record == null || !record.isLocked()) return 0;

        long lockEndTime = record.lastFailureTime + TimeUnit.MINUTES.toMillis(LOCK_DURATION_MINUTES);
        long remaining = lockEndTime - System.currentTimeMillis();
        return Math.max(0, remaining / 1000);
    }

    /**
     * 清理过期的签名记录
     */
    private void cleanupExpiredSignatures() {
        // 简单实现：定期清空（实际生产环境应该按时间清理）
        usedSignatures.clear();
    }

    /**
     * 认证失败记录
     */
    private static class AuthFailureRecord {
        int failureCount;
        long lastFailureTime;

        AuthFailureRecord(int failureCount, long lastFailureTime) {
            this.failureCount = failureCount;
            this.lastFailureTime = lastFailureTime;
        }

        boolean isLocked() {
            return failureCount >= MAX_AUTH_FAILURES;
        }
    }

    /**
     * 签名验证结果
     */
    public static class SignatureValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private SignatureValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static SignatureValidationResult success() {
            return new SignatureValidationResult(true, null);
        }

        public static SignatureValidationResult fail(String message) {
            return new SignatureValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
