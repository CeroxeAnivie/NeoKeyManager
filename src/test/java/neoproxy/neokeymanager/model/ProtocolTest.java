package neoproxy.neokeymanager.model;

import neoproxy.neokeymanager.model.Protocol.HeartbeatPayload;
import neoproxy.neokeymanager.model.Protocol.KeyMetadata;
import neoproxy.neokeymanager.model.Protocol.NodeStatusPayload;
import neoproxy.neokeymanager.model.Protocol.PublicNodeInfo;
import neoproxy.neokeymanager.model.Protocol.ReleasePayload;
import neoproxy.neokeymanager.model.Protocol.SyncPayload;
import neoproxy.neokeymanager.model.Protocol.SyncResponse;
import neoproxy.neokeymanager.model.Protocol.UpdateUrlResponse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol 单元测试
 * 测试协议常量和数据传输对象
 */
class ProtocolTest {

    // ==================== 常量测试 ====================

    @Test
    void testApiConstants() {
        assertThat(Protocol.API_GET_KEY).isEqualTo("/api/key");
        assertThat(Protocol.API_HEARTBEAT).isEqualTo("/api/heartbeat");
        assertThat(Protocol.API_SYNC).isEqualTo("/api/sync");
        assertThat(Protocol.API_RELEASE).isEqualTo("/api/release");
        assertThat(Protocol.API_NODE_STATUS).isEqualTo("/api/node/status");
        assertThat(Protocol.API_CLIENT_UPDATE_URL).isEqualTo("/api/node/client/update-url");
        assertThat(Protocol.API_CLIENT_NODELIST).isEqualTo("/client/nodelist");
    }

    @Test
    void testTimeoutConstant() {
        assertThat(Protocol.ZOMBIE_TIMEOUT_MS).isEqualTo(10000L);
    }

    @Test
    void testStatusConstants() {
        assertThat(Protocol.STATUS_OK).isEqualTo("ok");
        assertThat(Protocol.STATUS_KILL).isEqualTo("kill");
    }

    // ==================== HeartbeatPayload 测试 ====================

    @Test
    void testHeartbeatPayloadDefaultValues() {
        HeartbeatPayload payload = new HeartbeatPayload();

        assertThat(payload.serial).isNull();
        assertThat(payload.nodeId).isNull();
        assertThat(payload.port).isNull();
        assertThat(payload.connectionDetail).isNull();
    }

    @Test
    void testHeartbeatPayloadWithValues() {
        HeartbeatPayload payload = new HeartbeatPayload();
        payload.serial = "serial123";
        payload.nodeId = "node1";
        payload.port = "8080";
        payload.connectionDetail = "192.168.1.1:12345";

        assertThat(payload.serial).isEqualTo("serial123");
        assertThat(payload.nodeId).isEqualTo("node1");
        assertThat(payload.port).isEqualTo("8080");
        assertThat(payload.connectionDetail).isEqualTo("192.168.1.1:12345");
    }

