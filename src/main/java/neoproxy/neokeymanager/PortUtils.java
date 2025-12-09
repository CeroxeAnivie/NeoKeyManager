package neoproxy.neokeymanager;

public class PortUtils {

    /**
     * 计算端口范围大小
     * "8080" -> 1
     * "4600-4650" -> 51
     */
    public static int calculateSize(String portStr) {
        if (portStr == null || portStr.isBlank()) return 1;
        if (!portStr.contains("-")) {
            return isNumeric(portStr) ? 1 : 1;
        }

        try {
            String[] parts = portStr.split("-");
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            return Math.max(1, end - start + 1);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 判断是否为动态端口范围
     */
    public static boolean isDynamic(String portStr) {
        return calculateSize(portStr) > 1;
    }

    /**
     * 截断端口范围，确保不超过 Key 允许的最大连接数
     */
    public static String truncateRange(String targetPortStr, int limitSize) {
        if (targetPortStr == null || !targetPortStr.contains("-")) {
            return targetPortStr;
        }

        try {
            String[] parts = targetPortStr.split("-");
            int start = Integer.parseInt(parts[0].trim());
            int originalEnd = Integer.parseInt(parts[1].trim());

            // 限制最大结束端口
            int allowedEnd = start + limitSize - 1;
            int finalEnd = Math.min(originalEnd, allowedEnd);

            if (start >= finalEnd) {
                return String.valueOf(start);
            }
            return start + "-" + finalEnd;
        } catch (Exception e) {
            return targetPortStr;
        }
    }

    private static boolean isNumeric(String str) {
        if (str == null) return false;
        return str.matches("\\d+");
    }
}