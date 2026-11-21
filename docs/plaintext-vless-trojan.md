# 无 TLS 的 VLESS / Trojan 兼容

本定制版允许公网 IP、IPv6、域名和私网地址上的 VLESS / Trojan 节点使用
`security=none`，也接受省略或清空传输层安全设置的配置。

在节点编辑页将传输层安全设为空即可。普通 VLESS 的加密填写 `none`、流控留空；
服务端需要使用相同的协议和传输设置。导出的 VLESS / Trojan 分享链接会明确写入
`security=none`，再次导入时保留无 TLS 配置。旧版未声明 `security` 的 Trojan
分享链接仍按原有约定默认使用 TLS。

## 源码与构建

- AndroidLibXrayLite gitlink：`a211a9bf4436b12736a754f26dee579d0126a90b`。
- 固定 Xray 源码：`5ca6f4b7d4dc20a881d4330e498892697627ec0c`。
- 应用拥有的兼容补丁：`patches/xray-core-plaintext-vless-trojan.patch`。
- `build-libv2ray.sh` 先应用现有 HTTP / CMCC / CN2 补丁，再应用兼容补丁，
  运行回归测试并生成 ARM64 AAR；所有 APK 构建和正式发布均使用这个入口。

补丁恢复 VLESS / Trojan 出站配置加载时的旧兼容行为，TLS / REALITY 的设置及
校验仍由原内核处理。现有移动私有 SOCKS 的 TCP / UDP、CN2 和 CN2 下载继续保留。
不需要降低整个内核版本，也没有修改协议字段或存储格式。

## 回归检查

```sh
bash build-libv2ray.sh
cd V2rayNG
./gradlew :app:testPlaystoreDebugUnitTest \
  --tests com.v2ray.ang.ui.server.PlaintextServerValidationTest \
  --tests com.v2ray.ang.fmt.PlaintextVlessTrojanFmtTest \
  --tests com.v2ray.ang.core.CoreOutboundBuilderPlaintextTest
./gradlew :app:testPlaystoreDebugUnitTest :app:testFdroidDebugUnitTest \
  :app:testTelecomDebugUnitTest :app:compilePlaystoreDebugKotlin \
  :app:assemblePlaystoreDebug :app:assembleFdroidDebug :app:assembleTelecomDebug
```

Go 回归覆盖公网 IPv4 / IPv6 / 域名、私网地址，TCP / WebSocket / HTTPUpgrade /
gRPC，旧版和新版 JSON 配置，TLS 设置与无效输入。实际收发测试使用公网逻辑地址和
本机模拟服务，校验原生 VLESS / Trojan 握手及往返数据，不访问外部节点。

JUnit 回归覆盖无 TLS 节点表单校验、缺失密码或 ID、错误传输选项、分享链接和
配置 JSON 往返、出站生成以及 TLS / REALITY 参数保留。

**Not run**：在 ARM64 Android 手机上导入、编辑、保存无 TLS 的 VLESS / Trojan
节点，分别用触摸、键盘和 D-pad 激活保存，关闭并重开页面通过存储管理器核对配置，
再分别在 VPN、仅代理、root 模式连接实际节点；
测试需提供对应节点与设备。当前构建环境没有连接的 ARM64 设备。
