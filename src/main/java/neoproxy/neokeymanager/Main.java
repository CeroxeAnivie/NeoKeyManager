package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import fun.ceroxe.api.utils.MyConsole;
import neoproxy.neokeymanager.admin.AdminHandler;
import neoproxy.neokeymanager.admin.KeyService;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    private static final KeyService keyService = new KeyService();
    public static MyConsole myConsole;
    private static HttpServer httpServer;

    public static void main(String[] args) throws IOException {
        myConsole = new MyConsole("NeoKeyManager");
        myConsole.printWelcome = false;

        checkARGS(args);

        try {
            Config.load();
            myConsole.log("NeoKeyManager", "\n" + """
                    
                       _____                                    \s
                      / ____|                                   \s
                     | |        ___   _ __    ___   __  __   ___\s
                     | |       / _ \\ | '__|  / _ \\  \\ \\/ /  / _ \\
                     | |____  |  __/ | |    | (_) |  >  <  |  __/
                      \\_____|  \\___| |_|     \\___/  /_/\\_\\  \\___|
                                                                \s
                                                                 \
                    """);

            ServerLogger.infoWithSource("System", "nkm.system.init");

            Database.init();
            registerCommands();
            startWebServer();
            myConsole.start();

        } catch (Exception e) {
            ServerLogger.error("System", "nkm.error.startupFail", e);
            System.exit(1);
        }
    }

    private static void checkARGS(String[] args) {
        for (String arg : args) {
            switch (arg) {
                case "--zh-cn" -> ServerLogger.setLocale(Locale.SIMPLIFIED_CHINESE);
                case "--en-us" -> ServerLogger.setLocale(Locale.US);
            }
        }
    }

    public static void handleReload() {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            ServerLogger.infoWithSource("System", "nkm.info.reloading");
            Config.load();
            startWebServer();
        }, "NKM-Reloader").start();
    }

    private static void startWebServer() {
        stopWebServer();
        boolean sslSuccess = false;

        if (Config.SSL_CRT_PATH != null && Config.SSL_KEY_PATH != null) {
            try {
                SSLContext sslContext = SslFactory.createSSLContext(Config.SSL_CRT_PATH, Config.SSL_KEY_PATH);
                HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(Config.PORT), 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
                httpServer = httpsServer;
                sslSuccess = true;
                ServerLogger.infoWithSource("System", "nkm.system.startedHttps", Config.PORT);
            } catch (Exception e) {
                ServerLogger.error("System", "nkm.system.sslFail", e);
            }
        }

        if (!sslSuccess) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress(Config.PORT), 0);
                ServerLogger.infoWithSource("System", "nkm.system.startedHttp", Config.PORT);
            } catch (IOException e) {
                ServerLogger.error("System", "nkm.system.bindFail", e, Config.PORT);
            }
        }

        if (httpServer != null) {
            AdminHandler adminHandler = new AdminHandler();
            httpServer.createContext("/api/exec", adminHandler);
            httpServer.createContext("/api/query", adminHandler);
            httpServer.createContext("/api/querynomap", adminHandler);
            httpServer.createContext("/api/lp", adminHandler);
            httpServer.createContext("/api/lpnomap", adminHandler);
            httpServer.createContext("/api/reload", adminHandler);
            httpServer.createContext("/api", new KeyHandler());
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
        }
    }

    private static void stopWebServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    // ==================== Command Handlers (CLI) ====================

    private static void registerCommands() {
        myConsole.registerCommand("key", "Manage keys", args -> {
            if (args.isEmpty()) {
                printKeyUsage();
                return;
            }
            String subCmd = args.get(0).toLowerCase();
            List<String> subArgs = args.subList(1, args.size());
            try {
                String result = switch (subCmd) {
                    case "add" -> keyService.execAddKey(subArgs);
                    case "set" -> keyService.execSetKey(subArgs);
                    case "setconn" -> keyService.execSetConn(subArgs);
                    case "setsingle" -> keyService.execSetSingle(subArgs);
                    case "delsingle" -> keyService.execDelSingle(subArgs);
                    case "listsingle" -> keyService.execListSingle(subArgs);
                    case "map" -> keyService.execMapKey(subArgs);
                    case "delmap" -> keyService.execDelMap(subArgs);
                    case "del" -> keyService.execDelKey(subArgs);
                    case "enable" -> keyService.execEnable(subArgs, true);
                    case "disable" -> keyService.execEnable(subArgs, false);
                    case "link" -> keyService.execLinkKey(subArgs);
                    case "listlink" -> keyService.execListLink(subArgs);
                    case "list" -> {
                        handleListKeys(subArgs);
                        yield null;
                    }
                    case "lp" -> {
                        handleLookupKey(subArgs);
                        yield null;
                    }
                    case "web" -> keyService.execWeb(subArgs);
                    default -> {
                        ServerLogger.errorWithSource("Command", "nkm.error.unknownSubCommand", subCmd);
                        printKeyUsage();
                        yield null;
                    }
                };

                if (result != null) {
                    myConsole.log("KeyManager", result);
                }
            } catch (Exception e) {
                ServerLogger.error("Command", "nkm.error.execFail", e);
            }
        });

        myConsole.registerCommand("web", "Manage Web", args -> {
            try {
                String res = keyService.execWeb(args);
                myConsole.log("WebManager", res);
            } catch (Exception e) {
                ServerLogger.error("WebManager", "nkm.error.execFail", e);
            }
        });

        // 这里使用了 listActive 键的含义
        myConsole.registerCommand("list", ServerLogger.getMessage("nkm.usage.help.listActive"), args -> handleTopLevelList());
        myConsole.registerCommand("reload", "Reload System", args -> handleReload());
        myConsole.registerCommand("stop", "Stop System", args -> System.exit(0));
    }

    /**
     * 【修正】现在完全使用 messages.properties 中的 help 键
     * 覆盖了你截图中的所有灰色键值
     */
    private static void printKeyUsage() {
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.add"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.set"));
        myConsole.log("Usage", "key setconn <key> <num> - Set max connections"); // 截图中没有 setconn 的 help key，暂时保留硬编码
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.del"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.map"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.delmap"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.list"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.lp"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.toggle"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.web"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.link"));
        myConsole.log("Usage", "key setsingle/delsingle/listsingle ... - Manage Single Mode");

        // 使用截图中最后的那个 Note
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.portNote"));
    }

    private static void handleLookupKey(List<String> args) {
        if (args.size() != 1) {
            // 使用 nkm.usage.lp
            ServerLogger.warnWithSource("Usage", "nkm.usage.lp");
            return;
        }
        String targetKey = args.get(0);
        String realName = Database.getRealKeyName(targetKey);
        if (realName == null) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", targetKey);
            return;
        }
        printKeyTable(realName, false);
    }

    // ... (HandleListKeys, printKeyTable, handleTopLevelList 保持原样，与上一版一致) ...
    // 为了节省篇幅，这里省略重复的 Display Logic 代码，请直接复用上一版 Main.java 的底部代码
    private static void handleListKeys(List<String> args) {
        if (args.contains("active")) {
            handleTopLevelList();
            return;
        }
        boolean noMap = args.contains("nomap");
        printKeyTable(null, noMap);
    }

    private static void printKeyTable(String targetKeyFilter, boolean noMap) {
        List<Map<String, String>> rows = Database.getAllKeysRaw();
        if (rows.isEmpty()) {
            ServerLogger.infoWithSource("KeyManager", "nkm.info.noKeys");
            return;
        }
        if (targetKeyFilter != null) {
            List<Map<String, String>> filtered = new ArrayList<>();
            for (Map<String, String> row : rows) {
                if ("KEY".equals(row.get("type")) && targetKeyFilter.equals(row.get("name"))) {
                    filtered.add(row);
                } else if ("MAP".equals(row.get("type")) && targetKeyFilter.equals(row.get("parent_key"))) {
                    filtered.add(row);
                }
            }
            rows = filtered;
            if (rows.isEmpty()) {
                ServerLogger.infoWithSource("KeyManager", "nkm.info.noKeys");
                return;
            }
        }
        int maxNameLen = 10;
        for (Map<String, String> row : rows) {
            String n = row.get("name");
            if (n != null) maxNameLen = Math.max(maxNameLen, n.length());
        }
        maxNameLen += 2;
        String headerFmt, rowFmt;
        if (noMap) {
            headerFmt = "   %-7s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s %-6s";
            rowFmt = "   %-25s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s %-6s";
        } else {
            headerFmt = "   %-7s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s";
            rowFmt = "   %-25s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        String separator = "-".repeat(maxNameLen + (noMap ? 100 : 90));
        sb.append(separator).append("\n");
        if (noMap) {
            sb.append(String.format(headerFmt, "STATUS", "NAME", "BALANCE", "RATE", "PORT", "CONN", "EXPIRE", "WEB", "MAPS")).append("\n");
        } else {
            sb.append(String.format(headerFmt, "STATUS", "NAME", "BALANCE", "RATE", "PORT", "CONN", "EXPIRE", "WEB")).append("\n");
        }
        sb.append(separator).append("\n");
        for (Map<String, String> row : rows) {
            if ("KEY".equals(row.get("type"))) {
                String mapCount = row.getOrDefault("map_count", "0");
                if (noMap) {
                    sb.append(String.format(rowFmt,
                            row.get("status_icon"), row.get("name"), row.get("balance"), row.get("rate"),
                            row.get("port"), row.get("conns"), row.get("expire"), row.get("web"), mapCount
                    )).append("\n");
                } else {
                    sb.append(String.format(rowFmt,
                            row.get("status_icon"), row.get("name"), row.get("balance"), row.get("rate"),
                            row.get("port"), row.get("conns"), row.get("expire"), row.get("web")
                    )).append("\n");
                }
            } else if ("MAP".equals(row.get("type"))) {
                if (noMap) continue;
                String mapIndent = "   " + " ".repeat(25) + " ".repeat(maxNameLen);
                sb.append(mapIndent).append(row.get("map_str")).append("\n");
            }
        }
        sb.append(separator);
        if (myConsole != null) myConsole.log("KeyManager", sb.toString());
    }

    private static void handleTopLevelList() {
        SessionManager sm = SessionManager.getInstance();
        Map<String, Map<String, String>> activeSessions = sm.getActiveSessionsSnapshot();
        if (activeSessions.isEmpty()) {
            ServerLogger.infoWithSource("KeyManager", "nkm.info.noActiveSessions");
            return;
        }
        int maxNameLen = 16, maxNodeLen = 8, maxPortLen = 6;
        Map<String, String> displayNames = new java.util.HashMap<>();
        Map<String, String> occupancyMap = new java.util.HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : activeSessions.entrySet()) {
            String displayKey = entry.getKey();
            String realKey = Database.getRealKeyName(displayKey);
            String showName = displayKey;
            if (realKey != null && !displayKey.equals(realKey)) showName = displayKey + " -> " + realKey;
            displayNames.put(displayKey, showName);
            maxNameLen = Math.max(maxNameLen, showName.length());
            Map<String, Object> dbInfo = (realKey != null) ? Database.getKeyPortInfo(realKey) : null;
            int maxConns = (dbInfo != null && dbInfo.containsKey("max_conns")) ? (int) dbInfo.get("max_conns") : 0;
            int currentConns = (realKey != null) ? sm.getActiveCount(realKey) : 0;
            occupancyMap.put(displayKey, currentConns + " / " + maxConns);
            for (Map.Entry<String, String> nodeEntry : entry.getValue().entrySet()) {
                maxNodeLen = Math.max(maxNodeLen, nodeEntry.getKey().length());
                maxPortLen = Math.max(maxPortLen, nodeEntry.getValue().length());
            }
        }
        maxNameLen += 2; maxNodeLen += 2; maxPortLen += 2;
        String headerFmt = "   %-" + maxNameLen + "s %-12s %-" + maxNodeLen + "s %-" + maxPortLen + "s";
        String rowFmtKey = "   %-" + maxNameLen + "s %-12s %-" + maxNodeLen + "s %-" + maxPortLen + "s";
        String rowFmtSub = "   %-" + maxNameLen + "s %-12s %-" + maxNodeLen + "s %-" + maxPortLen + "s";
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        int totalWidth = 3 + maxNameLen + 1 + 12 + 1 + maxNodeLen + 1 + maxPortLen;
        String separator = "-".repeat(totalWidth);
        sb.append(separator).append("\n");
        sb.append(String.format(headerFmt, "SESSION (Link->Real)", "OCCUPANCY", "NODE ID", "PORT")).append("\n");
        sb.append(separator).append("\n");
        for (Map.Entry<String, Map<String, String>> entry : activeSessions.entrySet()) {
            String displayKey = entry.getKey();
            String showName = displayNames.get(displayKey);
            String usage = occupancyMap.get(displayKey);
            Map<String, String> nodes = entry.getValue();
            boolean isFirstNode = true;
            for (Map.Entry<String, String> nodeEntry : nodes.entrySet()) {
                String nodeId = nodeEntry.getKey();
                String portString = nodeEntry.getValue();
                if (isFirstNode) {
                    sb.append(String.format(rowFmtKey, showName, usage, nodeId, portString)).append("\n");
                    isFirstNode = false;
                } else {
                    sb.append(String.format(rowFmtSub, "", "", nodeId, portString)).append("\n");
                }
            }
            sb.append(separator).append("\n");
        }
        if (myConsole != null) myConsole.log("KeyManager", sb.toString());
    }
}