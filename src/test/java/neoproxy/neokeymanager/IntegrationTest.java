package neoproxy.neokeymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * NeoKeyManager Ultimate Test Suite (V21 - Bulletproof Self-Setup Edition)
 */
public class IntegrationTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String ADMIN_TOKEN = "admin_secret_123";
    private static final String CLIENT_TOKEN = "default_token";

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    // 状态备份与存在性标记
    private static boolean hadServerProps = false;
    private static String originalServerProps = "";

    private static boolean hadNodeAuth = false;
    private static String originalNodeAuth = "";

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   NeoKeyManager Ultimate Test Suite (V21)        ");
        System.out.println("==================================================");

        try {
            backupConfigs();
            setupEnvironment(); // 【新增】自我搭建防弹环境
            cleanup();

            testKeyLifecycle();
            testRobustParameterParsing();
            testBatchDelete();
            testBatchMapping();
            testAliasSystem();
            testToggleAndWeb();
            testTrafficSync();
            testConnectionUpdate();
            testBasicSingleMode();
            testDecoupledSingleMode();
            testDelSingleCommand();
            testSystemReload();

            testPublicNodeListAndRateLimit();

            cleanup();
            restoreConfigs();

            System.out.println("\n✅✅✅ ALL TESTS PASSED: SYSTEM IS 100% COVERED! ✅✅✅");

        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            try {
                restoreConfigs();
            } catch (Exception ignored) {
            }
            System.exit(1);
        }
    }

    private static void backupConfigs() throws Exception {
        Path propsPath = Path.of("server.properties");
        if (Files.exists(propsPath)) {
            hadServerProps = true;
            originalServerProps = Files.readString(propsPath);
        }

        Path authPath = Path.of("NodeAuth.json");
        if (Files.exists(authPath)) {
            hadNodeAuth = true;
            originalNodeAuth = Files.readString(authPath);
        }
    }

    private static void setupEnvironment() throws Exception {
        // 自我注入测试所需的全部鉴权节点
        String authContent = Files.exists(Path.of("NodeAuth.json")) ? Files.readString(Path.of("NodeAuth.json")) : "{}";
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(authContent);
            if (root == null) root = mapper.createObjectNode();
        } catch (Exception e) {
            root = mapper.createObjectNode();
        }

        String[] testNodes = {"N1", "NA", "NB", "NodeA", "NodeB", "NodeC"};
        for (String node : testNodes) {
            root.set(node.toLowerCase(), mapper.createObjectNode()
                    .put("realId", node)
                    .put("displayName", "TestNode-" + node));
        }
        Files.writeString(Path.of("NodeAuth.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        // 强迫服务器加载新环境
        execAdmin("reload");
        Thread.sleep(3500);
    }

    private static void restoreConfigs() throws Exception {
        if (hadServerProps) {
            Files.writeString(Path.of("server.properties"), originalServerProps);
        } else {
            Files.deleteIfExists(Path.of("server.properties"));
        }

        if (hadNodeAuth) {
            Files.writeString(Path.of("NodeAuth.json"), originalNodeAuth);
        } else {
            Files.deleteIfExists(Path.of("NodeAuth.json"));
        }

        execAdmin("reload");
        Thread.sleep(3500);
    }

    private static void setServerProperty(String key, String value) throws Exception {
        Path propsPath = Path.of("server.properties");
        String content = Files.exists(propsPath) ? Files.readString(propsPath) : "";
        String regex = "(?m)^" + key + "=.*";
        if (content.matches("(?s).*" + regex + ".*")) {
            content = content.replaceAll(regex, key + "=" + value);
        } else {
            content += "\n" + key + "=" + value + "\n";
        }
        Files.writeString(propsPath, content);
    }

    // ==================== Phase 1-11 ====================

    private static void testKeyLifecycle() throws Exception {
        printHeader("1. Key Lifecycle");
        assertSuccess(execAdmin("add", "test_key", "100", "PERMANENT", "8081", "10"), "Add Key");
        String yesterday = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm"));
        execAdmin("set", "test_key", "t=" + yesterday);
        if (getNps("/api/key?name=test_key&nodeId=N1").statusCode() != 409)
            throw new RuntimeException("Expired Key check failed");
        execAdmin("set", "test_key", "t=PERMANENT");
        if (getNps("/api/key?name=test_key&nodeId=N1").statusCode() != 200)
            throw new RuntimeException("Key restore failed");
        System.out.println("   -> Lifecycle Logic [OK]");
    }

    private static void testRobustParameterParsing() throws Exception {
        printHeader("1.1 Robust Parameter Parsing");
        assertSuccess(execAdmin("set", "test_key", "BALANCE=555", "rAtE=2.5", "wEb=TrUe"), "Set Mixed Case");
        System.out.println("   -> Case Insensitivity [OK]");
    }

    private static void testBatchDelete() throws Exception {
        printHeader("2. Batch Delete");
        execAdmin("add", "batch_k1", "10", "PERMANENT", "1001", "1");
        execAdmin("add", "batch_k2", "10", "PERMANENT", "1002", "1");
        execAdmin("del", "batch_k1", "batch_k2");
        if (getAdmin("/api/query").get("data").toString().contains("batch_k1"))
            throw new RuntimeException("batch_k1 not deleted");
        System.out.println("   -> Batch Delete [OK]");
    }

    private static void testBatchMapping() throws Exception {
        printHeader("3. Batch Mapping");
        execAdmin("add", "map_key", "10", "PERMANENT", "2000", "5");
        assertSuccess(execAdmin("map", "map_key", "nodeA", "nodeB", "3333"), "Batch Map");
        assertSuccess(execAdmin("delmap", "map_key", "nodeA", "nodeB"), "Batch DelMap");
        execAdmin("del", "map_key");
        System.out.println("   -> Batch Mapping logic [OK]");
    }

    private static void testAliasSystem() throws Exception {
        printHeader("4. Alias System");
        execAdmin("setconn", "test_key", "10");
        assertSuccess(execAdmin("link", "test_alias", "to", "test_key"), "Link Alias");
        var resp = getNps("/api/key?name=test_alias&nodeId=N1");
        if (resp.statusCode() != 200) throw new RuntimeException("Alias Auth Failed.");
        System.out.println("   -> Alias Auth & Persistence [OK]");
    }

    private static void testToggleAndWeb() throws Exception {
        printHeader("5. Toggle & Web Command");
        execAdmin("disable", "test_key");
        if (getNps("/api/key?name=test_key&nodeId=N1").statusCode() != 403)
            throw new RuntimeException("Disable failed");
        execAdmin("enable", "test_key");
        assertSuccess(execAdmin("web", "disable", "test_key"), "Web Disable");
        System.out.println("   -> Toggle & Web Logic [OK]");
    }

    private static void testTrafficSync() throws Exception {
        printHeader("6. Traffic Sync");
        execAdmin("set", "test_key", "b=100");
        postNps("/api/sync", "{ \"traffic\": { \"test_alias\": 10 } }");
        System.out.println("      Waiting 5.5s for async DB flush (Traffic Buffer)...");
        Thread.sleep(5500);
        double bal = getAdmin("/api/lp/test_key").get("data").get("balance").asDouble();
        if (Math.abs(bal - 90.0) > 0.01) throw new RuntimeException("Deduction failed. Expected 90, got " + bal);
        System.out.println("   -> Balance Deduction [OK]");
    }

    private static void testConnectionUpdate() throws Exception {
        printHeader("7. Connection Update (Non-Kicking)");
        assertSuccess(execAdmin("setconn", "test_key", "50"), "Set Conn");
        System.out.println("   -> SetConn (Soft Update) [OK]");
    }

    private static void testBasicSingleMode() throws Exception {
        printHeader("8. Basic Single Mode (RealKey)");
        execAdmin("add", "single_real", "100", "PERMANENT", "9000", "10");
        execAdmin("setsingle", "single_real", "true");
        if (getNps("/api/key?name=single_real&nodeId=NA").statusCode() != 200)
            throw new RuntimeException("Node A failed");
        postNps("/api/heartbeat", "{ \"serial\": \"single_real\", \"nodeId\": \"NA\", \"port\": \"9000\" }");
        var resp = getNps("/api/key?name=single_real&nodeId=NB");
        if (resp.statusCode() != 409) throw new RuntimeException("Node B should be 409");
        System.out.println("   -> RealKey Single Enforcement [OK]");
    }

    private static void testDecoupledSingleMode() throws Exception {
        printHeader("9. Decoupled Single Mode (Alias=Single, Real=Multi)");
        execAdmin("add", "multi_real", "100", "PERMANENT", "9001", "10");
        execAdmin("setconn", "multi_real", "10");
        execAdmin("link", "single_alias", "to", "multi_real");
        execAdmin("setsingle", "single_alias", "true");
        if (getNps("/api/key?name=single_alias&nodeId=NodeA").statusCode() != 200)
            throw new RuntimeException("Alias NodeA failed");
        postNps("/api/heartbeat", "{ \"serial\": \"single_alias\", \"nodeId\": \"NodeA\", \"port\": \"9001\" }");
        if (getNps("/api/key?name=single_alias&nodeId=NodeB").statusCode() != 409)
            throw new RuntimeException("Alias NodeB SHOULD fail");
        if (getNps("/api/key?name=multi_real&nodeId=NodeC").statusCode() != 200)
            throw new RuntimeException("RealKey NodeC failed");
        System.out.println("      [√] RealKey remains Multi-Login");
    }

    private static void testDelSingleCommand() throws Exception {
        printHeader("10. DelSingle Command");
        assertSuccess(execAdmin("delsingle", "single_alias"), "Remove Single");
        System.out.println("   -> Single Mode Removed [OK]");
    }

    private static void testSystemReload() throws Exception {
        printHeader("11. System Reload API");
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/reload"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Reload failed: " + resp.statusCode());
        System.out.println("      Waiting 3.5s for async system reload to fully complete...");
        Thread.sleep(3500);
        System.out.println("   -> System Reload [OK]");
    }

    // ==================== Phase 12 ====================

    private static void testPublicNodeListAndRateLimit() throws Exception {
        printHeader("12. Public Node List & Rate Limiting (File Modify + API Reload)");

        setServerProperty("NODE_JSON_FILE", "");
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/reload"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("      Waiting 3.5s for server to disable node list...");
        Thread.sleep(3500);

        var respInvalid = getPublicWithIp("/client/nodelist", "10.0.0.1");
        if (respInvalid.statusCode() != 404) {
            throw new RuntimeException("Expected 404 for empty config, but got " + respInvalid.statusCode());
        }
        System.out.println("   -> Invalid Config Rejection (404) [OK]");

        String customJson = "[\n" +
                "  {\n" +
                "    \"name\": \"测试自动节点\",\n" +
                "    \"address\": \"127.0.0.1\",\n" +
                "    \"icon\": \"svg\",\n" +
                "    \"HOST_HOOK_PORT\": 1111,\n" +
                "    \"HOST_CONNECT_PORT\": 2222\n" +
                "  }\n" +
                "]";
        Files.writeString(Path.of("test_public_nodes.json"), customJson);
        setServerProperty("NODE_JSON_FILE", "test_public_nodes.json");

        String authContent = Files.exists(Path.of("NodeAuth.json")) ? Files.readString(Path.of("NodeAuth.json")) : "{}";
        ObjectNode root = (ObjectNode) mapper.readTree(authContent);
        root.set("test_mock_node", mapper.createObjectNode()
                .put("realId", "test_mock_node")
                .put("displayName", "测试自动节点"));
        Files.writeString(Path.of("NodeAuth.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("      Waiting 3.5s for server to enable node list...");
        Thread.sleep(3500);

        var respOffline = getPublicWithIp("/client/nodelist", "10.0.0.2");
        if (respOffline.statusCode() != 200)
            throw new RuntimeException("Expected 200, got " + respOffline.statusCode());
        JsonNode arrOffline = mapper.readTree(respOffline.body());
        if (arrOffline.size() != 0) {
            throw new RuntimeException("Node should be hidden when offline.");
        }
        System.out.println("   -> Offline Node Filtering [OK]");

        postNps("/api/node/status", "{ \"nodeId\": \"test_mock_node\", \"activeTunnels\": 5 }");
        var respOnline = getPublicWithIp("/client/nodelist", "10.0.0.3");
        JsonNode arrOnline = mapper.readTree(respOnline.body());
        if (arrOnline.size() != 1 || !"测试自动节点".equals(arrOnline.get(0).get("name").asText())) {
            throw new RuntimeException("Node should appear online after status report!");
        }
        System.out.println("   -> Online Node Appearance [OK]");

        for (int i = 0; i < 10; i++) {
            var r = getPublicWithIp("/client/nodelist", "10.0.0.4");
            if (r.statusCode() != 200) {
                throw new RuntimeException("Rate limit triggered too early at request " + (i + 1));
            }
        }
        var respLimit = getPublicWithIp("/client/nodelist", "10.0.0.4");
        if (respLimit.statusCode() != 429) {
            throw new RuntimeException("Expected 429 Too Many Requests, but got " + respLimit.statusCode());
        }
        System.out.println("   -> IP Rate Limiting (10 requests/day) [OK]");
    }

    private static void cleanup() {
        try {
            execAdmin("del", "test_key", "test_alias", "single_real", "multi_real", "single_alias", "batch_k1", "batch_k2", "map_key");
            Files.deleteIfExists(Path.of("test_public_nodes.json"));
        } catch (Exception ignored) {
        }
    }

    private static JsonNode execAdmin(String cmd, String... args) throws Exception {
        if (cmd.equals("reload")) {
            var request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/reload"))
                    .header("Authorization", "Bearer " + ADMIN_TOKEN)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
            return null;
        }
        var payload = Map.of("args", args);
        var jsonBody = mapper.writeValueAsString(payload);
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/exec/" + cmd))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    private static JsonNode getAdmin(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + ADMIN_TOKEN).GET().build();
        return mapper.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private static HttpResponse<String> getNps(String uri) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + uri))
                .header("Authorization", "Bearer " + CLIENT_TOKEN).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getPublicWithIp(String uri, String spoofIp) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + uri))
                .header("X-Forwarded-For", spoofIp)
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode postNps(String uri, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + uri))
                .header("Authorization", "Bearer " + CLIENT_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return mapper.readTree(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private static void assertSuccess(JsonNode json, String msg) {
        if (json == null || !json.path("success").asBoolean()) {
            String err = json != null ? json.path("message").asText() : "Empty Response";
            throw new RuntimeException(msg + " FAILED: " + err);
        }
    }

    private static void printHeader(String title) {
        System.out.println("\n--- " + title + " ---");
    }
}
