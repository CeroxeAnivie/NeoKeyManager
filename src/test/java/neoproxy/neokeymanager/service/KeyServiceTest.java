package neoproxy.neokeymanager.service;

import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KeyService 单元测试
 */
class KeyServiceTest {

    private KeyService keyService;
    private String originalDbPath;

    @BeforeEach
    void setUp() throws Exception {
        keyService = new KeyService();

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
        Field stateCacheField = Database.class.getDeclaredField("stateCache");
        stateCacheField.setAccessible(true);
        ConcurrentHashMap<?, ?> stateCache = (ConcurrentHashMap<?, ?>) stateCacheField.get(null);
        if (stateCache != null) {
            stateCache.clear();
        }
    }

    // ==================== execAddKey 测试 ====================

    @Test
    void testExecAddKey() {
        String result = keyService.execAddKey(Arrays.asList("testKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        assertThat(result).contains("testKey");
        assertThat(Database.keyExists("testKey")).isTrue();
    }

    @Test
    void testExecAddKeyWithWeb() {
        String result = keyService.execAddKey(Arrays.asList("webKey", "100.0", "2030/12/31-23:59", "8080", "0.01", "true"));

        assertThat(result).contains("webKey");
    }

    @Test
    void testExecAddKeyDuplicate() {
        keyService.execAddKey(Arrays.asList("dupKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        assertThatThrownBy(() -> keyService.execAddKey(Arrays.asList("dupKey", "200.0", "2025/12/31-23:59", "9090", "0.02")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecAddKeyEmptyName() {
        assertThatThrownBy(() -> keyService.execAddKey(Arrays.asList("", "100.0", "2030/12/31-23:59", "8080", "0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecAddKeyInvalidArgs() {
        assertThatThrownBy(() -> keyService.execAddKey(Arrays.asList("key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecAddKeyInvalidBalance() {
        assertThatThrownBy(() -> keyService.execAddKey(Arrays.asList("key", "invalid", "2030/12/31-23:59", "8080", "0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecAddKeyInvalidRate() {
        assertThatThrownBy(() -> keyService.execAddKey(Arrays.asList("key", "100.0", "2030/12/31-23:59", "8080", "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execSetKey 测试 ====================

    @Test
    void testExecSetKeyBalance() {
        keyService.execAddKey(Arrays.asList("setKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetKey(Arrays.asList("setKey", "b=200.0"));

        assertThat(result).contains("setKey");
    }

    @Test
    void testExecSetKeyRate() {
        keyService.execAddKey(Arrays.asList("setRateKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetKey(Arrays.asList("setRateKey", "r=0.05"));

        assertThat(result).contains("setRateKey");
    }

    @Test
    void testExecSetKeyPort() {
        keyService.execAddKey(Arrays.asList("setPortKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetKey(Arrays.asList("setPortKey", "p=9090"));

        assertThat(result).contains("setPortKey");
    }

    @Test
    void testExecSetKeyMultipleParams() {
        keyService.execAddKey(Arrays.asList("multiSetKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetKey(Arrays.asList("multiSetKey", "b=200.0", "r=0.05", "p=9090"));

        assertThat(result).contains("multiSetKey");
    }

    @Test
    void testExecSetKeyNonExistent() {
        assertThatThrownBy(() -> keyService.execSetKey(Arrays.asList("nonExistent", "b=200.0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecSetKeyInvalidArgs() {
        assertThatThrownBy(() -> keyService.execSetKey(Collections.singletonList("key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execDelKey 测试 ====================

    @Test
    void testExecDelKey() {
        keyService.execAddKey(Arrays.asList("delKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));
        assertThat(Database.keyExists("delKey")).isTrue();

        String result = keyService.execDelKey(Collections.singletonList("delKey"));

        assertThat(result).contains("delKey");
        assertThat(Database.keyExists("delKey")).isFalse();
    }

    @Test
    void testExecDelKeyNonExistent() {
        // 删除不存在的key不会抛出异常，而是返回失败信息
        String result = keyService.execDelKey(Collections.singletonList("nonExistent"));
        
        assertThat(result).contains("Failed").contains("Not Found");
    }

    @Test
    void testExecDelKeyInvalidArgs() {
        assertThatThrownBy(() -> keyService.execDelKey(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execWeb 测试 ====================

    @Test
    void testExecWebEnable() {
        keyService.execAddKey(Arrays.asList("webTestKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execWeb(Arrays.asList("enable", "webTestKey"));

        assertThat(result).contains("webTestKey");
    }

    @Test
    void testExecWebDisable() {
        keyService.execAddKey(Arrays.asList("webDisableKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execWeb(Arrays.asList("disable", "webDisableKey"));

        assertThat(result).contains("webDisableKey");
    }

    @Test
    void testExecWebNonExistent() {
        assertThatThrownBy(() -> keyService.execWeb(Arrays.asList("nonExistent", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecWebInvalidArgs() {
        assertThatThrownBy(() -> keyService.execWeb(Collections.singletonList("key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execSetConn 测试 ====================

    @Test
    void testExecSetConn() {
        keyService.execAddKey(Arrays.asList("connKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetConn(Arrays.asList("connKey", "10"));

        assertThat(result).contains("connKey");
    }

    @Test
    void testExecSetConnNonExistent() {
        assertThatThrownBy(() -> keyService.execSetConn(Arrays.asList("nonExistent", "10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecSetConnInvalidArgs() {
        assertThatThrownBy(() -> keyService.execSetConn(Collections.singletonList("key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecSetConnInvalidNumber() {
        keyService.execAddKey(Arrays.asList("connKey2", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        assertThatThrownBy(() -> keyService.execSetConn(Arrays.asList("connKey2", "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execMapKey 测试 ====================

    @Test
    void testExecMapKey() {
        keyService.execAddKey(Arrays.asList("mapKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execMapKey(Arrays.asList("mapKey", "node1", "9090"));

        assertThat(result).contains("mapKey");
    }

    @Test
    void testExecMapKeyNonExistent() {
        assertThatThrownBy(() -> keyService.execMapKey(Arrays.asList("nonExistent", "node1", "9090")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecMapKeyInvalidArgs() {
        assertThatThrownBy(() -> keyService.execMapKey(Arrays.asList("key", "node1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execDelMap 测试 ====================

    @Test
    void testExecDelMap() {
        keyService.execAddKey(Arrays.asList("delMapKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));
        keyService.execMapKey(Arrays.asList("delMapKey", "node1", "9090"));

        String result = keyService.execDelMap(Arrays.asList("delMapKey", "node1"));

        assertThat(result).contains("delMapKey");
    }

    @Test
    void testExecDelMapNonExistent() {
        assertThatThrownBy(() -> keyService.execDelMap(Arrays.asList("nonExistent", "node1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecDelMapInvalidArgs() {
        assertThatThrownBy(() -> keyService.execDelMap(Collections.singletonList("key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execDelNode 测试 ====================

    @Test
    void testExecDelNode() {
        String result = keyService.execDelNode(Collections.singletonList("node1"));

        assertThat(result).isNotNull();
    }

    @Test
    void testExecDelNodeInvalidArgs() {
        assertThatThrownBy(() -> keyService.execDelNode(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execEnable/execDisable 测试 ====================

    @Test
    void testExecEnable() {
        keyService.execAddKey(Arrays.asList("enableKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execEnable(Collections.singletonList("enableKey"), true);

        assertThat(result).contains("enableKey");
    }

    @Test
    void testExecDisable() {
        keyService.execAddKey(Arrays.asList("disableKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execEnable(Collections.singletonList("disableKey"), false);

        assertThat(result).contains("disableKey");
    }

    @Test
    void testExecEnableNonExistent() {
        assertThatThrownBy(() -> keyService.execEnable(Collections.singletonList("nonExistent"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecEnableInvalidArgs() {
        assertThatThrownBy(() -> keyService.execEnable(Collections.emptyList(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execSetSingle 测试 ====================

    @Test
    void testExecSetSingle() {
        keyService.execAddKey(Arrays.asList("singleKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetSingle(Collections.singletonList("singleKey"));

        assertThat(result).contains("singleKey");
    }

    @Test
    void testExecSetSingleNonExistent() {
        assertThatThrownBy(() -> keyService.execSetSingle(Collections.singletonList("nonExistent")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecSetSingleInvalidArgs() {
        assertThatThrownBy(() -> keyService.execSetSingle(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execDelSingle 测试 ====================

    @Test
    void testExecDelSingle() {
        keyService.execAddKey(Arrays.asList("delSingleKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));
        keyService.execSetSingle(Collections.singletonList("delSingleKey"));

        String result = keyService.execDelSingle(Collections.singletonList("delSingleKey"));

        assertThat(result).contains("delSingleKey");
    }

    @Test
    void testExecDelSingleNonExistent() {
        assertThatThrownBy(() -> keyService.execDelSingle(Collections.singletonList("nonExistent")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecDelSingleInvalidArgs() {
        assertThatThrownBy(() -> keyService.execDelSingle(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execListSingle 测试 ====================

    @Test
    void testExecListSingle() {
        String result = keyService.execListSingle(Collections.emptyList());

        assertThat(result).isNotNull();
    }

    // ==================== execLinkKey 测试 ====================

    @Test
    void testExecLinkKey() {
        keyService.execAddKey(Arrays.asList("realKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execLinkKey(Arrays.asList("alias1", "to", "realKey"));

        assertThat(result).contains("alias1");
    }

    @Test
    void testExecLinkKeyNonExistent() {
        assertThatThrownBy(() -> keyService.execLinkKey(Arrays.asList("alias1", "to", "nonExistent")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecLinkKeyInvalidArgs() {
        assertThatThrownBy(() -> keyService.execLinkKey(Collections.singletonList("alias1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execListLink 测试 ====================

    @Test
    void testExecListLink() {
        String result = keyService.execListLink(Collections.emptyList());

        assertThat(result).isNotNull();
    }

    // ==================== execSetCbm 测试 ====================

    @Test
    void testExecSetCbm() {
        keyService.execAddKey(Arrays.asList("cbmKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        // 消息需要用 {} 包裹
        String result = keyService.execSetCbm(Arrays.asList("cbmKey", "{Custom message}"));

        assertThat(result).contains("cbmKey");
    }

    @Test
    void testExecSetCbmNonExistent() {
        assertThatThrownBy(() -> keyService.execSetCbm(Arrays.asList("nonExistent", "message")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecSetCbmInvalidArgs() {
        assertThatThrownBy(() -> keyService.execSetCbm(Collections.singletonList("key")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execDelCbm 测试 ====================

    @Test
    void testExecDelCbm() {
        keyService.execAddKey(Arrays.asList("delCbmKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));
        keyService.execSetCbm(Arrays.asList("delCbmKey", "{Custom message}"));

        String result = keyService.execDelCbm(Collections.singletonList("delCbmKey"));

        assertThat(result).contains("delCbmKey");
    }

    @Test
    void testExecDelCbmNonExistent() {
        assertThatThrownBy(() -> keyService.execDelCbm(Collections.singletonList("nonExistent")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testExecDelCbmInvalidArgs() {
        assertThatThrownBy(() -> keyService.execDelCbm(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== execListCbm 测试 ====================

    @Test
    void testExecListCbm() {
        String result = keyService.execListCbm(Collections.emptyList());

        assertThat(result).isNotNull();
    }

    // ==================== 边界测试 ====================

    @Test
    void testExecAddKeyWithPortRange() {
        String result = keyService.execAddKey(Arrays.asList("rangeKey", "100.0", "2030/12/31-23:59", "8080-8090", "0.01"));

        assertThat(result).contains("rangeKey");
    }

    @Test
    void testExecSetKeyRename() {
        keyService.execAddKey(Arrays.asList("oldName", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetKey(Arrays.asList("oldName", "n=newName"));

        assertThat(result).contains("newName");
    }

    @Test
    void testExecSetKeyExpireTime() {
        keyService.execAddKey(Arrays.asList("expireKey", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execSetKey(Arrays.asList("expireKey", "t=2025/12/31-23:59"));

        assertThat(result).contains("expireKey");
    }

    @Test
    void testExecAddKeyWithZeroBalance() {
        String result = keyService.execAddKey(Arrays.asList("zeroBal", "0.0", "2030/12/31-23:59", "8080", "0.01"));

        assertThat(result).contains("zeroBal");
    }

    @Test
    void testExecAddKeyWithZeroRate() {
        String result = keyService.execAddKey(Arrays.asList("zeroRate", "100.0", "2030/12/31-23:59", "8080", "0.0"));

        assertThat(result).contains("zeroRate");
    }

    @Test
    void testExecSetConnZero() {
        keyService.execAddKey(Arrays.asList("zeroConn", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        // 连接数不能为0，应该抛出异常
        assertThatThrownBy(() -> keyService.execSetConn(Arrays.asList("zeroConn", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conn");
    }

    @Test
    void testExecSetCbmEmptyMessage() {
        keyService.execAddKey(Arrays.asList("emptyCbm", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        // 空消息应该用 {} 包裹
        String result = keyService.execSetCbm(Arrays.asList("emptyCbm", "{}"));

        assertThat(result).contains("emptyCbm");
    }

    @Test
    void testExecLinkKeyWithUnicode() {
        keyService.execAddKey(Arrays.asList("unicodeReal", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        String result = keyService.execLinkKey(Arrays.asList("中文别名", "to", "unicodeReal"));

        assertThat(result).contains("中文别名");
    }

    @Test
    void testExecAddKeyWithUnicode() {
        String result = keyService.execAddKey(Arrays.asList("中文密钥", "100.0", "2030/12/31-23:59", "8080", "0.01"));

        assertThat(result).contains("中文密钥");
    }
}
