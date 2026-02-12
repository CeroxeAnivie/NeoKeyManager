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

            // 1. 重载配置文件
            Config.load();

            // [新增] 2. 重载节点鉴权白名单 (NodeAuth.json)
            NodeAuthManager.getInstance().load();

            // 3. 重启 Web 服务
            startWebServer();

            ServerLogger.infoWithSource("System", "nkm.info.reloadComplete");
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

            // 为所有 API 路径注册 Handler 并绑定安全延迟拦截器
            httpServer.createContext("/api/exec", adminHandler);
            httpServer.createContext("/api/query", adminHandler);
            httpServer.createContext("/api/querynomap", adminHandler);
            httpServer.createContext("/api/lp", adminHandler);
            httpServer.createContext("/api/lpnomap", adminHandler);
            httpServer.createContext("/api/reload", adminHandler);
            httpServer.createContext("/api", new KeyHandler());

            // 使用虚拟线程执行器：确保 sleep(200) 不会浪费系统线程资源
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
        }
    }

    private static void stopWebServer() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
        }
    }

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

                    // [新增] CBM 命令
                    case "setcbm" -> keyService.execSetCbm(subArgs);
                    case "delcbm" -> keyService.execDelCbm(subArgs);
                    case "listcbm" -> keyService.execListCbm(subArgs);

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

        myConsole.registerCommand("list", ServerLogger.getMessage("nkm.usage.help.listActive"), args -> handleTopLevelList());
        myConsole.registerCommand("reload", "Reload System", args -> handleReload());
        myConsole.registerCommand("stop", "Stop System", args -> System.exit(0));
    }

    // ==================== Command Handlers (CLI) ====================

    private static void printKeyUsage() {
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.add"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.set"));
        myConsole.log("Usage", "key setconn <key> <num> - Set max connections");
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.del"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.map"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.delmap"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.list"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.lp"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.toggle"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.web"));
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.help.link"));
        myConsole.log("Usage", "key setsingle/delsingle/listsingle ... - Manage Single Mode");
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.cbm")); // 新增用法
        myConsole.log("Usage", ServerLogger.getMessage("nkm.usage.portNote"));
    }

    private static void handleLookupKey(List<String> args) {
        if (args.size() != 1) {
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

        // [修改] 增加列宽适配别名
        int maxNameLen = 16, maxNodeLen = 15, maxPortLen = 6;
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
                // [修改] 转换为别名
                String realNodeId = nodeEntry.getKey();
                String alias = NodeAuthManager.getInstance().getAlias(realNodeId);
                maxNodeLen = Math.max(maxNodeLen, alias.length());
                maxPortLen = Math.max(maxPortLen, nodeEntry.getValue().length());
            }
        }
        maxNameLen += 2;
        maxNodeLen += 2;
        maxPortLen += 2;
        String headerFmt = "   %-" + maxNameLen + "s %-12s %-" + maxNodeLen + "s %-" + maxPortLen + "s";
        String rowFmtKey = "   %-" + maxNameLen + "s %-12s %-" + maxNodeLen + "s %-" + maxPortLen + "s";
        String rowFmtSub = "   %-" + maxNameLen + "s %-12s %-" + maxNodeLen + "s %-" + maxPortLen + "s";
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        int totalWidth = 3 + maxNameLen + 1 + 12 + 1 + maxNodeLen + 1 + maxPortLen;
        String separator = "-".repeat(totalWidth);
        sb.append(separator).append("\n");
        sb.append(String.format(headerFmt, "SESSION (Link->Real)", "OCCUPANCY", "NODE", "PORT (DETAIL)")).append("\n");
        sb.append(separator).append("\n");
        for (Map.Entry<String, Map<String, String>> entry : activeSessions.entrySet()) {
            String displayKey = entry.getKey();
            String showName = displayNames.get(displayKey);
            String usage = occupancyMap.get(displayKey);
            Map<String, String> nodes = entry.getValue();
            boolean isFirstNode = true;
            for (Map.Entry<String, String> nodeEntry : nodes.entrySet()) {
                String realNodeId = nodeEntry.getKey();
                // [修改] 显示别名
                String alias = NodeAuthManager.getInstance().getAlias(realNodeId);
                String portString = nodeEntry.getValue(); // 已包含详情
                if (isFirstNode) {
                    sb.append(String.format(rowFmtKey, showName, usage, alias, portString)).append("\n");
                    isFirstNode = false;
                } else {
                    sb.append(String.format(rowFmtSub, "", "", alias, portString)).append("\n");
                }
            }
            sb.append(separator).append("\n");
        }
        if (myConsole != null) myConsole.log("KeyManager", sb.toString());
    }
}