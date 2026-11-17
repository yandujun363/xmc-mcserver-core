# 版本分支

本仓库按 **每个 Minecraft 版本一个分支** 维护：分支名为 `<加载器>/<MC 版本>`，例如
`fabric/1.21.8`。每个分支都是完整、可独立 `./gradlew build` 的工程，不使用
Stonecutter 之类的前处理器，也不用把多版本塞进同一套源码。

`master` 是当前主线（26.1），其余版本各自成分支。

## 分支一览

| 分支 | MC | 协议 | Fabric API | Fabric Loader | Loom | Java |
| --- | --- | --- | --- | --- | --- | --- |
| `master` | 26.1 | 775 | 0.145.1+26.1 | 0.20.1 | 1.19.4 | 25 |
| `fabric/26.3` | 26.3 | 777 | 0.166.2+26.3 | 0.20.1 | 1.19.4 | 25 |
| `fabric/26.2` | 26.2 | 776 | 0.161.0+26.2 | 0.20.1 | 1.19.4 | 25 |
| `fabric/26.1` | 26.1 | 775 | 0.145.1+26.1 | 0.20.1 | 1.19.4 | 25 |
| `fabric/1.21.11` | 1.21.11 | 774 | 0.141.6+1.21.11 | 0.18.4 | 1.13.6 | 21 |
| `fabric/1.21.8` | 1.21.8 | 772 | 0.136.1+1.21.8 | 0.17.3 | 1.13.6 | 21 |
| `fabric/1.21.5` | 1.21.5 | 770 | 0.128.2+1.21.5 | 0.16.14 | 1.13.6 | 21 |
| `fabric/1.21.4` | 1.21.4 | 769 | 0.119.4+1.21.4 | 0.16.14 | 1.13.6 | 21 |
| `fabric/1.21.1` | 1.21.1 | 767 | 0.116.18+1.21.1 | 0.16.14 | 1.13.6 | 21 |

## 分支之间差在哪

### 1. 构建脚本

- **26.x**：Loom 1.19.4，插件 id `net.fabricmc.fabric-loom`，沿用默认的 official
  映射，依赖写 `implementation`。
- **1.21.x**：Loom 1.13.6，插件 id `fabric-loom`，必须显式
  `mappings loom.officialMojangMappings()`，依赖写 `modImplementation`。
  旧版 Loom 没有 plugin marker，`settings.gradle` 里用 `resolutionStrategy`
  把插件 id 映射到 `net.fabricmc:fabric-loom`。

  这不是审美问题：Loom 1.19 在处理 26.1 之前的 Fabric API 时，会因 access widener
  条目落在 intermediary 命名空间而直接失败
  （`Expected official namespace for access widener entry, found: intermediary`）。

### 2. 源码

只有 `lib/java/xmc/lib/net/` 按版本分叉：

| 符号 | 26.x | 1.21.x |
| --- | --- | --- |
| 通道注册表工厂 | `PayloadTypeRegistry.serverboundConfiguration()` / `clientboundConfiguration()` | `PayloadTypeRegistry.configurationC2S()` / `configurationS2C()` |
| 配置阶段回调取监听器 | `Context#packetListener()` | `Context#networkHandler()` |
| 资源标识符 | `net.minecraft.resources.Identifier` | `ResourceLocation`（≤1.21.10），`Identifier`（1.21.11 起两者都有） |
| 玩家档案名字 | `GameProfile#name()`（authlib 7+） | `GameProfile#getName()`（authlib 6.x，≤1.21.8） |

`core/`、`config/` 以及 `src/test/` 在各分支保持一致。

### 3. 元数据

`fabric.mod.json` 的 `minecraft`、`fabricloader`、`java` 约束按分支写死；`README.md`、
`ARCHITECTURE.md` 里的版本号和协议号也按分支改写。

## 同步规则

1. 业务逻辑（`core/`、`config/`、`padding/`、`src/test/`）先落 `master`。
2. 再 `git cherry-pick <sha>` 到各版本分支。冲突只可能出现在
   `build.gradle`、`settings.gradle`、`gradle.properties`、`fabric.mod.json`
   和 `lib/java/xmc/lib/net/` 下的文件。
3. 新版本发布时：从最接近的分支拉出新分支（26.x 从 26.x 拉，1.21.x 从 1.21.x 拉），
   改版本号与协议号，构建 + 冒烟通过后提交。

## 测试

完整 e2e 只在 **26.1** 上做：Xray 的 XMC 客户端目前把协议号写死成 775
（`transport/internet/finalmask/xmc/client.go`），没有多版本支持。

其它版本用 probe 冒烟，它自带 mock Xray（监听 10086），会完整走一遍
login → configuration → padding → data 通道：

```bash
# dev server 需要 online-mode=false、network-compression-threshold=-1
./gradlew probe -Pxmc.probe.protocol=<协议号>
```

1.21.1 / 1.21.4 / 1.21.5 的 dev server 必须用 **JDK 21** 启动：
Fabric Loader 0.16.x 自带的 Mixin 0.8.7 读不了 JDK 27 的 class 文件
（`Unsupported class file major version 71`）。

```bash
./gradlew -Dorg.gradle.java.home=<jdk21> runServer
./gradlew -Dorg.gradle.java.home=<jdk21> probe -Pxmc.probe.protocol=767
```
