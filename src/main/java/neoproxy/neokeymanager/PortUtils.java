package neoproxy.neokeymanager;

public class PortUtils {

    /**
     * 计算端口范围大小 (额度)
     * "30000" -> 1
     * "30000-30002" -> 3
     * 异常输入 -> 1 (默认安全策略)
     */
    public static int calculateSize(String portStr) {
        if (portStr == null || portStr.isBlank()) return 1;
        if (!portStr.contains("-")) {
            // 简单校验是否为数字
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
     * 根据额度限制，截断目标端口范围
     * @param targetPortStr 目标端口 (e.g., "7000-8000")
     * @param limitSize 额度 (e.g., 3)
     * @return 截断后的端口 (e.g., "7000-7002")
     */
    public static String truncateRange(String targetPortStr, int limitSize) {
        if (targetPortStr == null || !targetPortStr.contains("-")) {
            return targetPortStr;
        }

        try {
            String[] parts = targetPortStr.split("-");
            int start = Integer.parseInt(parts[0].trim());
            int originalEnd = Integer.parseInt(parts[1].trim());

            // 计算允许的最大结束端口
            int allowedEnd = start + limitSize - 1;

            // 取较小值
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