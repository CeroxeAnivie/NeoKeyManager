package neoproxy.neokeymanager.manager;

import neoproxy.neokeymanager.manager.NodeAuthManager.NodeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NodeAuthManager 单元测试
 */
class NodeAuthManagerTest {

    private Path tempDir;
    private String originalAuthFile;
    private NodeAuthManager authManager;

    @BeforeEach
    void setUp() throws Exception {
        // 创建临时目录
        tempDir = Files.createTempDirectory("nodeauth_test");
        originalAuthFile = System.getProperty("node.auth.file", "nodes.json");
        System.setProperty("node.auth.file", tempDir.resolve("nodes.json").toString());

        // 重置单例
        resetSingleton();

        authManager = NodeAuthManager.getInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
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

    private void resetSingleton() throws Exception {
        // 使用测试专用的重置方法
        NodeAuthManager.resetInstance();
    }

    private void createAuthFile(String content) throws Exception {
        File file = new File(System.getProperty("node.auth.file"));
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    // ==================== 认证测试 ====================

    @Test
    void testAuthenticateAndGetAlias() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("node1");

        assertThat(alias).isEqualTo("Node One");
    }

    @Test
    void testAuthenticateAndGetAliasCaseInsensitive() throws Exception {
        createAuthFile("{\"NODE1\": {\"realId\": \"NODE1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("node1");

        assertThat(alias).isEqualTo("Node One");
    }

    @Test
    void testAuthenticateAndGetAliasNonExistent() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("nonExistent");

        assertThat(alias).isNull();
    }

    @Test
    void testAuthenticateAndGetAliasEmptyFile() throws Exception {
        createAuthFile("{}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("node1");

        assertThat(alias).isNull();
    }

    @Test
    void testAuthenticateAndGetAliasNoFile() throws Exception {
        // 不创建文件直接加载
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("node1");

        assertThat(alias).isNull();
    }

    // ==================== 别名查询测试 ====================

    @Test
    void testGetAlias() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.getAlias("node1");

        assertThat(alias).isEqualTo("Node One");
    }

    @Test
    void testGetAliasNonExistent() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.getAlias("nonExistent");

        // getAlias 在找不到时返回传入的 realNodeId
        assertThat(alias).isEqualTo("nonExistent");
    }

    // ==================== 反向查询测试 ====================

    @Test
    void testGetRealIdByDisplayName() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String realId = authManager.getRealIdByDisplayName("Node One");

