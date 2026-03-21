package neoproxy.neokeymanager.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionManager 单元测试
 */
class SessionManagerTest {

    private SessionManager sessionManager;
    private Path tempDir;
    private String originalAuthFile;

    @BeforeEach
    void setUp() throws Exception {
        // 创建临时目录用于 NodeAuthManager
        tempDir = Files.createTempDirectory("session_test");
        originalAuthFile = System.getProperty("node.auth.file", "nodes.json");
        System.setProperty("node.auth.file", tempDir.resolve("nodes.json").toString());
        
        // 创建认证文件，注册测试节点
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}, \"node2\": {\"realId\": \"node2\", \"displayName\": \"Node Two\"}}");
        
        // 重置 NodeAuthManager
        NodeAuthManager.resetInstance();
        NodeAuthManager.getInstance().load();
        
        // 重置 SessionManager
        SessionManager.resetInstance();
        
        // 获取新的单例实例
        sessionManager = SessionManager.getInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 测试结束后清理
        clearAllSessions();
        
        // 恢复配置
        System.setProperty("node.auth.file", originalAuthFile);
        
        // 清理临时文件
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
    
    private void createAuthFile(String content) throws Exception {
        File file = new File(System.getProperty("node.auth.file"));
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private void clearAllSessions() throws Exception {
        // 通过反射清除会话数据
        Field sessionsField = SessionManager.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        ConcurrentHashMap<?, ?> sessions = (ConcurrentHashMap<?, ?>) sessionsField.get(sessionManager);
        if (sessions != null) {
            sessions.clear();
        }
    }

    // ==================== 端口分配测试 ====================

    @Test
    void testFindFirstFreePort() {
        String port = sessionManager.findFirstFreePort("key1", "8080-8090", "node1");

        assertThat(port).isEqualTo("8080");
    }

    @Test
    void testFindFirstFreePortWithUsedPort() {
        // 注册第一个会话占用8080
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        // 同一节点上，8080已被占用，应该返回8081
        String port = sessionManager.findFirstFreePort("key1", "8080-8090", "node1");

        assertThat(port).isEqualTo("8081");
    }

    @Test
    void testFindFirstFreePortAllUsed() {
        // 在同一节点上占用所有端口
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 2, false);
        sessionManager.tryRegisterSession("key1", "displayKey2", "node1", "8081", 2, false);

        String port = sessionManager.findFirstFreePort("key1", "8080-8081", "node1");

        assertThat(port).isNull();
    }

    @Test
    void testFindFirstFreePortEmptyRange() {
        String port = sessionManager.findFirstFreePort("key1", "", "node1");

        // 空字符串返回空字符串（代码行为）
        assertThat(port).isEmpty();
    }

    @Test
    void testFindFirstFreePortSinglePort() {
        String port = sessionManager.findFirstFreePort("key1", "8080", "node1");

        assertThat(port).isEqualTo("8080");
    }

    // ==================== 会话注册测试 ====================

    @Test
    void testTryRegisterSession() {
        boolean result = sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        assertThat(result).isTrue();
        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(1);
    }

    @Test
    void testTryRegisterSessionWithConnectionDetail() {
        boolean result = sessionManager.tryRegisterSession(
                "key1", "displayKey1", "node1", "8080", 5, false, "192.168.1.1:12345"
        );

        assertThat(result).isTrue();
    }

    @Test
    void testTryRegisterSessionDuplicate() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        // 重复注册同一节点和displayKey会刷新端口，返回true
        boolean result = sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        assertThat(result).isTrue();
    }

    @Test
    void testTryRegisterSessionMaxConnections() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 1, false);

        // 超过最大连接数应该失败
        boolean result = sessionManager.tryRegisterSession("key1", "displayKey2", "node2", "8081", 1, false);

        assertThat(result).isFalse();
    }

    @Test
    void testTryRegisterSessionWithAliasSingle() {
        boolean result = sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, true);

        assertThat(result).isTrue();
    }

    // ==================== 心跳测试 ====================

    @Test
    void testKeepAlive() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        boolean result = sessionManager.keepAlive("key1", "serial1", "node1", "8080", 5, false);

        assertThat(result).isTrue();
    }

    @Test
    void testKeepAliveWithConnectionDetail() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        boolean result = sessionManager.keepAlive(
                "key1", "serial1", "node1", "8080", 5, false, "192.168.1.1:12345"
        );

        assertThat(result).isTrue();
    }

    @Test
    void testKeepAliveNonExistentSession() {
        // keepAlive 在 session 不存在时会尝试创建新会话，如果节点已认证则返回 true
        boolean result = sessionManager.keepAlive("key1", "displayKey1", "node1", "8080", 5, false);

        assertThat(result).isTrue();
    }

    // ==================== 端口状态测试 ====================

    @Test
    void testIsSpecificPortActive() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        assertThat(sessionManager.isSpecificPortActive("key1", "node1", "8080")).isTrue();
        assertThat(sessionManager.isSpecificPortActive("key1", "node1", "8081")).isFalse();
    }

    @Test
    void testIsSpecificPortActiveNonExistentKey() {
        assertThat(sessionManager.isSpecificPortActive("nonExistent", "node1", "8080")).isFalse();
    }

    // ==================== 会话计数测试 ====================

    @Test
    void testGetActiveCount() {
        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(0);

        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);
        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(1);

        sessionManager.tryRegisterSession("key1", "displayKey2", "node2", "8081", 5, false);
        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(2);
    }

    @Test
    void testGetActiveCountDifferentKeys() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);
        sessionManager.tryRegisterSession("key2", "displayKey2", "node2", "8081", 5, false);

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(1);
        assertThat(sessionManager.getActiveCount("key2")).isEqualTo(1);
    }

    // ==================== 会话释放测试 ====================

    @Test
    void testReleaseSession() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);
        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(1);

        sessionManager.releaseSession("key1", "node1", "displayKey1");

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(0);
    }

    @Test
    void testReleaseSessionWithoutDisplayKey() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        sessionManager.releaseSession("key1", "node1");

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(0);
    }

    @Test
    void testReleaseNonExistentSession() {
        // 释放不存在的会话不应该抛出异常
        sessionManager.releaseSession("key1", "node1", "displayKey1");

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(0);
    }

    @Test
    void testReleaseAllSessionsForNode() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);
        sessionManager.tryRegisterSession("key2", "displayKey2", "node1", "8081", 5, false);

        sessionManager.releaseAllSessionsForNode("node1");

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(0);
        assertThat(sessionManager.getActiveCount("key2")).isEqualTo(0);
    }

    @Test
    void testForceReleaseKey() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);
        sessionManager.tryRegisterSession("key1", "displayKey2", "node2", "8081", 5, false);

        sessionManager.forceReleaseKey("key1");

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(0);
    }

    // ==================== 快照测试 ====================

    @Test
    void testGetActiveSessionsSnapshot() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        Map<String, Map<String, String>> snapshot = sessionManager.getActiveSessionsSnapshot();

        // getActiveSessionsSnapshot 使用 displayKey 作为外层 key
        assertThat(snapshot).containsKey("displayKey1");
    }

    @Test
    void testGetActiveSessionsSnapshotEmpty() {
        Map<String, Map<String, String>> snapshot = sessionManager.getActiveSessionsSnapshot();

        assertThat(snapshot).isEmpty();
    }

    // ==================== 边界测试 ====================

    @Test
    void testTryRegisterSessionWithZeroMaxConnections() {
        // maxConnections=0 时，countTotalPorts(0) >= 0 为 true，所以返回 false
        boolean result = sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 0, false);

        assertThat(result).isFalse();
    }

    @Test
    void testMultipleSessionsSameKeyDifferentNodes() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);
        sessionManager.tryRegisterSession("key1", "displayKey2", "node2", "8081", 5, false);

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(2);
    }

    @Test
    void testSessionPersistenceAcrossOperations() {
        sessionManager.tryRegisterSession("key1", "displayKey1", "node1", "8080", 5, false);

        // 多次心跳 - 使用相同的 displayKey
        assertThat(sessionManager.keepAlive("key1", "displayKey1", "node1", "8080", 5, false)).isTrue();
        assertThat(sessionManager.keepAlive("key1", "displayKey1", "node1", "8080", 5, false)).isTrue();

        assertThat(sessionManager.getActiveCount("key1")).isEqualTo(1);
    }
}
