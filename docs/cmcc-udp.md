# 私有 SOCKS UDP

本实现对应用户提供的《爱家加速协议与实现手册-20260926》`01-教育加速.md` 第 7、8 节：教育节点的 `0x80` / `0x82` SOCKS UDP ASSOCIATE 路径。配置仍使用现有的私有 SOCKS 节点和 `cmcc://` 字段，已有节点无需迁移。

## 收发与生命周期

- 用已认证的 TCP 连接发送 `UDP ASSOCIATE`，请求中的客户端地址为 `0.0.0.0:0`，应用目标放在每个 UDP 数据报的 SOCKS 头中。
- 使用服务端返回的中继端口。返回地址为 `0.0.0.0` 或 `::` 时，优先使用建立该 TCP 连接的实际对端 IP，避免多地址 DNS 将 UDP 发给另一台节点。
- 对上行完整 SOCKS UDP 数据报做一次 XOR FF，包含地址头；下行原样解析。保留每个数据报的独立目标、来源和边界。
- 只解析实际接收长度，丢弃截断地址、非法保留字节和 SOCKS `FRAG != 0` 的报文。这里的 FRAG 是 SOCKS 层分片，普通 IP 层分片由系统处理。
- 私有数据通道使用可容纳完整 UDP 数据报的缓冲区。过大上行报文和短写返回错误，短写不会被重试成第二个数据报；现有应用入口自己的大小限制仍适用。
- TCP 控制连接断开、连接空闲超时或所属请求取消时，关闭 TCP/UDP，打断并等待收发任务退出。每个私有请求还注册到所属 native outbound；核心关闭会取消并等待该实例的全部私有请求，涵盖脱离调用者取消的 UDP dispatcher。取消也能结束等待挑战的认证握手。
- `0x82` 先检查两个方法回复字节，再读取四字节挑战，避免方法被拒绝后继续等待不存在的数据。

控制连接与关联的关系以及 FRAG 处理遵循 [RFC 1928](https://www.rfc-editor.org/rfc/rfc1928.html)。单向 XOR 和私有认证字段依据手册及合成向量，测试不使用真实用户凭据。

## 可复现源码

基础 `AndroidLibXrayLite` gitlink 保持 `a211a9bf4436b12736a754f26dee579d0126a90b`，固定 Xray 源码为 `5ca6f4b7d4dc20a881d4330e498892697627ec0c`。它已有 HTTP Host 和私有 TCP 补丁；本应用再应用 [UDP 增量补丁](../patches/xray-core-cmcc-udp.patch)。补丁与它的 Go 回归测试均保存在主仓库。

这一应用集成层负责扩展固定的 native 输入，避免依赖没有提交的子模块工作树。`AndroidLibXrayLite/README.md` 描述的是固定基础源码；包含 UDP 的最终 AAR 必须通过仓库根部的 [build-libv2ray.sh](../build-libv2ray.sh) 构建。构建会先执行基础测试，再应用 UDP 和 CN2 补丁、执行 Go 竞态及配置测试，最后生成 ARM64 AAR。根据用户后续要求，应用和 HEV 默认也仅构建 ARM64 手机版。测试或构建失败会保留此前应用 AAR；生成物不提交到 Git。下面四种 ABI 的验收记录对应加入 CN2 前的历史构建。

从仓库根部执行：

```sh
bash build-libv2ray.sh
```

随后可在构建后的 native staging 目录单独执行回归：

```sh
cd AndroidLibXrayLite/.build/xray-core
GOWORK=off go test -race ./proxy/socks
GOWORK=off go test ./proxy/http ./proxy/socks
GOWORK=off go test ./infra/conf -run '^TestSocks'
```

Go 用例覆盖两种认证、中继 IPv4/IPv6/域名解析、未指定中继地址、完整上行 XOR、原样下行、大报文、每包目标、截断/分片丢弃、短写/超大包错误、控制连接关闭、活跃关联取消、认证期间取消，以及 native outbound 关闭同时回收活跃和准备中的请求、拒绝关闭后的新请求。普通 SOCKS UDP 及此前私有 TCP 的测试一并运行。

从 `V2rayNG/` 执行 Android 回归：

```sh
./gradlew :app:testPlaystoreDebugUnitTest \
  --tests 'com.v2ray.ang.core.CoreOutboundBuilderPrivateSocksTest' \
  :app:compilePlaystoreDebugKotlin :app:assemblePlaystoreDebug

ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedPlaystoreDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.v2ray.ang.CmccUdpRegressionTest
```

`CmccUdpRegressionTest` 仅用于专用测试设备：它临时保存并恢复设置，使用回环地址上的合成节点，通过 `LauncherManager` 启动实际 daemon，并经应用本地 SOCKS 入口进行 TCP/UDP 往返。VPN 用例检查 VPN 服务运行时的本地 SOCKS 数据通道；第三方应用经 TUN 入口的流量需另行验收。

## 验收记录

2026-10-02，修复后的工作树完成以下检查，修改尚未提交或推送，结果不称为分支 HEAD 上的行为验证：

- Native SOCKS、HTTP、SOCKS 配置和 AndroidLibXrayLite 模块测试，以及 Go `-race` 检查通过；四种 ABI 的 AAR 构建成功。
- API 24 / x86_64 / Playstore 的 7 项新增私有 TCP/UDP 设备用例和 15 项原有回归全部通过，共 22 项，失败、错误、跳过均为 0。新增用例覆盖两种认证的代理模式、VPN 服务运行时的本地 SOCKS 通道、活跃关联的服务停止以及认证期间的服务停止。
- Playstore、Fdroid、Telecom 各 132 项 JVM 单元测试通过，编译、debug 打包及 lint 成功。lint 没有错误，历史 Warning/Hint 保留。
- 三个发行版共 15 个 APK 的四种 ABI 原生库存在检查通过。最终 UDP AAR 的 `libgojni.so` 与各发行版合并后的原生库哈希一致，APK 内的库与 Gradle 去除调试符号后的原生库哈希一致；去除调试符号会改变 AAR 与 APK 的原始字节哈希。

### Not run

| 场景或命令 | 原因 |
| --- | --- |
| 真实教育节点 `0x80` / `0x82` 的 UDP DNS、STUN、游戏和长时间流量 | 手册未包含有效节点用户名和密码；需要实际节点配置，模拟节点不能替代真实服务端互通。 |
| 第三方应用经 VPN TUN / Root 路由入口的 UDP | 本次设备检查使用本地 SOCKS 入口；Root 需要兼容的真实 root 授权环境。 |
| 真实网络切换、handover 与活跃 UDP 的停止竞争 | 可用模拟器为 API 24，handover 分支从 API 28 开始；需要可靠的对应版本设备。 |
| `:app:connectedFdroidDebugAndroidTest`、`:app:connectedTelecomDebugAndroidTest`，以及 ARM64 / ARMv7 / x86 设备运行 | 当前设备仅执行 Playstore x86_64；其他 ABI 已构建并检查打包，尚未在对应硬件运行。 |
| 正式签名 release、远端 GitHub Actions 与发布 | 没有发布签名配置，本次未提交、推送或触发远端工作流。 |
