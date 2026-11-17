# NeoForge 接手文档

给下一次接手 **NeoForge 移植** 的会话/人。Fabric 侧已完成并推送，本文只讲 NeoForge。

- 适用版本：**1.21.x + 26.x**，不考虑 Forge、不考虑 1.20.x（1.20.1 没有 configuration 状态，
  且 Xray 客户端只实现了协议 775）。
- 目标形式：`neoforge/<MC 版本>` 分支，每个分支独立构建，和 Fabric 分支同一套约定。
- 硬约束：**尽量不 mixin**。

---

## 1. 先看结论

1. **API 移植本身很简单**，这个模组的 MC 面只有 8 个符号，NeoForge 侧对应关系见第 3 节，半天到一天。
2. **真正的门槛是 NeoForge 的通道协商**，有三道闸（第 4 节），其中两道**必须改 Xray 客户端**才能过，
   否则第一个 `xmc:data` 载荷到达时服务端直接断开。
3. **不需要 mixin**：服务端用 `PayloadRegistrar.optional()` + `listener.send()` + 轮询断连检测即可；
   客户端补两个 vanilla 合法帧（`pong(0)`、`minecraft:register`）。
4. 但**实际只适用于"纯净 + 本模组"的服务端**：NeoForge 服务端只要装了任何一个注册了**非 optional**
   通道的模组，XMC 客户端在协商阶段就会被整条拒绝。这一点必须写进面向用户文档。

---

## 2. 现状（交接点）

Fabric 侧已有 9 个分支（`master` + 8 个版本分支），全部通过构建，26.1 通过完整 e2e，其余通过
probe 冒烟。分支矩阵、分支间差异、同步规则见 [VERSIONS.md](VERSIONS.md)。

核心结论（对 NeoForge 同样适用）：**只有 `lib/java/xmc/lib/net/` 与构建脚本按版本分叉，
`core/`、`config/`、`src/test/` 全分支一致**。NeoForge 分支照这个结构切即可。

---

## 3. Fabric → NeoForge 对照表

以 1.21.11 / 26.1.x 的 NeoForge 源码为准（路径相对 `neoforged/NeoForge` 仓库）：

