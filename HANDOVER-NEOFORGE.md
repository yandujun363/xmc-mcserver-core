# NeoForge 接手文档

给下一次接手 **NeoForge 移植** 的会话/人。Fabric 侧已完成并推送，本文只讲 NeoForge。

- 适用版本：**1.21.x + 26.x**，不考虑 Forge、不考虑 1.20.x（原因见 Fabric 侧结论：
  1.20.1 没有 configuration 状态，Xray 客户端也只实现了协议 775）。
- 目标形式：`neoforge/<MC 版本>` 分支，每个分支独立构建，和 Fabric 分支同一套约定。
- 硬约束：**尽量不 mixin**。

---

## 1. 先看结论

1. **API 移植本身很简单**，这个模组的 MC 面只有 8 个符号，NeoForge 侧对应关系见第 3 节，半天到一天。
2. **真正的门槛是 NeoForge 的通道协商**，有三道闸（第 4 节），其中两道**必须改 Xray 客户端**才能过，否则第一个 `xmc:data` 载荷到达时服务端直接断开。
3. **不需要 mixin**：服务端用 `PayloadRegistrar.optional()` + `listener.send()` + 轮询断连检测即可；客户端补两个 vanilla 合法帧（`pong(0)`、`minecraft:register`）。
4. 但**实际只适用于"纯净 + 本模组"的服务端**：NeoForge 服务端只要装了任何一个注册了**非 optional** 通道的模组，XMC 客户端在协商阶段就会被整条拒绝。这一点必须写进面向用户文档。

---

## 2. 现状（交接点）

已推送的 Fabric 分支（各分支独立构建，均通过构建 + 本地冒烟；26.1 通过完整 e2e）：

| 分支 | MC | 协议 | Fabric API | Loader | Loom | Java |
| --- | --- | --- | --- | --- | --- | --- |
| `master` / `fabric/26.1` | 26.1 | 775 | 0.145.1+26.1 | 0.20.1 | 1.19.4 | 25 |
| `fabric/26.2` | 26.2 | 776 | 0.161.0+26.2 | 0.20.1 | 1.19.4 | 25 |
| `fabric/26.3` | 26.3 | 777 | 0.166.2+26.3 | 0.20.1 | 1.19.4 | 25 |
| `fabric/1.21.11` | 1.21.11 | 774 | 0.141.6+1.21.11 | 0.18.4 | 1.13.6 | 21 |
| `fabric/1.21.8` | 1.21.8 | 772 | 0.136.1+1.21.8 | 0.17.3 | 1.13.6 | 21 |
| `fabric/1.21.5` | 1.21.5 | 770 | 0.128.2+1.21.5 | 0.16.14 | 1.13.6 | 21 |
| `fabric/1.21.4` | 1.21.4 | 769 | 0.119.4+1.21.4 | 0.16.14 | 1.13.6 | 21 |
| `fabric/1.21.1` | 1.21.1 | 767 | 0.116.18+1.21.1 | 0.16.14 | 1.13.6 | 21 |

分支间差异、同步规则、测试方法见 [VERSIONS.md](VERSIONS.md)。核心结论：**只有
`lib/java/xmc/lib/net/` 与构建脚本按版本分叉，`core/`、`config/`、`src/test/` 全分支一致**。

---

## 3. Fabric → NeoForge 对照表

以 1.21.11 / 26.1.x 的 NeoForge 源码为准（路径相对 `neoforged/NeoForge` 仓库）：

| 功能 | Fabric（本仓库现状） | NeoForge |
| --- | --- | --- |
| 入口 | `ModInitializer#onInitialize` | `@Mod("xmc_mcserver_core")` 构造器 + `@EventBusSubscriber(modid = …)`（mod bus） |
| 元数据 | `src/main/resources/fabric.mod.json` | `META-INF/neoforge.mods.toml`（`modLoader="javafml"`、`loaderVersion`、`[[dependencies]]`） |
| 注册配置阶段通道 | `PayloadTypeRegistry.configurationC2S()/configurationS2C().register(type, codec)` | `RegisterPayloadHandlersEvent` → `PayloadRegistrar`，`configurationToServer/ToClient/configurationBidirectional` |
| 必须 optional | —（Fabric 不要求） | **`event.registrar("1").optional()`**，注意 `optional()`/`executesOn()` 返回**新实例**，要重新赋值 |
| 接收回调 | `ServerConfigurationNetworking.registerGlobalReceiver(type, (payload, ctx) -> …)` | `registrar.configurationToServer(type, codec, (payload, ctx) -> …)` |
| 取监听器 | `ctx.networkHandler()`（1.21.x）/ `ctx.packetListener()`（26.x） | `ctx.listener()` → 强转 `ServerConfigurationPacketListenerImpl`（`IPayloadContext#listener()` 返回 `ICommonPacketListener`） |
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
第一条 `xmc:data` 就被 4.1 断开。聊天/保活那套 keepalive 不能替代它。

