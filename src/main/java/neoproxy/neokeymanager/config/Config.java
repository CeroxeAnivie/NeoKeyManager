package neoproxy.neokeymanager.config;

import neoproxy.neokeymanager.utils.ServerLogger;

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
    public static int NODELIST_RATE_LIMIT_PER_DAY = 10;

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
                    ServerLogger.warnWithSource("Config", "nkm.config.invalidServerPort", PORT);
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
                    ServerLogger.warnWithSource("Config", "nkm.config.dbPathRestartRequired", DB_PATH);
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

            String nodeListRateLimit = props.getProperty("NODELIST_RATE_LIMIT_PER_DAY");
            if (nodeListRateLimit != null) {
                try {
                    int parsedLimit = Integer.parseInt(nodeListRateLimit.trim());
                    if (parsedLimit < 0) {
                        ServerLogger.warnWithSource("Config", "nkm.config.invalidNodeListRateLimit", NODELIST_RATE_LIMIT_PER_DAY);
                    } else {
                        NODELIST_RATE_LIMIT_PER_DAY = parsedLimit;
                    }
                } catch (NumberFormatException e) {
                    ServerLogger.warnWithSource("Config", "nkm.config.invalidNodeListRateLimit", NODELIST_RATE_LIMIT_PER_DAY);
                }
            }

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
                ServerLogger.errorWithSource("Config", "nkm.config.sslIncomplete");
                SSL_MISCONFIGURED = true;
                SSL_CRT_PATH = null;
                SSL_KEY_PATH = null;
            } else if (sslRequested && (SSL_CRT_PATH == null || SSL_KEY_PATH == null)) {
                SSL_MISCONFIGURED = true;
            }
            ensureRuntimeTemplates();
        } catch (IOException e) {
            ServerLogger.errorWithSource("Config", "nkm.config.loadFailed", e, e.getMessage());
        }
    }

    private static String validateSslFile(String path, String configKey) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmedPath = path.trim();
        File file = new File(trimmedPath);
        if (!file.exists() || !file.isFile()) {
            ServerLogger.warnWithSource("Config", "nkm.config.sslFileMissing", configKey, trimmedPath);
            return null;
        }
        return trimmedPath;
    }

    private static void extractDefaultConfig(File targetFile) {
        ServerLogger.infoWithSource("Config", "nkm.config.extractDefault");
        try (InputStream is = Config.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (is == null) {
                ServerLogger.errorWithSource("Config", "nkm.config.defaultMissing");
                return;
            }
            Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ServerLogger.infoWithSource("Config", "nkm.config.extractSuccess", targetFile.getAbsolutePath());
        } catch (IOException e) {
            ServerLogger.errorWithSource("Config", "nkm.config.extractFailed", e, e.getMessage());
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
                    ServerLogger.errorWithSource("Config", "nkm.config.templateMissing", resourcePath);
                    return;
                }
                Files.copy(is, targetFile.toPath());
                ServerLogger.infoWithSource("Config", "nkm.config.templateGenerated", targetFile.getAbsolutePath());
            }
        } catch (IOException e) {
            ServerLogger.errorWithSource("Config", "nkm.config.templateGenerateFailed", e, targetFile.getAbsolutePath(), e.getMessage());
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
        NODELIST_RATE_LIMIT_PER_DAY = 10;
    }
}
