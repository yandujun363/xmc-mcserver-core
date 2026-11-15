# xmc-mcserver-core

XMC（Minecraft finalmask）服务端的 Fabric 模组：让一个真实的 Minecraft 26.1
服务端在 **25565 同一端口**上同时服务普通玩家和 Xray XMC 客户端，并把 XMC 隧道
里的原始字节流转发给本机负责分流的 Xray（VLESS，TCP，无加密）。

> 协议版本：Minecraft **26.1 = 网络协议 775**。
>
> 模组**不参与认证，也不接管连接**：没有 mixin，不改登录分支，不拆 vanilla 的
> 管线。登录、加密、Yggdrasil / 离线认证、白名单与封禁全部由 vanilla 负责。
>
> Xray 侧需要 **原生帧 padding**（`"framePadding": true`）。该模式目前在本仓库
> 的本地分支里（基于草案 #7416 的 Yggdrasil 登录 + native padding），上游合并前
> 没有可用的客户端实现。

## 它做什么

```
Xray 客户端（XMC outbound，真实 Minecraft 登录 + 原生帧 padding）
    │  Handshake / Login Start / Encryption / Login Success —— 全是 vanilla 的
    ▼
Fabric 26.1 服务端（本模组）
    │  只注册两个 Configuration 状态通道：
    │    xmc:pad   启动 padding（普通自定义载荷，vanilla 直接丢弃）
    │    xmc:data  隧道字节流
    ▼
127.0.0.1:10086 等私有地址
    │  原始 TCP，把收到的字节原样写出
    ▼
本机 Xray（VLESS inbound，decryption=none）→ 分流 → 互联网
```

模组只做三件事：

1. 注册 `xmc:data` 通道；
2. 收到第一条 `xmc:data` 时创建会话，把捕获的 s2c 启动 padding 以
   `xmc:pad` 自定义载荷发出去；
3. 把 `xmc:data` 载荷拼成字节流，与配置的 Xray 端点做 TCP 双向转发。

它不是 XMC 的连接：不注册、不发任何东西，玩家走的是和没有模组时完全一样的
vanilla 路径。

## 为什么不需要"落地判定"

Xray 的 padding **就是合法的 Minecraft 帧**：每一轮都是 Configuration 状态的
custom payload 包（`xmc:pad`），长度字段按原生语义。vanilla 的 splitter 能正常
解析，`ServerCommonPacketListenerImpl#handleCustomPayload` 对未知 channel 是空
实现，所以服务端什么都不用做。

因此模组不需要在 Login Ack 处探测协议形状、不需要拆 Netty 管线，也不需要
"XMC / 普通玩家"分类：普通玩家看不到 `xmc:data`，XMC 客户端自己会发。

## 前置条件

| 项目 | 取值 | 原因 |
| --- | --- | --- |
| `online-mode` | `true` | XMC 客户端要走 vanilla 的加密请求和 session join；离线模式没有加密请求 |
| `network-compression-threshold` | `-1` | Xray 客户端读不了 `ClientboundLoginCompressionPacket` |

两者都是服务端设置，模组不会替你改；不满足时启动日志会打 warning。
`enforce-secure-profile=false` 也是实际测试需要的（Xray 客户端没有聊天签名密钥）。

## 安全边界

模组不实现白名单：**任何能登录进来的玩家**都可以发 `xmc:data` 打开隧道。
需要限制时请使用 vanilla 的 `white-list`、封禁和权限。模组不会创建玩家实体，
XMC 连接不进 Play，也不占 online 计数。

## 配置

`config/xmc-mcserver-core.json`：

```json
{
  "enabled": true,
  "maxPayloadBytes": 24576,
  "tunnel": {
    "host": "127.0.0.1",
    "port": 10086,
    "connectTimeoutMillis": 5000
  }
}
```

- `tunnel.host` 必须是私有/回环/链路本地/CGNAT/ULA 地址，加载时和连接时都会校验；
- `maxPayloadBytes` 是单个 `xmc:data` 载荷的上限（1024–32767）；
- 文件不存在时会写入一个 `enabled: false` 的示例。

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
./gradlew test        # 单元测试：padding 分帧、配置校验
```

开发者探针（需要一个正在运行的开发服务器）：

```bash
./gradlew runServer    # 离线模式即可
./gradlew probe        # 原生帧 padding + xmc:data 双向转发
./gradlew probeVanilla # 普通客户端（离线模式）必须能一路走到 Play 阶段
```

在线模式可以用 `probeOnline` 完整模拟一次原版登录（Yggdrasil 认证 + session join +
加密 + Configuration 握手），走的就是普通玩家的路径：

```bash
XMC_TEST_EMAIL=player@example.com XMC_TEST_PASSWORD=secret \
XMC_TEST_API=http://127.0.0.1:8080/api/yggdrasil \
  ./gradlew probeOnline
```

## 已知限制

- 只支持 26.1（协议 775）的预设 padding 数据；
- 客户端不发 `SelectKnownPacks`，所以 vanilla 的 Registry 同步任务会一直挂着，
  连接停在 Configuration 状态（keep-alive 由 vanilla 维持，客户端照常回）；
- 不支持 BungeeCord / Velocity 的登录转发。
