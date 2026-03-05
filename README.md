# Termux 应用（中文主页）

这是当前仓库的中文首页说明，目标是让你在 GitHub 手机端和网页端打开仓库时，优先看到中文内容。

## 仓库说明

本仓库主要包含 Termux Android 应用及你当前集成的相关模块代码（终端、文件管理、会话同步、UI 壳层等）。

- 终端应用主模块：`app/`
- 文件管理模块：`File-Manager-1.5.0/File-Manager-1.5.0/app/`
- 会话同步模块：`session-sync-core/`
- 文件桥接模块：`file-bridge/`
- 公共组件：`fossify-commons/`
- UI 壳层：`ui-shell/`
- 其他终端相关模块：`terminal-emulator/`、`terminal-view/`、`terminal-tabs/`、`termux-shared/`
- 额外模块：`artifact-calculator-kit/`

## 你当前仓库状态

你现在使用的是私有仓库，只有你自己（或你授权的协作者）可见。

建议日常更新流程：

```bash
git add -A
git commit -m "feat: 本次更新说明"
git push origin master:main
```

推送后：
- 手机 GitHub 会立即看到更新。
- 另一台机器执行 `git pull origin main` 即可同步。

## 新机器使用

首次拉取：

```bash
git clone <你的仓库地址>
```

进入项目后按本机环境准备 Android SDK/NDK，并配置 `local.properties`。

## 注意事项

- 本仓库提交的是源码与项目资源。
- 本地构建缓存、备份目录通常不建议入库（例如 `.gradle-user-home-local/`、`.android-user-home/`、`backup/`）。
- 若要发布版本，建议使用 `tag + release`：

```bash
git tag -a v1.0.0 -m "v1.0.0"
git push origin v1.0.0
```

## 维护建议

- 每次功能改动后先本地编译校验，再推送。
- 关键改动保留清晰 commit 信息，便于你在手机端快速查看历史。
- GitHub 令牌（PAT）一旦泄露请立即撤销并重建。
