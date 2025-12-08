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
            // 反扫描延迟
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 预读反扫描
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

            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            if (authHeader == null || !authHeader.equals("Bearer " + Config.AUTH_TOKEN)) {
                sendResponse(exchange, 401, "{\"error\":\"Unauthorized\"}");
                return;
            }

            if (path.equals("/api/key") && method.equals("GET")) {
                handleGetKey(exchange);
            } else if (path.equals("/api/sync") && method.equals("POST")) {
                handleSync(exchange, requestBodyStream);
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

    private void handleGetKey(HttpExchange exchange) throws IOException {
        Map<String, String> params = Utils.parseQueryParams(exchange.getRequestURI().getQuery());
        String name = params.get("name");
        String nodeId = params.get("nodeId");

        if (name == null || nodeId == null) {
            sendResponse(exchange, 400, "{\"error\":\"Missing params\"}");
            return;
        }

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

    private void handleSync(HttpExchange exchange, InputStream bodyStream) throws IOException {
        String body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
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
                if (nodeId != null) {
                    SessionManager.getInstance().tryAcquireOrRefresh(user, nodeId, 9999);
                }
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

    private String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractTrafficJson(String json) {
        int start = json.indexOf("\"traffic\"");
        if (start == -1) return "";
        int braceStart = json.indexOf("{", start);
        int braceEnd = json.indexOf("}", braceStart);
        if (braceStart != -1 && braceEnd != -1) {
            return json.substring(braceStart + 1, braceEnd);
        }
        return "";
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