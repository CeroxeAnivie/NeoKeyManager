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

public class KeyHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {
        try {
            Thread.sleep(5);

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
        String requestName = params.get("name");
        String nodeId = params.get("nodeId");

        if (requestName == null || nodeId == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Missing name or nodeId", null));
            return;
        }

        String realKeyName = Database.getRealKeyName(requestName);
        if (realKeyName == null) {
            sendResponse(exchange, 404, new ApiError("Key Not Found", null, null));
            return;
        }

        Map<String, Object> dbData = Database.getKeyInfoFull(realKeyName, nodeId);
        if (dbData == null) {
            sendResponse(exchange, 404, new ApiError("Key Not Found", null, null));
            return;
        }

        KeyStateResult state = (KeyStateResult) dbData.get("STATE_RESULT");
        boolean isSingle = Database.isNameSingle(requestName);

        if (state.status() == KeyStatus.DISABLED) {
            sendResponse(exchange, 403, new ApiError("Access Denied", state.reason(), KeyStatus.DISABLED));
            return;
        }

        if (state.status() == KeyStatus.PAUSED) {
            sendResponse(exchange, 409, new ApiError("Key Paused", state.reason(), KeyStatus.PAUSED));
            return;
        }

        String port = (String) dbData.get("default_port");
        int maxConns = (int) dbData.get("max_conns");

        // 端口冲突检查 (NPS 也会在 409 里处理 Port Conflict)
        if (!Utils.isDynamicPort(port)) {
            if (SessionManager.getInstance().isSpecificPortActive(realKeyName, nodeId, port)) {
                sendResponse(exchange, 409, new ApiError("Port Conflict", "Port " + port + " is busy", KeyStatus.ENABLED));
                return;
            }
        }

        // 注册 Session (鉴权核心逻辑)
        if (!SessionManager.getInstance().tryRegisterSession(realKeyName, requestName, nodeId, "INIT", maxConns, isSingle)) {
            String reason = isSingle ? "Global Single Mode Limit" : "Max Connections Reached";

            // 【核心修复】
            // 必须返回 409 Conflict！因为 NPS 源码中写死只在 if(code==409) 里判断 "Too Many Connections"
            // 如果返回 429，NPS 会直接忽略错误类型。
            // ApiError.error 必须包含 "Too Many Connections" 字符串。
            sendResponse(exchange, 409, new ApiError("Too Many Connections", reason, KeyStatus.ENABLED));
            return;
        }

        KeyInfoResponse response = new KeyInfoResponse(
                requestName,
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

        String realKeyName = Database.getRealKeyName(payload.serial);

        if (realKeyName == null) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Key Not Found"));
            return;
        }

        KeyStateResult currentState = Database.getKeyStatus(realKeyName);

        if (currentState == null || currentState.status() != KeyStatus.ENABLED) {
            String msg = (currentState != null) ? currentState.reason() : "Key Lost";
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", msg));
            return;
        }

        boolean isSingle = Database.isNameSingle(payload.serial);
        int maxConns = Database.getKeyMaxConns(realKeyName);

        if (SessionManager.getInstance().keepAlive(realKeyName, payload.serial, payload.nodeId, payload.port, maxConns, isSingle)) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_OK));
        } else {
            // 心跳失败，返回 STATUS_KILL
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Too Many Connections"));
        }
    }

    private void handleSync(HttpExchange exchange, InputStream body) throws IOException {
        Map<String, Double> traffic = Utils.parseTrafficMap(body);
        Map<String, Double> realTraffic = new java.util.HashMap<>();

        traffic.forEach((k, v) -> {
            String real = Database.getRealKeyName(k);
            if (real != null) {
                realTraffic.merge(real, v, Double::sum);
            }
        });

        if (!realTraffic.isEmpty()) Database.deductBalanceBatch(realTraffic);

        Protocol.SyncResponse resp = new Protocol.SyncResponse();
        resp.status = Protocol.STATUS_OK;
        resp.metadata = Database.getBatchKeyMetadata(realTraffic.keySet().stream().toList());
        sendResponse(exchange, 200, resp);
    }

    private void handleRelease(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.ReleasePayload payload = Utils.parseJson(body, Protocol.ReleasePayload.class);
        if (payload != null && payload.serial != null && payload.nodeId != null) {
            String realKeyName = Database.getRealKeyName(payload.serial);
            if (realKeyName != null) {
                SessionManager.getInstance().releaseSession(realKeyName, payload.nodeId);
            }
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