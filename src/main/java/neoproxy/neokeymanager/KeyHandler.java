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

            if (path.equals(Protocol.API_GET_KEY) && "GET".equals(method)) handleGetKey(exchange);
            else if (path.equals(Protocol.API_HEARTBEAT) && "POST".equals(method)) handleHeartbeat(exchange, body);
            else if (path.equals(Protocol.API_SYNC) && "POST".equals(method)) handleSync(exchange, body);
            else if (path.equals(Protocol.API_RELEASE) && "POST".equals(method)) handleRelease(exchange, body);
            else sendResponse(exchange, 404, new ApiError("Not Found", "Endpoint mismatch", null));
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
        if (state.status() == KeyStatus.DISABLED) {
            sendResponse(exchange, 403, new ApiError("Access Denied", state.reason(), KeyStatus.DISABLED));
            return;
        }
        if (state.status() == KeyStatus.PAUSED) {
            sendResponse(exchange, 409, new ApiError("Key Paused", state.reason(), KeyStatus.PAUSED));
            return;
        }

        boolean isSingle = Database.isNameSingle(requestName);
        int maxConns = (int) dbData.get("max_conns");
        String portConfig = (String) dbData.get("default_port");

        // --- 核心修复：自动寻找可用端口 ---
        String finalPort = SessionManager.getInstance().findFirstFreePort(realKeyName, portConfig);

        if (finalPort == null) {
            sendResponse(exchange, 409, new ApiError("Too Many Connections", "No free ports in range " + portConfig, KeyStatus.ENABLED));
            return;
        }

        if (!SessionManager.getInstance().tryRegisterSession(realKeyName, requestName, nodeId, "INIT", maxConns, isSingle)) {
            sendResponse(exchange, 409, new ApiError("Too Many Connections", "Global Limit Reached", KeyStatus.ENABLED));
            return;
        }

        sendResponse(exchange, 200, new KeyInfoResponse(
                requestName, (double) dbData.get("balance"), (double) dbData.get("rate"),
                (String) dbData.get("expireTime"), true, (boolean) dbData.get("enableWebHTML"),
                finalPort, maxConns
        ));
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
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Key Paused/Disabled"));
            return;
        }
        if (SessionManager.getInstance().keepAlive(realKeyName, payload.serial, payload.nodeId, payload.port, Database.getKeyMaxConns(realKeyName), Database.isNameSingle(payload.serial))) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_OK));
        } else {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Too Many Connections"));
        }
    }

    private void handleSync(HttpExchange exchange, InputStream body) throws IOException {
        Map<String, Double> traffic = Utils.parseTrafficMap(body);
        Map<String, Double> realTraffic = new java.util.HashMap<>();
        Map<String, String> aliasToReal = new java.util.HashMap<>();

        traffic.forEach((alias, v) -> {
            String real = Database.getRealKeyName(alias);
            if (real != null) {
                realTraffic.merge(real, v, Double::sum);
                aliasToReal.put(alias, real);
            }
        });

        if (!realTraffic.isEmpty()) Database.deductBalanceBatch(realTraffic);
        var realMetadataMap = Database.getBatchKeyMetadata(realTraffic.keySet().stream().toList());
        Map<String, Protocol.KeyMetadata> maskedMetadata = new java.util.HashMap<>();
        aliasToReal.forEach((alias, real) -> {
            if (realMetadataMap.containsKey(real)) maskedMetadata.put(alias, realMetadataMap.get(real));
        });

        Protocol.SyncResponse resp = new Protocol.SyncResponse();
        resp.status = Protocol.STATUS_OK;
        resp.metadata = maskedMetadata;
        sendResponse(exchange, 200, resp);
    }

    private void handleRelease(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.ReleasePayload payload = Utils.parseJson(body, Protocol.ReleasePayload.class);
        if (payload != null && payload.serial != null && payload.nodeId != null) {
            String realKeyName = Database.getRealKeyName(payload.serial);
            if (realKeyName != null) SessionManager.getInstance().releaseSession(realKeyName, payload.nodeId);
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