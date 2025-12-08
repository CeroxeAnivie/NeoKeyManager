package neoproxy.neokeymanager;

public class PortUtils {

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

    public static String truncateRange(String targetPortStr, int limitSize) {
        if (targetPortStr == null || !targetPortStr.contains("-")) {
            return targetPortStr;
        }

        try {
            String[] parts = targetPortStr.split("-");
            int start = Integer.parseInt(parts[0].trim());
            int originalEnd = Integer.parseInt(parts[1].trim());

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