| 功能 | Fabric（本仓库现状） | NeoForge |
| --- | --- | --- |
| 入口 | `ModInitializer#onInitialize` | `@Mod("xmc_mcserver_core")` 构造器 + `@EventBusSubscriber(modid = …)`（mod bus） |
| 元数据 | `src/main/resources/fabric.mod.json` | `META-INF/neoforge.mods.toml`（`modLoader="javafml"`、`loaderVersion`、`[[dependencies]]`） |
| 注册配置阶段通道 | `PayloadTypeRegistry.configurationC2S()/configurationS2C().register(type, codec)` | `RegisterPayloadHandlersEvent` → `PayloadRegistrar`，`configurationToServer/ToClient/configurationBidirectional` |
| 必须 optional | —（Fabric 不要求） | **`event.registrar("1").optional()`**；`optional()`/`executesOn()` 返回**新实例**，要重新赋值 |
| 接收回调 | `ServerConfigurationNetworking.registerGlobalReceiver(type, (payload, ctx) -> …)` | `registrar.configurationToServer(type, codec, (payload, ctx) -> …)` |
| 取监听器 | `ctx.networkHandler()`（1.21.x）/ `ctx.packetListener()`（26.x） | `ctx.listener()` → 强转 `ServerConfigurationPacketListenerImpl`（返回类型是 `ICommonPacketListener`） |
| 取玩家名 | `listener.getOwner().name()` / `.getName()` | 同左（authlib 版本决定 `name()` / `getName()`） |
| 往 config 阶段发载荷 | `ServerConfigurationNetworking.send(listener, payload)` | `listener.send(payload)`（`ICommonPacketListener#send(CustomPacketPayload)`）或 `ctx.reply(payload)` |
| 断连 | `ServerConfigurationConnectionEvents.DISCONNECT` | **没有对应事件**：轮询 `listener.isAcceptingMessages()`（等价 `connection.isConnected()`）或发送失败兜底 |
| 服务端生命周期 | `ServerLifecycleEvents.SERVER_STARTING/STARTED/STOPPING` | `ServerAboutToStartEvent` / `ServerStartedEvent` / `ServerStoppingEvent`（`net.neoforged.neoforge.event.server`） |
| 配置目录 | `FabricLoader#getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |
| 载荷上限 | 服务端 32767 / 客户端 1 MiB | 同（vanilla 常量，未变） |

`PayloadRegistrar` 的可用方法（26.1.x）：
`playToClient/playToServer/playBidirectional`、`configurationToClient/configurationToServer/configurationBidirectional`、
`commonToClient/commonToServer/commonBidirectional`、`executesOn(HandlerThread)`、`versioned(String)`、`optional()`。
本模组只需要 `configurationToServer(data)` + `configurationToClient(data)` + `configurationToClient(pad)`。

---

## 4. NeoForge 的三道闸（源码依据）

`net/neoforged/neoforge/network/registration/NetworkRegistry.java`（26.1.x 行号）：

### 4.1 未协商就发 modded 载荷 → 断开

```java
NetworkPayloadSetup payloadSetup = ChannelAttributes.getPayloadSetup(listener.getConnection());
if (payloadSetup == null) {
    LOGGER.warn("Received a modded payload before channel negotiation; disconnecting.");
    listener.disconnect(...);
    return;
}
```

`payloadSetup` 只有两个入口会设置：

- `initializeNeoForgeConnection(...)`：客户端发 `ModdedNetworkQueryPayload`（真 NeoForge 客户端）；
- `initializeOtherConnection(...)`：客户端回 `Pong(0)`（"原版/其它客户端"路径）。

### 4.2 原版路径需要 pong(0)，而 vanilla 配置被挪到 pong 之后

`patches/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch` 把 vanilla 的
`startConfiguration()` 改名成 `runConfiguration()`，并先发：

1. `MinecraftUnregisterPayload`
2. `MinecraftRegisterPayload`
3. `ModdedNetworkQueryPayload(Map.of())`
4. `ClientboundPingPacket(0)`

收到 `Pong(0)` 时才执行 `initializeOtherConnection(this)`（协商通过后）再 `runConfiguration()`。

→ **XMC 客户端必须回 `pong(0)`**（config 状态 c2s `0x05`，id=0），否则 `payloadSetup` 永远是 null，
第一条 `xmc:data` 就被 4.1 断开。keepalive 那套保活不能替代它。

### 4.3 发自定义载荷要过 `checkPacket`

`ServerCommonPacketListenerImpl` 的 `send(Packet, ChannelFutureListener)` 被 patch 上了
`NetworkRegistry.checkPacket(...)`：

- `minecraft:` 域或 NeoForge 内置载荷 → 放行；
- 否则 `hasChannel(...)` 必须为真，**不然直接抛 `UnsupportedOperationException`**；
- `hasChannel` 的兜底是 `ChannelAttributes.getOrCreateAdHocChannels(connection)`，而它由客户端发来的
  **`minecraft:register`** 填充（`NetworkRegistry.onMinecraftRegister`）。

→ **XMC 客户端必须发 `minecraft:register`**，内容为 NUL（`\0`）分隔的通道名，例如
`xmc:data\0xmc:pad`。编解码见 `payload/DinnerboneProtocolUtils.java#CHANNELS_CODEC`
（读：逐字节累积到 `\0` 再 `Identifier.parse`）。

### 4.4 不能把通道塞进 `minecraft:` 域

```java
if ("minecraft".equals(type.id().getNamespace())) {
    throw new UnsupportedOperationException("Cannot register payload ... using the domain \"minecraft\".");
}
```

