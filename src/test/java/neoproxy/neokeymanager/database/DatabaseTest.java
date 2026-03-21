package neoproxy.neokeymanager.database;

import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.model.DTOs.KeyStateResult;
import neoproxy.neokeymanager.model.DTOs.KeyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database 类单元测试
 * 使用 SQLite 内存数据库进行测试
 */
class DatabaseTest {

    private String originalDbPath;

    @BeforeEach
    void setUp() throws Exception {
        // 保存原始数据库路径
        originalDbPath = Config.DB_PATH;

        // 使用内存数据库进行测试 - 每个测试使用独立的数据库
        Config.DB_PATH = ":memory:" + System.nanoTime();

        // 清除缓存
        clearCache();

        // 初始化数据库
        Database.init();
    }

    @AfterEach
    void tearDown() {
        // 恢复原始数据库路径
        Config.DB_PATH = originalDbPath;
    }

    private void clearCache() throws Exception {
        // 通过反射清除静态缓存
        Field stateCacheField = Database.class.getDeclaredField("stateCache");
        stateCacheField.setAccessible(true);
        ConcurrentHashMap<?, ?> stateCache = (ConcurrentHashMap<?, ?>) stateCacheField.get(null);
        if (stateCache != null) {
            stateCache.clear();
        }

        Field trafficBufferField = Database.class.getDeclaredField("trafficBuffer");
        trafficBufferField.setAccessible(true);
        ConcurrentHashMap<?, ?> trafficBuffer = (ConcurrentHashMap<?, ?>) trafficBufferField.get(null);
        if (trafficBuffer != null) {
            trafficBuffer.clear();
        }
    }

    // ==================== 基本 CRUD 测试 ====================