        assertThat(realId).isEqualTo("node1");
    }

    @Test
    void testGetRealIdByDisplayNameNonExistent() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String realId = authManager.getRealIdByDisplayName("Non Existent");

        assertThat(realId).isNull();
    }

    // ==================== 注册节点列表测试 ====================

    @Test
    void testGetAllRegisteredNodes() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}, \"node2\": {\"realId\": \"node2\", \"displayName\": \"Node Two\"}}");
        authManager.load();

        List<NodeConfig> nodes = authManager.getAllRegisteredNodes();

        assertThat(nodes).hasSize(2);
    }

    @Test
    void testGetAllRegisteredNodesEmpty() throws Exception {
        createAuthFile("{}");
        authManager.load();

        List<NodeConfig> nodes = authManager.getAllRegisteredNodes();

        assertThat(nodes).isEmpty();
    }

    @Test
    void testGetAllRegisteredNodesNoFile() throws Exception {
        authManager.load();

        List<NodeConfig> nodes = authManager.getAllRegisteredNodes();

        assertThat(nodes).isEmpty();
    }

    // ==================== 添加节点测试 ====================

    @Test
    void testAddNodeToAllowlist() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist("node1", "Node One");

        assertThat(authManager.getAlias("node1")).isEqualTo("Node One");
    }

    @Test
    void testAddNodeToAllowlistDuplicateRealId() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist("node1", "Node One");
        // 重复添加相同realId应该更新displayName
        authManager.addNodeToAllowlist("node1", "Updated Node");

        assertThat(authManager.getAlias("node1")).isEqualTo("Updated Node");
    }

    @Test
    void testAddNodeToAllowlistDuplicateDisplayName() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist("node1", "Node One");
        // 展示名不是稳定身份，重复展示名不能再作为反向映射依据。
        authManager.addNodeToAllowlist("node2", "Node One");

        // 验证两个节点都存在
        assertThat(authManager.getAllRegisteredNodes()).hasSize(2);
        assertThat(authManager.getRealIdByDisplayName("Node One")).isNull();
    }

    @Test
    void testAddNodeToAllowlistPersistence() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist("node1", "Node One");

        // 重新加载验证持久化
        resetSingleton();
        authManager = NodeAuthManager.getInstance();
        authManager.load();

        assertThat(authManager.getAlias("node1")).isEqualTo("Node One");
    }

    // ==================== 显式注册检查测试 ====================

    @Test
    void testIsNodeExplicitlyRegistered() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        assertThat(authManager.isNodeExplicitlyRegistered("node1")).isTrue();
        assertThat(authManager.isNodeExplicitlyRegistered("nonExistent")).isFalse();
    }

    @Test
    void testIsNodeExplicitlyRegisteredCaseInsensitive() throws Exception {
        createAuthFile("{\"NODE1\": {\"realId\": \"NODE1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        assertThat(authManager.isNodeExplicitlyRegistered("node1")).isTrue();
    }

    @Test
    void testIsNodeExplicitlyRegisteredEmpty() throws Exception {
        createAuthFile("{}");
        authManager.load();

        assertThat(authManager.isNodeExplicitlyRegistered("node1")).isFalse();
    }

    // ==================== 加载测试 ====================

    @Test
    void testLoadMultipleTimes() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        // 第二次加载应该刷新数据
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Updated\"}, \"node2\": {\"realId\": \"node2\", \"displayName\": \"Node Two\"}}");
        authManager.load();

        assertThat(authManager.getAlias("node1")).isEqualTo("Updated");
        assertThat(authManager.getAllRegisteredNodes()).hasSize(2);
    }

    @Test
    void testLoadInvalidJson() throws Exception {
        createAuthFile("invalid json");

        // 加载无效JSON不应该抛出异常
        authManager.load();

        assertThat(authManager.getAllRegisteredNodes()).isEmpty();
    }

    @Test
    void testLoadMalformedArray() throws Exception {
        createAuthFile("{\"not\":\"an array\"}");

        authManager.load();

        assertThat(authManager.getAllRegisteredNodes()).isEmpty();
    }

    // ==================== 边界测试 ====================

    @Test
    void testAuthenticateWithEmptyRealId() throws Exception {
        createAuthFile("{\"\": {\"realId\": \"\", \"displayName\": \"Empty Node\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("");

        assertThat(alias).isNull();
        assertThat(authManager.getAllRegisteredNodes()).isEmpty();
    }

    @Test
    void testAuthenticateWithNullRealId() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias(null);

        assertThat(alias).isNull();
    }

    @Test
    void testAddNodeWithEmptyStrings() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist("", "");

        assertThat(authManager.getAllRegisteredNodes()).isEmpty();
    }

    @Test
    void testAddNodeWithNullValues() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist(null, null);

        // 应该处理null值而不抛出异常
        assertThat(authManager.getAllRegisteredNodes()).isEmpty();
    }

    @Test
    void testGetAliasWithNull() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String alias = authManager.getAlias(null);

        // getAlias 对 null 返回 "Unknown"
        assertThat(alias).isEqualTo("Unknown");
    }

    @Test
    void testGetRealIdByDisplayNameWithNull() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        String realId = authManager.getRealIdByDisplayName(null);

        assertThat(realId).isNull();
    }

    // ==================== 特殊字符测试 ====================

    @Test
    void testAuthenticateWithSpecialCharacters() throws Exception {
        createAuthFile("{\"node@#$%\": {\"realId\": \"node@#$%\", \"displayName\": \"Special Node\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("node@#$%");

        assertThat(alias).isEqualTo("Special Node");
    }

    @Test
    void testAuthenticateWithUnicode() throws Exception {
        createAuthFile("{\"中文节点\": {\"realId\": \"中文节点\", \"displayName\": \"中文显示\"}}");
        authManager.load();

        String alias = authManager.authenticateAndGetAlias("中文节点");

        assertThat(alias).isEqualTo("中文显示");
    }

    @Test
    void testAddNodeWithUnicode() throws Exception {
        authManager.load();

        authManager.addNodeToAllowlist("中文节点", "中文显示");

        assertThat(authManager.getAlias("中文节点")).isEqualTo("中文显示");
    }

    // ==================== 并发测试 ====================

    @Test
    void testConcurrentAuthentication() throws Exception {
        createAuthFile("{\"node1\": {\"realId\": \"node1\", \"displayName\": \"Node One\"}}");
        authManager.load();

        // 模拟并发认证
        for (int i = 0; i < 100; i++) {
            String alias = authManager.authenticateAndGetAlias("node1");
            assertThat(alias).isEqualTo("Node One");
        }
    }

    @Test
    void testConcurrentAddNode() throws Exception {
        authManager.load();

        // 模拟并发添加
        for (int i = 0; i < 10; i++) {
            final int index = i;
            authManager.addNodeToAllowlist("node" + index, "Node " + index);
        }

        assertThat(authManager.getAllRegisteredNodes()).hasSize(10);
    }

    // ==================== 单例测试 ====================

    @Test
    void testSingleton() {
        NodeAuthManager instance1 = NodeAuthManager.getInstance();
        NodeAuthManager instance2 = NodeAuthManager.getInstance();

        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    void testSingletonAfterReset() throws Exception {
        NodeAuthManager instance1 = NodeAuthManager.getInstance();

        resetSingleton();

        NodeAuthManager instance2 = NodeAuthManager.getInstance();

        assertThat(instance1).isNotSameAs(instance2);
    }
}
