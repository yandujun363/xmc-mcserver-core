# xmc-mcserver-core

XMC（Minecraft finalmask）服务端的 Fabric 模组：让一个真实的 Minecraft 1.21.8
服务端在 **25565 同一端口**上同时服务普通玩家和 Xray XMC 客户端。

模组只注册两个 Configuration 状态通道（通道名可配置，默认 `xmc:data` / `xmc:pad`），
并把数据通道上收到的字节与配置里的本地 TCP 端点做双向转发。它**没有 mixin**，
不接管连接：登录、加密、Yggdrasil / 离线认证、白名单与封禁全部由 vanilla 负责。

> 协议版本：Minecraft **1.21.8 = 网络协议 772**。
>
> 多版本分支（每个 MC 版本一个分支，独立构建）见 [VERSIONS.md](VERSIONS.md)。

## 前置条件

| 项目 | 取值 | 原因 |
| --- | --- | --- |
| `online-mode` | `true` | XMC 客户端要走 vanilla 的加密请求和 session join；离线模式没有加密请求 |
| `network-compression-threshold` | `65536`（或 `-1`） | `65536` 让 padding（≤32767）和数据通道载荷（≤24 KiB）保持未压缩，大包照常压缩；`-1` 完全关闭压缩 |
| `enforce-secure-profile` | `false` | Xray 客户端没有聊天签名密钥 |

这三项都是 `server.properties` 里的设置，模组不会替你改。`online-mode` 不满足时
启动日志会打 warning；`network-compression-threshold` 低于 `65536` 时会提示
padding 会被压小。

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

- `enabled`：总开关，`false` 时不注册任何通道；
- `channels.data` / `channels.padding`：两端共用的通道名，规则见下一节；通道在
  mod 初始化时注册，**改完必须重启服务端**；
- `tunnel.host`：本地 TCP 端点的地址，必须是私有 / 回环 / 链路本地 / CGNAT / ULA
  地址，加载时和连接时都会校验；
- `tunnel.port`：本地 TCP 端点的端口（1–65535）；
- `tunnel.connectTimeoutMillis`：连接超时（100–60000）；
- `maxPayloadBytes`：单个数据通道载荷的上限（1024–32767）。

配置文件不存在时会先写入一个 `enabled: false` 的示例，避免装完模组就意外开放。

## 通道名

两个通道默认是 `xmc:data` 和 `xmc:pad`，可以在 `channels` 里整体替换：

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

校验的原因是这两个字符串最终会传给 `Identifier.fromNamespaceAndPath`，再进入
Fabric 的 `PayloadTypeRegistry`：字符不合法会抛 `IdentifierException`，和 vanilla
或其他模组撞名会抛 `IllegalArgumentException`，两者都发生在 mod 初始化阶段，
也就是服务端直接起不来。模组在注册前先做上面的检查；注册仍然失败时只把 XMC
自身置为不可用并打日志，vanilla 照常启动。

## 自行构建与改 id

默认的 `xmc:data` / `xmc:pad` 是公开常量，固定通道名在链路上就是一个可识别的
特征。正式部署前建议改掉模组 id 和通道名后自行编译（`./gradlew build`）：

1. `src/main/resources/fabric.mod.json`：把 `"id": "xmc-mcserver-core"` 改成你自己的 id；
2. `build.gradle`：`loom { mods { "xmc-mcserver-core" { ... } } }` 里的名字同步改掉
   （必须与 mod id 一致）；
3. `core/java/xmc/core/XmcMod.java`：`MOD_ID` 改成同一个 id（只影响日志器名字）；
4. `config/java/xmc/config/XmcConfigLoader.java`：`FILE_NAME` 可以一起改名，避免
   配置文件名也变成特征；
5. 部署时把 `channels.data` / `channels.padding` 换成自己的通道名，并让 Xray 侧
   一致；
6. （可选）`settings.gradle` 的 `rootProject.name`、`gradle.properties` 的 `group`
   一起改。

改完重新构建，产物在 `build/libs/`。

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

## 支持范围

- **加载器：只有 Fabric。** 服务端侧仅提供 Fabric 实现。
- **Minecraft：26.x 与 1.21.x。** 每个版本一个独立分支，见 [VERSIONS.md](VERSIONS.md)。
  1.20.x 不做：1.20.1 没有 Configuration 状态，1.20.2–1.20.4 的 Configuration 自定义
  载荷包 ID 与之后不同。
- **NeoForge：不做。**

## 已知限制

- 只支持 1.21.8（协议 772）的预设 padding 数据；
- 通道名在启动时注册，修改后必须重启服务端；
- 客户端不发 `SelectKnownPacks`，所以 vanilla 的 Registry 同步任务会一直挂着，
  连接停在 Configuration 状态（keep-alive 由 vanilla 维持，客户端照常回）；
- 不支持 BungeeCord / Velocity 的登录转发。

## 许可证

**MPL-2.0**，与 Xray-core 一致。padding 预设与选择/分帧逻辑移植自 Xray-core 的
`transport/internet/finalmask/xmc`，该部分继续按 MPL-2.0 分发；仓库其余部分由版权
持有人同时提供 MIT 许可。详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
