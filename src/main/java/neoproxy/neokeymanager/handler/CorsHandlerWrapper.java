package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/**
 * CORS Handler 包装器
 * 将 CORS Filter 和实际的 HttpHandler 组合在一起
 */
public class CorsHandlerWrapper implements HttpHandler {

    private final HttpHandler handler;
    private final CorsFilter corsFilter;

    public CorsHandlerWrapper(HttpHandler handler, CorsFilter corsFilter) {
        this.handler = handler;
        this.corsFilter = corsFilter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 创建一个包含 CORS Filter 的链，然后执行
        List<Filter> filters = List.of(corsFilter);
        Filter.Chain chain = new Filter.Chain(filters, handler);
        chain.doFilter(exchange);
    }
}
