package neoproxy.neokeymanager.utils;

import neoproxy.neokeymanager.Application;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class ServerLogger {

    private static final String BUNDLE_NAME = "messages";
    public static boolean alert = true;
    private static ResourceBundle bundle;
    private static Locale currentLocale = Locale.getDefault(); // 默认跟随系统
    private static boolean testMode = false; // 测试模式标志，避免在测试中打印堆栈

    static {
        setLocale(currentLocale);
        // 检测是否在测试环境中运行
        testMode = isTestEnvironment();
    }

    /**
     * 检测当前是否在测试环境中运行
     */
    private static boolean isTestEnvironment() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName();
                if (className.contains("org.junit.") ||
                    className.contains("org.testng.") ||
                    className.contains("org.junit.jupiter.")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 手动设置测试模式（供测试类使用）
     */
    public static void setTestMode(boolean enabled) {
        testMode = enabled;
    }

    public static void setLocale(Locale locale) {
        if (locale == null) {
            System.err.println("[ServerLogger] Warning: Locale is null, using default locale");
            locale = Locale.getDefault();
        }
        currentLocale = locale;
        try {
            bundle = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale);
        } catch (MissingResourceException e) {
            System.err.println("CRITICAL ERROR: Failed to load ResourceBundle: " + BUNDLE_NAME);
            bundle = null;
        }
    }

    // ==================== INFO ====================
    public static void info(String key, Object... args) {
        log("INFO", "NeoKeyManager", key, args);
    }

    public static void infoWithSource(String source, String key, Object... args) {
        log("INFO", source, key, args);
    }

    // ==================== WARN ====================
    public static void warn(String key, Object... args) {
        log("WARN", "NeoKeyManager", key, args);
    }

    public static void warnWithSource(String source, String key, Object... args) {
        log("WARN", source, key, args);
    }

    // ==================== ERROR ====================
    public static void error(String key, Object... args) {
        log("ERROR", "NeoKeyManager", key, args);
    }

    public static void error(String source, String key, Throwable e, Object... args) {
        String message = getMessage(key, args);
        if (Application.myConsole != null) {
            Application.myConsole.error(source, message, e);
        } else {
            System.err.println("[" + source + "] " + message);
            // 在测试模式下不打印异常堆栈，保持测试输出整洁
            if (e != null && !testMode) {
                e.printStackTrace();
            }
        }
    }

    // 重载不带 Exception 的 errorWithSource
    public static void errorWithSource(String source, String key, Object... args) {
        log("ERROR", source, key, args);
    }

    // ==================== INTERNAL ====================
    private static void log(String level, String source, String key, Object... args) {
        String message = getMessage(key, args);
        if (Application.myConsole != null) {
            switch (level) {
                case "WARN" -> Application.myConsole.warn(source, message);
                case "ERROR" -> Application.myConsole.error(source, message);
                default -> Application.myConsole.log(source, message);
            }
        } else {
            // Fallback before console init
            System.out.println("[" + source + "] " + message);
        }
    }

    public static String getMessage(String key, Object... args) {
        if (key == null) return "!!! Null Key !!!";
        if (bundle == null) return "!!! No Bundle: " + key + " !!!";
        try {
            String pattern = bundle.getString(key);
            // 将数字转为字符串以避免千位分隔符
            Object[] formattedArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Number) {
                    formattedArgs[i] = String.valueOf(args[i]);
                } else {
                    formattedArgs[i] = args[i];
                }
            }
            return MessageFormat.format(pattern, formattedArgs);
        } catch (MissingResourceException e) {
            return "!!! Key Not Found: " + key + " !!!";
        }
    }
}