### 4.3 发自定义载荷要过 `checkPacket`

`ServerCommonPacketListenerImpl` 的 `send(Packet, ChannelFutureListener)` 被 patch 上了
`NetworkRegistry.checkPacket(...)`：

- `minecraft:` 域或 NeoForge 内置载荷 → 放行；
- 否则 `hasChannel(...)` 必须为真，**不然直接抛 `UnsupportedOperationException`**；
- `hasChannel` 的兜底是 `ChannelAttributes.getOrCreateAdHocChannels(connection)`，而它由客户端发来的
  **`minecraft:register`** 填充（`NetworkRegistry.onMinecraftRegister`）。

→ **XMC 客户端必须发 `minecraft:register`**，内容为 NUL（`\0`）分隔的通道名，例如
`xmc:data\0xmc:pad`。payload 编解码见 `payload/DinnerboneProtocolUtils.java#CHANNELS_CODEC`
（读：逐字节累积到 `\0` 再 `Identifier.parse`）。

### 4.4 还有一条：不能把通道塞进 `minecraft:` 域

`NetworkRegistry.register(...)` 里：

```java
if ("minecraft".equals(type.id().getNamespace())) {
    throw new UnsupportedOperationException("Cannot register payload ... using the domain \"minecraft\".");
}
```

所以别想着用 `minecraft:` 命名空间绕过 4.3 的分发。

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
   如果只是"回一条给同一条连接"，`ctx.reply(payload)` 更省事（但异步线程里要用 listener）；
5. 断连检测：一个 1s 的调度扫 `Map<listener, session>`，`!listener.isAcceptingMessages()` 即回收；
6. 生命周期改用 `ServerAboutToStartEvent/ServerStartedEvent/ServerStoppingEvent`；
7. 配置目录 `FMLPaths.CONFIGDIR.get().resolve("xmc-mcserver-core.json")`。

### 客户端（Xray 侧，必须）

在 `transport/internet/finalmask/xmc/` 里：

1. 进 configuration 状态后，**先容忍** NeoForge 先发的 4 个包（unregister / register / query / ping），
   只按包 id 认自己要的（config s2c `0x01` custom payload、`0x04` keepalive、`0x05` ping）；
2. 收到 `ping(0)` → 回 `pong(0)`（config c2s `0x05`）；
3. 发 `minecraft:register`（config c2s `0x02` custom payload：channel = `minecraft:register`，
   payload = `xmc:data\0xmc:pad`，NUL 结尾可选）；
4. 之后才是现有的 padding → data 流程。

这两个帧在 vanilla/Fabric 上都是无害的（vanilla 把 `minecraft:register` 当 `DiscardedPayload` 丢掉，
无 pending ping 时 pong 只更新延迟），可以**无条件对所有服务端发**，不必按加载器分支。

### 如果坚持不改客户端

只能 mixin `NetworkRegistry.handleModdedPayload` / `checkPacket`（或 `ChannelAttributes.getPayloadSetup`），
伪造一个 `payloadSetup` 并放行未协商通道。这不只是"多加一个 mixin"，而是在拆别人的安全检查，
而且要在多个 NeoForge 版本间维护。**不建议**。

---

## 6. 版本矩阵与构建

NeoForge 版本号跟 MC 走（`<MC minor>.<patch>`）：

| 分支 | MC | NeoForge | JDK | 备注 |
| --- | --- | --- | --- | --- |
| `neoforge/26.3` | 26.3 | 26.3.x | 25 | 26.1.x 分支结构同上 |
| `neoforge/26.2` | 26.2 | 26.2.x | 25 | |
| `neoforge/26.1` | 26.1 | 26.1.x | 25 | **先做这个**（客户端协议 775 现成） |
| `neoforge/1.21.11` | 1.21.11 | 21.11.x | 21 | |
| `neoforge/1.21.8` | 1.21.8 | 21.8.x | 21 | |
| `neoforge/1.21.5` | 1.21.5 | 21.5.x | 21 | |
| `neoforge/1.21.4` | 1.21.4 | 21.4.x | 21 | |
| `neoforge/1.21.1` | 1.21.1 | 21.1.x | 21 | `PayloadRegistrar` 已在，注意客户端 handler 拆分是 1.21.8 的事 |