别想着用 `minecraft:` 命名空间绕过 4.3 的分发。

### 4.5 非 optional 载荷 = 拒绝原版客户端

`initializeOtherConnection` 会拿"空通道列表"和**全服注册的载荷**协商，任何非 optional 载荷对不上就整条拒绝：

> You are trying to connect to a server that is running NeoForge, but you are not. Please install NeoForge Version: …

→ 本模组的两个通道**必须** `optional()`；并且要在文档里写清 4.5 的产品限制。

---

## 5. 不需要 mixin 的实现方案（推荐）

### 服务端

1. `RegisterPayloadHandlersEvent` 里：`event.registrar("1").optional()` 拿到新 registrar；
2. `configurationToServer(dataType, DATA_CODEC, handler)`、`configurationToClient(dataType, DATA_CODEC)`、
   `configurationToClient(paddingType, PADDING_CODEC)`；
3. 收包回调里 `ctx.listener()` 强转 `ServerConfigurationPacketListenerImpl`，用 `getOwner()` 取名、
   `isAcceptingMessages()` 判断可写；
4. 发送（隧道读线程 → 客户端）用 `listener.send(new XmcPayloads.Data(chunk))`；
   只回同一条连接时 `ctx.reply(payload)` 更省事（异步线程里用 listener）；
5. 断连检测：一个 1s 的调度扫 `Map<listener, session>`，`!listener.isAcceptingMessages()` 即回收；
6. 生命周期改用 `ServerAboutToStartEvent/ServerStartedEvent/ServerStoppingEvent`；
7. 配置目录 `FMLPaths.CONFIGDIR.get().resolve("xmc-mcserver-core.json")`。

### 客户端（Xray 侧，必须）

在 `transport/internet/finalmask/xmc/` 里：

1. 进 configuration 状态后，**先容忍** NeoForge 先发的 4 个包（unregister / register / query / ping），
   只按包 id 认自己要的（config s2c `0x01` custom payload、`0x04` keepalive、`0x05` ping）；
2. 收到 `ping(0)` → 回 `pong(0)`（config c2s `0x05`）；
3. 发 `minecraft:register`（config c2s `0x02` custom payload：channel = `minecraft:register`，
   payload = `xmc:data\0xmc:pad`）；
4. 之后才是现有的 padding → data 流程。

这两个帧在 vanilla/Fabric 上都是无害的（vanilla 把 `minecraft:register` 当 `DiscardedPayload` 丢掉，
无 pending ping 时 pong 只更新延迟），可以**无条件对所有服务端发**，不必按加载器分支。

### 如果坚持不改客户端

只能 mixin `NetworkRegistry.handleModdedPayload` / `checkPacket`（或 `ChannelAttributes.getPayloadSetup`），
伪造 `payloadSetup` 并放行未协商通道。这不只是"多加一个 mixin"，而是在拆别人的安全检查，还要跨多个
NeoForge 版本维护。**不建议**。

---

## 6. 版本矩阵与构建

NeoForge 版本号跟 MC 走（`<MC minor>.<patch>`）：

| 分支 | MC | NeoForge | JDK | 备注 |
| --- | --- | --- | --- | --- |
| `neoforge/26.3` | 26.3 | 26.3.x | 25 | |
| `neoforge/26.2` | 26.2 | 26.2.x | 25 | |
| `neoforge/26.1` | 26.1 | 26.1.x | 25 | **先做这个**（客户端协议 775 现成） |
| `neoforge/1.21.11` | 1.21.11 | 21.11.x | 21 | |
| `neoforge/1.21.8` | 1.21.8 | 21.8.x | 21 | 客户端 handler 从这版起走 `RegisterClientPayloadHandlersEvent` |
| `neoforge/1.21.5` | 1.21.5 | 21.5.x | 21 | |
| `neoforge/1.21.4` | 1.21.4 | 21.4.x | 21 | |
| `neoforge/1.21.1` | 1.21.1 | 21.1.x | 21 | |

