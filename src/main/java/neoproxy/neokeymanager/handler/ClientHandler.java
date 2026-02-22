package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import neoproxy.neokeymanager.manager.NodeManager;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;
import neoproxy.neokeymanager.utils.Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ClientHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.equals(Protocol.API_CLIENT_NODELIST) && "GET".equals(method)) {
                handleNodeList(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (Exception e) {
            ServerLogger.error("ClientAPI", "nkm.api.handleError", e);
            try {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleNodeList(HttpExchange exchange) throws IOException {
        // 【核心拦截修复】: 必须在这里判断！如果配置文件无效，直接发送 404 并切断连接！
        if (!NodeManager.getInstance().isConfigured()) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return; // 必须 return，防止向下执行返回 200
        }

        String clientIp = IpRateLimiter.getClientIp(exchange);

        if (!IpRateLimiter.allow(clientIp)) {
            ServerLogger.warnWithSource("ClientAPI", "nkm.client.rateLimit", clientIp);
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
            return;
        }

        ServerLogger.infoWithSource("ClientAPI", "nkm.client.access", clientIp);

        List<Protocol.PublicNodeInfo> onlineNodes = NodeManager.getInstance().getOnlinePublicNodes();
        String jsonResponse = Utils.toJson(onlineNodes);

        byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
