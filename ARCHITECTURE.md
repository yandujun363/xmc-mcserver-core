# 架构说明

## 边界

模组只做三件事：识别 XMC 落地、跑 padding、把 `xmc:data` 转发给本机 Xray。

登录、加密、Yggdrasil / 离线认证、白名单与封禁全部由 vanilla 负责；模组不参与
任何认证决策，配置里没有密码、玩家档案或密钥派生。

## 连接时序（协议 775）

```
C -> S  Handshake        id 0x00  protocol=775, address, port, nextState=2
C -> S  Login Start      id 0x00  username, uuid
S -> C  Encryption Req   id 0x01  vanilla 自己的 RSA 公钥
C -> S  Encryption Resp  id 0x01  encrypted(secret), encrypted(verifyToken)
        * AES/CFB8；在线模式下 vanilla 通过 session server 的 hasJoined 校验
S -> C  Login Success    id 0x02  uuid, username, properties
C -> S  Login Ack        id 0x03  (无字段)
        * ← 模组只在这里做一次判定
        * XMC：39 轮 padding，之后是 Configuration 阶段的自定义载荷隧道
C -> S  xmc:data         id 0x02  channel + payload
S -> C  xmc:data         id 0x01  channel + payload
S -> C  Keep Alive       id 0x04  long id
C -> S  Keep Alive       id 0x04  long id（回显）
```

普通玩家在 Login Ack 之后发送自己的第一个 Configuration 包（`ClientInformation`），
照常进入 Configuration → Play，模组不改变任何字节。

## 落地判定（不加标记）

`lib/net/LoginAckProbe` 挂在 vanilla 帧解码器之后（`splitter` 和 `flow` 之间）：

1. vanilla 解码 Login Ack 后，mixin 在 `handleLoginAcknowledgement` HEAD 取消
   vanilla 的处理，并装上探测器；
2. 探测器只做一件事：等帧解码器产出一个帧。
   - 普通客户端的第一个 Configuration 包是完整帧 → 立即交回 vanilla（调用原方法
     后转发缓冲字节），零延迟、零改动；
   - XMC 的 padding 记录把长度字段本身算进记录长度（见 Xray-core `padding.go`），
     所以永远比一个合法 MC 帧少 header 字节，帧解码器不可能产出帧；
3. 超时（`probeTimeoutMillis`）后：拆掉帧解码器、取出它缓冲的 padding 首段，
   交给接管逻辑；如果缓冲为空（对端在 Ack 之后什么都没发），仍然交回 vanilla。

判定完全由协议形状决定：不加字节、不看地址、不看用户名，普通玩家走的路径和没有
模组时一致。

## 接管

`lib/mc/MinecraftLoginSupport#takeOver`：

```
... decrypt -> splitter -> [xmc_probe] -> flow -> decoder -> prepender -> encoder -> packet_handler
```

- 在 `prepender` 前插入 `xmc_raw`（位于 encrypt 之后，写出的字节会经过加密）；
- 移除 `splitter`，以及 26.1 里可能是 `decoder` 或 `inbound_config` 的包解码器，
  还有 `FlowControlHandler` 和 `legacy_query`；
- 若服务端开启了压缩，摘掉 `compress` / `decompress` 并告警（XMC 客户端不接受
  压缩，见"前置条件"）；
- 把 `packetListener` 换成空实现，登录监听器不再 tick。

`RawConnection` 把解密后的字节暴露成阻塞流；`feed()` 用来把探测器已经取出的
padding 首段放到流的最前面。`XmcSession` 因此从 padding 的第一轮开始读。

## 会话与转发

`XmcSession` 在线程中：

1. 按 preset 执行 39 轮 padding（6 轮启动 + 33 轮进服；server 写 19 轮、读 20 轮，
   first turn 带 2 字节前缀 —— Login Ack 已被 vanilla 消费）；
2. 通过 `TunnelConnector` 连接 `tunnel.host:port`，连接前再次校验地址为私有地址；
3. 启动写线程读取 Xray 的 TCP 数据，按 `maxPayloadBytes` 切分打包成 `xmc:data`；
   socket 读超时（keepAlive 秒）时发送 keep-alive；
4. 当前线程读取客户端帧，`0x04` 忽略，`0x02` 且 channel 匹配时把载荷写入 Xray；
5. 任一端关闭时同步关闭 socket 和连接。

`RawConnection` 对上行数据做了 8 MiB 的缓冲上限。

## 前置条件（vanilla 侧，模组不改）

- `online-mode=true`：XMC 客户端要做 vanilla 的加密请求和 session join。离线模式下
  vanilla 不发加密请求，XMC 客户端无法登录；启动时会打 warning。
- `network-compression-threshold=-1`：Xray 客户端读不了
  `ClientboundLoginCompressionPacket`；启动时会打 warning。
- 白名单、封禁、人数限制按 vanilla 生效（登录阶段的 `canPlayerLogin`）。XMC 连接
  不会进入 Play，因此不创建玩家实体，也不占 online 计数。

## 目录职责

- `lib/`
  - `XmcLoginHooks`：lib 与 core 之间的静态注入点；
  - `mixin/`：`handleLoginAcknowledgement` 注入 + 登录监听器 accessor；
  - `net/LoginAckProbe`：落地判定；`RawConnection` / `RawFrameHandler`：接管后的裸流；
  - `mc/MinecraftLoginSupport`：管线接管与 handler 名常量；
  - `spi/XmcLoginHandler`：core 需要的三件事（enabled / probeTimeoutMillis / startSession）。
- `config/`
  - `XmcConfig`、`XmcConfigLoader`：JSON 解析、默认值与范围校验；
  - `PrivateAddress`：私有/回环/链路本地/CGNAT/ULA 判断，配置时和连接时都校验。
- `core/`
  - `XmcMod`：Fabric 入口，加载配置、安装 handler、检查 vanilla 前置条件；
  - `session/XmcRuntime`：会话表；
  - `session/XmcSession`、`TunnelConnector`：padding、隧道与转发；
  - `protocol/`：VarInt、帧；
  - `padding/`：preset 数据（由 Go 数据生成）、turn 读写。
