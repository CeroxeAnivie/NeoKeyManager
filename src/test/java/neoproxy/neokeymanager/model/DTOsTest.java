package neoproxy.neokeymanager.model;

import neoproxy.neokeymanager.model.DTOs.ApiError;
import neoproxy.neokeymanager.model.DTOs.KeyInfoResponse;
import neoproxy.neokeymanager.model.DTOs.KeyStateResult;
import neoproxy.neokeymanager.model.DTOs.KeyStatus;
import neoproxy.neokeymanager.model.DTOs.UpdateUrlResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTOs 单元测试
 * 测试数据传输对象的创建和属性
 */
class DTOsTest {

    // ==================== KeyStatus 枚举测试 ====================

    @Test
    void testKeyStatusValues() {
        assertThat(KeyStatus.values()).hasSize(3);
        assertThat(KeyStatus.ENABLED).isNotNull();
        assertThat(KeyStatus.PAUSED).isNotNull();
        assertThat(KeyStatus.DISABLED).isNotNull();
    }

    @Test
    void testKeyStatusOrdinal() {
        assertThat(KeyStatus.ENABLED.ordinal()).isEqualTo(0);
        assertThat(KeyStatus.PAUSED.ordinal()).isEqualTo(1);
        assertThat(KeyStatus.DISABLED.ordinal()).isEqualTo(2);
    }

    // ==================== ApiError 记录测试 ====================

    @Test
    void testApiErrorWithAllFields() {
        ApiError error = new ApiError("AUTH_ERROR", "Invalid token", KeyStatus.DISABLED, "Custom message");

        assertThat(error.error()).isEqualTo("AUTH_ERROR");
        assertThat(error.reason()).isEqualTo("Invalid token");
        assertThat(error.status()).isEqualTo(KeyStatus.DISABLED);
        assertThat(error.customBlockingMessage()).isEqualTo("Custom message");
    }

    @Test
    void testApiErrorWithThreeFields() {
        ApiError error = new ApiError("NOT_FOUND", "Key not found", KeyStatus.PAUSED);

        assertThat(error.error()).isEqualTo("NOT_FOUND");
        assertThat(error.reason()).isEqualTo("Key not found");
        assertThat(error.status()).isEqualTo(KeyStatus.PAUSED);
        assertThat(error.customBlockingMessage()).isNull();
    }

