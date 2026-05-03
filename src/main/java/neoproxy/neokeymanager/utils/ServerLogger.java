package neoproxy.neokeymanager.utils;

import neoproxy.neokeymanager.Application;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class ServerLogger {

    private static final String EN_BUNDLE_NAME = "messages_en";
    private static final String ZH_BUNDLE_NAME = "messages_zh";
    public static boolean alert = true;
    private static ResourceBundle bundle;
    private static Locale currentLocale = Locale.ENGLISH;

    static {
        setLocale(currentLocale);
    }

    public static void setLocale(Locale locale) {
        if (locale == null) {
            System.err.println("[ServerLogger] Warning: Locale is null, using English locale");
            locale = Locale.ENGLISH;
        }
        currentLocale = locale;
        String bundleName = resolveBundleName(locale);
        try {
            bundle = ResourceBundle.getBundle(bundleName, Locale.ROOT);
        } catch (MissingResourceException e) {
            System.err.println("CRITICAL ERROR: Failed to load ResourceBundle: " + bundleName);
            try {
                bundle = ResourceBundle.getBundle(EN_BUNDLE_NAME, Locale.ROOT);
            } catch (MissingResourceException fallbackError) {
                bundle = null;
            }
        }
    }

    private static String resolveBundleName(Locale locale) {
        return "zh".equalsIgnoreCase(locale.getLanguage()) ? ZH_BUNDLE_NAME : EN_BUNDLE_NAME;
    }

    // ==================== 信息 ====================
    public static void info(String key, Object... args) {
        log("INFO", "NeoKeyManager", key, args);
    }

    public static void infoWithSource(String source, String key, Object... args) {
        log("INFO", source, key, args);
    }

    // ==================== 警告 ====================
    public static void warn(String key, Object... args) {
        log("WARN", "NeoKeyManager", key, args);
    }

    public static void warnWithSource(String source, String key, Object... args) {
        log("WARN", source, key, args);
    }

    // ==================== 错误 ====================
    public static void error(String key, Object... args) {
        log("ERROR", "NeoKeyManager", key, args);
    }

    public static void error(String source, String key, Throwable e, Object... args) {
        String message = getMessage(key, args);
        writeError(source, message, e);
    }

    // 重载不带异常参数的 errorWithSource
    public static void errorWithSource(String source, String key, Object... args) {
        log("ERROR", source, key, args);
    }

    public static void errorWithSource(String source, String key, Throwable e, Object... args) {
        String message = getMessage(key, args);
        writeError(source, message, e);
    }

    public static void logRaw(String source, String message) {
        writeInfo(source, message);
    }

    // ==================== 内部 ====================
    private static void log(String level, String source, String key, Object... args) {
        String message = getMessage(key, args);
        switch (level) {
            case "WARN" -> writeWarn(source, message);
            case "ERROR" -> writeError(source, message, null);
            default -> writeInfo(source, message);
        }
    }

    private static void writeInfo(String source, String message) {
        if (Application.myConsole != null) {
            Application.myConsole.log(source, message);
            return;
        }
        System.out.println("[" + source + "] " + message);
    }

    private static void writeWarn(String source, String message) {
        if (Application.myConsole != null) {
            Application.myConsole.warn(source, message);
            return;
        }
        System.out.println("[" + source + "] " + message);
    }

    private static void writeError(String source, String message, Throwable e) {
        if (Application.myConsole != null) {
            if (e != null) {
                Application.myConsole.error(source, message, e);
            } else {
                Application.myConsole.error(source, message);
            }
            return;
        }
        System.err.println("[" + source + "] " + message);
        if (e != null) e.printStackTrace(System.err);
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
