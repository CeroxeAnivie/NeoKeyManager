package neoproxy.neokeymanager.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    public static void load() {
        File configFile = new File("server.properties");
        if (!configFile.exists()) {
            extractDefaultConfig(configFile);
        }
        try (FileInputStream fis = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.load(fis);

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
        try (InputStream is = Config.class.getResourceAsStream("/server.properties")) {
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
}
