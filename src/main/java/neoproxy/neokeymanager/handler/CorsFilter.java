package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * CORS过滤器 - 跨域资源共享
 * 【安全增强】支持可配置的CORS策略
 * 参考 NeoAuthServer 实现
 */
public class CorsFilter extends Filter {

    private final String allowedOrigins;
    private final boolean allowCredentials;

    /**
     * 创建CORS过滤器
     * @param allowedOrigins 允许的源，逗号分隔，*表示允许所有（生产环境不推荐）
     * @param allowCredentials 是否允许携带凭证
     */
    public CorsFilter(String allowedOrigins, boolean allowCredentials) {
        this.allowedOrigins = allowedOrigins != null ? allowedOrigins : "*";
        this.allowCredentials = allowCredentials;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String requestMethod = exchange.getRequestMethod();

        // 处理预检请求 (OPTIONS)
        if ("OPTIONS".equalsIgnoreCase(requestMethod)) {
            handlePreflight(exchange, origin);
            return;
        }

        // 处理实际请求
        handleActualRequest(exchange, origin);
        chain.doFilter(exchange);
    }

    private void handlePreflight(HttpExchange exchange, String origin) throws IOException {
        var headers = exchange.getResponseHeaders();

        // 设置允许的源
        if ("*".equals(allowedOrigins)) {
            headers.set("Access-Control-Allow-Origin", "*");
        } else if (origin != null && isOriginAllowed(origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }

        // 设置允许的方法
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        // 设置允许的请求头
        headers.set("Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-Requested-With, Accept, Origin");

        // 设置预检请求缓存时间
        headers.set("Access-Control-Max-Age", "86400");

        // 是否允许携带凭证
        if (allowCredentials && !"*".equals(allowedOrigins)) {
            headers.set("Access-Control-Allow-Credentials", "true");
        }

        // 暴露的响应头
        headers.set("Access-Control-Expose-Headers", "X-Request-ID");

        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void handleActualRequest(HttpExchange exchange, String origin) {
        var headers = exchange.getResponseHeaders();

        // 设置允许的源
        if ("*".equals(allowedOrigins)) {
            headers.set("Access-Control-Allow-Origin", "*");
        } else if (origin != null && isOriginAllowed(origin)) {
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
        }

        // 是否允许携带凭证
        if (allowCredentials && !"*".equals(allowedOrigins)) {
            headers.set("Access-Control-Allow-Credentials", "true");
        }
    }

    private boolean isOriginAllowed(String origin) {
        if ("*".equals(allowedOrigins)) return true;

        String[] origins = allowedOrigins.split(",");
        for (String allowed : origins) {
            if (allowed.trim().equalsIgnoreCase(origin)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String description() {
        return "CORS Filter - Allowed Origins: " + allowedOrigins;
    }
}
