package neoproxy.neokeymanager.manager;

import neoproxy.neokeymanager.config.Config;
import neoproxy.neokeymanager.model.Protocol;
import neoproxy.neokeymanager.utils.ServerLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class NodeManagerTest {

    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    private Path tempDir;
    private String originalNodeAuthFile;
    private String originalNodeJsonFile;

    @BeforeEach
    void setUp() throws Exception {
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        ServerLogger.setLocale(Locale.SIMPLIFIED_CHINESE);

        tempDir = Files.createTempDirectory("nodemanager_test");
        originalNodeAuthFile = System.getProperty("node.auth.file", "NodeAuth.json");
        originalNodeJsonFile = Config.NODE_JSON_FILE;

        System.setProperty("node.auth.file", tempDir.resolve("NodeAuth.json").toString());
        Config.resetToDefaults();
        Config.NODE_JSON_FILE = tempDir.resolve("nodes.json").toString();

        Files.writeString(Path.of(System.getProperty("node.auth.file")),
                """
                        {
                          "node-suqian": {
                            "realId": "node-suqian",
                            "displayName": "节点宿迁"
                          },
                          "node-shanghai": {
                            "realId": "node-shanghai",
                            "displayName": "节点上海"
                          }
                        }
                        """,
                StandardCharsets.UTF_8);
        Files.writeString(Path.of(Config.NODE_JSON_FILE),
                """
                        [
                          {
                            "realId": "node-suqian",
                            "name": "节点宿迁",
                            "address": "p.ceroxe.top",
                            "HOST_HOOK_PORT": 44801,
                            "HOST_CONNECT_PORT": 44802
                          },
                          {
                            "realId": "node-shanghai",
                            "name": "节点上海",
                            "address": "sh.ceroxe.top",
                            "HOST_HOOK_PORT": 44811,
                            "HOST_CONNECT_PORT": 44812
                          }
                        ]
                        """,
                StandardCharsets.UTF_8);

        NodeAuthManager.resetInstance();
        NodeManager.getInstance().loadNodesJson();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setOut(originalOut);
        ServerLogger.setLocale(Locale.getDefault());
        System.setProperty("node.auth.file", originalNodeAuthFile);
        Config.NODE_JSON_FILE = originalNodeJsonFile;
        Config.resetToDefaults();

        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((left, right) -> -left.compareTo(right))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    void recordNodeStatusShouldNotLogWhenRuntimeEndpointIsUnchanged() {
        NodeManager manager = NodeManager.getInstance();
        Protocol.NodeStatusPayload payload = new Protocol.NodeStatusPayload();
        payload.nodeId = "node-suqian";
        payload.address = "p.ceroxe.top";
        payload.hookPort = 44801;
        payload.connectPort = 44802;
        payload.version = "1.0.0";
        payload.activeTunnels = 3;

        boolean result = manager.recordNodeStatus(payload);

        assertThat(result).isTrue();
        assertThat(outContent.toString(StandardCharsets.UTF_8)).doesNotContain("运行时地址已更新");
    }

    @Test
    void recordNodeStatusShouldLogWhenRuntimeEndpointChanges() {
        NodeManager manager = NodeManager.getInstance();
        Protocol.NodeStatusPayload payload = new Protocol.NodeStatusPayload();
        payload.nodeId = "node-suqian";
        payload.address = "new.ceroxe.top";
        payload.hookPort = 55001;
        payload.connectPort = 55002;
        payload.version = "1.0.0";
        payload.activeTunnels = 3;

        boolean result = manager.recordNodeStatus(payload);

        assertThat(result).isTrue();
        assertThat(outContent.toString(StandardCharsets.UTF_8))
                .contains("运行时地址已更新")
                .contains("new.ceroxe.top:55001/55002");
    }

    @Test
    void recordNodeStatusShouldPreserveConfiguredNodeOrder() throws Exception {
        NodeManager manager = NodeManager.getInstance();
        Protocol.NodeStatusPayload payload = new Protocol.NodeStatusPayload();
        payload.nodeId = "node-suqian";
        payload.address = "p.ceroxe.top";
        payload.hookPort = 44801;
        payload.connectPort = 44802;
        payload.version = "1.0.0";
        payload.activeTunnels = 3;

        boolean result = manager.recordNodeStatus(payload);

        assertThat(result).isTrue();

        String persistedNodes = Files.readString(Path.of(Config.NODE_JSON_FILE), StandardCharsets.UTF_8);
        assertThat(persistedNodes.indexOf("\"realId\" : \"node-suqian\""))
                .isLessThan(persistedNodes.indexOf("\"realId\" : \"node-shanghai\""));
        assertThat(persistedNodes)
                .doesNotContain("\"version\"")
                .doesNotContain("\"online\"")
                .doesNotContain("\"lastSeen\"")
                .doesNotContain("\"activeTunnels\"");
    }
}
