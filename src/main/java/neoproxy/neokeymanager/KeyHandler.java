package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import neoproxy.neokeymanager.DTOs.*;

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

                // [新增] 客户端更新 URL 获取接口
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

    // [新增] 处理客户端更新 URL 请求
    private void handleClientUpdateUrl(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String os = params.get("os");
        String serial = params.get("serial");
        String nodeId = params.get("nodeId");

        // 1. 参数完整性检查
        if (os == null || serial == null || nodeId == null) {
            sendResponse(exchange, 400, new UpdateUrlResponse(null, false));
            return;
        }

        // 2. 节点鉴权
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId) == null) {
            sendResponse(exchange, 403, new UpdateUrlResponse(null, false));
            return;
        }

        // 3. 密钥合法性检查 (必须存在 且 状态为 ENABLED)
        String realKeyName = Database.getRealKeyName(serial);
        if (realKeyName == null) {
            ServerLogger.warnWithSource("API", "nkm.api.updateDenied", serial);
            sendResponse(exchange, 200, new UpdateUrlResponse(null, false)); // 返回200但valid=false，让客户端优雅处理
            return;
        }

        KeyStateResult state = Database.getKeyStatus(realKeyName);
        if (state == null || state.status() != KeyStatus.ENABLED) {
            ServerLogger.warnWithSource("API", "nkm.api.updateDenied", realKeyName);
            sendResponse(exchange, 200, new UpdateUrlResponse(null, false));
            return;
        }

        // 4. 获取对应 URL
        String url = null;
        if ("7z".equalsIgnoreCase(os)) {
            url = Config.CLIENT_UPDATE_URL_7Z;
        } else if ("jar".equalsIgnoreCase(os)) {
            url = Config.CLIENT_UPDATE_URL_JAR;
        }

        if (url == null || url.isBlank()) {
            ServerLogger.warn("API", "Update URL not configured for OS: " + os);
            sendResponse(exchange, 200, new UpdateUrlResponse(null, false));
        } else {
            // 成功返回 URL
            sendResponse(exchange, 200, new UpdateUrlResponse(url, true));
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

        // 1. 节点鉴权
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

        // ==================== [逻辑变更 Start] Default Node 独占校验 ====================
        // 只有当节点没有针对该 Key 的特殊 Map 时，才受 Default Node 限制

        // A. 检查是否存在 Map (豁免权)
        boolean hasSpecificMap = Database.hasSpecificMap(realKeyName, nodeId);

        if (!hasSpecificMap) {
            String defaultNodeCfg = Config.DEFAULT_NODE;
            NodeAuthManager authMgr = NodeAuthManager.getInstance();

            // B. 校验配置的 Default Node 是否有效 (防止配置错误导致所有节点不可用)
            // 有效定义 = 配置不为空 且 该节点ID存在于 NodeAuth.json 中
            boolean isDefaultNodeConfigValid = defaultNodeCfg != null
                    && !defaultNodeCfg.isBlank()
                    && authMgr.isNodeExplicitlyRegistered(defaultNodeCfg);

            if (isDefaultNodeConfigValid) {
                // C. 执行排他性检查
                // 如果配置了有效的主节点，且当前请求节点不是主节点 -> 拒绝访问
                if (!nodeId.equalsIgnoreCase(defaultNodeCfg.trim())) {
                    ServerLogger.warnWithSource("API", "nkm.api.defaultNodeDenied", nodeId, realKeyName);

                    // 返回 403 Forbidden，明确告知原因
                    sendResponse(exchange, 403, new ApiError("Access Denied",
                            "Default port is restricted to node: " + authMgr.getAlias(defaultNodeCfg),
                            KeyStatus.ENABLED));
                    return;
                }
            }
        }
        // ==================== [逻辑变更 End] ====================

        Map<String, Object> dbData = Database.getKeyInfoFull(realKeyName, nodeId);
        if (dbData == null) {
            sendResponse(exchange, 404, new ApiError("Key Not Found", null, null));
            return;
        }

        KeyStateResult state = (KeyStateResult) dbData.get("STATE_RESULT");

        // 2. 状态与 CBM 检查
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

        // 3. 端口分配
        String finalPort = SessionManager.getInstance().findFirstFreePort(realKeyName, portConfig, nodeId);

        if (finalPort == null) {
            sendResponse(exchange, 409, new ApiError("Too Many Connections", "No free ports in range " + portConfig, KeyStatus.ENABLED));
            return;
        }

        if (!SessionManager.getInstance().tryRegisterSession(realKeyName, requestName, nodeId, "INIT", maxConns, isSingle)) {
            sendResponse(exchange, 409, new ApiError("Too Many Connections", "Global Limit Reached", KeyStatus.ENABLED));
            return;
        }

        // 4. 打印详细接入日志 (Key + Node + Port)
        ServerLogger.info("nkm.api.access", requestName, nodeAlias, finalPort);

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

        if (NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId) == null) {
            sendResponse(exchange, 200, Map.of("status", Protocol.STATUS_KILL, "message", "Node Unauthorized"));
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