    @Test
    void testApiErrorImplementsSerializable() {
        ApiError error = new ApiError("TEST", "Test reason", KeyStatus.ENABLED);
        assertThat(error).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== KeyInfoResponse 记录测试 ====================

    @Test
    void testKeyInfoResponse() {
        KeyInfoResponse response = new KeyInfoResponse(
                "testKey",
                100.5,
                0.01,
                "2024/12/31-23:59",
                true,
                false,
                "8080-8090",
                5
        );

        assertThat(response.name()).isEqualTo("testKey");
        assertThat(response.balance()).isEqualTo(100.5);
        assertThat(response.rate()).isEqualTo(0.01);
        assertThat(response.expireTime()).isEqualTo("2024/12/31-23:59");
        assertThat(response.isEnable()).isTrue();
        assertThat(response.enableWebHTML()).isFalse();
        assertThat(response.port()).isEqualTo("8080-8090");
        assertThat(response.max_conns()).isEqualTo(5);
    }

    @Test
    void testKeyInfoResponseImplementsSerializable() {
        KeyInfoResponse response = new KeyInfoResponse("key", 0.0, 0.0, "", false, false, "", 0);
        assertThat(response).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== UpdateUrlResponse 记录测试 ====================

    @Test
    void testUpdateUrlResponseValid() {
        UpdateUrlResponse response = new UpdateUrlResponse("http://example.com/update", true);

        assertThat(response.url()).isEqualTo("http://example.com/update");
        assertThat(response.valid()).isTrue();
    }

    @Test
    void testUpdateUrlResponseInvalid() {
        UpdateUrlResponse response = new UpdateUrlResponse(null, false);

        assertThat(response.url()).isNull();
        assertThat(response.valid()).isFalse();
    }

    @Test
    void testUpdateUrlResponseImplementsSerializable() {
        UpdateUrlResponse response = new UpdateUrlResponse("", false);
        assertThat(response).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== KeyStateResult 记录测试 ====================

    @Test
    void testKeyStateResult() {
        KeyStateResult result = new KeyStateResult(KeyStatus.ENABLED, "All good");

        assertThat(result.status()).isEqualTo(KeyStatus.ENABLED);
        assertThat(result.reason()).isEqualTo("All good");
    }

    @Test
    void testKeyStateResultPaused() {
        KeyStateResult result = new KeyStateResult(KeyStatus.PAUSED, "Insufficient balance");

        assertThat(result.status()).isEqualTo(KeyStatus.PAUSED);
        assertThat(result.reason()).isEqualTo("Insufficient balance");
    }

    @Test
    void testKeyStateResultNullReason() {
        KeyStateResult result = new KeyStateResult(KeyStatus.DISABLED, null);

        assertThat(result.status()).isEqualTo(KeyStatus.DISABLED);
        assertThat(result.reason()).isNull();
    }

    // ==================== 边界测试 ====================

    @Test
    void testKeyInfoResponseWithZeroBalance() {
        KeyInfoResponse response = new KeyInfoResponse("key", 0.0, 0.01, "", true, false, "", 1);
        assertThat(response.balance()).isZero();
    }

    @Test
    void testKeyInfoResponseWithNegativeBalance() {
        KeyInfoResponse response = new KeyInfoResponse("key", -10.5, 0.01, "", true, false, "", 1);
        assertThat(response.balance()).isNegative();
    }

    @Test
    void testKeyInfoResponseWithZeroRate() {
        KeyInfoResponse response = new KeyInfoResponse("key", 100.0, 0.0, "", true, false, "", 1);
        assertThat(response.rate()).isZero();
    }

    @Test
    void testKeyInfoResponseWithZeroMaxConns() {
        KeyInfoResponse response = new KeyInfoResponse("key", 100.0, 0.01, "", true, false, "", 0);
        assertThat(response.max_conns()).isZero();
    }

    @Test
    void testApiErrorWithEmptyStrings() {
        ApiError error = new ApiError("", "", KeyStatus.ENABLED, "");

        assertThat(error.error()).isEmpty();
        assertThat(error.reason()).isEmpty();
        assertThat(error.customBlockingMessage()).isEmpty();
    }

    @Test
    void testApiErrorWithNullFields() {
        ApiError error = new ApiError(null, null, KeyStatus.ENABLED, null);

        assertThat(error.error()).isNull();
        assertThat(error.reason()).isNull();
        assertThat(error.customBlockingMessage()).isNull();
    }

    @Test
    void testUpdateUrlResponseWithEmptyUrl() {
        UpdateUrlResponse response = new UpdateUrlResponse("", true);
        assertThat(response.url()).isEmpty();
        assertThat(response.valid()).isTrue();
    }

    @Test
    void testUpdateUrlResponseWithLongUrl() {
        String longUrl = "http://example.com/" + "a".repeat(1000);
        UpdateUrlResponse response = new UpdateUrlResponse(longUrl, true);
        // "http://example.com/" = 19 字符 + 1000 个 'a' = 1019
        assertThat(response.url()).hasSize(1019);
    }

    @Test
    void testKeyStateResultWithEmptyReason() {
        KeyStateResult result = new KeyStateResult(KeyStatus.ENABLED, "");
        assertThat(result.reason()).isEmpty();
    }

    @Test
    void testKeyStateResultWithLongReason() {
        String longReason = "a".repeat(1000);
        KeyStateResult result = new KeyStateResult(KeyStatus.ENABLED, longReason);
        assertThat(result.reason()).hasSize(1000);
    }
}
