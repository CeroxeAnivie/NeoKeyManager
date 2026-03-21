package neoproxy.neokeymanager.model;

import neoproxy.neokeymanager.model.AdminDTOs.AdminResponse;
import neoproxy.neokeymanager.model.AdminDTOs.ExecRequest;
import neoproxy.neokeymanager.model.AdminDTOs.KeyDetail;
import neoproxy.neokeymanager.model.AdminDTOs.MapNode;
import neoproxy.neokeymanager.model.AdminDTOs.NodeStatusDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminDTOs 单元测试
 * 测试管理端数据传输对象
 */
class AdminDTOsTest {

    // ==================== AdminResponse 测试 ====================

    @Test
    void testAdminResponseSuccess() {
        AdminResponse response = new AdminResponse(true, "Operation successful", "data");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("Operation successful");
        assertThat(response.data()).isEqualTo("data");
    }

    @Test
    void testAdminResponseFailure() {
        AdminResponse response = new AdminResponse(false, "Operation failed", null);

        assertThat(response.success()).isFalse();
        assertThat(response.message()).isEqualTo("Operation failed");
        assertThat(response.data()).isNull();
    }

    @Test
    void testAdminResponseWithListData() {
        List<String> data = Arrays.asList("item1", "item2");
        AdminResponse response = new AdminResponse(true, "List retrieved", data);

        assertThat(response.data()).isInstanceOf(List.class);
        assertThat((List<?>) response.data()).hasSize(2);
    }

