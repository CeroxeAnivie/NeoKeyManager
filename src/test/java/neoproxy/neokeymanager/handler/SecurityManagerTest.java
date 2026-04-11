package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import neoproxy.neokeymanager.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SecurityManager 单元测试
 * 测试请求签名验证和账户锁定机制
 */
class SecurityManagerTest {

    private SecurityManager securityManager;

    @Mock
    private HttpExchange exchange;

    @Mock
    private Headers headers;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        securityManager = SecurityManager.getInstance();
        // 清除之前的测试数据
        securityManager.resetForTesting();
        // 设置测试用的密钥
        Config.API_SECRET = "test-secret-key-32chars-long!!";
    }

    // ==================== 签名验证测试 ====================

    @Test
    void testValidateSignatureSuccess() throws Exception {
        // 准备测试数据
        String method = "POST";
        String path = "/api/test";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"test\":\"data\"}";

        // 生成有效签名
        String signature = generateSignature(method, path, timestamp, body);

        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Timestamp")).thenReturn(timestamp);
        when(headers.getFirst("X-Signature")).thenReturn(signature);

        SecurityManager.SignatureValidationResult result = securityManager.validateSignature(exchange, body);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void testValidateSignatureMissingHeaders() {
        when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Timestamp")).thenReturn(null);
        when(headers.getFirst("X-Signature")).thenReturn(null);

        SecurityManager.SignatureValidationResult result = securityManager.validateSignature(exchange, "body");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Missing signature headers");
    }

    @Test
    void testValidateSignatureInvalidTimestamp() {
        when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Timestamp")).thenReturn("invalid-timestamp");
        when(headers.getFirst("X-Signature")).thenReturn("some-signature");

        SecurityManager.SignatureValidationResult result = securityManager.validateSignature(exchange, "body");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Invalid timestamp format");
    }

    @Test
    void testValidateSignatureExpiredRequest() {
        // 使用过期的时间戳（超过5分钟）
        String expiredTimestamp = String.valueOf(Instant.now().getEpochSecond() - 400);

        when(exchange.getRequestURI()).thenReturn(URI.create("/api/test"));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Timestamp")).thenReturn(expiredTimestamp);
        when(headers.getFirst("X-Signature")).thenReturn("some-signature");

        SecurityManager.SignatureValidationResult result = securityManager.validateSignature(exchange, "body");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Request expired or timestamp invalid");
    }

    @Test
    void testValidateSignatureInvalidSignature() throws Exception {
        String method = "POST";
        String path = "/api/test";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"test\":\"data\"}";

        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Timestamp")).thenReturn(timestamp);
        when(headers.getFirst("X-Signature")).thenReturn("invalid-signature");

        SecurityManager.SignatureValidationResult result = securityManager.validateSignature(exchange, body);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Invalid signature");
    }

    @Test
    void testValidateSignatureReplayAttack() throws Exception {
        String method = "POST";
        String path = "/api/test";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"test\":\"data\"}";
        String signature = generateSignature(method, path, timestamp, body);

        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Timestamp")).thenReturn(timestamp);
        when(headers.getFirst("X-Signature")).thenReturn(signature);

        // 第一次验证应该成功
        SecurityManager.SignatureValidationResult result1 = securityManager.validateSignature(exchange, body);
        assertThat(result1.isValid()).isTrue();

        // 第二次使用相同签名应该失败（重放攻击）
        SecurityManager.SignatureValidationResult result2 = securityManager.validateSignature(exchange, body);
        assertThat(result2.isValid()).isFalse();
        assertThat(result2.getErrorMessage()).isEqualTo("Signature has been used");
    }

    // ==================== 账户锁定测试 ====================

    @Test
    void testRecordAuthFailureAndLock() {
        String ip = "192.168.1.100";

        // 记录4次失败，不应该锁定
        for (int i = 0; i < 4; i++) {
            securityManager.recordAuthFailure(ip);
        }
        assertThat(securityManager.isIpLocked(ip)).isFalse();

        // 第5次失败，应该锁定
        securityManager.recordAuthFailure(ip);
        assertThat(securityManager.isIpLocked(ip)).isTrue();
    }

    @Test
    void testRecordAuthSuccessClearsFailures() {
        String ip = "192.168.1.100";

        // 记录3次失败
        for (int i = 0; i < 3; i++) {
            securityManager.recordAuthFailure(ip);
        }
        assertThat(securityManager.isIpLocked(ip)).isFalse();

        // 认证成功，清除失败记录
        securityManager.recordAuthSuccess(ip);

        // 再记录2次失败，不应该锁定（因为之前被清除了）
        for (int i = 0; i < 2; i++) {
            securityManager.recordAuthFailure(ip);
        }
        assertThat(securityManager.isIpLocked(ip)).isFalse();
    }

    @Test
    void testLockExpiresAfter15Minutes() {
        String ip = "192.168.1.100";

        // 触发锁定
        for (int i = 0; i < 5; i++) {
            securityManager.recordAuthFailure(ip);
        }
        assertThat(securityManager.isIpLocked(ip)).isTrue();

        // 验证剩余锁定时间
        long remainingTime = securityManager.getRemainingLockTime(ip);
        assertThat(remainingTime).isGreaterThan(0);
        assertThat(remainingTime).isLessThanOrEqualTo(15 * 60);
    }

    @Test
    void testGetRemainingLockTimeForUnlockedIp() {
        String ip = "192.168.1.100";

        // 未锁定的IP应该返回0
        long remainingTime = securityManager.getRemainingLockTime(ip);
        assertThat(remainingTime).isEqualTo(0);
    }

    @Test
    void testMultipleIpsIndependentLocking() {
        String ip1 = "192.168.1.100";
        String ip2 = "192.168.1.101";

        // IP1 触发锁定
        for (int i = 0; i < 5; i++) {
            securityManager.recordAuthFailure(ip1);
        }

        // IP2 只记录1次失败
        securityManager.recordAuthFailure(ip2);

        assertThat(securityManager.isIpLocked(ip1)).isTrue();
        assertThat(securityManager.isIpLocked(ip2)).isFalse();
    }

    @Test
    void testIsIpLockedWithNullIp() {
        assertThat(securityManager.isIpLocked(null)).isFalse();
    }

    @Test
    void testRecordAuthFailureWithNullIp() {
        // 不应该抛出异常
        securityManager.recordAuthFailure(null);
        assertThat(securityManager.isIpLocked(null)).isFalse();
    }

    @Test
    void testRecordAuthSuccessWithNullIp() {
        // 不应该抛出异常
        securityManager.recordAuthSuccess(null);
    }

    // ==================== 辅助方法 ====================

    private String generateSignature(String method, String path, String timestamp, String body) throws Exception {
        String data = method + "|" + path + "|" + timestamp + "|" + (body != null ? body : "");
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(Config.API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKey);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
