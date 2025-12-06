package neoproxy.neokeymanager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class Config {
    public static int PORT = 8080;
    public static String AUTH_TOKEN = "default_token";
    public static String DB_PATH = "./neokey_db";
    public static String SSL_CRT_PATH = null;
    public static String SSL_KEY_PATH = null;

    private static final String DEFAULT_CONFIG_CONTENT = """
            # NeoKeyManager Configuration File
            
            # Service Port (Default: 8080)
            SERVER_PORT=8080
            
            # Authorization Token (Must match NeoProxyServer's sync.cfg)
            AUTH_TOKEN=default_token
            
            # Database Path (H2 Database file path)
            DB_PATH=./neokey_db
            
            # SSL Configuration (Optional - Uncomment to enable HTTPS)
            # Support Nginx/OpenSSL .pem, .crt, .key (PKCS#1 & PKCS#8)
            # SSL_CRT_PATH=./cert/server.crt
            # SSL_KEY_PATH=./cert/server.key
            """;

    public static void load() {
        File file = new File("server.properties");

        // 【核心修复】文件不存在则自动创建
        if (!file.exists()) {
            createDefaultConfig(file);
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(fis);

            String p = props.getProperty("SERVER_PORT");
            if (p != null) PORT = Integer.parseInt(p.trim());

            String t = props.getProperty("AUTH_TOKEN");
            if (t != null) AUTH_TOKEN = t.trim();

            String d = props.getProperty("DB_PATH");
            if (d != null) DB_PATH = d.trim();

            String crt = props.getProperty("SSL_CRT_PATH");
            if (crt != null && !crt.isBlank()) SSL_CRT_PATH = crt.trim();

            String key = props.getProperty("SSL_KEY_PATH");
            if (key != null && !key.isBlank()) SSL_KEY_PATH = key.trim();

        } catch (IOException | NumberFormatException e) {
            System.err.println("[Config] Error loading config: " + e.getMessage());
        }
    }

    private static void createDefaultConfig(File file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(DEFAULT_CONFIG_CONTENT.getBytes(StandardCharsets.UTF_8));
            System.out.println("[Config] Created default server.properties");
        } catch (IOException e) {
            System.err.println("[Config] Failed to create default config file: " + e.getMessage());
        }
    }
}