package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.utils.ServerLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Centralized guard for administrator APIs.
 *
 * The admin token is intentionally reused as the HMAC key because README and
 * deployment configuration expose only one administrator secret. Keeping the
 * implementation aligned with that public contract avoids a hidden second
 * credential that operators cannot rotate safely.
 */
public final class SecurityManager {

    private static final long SIGNATURE_VALIDITY_SECONDS = 300L;
    private static final int MAX_AUTH_FAILURES = 5;
    private static final long LOCK_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private static final SecurityManager INSTANCE = new SecurityManager();

    private final ConcurrentHashMap<String, Long> usedSignatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AuthFailureRecord> authFailures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NKM-Security-Cleanup");
        t.setDaemon(true);
        return t;
    });

    private SecurityManager() {
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSignatures, 1, 1, TimeUnit.MINUTES);
    }

    public static SecurityManager getInstance() {
        return INSTANCE;
    }

    public SignatureValidationResult validateSignature(HttpExchange exchange, String body) {
        return validateSignature(exchange, body, Config.ADMIN_TOKEN);
    }

    public SignatureValidationResult validateSignature(HttpExchange exchange, String body, String secret) {
        String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
        String signature = exchange.getRequestHeaders().getFirst("X-Signature");
        String nonce = exchange.getRequestHeaders().getFirst("X-Nonce");

        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return SignatureValidationResult.fail("Missing signature headers");
        }
        if (secret == null || secret.isBlank()) {
            return SignatureValidationResult.fail("Signature secret is not configured");
        }

        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return SignatureValidationResult.fail("Invalid timestamp format");
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - requestTime) > SIGNATURE_VALIDITY_SECONDS) {
            return SignatureValidationResult.fail("Request expired or timestamp invalid");
        }

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String expectedSignature = generateSignature(secret, method, path, timestamp.trim(), nonce, body);
        if (!constantTimeEquals(signature.trim(), expectedSignature)) {
            return SignatureValidationResult.fail("Invalid signature");
        }

        String replayKey = method + "|" + path + "|" + timestamp.trim() + "|" + (nonce == null ? "" : nonce.trim()) + "|" + signature.trim();
        if (usedSignatures.putIfAbsent(replayKey, requestTime) != null) {
            return SignatureValidationResult.fail("Signature has been used");
        }

        return SignatureValidationResult.success();
    }

    public String generateSignature(String method, String path, String timestamp, String body) {
        return generateSignature(method, path, timestamp, null, body);
    }

    public String generateSignature(String method, String path, String timestamp, String nonce, String body) {
        return generateSignature(Config.ADMIN_TOKEN, method, path, timestamp, nonce, body);
    }

    public String generateSignature(String secret, String method, String path, String timestamp, String nonce, String body) {
        try {
            String trimmedNonce = nonce == null ? "" : nonce.trim();
            String data = trimmedNonce.isEmpty()
                    ? method + "|" + path + "|" + timestamp + "|" + (body == null ? "" : body)
                    : method + "|" + path + "|" + timestamp + "|" + trimmedNonce + "|" + (body == null ? "" : body);
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            ServerLogger.error("SecurityManager", "nkm.security.signatureBuildFail", e);
            return "";
        }
    }

    public boolean isIpLocked(String ip) {
        if (ip == null || ip.isBlank()) return false;
        AuthFailureRecord record = authFailures.get(ip);
        if (record == null || !record.isLocked()) return false;

        if (System.currentTimeMillis() - record.lastFailureTime > LOCK_DURATION_MILLIS) {
            authFailures.remove(ip);
            return false;
        }
        return true;
    }

    public void recordAuthFailure(String ip) {
        if (ip == null || ip.isBlank()) return;
        authFailures.compute(ip, (ignored, record) -> {
            long now = System.currentTimeMillis();
            if (record == null || now - record.lastFailureTime > LOCK_DURATION_MILLIS) {
                return new AuthFailureRecord(1, now);
            }
            record.failureCount++;
            record.lastFailureTime = now;
            if (record.failureCount == MAX_AUTH_FAILURES) {
                ServerLogger.warnWithSource("SecurityManager", "nkm.security.ipLocked", ip);
            }
            return record;
        });
    }

    public void recordAuthSuccess(String ip) {
        if (ip != null && !ip.isBlank()) {
            authFailures.remove(ip);
        }
    }

    public long getRemainingLockSeconds(String ip) {
        AuthFailureRecord record = authFailures.get(ip);
        if (record == null || !record.isLocked()) return 0;
        long remaining = LOCK_DURATION_MILLIS - (System.currentTimeMillis() - record.lastFailureTime);
        return Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(remaining));
    }

    public void resetForTesting() {
        authFailures.clear();
        usedSignatures.clear();
    }

    public static boolean constantTimeEquals(String actual, String expected) {
        if (actual == null || expected == null) return false;
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void cleanupExpiredSignatures() {
        long threshold = Instant.now().getEpochSecond() - SIGNATURE_VALIDITY_SECONDS;
        usedSignatures.entrySet().removeIf(entry -> entry.getValue() < threshold);
    }

    public record SignatureValidationResult(boolean valid, String reason) {
        static SignatureValidationResult success() {
            return new SignatureValidationResult(true, "OK");
        }

        static SignatureValidationResult fail(String reason) {
            return new SignatureValidationResult(false, reason);
        }
    }

    private static final class AuthFailureRecord {
        private int failureCount;
        private long lastFailureTime;

        private AuthFailureRecord(int failureCount, long lastFailureTime) {
            this.failureCount = failureCount;
            this.lastFailureTime = lastFailureTime;
        }

        private boolean isLocked() {
            return failureCount >= MAX_AUTH_FAILURES;
        }
    }
}
