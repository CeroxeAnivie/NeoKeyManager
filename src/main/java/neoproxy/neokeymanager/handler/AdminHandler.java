package neoproxy.neokeymanager.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import neoproxy.neokeymanager.Main;
import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.database.Database;
import neoproxy.neokeymanager.model.AdminDTOs.AdminResponse;
import neoproxy.neokeymanager.model.AdminDTOs.ExecRequest;
import neoproxy.neokeymanager.model.AdminDTOs.KeyDetail;
import neoproxy.neokeymanager.service.KeyService;
import neoproxy.neokeymanager.utils.ServerLogger;
import neoproxy.neokeymanager.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S-Level AdminHandler
 * 统一管理入口，确保所有管理操作都经过鉴权和统一的路由分发。
 */
public class AdminHandler implements HttpHandler {

    private static final Pattern EXEC_PATH_REGEX = Pattern.compile("^/api/exec/([^/]+)$");
    private static final Pattern LP_PATH_REGEX = Pattern.compile("^/api/(lp|lpnomap)/([^/]+)$");
    private final KeyService keyService = new KeyService();

    @Override
    public void handle(HttpExchange exchange) {
        try {
            // 1. Security Check
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + Config.ADMIN_TOKEN)) {
                sendJson(exchange, 401, new AdminResponse(false, "Unauthorized", null));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            // 2. Routing
            if (path.startsWith("/api/exec/") && "POST".equals(method)) {
                handleExec(exchange, path, exchange.getRequestBody());
            } else if (path.equals("/api/query") && "GET".equals(method)) {
                handleQuery(exchange, true);
            } else if (path.equals("/api/querynomap") && "GET".equals(method)) {
                handleQuery(exchange, false);
            } else if ((path.startsWith("/api/lp/") || path.startsWith("/api/lpnomap/")) && "GET".equals(method)) {
                handleLookup(exchange, path);
            } else if (path.equals("/api/reload") && ("POST".equals(method) || "GET".equals(method))) {
                // S-Level Add: 允许通过 API 重载系统配置
                Main.handleReload();
                sendJson(exchange, 200, new AdminResponse(true, "System Reloaded", null));
            } else {
                sendJson(exchange, 404, new AdminResponse(false, "Endpoint not found", null));
            }

        } catch (Exception e) {
            ServerLogger.error("AdminAPI", "Unhandled Exception", e);
            try {
                sendJson(exchange, 500, new AdminResponse(false, "Internal Server Error: " + e.getMessage(), null));
            } catch (IOException ignored) {
            }
        }
    }

    private void handleExec(HttpExchange exchange, String path, InputStream body) throws IOException {
        Matcher m = EXEC_PATH_REGEX.matcher(path);
        if (!m.matches()) {
            sendJson(exchange, 404, new AdminResponse(false, "Invalid Exec Path", null));
            return;
        }

        String cmd = m.group(1).toLowerCase();
        ExecRequest req;
        try {
            req = Utils.parseJson(body, ExecRequest.class);
        } catch (Exception e) {
            sendJson(exchange, 400, new AdminResponse(false, "Invalid JSON Body", null));
            return;
        }

        List<String> args = (req != null && req.args != null) ? req.args : Collections.emptyList();

        try {
            // Logic Closure: 确保所有 Main 中可用的命令（除 exit 外）这里都能用
            String resultMessage = switch (cmd) {
                case "add" -> keyService.execAddKey(args);
                case "set" -> keyService.execSetKey(args);
                case "setconn" -> keyService.execSetConn(args);
                case "del" -> keyService.execDelKey(args);

                case "map" -> keyService.execMapKey(args);
                case "delmap" -> keyService.execDelMap(args);

                case "enable" -> keyService.execEnable(args, true);
                case "disable" -> keyService.execEnable(args, false);

                case "link" -> keyService.execLinkKey(args);
                case "listlink" -> keyService.execListLink(args);

                case "setsingle" -> keyService.execSetSingle(args);
                case "delsingle" -> keyService.execDelSingle(args);
                case "listsingle" -> keyService.execListSingle(args);

                // NEW: Web command exposure
                case "web" -> keyService.execWeb(args);

                default -> throw new IllegalArgumentException("Unknown command: " + cmd);
            };

            sendJson(exchange, 200, new AdminResponse(true, resultMessage, null));

        } catch (IllegalArgumentException e) {
            // 业务逻辑错误 (参数不对，Key已存在等) -> 409 Conflict
            sendJson(exchange, 409, new AdminResponse(false, e.getMessage(), null));
        } catch (Exception e) {
            // 系统错误 (DB挂了等) -> 500
            ServerLogger.error("AdminExec", "Command Failed: " + cmd, e);
            sendJson(exchange, 500, new AdminResponse(false, "Execution failed: " + e.getMessage(), null));
        }
    }

    private void handleQuery(HttpExchange exchange, boolean includeMap) throws IOException {
        // 读操作暂不封装进 Service，保持高效直读
        List<KeyDetail> data = Database.getAllKeysStructured(includeMap, null);
        sendJson(exchange, 200, new AdminResponse(true, "Query successful", data));
    }

    private void handleLookup(HttpExchange exchange, String path) throws IOException {
        Matcher m = LP_PATH_REGEX.matcher(path);
        if (!m.matches()) {
            sendJson(exchange, 404, new AdminResponse(false, "Invalid Path", null));
            return;
        }
        String mode = m.group(1);
        String keyName = m.group(2);
        boolean includeMap = "lp".equals(mode);

        // 先解析 RealKey，确保 API 用户也能通过 Alias 查询
        String realKey = Database.getRealKeyName(keyName);
        if (realKey == null) {
            sendJson(exchange, 404, new AdminResponse(false, "Key not found: " + keyName, null));
            return;
        }

        List<KeyDetail> data = Database.getAllKeysStructured(includeMap, realKey);
        if (data.isEmpty()) {
            // 理论上 getRealKeyName 非空则 data 不应为空，除非并发删除
            sendJson(exchange, 404, new AdminResponse(false, "Key data vanished", null));
        } else {
            sendJson(exchange, 200, new AdminResponse(true, "Lookup successful", data.get(0)));
        }
    }

    private void sendJson(HttpExchange exchange, int code, Object obj) throws IOException {
        String json = Utils.toJson(obj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}