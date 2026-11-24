# 架构说明

## 边界

模组只做三件事：注册数据通道和 padding 通道（默认 `xmc:data` / `xmc:pad`）、在
第一条数据通道载荷到来时建立会话、把数据通道的字节原样转发给配置里的本地 TCP
端点。

端点上的内容模组不看：载荷按字节搬运，不解析、不分类（见下文[字节转发边界](#字节转发边界)）。

登录、加密、Yggdrasil / 离线认证、白名单与封禁全部由 vanilla 负责；模组
**没有 mixin**，不改任何 vanilla 代码路径，配置里没有密码、玩家档案或密钥派生。

通道名来自配置而不是写死在代码里：固定的默认值是链路上可识别的指纹，部署方可以
同时替换两端的通道名。

## 连接时序（协议 774）

```
C -> S  Handshake        id 0x00  protocol=774, address, port, nextState=2
C -> S  Login Start      id 0x00  username, uuid
S -> C  Encryption Req   id 0x01  vanilla 自己的 RSA 公钥
C -> S  Encryption Resp  id 0x01  encrypted(secret), encrypted(verifyToken)
        * AES/CFB8；在线模式下 vanilla 通过 session server 的 hasJoined 校验
S -> C  Login Success    id 0x02  uuid, username, properties
C -> S  Login Ack        id 0x03  (无字段)
        * 从这里开始全部是合法 Minecraft 帧
C -> S  padding 通道     id 0x02  channel + padding 数据（vanilla 丢弃；默认 xmc:pad）
S -> C  padding 通道     id 0x01  channel + padding 数据（客户端丢弃；默认 xmc:pad）
C -> S  数据通道         id 0x02  channel + 数据通道字节（默认 xmc:data）
S -> C  数据通道         id 0x01  channel + 数据通道字节（默认 xmc:data）
S -> C  Keep Alive       id 0x04  long id
C -> S  Keep Alive       id 0x04  long id（回显）
```

普通玩家在 Login Ack 之后发送 `ClientInformation`，照常进入
Configuration → Play；模组不改变任何字节。

## 通道注册

`lib/net/XmcPayloads` 定义两个 `CustomPacketPayload`，id 由
`XmcConfig.Channels` 决定（默认 `xmc:data` / `xmc:pad`）：

- 数据通道：双向注册（`PayloadTypeRegistry.serverboundConfiguration()` 与
  `clientboundConfiguration()`）；
- padding 通道：只注册 clientbound（服务端发送 padding 用）。

注册发生在 `XmcMod#onInitialize`，也就是任何连接存在之前：`PayloadTypeRegistry`
没有注销接口，第一次连接之后不能再注册，同一个 id 重复注册还会抛
`IllegalArgumentException`。因此通道名在读取配置时就按 `Identifier` 的字符规则
校验（namespace `[a-z0-9_.-]+`、path `[a-z0-9/._-]+`、非空、不是保留的
`minecraft`、两通道不相同），注册前就能排除 `IdentifierException` 和自撞名；
注册仍然失败（例如和另一个模组撞名）时，`XmcMod` 捕获异常、只把 XMC 置为
disabled，vanilla 照常启动。

接收端用 Fabric 的 `ServerConfigurationNetworking.registerGlobalReceiver`，
回调跑在服务器线程；发送用 `ServerConfigurationNetworking.send`。

未注册的 channel（普通客户端不会发数据通道）由 vanilla 的
`ServerCommonPacketListenerImpl#handleCustomPayload` 空实现丢弃，Fabric 的
networking 层对没有 handler 的 channel 返回 `false` 后也交回 vanilla。

## 会话生命周期

`core/session/XmcRuntime` 用 `Map<ServerConfigurationPacketListenerImpl, XmcSession>`
记录活跃会话：

1. 第一条数据通道载荷 → 创建 `XmcSession` 并启动线程；
2. 线程先发 s2c 启动 padding（`core/padding/PaddingFrames`，每个捕获的 chunk
   拆成不超过 32767 字节的 padding 通道载荷），再连接 `tunnel.host:port`；
3. socket 读线程把字节入队（上限 4 MiB，满了阻塞，形成背压）；
4. 每个 tick 由 `TickSender` 把队列合并刷给客户端：按 `maxPayloadBytes` 切块，
   每块一个数据通道载荷，空闲的 tick 补一个 1 字节 padding 载荷；
5. 主线程把客户端数据通道载荷写入 socket（先入队，避免阻塞服务器线程）；
6. `ServerConfigurationConnectionEvents.DISCONNECT` 或任意一端关闭 → 关闭会话。

客户端发来的 padding 通道载荷会被 vanilla 丢弃，模组不需要读；客户端自己的
padding 也不会被消费。所以 padding 的方向、时机完全由发送方决定，两端没有校验
依赖。

每 tick 一次的节拍来自 `ServerTickEvents.END_SERVER_TICK`，和真实服务器「一个 tick
发一批包」的形态一致。空服被 vanilla 暂停时该事件不再触发，所以 `XmcMod` 另起一个
100 ms 看门狗兜底：超过 100 ms 没等到 tick 就照常刷一轮。

## 字节转发边界

`TunnelConnector` 只做三件事：解析 `tunnel.host`、确认它是私有地址、`Socket.connect`
并打开 `TCP_NODELAY`。`XmcSession` 两边都是纯字节搬运：

- socket → 客户端：`read(buffer)` 之后入队，每个 tick 合并刷出，按 `maxPayloadBytes`
  切块，每块一个数据通道载荷；
- 客户端 → socket：载荷先入队，会话线程 `out.write(data)`，队列空了再 flush。

整条链路没有一处读协议头，没有协议字段，也没有硬编码端口，`tunnel.host:port` 上
监听什么，模组就原样转发什么。限制也来自"字节流"：只有 TCP、一条连接一条流、
不做 mux。

这条边界也划出了红线：任何需要**解析**转发内容的改动（协议探测、按端口分流、
内容过滤、按协议改 MTU）都会把这个性质弄没，加之前先想清楚。

## 为什么不接管 vanilla

旧实现需要在 Login Ack 处拆掉 `splitter`/`decoder`，因为 padding 记录的长度
字段包含自身 varint，vanilla 的帧解析会错位。现在 Xray 侧改成原生帧
（[PR #7402](https://github.com/XTLS/Xray-core/pull/7402)，breaking、默认生效），
padding 就是普通的 custom payload，vanilla 的管线保持原样：

- 不需要 mixin，不需要 accessor；
- 不需要 `FlowControlHandler` 处理、不需要手工恢复 autoRead；
- 跨版本升级时只依赖两个通道 id（可配置）和 Configuration 状态本身。

## 前置条件与副作用

- `online-mode=true`：XMC 客户端要走 vanilla 的加密请求和 session join；
- `network-compression-threshold=65536`（或 `-1`）：客户端已实现 `Set Compression`
  （[PR #7402](https://github.com/XTLS/Xray-core/pull/7402)）。`65536` 让 padding
  （≤32767）和数据通道载荷（≤24 KiB）保持未压缩，避免 zlib 把全零 padding 压到
  几十字节；`-1` 完全关闭压缩。低于 `65536` 时登录仍然可用，但 padding 的 wire
  形状会失效；
- `enforce-secure-profile=false`：Xray 客户端没有聊天签名密钥；
- 连接停在 Configuration 状态，不进入 Play：不创建玩家实体、不占 online 计数，
  Registry 同步任务会一直等待（vanilla 的 keep-alive 正常维持连接）。

## 目录职责

- `lib/`
  - `XmcPayloads`：两个载荷类型、动态通道 id、codec 和注册；
  - `XmcNetworking`：Fabric networking 的收发桥接与安装/卸载；
  - `XmcChannelHandler`：core 实现的回调接口。
- `config/`
  - `XmcConfig`、`XmcConfigLoader`：JSON 解析、默认值、通道名与范围校验；
  - `PrivateAddress`：私有/回环/链路本地/CGNAT/ULA 判断，配置时和连接时都校验。
- `core/`
  - `XmcMod`：Fabric 入口，初始化时读取配置并按通道名注册 networking、检查 vanilla
    前置条件；
  - `session/XmcRuntime`：会话表；
  - `session/XmcSession`、`TunnelConnector`：padding、连接与转发；
  - `padding/`：preset 数据（移植自 Xray-core，MPL-2.0）、turn 选择与分帧。
