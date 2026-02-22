# NeoKeyManager

![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square) ![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square) ![Status](https://img.shields.io/badge/Status-Production%20Ready-green?style=flat-square)

**NeoKeyManager (NKM)** 是专为 [NeoProxyServer](https://github.com/CeroxeAnivie/NeoProxyServer) 设计的企业级分布式鉴权与节点管控中心。

它将 NPS 集群从“单机自治”升级为“中央管控”，提供统一的密钥分发、跨节点流量结算、**全局单例限制 (Single Mode)**、**别名映射 (Alias System)** 以及**客户端节点动态分发**功能。基于 **Java 21 Virtual Threads** 与 **高性能 SQLite WAL 模式** 构建，能够轻松处理数万并发连接的鉴权请求。

---

## ✨ 核心特性

*   **⚡ 下一代并发架构**：完全基于 Java 21 虚拟线程 (Project Loom) 重构，资源占用极低，吞吐量极高。
*   **📦 高性能 SQLite 存储**：[新] 抛弃 H2，采用原生 SQLite 驱动并开启 WAL (Write-Ahead Logging) 模式与内存缓冲池，完美应对万级节点并发流量扣费。
*   **🌐 公开节点动态分发**：[新] 实时收集全网节点心跳，为客户端 (NeoLink) 提供无鉴权的可用节点列表拉取接口，内置严格的 IP 滑动限流 (防 CC 攻击)。
*   **🔒 全局单例模式 (Single Mode)**：支持强制密钥在全网集群中只能有一个活跃连接，杜绝账号多人共享。
*   **🎭 别名系统 (Alias System)**：为真实密钥创建别名 (Link)，支持无感轮换密钥，客户端仅需配置别名即可连接。
*   **🛑 自定义屏蔽消息 (CBM)**：[新] 支持为被封禁/禁用的密钥下发自定义断开提示 (例如："您的套餐已过期，请联系管理员")。
*   **👑 主节点独占策略**：[新] 可配置全局默认节点，未单独分配映射的密钥将只能在指定的主节点上使用。
*   **🔄 无缝热重载**：[新] 支持通过 API 或 CLI 指令热重载配置文件与白名单，全程不停机。

---

## 🚀 架构变更与行为说明

接入 NeoKeyManager 后，NPS 的工作模式将切换为 **托管模式 (Managed Mode)**。

### 1. 核心逻辑变更

| 特性 | 本地模式 (Standalone) | 托管模式 (Managed by NKM) |
|:---|:---|:---|
| **鉴权逻辑** | 读取本地 `sk.db` 文件。 | 实时向 NKM 发起 HTTP 鉴权，支持 **别名透传**。 |
| **单例控制** | 仅限制单节点内的连接数。 | **全网级限制**。开启 Single 模式后，跨节点多开也会被秒级拦截。 |
| **流量结算** | 本地扣费。 | NPS 缓存流量批量上报，NKM 使用内存缓冲池异步落盘，秒级下发 Kill 指令。 |
| **端口管理** | 仅支持本地端口。 | 支持 **Map 映射**，NKM 可指定某 Key 在特定节点使用特定端口。 |
| **节点分发** | 客户端写死 IP。 | 客户端可访问 NKM `/client/nodelist` 动态拉取在线节点。 |

---

## 🛠️ 安装与配置

### 1. 环境要求
*   **Java 21** (JDK 21+) - **必须**，否则无法运行。

### 2. 启动

```bash
# 默认启动 (根据系统语言)
java -jar NeoKeyManager-3.0.0.jar

# 强制中文环境启动
java -jar NeoKeyManager-3.0.0.jar --zh-cn
```

### 3. 配置文件 (`server.properties`)

首次运行会自动生成。**请务必修改默认 Token！**

```properties
# 服务监听端口
SERVER_PORT=8080

# NPS 接入 Token (用于 NPS 节点连接 NKM)
AUTH_TOKEN=your_nps_connect_token

# 管理员 Token (用于 Admin API 控制与自动化脚本)
ADMIN_TOKEN=your_admin_secret_key

# 数据库路径 (SQLite 数据库文件路径)
DB_PATH=./neokey_db

# 主节点独占策略 (留空则所有节点自由竞争)
# 填写 NodeAuth.json 中的节点 ID
DEFAULT_NODE=

# 公开节点列表配置文件路径 (提供给客户端拉取)
NODE_JSON_FILE=node.json

# 客户端自动更新下载包链接 (NeoLink)
CLIENT_UPDATE_URL_7Z=https://your-domain.com/NeoLink-latest.7z
CLIENT_UPDATE_URL_JAR=https://your-domain.com/NeoLink-latest.jar

# SSL 配置 (配置后自动开启 HTTPS)
SSL_CRT_PATH=
SSL_KEY_PATH=
```

---

## 📖 命令手册 (CLI)

NKM 控制台支持以下指令。所有指令均即时生效，无需重启。

### 🔑 基础密钥管理

*   **添加密钥**
    ```bash
    key add <name> <balance> <expire> <port> <rate> [webHTML]
    # 示例: 创建永久密钥，端口范围 30000-30005
    key add user1 1000 PERMANENT 30000-30005 50 true
    ```

*   **修改密钥 (支持重命名)**
    ```bash
    key set <name> [b=<余额>] [r=<速率>] [p=<端口>] [t=<时间>] [w=<web>] [c=<并发>] [n=<新名>]
    # 示例: 充值并重命名密钥
    key set user1 b=5000 n=user1_vip
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

### 🛑 自定义屏蔽消息 (CBM - 新功能)
当希望封禁用户并给出明确断开理由时使用。设置后该 Key 将自动 Disable 并强制踢下线。

*   **设置屏蔽消息**
    ```bash
    key setcbm <name> {您的套餐已耗尽，请联系管理员充值}
    ```
*   **移除屏蔽消息**
    ```bash
    key delcbm <name>
    ```
*   **查看所有屏蔽消息**
    ```bash
    key listcbm
    ```

### 🔒 高级特性

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

*   **列出密钥**：`key list` (显示状态、余额、[S]单例标记、端口映射树)
*   **查看活跃会话**：`list` (显示当前在线的节点、IP、使用的别名与真实 Key)
*   **重载配置**：`reload` (热重载 properties、NodeAuth、node.json)
*   **退出**：`stop`

---

## 🔌 HTTP API (DevOps)

NKM 提供了完整的 RESTful API 用于对接面板或计费系统。

### 1. 管理端 API (需验证)
*   **Header**: `Authorization: Bearer <ADMIN_TOKEN>`
*   **Base URL**: `http://ip:port`

| Method | Endpoint | Description | Payload Example |
|:---|:---|:---|:---|
| **POST** | `/api/exec/<cmd>` | 执行 CLI 等效指令 | `{"args": ["user1", "b=100"]}` |
| **GET** | `/api/query` | 获取所有 Key 详情 (含 Map) | - |
| **GET** | `/api/lp/<name>` | 查询单个 Key | - |
| **POST** | `/api/reload` | 热重载系统配置 | - |

### 2. 客户端 API (公开无鉴权)
用于 NeoLink 客户端动态拉取在线节点。

*   **Endpoint**: `GET /client/nodelist`
*   **防护机制**: 基于真实 IP 的滑动窗口限流 (限制 10次/天/IP)。
*   **返回机制**: 仅返回 `node.json` 中配置且**最近 1 分钟内有心跳上报**的存活节点。

---

## 📝 License

MIT License.

*Designed & Developed by CeroxeAnivie for the NeoProxyServer Ecosystem.*