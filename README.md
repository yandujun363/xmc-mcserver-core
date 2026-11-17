# xmc-mcserver-core

XMC（Minecraft finalmask）服务端的 Fabric 模组：让一个真实的 Minecraft 26.1
服务端在 **25565 同一端口**上同时服务普通玩家和 Xray XMC 客户端，并把 XMC 隧道
里的原始字节流转发给本机负责分流的 Xray（VLESS，TCP，无加密）。

> 协议版本：Minecraft **26.1 = 网络协议 775**。
>
> 多版本分支（每个 MC 版本一个分支，独立构建）见 [VERSIONS.md](VERSIONS.md)。
>
> 模组**不参与认证，也不接管连接**：没有 mixin，不改登录分支，不拆 vanilla 的
> 管线。登录、加密、Yggdrasil / 离线认证、白名单与封禁全部由 vanilla 负责。
>
> Xray 侧需要 **原生帧 padding**。该格式在 [PR #7402](https://github.com/XTLS/Xray-core/pull/7402)
> （`XMC: native frame padding and delay range`）里，按 maintainer 要求做成 breaking
> change，默认生效、没有配置开关。同一个 PR 还补上了 `Set Compression` 支持
> （[#7394 的说明](https://github.com/XTLS/Xray-core/issues/7394#issuecomment-6895583085)），
> 客户端可以在开启压缩的服务端上完成登录。Yggdrasil 登录仍在等待草案
> [PR #7416](https://github.com/XTLS/Xray-core/pull/7416)，上游合并前没有完整的
> 客户端实现。

> **强烈建议自行构建，并改掉模组 id 与通道名。** 默认的 `xmc:data` / `xmc:pad`
> 是公开常量，固定通道名在链路上就是一个可识别的指纹。正式部署前请按
> [自建与改 id](#自建与改-id) 修改后自行编译（`./gradlew build`），不要直接使用
> 发行 jar。

## 它做什么

```
Xray 客户端（XMC outbound，真实 Minecraft 登录 + 原生帧 padding）
    │  Handshake / Login Start / Encryption / Login Success —— 全是 vanilla 的
    ▼
Fabric 26.1 服务端（本模组）
    │  只注册两个 Configuration 状态通道（通道名可配置，默认）：
    │    xmc:pad   启动 padding（普通自定义载荷，vanilla 直接丢弃）
    │    xmc:data  隧道字节流
    ▼
127.0.0.1:10086 等私有地址
    │  原始 TCP，把收到的字节原样写出
    ▼
本机 Xray（VLESS inbound，decryption=none）→ 分流 → 互联网
```

模组只做三件事：

1. 按配置注册数据通道（默认 `xmc:data`）和 padding 通道（默认 `xmc:pad`）；
2. 收到第一条数据通道载荷时创建会话，把捕获的 s2c 启动 padding 以 padding
   通道的自定义载荷发出去；
3. 把数据通道载荷拼成字节流，与配置的 Xray 端点做 TCP 双向转发。

它不是 XMC 的连接：普通玩家不注册、不发任何东西，走的是和没有模组时完全一样的
vanilla 路径。

## 为什么不需要"落地判定"

Xray 的 padding **就是合法的 Minecraft 帧**：每一轮都是 Configuration 状态的
custom payload 包（默认 `xmc:pad`），长度字段按原生语义。vanilla 的 splitter 能
正常解析，`ServerCommonPacketListenerImpl#handleCustomPayload` 对未知 channel 是
空实现，所以服务端什么都不用做。

因此模组不需要在 Login Ack 处探测协议形状、不需要拆 Netty 管线，也不需要
"XMC / 普通玩家"分类：普通玩家看不到数据通道，XMC 客户端自己会发。

## 前置条件

| 项目 | 取值 | 原因 |
| --- | --- | --- |
| `online-mode` | `true` | XMC 客户端要走 vanilla 的加密请求和 session join；离线模式没有加密请求 |
| `network-compression-threshold` | `65536`（或 `-1`） | `65536` 让 padding（≤32767）和数据通道载荷（≤24 KiB）保持未压缩，大包照常压缩；`-1` 完全关闭压缩。需要含 `Set Compression` 支持的 Xray：[PR #7402](https://github.com/XTLS/Xray-core/pull/7402) |

两者都是服务端设置，模组不会替你改；`online-mode` 不满足时启动日志会打 warning，
`network-compression-threshold` 低于 `65536` 时会提示 padding 会被压小。
`enforce-secure-profile=false` 也是实际测试需要的（Xray 客户端没有聊天签名密钥）。

## 安全边界

模组不实现白名单：**任何能登录进来的玩家**都可以向数据通道发载荷打开隧道。
需要限制时请使用 vanilla 的 `white-list`、封禁和权限。模组不会创建玩家实体，
XMC 连接不进 Play，也不占 online 计数。

默认通道名是公开的固定字符串，扫描方可以据此识别这条连接；见
[通道名与指纹](#通道名与指纹) 和 [自建与改 id](#自建与改-id)。

## 配置

`config/xmc-mcserver-core.json`：

```json
{
  "enabled": true,
  "maxPayloadBytes": 24576,
  "channels": {
    "data": "xmc:data",
    "padding": "xmc:pad"
  },
  "tunnel": {
    "host": "127.0.0.1",
    "port": 10086,
    "connectTimeoutMillis": 5000
  }
}
```

- `channels.data` / `channels.padding` 是两端共用的通道名，规则见下一节；
  通道在 mod 初始化时注册，**改完必须重启服务端**；
- `tunnel.host` 必须是私有/回环/链路本地/CGNAT/ULA 地址，加载时和连接时都会校验；
- `maxPayloadBytes` 是单个数据通道载荷的上限（1024–32767）；
- 文件不存在时会写入一个 `enabled: false` 的示例。

## 通道名与指纹

两个通道默认是 `xmc:data`（隧道字节流）和 `xmc:pad`（启动 padding），可以在
`channels` 里整体替换：

```json
"channels": {
  "data": "myxmc:stream",
  "padding": "myxmc:noise"
}
```

加载时会校验，不合法直接拒绝配置，不会带病启动：

- 必须是 `namespace:path`，且只有一个 `:`；
- namespace 只允许 `a-z`、`0-9`、`_`、`.`、`-`，不能是 `..`，也不能是保留的
  `minecraft`；
- path 只允许 `a-z`、`0-9`、`_`、`-`、`.`、`/`；
- 整条 id 不超过 128 字符；
- `data` 和 `padding` 不能是同一个 id；
- 两端（Xray XMC outbound 与模组）必须填同一对值。

为什么要校验：这两个字符串最终会传给 `Identifier.fromNamespaceAndPath`，再进入
Fabric 的 `PayloadTypeRegistry`。字符不合法会抛 `IdentifierException`，和 vanilla
或其他模组撞名会抛 `IllegalArgumentException`，两者都发生在 mod 初始化阶段——
也就是服务端直接起不来。模组在注册前先做上面的检查；注册仍然失败时只把 XMC
自身置为不可用并打日志，vanilla 照常启动。

> Xray 侧目前把 `xmc:data` / `xmc:pad` 写在代码里（`packet_stream.go`、
> `padding.go`），可配置通道的 PR/草案还在处理中。在 Xray 端支持之前，只改模组
> 这一侧会连不上。

## 自建与改 id

公开的默认值本身就是指纹：DPI / 服务端扫描只要认 `xmc:data` 就能把这条连接挑
出来。请不要把发行 jar 直接用于生产，至少做下面几步再构建：

1. `src/main/resources/fabric.mod.json`：把 `"id": "xmc-mcserver-core"` 改成你自己的 id；
2. `build.gradle`：`loom { mods { "xmc-mcserver-core" { ... } } }` 里的名字同步改掉
   （必须与 mod id 一致）；
3. `core/java/xmc/core/XmcMod.java`：`MOD_ID` 改成同一个 id（只影响日志器名字）；
4. `config/java/xmc/config/XmcConfigLoader.java`：`FILE_NAME` 可以一起改名，避免
   配置文件名也变成指纹；
5. 部署时把 `channels.data` / `channels.padding` 换成自己的通道名，并让 Xray 侧
   一致；
6. （可选）`settings.gradle` 的 `rootProject.name`、`gradle.properties` 的 `group`
   一起改。

改完重新构建，产物在 `build/libs/`。

## 目录结构

```
lib/      与 Minecraft / Fabric 交互：通道注册、载荷类型、收发桥接
config/   配置模型、JSON 加载与校验、私有地址限制
core/     padding preset、会话与隧道转发
src/      fabric.mod.json、单元测试、开发者探针
```

依赖方向：`core -> lib`、`core -> config`。

## 构建与测试

```bash
./gradlew build
# 产物：build/libs/xmc-mcserver-core-0.1.0.jar
./gradlew test        # 单元测试：padding 分帧、配置与通道名校验
```

开发者探针（需要一个正在运行的开发服务器）：

```bash
./gradlew runServer    # 离线模式即可
./gradlew probe        # 原生帧 padding + 数据通道双向转发
./gradlew probeVanilla # 普通客户端（离线模式）必须能一路走到 Play 阶段
```

探针的通道名默认也是 `xmc:data` / `xmc:pad`；服务端配置改过通道名时，用环境变量
把探针对齐（Gradle 的 `JavaExec` 会继承环境变量）：

```bash
XMC_PROBE_DATA_CHANNEL=myxmc:stream XMC_PROBE_PADDING_CHANNEL=myxmc:noise ./gradlew probe
```

在线模式可以用 `probeOnline` 完整模拟一次原版登录（Yggdrasil 认证 + session join +
加密 + Configuration 握手），走的就是普通玩家的路径：

```bash
XMC_TEST_EMAIL=player@example.com XMC_TEST_PASSWORD=secret \
XMC_TEST_API=http://127.0.0.1:8080/api/yggdrasil \
  ./gradlew probeOnline
```

> 探针本身没有实现压缩：开发服务器请保持 `network-compression-threshold=-1`，
> 否则探针会在 Login 阶段拒绝 `Set Compression`。上面的 `65536` 推荐值针对玩家部署。

## 已知限制

- 只支持 26.1（协议 775）的预设 padding 数据；
- 通道名在启动时注册，修改后必须重启服务端；
- 客户端不发 `SelectKnownPacks`，所以 vanilla 的 Registry 同步任务会一直挂着，
  连接停在 Configuration 状态（keep-alive 由 vanilla 维持，客户端照常回）；
- 不支持 BungeeCord / Velocity 的登录转发。