构建用 **ModDevGradle**（`net.neoforged.moddev`，NeoForge 官方现在推荐），不要用 ForgeGradle；
各分支各自 pin 对应的 ModDevGradle / NeoForge 版本，别指望一套脚本通吃。
JDK 必须按表选：26.x 用 25，1.21.x 用 21（用 27 跑 1.21.x 的 dev server 会被 Mixin 挡住，见第 8 节）。

---

## 7. 测试环境怎么搭（从零开始）

> 没有现成环境时按这一节搭。**最小验证（A）不需要 Xray，半小时内能跑通；完整 e2e（B）需要一个
> 带 XMC 补丁的 Xray 构建。**

### 7.0 前置

- Linux（或 WSL2），能连外网。
- **JDK**：26.x 需要 25，1.21.x 需要 21。推荐 Temurin：

  ```bash
  # 例：JDK 21（1.21.x 分支）
  curl -L -o jdk21.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf jdk21.tar.gz && export JAVA_HOME="$PWD/jdk-21.0.13+9" && export PATH="$JAVA_HOME/bin:$PATH"
  ```

  跑 dev server 时用 `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" runServer` 指定，
  别依赖系统默认 JDK。
- Gradle 用仓库自带的 wrapper（`./gradlew`），不需要单独装。
- 端口占用检查：`ss -ltnp | grep -E ':(25565|25566|10086|1080|18080|8090)'`。

### 7.1 A. 最小验证：dev server + probe（不需要 Xray）

`src/test/java/xmc/probe/XmcProbe.java` 自带 mock Xray：它连 25565 做一次完整登录 +
configuration 握手，然后在本地监听 10086 充当"Xray 落地端"，验证 padding 与 data 通道双向可通。

```bash
git checkout fabric/26.1          # 或未来的 neoforge/26.1
mkdir -p run && echo "eula=true" > run/eula.txt   # 首次运行由 Loom 生成 run/，需要接受 EULA

# probe 要求：离线模式 + 关闭压缩
sed -i 's/^online-mode=.*/online-mode=false/'        run/server.properties 2>/dev/null
sed -i 's/^network-compression-threshold=.*/network-compression-threshold=-1/' run/server.properties 2>/dev/null

./gradlew runServer                      # 终端 1，等到 "Done (" 出现
./gradlew probe -Pxmc.probe.protocol=775 # 终端 2（26.1）；1.21.8 用 772，1.21.1 用 767…
```

看到 `[probe] tunnel received: HELLO-BACK` 即通过。服务端日志应出现
`XMC channels registered` / `XMC enabled` / `XMC landing detected`。

版本与协议号对照见 [VERSIONS.md](VERSIONS.md)；协议号不对握手会被 vanilla 直接断开
（`ServerHandshakePacketListenerImpl` 里比较 `WorldVersion.protocolVersion()`）。

### 7.2 B. 完整 e2e：真实 Xray 客户端

**组件与端口**

```
curl --socks5 127.0.0.1:1080            # 客户端入口
        │
        ▼  Xray 客户端：socks-in 1080 → vless-out → 127.0.0.1:25565
        │              streamSettings.finalmask.tcp[].type = "xmc"
        ▼
MC 服务端 25565（online-mode=true + 本模组）
        │  模组把 xmc:data 通道的字节转发到 ↓
        ▼
Xray 服务端：vless inbound 127.0.0.1:10086（decryption=none）→ freedom
        │
        ▼
HTTP 目标（例：python3 -m http.server 18080）→ 用 socks 拉一个文件校验 sha256
```

**1) Xray 构建**

XMC 传输还没进 Xray 发行版，需要带以下补丁的构建：

