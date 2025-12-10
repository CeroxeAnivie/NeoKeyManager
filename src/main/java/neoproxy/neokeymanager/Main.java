package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import plethora.utils.MyConsole;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final String PORT_INPUT_PATTERN = "^(\\d+)(?:-(\\d+))?$";
    private static final Pattern PORT_INPUT_REGEX = Pattern.compile(PORT_INPUT_PATTERN);

    public static MyConsole myConsole;
    private static HttpServer httpServer;

    public static void main(String[] args) {
        checkARGS(args);
        try {
            Config.load();
            myConsole = new MyConsole("NeoKeyManager");
            myConsole.printWelcome = false;
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
            myConsole.log("NeoKeyManager", "Initializing...");

            Database.init();
            registerCommands();
            startWebServer();
            myConsole.start();
        } catch (Exception e) {
            e.printStackTrace();
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

    // ==================== Command Handlers ====================

    private static void handleMapKey(List<String> args) {
        if (args.size() != 3) {
            ServerLogger.warnWithSource("Usage", "nkm.usage.map");
            return;
        }
        String name = args.get(0);
        String nodeId = args.get(1);
        String mapPort = args.get(2);

        String validMapPort = validateAndFormatPortInput(mapPort);
        if (validMapPort == null) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.mappingPortInvalid", mapPort);
            return;
        }

        Map<String, Object> keyInfo = Database.getKeyPortInfo(name);
        if (keyInfo == null) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", name);
            return;
        }

        String defaultPort = (String) keyInfo.get("default_port");
        boolean defaultIsDynamic = PortUtils.isDynamic(defaultPort);
        boolean mapIsDynamic = PortUtils.isDynamic(validMapPort);

        if (!defaultIsDynamic && mapIsDynamic) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.mapStaticToDynamic", name, defaultPort, validMapPort);
            return;
        }

        Database.addNodePort(name, nodeId, validMapPort);
        ServerLogger.infoWithSource("KeyManager", "nkm.info.mappingUpdated", name, nodeId, validMapPort);
    }

    /**
     * [Task 1] 实现 key delmap <key> <node-id>
     */
    private static void handleDelMapKey(List<String> args) {
        if (args.size() != 2) {
            ServerLogger.warnWithSource("Usage", "Usage: key delmap <key> <node-id>");
            return;
        }
        String name = args.get(0);
        String nodeId = args.get(1);

        if (!Database.keyExists(name)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", name);
            return;
        }

        if (Database.deleteNodeMap(name, nodeId)) {
            ServerLogger.infoWithSource("KeyManager", "nkm.info.mappingDeleted", name, nodeId);
        } else {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.delMapFailed", name, nodeId);
        }
    }

    /**
     * [Task 4] 实现 key lp <key>
     */
    private static void handleLookupKey(List<String> args) {
        if (args.size() != 1) {
            ServerLogger.warnWithSource("Usage", "Usage: key lp <key>");
            return;
        }
        String targetKey = args.get(0);
        if (!Database.keyExists(targetKey)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", targetKey);
            return;
        }
        // 复用 list 逻辑，但进行过滤
        printKeyTable(targetKey, false);
    }

    private static void handleSetKey(List<String> args) {
        if (args.size() < 2) {
            ServerLogger.warnWithSource("Usage", "nkm.usage.set");
            return;
        }
        String name = args.get(0);

        if (!Database.keyExists(name)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFoundAdd", name);
            return;
        }

        Map<String, Object> oldInfo = Database.getKeyPortInfo(name);
        String oldPort = (String) oldInfo.get("default_port");
        boolean oldIsDynamic = PortUtils.isDynamic(oldPort);

        Double newBalance = null;
        Double newRate = null;
        String newPort = null;
        String newExpireTime = null;
        Boolean newWeb = null;

        for (int i = 1; i < args.size(); i++) {
            String param = args.get(i);
            if (param.startsWith("b=")) newBalance = parseDoubleSafely(param.substring(2), "balance");
            else if (param.startsWith("r=")) newRate = parseDoubleSafely(param.substring(2), "rate");
            else if (param.startsWith("p=")) {
                String rawPort = param.substring(2);
                newPort = validateAndFormatPortInput(rawPort);
                if (newPort == null) {
                    ServerLogger.errorWithSource("KeyManager", "nkm.error.portInvalid", rawPort);
                    return;
                }
            } else if (param.startsWith("t=")) newExpireTime = correctInputTime(param.substring(2));
            else if (param.startsWith("w=")) newWeb = parseBoolean(param.substring(2));
        }

        boolean needCleanMap = false;
        if (newPort != null) {
            boolean newIsDynamic = PortUtils.isDynamic(newPort);
            if (oldIsDynamic && !newIsDynamic) {
                needCleanMap = true;
                ServerLogger.warnWithSource("KeyManager", "nkm.warn.typeChangeClean", name);
            }
        }

        Database.updateKey(name, newBalance, newRate, newPort, newExpireTime, newWeb);

        if (needCleanMap) {
            Database.deleteNodeMapsByKey(name);
            ServerLogger.infoWithSource("KeyManager", "nkm.info.mapsCleanedForCompatibility", name);
        }

        if (newPort != null) SessionManager.getInstance().forceReleaseKey(name);

        ServerLogger.infoWithSource("KeyManager", "nkm.info.keyUpdated", name);
    }

    /**
     * [Task 2] 修改 key list 逻辑，支持 nomap
     */
    private static void handleListKeys(List<String> args) {
        boolean noMap = args.contains("nomap");
        printKeyTable(null, noMap);
    }

    /**
     * 通用表格打印方法
     *
     * @param targetKeyFilter 如果不为null，只打印此Key及其Map
     * @param noMap           如果为true，不打印MAP行，并在Key行显示Count
     */
    private static void printKeyTable(String targetKeyFilter, boolean noMap) {
        List<Map<String, String>> rows = Database.getAllKeysRaw();
        if (rows.isEmpty()) {
            ServerLogger.infoWithSource("KeyManager", "nkm.info.noKeys");
            return;
        }

        // 过滤数据 (lp <key> 逻辑)
        if (targetKeyFilter != null) {
            List<Map<String, String>> filtered = new ArrayList<>();
            for (Map<String, String> row : rows) {
                // 如果是Key行且名字匹配
                if ("KEY".equals(row.get("type")) && targetKeyFilter.equals(row.get("name"))) {
                    filtered.add(row);
                }
                // 如果是Map行且parent_key匹配
                else if ("MAP".equals(row.get("type")) && targetKeyFilter.equals(row.get("parent_key"))) {
                    filtered.add(row);
                }
            }
            rows = filtered;
            if (rows.isEmpty()) {
                // 理论上前面 keyExists 检查过了，但双重保险
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

        String headerFmt;
        String rowFmt;

        if (noMap) {
            // [Task 2] nomap 格式: 添加 MAPS 列
            headerFmt = "   %-7s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s %-6s";
            rowFmt = "   %-16s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s %-6s";
        } else {
            headerFmt = "   %-7s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s";
            rowFmt = "   %-16s %-" + maxNameLen + "s %-12s %-8s %-16s %-6s %-18s %-4s";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        String separator = "-".repeat(maxNameLen + (noMap ? 100 : 90)); // 略微加宽

        sb.append(separator).append("\n");
        if (noMap) {
            sb.append(String.format(headerFmt, "ENABLED", "NAME", "BALANCE", "RATE", "PORT", "CONNS", "EXPIRE", "WEB", "MAPS")).append("\n");
        } else {
            sb.append(String.format(headerFmt, "ENABLED", "NAME", "BALANCE", "RATE", "PORT", "CONNS", "EXPIRE", "WEB")).append("\n");
        }
        sb.append(separator).append("\n");

        for (Map<String, String> row : rows) {
            if ("KEY".equals(row.get("type"))) {
                if (noMap) {
                    sb.append(String.format(rowFmt,
                            row.get("status_icon"),
                            row.get("name"),
                            row.get("balance"),
                            row.get("rate"),
                            row.get("port"),
                            row.get("conns"),
                            row.get("expire"),
                            row.get("web"),
                            row.get("map_count") // [Task 2] 显示 Map 数量
                    )).append("\n");
                } else {
                    sb.append(String.format(rowFmt,
                            row.get("status_icon"),
                            row.get("name"),
                            row.get("balance"),
                            row.get("rate"),
                            row.get("port"),
                            row.get("conns"),
                            row.get("expire"),
                            row.get("web")
                    )).append("\n");
                }
            } else if ("MAP".equals(row.get("type"))) {
                // 如果是 nomap 模式，跳过 MAP 行
                if (noMap) continue;

                int indentSize = 11 + maxNameLen + 13 + 9;
                String mapIndent = " ".repeat(indentSize);
                sb.append(mapIndent).append(row.get("map_str")).append("\n");
            }
        }
        sb.append(separator);

        if (myConsole != null) myConsole.log("KeyManager", sb.toString());
        else System.out.println(sb.toString());
    }

    private static void handleAddKey(List<String> args) {
        if (args.size() != 5 && args.size() != 6) {
            ServerLogger.warnWithSource("Usage", "nkm.usage.add");
            ServerLogger.warnWithSource("Usage", "nkm.usage.portNote");
            return;
        }
        String name = args.get(0);

        if (Database.keyExists(name)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyExists", name);
            return;
        }

        Double balance = parseDoubleSafely(args.get(1), "balance");
        if (balance == null) return;

        String expireTimeInput = args.get(2);
        String expireTime = correctInputTime(expireTimeInput);
        if (expireTime == null) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.timeFormat", expireTimeInput);
            return;
        }

        String portStr = args.get(3);
        String validatedPortStr = validateAndFormatPortInput(portStr);
        if (validatedPortStr == null) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.portInvalid", portStr);
            return;
        }

        Double rate = parseDoubleSafely(args.get(4), "rate");
        if (rate == null) return;

        boolean enableWebHTML = false;
        if (args.size() == 6) {
            String webStr = args.get(5).toLowerCase();
            enableWebHTML = webStr.equals("true") || webStr.equals("1") || webStr.equals("on");
        }

        int maxConns = PortUtils.calculateSize(validatedPortStr);
        if (Database.addKey(name, balance, rate, expireTime, validatedPortStr, maxConns)) {
            if (enableWebHTML) {
                Database.setWebStatus(name, true);
                ServerLogger.infoWithSource("KeyManager", "nkm.info.keyAddedWeb", name);
            } else {
                ServerLogger.infoWithSource("KeyManager", "nkm.info.keyAdded", name);
            }
        } else {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.sqlFail");
        }
    }

    private static void handleToggleKey(List<String> args, boolean enable) {
        if (args.size() != 1) {
            ServerLogger.warnWithSource("Usage", "Usage: key <enable|disable> <name>");
            return;
        }
        String name = args.get(0);
        if (!Database.keyExists(name)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", name);
            return;
        }
        if (Database.setKeyStatus(name, enable)) {
            String status = enable ? "ENABLED" : "DISABLED";
            ServerLogger.infoWithSource("KeyManager", "nkm.info.keyStatus", name, status);
            if (!enable) SessionManager.getInstance().forceReleaseKey(name);
        }
    }

    private static void handleDelKey(List<String> args) {
        if (args.size() != 1) {
            ServerLogger.warnWithSource("Usage", "Usage: key del <name>");
            return;
        }
        String name = args.get(0);
        if (!Database.keyExists(name)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", name);
            return;
        }
        Database.deleteKey(name);
        SessionManager.getInstance().forceReleaseKey(name);
        ServerLogger.infoWithSource("KeyManager", "nkm.info.keyDeleted", name);
    }

    private static void handleReload() {
        Config.load();
        Database.init();
        startWebServer();
        ServerLogger.infoWithSource("System", "nkm.info.reloading");
    }

    public static void shutdown() {
        stopWebServer();
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
                switch (subCmd) {
                    case "add" -> handleAddKey(subArgs);
                    case "set" -> handleSetKey(subArgs);
                    case "map" -> handleMapKey(subArgs);
                    case "delmap" -> handleDelMapKey(subArgs); // [Task 1]
                    case "list" -> handleListKeys(subArgs);    // [Task 2]
                    case "lp" -> handleLookupKey(subArgs);     // [Task 4]
                    case "del" -> handleDelKey(subArgs);
                    case "enable" -> handleToggleKey(subArgs, true);
                    case "disable" -> handleToggleKey(subArgs, false);
                    default -> {
                        // [Task 3] 未知指令提示
                        ServerLogger.errorWithSource("Command", "Unknown subcommand: " + subCmd);
                        printKeyUsage();
                    }
                }
            } catch (Exception e) {
                ServerLogger.error("Command", "nkm.error.execFail", e);
            }
        });

        myConsole.registerCommand("web", "Manage Web", args -> {
            if (args.size() < 2) {
                ServerLogger.warnWithSource("Usage", "Usage: web <enable|disable> <key>");
                return;
            }
            String subCmd = args.get(0).toLowerCase();
            String keyName = args.get(1);
            if (!Database.keyExists(keyName)) {
                ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", keyName);
                return;
            }
            boolean enable = subCmd.equals("enable");
            Database.setWebStatus(keyName, enable);
            ServerLogger.infoWithSource("WebManager", "nkm.info.webStatus", keyName, enable);
        });

        myConsole.registerCommand("reload", "Reload", args -> handleReload());
        myConsole.registerCommand("stop", "Stop", args -> System.exit(0));
    }

    private static void printKeyUsage() {
        myConsole.log("Usage", "  key add <name> <balance> <expireTime> <port> <rate> [webHTML]");
        myConsole.log("Usage", "  key set <name> [b=<bal>] [r=<rate>] [p=<port>] [t=<time>] [w=<web>]");
        myConsole.log("Usage", "  key del <name>");
        myConsole.log("Usage", "  key map <name> <nodeId> <port>");
        myConsole.log("Usage", "  key delmap <name> <nodeId>");
        myConsole.log("Usage", "  key list [nomap]");
        myConsole.log("Usage", "  key lp <name>");
        myConsole.log("Usage", "  key <enable|disable> <name>");
        myConsole.log("Usage", "  web <enable|disable> <name>");
    }

    private static String validateAndFormatPortInput(String portInput) {
        if (portInput == null) return null;
        Matcher matcher = PORT_INPUT_REGEX.matcher(portInput.trim());
        if (!matcher.matches()) return null;
        try {
            int start = Integer.parseInt(matcher.group(1));
            if (start < 1 || start > 65535) return null;
            if (matcher.group(2) != null) {
                int end = Integer.parseInt(matcher.group(2));
                if (end < 1 || end > 65535 || end < start) return null;
                return start + "-" + end;
            }
            return String.valueOf(start);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleSafely(String str, String fieldName) {
        try {
            return Double.parseDouble(str);
        } catch (Exception e) {
            ServerLogger.errorWithSource("KeyManager", "Invalid value for " + fieldName + ": " + str);
            return null;
        }
    }

    private static Boolean parseBoolean(String str) {
        if (str == null) return null;
        return str.equalsIgnoreCase("true") || str.equals("1") || str.equalsIgnoreCase("on");
    }

    private static String correctInputTime(String time) {
        if (time == null) return null;
        if (!time.matches("^\\d{4}/\\d{1,2}/\\d{1,2}-\\d{1,2}:\\d{1,2}$")) return null;
        return time;
    }
}