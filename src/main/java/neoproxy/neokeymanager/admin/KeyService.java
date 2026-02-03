package neoproxy.neokeymanager.admin;

import neoproxy.neokeymanager.Database;
import neoproxy.neokeymanager.ServerLogger;
import neoproxy.neokeymanager.SessionManager;
import neoproxy.neokeymanager.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeyService {

    private static final Pattern PORT_INPUT_REGEX = Pattern.compile("^(\\d+)(?:-(\\d+))?$");

    public String execAddKey(List<String> args) {
        if (args.size() != 5 && args.size() != 6) {
            // 使用 nkm.usage.add
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.add"));
        }

        String name = args.get(0).trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Key name cannot be empty");

        if (Database.getRealKeyName(name) != null) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyExists", name));
        }

        try {
            Double balance = parseDouble(args.get(1), "balance");
            String expireTime = correctInputTime(args.get(2));
            String portStr = validateAndFormatPortInput(args.get(3));
            Double rate = parseDouble(args.get(4), "rate");

            boolean enableWeb = false;
            if (args.size() == 6) {
                enableWeb = parseBooleanStrict(args.get(5));
            }

            int maxConns = Utils.calculatePortSize(portStr);

            if (!Database.addKey(name, balance, rate, expireTime, portStr, maxConns)) {
                throw new RuntimeException(ServerLogger.getMessage("nkm.error.sqlFail"));
            }

            if (enableWeb) {
                Database.setWebStatus(name, true);
                return ServerLogger.getMessage("nkm.info.keyAddedWeb", name);
            } else {
                return ServerLogger.getMessage("nkm.info.keyAdded", name);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            ServerLogger.error("KeyService", "AddKey Failed", e);
            throw new RuntimeException("System error while adding key: " + e.getMessage());
        }
    }

    public String execSetKey(List<String> args) {
        if (args.size() < 2) {
            // 使用 nkm.usage.set
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.set"));
        }
        String name = args.get(0);
        String realName = Database.getRealKeyName(name);

        if (realName == null) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));
        }

        Double newBalance = null;
        Double newRate = null;
        String newPort = null;
        String newExpireTime = null;
        Boolean newWeb = null;
        Integer newMaxConns = null;
        String newNameParam = null;

        for (int i = 1; i < args.size(); i++) {
            String param = args.get(i);
            String lowerParam = param.toLowerCase();

            if (lowerParam.startsWith("b=") || lowerParam.startsWith("balance="))
                newBalance = parseDouble(getValue(param), "balance");
            else if (lowerParam.startsWith("r=") || lowerParam.startsWith("rate="))
                newRate = parseDouble(getValue(param), "rate");
            else if (lowerParam.startsWith("p=") || lowerParam.startsWith("port="))
                newPort = validateAndFormatPortInput(getValue(param));
            else if (lowerParam.startsWith("c=") || lowerParam.startsWith("conn="))
                newMaxConns = parseInt(getValue(param), "max_conns");
            else if (lowerParam.startsWith("t=") || lowerParam.startsWith("time="))
                newExpireTime = correctInputTime(getValue(param));
            else if (lowerParam.startsWith("w=") || lowerParam.startsWith("web="))
                newWeb = parseBooleanStrict(getValue(param));
            else if (lowerParam.startsWith("n=") || lowerParam.startsWith("name="))
                newNameParam = getValue(param);
        }

        if (newNameParam != null && !newNameParam.isBlank() && !newNameParam.equals(realName)) {
            if (Database.keyExists(newNameParam) || Database.isAlias(newNameParam)) {
                throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyExists", newNameParam));
            }
            if (Database.renameKey(realName, newNameParam)) {
                SessionManager.getInstance().forceReleaseKey(realName);
                realName = newNameParam;
            } else {
                throw new RuntimeException("Failed to rename key in database.");
            }
        }

        Database.updateKey(realName, newBalance, newRate, newPort, newExpireTime, newWeb, newMaxConns);

        if (newPort != null || newNameParam != null) {
            SessionManager.getInstance().forceReleaseKey(realName);
        }

        return ServerLogger.getMessage("nkm.info.keyUpdated", realName);
    }

    private String getValue(String param) {
        int idx = param.indexOf('=');
        return (idx != -1 && idx < param.length() - 1) ? param.substring(idx + 1) : "";
    }

    public String execDelKey(List<String> args) {
        if (args.isEmpty()) {
            // 使用 nkm.usage.del
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.del"));
        }

        StringJoiner success = new StringJoiner(", ");
        StringJoiner failed = new StringJoiner(", ");
        int successCount = 0;

        for (String name : args) {
            try {
                if (Database.isAlias(name)) {
                    if (Database.deleteAlias(name)) {
                        success.add(name + "(Alias)");
                        successCount++;
                    } else {
                        failed.add(name);
                    }
                } else if (Database.keyExists(name)) {
                    Database.deleteKey(name);
                    SessionManager.getInstance().forceReleaseKey(name);
                    success.add(name);
                    successCount++;
                } else {
                    failed.add(name + "(Not Found)");
                }
            } catch (Exception e) {
                failed.add(name + "(Error)");
                ServerLogger.error("KeyService", "Batch delete failed for " + name, e);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (successCount > 0) sb.append("Deleted: [").append(success).append("]. ");
        if (failed.length() > 0) sb.append("Failed: [").append(failed).append("].");

        return sb.toString().isEmpty() ? "No keys processed." : sb.toString();
    }

    public String execWeb(List<String> args) {
        if (args.size() < 2) {
            // 使用 nkm.usage.web
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.web"));
        }
        String subCmd = args.get(0).toLowerCase();
        String keyName = args.get(1);

        String realName = Database.getRealKeyName(keyName);
        if (realName == null) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", keyName));
        }

        boolean enable;
        if (subCmd.equals("enable") || subCmd.equals("on") || subCmd.equals("true")) {
            enable = true;
        } else if (subCmd.equals("disable") || subCmd.equals("off") || subCmd.equals("false")) {
            enable = false;
        } else {
            throw new IllegalArgumentException("Unknown web command: " + subCmd);
        }

        Database.setWebStatus(realName, enable);
        return ServerLogger.getMessage("nkm.info.webStatus", realName, enable);
    }

    public String execSetConn(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("Usage: setconn <key> <num>");
        String name = args.get(0);
        String realName = Database.getRealKeyName(name);
        if (realName == null)
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));

        Integer num = parseInt(args.get(1), "conn");
        if (num == null || num < 1)
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.invalidParam", "conn", args.get(1)));

        if (!Database.setKeyMaxConns(realName, num))
            throw new RuntimeException(ServerLogger.getMessage("nkm.error.sqlFail"));

        return ServerLogger.getMessage("nkm.info.keyUpdated", realName);
    }

    public String execMapKey(List<String> args) {
        if (args.size() < 3) {
            // 使用 nkm.usage.map
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.map"));
        }

        String keyName = args.get(0);
        String realName = Database.getRealKeyName(keyName);
        if (realName == null)
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", keyName));

        String mapPortStr = args.get(args.size() - 1);
        String mapPort = validateAndFormatPortInput(mapPortStr);

        List<String> targetNodes = args.subList(1, args.size() - 1);

        int count = 0;
        for (String nodeId : targetNodes) {
            Database.addNodePort(realName, nodeId, mapPort);
            count++;
        }

        if (count == 1) {
            return ServerLogger.getMessage("nkm.info.mappingUpdated", realName, targetNodes.get(0), mapPort);
        } else {
            return String.format("Mapped Key [%s] to Port [%s] on %d nodes: %s",
                    realName, mapPort, count, targetNodes);
        }
    }

    public String execDelMap(List<String> args) {
        if (args.size() < 2) {
            // 使用 nkm.usage.delmap
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.delmap"));
        }

        String keyName = args.get(0);
        String realName = Database.getRealKeyName(keyName);
        if (realName == null)
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", keyName));

        List<String> targetNodes = args.subList(1, args.size());

        int successCount = 0;
        List<String> deletedNodes = new ArrayList<>();

        for (String nodeId : targetNodes) {
            if (Database.deleteNodeMap(realName, nodeId)) {
                successCount++;
                deletedNodes.add(nodeId);
            }
        }

        if (successCount == 0) {
            throw new IllegalArgumentException("No mappings were deleted (Nodes not found or not mapped).");
        } else if (successCount == 1) {
            return ServerLogger.getMessage("nkm.info.mappingDeleted", realName, deletedNodes.get(0));
        } else {
            return String.format("Deleted mappings for Key [%s] on nodes: %s", realName, deletedNodes);
        }
    }

    public String execEnable(List<String> args, boolean enable) {
        if (args.size() != 1) {
            // 使用 nkm.usage.toggle
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.toggle"));
        }
        String name = args.get(0);
        String realName = Database.getRealKeyName(name);
        if (realName == null)
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));

        if (!Database.setKeyStatusStrict(realName, enable)) {
            if (enable)
                throw new IllegalArgumentException(ServerLogger.getMessage("nkm.warn.enableRejected", realName, "Check Balance/Expire"));
            else throw new RuntimeException("Database failed to disable key.");
        }

        if (!enable) SessionManager.getInstance().forceReleaseKey(realName);

        String status = enable ? "ENABLED" : "DISABLED";
        return ServerLogger.getMessage("nkm.info.keyStatus", realName, status);
    }

    public String execSetSingle(List<String> args) {
        if (args.isEmpty()) throw new IllegalArgumentException("Usage: key setsingle <key_or_alias> [true/false]");
        String name = args.get(0);
        boolean exists = Database.keyExists(name) || Database.isAlias(name);

        if (!exists) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));
        }

        boolean isSingle = true;
        if (args.size() >= 2) {
            isSingle = parseBooleanStrict(args.get(1));
        }

        if (Database.setKeySingle(name, isSingle)) {
            String realKey = Database.getRealKeyName(name);
            if (realKey != null) {
                SessionManager.getInstance().forceReleaseKey(realKey);
            }
            return "Single mode for [" + name + "] set to: " + isSingle;
        } else {
            throw new RuntimeException("Database failed to update single mode.");
        }
    }

    public String execDelSingle(List<String> args) {
        if (args.size() != 1) throw new IllegalArgumentException("Usage: key delsingle <key_or_alias>");
        String name = args.get(0);
        boolean exists = Database.keyExists(name) || Database.isAlias(name);
        if (!exists) throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));

        if (Database.setKeySingle(name, false)) {
            String realKey = Database.getRealKeyName(name);
            if (realKey != null) {
                SessionManager.getInstance().forceReleaseKey(realKey);
            }
            return "Single mode removed for [" + name + "].";
        } else {
            throw new RuntimeException("Database failed to remove single mode.");
        }
    }

    public String execListSingle(List<String> args) {
        List<String> singles = Database.getSingleKeys();
        if (singles.isEmpty()) return "No keys are marked as Single.";
        return "Keys in Single Mode:\n" + String.join("\n", singles);
    }

    public String execLinkKey(List<String> args) {
        if (args.size() != 3 || !args.get(1).equalsIgnoreCase("to")) {
            // 使用 nkm.usage.link
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.link"));
        }
        String alias = args.get(0);
        String target = args.get(2);

        if (Database.getRealKeyName(alias) != null) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyExists", alias));
        }
        String realTarget = Database.getRealKeyName(target);
        if (realTarget == null) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", target));
        }

        Database.addLink(alias, realTarget);
        return ServerLogger.getMessage("nkm.info.linked", alias, realTarget);
    }

    public String execListLink(List<String> args) {
        var links = Database.getAllLinks();
        if (links.isEmpty()) return "No links found.";

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        links.forEach((k, v) -> sb.append(String.format("   %-15s -> %s%n", k, v)));
        return sb.toString();
    }

    // ==================== [修改] CBM 命令逻辑 ====================
    public String execSetCbm(List<String> args) {
        // 至少需要 key 和一个被 {} 包裹的消息
        if (args.size() < 2) throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.cbm"));

        String name = args.getFirst();
        String realKey = Database.getRealKeyName(name);
        if (realKey == null) throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));

        // 1. 解析大括号内容
        // 将剩余参数还原为原始字符串（保留空格）
        String rawArgs = String.join(" ", args.subList(1, args.size()));

        int start = rawArgs.indexOf('{');
        int end = rawArgs.lastIndexOf('}');

        if (start == -1 || end == -1 || start >= end) {
            throw new IllegalArgumentException("Format Error: Message must be enclosed in { }. Example: key setcbm key1 {System Maintenance}");
        }

        // 提取大括号内部的内容
        String msg = rawArgs.substring(start + 1, end);

        // 2. 写入 CBM 消息
        if (!Database.setCustomBlockingMsg(realKey, msg)) {
            throw new RuntimeException("Database error: Failed to save CBM.");
        }

        // 3. [核心要求] 自动禁用 Key 并踢人
        // 逻辑等同于 key disable <key>
        if (!Database.setKeyStatusStrict(realKey, false)) {
            throw new RuntimeException("CBM saved, but failed to disable key automatically.");
        }
        // 强制踢出当前连接，确保下次连接时触发 CBM
        SessionManager.getInstance().forceReleaseKey(realKey);

        return ServerLogger.getMessage("nkm.cbm.set", realKey);
    }

    // ==================== [修复] 使用 nkm.cbm.notFound ====================
    public String execDelCbm(List<String> args) {
        if (args.size() != 1) throw new IllegalArgumentException(ServerLogger.getMessage("nkm.usage.cbm"));

        String name = args.getFirst();
        String realKey = Database.getRealKeyName(name);
        if (realKey == null) throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.keyNotFound", name));

        // 1. 先检查是否存在 CBM
        String currentMsg = Database.getCustomBlockingMsg(realKey);
        if (currentMsg == null) {
            // [此处使用了 nkm.cbm.notFound]
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.cbm.notFound", realKey));
        }

        // 2. 执行删除
        if (Database.setCustomBlockingMsg(realKey, null)) {
            return ServerLogger.getMessage("nkm.cbm.deleted", realKey);
        } else {
            throw new RuntimeException("Database error: Failed to delete CBM.");
        }
    }

    public String execListCbm(List<String> args) {
        Map<String, String> map = Database.getAllCustomBlockingMsgs();
        if (map.isEmpty()) return "No Custom Blocking Messages found.";

        StringBuilder sb = new StringBuilder();
        sb.append(ServerLogger.getMessage("nkm.list.cbmHeader")).append("\n");
        map.forEach((k, v) -> sb.append(String.format("   %-15s : %s%n", k, v)));
        return sb.toString();
    }

    // ==================== Helpers ====================

    private String validateAndFormatPortInput(String portInput) {
        if (portInput == null || portInput.isBlank()) throw new IllegalArgumentException("Port cannot be empty");
        Matcher matcher = PORT_INPUT_REGEX.matcher(portInput.trim());
        if (!matcher.matches())
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.portInvalid", portInput));
        try {
            int start = Integer.parseInt(matcher.group(1));
            if (start < 1 || start > 65535) throw new NumberFormatException();
            if (matcher.group(2) != null) {
                int end = Integer.parseInt(matcher.group(2));
                if (end < 1 || end > 65535 || end < start) throw new NumberFormatException();
                return start + "-" + end;
            }
            return String.valueOf(start);
        } catch (Exception e) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.portInvalid", portInput));
        }
    }

    private Double parseDouble(String str, String name) {
        try {
            return Double.parseDouble(str);
        } catch (Exception e) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.invalidParam", name, str));
        }
    }

    private Integer parseInt(String str, String name) {
        try {
            return Integer.parseInt(str);
        } catch (Exception e) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.invalidParam", name, str));
        }
    }

    private boolean parseBooleanStrict(String str) {
        if (str == null) return false;
        String s = str.trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("enable");
    }

    private String correctInputTime(String time) {
        if (time == null || time.isBlank()) return null;
        if (time.equalsIgnoreCase("PERMANENT")) return "PERMANENT";
        if (!time.matches("^\\d{4}/\\d{1,2}/\\d{1,2}-\\d{1,2}:\\d{1,2}$")) {
            throw new IllegalArgumentException(ServerLogger.getMessage("nkm.error.timeFormat", time));
        }
        return time;
    }
}