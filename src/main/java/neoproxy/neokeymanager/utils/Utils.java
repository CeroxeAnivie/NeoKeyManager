package neoproxy.neokeymanager.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工业级工具类：集成 Gson JSON 处理与原有参数解析逻辑
 * 职责：单一职责，处理所有序列化与字符串操作
 */
public class Utils {

    // ==================== JSON Logic (Gson) ====================
    private static final Gson GSON;
    private static final Pattern PORT_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    static {
        GSON = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    public static String toJson(Object object) {
        try {
            return GSON.toJson(object);
        } catch (Exception e) {
            ServerLogger.error("Utils", "nkm.error.jsonSerialize", e);
            return "{\"error\":\"JSON_ERROR\"}";
        }
    }

    public static <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return GSON.fromJson(json, clazz);
        } catch (Exception e) {
            ServerLogger.error("Utils", "nkm.error.jsonParse", e);
            return null;
        }
    }

    public static <T> T parseJson(InputStream is, Class<T> clazz) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, clazz);
        }
    }

    // ==================== Param & Port Logic ====================

    /**
     * 专门解析流量同步数据，兼容不同格式
     */
    public static Map<String, Double> parseTrafficMap(InputStream is) {
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonObject rootObj = root.getAsJsonObject();

            // 兼容 {"key": 10} 和 {"traffic": {"key": 10}}
            JsonElement trafficElement = rootObj.has("traffic") ? rootObj.get("traffic") : rootObj;

            return GSON.fromJson(trafficElement, new TypeToken<Map<String, Double>>() {}.getType());
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static Map<String, String> parseQueryParams(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=", 2);
            if (entry.length > 0) {
                String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                // 只有存在值时才放入map，没有值时key不存在（返回null）
                if (entry.length > 1) {
                    result.put(key, URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    public static int calculatePortSize(String port) {
        if (port == null || port.isBlank()) return 0;
        Matcher m = PORT_RANGE_PATTERN.matcher(port);
        if (m.matches()) {
            try {
                int start = Integer.parseInt(m.group(1));
                int end = m.group(2) != null ? Integer.parseInt(m.group(2)) : start;
                return end - start + 1;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 检查是否为动态端口（auto 或 AUTO）
     *
     * @param port 端口字符串
     * @return 如果是动态端口返回 true，否则返回 false
     */
    public static boolean isDynamicPort(String port) {
        if (port == null || port.isBlank()) return false;
        return "auto".equalsIgnoreCase(port.trim());
    }
}
