# 特供版更新发布

特供版的“检查更新”只读取
`ztyawc/v2rayNG` 的正式 GitHub Release。它不会读取官方 `2dust/v2rayNG`
的 Release，也不会读取 GitHub Actions artifact。

## 首次配置签名

Android 只能安装由同一签名证书签发的更新包。为让后续更新可覆盖安装，先选择一份
专用于特供版、会长期保存的 Android keystore，并在本仓库的 **Settings → Secrets and
variables → Actions** 配置下列 repository secrets：

- `TELECOM_KEYSTORE_BASE64`：keystore 文件的 Base64 内容；
- `TELECOM_KEYSTORE_PASSWORD`：keystore 密码；
- `TELECOM_KEY_ALIAS`：签名 alias；
- `TELECOM_KEY_PASSWORD`：该 alias 的密码。
- `TELECOM_CERT_SHA256`：同一 alias 的签名证书 SHA-256 指纹（可带或不带冒号）。

私钥、密码和 keystore 不得提交到仓库或写入 Release notes。使用本地已有 keystore 时，
先确认其证书指纹与已安装特供版一致；若不一致，Android 会拒绝覆盖安装，用户需要
首次手动卸载旧包后安装新的正式签名包。此后所有 Release 必须持续使用同一份 keystore。
发布工作流会把 APK 的实际签名指纹与 `TELECOM_CERT_SHA256` 比对，以防后续误用另一份
签名密钥。可用下列命令查看 keystore 的指纹（不要把 keystore 或密码写入仓库）：

```sh
keytool -list -v -keystore telecom-release.jks -alias "$TELECOM_KEY_ALIAS"
```

## 发布流程

1. 先把 `V2rayNG/app/build.gradle.kts` 的 `versionCode` 和纯数字
   `versionName` 一起提升，例如 `737` / `2.2.7`。
2. 将版本变更合并到默认分支 `codex/telecom-private-socks`。
3. 在 **Actions → Release Telecom APK → Run workflow** 中，从默认分支运行，填写
   `release_tag=v2.2.7`。tag 必须与 APK 的 `versionName` 完全对应。
4. 工作流会先运行 Telecom 更新检查单元测试，再重建补丁内核和 native 依赖，生成签名的
   `v2rayNG_telecom_2.2.7_arm64-v8a.apk`，验证包名与 APK 签名，随后创建正式 GitHub
   Release 及 SHA-256 文件。

不要将该 Release 标记为 prerelease。应用会读取 Release 列表、忽略不适用的 prerelease，
并严格匹配 `v2rayNG_telecom_<version>_<abi>.apk`；因此官方、F-Droid 或签名 sidecar
文件都不会被误选。fork 还没有 Release 时该列表为空，应用会显示已是最新版本，而不会
将 GitHub 的 `latest` 端点 404 误报为网络失败。

当前发布工作流只构建 `arm64-v8a`，因为补丁后的 libv2ray AAR 目前也只包含 arm64
native 库。其他 ABI 的特供版不能使用此 Release 更新；若未来提供它们，必须同时构建
对应 AAR 和同名 ABI APK 资产。

发布后可在特供版的侧栏打开“检查更新”验证；它应显示当前 fork 的 Release notes，并打开
匹配 ABI 的 GitHub Release APK 下载地址。