    @Test
    void testAddKey() {
        boolean result = Database.addKey("testKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(result).isTrue();
        assertThat(Database.keyExists("testKey")).isTrue();
    }

    @Test
    void testAddKeyDuplicate() {
        Database.addKey("testKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        // 添加重复key应该失败
        boolean result = Database.addKey("testKey", 200.0, 0.02, "2025/12/31-23:59", "9090", 10);

        assertThat(result).isFalse();
    }

    @Test
    void testKeyExists() {
        assertThat(Database.keyExists("nonExistent")).isFalse();

        Database.addKey("existingKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(Database.keyExists("existingKey")).isTrue();
    }

    @Test
    void testDeleteKey() {
        Database.addKey("deleteKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        assertThat(Database.keyExists("deleteKey")).isTrue();

        Database.deleteKey("deleteKey");

        assertThat(Database.keyExists("deleteKey")).isFalse();
    }

    @Test
    void testDeleteNonExistentKey() {
        // 删除不存在的key不应该抛出异常
        Database.deleteKey("nonExistent");

        assertThat(Database.keyExists("nonExistent")).isFalse();
    }

    // ==================== 查询测试 ====================

    @Test
    void testGetKeyStatus() {
        Database.addKey("statusKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        KeyStateResult result = Database.getKeyStatus("statusKey");

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(KeyStatus.ENABLED);
    }

    @Test
    void testGetKeyStatusNonExistent() {
        KeyStateResult result = Database.getKeyStatus("nonExistent");

        assertThat(result).isNull();
    }

    @Test
    void testGetRealKeyName() {
        Database.addKey("realKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        String name = Database.getRealKeyName("realKey");

        assertThat(name).isEqualTo("realKey");
    }

    @Test
    void testGetRealKeyNameNonExistent() {
        String name = Database.getRealKeyName("nonExistent");

        assertThat(name).isNull();
    }

    // ==================== 更新测试 ====================

    @Test
    void testUpdateKeyBalance() {
        Database.addKey("updateKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.updateKey("updateKey", 200.0, null, null, null, null, null);

        Map<String, Object> info = Database.getKeyInfoFull("updateKey", null);
        assertThat(info.get("balance")).isEqualTo(200.0);
    }

    @Test
    void testUpdateKeyRate() {
        Database.addKey("updateRateKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.updateKey("updateRateKey", null, 0.05, null, null, null, null);

        Map<String, Object> info = Database.getKeyInfoFull("updateRateKey", null);
        assertThat(info.get("rate")).isEqualTo(0.05);
    }

    @Test
    void testUpdateKeyPort() {
        Database.addKey("updatePortKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.updateKey("updatePortKey", null, null, "9090", null, null, null);

        Map<String, Object> info = Database.getKeyInfoFull("updatePortKey", null);
        assertThat(info.get("default_port")).isEqualTo("9090");
    }

    @Test
    void testUpdateKeyMultipleFields() {
        Database.addKey("multiUpdateKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.updateKey("multiUpdateKey", 200.0, 0.02, "9090", "2025/12/31-23:59", true, 10);

        Map<String, Object> info = Database.getKeyInfoFull("multiUpdateKey", null);
        assertThat(info.get("balance")).isEqualTo(200.0);
        assertThat(info.get("rate")).isEqualTo(0.02);
        assertThat(info.get("default_port")).isEqualTo("9090");
    }

    // ==================== 状态测试 ====================

    @Test
    void testSetKeyStatusStrict() {
        Database.addKey("statusKey2", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.setKeyStatusStrict("statusKey2", false);

        KeyStateResult result = Database.getKeyStatus("statusKey2");
        assertThat(result.status()).isEqualTo(KeyStatus.DISABLED);
    }

    @Test
    void testSetWebStatus() {
        Database.addKey("webKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.setWebStatus("webKey", true);

        Map<String, Object> info = Database.getKeyInfoFull("webKey", null);
        assertThat(info.get("enableWebHTML")).isEqualTo(true);
    }

    // ==================== 别名测试 ====================

    @Test
    void testAddLink() {
        Database.addKey("realKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.addLink("alias1", "realKey");

        assertThat(Database.isAlias("alias1")).isTrue();
        assertThat(Database.getRealKeyName("alias1")).isEqualTo("realKey");
    }

    @Test
    void testIsAlias() {
        Database.addKey("realKey2", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addLink("alias3", "realKey2");

        assertThat(Database.isAlias("alias3")).isTrue();
        assertThat(Database.isAlias("realKey2")).isFalse();
        assertThat(Database.isAlias("nonExistent")).isFalse();
    }

    @Test
    void testGetRealKeyNameWithAlias() {
        Database.addKey("realKey3", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addLink("alias4", "realKey3");

        assertThat(Database.getRealKeyName("realKey3")).isEqualTo("realKey3");
        assertThat(Database.getRealKeyName("alias4")).isEqualTo("realKey3");
    }

    @Test
    void testDeleteAlias() {
        Database.addKey("realKey4", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addLink("alias5", "realKey4");
        assertThat(Database.isAlias("alias5")).isTrue();

        boolean result = Database.deleteAlias("alias5");

        assertThat(result).isTrue();
        assertThat(Database.isAlias("alias5")).isFalse();
    }

    @Test
    void testGetAllLinks() {
        Database.addKey("realKey5", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addLink("alias6", "realKey5");
        Database.addLink("alias7", "realKey5");

        Map<String, String> links = Database.getAllLinks();

        assertThat(links).hasSize(2);
        assertThat(links.get("alias6")).isEqualTo("realKey5");
        assertThat(links.get("alias7")).isEqualTo("realKey5");
    }

    // ==================== 节点端口映射测试 ====================

    @Test
    void testAddNodePort() {
        Database.addKey("mapKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.addNodePort("mapKey", "node1", "9090");

        Map<String, Object> info = Database.getKeyInfoFull("mapKey", "node1");
        assertThat(info.get("default_port")).isEqualTo("9090");
    }

    @Test
    void testDeleteNodeMap() {
        Database.addKey("mapKey2", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addNodePort("mapKey2", "node1", "9090");

        boolean result = Database.deleteNodeMap("mapKey2", "node1");

        assertThat(result).isTrue();
    }

    @Test
    void testHasSpecificMap() {
        Database.addKey("mapKey4", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addNodePort("mapKey4", "node1", "9090");

        assertThat(Database.hasSpecificMap("mapKey4", "node1")).isTrue();
        assertThat(Database.hasSpecificMap("mapKey4", "node2")).isFalse();
    }

    @Test
    void testIsNodeMappedAnywhere() {
        Database.addKey("mapKey5", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addNodePort("mapKey5", "node1", "9090");

        assertThat(Database.isNodeMappedAnywhere("node1")).isTrue();
        assertThat(Database.isNodeMappedAnywhere("node2")).isFalse();
    }

    // ==================== 批量操作测试 ====================

    @Test
    void testGetAllKeysRaw() {
        Database.addKey("key1", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addKey("key2", 200.0, 0.02, "2025/12/31-23:59", "9090", 10);

        List<Map<String, String>> keys = Database.getAllKeysRaw();

        assertThat(keys).hasSize(2);
    }

    @Test
    void testGetAllKeysStructured() {
        Database.addKey("detailKey1", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        var details = Database.getAllKeysStructured(true, null);

        assertThat(details).hasSize(1);
    }

    // ==================== 流量扣费测试 ====================

    @Test
    void testDeductBalanceBatch() {
        Database.addKey("trafficKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Map<String, Double> trafficMap = new HashMap<>();
        trafficMap.put("trafficKey", 30.0);

        Database.deductBalanceBatch(trafficMap);

        // 批量扣费是异步的，通过缓冲区处理
        // 这里主要验证方法不抛出异常
    }

    // ==================== 自定义消息测试 ====================

    @Test
    void testSetCustomBlockingMsg() {
        Database.addKey("msgKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        boolean result = Database.setCustomBlockingMsg("msgKey", "Custom message");

        assertThat(result).isTrue();
        assertThat(Database.getCustomBlockingMsg("msgKey")).isEqualTo("Custom message");
    }

    @Test
    void testGetCustomBlockingMsgDefault() {
        Database.addKey("msgKey2", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(Database.getCustomBlockingMsg("msgKey2")).isNull();
    }

    @Test
    void testGetAllCustomBlockingMsgs() {
        Database.addKey("msgKey3", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.setCustomBlockingMsg("msgKey3", "Message 1");

        Map<String, String> msgs = Database.getAllCustomBlockingMsgs();

        assertThat(msgs).containsKey("msgKey3");
        assertThat(msgs.get("msgKey3")).isEqualTo("Message 1");
    }

    // ==================== 单例key测试 ====================

    @Test
    void testSetKeySingle() {
        Database.addKey("singleKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        boolean result = Database.setKeySingle("singleKey", true);

        assertThat(result).isTrue();
        assertThat(Database.isNameSingle("singleKey")).isTrue();
    }

    @Test
    void testIsNameSingleDefault() {
        Database.addKey("nonSingleKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(Database.isNameSingle("nonSingleKey")).isFalse();
    }

    @Test
    void testGetSingleKeys() {
        Database.addKey("singleKey1", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addKey("singleKey2", 200.0, 0.02, "2025/12/31-23:59", "9090", 10);
        Database.setKeySingle("singleKey1", true);
        Database.setKeySingle("singleKey2", true);

        List<String> singleKeys = Database.getSingleKeys();

        // getSingleKeys 返回格式为 "keyName (RealKey)"
        assertThat(singleKeys).contains("singleKey1 (RealKey)", "singleKey2 (RealKey)");
    }

    // ==================== 重命名测试 ====================

    @Test
    void testRenameKey() {
        Database.addKey("oldName", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        boolean result = Database.renameKey("oldName", "newName");

        assertThat(result).isTrue();
        assertThat(Database.keyExists("oldName")).isFalse();
        assertThat(Database.keyExists("newName")).isTrue();
    }

    @Test
    void testRenameKeyToExistingName() {
        Database.addKey("name1", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addKey("name2", 200.0, 0.02, "2025/12/31-23:59", "9090", 10);

        boolean result = Database.renameKey("name1", "name2");

        assertThat(result).isFalse();
    }

    @Test
    void testRenameNonExistentKey() {
        boolean result = Database.renameKey("nonExistent", "newName");

        assertThat(result).isFalse();
    }

    // ==================== 最大连接数测试 ====================

    @Test
    void testSetKeyMaxConns() {
        Database.addKey("connKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        boolean result = Database.setKeyMaxConns("connKey", 20);

        assertThat(result).isTrue();
        assertThat(Database.getKeyMaxConns("connKey")).isEqualTo(20);
    }

    @Test
    void testGetKeyMaxConnsDefault() {
        Database.addKey("defaultConnKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(Database.getKeyMaxConns("defaultConnKey")).isEqualTo(5);
    }

    // ==================== 批量元数据测试 ====================

    @Test
    void testGetBatchKeyMetadata() {
        Database.addKey("metaKey1", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addKey("metaKey2", 200.0, 0.02, "2025/12/31-23:59", "9090", 10);

        List<String> keys = Arrays.asList("metaKey1", "metaKey2");
        Map<String, neoproxy.neokeymanager.model.Protocol.KeyMetadata> metadata = Database.getBatchKeyMetadata(keys);

        assertThat(metadata).hasSize(2);
        assertThat(metadata.get("metaKey1")).isNotNull();
        assertThat(metadata.get("metaKey2")).isNotNull();
    }

    // ==================== 边界测试 ====================

    @Test
    void testAddKeyWithZeroBalance() {
        boolean result = Database.addKey("zeroBalKey", 0.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(result).isTrue();
        Map<String, Object> info = Database.getKeyInfoFull("zeroBalKey", null);
        assertThat(info.get("balance")).isEqualTo(0.0);
    }

    @Test
    void testAddKeyWithNegativeBalance() {
        boolean result = Database.addKey("negKey", -100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(result).isTrue();
        Map<String, Object> info = Database.getKeyInfoFull("negKey", null);
        assertThat(info.get("balance")).isEqualTo(-100.0);
    }

    @Test
    void testAddKeyWithZeroRate() {
        boolean result = Database.addKey("zeroRateKey", 100.0, 0.0, "2030/12/31-23:59", "8080", 5);

        assertThat(result).isTrue();
        Map<String, Object> info = Database.getKeyInfoFull("zeroRateKey", null);
        assertThat(info.get("rate")).isEqualTo(0.0);
    }

    @Test
    void testAddKeyWithZeroMaxConns() {
        boolean result = Database.addKey("zeroConnKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 0);

        assertThat(result).isTrue();
        assertThat(Database.getKeyMaxConns("zeroConnKey")).isEqualTo(0);
    }

    @Test
    void testAddKeyWithEmptyExpireTime() {
        boolean result = Database.addKey("noExpireKey", 100.0, 0.01, "", "8080", 5);

        assertThat(result).isTrue();
        Map<String, Object> info = Database.getKeyInfoFull("noExpireKey", null);
        assertThat(info.get("expireTime")).isEqualTo("");
    }

    @Test
    void testAddKeyWithNullExpireTime() {
        boolean result = Database.addKey("nullExpireKey", 100.0, 0.01, null, "8080", 5);

        assertThat(result).isTrue();
        Map<String, Object> info = Database.getKeyInfoFull("nullExpireKey", null);
        assertThat(info.get("expireTime")).isNull();
    }

    @Test
    void testGetKeyPortInfo() {
        Database.addKey("portInfoKey", 100.0, 0.01, "2030/12/31-23:59", "8080-8090", 5);

        Map<String, Object> portInfo = Database.getKeyPortInfo("portInfoKey");

        assertThat(portInfo).isNotNull();
        assertThat(portInfo.get("default_port")).isEqualTo("8080-8090");
    }

    @Test
    void testDeleteNodeMapByNode() {
        Database.addKey("deleteByNodeKey", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);
        Database.addNodePort("deleteByNodeKey", "node1", "9090");

        int deleted = Database.deleteNodeMapByNode("node1");

        assertThat(deleted).isEqualTo(1);
    }

    // ==================== 特殊字符测试 ====================

    @Test
    void testAddKeyWithSpecialCharacters() {
        boolean result = Database.addKey("key@#$%", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(result).isTrue();
        assertThat(Database.keyExists("key@#$%")).isTrue();
    }

    @Test
    void testAddKeyWithUnicode() {
        boolean result = Database.addKey("中文测试", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        assertThat(result).isTrue();
        assertThat(Database.keyExists("中文测试")).isTrue();
    }

    @Test
    void testAddLinkWithUnicode() {
        Database.addKey("realUnicode", 100.0, 0.01, "2030/12/31-23:59", "8080", 5);

        Database.addLink("别名测试", "realUnicode");

        assertThat(Database.isAlias("别名测试")).isTrue();
    }
}
