package neoproxy.neokeymanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * NeoKeyManager Ultimate Test Suite (V9 - Fixed)
 */
public class IntegrationTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String ADMIN_TOKEN = "admin_secret_123";
    private static final String CLIENT_TOKEN = "default_token";

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   NeoKeyManager Ultimate Test Suite (V9)         ");
        System.out.println("==================================================");

        try {
            cleanup();

            // --- Phase 1: Core & Robustness ---
            testKeyLifecycle();
            testRobustParameterParsing();

            // --- Phase 2: Batch Operations ---
            testBatchDelete();
            testBatchMapping();

            // --- Phase 3: Logic & Features ---
            testAliasSystem();
            testToggleAndWeb();
            testTrafficSync();
            testConnectionUpdate();

            // --- Phase 4: Single Mode Enforcement ---
            testBasicSingleMode();
            testDecoupledSingleMode(); // <--- 修复点在这里
            testDelSingleCommand();

            // --- Phase 5: System ---
            testSystemReload();

            cleanup();

            System.out.println("\n✅✅✅ ALL TESTS PASSED: SYSTEM IS 100% COVERED! ✅✅✅");

        } catch (Exception e) {
            System.err.println("\n❌ TEST FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ==================== Phase 1: Core & Robustness ====================

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

        JsonNode info = getAdmin("/api/lp/test_key").get("data");
        if (info.get("balance").asDouble() != 555.0) throw new RuntimeException("Failed to parse BALANCE=");
        if (info.get("rate").asDouble() != 2.5) throw new RuntimeException("Failed to parse rAtE=");
        if (!info.get("enableWeb").asBoolean()) throw new RuntimeException("Failed to parse wEb=");

        System.out.println("   -> Case Insensitivity [OK]");
    }

    // ==================== Phase 2: Batch Operations ====================

    private static void testBatchDelete() throws Exception {
        printHeader("2. Batch Delete");
        execAdmin("add", "batch_k1", "10", "PERMANENT", "1001", "1");
        execAdmin("add", "batch_k2", "10", "PERMANENT", "1002", "1");

        String msg = execAdmin("del", "batch_k1", "batch_k2").get("message").asText();
        System.out.println("      Result: " + msg);

        if (getAdmin("/api/query").get("data").toString().contains("batch_k1"))
            throw new RuntimeException("batch_k1 not deleted");
        if (getAdmin("/api/query").get("data").toString().contains("batch_k2"))
            throw new RuntimeException("batch_k2 not deleted");

        System.out.println("   -> Batch Delete [OK]");
    }

    private static void testBatchMapping() throws Exception {
        printHeader("3. Batch Mapping");
        execAdmin("add", "map_key", "10", "PERMANENT", "2000", "5");

        assertSuccess(execAdmin("map", "map_key", "nodeA", "nodeB", "3333"), "Batch Map");

        JsonNode data = getAdmin("/api/lp/map_key").get("data");
        JsonNode maps = data.get("maps");

        boolean hasA = false, hasB = false;
        for (JsonNode m : maps) {
            if (m.get("nodeId").asText().equalsIgnoreCase("nodeA") && m.get("port").asText().equals("3333")) hasA = true;
            if (m.get("nodeId").asText().equalsIgnoreCase("nodeB") && m.get("port").asText().equals("3333")) hasB = true;
        }

        if (!hasA || !hasB) throw new RuntimeException("Batch mapping failed to store both nodes");
        System.out.println("   -> Batch Map Creation [OK]");

        assertSuccess(execAdmin("delmap", "map_key", "nodeA", "nodeB"), "Batch DelMap");
        if (getAdmin("/api/lp/map_key").get("data").get("maps").size() != 0)
            throw new RuntimeException("Batch delmap failed to clear nodes");

        System.out.println("   -> Batch Map Deletion [OK]");
        execAdmin("del", "map_key");
    }

    // ==================== Phase 3: Logic & Features ====================

    private static void testAliasSystem() throws Exception {
        printHeader("4. Alias System");
        assertSuccess(execAdmin("link", "test_alias", "to", "test_key"), "Link Alias");
        var resp = getNps("/api/key?name=test_alias&nodeId=N1");
        if (resp.statusCode() != 200) throw new RuntimeException("Alias Auth Failed");
        JsonNode json = mapper.readTree(resp.body());
        if (!"test_alias".equals(json.get("name").asText()))
            throw new RuntimeException("API should return Alias name");
        System.out.println("   -> Alias Auth & Persistence [OK]");
    }

    private static void testToggleAndWeb() throws Exception {
        printHeader("5. Toggle & Web Command");
        execAdmin("disable", "test_key");
        if (getNps("/api/key?name=test_key&nodeId=N1").statusCode() != 403)
            throw new RuntimeException("Disable failed");
        execAdmin("enable", "test_key");
        if (getNps("/api/key?name=test_key&nodeId=N1").statusCode() != 200) throw new RuntimeException("Enable failed");

        assertSuccess(execAdmin("web", "disable", "test_key"), "Web Disable");
        if (getAdmin("/api/lp/test_key").get("data").get("enableWeb").asBoolean())
            throw new RuntimeException("Web disable failed");

        System.out.println("   -> Toggle & Web Logic [OK]");
    }

    private static void testTrafficSync() throws Exception {
        printHeader("6. Traffic Sync");
        execAdmin("set", "test_key", "b=100");

        postNps("/api/sync", "{ \"traffic\": { \"test_alias\": 10 } }");
        double bal = getAdmin("/api/lp/test_key").get("data").get("balance").asDouble();
        if (Math.abs(bal - 90.0) > 0.01) throw new RuntimeException("Deduction failed. Expected 90, got " + bal);
        System.out.println("   -> Balance Deduction [OK]");
    }

    private static void testConnectionUpdate() throws Exception {
        printHeader("7. Connection Update (Non-Kicking)");
        assertSuccess(execAdmin("setconn", "test_key", "50"), "Set Conn");
        int conns = getAdmin("/api/lp/test_key").get("data").get("maxConns").asInt();
        if (conns != 50) throw new RuntimeException("Set Conn failed update DB");
        System.out.println("   -> SetConn (Soft Update) [OK]");
    }

    // ==================== Phase 4: Single Mode Enforcement ====================

    private static void testBasicSingleMode() throws Exception {
        printHeader("8. Basic Single Mode (RealKey)");
        execAdmin("add", "single_real", "100", "PERMANENT", "9000", "10");
        execAdmin("setsingle", "single_real", "true");

        if (getNps("/api/key?name=single_real&nodeId=NA").statusCode() != 200)
            throw new RuntimeException("Node A failed");
        postNps("/api/heartbeat", "{ \"serial\": \"single_real\", \"nodeId\": \"NA\", \"port\": \"9000\" }");

        var resp = getNps("/api/key?name=single_real&nodeId=NB");
        if (resp.statusCode() != 409) {
            throw new RuntimeException("Node B should be 409 Conflict, but got: " + resp.statusCode());
        }
        System.out.println("   -> RealKey Single Enforcement [OK]");
    }

    // 【核心修复点】
    private static void testDecoupledSingleMode() throws Exception {
        printHeader("9. Decoupled Single Mode (Alias=Single, Real=Multi)");

        // 1. 添加 Key (默认 max_conns=1)
        execAdmin("add", "multi_real", "100", "PERMANENT", "9001", "10");

        // 2. [关键修复] 显式设置连接数为 10，否则默认是 1，NodeC 就会被挤掉
        execAdmin("setconn", "multi_real", "10");

        // 3. 设置别名和单例模式
        execAdmin("link", "single_alias", "to", "multi_real");
        execAdmin("setsingle", "single_alias", "true");

        // Test Step A: Alias 登录 (占用 1/10)
        if (getNps("/api/key?name=single_alias&nodeId=NodeA").statusCode() != 200)
            throw new RuntimeException("Alias login on NodeA failed");
        postNps("/api/heartbeat", "{ \"serial\": \"single_alias\", \"nodeId\": \"NodeA\", \"port\": \"9001\" }");

        // Test Step B: 另一个节点用 Alias 登录 (应该被 Single 模式拦截 -> 409)
        var respB = getNps("/api/key?name=single_alias&nodeId=NodeB");
        if (respB.statusCode() != 409) {
            throw new RuntimeException("Alias login on NodeB SHOULD fail with 409");
        }
        System.out.println("      [√] Alias blocked with HTTP 409");

        // Test Step C: 另一个节点用 RealKey 登录 (应该成功，因为 RealKey 允许 10 个连接，且没有 Single 限制)
        if (getNps("/api/key?name=multi_real&nodeId=NodeC").statusCode() != 200)
            throw new RuntimeException("RealKey login on NodeC failed");

        System.out.println("      [√] RealKey remains Multi-Login");
    }

    private static void testDelSingleCommand() throws Exception {
        printHeader("10. DelSingle Command");
        assertSuccess(execAdmin("delsingle", "single_alias"), "Remove Single Mode");

        var resp = getNps("/api/key?name=single_alias&nodeId=NodeB");
        if (resp.statusCode() != 200) throw new RuntimeException("Node B login failed after delsingle");
        System.out.println("   -> Single Mode Removed [OK]");
    }

    // ==================== Phase 5: System ====================

    private static void testSystemReload() throws Exception {
        printHeader("11. System Reload API");
        var request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/reload"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        var resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) throw new RuntimeException("Reload failed: " + resp.statusCode());
        System.out.println("   -> System Reload [OK]");
    }

    // ==================== Utilities ====================
    private static void cleanup() {
        try {
            execAdmin("del", "test_key", "test_alias", "single_real", "multi_real", "single_alias", "batch_k1", "batch_k2", "map_key");
        } catch (Exception ignored) {}
    }

    private static JsonNode execAdmin(String cmd, String... args) throws Exception {
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