构建用 **ModDevGradle**（NeoForge 官方现在推荐，`net.neoforged.moddev`），不要用 ForgeGradle；
1.21.x 分支各自 pin 对应的 ModDevGradle/NeoForge 版本，别指望一套脚本通吃。

---

## 7. 本地测试台（照抄）

组件都在 `/home/yangdujun/dshwork/.tmpf/xmc-e2e/`：

| 组件 | 端口 | 启动 |
| --- | --- | --- |
| `yggdrasil-server` | 8090 | `./yggdrasil-server -address 127.0.0.1:8090` |
| 静态 HTTP（`www/small.bin`） | 18080 | `cd www && python3 -m http.server 18080` |
| `relay-bin`（统计双向字节数） | 25566 → 25565 | `./relay-bin -listen 127.0.0.1:25566 -target 127.0.0.1:25565 -log relay.log` |
| `xray-fixed`（VLESS inbound，隧道出口） | 10086 | `./xray-fixed run -c xray-server.json` |
| `xray-fixed`（socks 入口 + finalmask/xmc） | 1080 | `./xray-fixed run -c xray-client.json` |
| MC 服务端 | 25565 | 见下 |

MC 服务端（Fabric 侧方式，NeoForge 换成 `runServer` 即可）：

```bash
# run/server.properties: online-mode=true, network-compression-threshold=65536
JAVA_TOOL_OPTIONS="-javaagent:$BENCH/authlib-injector.jar=http://127.0.0.1:8090/api/yggdrasil" \
  ./gradlew runServer
# 等待 Done (，然后
curl --socks5-hostname 127.0.0.1:1080 http://127.0.0.1:18080/small.bin -o out.bin
```

现成脚本：`.tmpf/mcver/e2e-261.sh`（Fabric 26.1 全链路，改 Server 启动命令即可复用），
日志在 `.tmpf/mcver/e2e/`。

冒烟（不需要 Xray）：`src/test/java/xmc/probe/XmcProbe.java` 自带 mock Xray（监听 10086），
NeoForge 版要给它加一个变体：**先回 pong(0) + 发 minecraft:register**，其余流程照旧。

```bash
# dev server 必须 online-mode=false、network-compression-threshold=-1
./gradlew probe -Pxmc.probe.protocol=775
```

---

## 8. 这一轮踩过的坑（别再踩）

1. **Loom 1.19 + 26.1 之前的 Fabric API**：`Expected official namespace for access widener entry, found: intermediary`。
   解决方式见 [VERSIONS.md](VERSIONS.md)（1.21.x 用 Loom 1.13.6 + official mappings + `modImplementation`）。
2. **旧 Loom 没有 plugin marker**：插件 id 是 `fabric-loom` 而不是 `net.fabricmc.fabric-loom`，
   且要在 `settings.gradle` 用 `resolutionStrategy` 映射到 `net.fabricmc:fabric-loom`。
3. **JDK 27 跑 1.21.x 的 dev server 会炸**：Fabric Loader 0.16.x 的 Mixin 0.8.7 报
   `Unsupported class file major version 71`。1.21.1/1.21.4/1.21.5 必须用 JDK 21
   （本机在 `.tmpf/jdk/jdk-21.0.13+9`），26.x 用 JDK 27 没问题。NeoForge 侧同理要按版本选 JDK。
4. **vanilla 会拒绝协议号不匹配的握手**（`ServerHandshakePacketListenerImpl` 里
   `if_icmpeq WorldVersion.protocolVersion()`，不匹配就发 Login Disconnect）。probe 必须用
   `-Pxmc.probe.protocol=<n>`。
5. **`/tmp` 是 tmpfs（3.9G）**：解压 jar、临时目录一律放 `.tmpf/`，别往 `/tmp` 塞。

---

## 9. 下一步清单（按顺序）

1. `neoforge/26.1`：ModDevGradle 骨架 + `neoforge.mods.toml` + `optional()` 注册，先只验证
   "纯净 NeoForge 服能启动、普通玩家能进、XMC 通道注册成功"。
2. 在本地 `xray-fixed`（或 Xray 分支）里加 4.2/4.3 的两个帧，跑通 e2e；先把这步的补丁留在
   `.tmpf` 里作为上游 PR 草案。
3. 铺 `neoforge/26.2`、`neoforge/26.3`（API 面基本一致，只换版本号 + 协议号）。
4. 再铺 1.21.x（注意 1.21.8 起客户端 handler 走 `RegisterClientPayloadHandlersEvent`）。
5. 给 probe 加 neo 变体，让每个分支都能离线冒烟。
6. 把 4.5 的限制、以及"需要客户端配合"写进 README；协议 776/777 也要等 Xray 客户端支持。
