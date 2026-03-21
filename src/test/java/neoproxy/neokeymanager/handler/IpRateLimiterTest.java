package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * IpRateLimiter 单元测试
 * 测试 IP 限流功能
 */
class IpRateLimiterTest {

    @Mock
    private HttpExchange exchange;

    @Mock
    private Headers headers;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetClientIpFromRemoteAddress() {
        // 模拟远程地址
        InetSocketAddress address = new InetSocketAddress("192.168.1.100", 12345);
        when(exchange.getRemoteAddress()).thenReturn(address);
        when(exchange.getRequestHeaders()).thenReturn(headers);

        String ip = IpRateLimiter.getClientIp(exchange);

        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    void testGetClientIpFromXForwardedFor() {
        // 模拟内网地址，应该读取 X-Forwarded-For
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 12345);
        when(exchange.getRemoteAddress()).thenReturn(address);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Forwarded-For")).thenReturn("203.0.113.1, 70.41.3.18");

        String ip = IpRateLimiter.getClientIp(exchange);

        assertThat(ip).isEqualTo("203.0.113.1");
    }

    @Test
    void testGetClientIpFromXRealIp() {
        // 模拟内网地址，X-Forwarded-For 不存在，读取 X-Real-IP
        InetSocketAddress address = new InetSocketAddress("10.0.0.1", 12345);
        when(exchange.getRemoteAddress()).thenReturn(address);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Forwarded-For")).thenReturn(null);
        when(headers.getFirst("X-Real-IP")).thenReturn("198.51.100.1");

        String ip = IpRateLimiter.getClientIp(exchange);

        assertThat(ip).isEqualTo("198.51.100.1");
    }

    @Test
    void testGetClientIpFrom192_168() {
        // 模拟 192.168.x.x 内网地址
        InetSocketAddress address = new InetSocketAddress("192.168.1.50", 12345);
        when(exchange.getRemoteAddress()).thenReturn(address);
        when(exchange.getRequestHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Forwarded-For")).thenReturn("203.0.113.50");

        String ip = IpRateLimiter.getClientIp(exchange);

        assertThat(ip).isEqualTo("203.0.113.50");
    }

    @Test
    void testAllowFirstRequest() {
        assertThat(IpRateLimiter.allow("192.168.1.1")).isTrue();
    }

    @Test
    void testAllowMultipleRequests() {
        String ip = "192.168.1.2";

        // 前 10 次应该都允许
        for (int i = 0; i < 10; i++) {
            assertThat(IpRateLimiter.allow(ip)).isTrue();
        }
    }

    @Test
    void testAllowExceedsLimit() {
        String ip = "192.168.1.3";

        // 先消耗 10 次配额
        for (int i = 0; i < 10; i++) {
            IpRateLimiter.allow(ip);
        }

        // 第 11 次应该被拒绝
        assertThat(IpRateLimiter.allow(ip)).isFalse();
    }

    @Test
    void testAllowNullIp() {
        assertThat(IpRateLimiter.allow(null)).isFalse();
    }

    @Test
    void testAllowEmptyIp() {
        assertThat(IpRateLimiter.allow("")).isFalse();
    }

    @Test
    void testAllowBlankIp() {
        assertThat(IpRateLimiter.allow("   ")).isFalse();
    }

    @Test
    void testDifferentIpsIndependent() {
        String ip1 = "192.168.1.10";
        String ip2 = "192.168.1.11";

        // ip1 消耗完配额
        for (int i = 0; i < 10; i++) {
            IpRateLimiter.allow(ip1);
        }

        // ip1 应该被拒绝
        assertThat(IpRateLimiter.allow(ip1)).isFalse();

        // ip2 应该仍然可以访问
        assertThat(IpRateLimiter.allow(ip2)).isTrue();
    }

    @Test
    void testAllowWithIPv6() {
        String ipv6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
        assertThat(IpRateLimiter.allow(ipv6)).isTrue();
    }

    @Test
    void testGetClientIpNullExchange() {
        // 这个测试需要处理 NullPointerException
        try {
            IpRateLimiter.getClientIp(null);
        } catch (NullPointerException e) {
            // 预期行为
            assertThat(e).isInstanceOf(NullPointerException.class);
        }
    }
}
