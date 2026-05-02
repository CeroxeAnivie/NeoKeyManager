package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 HTTP server 原生实现的 CORS filter。
 *
 * 将 CORS 保持在路由边界而不是业务处理器内部，这样 preflight 请求就不会消耗命令或鉴权代码路径。
 */
public final class CorsFilter extends Filter {

    private final String allowedOrigins;
    private final boolean allowCredentials;
    private final Set<String> normalizedOrigins;

    public CorsFilter(String allowedOrigins, boolean allowCredentials) {
        this.allowedOrigins = (allowedOrigins == null || allowedOrigins.isBlank()) ? "*" : allowedOrigins.trim();
        this.allowCredentials = allowCredentials;
        this.normalizedOrigins = Arrays.stream(this.allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        applyCorsHeaders(exchange, origin);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        chain.doFilter(exchange);
    }

    private void applyCorsHeaders(HttpExchange exchange, String origin) {
        var headers = exchange.getResponseHeaders();
        if ("*".equals(allowedOrigins) && !allowCredentials) {
            headers.set("Access-Control-Allow-Origin", "*");
        } else if (origin != null && isAllowed(origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }

        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Timestamp, X-Nonce, X-Signature");
        headers.set("Access-Control-Max-Age", "86400");
        headers.set("Access-Control-Expose-Headers", "X-RateLimit-Remaining");

        if (allowCredentials && origin != null && isAllowed(origin)) {
            headers.set("Access-Control-Allow-Credentials", "true");
        }
    }

    private boolean isAllowed(String origin) {
        return normalizedOrigins.contains("*") || normalizedOrigins.contains(origin.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String description() {
        return "NeoKeyManager CORS Filter";
    }
}
