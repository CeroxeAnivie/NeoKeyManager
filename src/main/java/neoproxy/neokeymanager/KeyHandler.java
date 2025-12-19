package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KeyHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {
        try {
            Thread.sleep(15);

            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + Config.AUTH_TOKEN)) {
                sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            InputStream body = exchange.getRequestBody();

            if (path.equals(Protocol.API_GET_KEY) && "GET".equals(method)) {
                handleGetKey(exchange);
            } else if (path.equals(Protocol.API_HEARTBEAT) && "POST".equals(method)) {
                handleHeartbeat(exchange, body);
            } else if (path.equals(Protocol.API_SYNC) && "POST".equals(method)) {
                handleSync(exchange, body);
            } else if (path.equals(Protocol.API_RELEASE) && "POST".equals(method)) {
                handleRelease(exchange, body);
            } else {
                sendResponse(exchange, 404, "{\"error\":\"Not Found\"}");
            }
        } catch (Exception e) {
            ServerLogger.error("API", "nkm.api.handleError", e);
            try {
                sendResponse(exchange, 500, "{\"error\":\"Internal Error\"}");
            } catch (IOException ignored) {
            }
        }
    }

    // ==================== 1. 注册 (GET /api/key) ====================
    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String nodeId = params.get("nodeId");

        if (name == null || nodeId == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing params\"}");
            return;
        }

        Map<String, Object> info = Database.getKeyInfoFull(name, nodeId);
        if (info == null) {
            sendResponse(exchange, 404, "{\"error\":\"Key not found\"}");
            return;
        }

        if (info.containsKey("ERROR_CODE")) {
            sendResponse(exchange, (int) info.get("ERROR_CODE"), "{\"error\":\"" + info.get("MSG") + "\"}");
            return;
        }

        // 获取端口和连接数限制
        String finalPort = (String) info.get("port");
        int maxConns = (int) info.get("max_conns");

        // [核心需求 3] 端口冲突预检查
        // 如果 finalPort 是单端口（静态端口），且已被该 Key 在该 Node 上占用
        // 则返回 409 No more ports available on <nodeid>
        if (!Utils.isDynamicPort(finalPort)) {
            if (SessionManager.getInstance().isSpecificPortActive(name, nodeId, finalPort)) {
                // 特殊错误消息
                sendResponse(exchange, 409, "{\"error\":\"No more ports available on " + nodeId + "\"}");
                return;
            }
        }

        // 尝试创建 Session (全局连接数检查)
        boolean success = SessionManager.getInstance().tryRegisterSession(name, nodeId, "INIT", maxConns);

        if (!success) {
            sendResponse(exchange, 409, "{\"error\":\"Max connections reached (" + maxConns + ")\"}");
        } else {
            sendResponse(exchange, 200, Utils.toJson(info));
        }
    }

    // ==================== 2. 保活 (POST /api/heartbeat) ====================
    private void handleHeartbeat(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String json = readBody(bodyStream);
        String serial = extractJson(json, "serial");
        String nodeId = extractJson(json, "nodeId");
        String port = extractJson(json, "port");

        if (serial == null || nodeId == null) {
            sendResponse(exchange, 400, toJsonStatus(Protocol.STATUS_KILL, "Invalid Format"));
            return;
        }

        int maxConns = Database.getKeyMaxConns(serial);
        if (maxConns == -1) {
            sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_KILL, "Key Not Found"));
            return;
        }

        boolean alive = SessionManager.getInstance().keepAlive(serial, nodeId, port, maxConns);

        if (alive) {
            sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_OK, null));
        } else {
            sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_KILL, "Max connections reached"));
        }
    }

    // ==================== 3. 结算 (POST /api/sync) ====================
    private void handleSync(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String json = readBody(bodyStream);
        Map<String, Double> trafficMap = Utils.parseTrafficMap(json);

        if (!trafficMap.isEmpty()) {
            Database.deductBalanceBatch(trafficMap);
        }

        List<String> keys = new ArrayList<>(trafficMap.keySet());
        Map<String, Protocol.KeyMetadata> metadata = Database.getBatchKeyMetadata(keys);

        Protocol.SyncResponse resp = new Protocol.SyncResponse();
        resp.status = Protocol.STATUS_OK;
        resp.metadata = metadata;

        sendResponse(exchange, 200, Utils.toJson(resp));
    }

    // ==================== 4. 释放 (POST /api/release) ====================
    private void handleRelease(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String json = readBody(bodyStream);
        String serial = extractJson(json, "serial");
        String nodeId = extractJson(json, "nodeId");

        if (serial != null && nodeId != null) {
            SessionManager.getInstance().releaseSession(serial, nodeId);
        }
        sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_OK, "Released"));
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readBody(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String toJsonStatus(String status, String msg) {
        if (msg == null) return "{\"status\":\"" + status + "\"}";
        return "{\"status\":\"" + status + "\", \"message\":\"" + msg + "\"}";
    }

    private String extractJson(String json, String key) {
        if (json == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}