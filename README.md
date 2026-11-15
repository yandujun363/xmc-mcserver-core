# xmc-mcserver-core

XMC（Minecraft finalmask）服务端的 Fabric 模组：让一个真实的 Minecraft 26.1
服务端在 **25565 同一端口**上同时服务普通玩家和 Xray XMC 客户端，并把 XMC 隧道
里的原始字节流转发给本机负责分流的 Xray（VLESS，TCP，无加密）。

> 协议版本：Minecraft **26.1 = 网络协议 775**。
>
> 模组**不参与认证**：登录、加密、Yggdrasil / 离线认证、白名单与封禁全部由
> vanilla 负责。配置里没有密码、玩家档案、材质或密钥派生。
>
> Xray 侧需要 **XMC 客户端 Yggdrasil 模式**（#7394 / `feat/xmc-yggdrasil-login`，
> 尚未合并）。在那之前模组没有可用的上游客户端实现。

## 它做什么

```
Xray 客户端（XMC outbound，走真实 Minecraft 登录）
    │  Handshake / Login Start / Encryption / Login Success —— 全是 vanilla 的
    ▼
Fabric 26.1 服务端（本模组）
    │  Login Ack 之后判断：对面发的是 Configuration 包还是 XMC padding
    │  XMC 才接管：padding 伪装 + Configuration 阶段的 xmc:data 通道
    ▼
127.0.0.1:10086 等私有地址
    │  原始 TCP，把收到的字节原样写出
    ▼
本机 Xray（VLESS inbound，decryption=none）→ 分流 → 互联网
```

模组只做三件事：

1. 在 **Login Acknowledged 之后**判断这条连接是不是 XMC（见下）；
2. 是 XMC：接管连接、跑 39 轮启动/进服 padding；
3. 把 `xmc:data` 载荷拼成字节流，与配置的 Xray 端点做 TCP 双向转发。

它不是 XMC：立刻把连接原样交回 vanilla，普通玩家的登录、配置和进服流程与没有
模组时完全一致。

## 为什么不需要标记

普通客户端的第一个 Configuration 包是完整帧，而 XMC 的 padding 记录把长度字段
本身算进记录长度（Xray-core `padding.go`），永远比合法 MC 帧少 header 字节。
所以 vanilla 的帧解码器要么立即产出一个帧（普通玩家 → 交回 vanilla），要么在
`probeTimeoutMillis` 内什么都产不出来（XMC → 接管）。判定完全由协议形状决定：
不加字节、不看地址、不看用户名。

## 前置条件

| 项目 | 取值 | 原因 |
| --- | --- | --- |
| `online-mode` | `true` | XMC 客户端要走 vanilla 的加密请求和 session join；离线模式没有加密请求 |
| `network-compression-threshold` | `-1` | Xray 客户端读不了 `ClientboundLoginCompressionPacket` |

两者都是服务端设置，模组不会替你改；不满足时启动日志会打 warning。

## 目录结构

```
lib/      与 Minecraft / Netty 交互：Login Ack 探测、连接接管、裸字节流
config/   配置模型、JSON 加载与校验、私有地址限制
core/     padding preset、xmc:data 包流、隧道转发会话
src/      fabric.mod.json、mixin 配置、单元测试
```

依赖方向：`core -> lib`、`core -> config`。`lib` 只在启动时由 `core` 通过
`XmcLoginHooks` 注入实现，反向不依赖 `core`。

## 构建

需要 JDK 25+（Gradle 9.8；本机 JDK 27 已验证）：

```bash
./gradlew build
# 产物：build/libs/xmc-mcserver-core-<version>.jar
```

单元测试覆盖 VarInt/帧、padding 读写和配置校验：

```bash
./gradlew test
```

## 配置

首次启动会在 `config/xmc-mcserver-core.json` 写入一份 `enabled: false` 的示例；
确认参数后再改成 `true`。完整字段：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否启用 XMC 落地识别与隧道 |
| `channel` | `xmc:data` | 隧道载荷 channel（Xray 当前写死 `xmc:data`） |
| `keepAliveSeconds` | `15` | 服务端发送 Configuration keep-alive 的间隔 |
| `maxPayloadBytes` | `24576` | 单个 `xmc:data` 载荷上限，需 ≤ Xray 的 24 KiB |
| `handshakeTimeoutSeconds` | `120` | padding 阶段超时 |
| `probeTimeoutMillis` | `800` | Login Ack 之后等第一个 Configuration 帧的时间；超时视为 XMC |
| `tunnel.host` | `127.0.0.1` | 本机 Xray 地址，**只允许私有/回环/链路本地/CGNAT 地址** |
| `tunnel.port` | — | 本机 Xray VLESS inbound 端口 |
| `tunnel.connectTimeoutMillis` | `5000` | TCP 连接超时 |

示例见仓库根目录的 `config.example.json`。

Xray 侧需要一个 VLESS inbound（TCP、无 TLS），只监听私有地址：

```json
{
  "inbounds": [{
    "listen": "127.0.0.1",
    "port": 10086,
    "protocol": "vless",
    "settings": {
      "clients": [{ "id": "<uuid>", "flow": "" }],
      "decryption": "none"
    },
    "streamSettings": { "network": "tcp", "security": "none" }
  }]
}
```

Xray 客户端配置 XMC outbound 时用 Yggdrasil 模式（`yggdrasil` 段），不带
`password` / `profiles`：玩家身份由 vanilla 的 session server 验证。

## 兼容性与限制

- **仅 26.1 / 协议 775**。
- 模组只在 Login Ack 之后介入；之前的 hello / 密钥交换 / hasJoined / Login
  Success 全部是 vanilla 的，模组不改密钥、不碰 verify token。
- XMC 连接停在 Configuration 阶段（Xray 客户端不会回 `FinishConfigurationAck`），
  不创建玩家实体，也不占 online 计数。
- 不支持预共享密码模式（上游已标记为 legacy）；不支持 BungeeCord / Velocity 的
  登录转发。
- padding 目前不是合法的 MC 帧（长度字段含自身），所以必须在 Login Ack 之后
  接管帧解码器；如果上游把 padding 改成合法 MC 包，这层接管可以进一步删掉。
- 私有的 `tunnel` 地址限制用于避免模组被当成公网 TCP 出口。

## 开发者集成测试

仓库里带一个只用于开发调试的客户端探针（`src/test/java/xmc/probe/XmcProbe.java`），
开发服务器在 `run/` 下（`online-mode=false`、`network-compression-threshold=-1`，
探针不需要认证）：

```bash
# 1. 启动开发服务器
./gradlew runServer
# 2. 另一个终端跑探针
./gradlew probe          # XMC：Login Ack + padding + xmc:data 双向转发
./gradlew probeVanilla   # 普通客户端：必须走到 Play 阶段
```

`probe` 会在 127.0.0.1:10086 起一个占位 TCP 服务，模拟“本机 Xray”，验证两个方向
的字节转发。

## 与 Xray 上游的关系

- padding 数据与协议帧来自 Xray-core `transport/internet/finalmask/xmc`；
  `padding_preset.go` 改动时请同步 `core/java/xmc/core/padding/PaddingPreset26.java`。
- 客户端侧依赖 #7394 / `feat/xmc-yggdrasil-login` 的 Yggdrasil 登录。
- 等上游把 padding 改成合法 MC 帧后，可以去掉 Login Ack 处的接管，只保留
  `xmc:data` 通道。
