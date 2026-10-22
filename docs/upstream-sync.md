# 与 v2rayNG 上游保持同步

本仓库把官方 [2dust/v2rayNG](https://github.com/2dust/v2rayNG) 作为可信上游，
并保留特供版的协议、共存 flavor、构建和数据文件改动。同步时必须通过可审查的
Pull Request；不要直接把上游强制推送到默认分支。

## 自动同步

特供版默认分支是 `codex/telecom-private-socks`。新增的
[`propose-upstream-sync.yml`](../.github/workflows/propose-upstream-sync.yml)
会在每周一 UTC 03:17 以及手动运行时执行以下操作：

1. 检出当前 fork 的 GitHub 默认分支，而不是把 `master` 写死；因此 fork 改用其他默认分支也能工作。
2. 从固定地址 `https://github.com/2dust/v2rayNG.git` 获取指定分支（默认 `master`），并比较提交历史。
3. 有新提交时，创建以 `sync/upstream-<branch>-<upstream SHA>` 命名的全新分支，在该分支生成一个普通 merge commit，并创建 PR 指向 fork 的默认分支。
4. 若存在冲突，或同名审查分支已经存在，工作流停止且不改写任何远端分支。

它不会自动合并 PR、不会使用 `--force`，也不会执行刚从上游获取的代码。上游仓库地址
不是工作流输入；手动输入只允许安全的合法 Git 分支名，以免把任意仓库或 refspec 当成同步源。

工作流使用 GitHub 自动提供、且仅在该 job 生命周期内有效的 `GITHUB_TOKEN`。不需要存放
PAT、SSH 私钥或上游凭据。为了让工作流能推送审查分支并创建 PR，请在 fork 的
**Settings → Actions → General → Workflow permissions** 中允许 workflow 使用
`Read and write permissions`。如果组织策略不允许该权限，工作流会安全失败，不会回退到
任何长期凭据。

在 **Actions → Propose upstream sync** 中点击 **Run workflow**：

- 保持 `upstream_ref=master` 可检查官方默认分支；需要检查其他官方分支时可明确填写分支名。
- 勾选 `dry_run` 只获取和比较，不创建分支或 PR。
- 每次生成的 PR 都需要人工检查、完成必要测试后再合并。由 `GITHUB_TOKEN` 创建的 PR 在 GitHub
  的事件防递归规则下未必自动触发现有 PR CI；必要时从 Actions 页面手动运行相关构建/测试。

## 本地一次性设置

完成 fork 后，`origin` 应指向自己的 GitHub 仓库，`upstream` 指向官方仓库：

```sh
git remote set-url origin https://github.com/<你的账号>/v2rayNG.git
git remote add upstream https://github.com/2dust/v2rayNG.git 2>/dev/null \
  || git remote set-url upstream https://github.com/2dust/v2rayNG.git
git remote -v
```

本地检查上游差异：

```sh
git fetch --prune upstream
git log --oneline origin/codex/telecom-private-socks..upstream/master
git diff --stat origin/codex/telecom-private-socks...upstream/master
```

需要手动处理冲突时，以新的审查分支完成，不要修改默认分支历史：

```sh
git switch codex/telecom-private-socks
git pull --ff-only origin codex/telecom-private-socks
git switch --create sync/manual-upstream-$(git rev-parse --short upstream/master)
git merge --no-ff upstream/master
# 解决冲突、测试并提交后：
git push -u origin HEAD
```

## 特供版审查清单

同步 PR 合并前，重点确认上游改动没有替换或删除以下内容：

- `telecom` flavor、应用显示名和共存包名；
- HTTP 自定义 Header、私有 SOCKS 节点及其序列化/测速逻辑；
- `AndroidLibXrayLite` 的 gitlink、补丁、GeoIP 数据和 AAR 构建来源；
- 版本号、签名、发布与 GitHub Actions 配置。

`AndroidLibXrayLite` 是独立子模块，不能仅靠根仓库同步 PR 保证其 fork 也同步。对子模块应在其
自己的 fork 中同样设置 `origin`（自己的 fork）和 `upstream`（官方
`2dust/AndroidLibXrayLite`），先审查并推送子模块提交，再在本仓库更新 gitlink。合并任何同步
PR 后运行：

```sh
git submodule update --init --recursive
cd V2rayNG
./gradlew testTelecomDebugUnitTest
```

发布前还应按特供版构建流程验证生成 APK 包名、显示名、签名，以及
`assets/geoip-only-cn-private.dat` 是否被打入包内。
