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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class KeyHandler implements HttpHandler {

    private final SecurityManager securityManager = SecurityManager.getInstance();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            Thread.sleep(5);

            // [安全增强] 获取客户端 IP
            String clientIp = IpRateLimiter.getClientIp(exchange);

            // [安全增强] 检查 IP 是否被锁定（当启用签名验证时生效）
            if (Config.ENABLE_SIGNATURE_VERIFY && securityManager.isIpLocked(clientIp)) {
                long remainingTime = securityManager.getRemainingLockTime(clientIp);
                sendResponse(exchange, 423, new ApiError("Locked", "IP is locked. Try again in " + remainingTime + " seconds", null, null));
                return;
            }

            // [安全增强] 验证请求签名（可选，默认禁用保持向后兼容）
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (Config.ENABLE_SIGNATURE_VERIFY) {
                SecurityManager.SignatureValidationResult sigResult = securityManager.validateSignature(exchange, body);
                if (!sigResult.isValid()) {
                    securityManager.recordAuthFailure(clientIp);
                    sendResponse(exchange, 401, new ApiError("Unauthorized", sigResult.getErrorMessage(), null, null));
                    return;
                }
            }

            // 原有的 Token 验证
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + Config.AUTH_TOKEN)) {
                if (Config.ENABLE_SIGNATURE_VERIFY) {
                    securityManager.recordAuthFailure(clientIp);
                }
                sendResponse(exchange, 401, new ApiError("Unauthorized", "Invalid Token", null, null));
                return;
            }

            // [安全增强] 认证成功，清除失败记录（当启用签名验证时生效）
            if (Config.ENABLE_SIGNATURE_VERIFY) {
                securityManager.recordAuthSuccess(clientIp);
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            InputStream bodyStream = body.isEmpty() ? exchange.getRequestBody() : new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));

            if (path.equals(Protocol.API_GET_KEY) && "GET".equals(method)) handleGetKey(exchange);
            else if (path.equals(Protocol.API_HEARTBEAT) && "POST".equals(method)) handleHeartbeat(exchange, bodyStream);
            else if (path.equals(Protocol.API_SYNC) && "POST".equals(method)) handleSync(exchange, bodyStream);
            else if (path.equals(Protocol.API_RELEASE) && "POST".equals(method)) handleRelease(exchange, bodyStream);
            else if (path.equals(Protocol.API_NODE_STATUS) && "POST".equals(method)) handleNodeStatus(exchange, bodyStream);
            else if (path.equals(Protocol.API_CLIENT_UPDATE_URL) && "GET".equals(method))
                handleClientUpdateUrl(exchange);
            else sendResponse(exchange, 404, new ApiError("Not Found", "Endpoint mismatch", null, null));
        } catch (Exception e) {
            ServerLogger.error("API", "nkm.api.handleError", e);
            try {
                sendResponse(exchange, 500, new ApiError("Internal Error", e.getMessage(), null, null));
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
        String serial = params.get("serial");
        String nodeId = params.get("nodeId");
        String port = params.get("port");

        if (serial == null || nodeId == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Missing serial or nodeId", null, null));
            return;
        }

        // 1. 节点鉴权
        String nodeAlias = NodeAuthManager.getInstance().authenticateAndGetAlias(nodeId);
        if (nodeAlias == null) {
            ServerLogger.warnWithSource("API", "nkm.api.authFail", nodeId);
            sendResponse(exchange, 403, new ApiError("Forbidden", "Node not registered", null, null));
            return;
        }

        // 2. 获取真实密钥名
        String realKeyName = Database.getRealKeyName(serial);
        if (realKeyName == null) {
            ServerLogger.warnWithSource("API", "nkm.api.keyNotFound", serial);
            sendResponse(exchange, 404, new ApiError("Not Found", "Key not found", null, null));
            return;
        }

        // 3. 检查密钥状态
        DTOs.KeyStateResult state = Database.getKeyStatus(realKeyName);
        if (state == null) {
            sendResponse(exchange, 500, new ApiError("Internal Error", "Failed to get key status", null, null));
            return;
        }

        // 4. 处理不同状态
        switch (state.status()) {
            case DISABLED -> {
                sendResponse(exchange, 403, new ApiError("Forbidden", "Key is disabled", KeyStatus.DISABLED, null));
            }
            case PAUSED -> {
                sendResponse(exchange, 403, new ApiError("Forbidden", state.reason(), KeyStatus.PAUSED, null));
            }
            case ENABLED -> {
                // 5. 检查连接数限制
                SessionManager sm = SessionManager.getInstance();
                int currentConns = sm.getActiveCount(realKeyName);
                Map<String, Object> keyInfo = Database.getKeyPortInfo(realKeyName);
                int maxConns = keyInfo != null && keyInfo.containsKey("max_conns") ? (int) keyInfo.get("max_conns") : 1;
                
                if (currentConns >= maxConns) {
                    sendResponse(exchange, 429, new ApiError("Too Many Requests", "Connection limit reached", null, null));
                    return;
                }

                // 6. 端口分配逻辑
                String assignedPort = port;
                if (assignedPort == null || assignedPort.isBlank()) {
                    assignedPort = keyInfo != null && keyInfo.containsKey("default_port") ? (String) keyInfo.get("default_port") : "0";
                }

                // 7. 返回成功响应
                double balance = keyInfo != null && keyInfo.containsKey("balance") ? (double) keyInfo.get("balance") : 0.0;
                double rate = keyInfo != null && keyInfo.containsKey("rate") ? (double) keyInfo.get("rate") : 0.0;
                String expireTime = keyInfo != null && keyInfo.containsKey("expire_time") ? (String) keyInfo.get("expire_time") : "";
                boolean enableWeb = keyInfo != null && keyInfo.containsKey("enable_web") && (boolean) keyInfo.get("enable_web");

                Protocol.KeyMetadata metadata = new Protocol.KeyMetadata();
                metadata.isValid = true;
                metadata.balance = balance;
                metadata.rate = rate;
                metadata.expireTime = expireTime;
                metadata.enableWebHTML = enableWeb;

                Map<String, Object> response = new java.util.HashMap<>();
                response.put("key", realKeyName);
                response.put("port", assignedPort);
                response.put("rate", rate);
                response.put("metadata", metadata);

                ServerLogger.infoWithSource("API", "nkm.api.keyAssigned", realKeyName, nodeAlias, assignedPort);
                sendResponse(exchange, 200, response);
            }
        }
    }

    private void handleHeartbeat(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.HeartbeatPayload payload = Utils.parseJson(body, Protocol.HeartbeatPayload.class);
        if (payload == null || payload.serial == null || payload.nodeId == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Invalid payload", null, null));
            return;
        }

        // 节点鉴权
        String nodeAlias = NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId);
        if (nodeAlias == null) {
            sendResponse(exchange, 403, new ApiError("Forbidden", "Node not registered", null, null));
            return;
        }

        // 获取真实密钥名
        String realKeyName = Database.getRealKeyName(payload.serial);
        if (realKeyName == null) {
            sendResponse(exchange, 404, new ApiError("Not Found", "Key not found", null, null));
            return;
        }

        // 标记节点在线（Session 更新逻辑由 SessionManager 内部处理）
        NodeManager.getInstance().markNodeOnline(payload.nodeId);

        sendResponse(exchange, 200, Map.of("status", "ok"));
    }

    private void handleSync(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.SyncPayload payload = Utils.parseJson(body, Protocol.SyncPayload.class);
        if (payload == null || payload.nodeId == null || payload.traffic == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Invalid payload", null, null));
            return;
        }

        // 节点鉴权
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId) == null) {
            sendResponse(exchange, 403, new ApiError("Forbidden", "Node not registered", null, null));
            return;
        }

        // 流量扣除逻辑由 Database 内部处理
        // 返回密钥元数据
        Map<String, Protocol.KeyMetadata> metadataMap = new java.util.HashMap<>();
        for (String key : payload.traffic.keySet()) {
            String realKeyName = Database.getRealKeyName(key);
            if (realKeyName != null) {
                DTOs.KeyStateResult state = Database.getKeyStatus(realKeyName);
                Map<String, Object> keyInfo = Database.getKeyPortInfo(realKeyName);
                if (state != null) {
                    Protocol.KeyMetadata metadata = new Protocol.KeyMetadata();
                    metadata.isValid = state.status() == KeyStatus.ENABLED;
                    metadata.reason = state.status().name();
                    metadata.balance = keyInfo != null && keyInfo.containsKey("balance") ? (double) keyInfo.get("balance") : 0.0;
                    metadata.rate = keyInfo != null && keyInfo.containsKey("rate") ? (double) keyInfo.get("rate") : 0.0;
                    metadata.expireTime = keyInfo != null && keyInfo.containsKey("expire_time") ? (String) keyInfo.get("expire_time") : "";
                    metadata.enableWebHTML = keyInfo != null && keyInfo.containsKey("enable_web") && (boolean) keyInfo.get("enable_web");
                    metadataMap.put(key, metadata);
                }
            }
        }

        Protocol.SyncResponse response = new Protocol.SyncResponse();
        response.status = "ok";
        response.metadata = metadataMap;

        sendResponse(exchange, 200, response);
    }

    private void handleRelease(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.ReleasePayload payload = Utils.parseJson(body, Protocol.ReleasePayload.class);
        if (payload == null || payload.serial == null || payload.nodeId == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Invalid payload", null, null));
            return;
        }

        // 节点鉴权
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId) == null) {
            sendResponse(exchange, 403, new ApiError("Forbidden", "Node not registered", null, null));
            return;
        }

        // 释放会话
        String realKeyName = Database.getRealKeyName(payload.serial);
        if (realKeyName != null) {
            SessionManager.getInstance().releaseSession(realKeyName, payload.nodeId);
        }

        sendResponse(exchange, 200, Map.of("status", "ok"));
    }

    private void handleNodeStatus(HttpExchange exchange, InputStream body) throws IOException {
        Protocol.NodeStatusPayload payload = Utils.parseJson(body, Protocol.NodeStatusPayload.class);
        if (payload == null || payload.nodeId == null) {
            sendResponse(exchange, 400, new ApiError("Bad Request", "Invalid payload", null, null));
            return;
        }

        // 节点鉴权
        if (NodeAuthManager.getInstance().authenticateAndGetAlias(payload.nodeId) == null) {
            sendResponse(exchange, 403, new ApiError("Forbidden", "Node not registered", null, null));
            return;
        }

        // 标记节点在线
        NodeManager.getInstance().markNodeOnline(payload.nodeId);

        sendResponse(exchange, 200, Map.of("status", "ok"));
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = Utils.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
