package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.database.Database;
import neoproxy.neokeymanager.manager.NodeAuthManager;
import neoproxy.neokeymanager.manager.NodeManager;
import neoproxy.neokeymanager.manager.SessionManager;
import neoproxy.neokeymanager.model.DTOs;
import neoproxy.neokeymanager.model.DTOs.ApiError;
import neoproxy.neokeymanager.model.DTOs.KeyStatus;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;
import neoproxy.neokeymanager.utils.Utils;

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
            else if (path.equals(Protocol.API_NODE_STATUS) && "POST".equals(method)) handleNodeStatus(exchange, body);
            else if (path.equals(Protocol.API_CLIENT_UPDATE_URL) && "GET".equals(method))
                handleClientUpdateUrl(exchange);
            else sendResponse(exchange, 404, new ApiError("Not Found", "Endpoint mismatch", null));
        } catch (Exception e) {
            ServerLogger.error("API", "nkm.api.handleError", e);
            try {
                sendResponse(exchange, 500, new ApiError("Internal Error", e.getMessage(), null));
            } catch (IOException ignored) {
            }
        }
    }

    private void handleClientUpdateUrl(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String os = params.get("os");
        String serial = params.get("serial");
        String nodeId = params.get("nodeId");

        if (os == null || serial == null || nodeId == null) {
            sendResponse(exchange, 400, new DTOs.UpdateUrlResponse(null, false));
            return;
        }

        if (NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId) == null) {
            sendResponse(exchange, 403, new DTOs.UpdateUrlResponse(null, false));
            return;
        }

        String realKeyName = Database.getRealKeyName(serial);
        if (realKeyName == null) {
            ServerLogger.warnWithSource("API", "nkm.api.updateDenied", serial);
            sendResponse(exchange, 200, new DTOs.UpdateUrlResponse(null, false));
            return;
        }

        DTOs.KeyStateResult state = Database.getKeyStatus(realKeyName);
        if (state == null || state.status() != KeyStatus.ENABLED) {
            ServerLogger.warnWithSource("API", "nkm.api.updateDenied", realKeyName);
            sendResponse(exchange, 200, new DTOs.UpdateUrlResponse(null, false));
            return;
        }

        String url = null;
        if ("7z".equalsIgnoreCase(os)) {
            url = Config.CLIENT_UPDATE_URL_7Z;
        } else if ("jar".equalsIgnoreCase(os)) {
            url = Config.CLIENT_UPDATE_URL_JAR;
        }

        if (url == null || url.isBlank()) {
            ServerLogger.warn("API", "Update URL not configured for OS: " + os);
            sendResponse(exchange, 200, new DTOs.UpdateUrlResponse(null, false));
        } else {
            sendResponse(exchange, 200, new DTOs.UpdateUrlResponse(url, true));
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

        String nodeAlias = NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId);
        if (nodeAlias == null) {
            sendResponse(exchange, 403, new ApiError("Access Denied", "Node Unauthorized", KeyStatus.DISABLED));
            return;
        }

        String realKeyName = Database.getRealKeyName(requestName);
        if (realKeyName == null) {
            sendResponse(exchange, 404, new ApiError("Key Not Found", null, null));
            return;
        }

        boolean hasSpecificMap = Database.hasSpecificMap(realKeyName, nodeId);
        if (!hasSpecificMap) {
            String defaultNodeCfg = Config.DEFAULT_NODE;
            NodeAuthManager authMgr = NodeAuthManager.getInstance();
            boolean isDefaultNodeConfigValid = defaultNodeCfg != null
                    && !defaultNodeCfg.isBlank()
                    && authMgr.isNodeExplicitlyRegistered(defaultNodeCfg);

            if (isDefaultNodeConfigValid) {
                if (!nodeId.equalsIgnoreCase(defaultNodeCfg.trim())) {
                    ServerLogger.warnWithSource("API", "nkm.api.defaultNodeDenied", nodeId, realKeyName);
                    sendResponse(exchange, 403, new ApiError("Access Denied",
                            "Default port is restricted to node: " + authMgr.getAlias(defaultNodeCfg),
                            KeyStatus.ENABLED));
                    return;
                }
            }
        }

        Map<String, Object> dbData = Database.getKeyInfoFull(realKeyName, nodeId);
        if (dbData == null) {
            sendResponse(exchange, 404, new ApiError("Key Not Found", null, null));
            return;
        }

        DTOs.KeyStateResult state = (DTOs.KeyStateResult) dbData.get("STATE_RESULT");
        if (state.status() == KeyStatus.DISABLED || state.status() == KeyStatus.PAUSED) {
            String cbm = Database.getCustomBlockingMsg(realKeyName);
            sendResponse(exchange, state.status() == KeyStatus.DISABLED ? 403 : 409,
                    new ApiError(state.status() == KeyStatus.DISABLED ? "Access Denied" : "Key Paused",
                            state.reason(), state.status(), cbm));
            return;
        }

        boolean isSingle = Database.isNameSingle(requestName);
        int maxConns = (int) dbData.get("max_conns");
        String portConfig = (String) dbData.get("default_port");

        String finalPort = SessionManager.getInstance().findFirstFreePort(realKeyName, portConfig, nodeId);
        if (finalPort == null) {
            sendResponse(exchange, 409, new ApiError("Too Many Connections", "No free ports in range " + portConfig, KeyStatus.ENABLED));
            return;
        }

        if (!SessionManager.getInstance().tryRegisterSession(realKeyName, requestName, nodeId, "INIT", maxConns, isSingle)) {
            sendResponse(exchange, 409, new ApiError("Too Many Connections", "Global Limit Reached", KeyStatus.ENABLED));
            return;
        }

        ServerLogger.info("nkm.api.access", requestName, nodeAlias, finalPort);

        sendResponse(exchange, 200, new DTOs.KeyInfoResponse(
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

        if (NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId) == null) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Node Unauthorized"));
            return;
        }

        // [核心注入点] 心跳有效，同步更新节点在线状态
        NodeManager.getInstance().markNodeOnline(payload.nodeId);

        String realKeyName = Database.getRealKeyName(payload.serial);
        if (realKeyName == null) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Key Not Found"));
            return;
        }

        DTOs.KeyStateResult currentState = Database.getKeyStatus(realKeyName);
        if (currentState == null || currentState.status() != KeyStatus.ENABLED) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Key Paused/Disabled"));
            return;
        }

        if (SessionManager.getInstance().keepAlive(
                realKeyName, payload.serial, payload.nodeId, payload.port,
                Database.getKeyMaxConns(realKeyName),
                Database.isNameSingle(payload.serial),
                payload.connectionDetail
        )) {
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
            if (realKeyName != null) {
                SessionManager.getInstance().releaseSession(realKeyName, payload.nodeId, payload.serial);
            }
        }
        sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_OK));
    }

    private void handleNodeStatus(HttpExchange exchange, InputStream body) throws IOException {
        // [填补画饼] 处理 NPS 发来的专属状态汇报
        Protocol.NodeStatusPayload payload = Utils.parseJson(body, Protocol.NodeStatusPayload.class);
        if (payload != null && payload.nodeId != null) {
            if (NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId) != null) {
                // 合法节点，刷新它的在线存活时间
                NodeManager.getInstance().markNodeOnline(payload.nodeId);
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
