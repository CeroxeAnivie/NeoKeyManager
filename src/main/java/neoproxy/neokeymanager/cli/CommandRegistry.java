package neoproxy.neokeymanager.cli;

import fun.ceroxe.api.utils.MyConsole;
import neoproxy.neokeymanager.Application;
import neoproxy.neokeymanager.service.KeyService;
import neoproxy.neokeymanager.utils.ServerLogger;

import java.util.*;
import java.util.function.Consumer;

/**
 * 命令注册中心
 * 集中管理所有 CLI 命令的注册、执行和 Help 生成
 */
public class CommandRegistry {

    private final MyConsole console;
    private final KeyService keyService;
    private final Map<String, CommandDefinition> commands = new LinkedHashMap<>();

    public CommandRegistry(MyConsole console, KeyService keyService) {
        this.console = console;
        this.keyService = keyService;
        registerAllCommands();
    }

    /**
     * 注册所有命令
     */
    private void registerAllCommands() {
        // key 命令及其子命令
        registerKeyCommand();

        // web 命令
        registerCommand("web",
            ServerLogger.getMessage("nkm.cmd.desc.web"),
            args -> {
                try {
                    String res = keyService.execWeb(args);
                    console.log("WebManager", res);
                } catch (Exception e) {
                    ServerLogger.error("WebManager", "nkm.error.execFail", e);
                }
            }
        );

        // list 命令
        registerCommand("list",
            ServerLogger.getMessage("nkm.cmd.desc.list"),
            args -> handleListActive()
        );

        // reload 命令
        registerCommand("reload",
            ServerLogger.getMessage("nkm.cmd.desc.reload"),
            args -> handleReload()
        );

        // exit 命令
        registerCommand("exit",
            ServerLogger.getMessage("nkm.cmd.desc.exit"),
            args -> System.exit(0)
        );

        // help 命令
        registerCommand("help",
            ServerLogger.getMessage("nkm.cmd.desc.help"),
            args -> printHelp(args.isEmpty() ? null : args.get(0))
        );
    }

    /**
     * 注册 key 命令及其所有子命令
     */
    private void registerKeyCommand() {
        registerCommand("key",
            ServerLogger.getMessage("nkm.cmd.desc.key"),
            args -> {
                if (args.isEmpty()) {
                    printKeyHelp();
                    return;
                }
                String subCmd = args.get(0).toLowerCase();
                List<String> subArgs = args.subList(1, args.size());
                executeKeySubCommand(subCmd, subArgs);
            }
        );
    }

    /**
     * 执行 key 子命令
     */
    private void executeKeySubCommand(String subCmd, List<String> subArgs) {
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
                case "delnode" -> keyService.execDelNode(subArgs);
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
                case "setcbm" -> keyService.execSetCbm(subArgs);
                case "delcbm" -> keyService.execDelCbm(subArgs);
                case "listcbm" -> keyService.execListCbm(subArgs);
                default -> {
                    ServerLogger.errorWithSource("Command", "nkm.error.unknownSubCommand", subCmd);
                    printKeyHelp();
                    yield null;
                }
            };
            if (result != null) console.log("KeyManager", result);
        } catch (Exception e) {
            ServerLogger.error("Command", "nkm.error.execFail", e);
        }
    }

    /**
     * 注册单个命令
     */
    private void registerCommand(String name, String description, Consumer<List<String>> handler) {
        CommandDefinition cmd = new CommandDefinition(name, description, handler);
        commands.put(name, cmd);
        console.registerCommand(name, description, handler);
    }

    // ==================== Help 系统 ====================

    /**
     * 打印主 Help 信息
     */
    public void printHelp(String commandName) {
        if (commandName != null) {
            printSpecificHelp(commandName);
            return;
        }

        console.log("Help", ServerLogger.getMessage("nkm.help.title"));
        console.log("Help", "-".repeat(50));

        for (CommandDefinition cmd : commands.values()) {
            console.log("Help", String.format("  %-10s - %s", cmd.name, cmd.description));
        }

        console.log("Help", "-".repeat(50));
        console.log("Help", ServerLogger.getMessage("nkm.help.tip"));
    }

    /**
     * 打印特定命令的详细 Help
     */
    private void printSpecificHelp(String commandName) {
        switch (commandName.toLowerCase()) {
            case "key" -> printKeyHelp();
            case "web" -> printWebHelp();
            case "list" -> console.log("Help", ServerLogger.getMessage("nkm.usage.help.listActive"));
            case "reload" -> console.log("Help", ServerLogger.getMessage("nkm.cmd.desc.reload"));
            case "exit" -> console.log("Help", ServerLogger.getMessage("nkm.cmd.desc.exit"));
            default -> console.log("Help", ServerLogger.getMessage("nkm.error.unknownSubCommand", commandName));
        }
    }

    /**
     * 打印 key 命令的详细 Help
     */
    private void printKeyHelp() {
        console.log("Help", "\n" + ServerLogger.getMessage("nkm.help.key.title"));
        console.log("Help", "-".repeat(50));

        console.log("Help", ServerLogger.getMessage("nkm.usage.help.add"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.set"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.del"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.map"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.delmap"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.list"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.lp"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.toggle"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.web"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.link"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.setconn"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.setsingle"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.delsingle"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.listsingle"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.delnode"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.listlink"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.setcbm"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.delcbm"));
        console.log("Help", ServerLogger.getMessage("nkm.usage.help.listcbm"));

        console.log("Help", "-".repeat(50));
        console.log("Help", ServerLogger.getMessage("nkm.usage.portNote"));
    }

    /**
     * 打印 web 命令的 Help
     */
    private void printWebHelp() {
        console.log("Help", ServerLogger.getMessage("nkm.usage.web"));
    }

    // ==================== 命令处理委托 ====================

    private void handleListActive() {
        // 委托给 Application 类处理
        Application.handleTopLevelList();
    }

    private void handleReload() {
        Application.handleReload();
    }

    private void handleListKeys(List<String> args) {
        Application.handleListKeys(args);
    }

    private void handleLookupKey(List<String> args) {
        Application.handleLookupKey(args);
    }

    // ==================== 内部类 ====================

    private record CommandDefinition(String name, String description, Consumer<List<String>> handler) {
    }
}
