package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeyHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) {
        try {
            // 1. 反扫描/爆破延迟 (轻微延迟，防止暴力攻击)
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 2. 预读检查 (防止空 Body 导致的流错误)
            InputStream originalIs = exchange.getRequestBody();
            PushbackInputStream pbis = new PushbackInputStream(originalIs, 1);
            try {
                int firstByte = pbis.read();
                if (firstByte != -1) {
                    pbis.unread(firstByte);
                }
            } catch (IOException ignored) {
            }

            final InputStream requestBodyStream = pbis;
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            // 3. 统一鉴权
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !authHeader.equals("Bearer " + Config.AUTH_TOKEN)) {
                sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            // 4. 路由分发
            if (path.equals(Protocol.API_HEARTBEAT) && method.equals("POST")) {
                handleHeartbeat(exchange, requestBodyStream);
            } else if (path.equals(Protocol.API_STATUS) && method.equals("POST")) {
                handleStatusCheck(exchange, requestBodyStream);
            } else if (path.equals(Protocol.API_SYNC) && method.equals("POST")) {
                handleSync(exchange, requestBodyStream);
            } else if (path.equals("/api/key") && method.equals("GET")) {
                handleGetKey(exchange);
            } else if (path.equals("/api/release") && method.equals("POST")) {
                handleRelease(exchange);
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

    // ==================== 1. 心跳接口 (Core) ====================
    private void handleHeartbeat(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String body = readBody(bodyStream);

        // 解析 JSON
        String serial = extractJsonString(body, "serial");
        String nodeId = extractJsonString(body, "nodeId");
        String port = extractJsonString(body, "port");

        if (serial == null || nodeId == null) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid Heartbeat Format\"}");
            return;
        }

        // 1. 验证 Key 基本有效性 (使用轻量级查询)
        Map<String, Object> keyInfo = Database.getKeyInfoSimple(serial);

        if (keyInfo == null || keyInfo.containsKey("ERROR_CODE")) {
            // Key 不存在或已失效 (被禁/过期/余额不足)
            String msg = keyInfo != null ? (String) keyInfo.get("MSG") : "Key Not Found";
            // 返回 kill 指令让 NPS 断开连接
            sendResponse(exchange, 200, "{\"status\":\"kill\", \"message\":\"" + msg + "\"}");
            return;
        }

        // 2. 获取允许的最大连接数
        int maxConns = (int) keyInfo.getOrDefault("max_conns", 1);

        // 3. 更新 Session (核心逻辑)
        // 如果连接数已满，SessionManager 会返回 false
        boolean accepted = SessionManager.getInstance().handleHeartbeat(serial, nodeId, port, maxConns);

        if (accepted) {
            sendResponse(exchange, 200, "{\"status\":\"ok\"}");
        } else {
            sendResponse(exchange, 200, "{\"status\":\"kill\", \"message\":\"Max connections reached\"}");
        }
    }

    // ==================== 2. 状态批量查询 (New) ====================
    private void handleStatusCheck(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String body = readBody(bodyStream);

        // 解析 keys 数组
        List<String> keysToCheck = parseKeysFromJson(body);

        if (keysToCheck.isEmpty()) {
            sendResponse(exchange, 400, "{\"error\":\"Missing keys list\"}");
            return;
        }

        // 批量查询数据库
        Map<String, Protocol.KeyStatusDetail> statuses = Database.getBatchKeyStatus(keysToCheck);

        // 手动构建复杂 JSON 响应
        StringBuilder json = new StringBuilder("{\"statuses\":{");
        int i = 0;
        for (Map.Entry<String, Protocol.KeyStatusDetail> entry : statuses.entrySet()) {
            String k = entry.getKey();
            Protocol.KeyStatusDetail v = entry.getValue();

            json.append("\"").append(escapeJson(k)).append("\":{");
            json.append("\"isValid\":").append(v.isValid).append(",");
            json.append("\"reason\":\"").append(v.reason).append("\",");
            json.append("\"balance\":").append(v.balance).append(",");
            // handle null expireTime
            json.append("\"expireTime\":\"").append(v.expireTime == null ? "" : v.expireTime).append("\"");
            json.append("}");

            if (i < statuses.size() - 1) json.append(",");
            i++;
        }
        json.append("}}");

        sendResponse(exchange, 200, json.toString());
    }

    // ==================== 3. 流量同步 (Fixed 9999 Bug) ====================
    private void handleSync(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String body = readBody(bodyStream);
        String nodeId = extractJsonString(body, "nodeId");

        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9.]+)");
        Matcher m = p.matcher(extractTrafficJson(body));

        List<String> involvedKeys = new ArrayList<>();
        while (m.find()) {
            String user = m.group(1);
            involvedKeys.add(user);
            try {
                double mib = Double.parseDouble(m.group(2));
                if (mib > 0) Database.deductBalance(user, mib);

                // [Fix 9999 Bug]
                // 不再盲目调用 tryAcquireOrRefresh(..., 9999)
                // 而是调用 refreshOnTraffic，仅刷新时间戳，不进行连接数逻辑校验
                // 真正的连接数控制由 handleHeartbeat 负责
                SessionManager.getInstance().refreshOnTraffic(user, nodeId);

            } catch (NumberFormatException ignored) {
            }
        }

        List<String> invalidKeys = Database.checkInvalidKeys(involvedKeys);

        String response;
        if (invalidKeys.isEmpty()) {
            response = "{\"status\":\"ok\"}";
        } else {
            StringBuilder sb = new StringBuilder("{\"status\":\"ok\", \"kill_keys\":[");
            for (int i = 0; i < invalidKeys.size(); i++) {
                sb.append("\"").append(invalidKeys.get(i)).append("\"");
                if (i < invalidKeys.size() - 1) sb.append(",");
            }
            sb.append("]}");
            response = sb.toString();
        }

        sendResponse(exchange, 200, response);
    }

    // ==================== 4. 初始连接 (Legacy Support) ====================
    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String nodeId = params.get("nodeId");

        if (name == null || nodeId == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing params\"}");
            return;
        }

        // 获取完整信息并注册 Session
        Map<String, Object> info = Database.getKeyInfo(name, nodeId);

        if (info == null) {
            sendResponse(exchange, 404, "{\"error\":\"Key not found\"}");
        } else if (info.containsKey("ERROR_CODE")) {
            int code = (int) info.get("ERROR_CODE");
            String msg = (String) info.get("MSG");
            sendResponse(exchange, code, "{\"error\":\"" + msg + "\"}");
        } else {
            sendResponse(exchange, 200, Utils.toJson(info));
        }
    }

    // ==================== 5. 释放连接 ====================
    private void handleRelease(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String nodeId = params.get("nodeId");

        if (name != null && nodeId != null) {
            SessionManager.getInstance().release(name, nodeId);
            sendResponse(exchange, 200, "{\"status\":\"released\"}");
        } else {
            sendResponse(exchange, 400, "{\"error\":\"Missing params\"}");
        }
    }

    // ==================== 6. 工具方法 (Helpers) ====================

    private String readBody(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String extractJsonString(String json, String key) {
        if (json == null) return null;
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractTrafficJson(String json) {
        if (json == null) return "";
        int start = json.indexOf("\"traffic\"");
        if (start == -1) return "";
        int braceStart = json.indexOf("{", start);
        int braceEnd = json.indexOf("}", braceStart);
        if (braceStart != -1 && braceEnd != -1) {
            return json.substring(braceStart + 1, braceEnd);
        }
        return "";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\\", "\\\\");
    }

    /**
     * 从 JSON 中解析字符串数组
     * 格式示例: { "keys": ["a", "b", "c"] }
     */
    private List<String> parseKeysFromJson(String json) {
        List<String> list = new ArrayList<>();
        if (json == null) return list;

        // 1. 定位数组内容
        int start = json.indexOf("[");
        int end = json.lastIndexOf("]");

        if (start != -1 && end != -1 && end > start) {
            String content = json.substring(start + 1, end);
            // 2. 按逗号分割
            String[] parts = content.split(",");
            for (String p : parts) {
                // 3. 清理引号和空白字符
                String key = p.trim()
                        .replaceAll("^\"|\"$", "") // 去除首尾双引号
                        .replace("\n", "")
                        .replace("\r", "");

                if (!key.isBlank()) {
                    list.add(key);
                }
            }
        }
        return list;
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}