# NeoKeyManager

![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square) ![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square) ![Status](https://img.shields.io/badge/Status-Production%20Ready-green?style=flat-square) ![Version](https://img.shields.io/badge/Version-1.2.1-purple?style=flat-square)


**NeoKeyManager (NKM)** 是专为 [NeoProxyServer](https://github.com/CeroxeAnivie/NeoProxyServer) 设计的企业级分布式鉴权中心。

它将 NPS 集群从“单机自治”升级为“中央管控”，提供统一的密钥分发、跨节点流量结算、**全局单例限制 (Single Mode)** 以及 **别名映射 (Alias System)** 功能。基于 **Java 21 Virtual Threads** 构建，能够轻松处理数万并发连接的鉴权请求。

---

## ✨ 核心特性

*   **⚡ 下一代并发架构**：完全基于 Java 21 虚拟线程 (Project Loom) 重构，资源占用极低，吞吐量极高。
*   **🔒 全局单例模式 (Single Mode)**：[新] 支持强制密钥在全网集群中只能有一个活跃连接，杜绝账号多人共享。
*   **🎭 别名系统 (Alias System)**：[新] 为真实密钥创建别名 (Link)，支持无感轮换密钥，客户端仅需配置别名即可连接。
*   **🛡️ 双重鉴权体系**：[新] 区分 `AUTH_TOKEN` (NPS 接入) 与 `ADMIN_TOKEN` (管理接口)，提升系统安全性。
*   **🌍 国际化支持**：内置中英双语交互，启动参数自动切换 (`--zh-cn` / `--en-us`)。
*   **🔄 实时流量结算**：接收各节点 NPS 的流量上报，实时扣除余额，秒级下线耗尽或过期的密钥。
*   **🔗 节点级映射**：支持同一密钥在不同节点 (Node) 映射到不同端口，灵活管理大规模集群。

---

## 🚀 架构变更与行为说明

接入 NeoKeyManager 后，NPS 的工作模式将切换为 **托管模式 (Managed Mode)**。

### 1. 核心逻辑变更

| 特性       | 本地模式 (Standalone) | 托管模式 (Managed by NKM)                   |
|:---------|:------------------|:----------------------------------------|
| **鉴权逻辑** | 读取本地 `sk.db` 文件。  | 实时向 NKM 发起 HTTP 鉴权，支持 **别名透传**。         |
| **单例控制** | 仅限制单节点内的连接数。      | **全网级限制**。开启 Single 模式后，即使用户切换节点也会被拦截。  |
| **流量结算** | 本地扣费。             | NPS 缓存流量并批量上报，由 NKM 统一扣费并广播 Kill 指令。    |
| **端口管理** | 仅支持本地端口。          | 支持 **Map 映射**，NKM 可指定某 Key 在特定节点使用特定端口。 |

### 2. NPS 控制台行为变化

*   **只读保护**：带有 **`(R)`** (Remote) 标签的密钥无法在 NPS 端修改（如 `key set`），必须在 NKM 端操作。
*   **全局防冲突**：NPS 添加本地密钥时，会自动检查 NKM 是否存在同名密钥或别名。
*   **WebHTML 同步**：Web 访问权限（防 HTTP 探测）完全由 NKM 实时下发。

---

## 🛠️ 安装与配置

### 1. 环境要求
*   **Java 21** (JDK 21+) - **必须**，否则无法运行。

### 2. 启动

```bash
# 默认启动 (根据系统语言)
java -jar NeoKeyManager-1.2.1.jar

# 强制中文环境启动
java -jar NeoKeyManager-1.2.1.jar --zh-cn
```

### 3. 配置文件 (`server.properties`)

首次运行会自动生成。**请务必修改默认 Token！**

```properties
# 服务监听端口
SERVER_PORT=8080

# NPS 接入 Token (用于 NPS 节点连接 NKM)
AUTH_TOKEN=your_nps_connect_token

# 管理员 Token (用于 API 控制与自动化脚本)
# [新增] 必须配置，否则无法调用 /api/exec 接口
ADMIN_TOKEN=your_admin_secret_key

# 数据库路径 (H2)
DB_PATH=./neokey_db

# SSL 配置 (配置后自动开启 HTTPS)
SSL_CRT_PATH=
SSL_KEY_PATH=
```

---

## 📖 命令手册 (CLI)

NKM 控制台支持以下指令。所有指令均即时生效，无需重启 NPS。

### 🔑 基础密钥管理

*   **添加密钥**
    ```bash
    key add <name> <balance> <expire> <port> <rate> [webHTML]
    # 示例: 创建永久密钥，端口范围 30000-30005
    key add user1 1000 PERMANENT 30000-30005 50 true
    ```

*   **修改密钥**
    ```bash
    key set <name> [b=<余额>] [r=<速率>] [p=<端口>] [t=<时间>] [w=<web>]
    # 示例: 充值并修改费率
    key set user1 b=5000 r=1.5
    ```

*   **连接数限制**
    ```bash
    key setconn <name> <max_connections>
    ```

*   **启停控制**
    ```bash
    key enable <name>   # 启用
    key disable <name>  # 禁用 (NPS 端立即断开)
    ```

### 🔒 高级特性 (新功能)

*   **单例模式 (Single Mode)**
    强制该 Key 在所有节点中只能存在 **1** 个活跃连接。
    ```bash
    key setsingle <name> true   # 开启单例
    key setsingle <name> false  # 关闭单例
    key listsingle              # 查看开启了单例的 Key
    ```

*   **别名系统 (Alias System)**
    为真实 Key 创建别名，客户端可使用别名连接。
    ```bash
    key link <alias> to <real_key>  # 创建别名
    # 示例: 客户只知道 "vip_01"，后端实际指向 "user_id_10086"
    key link vip_01 to user_id_10086
    
    key listlink                    # 查看所有别名关系
    key del <alias>                 # 删除别名 (不影响原 Key)
    ```

### 🌍 节点端口映射

支持将同一个 Key 在特定节点 (Node ID) 指向特定端口。

*   **添加映射**
    ```bash
    key map <name> <node_id> <port>
    # 示例: user1 默认端口 30000，但在 "HK_Node" 节点使用 8080
    key map user1 HK_Node 8080
    ```

*   **删除映射**
    ```bash
    key delmap <name> <node_id>
    ```

### 📊 查询与维护

*   **列出密钥**：`key list` (显示状态、余额、[S]单例标记)
*   **查看活跃会话**：`list` (显示当前在线的节点、IP、使用的别名)
*   **重载配置**：`reload`
*   **退出**：`stop`

---

## 🔌 HTTP API (DevOps)

NKM 提供了完整的 RESTful API 用于对接面板或计费系统。

*   **Header**: `Authorization: Bearer <ADMIN_TOKEN>`
*   **Base URL**: `http://ip:port/api`

| Method   | Endpoint          | Description | Payload Example                   |
|:---------|:------------------|:------------|:----------------------------------|
| **POST** | `/exec/add`       | 执行添加指令      | `{"args": ["user1", "100", ...]}` |
| **POST** | `/exec/setsingle` | 设置单例模式      | `{"args": ["user1", "true"]}`     |
| **GET**  | `/query`          | 获取所有 Key 详情 | -                                 |
| **GET**  | `/lp/<name>`      | 查询单个 Key    | -                                 |

---

## 📝 License

MIT License.

*Developed by CeroxeAnivie for the NeoProxyServer Ecosystem.*