    @Test
    void testAdminResponseImplementsSerializable() {
        AdminResponse response = new AdminResponse(true, "", null);
        assertThat(response).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== KeyDetail 测试 ====================

    @Test
    void testKeyDetailDefaultValues() {
        KeyDetail detail = new KeyDetail();

        assertThat(detail.name).isNull();
        assertThat(detail.balance).isEqualTo(0.0);
        assertThat(detail.rate).isEqualTo(0.0);
        assertThat(detail.port).isNull();
        assertThat(detail.maxConns).isEqualTo(0);
        assertThat(detail.expireTime).isNull();
        assertThat(detail.enableWeb).isFalse();
        assertThat(detail.status).isNull();
        assertThat(detail.maps).isNull();
    }

    @Test
    void testKeyDetailWithValues() {
        KeyDetail detail = new KeyDetail();
        detail.name = "testKey";
        detail.balance = 100.5;
        detail.rate = 0.01;
        detail.port = "8080-8090";
        detail.maxConns = 5;
        detail.expireTime = "2024/12/31-23:59";
        detail.enableWeb = true;
        detail.status = "ENABLED";
        detail.maps = new ArrayList<>();

        assertThat(detail.name).isEqualTo("testKey");
        assertThat(detail.balance).isEqualTo(100.5);
        assertThat(detail.rate).isEqualTo(0.01);
        assertThat(detail.port).isEqualTo("8080-8090");
        assertThat(detail.maxConns).isEqualTo(5);
        assertThat(detail.expireTime).isEqualTo("2024/12/31-23:59");
        assertThat(detail.enableWeb).isTrue();
        assertThat(detail.status).isEqualTo("ENABLED");
        assertThat(detail.maps).isEmpty();
    }

    @Test
    void testKeyDetailImplementsSerializable() {
        KeyDetail detail = new KeyDetail();
        assertThat(detail).isInstanceOf(java.io.Serializable.class);
    }

    @Test
    void testKeyDetailWithMaps() {
        KeyDetail detail = new KeyDetail();
        detail.name = "keyWithMaps";
        detail.maps = Arrays.asList(
                new MapNode("node1", "8080"),
                new MapNode("node2", "8081")
        );

        assertThat(detail.maps).hasSize(2);
        assertThat(detail.maps.get(0).nodeId).isEqualTo("node1");
        assertThat(detail.maps.get(0).port).isEqualTo("8080");
    }

    // ==================== MapNode 测试 ====================

    @Test
    void testMapNodeConstructor() {
        MapNode node = new MapNode("node1", "8080");

        assertThat(node.nodeId).isEqualTo("node1");
        assertThat(node.port).isEqualTo("8080");
    }

    @Test
    void testMapNodeWithNullValues() {
        MapNode node = new MapNode(null, null);

        assertThat(node.nodeId).isNull();
        assertThat(node.port).isNull();
    }

    @Test
    void testMapNodeImplementsSerializable() {
        MapNode node = new MapNode("node1", "8080");
        assertThat(node).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== ExecRequest 测试 ====================

    @Test
    void testExecRequestDefaultValues() {
        ExecRequest request = new ExecRequest();

        assertThat(request.args).isNull();
    }

    @Test
    void testExecRequestWithArgs() {
        ExecRequest request = new ExecRequest();
        request.args = Arrays.asList("user1", "b=20", "r=0.01");

        assertThat(request.args).hasSize(3);
        assertThat(request.args.get(0)).isEqualTo("user1");
        assertThat(request.args.get(1)).isEqualTo("b=20");
    }

    @Test
    void testExecRequestWithEmptyArgs() {
        ExecRequest request = new ExecRequest();
        request.args = Collections.emptyList();

        assertThat(request.args).isEmpty();
    }

    @Test
    void testExecRequestImplementsSerializable() {
        ExecRequest request = new ExecRequest();
        assertThat(request).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== NodeStatusDetail 测试 ====================

    @Test
    void testNodeStatusDetailOnline() {
        NodeStatusDetail detail = new NodeStatusDetail("real-node-1", "Node 1", true);

        assertThat(detail.realId).isEqualTo("real-node-1");
        assertThat(detail.displayName).isEqualTo("Node 1");
        assertThat(detail.isOnline).isTrue();
    }

    @Test
    void testNodeStatusDetailOffline() {
        NodeStatusDetail detail = new NodeStatusDetail("real-node-2", "Node 2", false);

        assertThat(detail.realId).isEqualTo("real-node-2");
        assertThat(detail.displayName).isEqualTo("Node 2");
        assertThat(detail.isOnline).isFalse();
    }

    @Test
    void testNodeStatusDetailWithNullValues() {
        NodeStatusDetail detail = new NodeStatusDetail(null, null, false);

        assertThat(detail.realId).isNull();
        assertThat(detail.displayName).isNull();
        assertThat(detail.isOnline).isFalse();
    }

    @Test
    void testNodeStatusDetailImplementsSerializable() {
        NodeStatusDetail detail = new NodeStatusDetail("node1", "Node 1", true);
        assertThat(detail).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== 边界测试 ====================

    @Test
    void testKeyDetailWithNegativeBalance() {
        KeyDetail detail = new KeyDetail();
        detail.balance = -50.0;

        assertThat(detail.balance).isNegative();
    }

    @Test
    void testKeyDetailWithZeroRate() {
        KeyDetail detail = new KeyDetail();
        detail.rate = 0.0;

        assertThat(detail.rate).isZero();
    }

    @Test
    void testKeyDetailWithVeryLargeBalance() {
        KeyDetail detail = new KeyDetail();
        detail.balance = Double.MAX_VALUE;

        assertThat(detail.balance).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void testKeyDetailWithVerySmallRate() {
        KeyDetail detail = new KeyDetail();
        detail.rate = Double.MIN_VALUE;

        assertThat(detail.rate).isEqualTo(Double.MIN_VALUE);
    }

    @Test
    void testMapNodeWithEmptyStrings() {
        MapNode node = new MapNode("", "");

        assertThat(node.nodeId).isEmpty();
        assertThat(node.port).isEmpty();
    }

    @Test
    void testExecRequestWithSingleArg() {
        ExecRequest request = new ExecRequest();
        request.args = Collections.singletonList("single");

        assertThat(request.args).hasSize(1);
    }

    @Test
    void testNodeStatusDetailWithEmptyStrings() {
        NodeStatusDetail detail = new NodeStatusDetail("", "", true);

        assertThat(detail.realId).isEmpty();
        assertThat(detail.displayName).isEmpty();
    }

    @Test
    void testAdminResponseWithComplexData() {
        KeyDetail keyDetail = new KeyDetail();
        keyDetail.name = "complexKey";
        keyDetail.balance = 999.99;

        AdminResponse response = new AdminResponse(true, "Complex data", keyDetail);

        assertThat(response.data()).isInstanceOf(KeyDetail.class);
        KeyDetail returnedDetail = (KeyDetail) response.data();
        assertThat(returnedDetail.name).isEqualTo("complexKey");
        assertThat(returnedDetail.balance).isEqualTo(999.99);
    }
}
