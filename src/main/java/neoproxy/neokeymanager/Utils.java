package neoproxy.neokeymanager;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {

    private static final Pattern PORT_RANGE_PATTERN = Pattern.compile("^(\\d+)(?:-(\\d+))?$");
    private static final Pattern TRAFFIC_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9.]+)");

    // ==================== HTTP / JSON Helpers ====================

    public static Map<String, String> parseQueryParams(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isBlank()) return result;

        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                try {
                    String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
                    result.put(key, value);
                } catch (IllegalArgumentException e) {
                    // 忽略畸形参数
                }
            }
        }
        return result;
    }

    /**
     * 通用对象转 JSON
     */
    public static String toJson(Object obj) {
        if (obj == null) return "null";

        if (obj instanceof Map) {
            return mapToJson((Map<?, ?>) obj); // /api/key 的响应走这里
        } else if (obj instanceof Protocol.SyncResponse) {
            return syncResponseToJson((Protocol.SyncResponse) obj); // /api/sync 的响应走这里
        }

        // 兜底处理
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    private static String mapToJson(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sb.append("\"").append(entry.getKey()).append("\":");
            Object val = entry.getValue();

            if (val instanceof Map) {
                sb.append(mapToJson((Map<?, ?>) val));
            } else if (val instanceof Protocol.KeyMetadata) {
                // 【核心修复点】序列化 KeyMetadata，补充所有新字段
                Protocol.KeyMetadata meta = (Protocol.KeyMetadata) val;
                sb.append("{")
                        .append("\"isValid\":").append(meta.isValid).append(",")
                        .append("\"balance\":").append(meta.balance).append(",")
                        // --- 新增字段 Start ---
                        .append("\"rate\":").append(meta.rate).append(",")
                        .append("\"enableWebHTML\":").append(meta.enableWebHTML).append(",")
                        // expireTime 是字符串，需要加引号处理，且可能为 null
                        .append("\"expireTime\":\"").append(escapeJson(meta.expireTime)).append("\",")
                        // --- 新增字段 End ---
                        .append("\"reason\":\"").append(escapeJson(meta.reason)).append("\"")
                        .append("}");
            } else if (val instanceof String) {
                sb.append("\"").append(escapeJson((String) val)).append("\"");
            } else if (val instanceof Boolean || val instanceof Number) {
                sb.append(val);
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
            }
            if (++i < map.size()) sb.append(",");
        }
        sb.append("}");
        return sb.toString();
    }

    // 手动序列化 SyncResponse
    private static String syncResponseToJson(Protocol.SyncResponse resp) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"status\":\"").append(resp.status).append("\",");
        sb.append("\"metadata\":").append(mapToJson(resp.metadata));
        sb.append("}");
        return sb.toString();
    }

    /**
     * 解析流量 JSON: {"key1": 10.5, "key2": 2.0}
     */
    public static Map<String, Double> parseTrafficMap(String json) {
        Map<String, Double> map = new HashMap<>();
        if (json == null) return map;

        // 简单提取 traffic 对象
        int start = json.indexOf("\"traffic\"");
        if (start != -1) {
            int braceStart = json.indexOf("{", start);
            int braceEnd = json.indexOf("}", braceStart);
            if (braceStart != -1 && braceEnd != -1) {
                json = json.substring(braceStart + 1, braceEnd);
            }
        }

        Matcher m = TRAFFIC_PATTERN.matcher(json);
        while (m.find()) {
            try {
                String key = m.group(1);
                double val = Double.parseDouble(m.group(2));
                map.put(key, val);
            } catch (NumberFormatException ignored) {
            }
        }
        return map;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== Port Logic ====================

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

    public static String truncatePortRange(String port, int maxConns) {
        if (port == null || !port.contains("-")) return port;
        Matcher m = PORT_RANGE_PATTERN.matcher(port);
        if (m.matches()) {
            try {
                int start = Integer.parseInt(m.group(1));
                if (m.group(2) != null) {
                    int originalEnd = Integer.parseInt(m.group(2));
                    int calculatedEnd = start + maxConns - 1;
                    int finalEnd = Math.min(originalEnd, calculatedEnd);

                    if (finalEnd == start) return String.valueOf(start);
                    return start + "-" + finalEnd;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return port;
    }

    public static boolean isDynamicPort(String port) {
        return port != null && port.contains("-");
    }
}