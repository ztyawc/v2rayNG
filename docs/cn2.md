# CN2 与 CN2 下载

本实现对应用户提供的《爱家加速协议与实现手册-20260926》`03-QyProxy与三种线路.md`。应用支持 QyProxy 的 `cn2` 和 `cn2_download` 两个角色，每个出站分别维护控制会话、数据 token、动态 TCP/UDP 端口池和 DNS 缓存。

## 添加节点

在主界面的添加菜单选择 **手动输入 [CN2 / CN2 下载]**，选择线路角色，填写节点 IPv4 地址或域名、实际控制端口、原始节点用户名、CN2 专用 secret、设备 SN、游戏 ID、区服 ID 和游戏区域。客户端类型、产品和版本可以按实际节点要求填写；默认值来自手册的独立实现。

手册公开示例没有提供可直接登录的用户名或 CN2 专用 secret，需填写自己已有的有效节点信息。用户名由内核按 `原始用户名&客户端类型&游戏ID&SN` 拼接。游戏 ID 与区服 ID 是两个独立字段。

也可以从剪贴板、二维码或订阅导入 `qyproxy://` 链接，格式如下。示例只使用文档地址和合成凭据：

```text
qyproxy://test-user:test-secret@198.51.100.7:7025?role=cn2&sn=test-device&game-id=456&server-id=123&game-area=hk#CN2
qyproxy://test-user:test-secret@198.51.100.8:7025?role=cn2_download&sn=test-device&game-id=456&server-id=123&game-area=hk#CN2-download
```

用户名和 secret 分别按 URI component 编码，特殊字符不会作为分隔符解释。重复参数、未知参数、缺失身份参数、越界 ID、IPv6 节点地址以及不支持的 role 会拒绝导入。已有配置格式与字段没有改名；增加的 `ProfileItem.qyProxy` 是可选字段，旧私有 SOCKS 和 HTTP 配置读取回归已覆盖。

两种角色会在 Configure 的连接区域和选择区域填写对应 role，游戏区域单独保留；导入的 zone 值随配置保留，但这些角色在握手中按手册固定使用 role。全局 Mux 在此协议上关闭。

## 传输与生命周期

- 控制流按五字节帧头和声明长度读取，区分两种 TLV 长度语义，进行 Configure / CHAP-MD5，解析当前回复的实际端口池。
- 同一出站的 TCP/UDP 共享登录，单个请求取消不结束其他请求使用的控制会话。控制断线或心跳超时会关闭旧数据 socket，按 2/5/10 秒退避进行完整重新认证；认证拒绝或重试耗尽后需更新配置并重载。
- TCP 初始化保留有序与重复目标选项，累积读取 14 字节 ACK；之后双向连续 XOR，各自维护偏移。短写按实际写出长度推进，copy 不绕过编解码。Xray 统计包装后的 TCP socket 仍可传递半关闭。服务器先发出 FIN 时会及时发送已缓冲的回复和 EOF，后续上行超时不丢弃已完成的下行数据。
- UDP 使用带目标/来源 IPv4 的独立数据帧，只有 payload 做 XOR，每包从 mask 的位置零开始。按实际接收长度解析，检查真正的节点来源，丢弃错误来源和不支持的报文格式。
- 域名目标通过当前隧道查询 A 记录；CN2 DNS 使用普通 UDP，不添加主线 DNS 前缀。并发同 ID 查询会改写关联内 ID，校验回复 question，再恢复原 ID 和 DNS 目标。DNS 缓存随认证代次回收。
- native outbound 关闭会取消并等待所有活跃请求、数据 worker 和控制登录退出；关闭后的请求被拒绝。

当前范围与手册参考实现一致：IPv4 数据地址、普通 TCP 与未分片 UDP。CN2 每个上行 UDP payload 最多 1190 字节，超限明确返回错误。不包含协议分片、IPv6 数据地址、KCP、fake TCP、QyVpn、Hop、CODE9 恢复、业务账号自动刷新或 APP 的自动分流策略。

## 源码与 ARM64 构建