    @Test
    void testHeartbeatPayloadImplementsSerializable() {
        HeartbeatPayload payload = new HeartbeatPayload();
        assertThat(payload).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== NodeStatusPayload 测试 ====================

    @Test
    void testNodeStatusPayloadDefaultValues() {
        NodeStatusPayload payload = new NodeStatusPayload();

        assertThat(payload.nodeId).isNull();
        assertThat(payload.version).isNull();
        assertThat(payload.timestamp).isEqualTo(0L);
        assertThat(payload.activeTunnels).isEqualTo(0);
    }

    @Test
    void testNodeStatusPayloadWithValues() {
        NodeStatusPayload payload = new NodeStatusPayload();
        payload.nodeId = "node1";
        payload.version = "1.0.0";
        payload.timestamp = 1234567890L;
        payload.activeTunnels = 10;

        assertThat(payload.nodeId).isEqualTo("node1");
        assertThat(payload.version).isEqualTo("1.0.0");
        assertThat(payload.timestamp).isEqualTo(1234567890L);
        assertThat(payload.activeTunnels).isEqualTo(10);
    }

    @Test
    void testNodeStatusPayloadImplementsSerializable() {
        NodeStatusPayload payload = new NodeStatusPayload();
        assertThat(payload).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== SyncPayload 测试 ====================

    @Test
    void testSyncPayloadDefaultValues() {
        SyncPayload payload = new SyncPayload();

        assertThat(payload.nodeId).isNull();
        assertThat(payload.traffic).isNull();
    }

    @Test
    void testSyncPayloadWithValues() {
        SyncPayload payload = new SyncPayload();
        payload.nodeId = "node1";
        payload.traffic = new HashMap<>();
        payload.traffic.put("key1", 100.5);
        payload.traffic.put("key2", 200.3);

        assertThat(payload.nodeId).isEqualTo("node1");
        assertThat(payload.traffic).hasSize(2);
        assertThat(payload.traffic.get("key1")).isEqualTo(100.5);
    }

    @Test
    void testSyncPayloadImplementsSerializable() {
        SyncPayload payload = new SyncPayload();
        assertThat(payload).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== ReleasePayload 测试 ====================

    @Test
    void testReleasePayloadDefaultValues() {
        ReleasePayload payload = new ReleasePayload();

        assertThat(payload.serial).isNull();
        assertThat(payload.nodeId).isNull();
    }

    @Test
    void testReleasePayloadWithValues() {
        ReleasePayload payload = new ReleasePayload();
        payload.serial = "serial123";
        payload.nodeId = "node1";

        assertThat(payload.serial).isEqualTo("serial123");
        assertThat(payload.nodeId).isEqualTo("node1");
    }

    @Test
    void testReleasePayloadImplementsSerializable() {
        ReleasePayload payload = new ReleasePayload();
        assertThat(payload).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== SyncResponse 测试 ====================

    @Test
    void testSyncResponseDefaultValues() {
        SyncResponse response = new SyncResponse();

        assertThat(response.status).isNull();
        assertThat(response.metadata).isNull();
    }

    @Test
    void testSyncResponseWithValues() {
        SyncResponse response = new SyncResponse();
        response.status = "ok";
        response.metadata = new HashMap<>();

        KeyMetadata meta = new KeyMetadata();
        meta.isValid = true;
        meta.balance = 100.0;

        response.metadata.put("key1", meta);

        assertThat(response.status).isEqualTo("ok");
        assertThat(response.metadata).hasSize(1);
    }

    @Test
    void testSyncResponseImplementsSerializable() {
        SyncResponse response = new SyncResponse();
        assertThat(response).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== KeyMetadata 测试 ====================

    @Test
    void testKeyMetadataDefaultValues() {
        KeyMetadata metadata = new KeyMetadata();

        assertThat(metadata.isValid).isFalse();
        assertThat(metadata.reason).isNull();
        assertThat(metadata.balance).isEqualTo(0.0);
        assertThat(metadata.rate).isEqualTo(0.0);
        assertThat(metadata.expireTime).isNull();
        assertThat(metadata.enableWebHTML).isFalse();
    }

    @Test
    void testKeyMetadataWithValues() {
        KeyMetadata metadata = new KeyMetadata();
        metadata.isValid = true;
        metadata.reason = "All good";
        metadata.balance = 100.5;
        metadata.rate = 0.01;
        metadata.expireTime = "2024/12/31-23:59";
        metadata.enableWebHTML = true;

        assertThat(metadata.isValid).isTrue();
        assertThat(metadata.reason).isEqualTo("All good");
        assertThat(metadata.balance).isEqualTo(100.5);
        assertThat(metadata.rate).isEqualTo(0.01);
        assertThat(metadata.expireTime).isEqualTo("2024/12/31-23:59");
        assertThat(metadata.enableWebHTML).isTrue();
    }

    @Test
    void testKeyMetadataImplementsSerializable() {
        KeyMetadata metadata = new KeyMetadata();
        assertThat(metadata).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== UpdateUrlResponse 测试 ====================

    @Test
    void testUpdateUrlResponseDefaultConstructor() {
        UpdateUrlResponse response = new UpdateUrlResponse();

        assertThat(response.url).isNull();
        assertThat(response.valid).isFalse();
    }

    @Test
    void testUpdateUrlResponseParameterizedConstructor() {
        UpdateUrlResponse response = new UpdateUrlResponse("http://example.com", true);

        assertThat(response.url).isEqualTo("http://example.com");
        assertThat(response.valid).isTrue();
    }

    @Test
    void testUpdateUrlResponseImplementsSerializable() {
        UpdateUrlResponse response = new UpdateUrlResponse();
        assertThat(response).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== PublicNodeInfo 测试 ====================

    @Test
    void testPublicNodeInfoDefaultValues() {
        PublicNodeInfo info = new PublicNodeInfo();

        assertThat(info.name).isNull();
        assertThat(info.address).isNull();
        assertThat(info.icon).isNull();
        assertThat(info.HOST_HOOK_PORT).isEqualTo(0);
        assertThat(info.HOST_CONNECT_PORT).isEqualTo(0);
    }

    @Test
    void testPublicNodeInfoWithValues() {
        PublicNodeInfo info = new PublicNodeInfo();
        info.name = "Node 1";
        info.address = "192.168.1.1";
        info.icon = "icon.png";
        info.HOST_HOOK_PORT = 8080;
        info.HOST_CONNECT_PORT = 9090;

        assertThat(info.name).isEqualTo("Node 1");
        assertThat(info.address).isEqualTo("192.168.1.1");
        assertThat(info.icon).isEqualTo("icon.png");
        assertThat(info.HOST_HOOK_PORT).isEqualTo(8080);
        assertThat(info.HOST_CONNECT_PORT).isEqualTo(9090);
    }

    @Test
    void testPublicNodeInfoImplementsSerializable() {
        PublicNodeInfo info = new PublicNodeInfo();
        assertThat(info).isInstanceOf(java.io.Serializable.class);
    }

    // ==================== 边界测试 ====================

    @Test
    void testKeyMetadataWithNegativeBalance() {
        KeyMetadata metadata = new KeyMetadata();
        metadata.balance = -50.0;

        assertThat(metadata.balance).isNegative();
    }

    @Test
    void testKeyMetadataWithZeroRate() {
        KeyMetadata metadata = new KeyMetadata();
        metadata.rate = 0.0;

        assertThat(metadata.rate).isZero();
    }

    @Test
    void testSyncPayloadWithEmptyTraffic() {
        SyncPayload payload = new SyncPayload();
        payload.traffic = new HashMap<>();

        assertThat(payload.traffic).isEmpty();
    }

    @Test
    void testSyncResponseWithEmptyMetadata() {
        SyncResponse response = new SyncResponse();
        response.metadata = new HashMap<>();

        assertThat(response.metadata).isEmpty();
    }

    @Test
    void testNodeStatusPayloadWithNegativeTimestamp() {
        NodeStatusPayload payload = new NodeStatusPayload();
        payload.timestamp = -1L;

        assertThat(payload.timestamp).isNegative();
    }

    @Test
    void testPublicNodeInfoWithZeroPorts() {
        PublicNodeInfo info = new PublicNodeInfo();
        info.HOST_HOOK_PORT = 0;
        info.HOST_CONNECT_PORT = 0;

        assertThat(info.HOST_HOOK_PORT).isZero();
        assertThat(info.HOST_CONNECT_PORT).isZero();
    }

    @Test
    void testPublicNodeInfoWithLargePorts() {
        PublicNodeInfo info = new PublicNodeInfo();
        info.HOST_HOOK_PORT = 65535;
        info.HOST_CONNECT_PORT = 65535;

        assertThat(info.HOST_HOOK_PORT).isEqualTo(65535);
        assertThat(info.HOST_CONNECT_PORT).isEqualTo(65535);
    }
}
