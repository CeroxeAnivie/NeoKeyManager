package neoproxy.neokeymanager.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public class Config {
    private static final String DEFAULT_CONFIG_RESOURCE = "/server.properties";
    private static final String TEMPLATE_RESOURCE_DIR = "/templates/";
    private static final String DEFAULT_NODE_AUTH_FILE = "NodeAuth.json";

    public static int PORT = 8080;
    public static String AUTH_TOKEN = "nkm-node-token";
    public static String ADMIN_TOKEN = "nkm-admin-token";
    public static String DB_PATH = "./neokey_db";
    public static String SSL_CRT_PATH = null;
    public static String SSL_KEY_PATH = null;
    public static boolean SSL_MISCONFIGURED = false;
    public static String CLIENT_UPDATE_URL_EXE = "";
    public static String CLIENT_UPDATE_URL_JAR = "";
    public static String DEFAULT_NODE = "";
    public static String CORS_ALLOWED_ORIGINS = "*";
    public static boolean CORS_ALLOW_CREDENTIALS = false;

    public static String NODE_JSON_FILE = "";

    public static void load() {
        load(true);
    }

    public static void load(boolean allowDbPathUpdate) {
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
            if (d != null) {
                String newDbPath = d.trim();
                if (allowDbPathUpdate) {
                    DB_PATH = newDbPath;
                } else if (!DB_PATH.equals(newDbPath)) {
                    System.err.println("[Config] DB_PATH changes require restart. Keeping current DB_PATH: " + DB_PATH);
                }
            }

            String uExe = props.getProperty("CLIENT_UPDATE_URL_EXE");
            if (uExe != null) CLIENT_UPDATE_URL_EXE = uExe.trim();

            String uJar = props.getProperty("CLIENT_UPDATE_URL_JAR");
            if (uJar != null) CLIENT_UPDATE_URL_JAR = uJar.trim();

            String dn = props.getProperty("DEFAULT_NODE");
            if (dn != null) DEFAULT_NODE = dn.trim();

            String nodeJson = props.getProperty("NODE_JSON_FILE");
            if (nodeJson != null) NODE_JSON_FILE = nodeJson.trim();

            String corsOrigins = props.getProperty("CORS_ALLOWED_ORIGINS");
            if (corsOrigins != null) CORS_ALLOWED_ORIGINS = corsOrigins.trim();

            String corsCredentials = props.getProperty("CORS_ALLOW_CREDENTIALS");
            if (corsCredentials != null) CORS_ALLOW_CREDENTIALS = Boolean.parseBoolean(corsCredentials.trim());

            String crtPathRaw = props.getProperty("SSL_CRT_PATH");
            String keyPathRaw = props.getProperty("SSL_KEY_PATH");
            boolean sslRequested = (crtPathRaw != null && !crtPathRaw.isBlank())
                    || (keyPathRaw != null && !keyPathRaw.isBlank());
            SSL_MISCONFIGURED = false;
            SSL_CRT_PATH = validateSslFile(crtPathRaw, "SSL_CRT_PATH");
            SSL_KEY_PATH = validateSslFile(keyPathRaw, "SSL_KEY_PATH");

            if ((SSL_CRT_PATH != null && SSL_KEY_PATH == null) || (SSL_CRT_PATH == null && SSL_KEY_PATH != null)) {
                System.err.println("[Config] SSL configuration is incomplete (missing pair). Refusing to silently downgrade.");
                SSL_MISCONFIGURED = true;
                SSL_CRT_PATH = null;
                SSL_KEY_PATH = null;
            } else if (sslRequested && (SSL_CRT_PATH == null || SSL_KEY_PATH == null)) {
                SSL_MISCONFIGURED = true;
            }
            ensureRuntimeTemplates();
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
            System.err.println("[Config] Warning: " + configKey + " points to non-existent file: [" + trimmedPath + "]. HTTPS startup will be rejected.");
            return null;
        }
        return trimmedPath;
    }

    private static void extractDefaultConfig(File targetFile) {
        System.out.println("[Config] 'server.properties' not found. Extracting default from resources...");
        try (InputStream is = Config.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (is == null) {
                System.err.println("[Config] CRITICAL: '/server.properties' NOT FOUND in JAR resources!");
                return;
            }
            Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Config] Successfully extracted default config to: " + targetFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Config] Failed to extract default configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void ensureRuntimeTemplates() {
        extractTemplateIfMissing(DEFAULT_NODE_AUTH_FILE, new File(System.getProperty("node.auth.file", DEFAULT_NODE_AUTH_FILE)));
        if (NODE_JSON_FILE != null && !NODE_JSON_FILE.isBlank()) {
            extractTemplateIfMissing("nodes.json", new File(NODE_JSON_FILE));
        }
    }

    private static void extractTemplateIfMissing(String templateName, File targetFile) {
        if (targetFile == null || targetFile.exists()) {
            return;
        }

        File parent = targetFile.getParentFile();
        try {
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            String resourcePath = TEMPLATE_RESOURCE_DIR + templateName;
            try (InputStream is = Config.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    System.err.println("[Config] Template resource not found: " + resourcePath);
                    return;
                }
                Files.copy(is, targetFile.toPath());
                System.out.println("[Config] Generated missing runtime template: " + targetFile.getAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("[Config] Failed to generate template " + targetFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * 重置配置为默认值（仅用于测试）
     */
    public static void resetToDefaults() {
        PORT = 8080;
        AUTH_TOKEN = "nkm-node-token";
        ADMIN_TOKEN = "nkm-admin-token";
        DB_PATH = "./neokey_db";
        SSL_CRT_PATH = null;
        SSL_KEY_PATH = null;
        SSL_MISCONFIGURED = false;
        CLIENT_UPDATE_URL_EXE = "";
        CLIENT_UPDATE_URL_JAR = "";
        DEFAULT_NODE = "";
        CORS_ALLOWED_ORIGINS = "*";
        CORS_ALLOW_CREDENTIALS = false;
        NODE_JSON_FILE = "";
    }
}
