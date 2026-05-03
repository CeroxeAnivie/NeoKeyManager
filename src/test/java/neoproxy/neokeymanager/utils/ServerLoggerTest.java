package neoproxy.neokeymanager.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServerLogger 单元测试
 * 测试服务器日志工具
 */
class ServerLoggerTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        // 恢复默认locale
        ServerLogger.setLocale(Locale.getDefault());
    }

    @Test
    void testSetLocale() {
        ServerLogger.setLocale(Locale.US);
        // 验证locale设置成功（不抛出异常）
    }

    @Test
    void testSetLocaleChinese() {
        ServerLogger.setLocale(Locale.SIMPLIFIED_CHINESE);
        // 验证locale设置成功
    }

    @Test
    void testInfo() {
        ServerLogger.info("nkm.system.init");

        String output = outContent.toString();
        // 输出格式: [NeoKeyManager] 系统初始化中...
        assertThat(output).contains("[NeoKeyManager]");
    }

    @Test
    void testInfoWithArgs() {
        ServerLogger.info("nkm.system.startedHttp", 8080);

        String output = outContent.toString();
        assertThat(output).contains("8080");
    }

    @Test
    void testInfoWithSource() {
        ServerLogger.infoWithSource("TestSource", "nkm.system.init");

        String output = outContent.toString();
        assertThat(output).contains("TestSource");
    }

    @Test
    void testWarn() {
        ServerLogger.warn("nkm.warning.test");

        String output = outContent.toString();
        // 输出格式: [NeoKeyManager] !!! Key Not Found: nkm.warning.test !!!
        assertThat(output).contains("[NeoKeyManager]");
    }

    @Test
    void testWarnWithSource() {
        ServerLogger.warnWithSource("TestSource", "nkm.warning.test");

        String output = outContent.toString();
        assertThat(output).contains("TestSource");
    }

    @Test
    void testError() {
        ServerLogger.error("nkm.error.test");

        String output = errContent.toString();
        // 输出格式: [NeoKeyManager] !!! Key Not Found: nkm.error.test !!!
        assertThat(output).contains("[NeoKeyManager]");
    }

    @Test
    void testErrorWithSource() {
        ServerLogger.errorWithSource("TestSource", "nkm.error.test");

        String output = errContent.toString();
        assertThat(output).contains("TestSource");
    }

    @Test
    void testErrorWithException() {
        Exception testException = new RuntimeException("Test exception");
        ServerLogger.error("TestSource", "nkm.error.test", testException);

        String output = errContent.toString();
        assertThat(output).contains("Test exception");
    }

    @Test
    void testGetMessage() {
        String message = ServerLogger.getMessage("nkm.system.init");

        assertThat(message).isNotNull();
        assertThat(message).isNotEqualTo("!!! Key Not Found: nkm.system.init !!!");
    }

    @Test
    void testGetMessageWithArgs() {
        String message = ServerLogger.getMessage("nkm.system.startedHttp", 8080);

        assertThat(message).contains("8080");
    }

    @Test
    void testGetMessageNonExistent() {
        String message = ServerLogger.getMessage("non.existent.key");

        assertThat(message).contains("Key Not Found");
    }

    @Test
    void testGetMessageWithNumberFormatting() {
        // 测试数字格式化（避免千位分隔符）
        // 使用已存在的key
        String message = ServerLogger.getMessage("nkm.system.startedHttp", 1000);

        assertThat(message).contains("1000");
    }

    @Test
    void testGetMessageWithMultipleArgs() {
        // 使用已存在的key进行测试
        String message = ServerLogger.getMessage("nkm.system.startedHttp", 8080);

        assertThat(message).contains("8080");
    }

    @Test
    void testAlertFlag() {
        // 测试alert标志
        boolean originalAlert = ServerLogger.alert;
        ServerLogger.alert = true;

        assertThat(ServerLogger.alert).isTrue();

        ServerLogger.alert = false;
        assertThat(ServerLogger.alert).isFalse();

        ServerLogger.alert = originalAlert;
    }

    @Test
    void testLocaleSwitching() {
        // 测试切换locale
        ServerLogger.setLocale(Locale.US);
        String usMessage = ServerLogger.getMessage("nkm.system.init");

        ServerLogger.setLocale(Locale.SIMPLIFIED_CHINESE);
        String cnMessage = ServerLogger.getMessage("nkm.system.init");

        // 消息应该不同（如果资源文件中有对应的翻译）
        // 如果没有翻译，可能相同
    }

    @Test
    void testNullLocale() {
        // 测试null locale（应该使用默认locale）
        ServerLogger.setLocale(null);
        // 不应该抛出异常
    }

    @Test
    void testEmptyKey() {
        String message = ServerLogger.getMessage("");

        assertThat(message).contains("Key Not Found");
    }

    @Test
    void testNullKey() {
        String message = ServerLogger.getMessage(null);

        assertThat(message).isEqualTo("!!! Null Key !!!");
    }

    @Test
    void testSpecialCharactersInArgs() {
        // 使用已存在的key测试特殊字符参数
        ServerLogger.info("nkm.system.startedHttp", "@#$%^&*()");

        String output = outContent.toString();
        assertThat(output).contains("@#$%^&*()");
    }

    @Test
    void testUnicodeInArgs() {
        // 使用已存在的key测试中文参数
        ServerLogger.info("nkm.system.startedHttp", "中文测试");

        String output = outContent.toString();
        assertThat(output).contains("中文测试");
    }

    @Test
    void testLongMessage() {
        String longArg = "a".repeat(1000);
        ServerLogger.info("nkm.system.startedHttp", longArg);

        String output = outContent.toString();
        assertThat(output).contains(longArg);
    }

    @Test
    void testEmptyArgs() {
        String message = ServerLogger.getMessage("nkm.system.init");

        assertThat(message).isNotNull();
    }

    @Test
    void testNestedException() {
        Exception inner = new RuntimeException("Inner");
        Exception outer = new RuntimeException("Outer", inner);

        ServerLogger.error("Test", "nkm.error.test", outer);

        String output = errContent.toString();
        assertThat(output).contains("Outer");
        assertThat(output).contains("Inner");
    }

    @Test
    void allProductionServerLoggerKeysExistInBothBundles() throws IOException {
        Set<String> codeKeys = new TreeSet<>();
        Pattern withSourcePattern = Pattern.compile(
                "ServerLogger\\.(?:infoWithSource|warnWithSource|errorWithSource)\\(\"[^\"]+\"\\s*,\\s*\"([^\"]+)\"");
        Pattern plainPattern = Pattern.compile(
                "ServerLogger\\.(?:info|warn|error)\\((?:\"[^\"]+\"\\s*,\\s*)?\"([^\"]+)\"");
        Pattern getMessagePattern = Pattern.compile(
                "ServerLogger\\.getMessage\\(\"([^\"]+)\"");

        try (var stream = Files.walk(Path.of("src/main/java"))) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String content = Files.readString(path);
                    collectMatches(codeKeys, withSourcePattern.matcher(content));
                    collectMatches(codeKeys, plainPattern.matcher(content));
                    collectMatches(codeKeys, getMessagePattern.matcher(content));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        Properties en = new Properties();
        Properties zh = new Properties();
        try (var enStream = Files.newInputStream(Path.of("src/main/resources/messages_en.properties"));
             var zhStream = Files.newInputStream(Path.of("src/main/resources/messages_zh.properties"))) {
            en.load(enStream);
            zh.load(zhStream);
        }

        Set<String> missingInEn = new TreeSet<>();
        Set<String> missingInZh = new TreeSet<>();
        for (String key : codeKeys) {
            if (!en.containsKey(key)) {
                missingInEn.add(key);
            }
            if (!zh.containsKey(key)) {
                missingInZh.add(key);
            }
        }

        assertThat(missingInEn).as("missing keys in messages_en.properties").isEmpty();
        assertThat(missingInZh).as("missing keys in messages_zh.properties").isEmpty();
    }

    private static void collectMatches(Set<String> target, Matcher matcher) {
        while (matcher.find()) {
            target.add(matcher.group(1));
        }
    }
}
