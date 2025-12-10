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
            // 反爆破延迟
            Thread.sleep(15);

            // 鉴权
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + Config.AUTH_TOKEN)) {
                sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            InputStream body = exchange.getRequestBody();

            // 路由分发
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
    // NPS 启动连接时调用，必须在此处创建 Session
    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String nodeId = params.get("nodeId");

        if (name == null || nodeId == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing params\"}");
            return;
        }

        // 1. 获取数据库配置
        Map<String, Object> info = Database.getKeyInfoFull(name, nodeId); // 包含 max_conns
        if (info == null) {
            sendResponse(exchange, 404, "{\"error\":\"Key not found\"}");
            return;
        }

        // 检查业务层错误 (如余额不足/禁用)
        if (info.containsKey("ERROR_CODE")) {
            sendResponse(exchange, (int) info.get("ERROR_CODE"), "{\"error\":\"" + info.get("MSG") + "\"}");
            return;
        }

        // 2. 尝试创建 Session (霸占模式)
        // 初始连接时，端口可能还未分配，暂时用 "INIT" 占位
        int maxConns = (int) info.get("max_conns");
        boolean success = SessionManager.getInstance().tryRegisterSession(name, nodeId, "INIT", maxConns);

        if (!success) {
            // 并发已满，新的进不来
            sendResponse(exchange, 409, "{\"error\":\"Max connections reached (" + maxConns + ")\"}");
        } else {
            // 成功，返回配置信息
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

        // 简易查询 max_conns (不查余额，余额由 Sync 查)
        int maxConns = Database.getKeyMaxConns(serial);
        if (maxConns == -1) {
            sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_KILL, "Key Not Found"));
            return;
        }

        // 尝试保活
        boolean alive = SessionManager.getInstance().keepAlive(serial, nodeId, port, maxConns);

        if (alive) {
            sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_OK, null));
        } else {
            // Session 丢失且重新注册失败(满了) -> 杀死连接
            sendResponse(exchange, 200, toJsonStatus(Protocol.STATUS_KILL, "Max connections reached"));
        }
    }

    // ==================== 3. 结算 (POST /api/sync) ====================
    private void handleSync(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String json = readBody(bodyStream);
        Map<String, Double> trafficMap = Utils.parseTrafficMap(json);

        // 1. 扣费
        if (!trafficMap.isEmpty()) {
            Database.deductBalanceBatch(trafficMap);
        }

        // 2. 获取元数据 (检查是否需要因欠费而断开)
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

    // ... [Helper Methods: sendResponse, readBody, toJsonStatus, extractJson] ...

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

    // 简易 JSON 提取器，建议使用真正的 JSON 库 (Gson/Fastjson) 替换
    private String extractJson(String json, String key) {
        if (json == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}