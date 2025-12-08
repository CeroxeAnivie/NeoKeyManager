package neoproxy.neokeymanager;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import plethora.utils.MyConsole;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final String TIME_FORMAT_PATTERN = "^(\\d{4})/(\\d{1,2})/(\\d{1,2})-(\\d{1,2}):(\\d{1,2})$";
    private static final Pattern TIME_PATTERN = Pattern.compile(TIME_FORMAT_PATTERN);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd-HH:mm");
    private static final String PORT_INPUT_PATTERN = "^(\\d+)(?:-(\\d+))?$";
    private static final Pattern PORT_INPUT_REGEX = Pattern.compile(PORT_INPUT_PATTERN);

    public static MyConsole myConsole;
    private static HttpServer httpServer;

    public static void main(String[] args) {
        // 1. 优先检查参数设置语言
        checkARGS(args);

        try {
            Config.load();
            myConsole = new MyConsole("NeoKeyManager");
            myConsole.printWelcome = true;
            myConsole.log("NeoKeyManager","\n"+"""
            
               _____                                    \s
              / ____|                                   \s
             | |        ___   _ __    ___   __  __   ___\s
             | |       / _ \\ | '__|  / _ \\  \\ \\/ /  / _ \\
             | |____  |  __/ | |    | (_) |  >  <  |  __/
              \\_____|  \\___| |_|     \\___/  /_/\\_\\  \\___|
                                                        \s
                                                         \
            """);

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
                ServerLogger.infoWithSource("System", "nkm.system.sslInit", Config.SSL_CRT_PATH);
                SSLContext sslContext = SslFactory.createSSLContext(Config.SSL_CRT_PATH, Config.SSL_KEY_PATH);
                HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(Config.PORT), 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
                httpServer = httpsServer;
                sslSuccess = true;
                ServerLogger.infoWithSource("System", "nkm.system.startedHttps", Config.PORT);
            } catch (Exception e) {
                ServerLogger.error("System", "nkm.system.sslFail", e);
                ServerLogger.warnWithSource("System", "nkm.system.downgradeHttp");
            }
        }

        if (!sslSuccess) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress(Config.PORT), 0);
                ServerLogger.infoWithSource("System", "nkm.system.startedHttp", Config.PORT);
            } catch (IOException e) {
                ServerLogger.error("System", "nkm.system.bindFail", e, Config.PORT);
                ServerLogger.warnWithSource("System", "nkm.system.startFailHint");
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
        if (isOutOfDate(expireTime)) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.timeEarlier", expireTime);
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
        int oldSize = (int) oldInfo.get("max_conns");

        Double newBalance = null;
        Double newRate = null;
        String newPort = null;
        String newExpireTime = null;
        Boolean newWeb = null;

        for (int i = 1; i < args.size(); i++) {
            String param = args.get(i);
            if (param.startsWith("b=")) {
                newBalance = parseDoubleSafely(param.substring(2), "balance");
                if (newBalance == null) return;
            } else if (param.startsWith("r=")) {
                newRate = parseDoubleSafely(param.substring(2), "rate");
                if (newRate == null) return;
            } else if (param.startsWith("p=")) {
                String rawPort = param.substring(2);
                newPort = validateAndFormatPortInput(rawPort);
                if (newPort == null) {
                    ServerLogger.errorWithSource("KeyManager", "nkm.error.portInvalid", rawPort);
                    return;
                }
            } else if (param.startsWith("t=")) {
                String rawTime = param.substring(2);
                newExpireTime = correctInputTime(rawTime);
                if (newExpireTime == null) {
                    ServerLogger.errorWithSource("KeyManager", "nkm.error.timeFormat", rawTime);
                    return;
                }
                if (isOutOfDate(newExpireTime)) {
                    ServerLogger.errorWithSource("KeyManager", "nkm.error.timeEarlier", newExpireTime);
                    return;
                }
            } else if (param.startsWith("w=")) {
                String val = param.substring(2).toLowerCase();
                newWeb = val.equals("true") || val.equals("1") || val.equals("on");
            } else {
                ServerLogger.errorWithSource("KeyManager", "nkm.error.unknownParam", param);
                return;
            }
        }

        if (newPort != null && !newPort.equals(oldPort)) {
            int newSize = PortUtils.calculateSize(newPort);
            if ((oldSize > 1) != (newSize > 1) || oldSize != newSize) {
                Database.deleteNodeMapsByKey(name);
                SessionManager.getInstance().forceReleaseKey(name);
                ServerLogger.warnWithSource("KeyManager", "nkm.warn.portChanged");
            }
        }

        Database.updateKey(name, newBalance, newRate, newPort, newExpireTime, newWeb);
        ServerLogger.infoWithSource("KeyManager", "nkm.info.keyUpdated", name);
    }

    private static void handleToggleKey(List<String> args, boolean enable) {
        if (args.size() != 1) {
            ServerLogger.warnWithSource("Usage", "nkm.usage.toggle", (enable ? "enable" : "disable"));
            return;
        }
        String name = args.get(0);
        if (Database.setKeyStatus(name, enable)) {
            String status = enable ? "ENABLED" : "DISABLED";
            ServerLogger.infoWithSource("KeyManager", "nkm.info.keyStatus", name, status);
            if (!enable) SessionManager.getInstance().forceReleaseKey(name);
        } else {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.keyNotFound", name);
        }
    }

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

        Database.addNodePort(name, nodeId, validMapPort);
        ServerLogger.infoWithSource("KeyManager", "nkm.info.mappingUpdated", name, nodeId, validMapPort);
        ServerLogger.infoWithSource("KeyManager", "nkm.info.nodeIdCase");
    }

    private static void handleDelMapKey(List<String> args) {
        if (args.size() != 2) {
            ServerLogger.warnWithSource("Usage", "nkm.usage.delmap");
            return;
        }
        if (Database.deleteNodeMap(args.get(0), args.get(1))) {
            ServerLogger.infoWithSource("KeyManager", "nkm.info.mappingDeleted");
        } else {
            ServerLogger.warnWithSource("KeyManager", "nkm.warn.mappingNotFound");
        }
    }

    private static void handleDelKey(List<String> args) {
        if (args.size() != 1) {
            ServerLogger.warnWithSource("Usage", "nkm.usage.del");
            return;
        }
        String name = args.get(0);
        Database.deleteKey(name);
        SessionManager.getInstance().forceReleaseKey(name);
        ServerLogger.infoWithSource("KeyManager", "nkm.info.keyDeleted", name);
    }

    private static void handleListKeys() {
        List<String> keys = Database.getAllKeysFormatted();
        if (keys.isEmpty()) {
            ServerLogger.infoWithSource("KeyManager", "nkm.info.noKeys");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            String header = String.format("   %-7s %-12s %-12s %-8s %-16s %-6s %-18s %-4s",
                    "ENABLED", "NAME", "BALANCE", "RATE", "PORT", "CONNS", "EXPIRE", "WEB");
            String separator = "-".repeat(100);

            sb.append(separator).append("\n");
            sb.append(header).append("\n");
            sb.append(separator).append("\n");

            for (String k : keys) {
                sb.append(k).append("\n");
            }
            sb.append(separator);

            if (myConsole != null) {
                myConsole.log("KeyManager", sb.toString());
            } else {
                System.out.println(sb.toString());
            }
        }
    }

    private static void handleReload() {
        ServerLogger.infoWithSource("System", "nkm.info.reloading");

        int oldPort = Config.PORT;
        String oldDb = Config.DB_PATH;
        String oldCrt = Config.SSL_CRT_PATH;
        String oldKey = Config.SSL_KEY_PATH;

        Config.load();

        if (!Objects.equals(oldDb, Config.DB_PATH)) {
            ServerLogger.warnWithSource("System", "nkm.warn.dbChanged");
            Database.init();
        }

        boolean netChanged = (oldPort != Config.PORT) ||
                !Objects.equals(oldCrt, Config.SSL_CRT_PATH) ||
                !Objects.equals(oldKey, Config.SSL_KEY_PATH);

        if (netChanged) {
            ServerLogger.warnWithSource("System", "nkm.warn.netChanged");
            startWebServer();
        } else {
            ServerLogger.infoWithSource("System", "nkm.info.configUpdated");
        }

        ServerLogger.infoWithSource("System", "nkm.info.currentToken", Config.AUTH_TOKEN);
    }

    public static void shutdown() {
        stopWebServer();
        ServerLogger.infoWithSource("System", "nkm.info.shutdown");
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
                    case "delmap" -> handleDelMapKey(subArgs);
                    case "list" -> handleListKeys();
                    case "del" -> handleDelKey(subArgs);
                    case "enable" -> handleToggleKey(subArgs, true);
                    case "disable" -> handleToggleKey(subArgs, false);
                    default -> printKeyUsage();
                }
            } catch (Exception e) {
                ServerLogger.error("Command", "nkm.error.execFail", e);
            }
        });

        myConsole.registerCommand("web", "Manage Web Access", args -> {
            if (args.size() < 2) {
                ServerLogger.warnWithSource("Usage", "nkm.usage.web");
                return;
            }
            String subCmd = args.get(0).toLowerCase();
            String keyName = args.get(1);
            boolean enable = subCmd.equals("enable");

            if (Database.keyExists(keyName)) {
                Database.setWebStatus(keyName, enable);
                ServerLogger.infoWithSource("WebManager", "nkm.info.webStatus", keyName, enable);
            } else {
                ServerLogger.errorWithSource("WebManager", "nkm.error.keyNotFound", keyName);
            }
        });

        myConsole.registerCommand("reload", "Hot reload configuration", args -> handleReload());
        myConsole.registerCommand("stop", "Stop the server", args -> shutdown());
        myConsole.setShutdownHook(Main::shutdown);
    }

    private static void printKeyUsage() {
        ServerLogger.warnWithSource("Usage", "nkm.usage.add");
        ServerLogger.warnWithSource("Usage", "nkm.usage.set");
        ServerLogger.warnWithSource("Usage", "nkm.usage.toggleGeneric");
        ServerLogger.warnWithSource("Usage", "nkm.usage.mapGeneric");
        ServerLogger.warnWithSource("Usage", "nkm.usage.listDel");
        ServerLogger.warnWithSource("Usage", "nkm.usage.reload");
    }

    // ==================== Validation Utils ====================

    private static String validateAndFormatPortInput(String portInput) {
        if (portInput == null || portInput.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = PORT_INPUT_REGEX.matcher(portInput.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            String startPortStr = matcher.group(1);
            String endPortStr = matcher.group(2);
            int startPort = Integer.parseInt(startPortStr);
            if (startPort < 1 || startPort > 65535) {
                return null;
            }
            if (endPortStr == null) {
                return String.valueOf(startPort);
            } else {
                int endPort = Integer.parseInt(endPortStr);
                if (endPort < 1 || endPort > 65535 || endPort < startPort) {
                    return null;
                }
                return startPort + "-" + endPort;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleSafely(String str, String fieldName) {
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            ServerLogger.errorWithSource("KeyManager", "nkm.error.invalidValue", fieldName, str);
            return null;
        }
    }

    private static String correctInputTime(String time) {
        if (time == null) return null;
        Matcher matcher = TIME_PATTERN.matcher(time);
        if (!matcher.matches()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            int hour = Integer.parseInt(matcher.group(4));
            int minute = Integer.parseInt(matcher.group(5));
            if (month < 1 || month > 12 || day < 1 || day > 31 ||
                    hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            return String.format("%04d/%02d/%02d-%02d:%02d", year, month, day, hour, minute);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOutOfDate(String endTime) {
        try {
            if (endTime == null || endTime.isBlank() || endTime.equalsIgnoreCase("PERMANENT")) return false;
            LocalDateTime inputTime = LocalDateTime.parse(endTime, DATE_FORMATTER);
            return LocalDateTime.now().isAfter(inputTime);
        } catch (Exception e) {
            return false;
        }
    }
}