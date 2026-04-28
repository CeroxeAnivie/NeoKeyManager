package neoproxy.neokeymanager.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config 类单元测试
 * 测试配置加载、默认值、边界情况
 */
class ConfigTest {

    @TempDir
    Path tempDir;

    private File configFile;

    @BeforeEach
    void setUp() {
        configFile = new File("server.properties");
        // 重置为默认值
        Config.resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        // 清理测试文件
        if (configFile.exists()) {
            configFile.delete();
        }
        // 重置为默认值
        Config.resetToDefaults();
    }

    @Test
    void testDefaultValues() {
        // 测试默认值
        assertThat(Config.PORT).isEqualTo(8080);
        assertThat(Config.AUTH_TOKEN).isEqualTo("nkm-node-token");
        assertThat(Config.ADMIN_TOKEN).isEqualTo("nkm-admin-token");
        assertThat(Config.DB_PATH).isEqualTo("./neokey_db");
        assertThat(Config.SSL_CRT_PATH).isNull();
        assertThat(Config.SSL_KEY_PATH).isNull();
        assertThat(Config.CLIENT_UPDATE_URL_EXE).isEmpty();
        assertThat(Config.CLIENT_UPDATE_URL_JAR).isEmpty();
        assertThat(Config.DEFAULT_NODE).isEmpty();
        assertThat(Config.NODE_JSON_FILE).isEmpty();
    }

    @Test
    void testLoadValidConfig() throws IOException {
        // 创建有效的配置文件
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=9090\n");
            writer.write("AUTH_TOKEN=test_token\n");
            writer.write("ADMIN_TOKEN=test_admin\n");
            writer.write("DB_PATH=./test_db\n");
            writer.write("CLIENT_UPDATE_URL_EXE=http://example.com/update.exe\n");
            writer.write("CLIENT_UPDATE_URL_JAR=http://example.com/update.jar\n");
            writer.write("DEFAULT_NODE=node1\n");
            writer.write("NODE_JSON_FILE=nodes.json\n");
        }

        Config.load();

        assertThat(Config.PORT).isEqualTo(9090);
        assertThat(Config.AUTH_TOKEN).isEqualTo("test_token");
        assertThat(Config.ADMIN_TOKEN).isEqualTo("test_admin");
        assertThat(Config.DB_PATH).isEqualTo("./test_db");
        assertThat(Config.CLIENT_UPDATE_URL_EXE).isEqualTo("http://example.com/update.exe");
        assertThat(Config.CLIENT_UPDATE_URL_JAR).isEqualTo("http://example.com/update.jar");
        assertThat(Config.DEFAULT_NODE).isEqualTo("node1");
        assertThat(Config.NODE_JSON_FILE).isEqualTo("nodes.json");
    }

    @Test
    void testLoadInvalidPortFormat() throws IOException {
        // 创建包含无效端口格式的配置文件
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=invalid\n");
        }

        int originalPort = Config.PORT;
        Config.load();

        // 端口格式无效时应该保持原值
        assertThat(Config.PORT).isEqualTo(originalPort);
    }

    @Test
    void testLoadEmptyConfig() throws IOException {
        // 创建空配置文件
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("");
        }

        Config.load();

        // 应该使用默认值
        assertThat(Config.PORT).isEqualTo(8080);
    }

    @Test
    void testLoadPartialConfig() throws IOException {
        // 创建部分配置文件
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=7777\n");
            writer.write("AUTH_TOKEN=partial_token\n");
        }

        Config.load();

        assertThat(Config.PORT).isEqualTo(7777);
        assertThat(Config.AUTH_TOKEN).isEqualTo("partial_token");
        // 其他应该使用默认值
        assertThat(Config.ADMIN_TOKEN).isEqualTo("nkm-admin-token");
    }

    @Test
    void testPortTrimming() throws IOException {
        // 测试端口值的前后空格处理
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=  8888  \n");
        }

        Config.load();

        assertThat(Config.PORT).isEqualTo(8888);
    }

    @Test
    void testTokenTrimming() throws IOException {
        // 测试token值的前后空格处理
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("AUTH_TOKEN=  token_with_spaces  \n");
        }

        Config.load();

        assertThat(Config.AUTH_TOKEN).isEqualTo("token_with_spaces");
    }

    @Test
    void testNegativePort() throws IOException {
        // 测试负数端口（边界情况）
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=-1\n");
        }

        int originalPort = Config.PORT;
        Config.load();

        // 负数应该被解析但可能不是有效端口
        assertThat(Config.PORT).isEqualTo(-1);
    }

    @Test
    void testZeroPort() throws IOException {
        // 测试零端口
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=0\n");
        }

        Config.load();

        assertThat(Config.PORT).isEqualTo(0);
    }

    @Test
    void testLargePort() throws IOException {
        // 测试大端口号
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SERVER_PORT=65535\n");
        }

        Config.load();

        assertThat(Config.PORT).isEqualTo(65535);
    }

    @Test
    void testSslIncompleteConfiguration() throws IOException {
        // 测试不完整的SSL配置（只配置了一个）
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SSL_CRT_PATH=/path/to/cert.crt\n");
            // 不配置 SSL_KEY_PATH
        }

        Config.load();

        // 不完整的SSL配置应该被重置为null
        assertThat(Config.SSL_CRT_PATH).isNull();
        assertThat(Config.SSL_KEY_PATH).isNull();
    }

    @Test
    void testSslNonExistentFiles() throws IOException {
        // 测试SSL文件不存在的情况
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("SSL_CRT_PATH=/non/existent/cert.crt\n");
            writer.write("SSL_KEY_PATH=/non/existent/key.key\n");
        }

        Config.load();

        // 不存在的文件应该返回null
        assertThat(Config.SSL_CRT_PATH).isNull();
        assertThat(Config.SSL_KEY_PATH).isNull();
    }

    @Test
    void testConfigFileNotExists() {
        // 确保配置文件不存在
        if (configFile.exists()) {
            configFile.delete();
        }

        // 加载配置（应该提取默认配置）
        Config.load();

        // 验证默认配置被提取
        assertThat(configFile).exists();
    }

    @Test
    void testConfigFileWithComments() throws IOException {
        // 测试带注释的配置文件
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("# This is a comment\n");
            writer.write("SERVER_PORT=9999\n");
            writer.write("! Another comment style\n");
            writer.write("AUTH_TOKEN=commented_token\n");
        }

        Config.load();

        assertThat(Config.PORT).isEqualTo(9999);
        assertThat(Config.AUTH_TOKEN).isEqualTo("commented_token");
    }

    @Test
    void testSpecialCharactersInTokens() throws IOException {
        // 测试特殊字符
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("AUTH_TOKEN=token@#$%^&*()\n");
        }

        Config.load();

        assertThat(Config.AUTH_TOKEN).isEqualTo("token@#$%^&*()");
    }

    @Test
    void testUnicodeInTokens() throws IOException {
        // 测试Unicode字符
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write("AUTH_TOKEN=中文测试_token\n");
        }

        Config.load();

        assertThat(Config.AUTH_TOKEN).isEqualTo("中文测试_token");
    }
}
