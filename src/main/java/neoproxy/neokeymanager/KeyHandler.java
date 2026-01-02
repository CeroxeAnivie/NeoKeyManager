package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import neoproxy.neokeymanager.DTOs.ApiError;
import neoproxy.neokeymanager.DTOs.KeyInfoResponse;
import neoproxy.neokeymanager.DTOs.KeyStateResult;
import neoproxy.neokeymanager.DTOs.KeyStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * API 请求处理器
 * 职责：处理 HTTP 请求，进行权限校验，分发业务逻辑，返回标准 JSON
 */
public class KeyHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {
        try {
            // 简单的防抖
            Thread.sleep(10);

            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + Config.AUTH_TOKEN)) {
                sendResponse(exchange, 401, new ApiError("Unauthorized", "Invalid Token", null));
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
                sendResponse(exchange, 404, new ApiError("Not Found", "Endpoint mismatch", null));
            }

        } catch (Exception e) {
            ServerLogger.error("API", "nkm.api.handleError", e);
            try {
                sendResponse(exchange, 500, new ApiError("Internal Error", e.getMessage(), null));
            } catch (IOException ignored) {
            }
        }
    }

    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String nodeId = params.get("nodeId");

        if (name == null || nodeId == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Missing name or nodeId", null));
            return;
        }

        Map<String, Object> dbData = Database.getKeyInfoFull(name, nodeId);

        if (dbData == null) {
            sendResponse(exchange, 404, new ApiError("Key Not Found", null, null));
            return;
        }

        KeyStateResult state = (KeyStateResult) dbData.get("STATE_RESULT");

        // 1. 手动禁用 -> 403
        if (state.status() == KeyStatus.DISABLED) {
            sendResponse(exchange, 403, new ApiError("Access Denied", state.reason(), KeyStatus.DISABLED));
            return;
        }

        // 2. 暂停 (欠费/过期) -> 409 + 具体原因 (满足需求)
        if (state.status() == KeyStatus.PAUSED) {
            sendResponse(exchange, 409, new ApiError("Key Paused", state.reason(), KeyStatus.PAUSED));
            return;
        }

        String port = (String) dbData.get("default_port");
        int maxConns = (int) dbData.get("max_conns");

        // 端口冲突检查
        if (!Utils.isDynamicPort(port)) {
            if (SessionManager.getInstance().isSpecificPortActive(name, nodeId, port)) {
                sendResponse(exchange, 409, new ApiError("Port Conflict", "Port " + port + " is busy", KeyStatus.ENABLED));
                return;
            }
        }

        // 连接数检查
        if (!SessionManager.getInstance().tryRegisterSession(name, nodeId, "INIT", maxConns)) {
            sendResponse(exchange, 429, new ApiError("Too Many Connections", "Max: " + maxConns, KeyStatus.ENABLED));
            return;
        }

        // 成功响应
        KeyInfoResponse response = new KeyInfoResponse(
                (String) dbData.get("name"),
                (double) dbData.get("balance"),
                (double) dbData.get("rate"),
                (String) dbData.get("expireTime"),
                true,
                (boolean) dbData.get("enableWebHTML"),
                port,
                maxConns
        );
        sendResponse(exchange, 200, response);
    }

    private void handleHeartbeat(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.HeartbeatPayload payload = Utils.parseJson(body, Protocol.HeartbeatPayload.class);
        if (payload == null || payload.serial == null) {
            sendResponse(exchange, 400, new ApiError("Invalid Payload", null, null));
            return;
        }

        KeyStateResult currentState = Database.getKeyStatus(payload.serial);

        // 状态异常 -> KILL
        if (currentState == null || currentState.status() != KeyStatus.ENABLED) {
            String msg = (currentState != null) ? currentState.reason() : "Key Lost";
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", msg));
            return;
        }

        int maxConns = Database.getKeyMaxConns(payload.serial);
        if (SessionManager.getInstance().keepAlive(payload.serial, payload.nodeId, payload.port, maxConns)) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_OK));
        } else {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Max Conns Exceeded"));
        }
    }

    private void handleSync(HttpExchange exchange, InputStream body) throws IOException {
        Map<String, Double> traffic = Utils.parseTrafficMap(body);
        if (!traffic.isEmpty()) Database.deductBalanceBatch(traffic);

        Protocol.SyncResponse resp = new Protocol.SyncResponse();
        resp.status = Protocol.STATUS_OK;
        resp.metadata = Database.getBatchKeyMetadata(traffic.keySet().stream().toList());
        sendResponse(exchange, 200, resp);
    }

    private void handleRelease(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.ReleasePayload payload = Utils.parseJson(body, Protocol.ReleasePayload.class);
        if (payload != null && payload.serial != null && payload.nodeId != null) {
            SessionManager.getInstance().releaseSession(payload.serial, payload.nodeId);
        }
        sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_OK));
    }

    private void sendResponse(HttpExchange exchange, int code, Object responseObj) throws IOException {
        String json = Utils.toJson(responseObj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}