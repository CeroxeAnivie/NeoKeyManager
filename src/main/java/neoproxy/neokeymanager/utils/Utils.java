package neoproxy.neokeymanager.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工业级工具类：集成 Jackson JSON 处理与原有参数解析逻辑
 * 职责：单一职责，处理所有序列化与字符串操作
 */
public class Utils {

    // ==================== JSON Logic (Jackson) ====================
    private static final ObjectMapper MAPPER;
    private static final Pattern PORT_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    static {
        MAPPER = new ObjectMapper();
        MAPPER.registerModule(new JavaTimeModule()); // 支持 LocalDateTime
        // 忽略未知的 JSON 字段，保证向后兼容性
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 允许序列化空对象，防止报错
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public static String toJson(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            ServerLogger.error("Utils", "nkm.error.jsonSerialize", e);
            return "{\"error\":\"JSON_ERROR\"}";
        }
    }

    public static <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            ServerLogger.error("Utils", "nkm.error.jsonParse", e);
            return null;
        }
    }

    public static <T> T parseJson(InputStream is, Class<T> clazz) throws IOException {
        return MAPPER.readValue(is, clazz);
    }

    // ==================== Param & Port Logic ====================

    /**
     * 专门解析流量同步数据，兼容不同格式
     */
    public static Map<String, Double> parseTrafficMap(InputStream is) {
        try {
            JsonNode root = MAPPER.readTree(is);
            // 兼容 {"key": 10} 和 {"traffic": {"key": 10}}
            JsonNode trafficNode = root.has("traffic") ? root.get("traffic") : root;
            return MAPPER.convertValue(trafficNode, new TypeReference<Map<String, Double>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static Map<String, String> parseQueryParams(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                result.put(
                        URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(entry[1], StandardCharsets.UTF_8)
                );
            }
        }
        return result;
    }

    public static int calculatePortSize(String port) {
        if (port == null || port.isBlank()) return 1;
        Matcher m = PORT_RANGE_PATTERN.matcher(port);
        if (m.matches()) {
            try {
                int start = Integer.parseInt(m.group(1));
                if (m.group(2) != null) {
                    int end = Integer.parseInt(m.group(2));
                    return Math.max(1, end - start + 1);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    public static boolean isDynamicPort(String port) {
        return port != null && port.contains("-");
    }
}