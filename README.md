# xmc-mcserver-core

XMC（Minecraft finalmask）服务端的 Fabric 模组实现：让一个真实的 Minecraft
26.1 服务端在 **25565 同一端口**上同时服务普通玩家和 Xray XMC 客户端，并把
XMC 隧道里的原始字节流转发给本机负责分流的 Xray（VLESS，TCP，无加密）。

> 协议版本：Minecraft **26.1 = 网络协议 775**（`minecraft-data` 与 Xray-core
> 的 XMC 实现都硬编码 775）。755 是 1.17，和 26.1 不是一回事。
>
> 兼容目标：Xray-core 当前主线（#6769 合并后）的 XMC 预共享密码模式。
> #7394 的 Yggdrasil 登录尚未合并，等它合入后再做多版本/新登录方式。

## 它做什么

```
Xray 客户端 (XMC outbound)
    │  Minecraft 登录 + 配置阶段隧道（协议 775）
    ▼
Fabric 26.1 服务端（本模组）
    │  登录阶段识别 XMC，Configuration 前接管连接
    │  padding 伪装 + xmc:data 自定义载荷
    ▼
127.0.0.1:10086 等私有地址
    │  原始 TCP，把收到的字节原样写出
    ▼
本机 Xray（VLESS inbound，decryption=none）→ 分流 → 互联网
```

模组本身**不解析 VLESS**，只做三件事：

1. 在登录阶段识别 XMC 客户端（verify token 尾部带预共享密码）；
2. 完成 XMC 的登录握手、Login Acknowledged 和 40 轮启动/进服 padding；
3. 把 `xmc:data` 载荷拼成字节流，与配置的 Xray 端点做 TCP 双向转发。

普通 Minecraft 玩家的登录流程保持不变（online-mode 和 offline-mode 都兼容）。

## 目录结构

```
lib/      与 Minecraft / Netty 内部交互：Mixin、accessor、连接接管、登录成功包
config/   配置模型、JSON 加载与校验、私有地址限制
core/     XMC 协议：VarInt/帧、RSA 派生、padding、xmc:data 包流、转发会话
src/      fabric.mod.json、mixin 配置、单元测试
```

依赖方向：`core -> lib`、`core -> config`，`config -> lib`（共享 `McProfile`）。
`lib` 只在启动时由 `core` 通过 `XmcLoginHooks` 注入实现，反向不依赖 `core`。

## 构建

需要 JDK 25+（Gradle 使用 9.8；本机 JDK 27 已验证）：

```bash
./gradlew build
# 产物：build/libs/xmc-mcserver-core-<version>.jar
```

单元测试包含与 Go 版 `xmc.DeriveRSAKey` 的逐字节对比、VarInt/帧、
padding 读写和配置校验：

```bash
./gradlew test
```

## 配置

首次启动会在 `config/xmc-mcserver-core.json` 写入一份 `enabled: false` 的示例；
确认参数后再改成 `true`。完整字段：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否启用 XMC 接管 |
| `password` | — | 与 Xray XMC 客户端一致的预共享密码，同时用于派生 1024-bit RSA 密钥 |
| `channel` | `xmc:data` | 隧道使用的自定义载荷 channel（Xray 当前写死 `xmc:data`，后续 PR 可控） |
| `keepAliveSeconds` | `15` | 服务端发送 Configuration keep-alive 的间隔 |
| `maxPayloadBytes` | `24576` | 单个 `xmc:data` 载荷上限，需 ≤ Xray 的 24 KiB |
| `handshakeTimeoutSeconds` | `120` | 握手阶段超时（当前由连接读超时兜底） |
| `profiles` | — | 允许登录的档案，必须和 Xray 客户端配置**完全一致**（用户名、UUID、材质值、材质签名） |
| `tunnel.host` | `127.0.0.1` | 本机 Xray 的地址，**只允许私有/回环/链路本地/CGNAT 地址** |
| `tunnel.port` | — | 本机 Xray VLESS inbound 端口 |
| `tunnel.connectTimeoutMillis` | `5000` | TCP 连接超时 |

示例见仓库根目录的 `config.example.json`。注意：示例里的内网地址/密码/材质
都是占位符，公开仓库不要提交真实值。

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

Xray 客户端的 XMC outbound 需要与模组配置一致：`password`、相同的
`profiles`（用户名/UUID/材质值/材质签名），`hostname` 填真实连接地址即可。

## 兼容性与限制

- **仅 26.1 / 协议 775**。Xray 的 XMC 客户端当前也写死 775。
- 只实现了**当前主线**的预共享密码认证；#7394 的 Yggdrasil 登录合并后再补。
- 走的是 XMC 的 Minecraft 登录 + Configuration 阶段；服务端不会进入真实
  Configuration/Play 阶段，XMC 连接不会创建玩家实体。
- **不支持 BungeeCord / Velocity 的登录转发**：模组会强制发起加密请求，
  代理转发场景需要另做兼容。
- 私有的 `tunnel` 地址限制用于避免模组被当成公网 TCP 出口；如果 Xray 和
  MC 服务端不在同一台机器，请用内网地址并确保防火墙只放行必要流量。
- XMC 连接使用 1024-bit RSA + AES/CFB8，与 Xray 实现一致；密码和档案列表
  是唯一凭据，请使用足够随机的密码。

## 开发者集成测试

仓库里带一个只用于开发调试的客户端探针
（`src/test/java/xmc/probe/XmcProbe.java`），它直接实现 XMC 客户端协议：

```bash
# 1. 在 run/config/xmc-mcserver-core.json 启用 XMC（可用 run/ 下的开发服务器）
# 2. 启动开发服务器
./gradlew runServer
# 3. 另一个终端跑探针
./gradlew probe          # 完整 XMC 登录 + padding + xmc:data 双向转发
./gradlew probeVanilla   # 普通离线模式客户端，验证强制加密后仍能进入 Configuration
```

`probe` 会在 127.0.0.1:10086 起一个占位 TCP 服务，模拟“本机 Xray”，用来验证
两个方向的字节转发。探针不实现压缩，开发服务器建议设置
`network-compression-threshold=-1`。

## 与 Xray 上游的关系

- 协议、padding 数据、RSA 派生均来自 Xray-core
  `transport/internet/finalmask/xmc`（#6769 合并后的主线）。
- padding 数据由 `padding_preset.go` 生成，改动时请同步
  `core/java/xmc/core/padding/PaddingPreset26.java`。
- 等 #7394 的 Yggdrasil 方案合并后，再评估新增登录模式和协议版本适配。
