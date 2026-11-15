# 架构说明

## 连接时序（协议 775）

客户端与 Xray 的 XMC 实现保持一致：

```
C -> S  Handshake        id 0x00  protocol=775, address, port, nextState=2
C -> S  Login Start      id 0x00  username, uuid
S -> C  Encryption Req   id 0x01  serverId="", publicKey(派生), verifyToken(4B), shouldAuthenticate=true
C -> S  Encryption Resp  id 0x01  encrypted(secret), encrypted(verifyToken || password)
        * 双方开启 AES/CFB8
S -> C  Login Success    id 0x02  uuid, username, properties(textures)
C -> S  Login Ack        id 0x03  (无字段)
        * 服务端开始 padding：默认 preset 6 轮启动 + 33 轮进服
        * 之后进入 Configuration 阶段的自定义载荷隧道：
C -> S  xmc:data         id 0x02  channel + payload
S -> C  xmc:data         id 0x01  channel + payload
S -> C  Keep Alive       id 0x04  long id
C -> S  Keep Alive       id 0x04  long id（回显）
```

padding 的帧长度、chunk 边界和时序来自 Xray-core 的
`padding_preset.go`，通过 `xmc.core.padding.PaddingPreset26` 逐字节移植。
服务端在 20 组抓包 profile 中随机选择一组来设置每个 server→client turn 的
发送长度范围；客户端按 min/max 校验，因此两边可以互通。

## 登录接管

`lib` 中的 Mixin 只做三件事：

1. `MinecraftServer#getKeyPair` 返回由密码派生的 RSA 密钥对。这样 vanilla
   客户端和 XMC 客户端都会用同一把服务端公钥做密钥协商，XMC 客户端对公钥的
   固定校验才能通过。
2. `ServerLoginPacketListenerImpl#handleHello` 中的
   `usesAuthentication()` 被重定向为 `true`（XMC 启用时）。offline-mode 服务端
   也会发送加密请求；普通客户端照常响应。
3. `handleKey` 在 vanilla 处理前解密 verify token：
   - 只有 4 字节 challenge：普通客户端。online-mode 交回 vanilla；offline-mode
     由模组完成离线登录（`startClientVerification`）。
   - challenge + 密码：XMC。校验密码和档案后开启 AES、发送 Login Success，
     把连接从登录包管线切换为原始帧管线，并交给 `core` 的会话线程。
   - 其他：交回 vanilla 报错。

接管点选在 `handleKey`：此时客户端还没收到 Login Success，不会在同一个 TCP
段里提前发送 Login Ack/padding，避免了登录解码器把 padding 当成登录包解码。

## 管线切换

`MinecraftLoginSupport#takeOver`：

```
... decrypt -> splitter -> flow -> decoder -> encrypt -> [xmc_raw] -> prepender -> encoder -> packet_handler
```

- 在 `prepender` 前插入 `xmc_raw`（位于 `encrypt` 之后，写出的字节会经过加密）；
- 移除 `decoder`：`ByteToMessageDecoder` 的残留会交给紧随其后的 `xmc_raw`；
- 移除 `legacy_query`，避免 padding 首字节恰好是 0xFE 时被旧版 ping 逻辑截获；
- 把 `packetListener` 换成空实现，登录监听器的 600 tick slow-login 不再触发。

`splitter` 保留，因此 `xmc_raw` 收到的是**去掉 VarInt 长度的帧体**。
`RawConnection` 的 `InputStream` 重新拼回 `VarInt(length) || body`，就得到
和 Xray 完全一致的无格式字节流；`OutputStream` 通过 `xmc_raw` 的 context 写出，
只经过 `encrypt`，不会经过 packet encoder/prepender。

## 会话与转发

`XmcSession` 在线程中：

1. 读 Login Ack（帧体必须恰好是 `{0x03}`，wire length 必须为 2）；
2. 按 preset 执行 39 轮 padding（6 轮启动 + 33 轮进服；server 写 19 轮、读 20 轮，first turn 带 2 字节前缀）；
3. 通过 `TunnelConnector` 连接 `tunnel.host:port`，连接前再次校验地址为私有地址；
4. 启动写线程读取 Xray 的 TCP 数据，按 `maxPayloadBytes` 切分打包成
   `xmc:data`；socket 读超时（keepAlive 秒）时发送 keep-alive；
5. 当前线程读取客户端帧，`0x04` 忽略，`0x02` 且 channel 匹配时把载荷写入 Xray；
6. 任一端关闭时同步关闭 socket 和连接。

`RawConnection` 对上行帧做了 8 MiB 的缓冲上限，超过即断开，避免慢客户端
撑爆内存。

## 目录职责

- `lib/`
  - `XmcLoginHooks`：lib 与 core 之间的静态注入点；
  - `mixin/`：Minecraft 登录/密钥包/连接字段的 Mixin 与 accessor；
  - `net/`：`RawConnection`、`RawFrameHandler`、空 PacketListener；
  - `mc/MinecraftLoginSupport`：开启加密、发送登录成功/断开、接管管线；
  - `model/McProfile`、`spi/*`：跨模块的档案与决策类型。
- `config/`
  - `XmcConfig`、`XmcConfigLoader`：JSON 解析、默认值、范围与档案校验；
  - `PrivateAddress`：私有/回环/链路本地/CGNAT/ULA 判断，配置时和连接时都校验。
- `core/`
  - `XmcMod`：Fabric 入口，服务器启动时加载配置并安装 handler；
  - `session/XmcRuntime`：派生密钥、校验密码/档案、创建会话；
  - `session/XmcSession`、`TunnelConnector`：padding、隧道与转发；
  - `protocol/`：VarInt、帧、RSA 派生；
  - `padding/`：preset 数据（由 Go 数据生成）、turn 读写。
