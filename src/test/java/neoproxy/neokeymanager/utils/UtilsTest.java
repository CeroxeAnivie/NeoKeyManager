package neoproxy.neokeymanager.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Utils 工具类单元测试
 */
class UtilsTest {

    // ==================== JSON 序列化测试 ====================

    @Test
    void testToJson() {
        TestObject obj = new TestObject("test", 123);

        String json = Utils.toJson(obj);

        assertThat(json).contains("\"name\":\"test\"");
        assertThat(json).contains("\"value\":123");
    }

    @Test
    void testToJsonNull() {
        String json = Utils.toJson(null);

        assertThat(json).isEqualTo("null");
    }

    @Test
    void testToJsonEmptyObject() {
        String json = Utils.toJson(new Object());

        assertThat(json).isNotNull();
    }

    // ==================== JSON 解析测试 ====================

    @Test
    void testParseJsonFromString() {
        String json = "{\"name\":\"test\",\"value\":123}";

        TestObject obj = Utils.parseJson(json, TestObject.class);

        assertThat(obj.name).isEqualTo("test");
        assertThat(obj.value).isEqualTo(123);
    }

    @Test
    void testParseJsonFromInputStream() throws Exception {
        String json = "{\"name\":\"test\",\"value\":123}";
        InputStream is = new ByteArrayInputStream(json.getBytes());

        TestObject obj = Utils.parseJson(is, TestObject.class);

        assertThat(obj.name).isEqualTo("test");
        assertThat(obj.value).isEqualTo(123);
    }

    @Test
    void testParseJsonNull() {
        TestObject obj = Utils.<TestObject>parseJson((String) null, TestObject.class);

        assertThat(obj).isNull();
    }

    @Test
    void testParseJsonEmptyString() {
        TestObject obj = Utils.parseJson("", TestObject.class);

        assertThat(obj).isNull();
    }

    @Test
    void testParseJsonInvalid() {
        String json = "invalid json";

        TestObject obj = Utils.parseJson(json, TestObject.class);

        assertThat(obj).isNull();
    }

    // ==================== 流量映射解析测试 ====================

    @Test
    void testParseTrafficMap() throws Exception {
        String json = "{\"key1\":100.5,\"key2\":200.3}";
        InputStream is = new ByteArrayInputStream(json.getBytes());

        Map<String, Double> map = Utils.parseTrafficMap(is);

        assertThat(map).hasSize(2);
        assertThat(map.get("key1")).isEqualTo(100.5);
        assertThat(map.get("key2")).isEqualTo(200.3);
    }

    @Test
    void testParseTrafficMapEmpty() throws Exception {
        String json = "{}";
        InputStream is = new ByteArrayInputStream(json.getBytes());

        Map<String, Double> map = Utils.parseTrafficMap(is);

        assertThat(map).isEmpty();
    }

    @Test
    void testParseTrafficMapInvalid() throws Exception {
        String json = "invalid";
        InputStream is = new ByteArrayInputStream(json.getBytes());

        Map<String, Double> map = Utils.parseTrafficMap(is);

        assertThat(map).isEmpty();
    }

    // ==================== 查询参数解析测试 ====================