- #6769 / #7402：`finalmask` 的 `xmc` transport + 原生帧 padding（breaking，默认生效）
- #7394：`Set Compression` 支持
- #7416：Yggdrasil 第三方登录（**本模组只做服务端，不做认证**，所以客户端必须走 `yggdrasil` 模式）

自检：

```bash
xray version                      # 记住 commit
xray run -c client.json           # 若报 unknown transport "xmc"，说明构建不对
```

**2) 认证怎么配（二选一）**

- **真实账号 + 官方 API**：服务端 `online-mode=true`（不需要 authlib-injector），客户端 XMC 配置里
  `yggdrasil.api` 指向官方 authserver。注意这要求 Xray 的 Yggdrasil 模式支持官方 API 形态。
- **第三方 Yggdrasil（皮肤站等）**：服务端加 authlib-injector：

  ```bash
  # 最新版本与下载地址：https://authlib-injector.yushi.moe/artifact/latest.json
  # 从 json 里取 download_url 下载 jar，然后：
  JAVA_TOOL_OPTIONS="-javaagent:authlib-injector.jar=<你的 yggdrasil API 根>" ./gradlew runServer
  ```

  客户端 XMC 的 `yggdrasil.api` 指向同一个根，`username`/`password`/`player` 填该站的账号。
  （只想本地跑通、不接第三方站的话，可以自己写一个最小 Yggdrasil：`authserver/authenticate`、
  `authserver/refresh`、`authserver/validate`、`sessionserver/session/minecraft/hasJoined`，
  再加一个带 `signaturePublickey` 的 meta；`hasJoined` 返回对应 UUID + 名字即可。）

**3) 服务端准备**

```properties
online-mode=true
network-compression-threshold=65536   # 让 padding/data 保持未压缩，-1 也可
enforce-secure-profile=false          # XMC 客户端没有聊天签名密钥
server-port=25565
```

`run/config/xmc-mcserver-core.json`（模组配置，默认即可）：

```json
{ "enabled": true, "maxPayloadBytes": 24576,
  "channels": { "data": "xmc:data", "padding": "xmc:pad" },
  "tunnel": { "host": "127.0.0.1", "port": 10086, "connectTimeoutMillis": 5000 } }
```

**4) Xray 服务端 / 客户端配置骨架**

服务端（`vless` inbound 10086 + `freedom` outbound）：

```json
{
  "log": { "loglevel": "info" },
  "inbounds": [{ "tag": "vless-in", "listen": "127.0.0.1", "port": 10086,
    "protocol": "vless",
    "settings": { "clients": [{ "id": "00112233-4455-6677-8899-aabbccddeeff" }], "decryption": "none" } }],
  "outbounds": [{ "tag": "direct", "protocol": "freedom" }]
}
```

客户端（socks 1080 → 25565，`finalmask.xmc`）：

```json
{
  "log": { "loglevel": "info" },
  "inbounds": [{ "tag": "socks-in", "listen": "127.0.0.1", "port": 1080,
    "protocol": "socks", "settings": { "auth": "noauth", "udp": false } }],
  "outbounds": [{
    "tag": "vless-out", "protocol": "vless",
    "settings": { "vnext": [{ "address": "127.0.0.1", "port": 25565,
      "users": [{ "id": "00112233-4455-6677-8899-aabbccddeeff", "encryption": "none" }] }] },
    "streamSettings": { "network": "tcp", "finalmask": { "tcp": [{
      "type": "xmc",
      "settings": {
        "hostname": "mc.example.com",
        "yggdrasil": { "api": "<yggdrasil API 根>", "username": "<账号>",
                       "password": "<密码>", "player": "<角色名>" },
        "paddings": [
          { "length": "32768", "direction": "s2c" },
          { "length": "65536", "direction": "c2s", "delay": "600" }
        ]
      } }] } }
  }]
}
```

**5) 跑起来**