基础 AndroidLibXrayLite gitlink 为 `a211a9bf4436b12736a754f26dee579d0126a90b`，Xray 固定源码为 `5ca6f4b7d4dc20a881d4330e498892697627ec0c`。主仓库在基础 HTTP/私有 SOCKS 补丁和 UDP 增量补丁后应用 [CN2 补丁](../patches/xray-core-qyproxy-cn2.patch)，不依赖已跟踪的子模块源码修改。

QyProxy transport 和合成测试改编自 ZIP 附带的 `ztyawc/mihomo-cn2` 提交 `0d09a3da4fc9b9c400a33bbd6b0e04c6543d362a`。补丁内保留 GPLv3 许可和来源说明。Xray 配置、出站接口、统计包装、实际 UDP 来源和请求生命周期由本项目接入。

按用户要求，Gradle、HEV 和最终 AAR 默认只构建 ARM64，且不额外生成通用 APK。从仓库根部执行：

```sh
bash compile-hevtun.sh
install -m 0644 libs/arm64-v8a/libhev-socks5-tunnel.so V2rayNG/app/libs/arm64-v8a/libhev-socks5-tunnel.so
install -m 0755 libs/arm64-v8a/libhevsockstun.so V2rayNG/app/libs/arm64-v8a/libhevsockstun.so
bash build-libv2ray.sh
```

从 `V2rayNG/` 执行：

```sh
./gradlew :app:testPlaystoreDebugUnitTest :app:compilePlaystoreDebugKotlin :app:assemblePlaystoreDebug -PABI_FILTERS=arm64-v8a
```

native staging 中的协议、竞态和整个 core 配置加载检查：

```sh
cd AndroidLibXrayLite/.build/xray-core
GOWORK=off go test -race ./proxy/socks ./proxy/qyproxy/...
GOWORK=off go test ./infra/conf -run '^(TestSocks|TestQyProxy)'
```

ARM64 APK 要包含 `libgojni.so`、`libhev-socks5-tunnel.so` 和 `libhevsockstun.so`。从根部运行 `python3 scripts/check_native_packaging.py V2rayNG/app/build/outputs/apk/telecom/debug --abis arm64-v8a` 检查。

## 本次检查结果

2026-10-02 当前工作树的结果：

- 原生协议测试、Go race 检查和完整 Xray 配置加载测试通过，包括共享控制会话、TCP 半关闭、服务器先发 FIN、UDP 来源检查、DNS 并发和关闭取消。
- Playstore、Fdroid、Telecom 各 147 项单元测试通过，合计 441 次执行；新增协议、出站配置与编辑器状态的 15 项测试包含在其中。
- 三个发行版的 Kotlin 编译、ARM64 debug APK 组装和 lint 通过。Lint 无错误；仍有既有警告，分别为 312、328、327 条。
- 三个 APK 各自仅包含 `arm64-v8a`，均通过 native packaging 检查；最终 AAR、Gradle 合并/剥离结果和 APK 的原生库来源一致。

这些结果不包含下面列出的手机与真实节点运行场景。产物为 debug 签名的测试 APK。

## Not run

| 场景或命令 | 原因 |
| --- | --- |
| ARM64 手机上的两种角色 TCP/UDP/DNS、VPN TUN 与 Root 入口、停止/重连竞争 | `adb devices` 没有连接手机；本次按用户要求只构建 ARM64，现有 x86_64 模拟器不适用。Go 回环节点测试不能替代 Android JNI 与服务入口的运行检查。 |
| ARM64 手机上新菜单与表单的触控、键盘、D-pad、IME 首尾控件与保存、TalkBack 标签/状态/焦点、Activity 重建、保存后重开 | 缺少适用的 ARM64 设备。ViewModel 状态和存储模拟测试不能替代真实界面交互检查。 |
| 真实 CN2 / CN2 下载节点的登录、出口、DNS/STUN、游戏流量、长时间下载与网络切换 | ZIP 没有可用用户名、SN 和 CN2 专用 secret；需要自己的有效节点参数。 |
| 正式签名 release 与发布 | 未配置发行签名，当前交付为 debug APK；未执行发布工作流。 |
| 远端 GitHub Actions 验收 | 此记录只列本地检查结果；推送会触发构建，远端结果见仓库 Actions。 |

上述结果只覆盖列出的自动化检查，不表示手机或真实节点运行场景已通过。此前 UDP 的 x86_64 设备验收是加入 CN2 前的记录，不能代替本次检查。
