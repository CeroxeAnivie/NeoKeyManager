package neoproxy.neokeymanager;

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
    public static String ADMIN_TOKEN = "admin_secret"; // [新增] 管理员Token
    public static String DB_PATH = "./neokey_db";
    public static String SSL_CRT_PATH = null;
    public static String SSL_KEY_PATH = null;

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

            // [新增] 读取 ADMIN_TOKEN
            String at = props.getProperty("ADMIN_TOKEN");
            if (at != null) ADMIN_TOKEN = at.trim();

            String d = props.getProperty("DB_PATH");
            if (d != null) DB_PATH = d.trim();

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