    @Test
    void testParseQueryParams() {
        String query = "key1=value1&key2=value2&key3=value3";

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params).hasSize(3);
        assertThat(params.get("key1")).isEqualTo("value1");
        assertThat(params.get("key2")).isEqualTo("value2");
        assertThat(params.get("key3")).isEqualTo("value3");
    }

    @Test
    void testParseQueryParamsEmpty() {
        Map<String, String> params = Utils.parseQueryParams("");

        assertThat(params).isEmpty();
    }

    @Test
    void testParseQueryParamsNull() {
        Map<String, String> params = Utils.parseQueryParams(null);

        assertThat(params).isEmpty();
    }

    @Test
    void testParseQueryParamsSingleParam() {
        String query = "key=value";

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params).hasSize(1);
        assertThat(params.get("key")).isEqualTo("value");
    }

    @Test
    void testParseQueryParamsWithEmptyValue() {
        String query = "key1=&key2=value2";

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params.get("key1")).isEmpty();
        assertThat(params.get("key2")).isEqualTo("value2");
    }

    @Test
    void testParseQueryParamsWithSpecialChars() {
        String query = "key=hello%20world";

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params.get("key")).isEqualTo("hello world");
    }

    // ==================== 端口大小计算测试 ====================

    @Test
    void testCalculatePortSizeSinglePort() {
        int size = Utils.calculatePortSize("8080");

        assertThat(size).isEqualTo(1);
    }

    @Test
    void testCalculatePortSizeRange() {
        int size = Utils.calculatePortSize("8080-8089");

        assertThat(size).isEqualTo(10);
    }

    @Test
    void testCalculatePortSizeEmpty() {
        int size = Utils.calculatePortSize("");

        assertThat(size).isEqualTo(0);
    }

    @Test
    void testCalculatePortSizeNull() {
        int size = Utils.calculatePortSize(null);

        assertThat(size).isEqualTo(0);
    }

    @Test
    void testCalculatePortSizeInvalid() {
        int size = Utils.calculatePortSize("invalid");

        assertThat(size).isEqualTo(0);
    }

    @Test
    void testCalculatePortSizeSingleNumber() {
        int size = Utils.calculatePortSize("100");

        assertThat(size).isEqualTo(1);
    }

    // ==================== 动态端口检查测试 ====================

    @Test
    void testIsDynamicPortTrue() {
        assertThat(Utils.isDynamicPort("auto")).isTrue();
        assertThat(Utils.isDynamicPort("AUTO")).isTrue();
        assertThat(Utils.isDynamicPort("Auto")).isTrue();
    }

    @Test
    void testIsDynamicPortFalse() {
        assertThat(Utils.isDynamicPort("8080")).isFalse();
        assertThat(Utils.isDynamicPort("8080-8090")).isFalse();
        assertThat(Utils.isDynamicPort("")).isFalse();
    }

    @Test
    void testIsDynamicPortNull() {
        assertThat(Utils.isDynamicPort(null)).isFalse();
    }

    // ==================== 边界测试 ====================

    @Test
    void testParseQueryParamsWithMultipleEquals() {
        String query = "key=value=with=equals";

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params.get("key")).isEqualTo("value=with=equals");
    }

    @Test
    void testParseQueryParamsWithNoValue() {
        String query = "key1&key2=value2";

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params.get("key1")).isNull();
        assertThat(params.get("key2")).isEqualTo("value2");
    }

    @Test
    void testParseTrafficMapWithNestedObject() throws Exception {
        String json = "{\"key1\":{\"nested\":\"value\"}}";
        InputStream is = new ByteArrayInputStream(json.getBytes());

        // 嵌套对象解析为Double应该失败，返回空map
        Map<String, Double> map = Utils.parseTrafficMap(is);

        assertThat(map).isEmpty();
    }

    @Test
    void testToJsonComplexObject() {
        ComplexObject obj = new ComplexObject();
        obj.name = "test";
        obj.nested = new TestObject("nested", 456);

        String json = Utils.toJson(obj);

        assertThat(json).contains("\"name\":\"test\"");
        assertThat(json).contains("\"nested\"");
    }

    @Test
    void testCalculatePortSizeLargeRange() {
        int size = Utils.calculatePortSize("1-65535");

        assertThat(size).isEqualTo(65535);
    }

    @Test
    void testCalculatePortSizeReverseRange() {
        // 反向范围应该处理
        int size = Utils.calculatePortSize("100-50");

        // 根据实现可能返回0或负数
        assertThat(size).isLessThanOrEqualTo(0);
    }

    @Test
    void testParseQueryParamsWithUnicode() {
        String query = "key=%E4%B8%AD%E6%96%87"; // URL编码的中文"中文"

        Map<String, String> params = Utils.parseQueryParams(query);

        assertThat(params.get("key")).isEqualTo("中文");
    }

    // ==================== 测试辅助类 ====================

    public static class TestObject {
        public String name;
        public int value;

        public TestObject() {
        }

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    public static class ComplexObject {
        public String name;
        public TestObject nested;
    }
}
