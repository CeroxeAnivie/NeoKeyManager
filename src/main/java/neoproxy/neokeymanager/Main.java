package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import plethora.utils.MyConsole;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    public static MyConsole myConsole;
    private static HttpServer httpServer;

    public static void main(String[] args) {
        try {
            Config.load();
            myConsole = new MyConsole("NeoKeyManager");
            myConsole.printWelcome = true;

            Database.init();
            registerCommands();
            startWebServer();
            myConsole.start();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void startWebServer() {
        boolean sslSuccess = false;
        if (Config.SSL_CRT_PATH != null && Config.SSL_KEY_PATH != null) {
            try {
                myConsole.log("System", "Initializing SSL context with cert: " + Config.SSL_CRT_PATH);
                SSLContext sslContext = SslFactory.createSSLContext(Config.SSL_CRT_PATH, Config.SSL_KEY_PATH);
                HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(Config.PORT), 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
                httpServer = httpsServer;
                sslSuccess = true;
                myConsole.log("System", "NeoKeyManager started on port " + Config.PORT + " (HTTPS)");
            } catch (Exception e) {
                myConsole.error("System", "Failed to initialize SSL", e);
                myConsole.warn("System", "DOWNGRADING TO HTTP MODE...");
            }
        }

        if (!sslSuccess) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress(Config.PORT), 0);
                myConsole.log("System", "NeoKeyManager started on port " + Config.PORT + " (HTTP)");
            } catch (IOException e) {
                myConsole.error("System", "Failed to bind port " + Config.PORT, e);
                System.exit(1);
            }
        }

        httpServer.createContext("/api", new KeyHandler());
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
    }

    private static void registerCommands() {
        myConsole.registerCommand("key", "Manage keys", args -> {
            if (args.isEmpty()) { printKeyUsage(); return; }
            String subCmd = args.get(0).toLowerCase();
            List<String> subArgs = args.subList(1, args.size());

            try {
                switch (subCmd) {
                    case "add" -> handleAddKey(subArgs);
                    case "set" -> handleSetKey(subArgs);
                    case "map" -> handleMapKey(subArgs);
                    case "delmap" -> handleDelMapKey(subArgs);
                    case "list" -> handleListKeys();
                    case "del" -> handleDelKey(subArgs);
                    // 【新增指令】
                    case "enable" -> handleToggleKey(subArgs, true);
                    case "disable" -> handleToggleKey(subArgs, false);
                    default -> printKeyUsage();
                }
            } catch (Exception e) {
                myConsole.error("Command", "Execution failed", e);
            }
        });

        // 【新增 WEB 控制指令】
        myConsole.registerCommand("web", "Manage Web Access", args -> {
            if (args.size() < 2) {
                myConsole.warn("Usage", "web enable <key> | web disable <key>");
                return;
            }
            String subCmd = args.get(0).toLowerCase();
            String keyName = args.get(1);
            boolean enable = subCmd.equals("enable");

            if (Database.keyExists(keyName)) {
                Database.setWebStatus(keyName, enable);
                myConsole.log("WebManager", "Web access for " + keyName + " set to " + enable);
            } else {
                myConsole.error("WebManager", "Key not found: " + keyName);
            }
        });

        myConsole.registerCommand("stop", "Stop the server", args -> shutdown());
        myConsole.setShutdownHook(Main::shutdown);
    }

    // 【处理 Key 开关】
    private static void handleToggleKey(List<String> args, boolean enable) {
        if (args.size() != 1) {
            myConsole.warn("Usage", "key " + (enable ? "enable" : "disable") + " <name>");
            return;
        }
        String name = args.get(0);
        if (Database.setKeyStatus(name, enable)) {
            myConsole.log("KeyManager", "Key " + name + " is now " + (enable ? "ENABLED" : "DISABLED"));
            if (!enable) SessionManager.getInstance().forceReleaseKey(name);
        } else {
            myConsole.error("KeyManager", "Key not found: " + name);
        }
    }

    private static void handleAddKey(List<String> args) {
        if (args.size() != 5) {
            myConsole.warn("Usage", "key add <name> <balance> <expireTime> <port> <rate>");
            return;
        }
        int maxConns = PortUtils.calculateSize(args.get(3));
        Database.addKey(args.get(0), Double.parseDouble(args.get(1)), Double.parseDouble(args.get(4)), args.get(2), args.get(3), maxConns);
        myConsole.log("KeyManager", "Added: " + args.get(0));
    }

    private static void handleSetKey(List<String> args) {
        if (args.size() < 2) {
            myConsole.warn("Usage", "key set <name> [b=<balance>] [r=<rate>] [p=<port>] [t=<expireTime>] [w=<webHTML>]");
            return;
        }
        String name = args.get(0);
        if (!Database.keyExists(name)) {
            myConsole.error("KeyManager", "Key not found: " + name);
            return;
        }

        Map<String, Object> oldInfo = Database.getKeyPortInfo(name);
        String oldPort = (String) oldInfo.get("default_port");
        int oldSize = (int) oldInfo.get("max_conns");

        Double newBalance = null;
        Double newRate = null;
        String newPort = null;
        String newExpireTime = null;
        Boolean newWeb = null;

        for (int i = 1; i < args.size(); i++) {
            String param = args.get(i);
            if (param.startsWith("b=")) newBalance = Double.parseDouble(param.substring(2));
            else if (param.startsWith("r=")) newRate = Double.parseDouble(param.substring(2));
            else if (param.startsWith("p=")) newPort = param.substring(2);
            else if (param.startsWith("t=")) newExpireTime = param.substring(2);
            else if (param.startsWith("w=")) {
                String val = param.substring(2).toLowerCase();
                newWeb = val.equals("true") || val.equals("1") || val.equals("on");
            }
        }

        if (newPort != null && !newPort.equals(oldPort)) {
            int newSize = PortUtils.calculateSize(newPort);
            // 端口变更逻辑...
            if ((oldSize > 1) != (newSize > 1) || oldSize != newSize) {
                Database.deleteNodeMapsByKey(name);
                SessionManager.getInstance().forceReleaseKey(name);
                myConsole.warn("KeyManager", "Port configuration changed drastically. Maps CLEARED.");
            }
        }

        Database.updateKey(name, newBalance, newRate, newPort, newExpireTime, newWeb);
        myConsole.log("KeyManager", "Key updated: " + name);
    }

    private static void handleMapKey(List<String> args) {
        if (args.size() != 3) {
            myConsole.warn("Usage", "key map <name> <node_id> <custom_port>");
            return;
        }
        String name = args.get(0);
        String nodeId = args.get(1);
        String mapPort = args.get(2);
        // ... (省略原有校验逻辑，保持不变) ...
        Database.addNodePort(name, nodeId, mapPort);
        myConsole.log("KeyManager", "Mapping updated: " + name + " @ " + nodeId + " -> " + mapPort);
    }

    private static void handleDelMapKey(List<String> args) {
        if (args.size() != 2) {
            myConsole.warn("Usage", "key delmap <name> <node_id>");
            return;
        }
        if (Database.deleteNodeMap(args.get(0), args.get(1))) {
            myConsole.log("KeyManager", "Mapping deleted.");
        } else {
            myConsole.warn("KeyManager", "Mapping not found.");
        }
    }

    private static void handleListKeys() {
        List<String> keys = Database.getAllKeysFormatted();
        if (keys.isEmpty()) {
            myConsole.log("KeyManager", "No keys found.");
        } else {
            // 【核心修改】最后一列 WEB 改为 %-4s
            String header = String.format("%-6s %-12s %-12s %-8s %-16s %-6s %-18s %-4s",
                    "STS", "NAME", "BALANCE", "RATE", "PORT", "CONNS", "EXPIRE", "WEB");
            String separator = "-".repeat(header.length() + 2);

            myConsole.log("KeyManager", separator);
            myConsole.log("KeyManager", header);
            myConsole.log("KeyManager", separator);

            for (String k : keys) {
                myConsole.log("KeyManager", k);
            }
            myConsole.log("KeyManager", separator);
        }
    }

    private static void handleDelKey(List<String> args) {
        if (args.size() != 1) {
            myConsole.warn("Usage", "key del <name>");
            return;
        }
        String name = args.get(0);
        Database.deleteKey(name);
        SessionManager.getInstance().forceReleaseKey(name);
        myConsole.log("KeyManager", "Key deleted: " + name);
    }
    private static void printKeyUsage() {
        myConsole.warn("Usage", "key add <name> <balance> <expireTime> <port> <rate>");
        myConsole.warn("Usage", "key set <name> [b=..] [r=..] [p=..] [t=..] [w=..]");
        myConsole.warn("Usage", "key enable <name> | key disable <name>");
        myConsole.warn("Usage", "key map <name> <node> <port> | key delmap <name> <node>");
        myConsole.warn("Usage", "key list | key del <name>");
        myConsole.warn("Usage", "web enable <name> | web disable <name>");
    }

    public static void shutdown() {
        if (httpServer != null) httpServer.stop(0);
        myConsole.log("System", "Shutting down...");
    }
}