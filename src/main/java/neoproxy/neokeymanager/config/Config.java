package neoproxy.neokeymanager.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public class Config {
    public static int PORT = 8080;
    public static String AUTH_TOKEN = "default_token";
    public static String ADMIN_TOKEN = "admin_secret";
    public static String DB_PATH = "./neokey_db";
    public static String SSL_CRT_PATH = null;
    public static String SSL_KEY_PATH = null;
    public static String CLIENT_UPDATE_URL_7Z = "";
    public static String CLIENT_UPDATE_URL_JAR = "";
    public static String DEFAULT_NODE = "";

    // [新增] 公开节点列表配置文件路径
    public static String NODE_JSON_FILE = "";

    // [安全增强] CORS 配置
    public static String CORS_ALLOWED_ORIGINS = "*";
    public static boolean CORS_ALLOW_CREDENTIALS = false;

    // [安全增强] API 签名密钥（用于请求签名验证，为空时禁用签名验证）
    public static String API_SECRET = "";

    // [安全增强] 是否启用请求签名验证（默认禁用，保持向后兼容）
    public static boolean ENABLE_SIGNATURE_VERIFY = false;

    public static void load() {
        File configFile = new File("server.properties");
        if (!configFile.exists()) {
            extractDefaultConfig(configFile);
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);

            String p = props.getProperty("SERVER_PORT");
            if (p != null) {
                try {
                    PORT = Integer.parseInt(p.trim());
                } catch (NumberFormatException e) {
                    System.err.println("[Config] Invalid SERVER_PORT format, using default: " + PORT);
                }
            }

            String t = props.getProperty("AUTH_TOKEN");
            if (t != null) AUTH_TOKEN = t.trim();

            String at = props.getProperty("ADMIN_TOKEN");
            if (at != null) ADMIN_TOKEN = at.trim();

            String d = props.getProperty("DB_PATH");
            if (d != null) DB_PATH = d.trim();

            String u7z = props.getProperty("CLIENT_UPDATE_URL_7Z");
            if (u7z != null) CLIENT_UPDATE_URL_7Z = u7z.trim();

            String uJar = props.getProperty("CLIENT_UPDATE_URL_JAR");
            if (uJar != null) CLIENT_UPDATE_URL_JAR = uJar.trim();

            String dn = props.getProperty("DEFAULT_NODE");
            if (dn != null) DEFAULT_NODE = dn.trim();

            // [新增] 读取 NODE_JSON_FILE
            String nodeJson = props.getProperty("NODE_JSON_FILE");
            if (nodeJson != null) NODE_JSON_FILE = nodeJson.trim();

            String crtPathRaw = props.getProperty("SSL_CRT_PATH");
            String keyPathRaw = props.getProperty("SSL_KEY_PATH");
            SSL_CRT_PATH = validateSslFile(crtPathRaw, "SSL_CRT_PATH");
            SSL_KEY_PATH = validateSslFile(keyPathRaw, "SSL_KEY_PATH");

            if ((SSL_CRT_PATH != null && SSL_KEY_PATH == null) || (SSL_CRT_PATH == null && SSL_KEY_PATH != null)) {
                System.err.println("[Config] SSL configuration is incomplete (missing pair). Downgrading to HTTP mode.");
                SSL_CRT_PATH = null;
                SSL_KEY_PATH = null;
            }

            // [安全增强] 读取 CORS 配置
            String corsOrigins = props.getProperty("CORS_ALLOWED_ORIGINS");
            if (corsOrigins != null) CORS_ALLOWED_ORIGINS = corsOrigins.trim();

            String corsCreds = props.getProperty("CORS_ALLOW_CREDENTIALS");
            if (corsCreds != null) {
                CORS_ALLOW_CREDENTIALS = Boolean.parseBoolean(corsCreds.trim());
            }

            // [安全增强] 读取 API 签名密钥
            String apiSecret = props.getProperty("API_SECRET");
            if (apiSecret != null) API_SECRET = apiSecret.trim();

            // [安全增强] 读取是否启用签名验证
            String enableSig = props.getProperty("ENABLE_SIGNATURE_VERIFY");
            if (enableSig != null) ENABLE_SIGNATURE_VERIFY = Boolean.parseBoolean(enableSig.trim());
        } catch (IOException e) {
            System.err.println("[Config] Critical Error loading server.properties: " + e.getMessage());
        }
    }

    private static String validateSslFile(String path, String configKey) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmedPath = path.trim();
        File file = new File(trimmedPath);
        if (!file.exists() || !file.isFile()) {
            System.err.println("[Config] Warning: " + configKey + " points to non-existent file: [" + trimmedPath + "]. Will verify SSL availability...");
            return null;
        }
        return trimmedPath;
    }

    private static void extractDefaultConfig(File targetFile) {
        System.out.println("[Config] 'server.properties' not found. Extracting default from resources...");
        try (InputStream is = Config.class.getResourceAsStream("/templates/server.properties")) {
            if (is == null) {
                System.err.println("[Config] CRITICAL: '/templates/server.properties' NOT FOUND in JAR resources!");
                return;
            }
            Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Config] Successfully extracted default config to: " + targetFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Config] Failed to extract default configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 重置配置为默认值（仅用于测试）
     */
    public static void resetToDefaults() {
        PORT = 8080;
        AUTH_TOKEN = "default_token";
        ADMIN_TOKEN = "admin_secret";
        DB_PATH = "./neokey_db";
        SSL_CRT_PATH = null;
        SSL_KEY_PATH = null;
        CLIENT_UPDATE_URL_7Z = "";
        CLIENT_UPDATE_URL_JAR = "";
        DEFAULT_NODE = "";
        NODE_JSON_FILE = "";
        CORS_ALLOWED_ORIGINS = "*";
        CORS_ALLOW_CREDENTIALS = false;
        API_SECRET = "";
        ENABLE_SIGNATURE_VERIFY = false;
    }
}