```bash
# 终端 1：目标 HTTP
mkdir -p www && head -c 100 /dev/urandom > www/small.bin
(cd www && python3 -m http.server 18080)

# 终端 2：Xray 服务端（隧道出口）
xray run -c xray-server.json

# 终端 3：MC 服务端（按 7.1 节方式启动，online-mode=true + authlib-injector）

# 终端 4：Xray 客户端
xray run -c xray-client.json

# 终端 5：验证
curl --socks5-hostname 127.0.0.1:1080 -o got.bin http://127.0.0.1:18080/small.bin
sha256sum got.bin www/small.bin          # 两个哈希必须一致
```

服务端日志出现 `XMC landing detected for <player>`、Xray 服务端日志出现
`accepted tcp:127.0.0.1:18080 [vless-in >> direct]`，即链路打通。

> **协议号限制**：Xray 的 XMC 客户端把协议号写死在一个常量里（当前 775）。
> 所以完整 e2e 只能对 **26.1** 做；26.2/26.3 和 1.21.x 目前只能用 probe 冒烟，
> 等 Xray 支持多版本（这也是需要上游 PR 的部分）。

### 7.3 C. NeoForge 自己的冒烟

NeoForge 版本除了上面的步骤，还要先验证：

1. 纯净 NeoForge dev server 能起（`./gradlew runServer`），mod 列表里有本模组；
2. 日志出现 `XMC channels registered`（说明 `optional()` 注册成功、没被 `minecraft:` 域检查拒绝）；
3. **普通玩家能进**（这一步验证 4.5：没有非 optional 载荷挡路）；
4. 再跑 probe 的 neo 变体（需要先实现第 5 节客户端侧的 `pong(0)` + `minecraft:register`）。

---

## 8. 这一轮踩过的坑（别再踩）

1. **Loom 1.19 + 26.1 之前的 Fabric API** 会直接失败：
   `Expected official namespace for access widener entry, found: intermediary`。
   解决方式见 [VERSIONS.md](VERSIONS.md)（1.21.x 用 Loom 1.13.6 + official mappings + `modImplementation`）。
2. **旧 Loom 的插件 id 是 `fabric-loom`**（不是 `net.fabricmc.fabric-loom`），且没有 plugin marker，
   要在 `settings.gradle` 用 `resolutionStrategy` 映射到 `net.fabricmc:fabric-loom`。
3. **JDK 太高会挡住老 dev server**：Fabric Loader 0.16.x 的 Mixin 0.8.7 读不了 JDK 27 的 class 文件
   （`Unsupported class file major version 71`）。1.21.x 一律用 JDK 21 起 dev server。
4. **vanilla 会拒绝协议号不匹配的握手**：必须给 probe/客户端填对目标版本的协议号。
5. **临时目录别用内存盘**：下载的 jar、解压的 mappings 放磁盘目录（例如 `~/.cache/xmc`），
   不要塞进 `tmpfs` 挂载的 `/tmp`，否则几十上百 MB 会直接吃掉内存。

---

## 9. 下一步清单（按顺序）

1. `neoforge/26.1`：ModDevGradle 骨架 + `neoforge.mods.toml` + `optional()` 注册，先只验证
   "纯净 NeoForge 服能启动、普通玩家能进、XMC 通道注册成功"。
2. 在 Xray 侧加 4.2/4.3 的两个帧（本地改一个 Xray 构建即可），跑通 e2e；把这部分整理成上游 PR 草案
   （写进 PR 描述或 `docs/`，不要依赖本地临时文件）。
3. 铺 `neoforge/26.2`、`neoforge/26.3`（API 面基本一致，只换版本号 + 协议号）。
4. 再铺 1.21.x（注意 1.21.8 起客户端 handler 走 `RegisterClientPayloadHandlersEvent`）。
5. 给 probe 加 neo 变体（`pong(0)` + `minecraft:register`），让每个分支都能离线冒烟。
6. 把 4.5 的限制、以及"需要客户端配合"写进 README；协议 776/777 也要等 Xray 客户端支持。
