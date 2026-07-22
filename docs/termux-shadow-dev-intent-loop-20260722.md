# Shadow Plugin `dev` 意图闭环验收（2026-07-22）

> 状态：`DEVICE_ACCEPTED`
>
> 范围：CLI `0.8.0` 的单命令开发入口、默认运行健康、失败上下文、事务化版本、恢复别名、
> 多模块 Android Library 离线诊断与 canonical template 支持。

## 结论

Codex 的日常入口已经收敛为：

```sh
cd ~/termux-shadow-retest-071
shadow-plugin dev
```

不再要求记忆 `--run`、版本号、build/publish/run 顺序或额外 status。`dev` 默认完成项目发现、
快速 doctor、全模块指纹、构建/缓存、版本提交、发布、启动和真实健康证明。`dev --json` 自动使用
agent 合同；`retry` 和 `resume` 是同一状态机的别名。

手机内 CLI 从本地文件、私有 Unix socket、Gradle 与同 UID Host 控制通道完成闭环，不调用 ADB。
本轮 ADB 仅作为工作站 APK 安装与只读/故障验收工具。

## 最终制品

| 制品 | 结果 |
| --- | --- |
| 设备 | `AGI-AN00` / Android 16，`192.168.1.60:43179` |
| CLI | `shadow-plugin 0.8.0` |
| CLI SHA-256 | `56e685b44c7f05ccbceb3b4b728431a6ec9f520760d1a28f053fcc1d3a1d8ddf` |
| ARM64 debug APK SHA-256 | `49d8b96d17306fdce565fae5adac86aae0a3ac6280f77c7a62a0044368fbc12e` |
| Worker | PID `10755`，protocol 1，CLI/binary SHA 与安装文件一致 |

APK `install -r` 后，Termux installer 原子替换了 `$PREFIX/bin/shadow-plugin` 和 canonical
template。设备文件、APK staged asset 与本地 `dist/shadow-plugin` 的 SHA 完全一致。模板同时包含：

```groovy
id 'com.android.library' version '8.13.1' apply false
```

以及将缺失的 Library plugin marker 映射到已缓存 AGP 模块的
`pluginManagement.resolutionStrategy`。

## 真机闭环

验收工程是两模块 `termux-shadow-retest-071`。开始状态为健康 `1.0.1/code 2`，下一版本为 3。

| 场景 | 真机结果 |
| --- | --- |
| 缺少根 Library 版本 | 约 0.47 秒，`ANDROID_LIBRARY_PLUGIN_UNDECLARED`，Gradle 未启动 |
| 缺少离线 AGP mapping | 约 0.40 秒，`ANDROID_LIBRARY_PLUGIN_OFFLINE_MAPPING_MISSING`，定位 `settings.gradle` |
| 首次多模块冷构建 | Gradle 28.803 秒，构建/校验/注册 code 3 成功 |
| 首次运行健康超时 | 旧 active code 2 不变，candidate code 3 可恢复 |
| `shadow-plugin retry --json` | 3.901 秒，从 candidate 续跑；build `SKIPPED`、publish `ALREADY_REGISTERED`、run `HEALTHY` |
| 最终 `dev --json` | 98–202 ms，`NO_CHANGES`，无 Gradle/发布/启动 |
| 独立 `resume --json` | 142 ms，同 Worker PID，`workerReused=true` |
| 子模块 Java 故障 | 3.445 秒，精确到 `modules/feature-kit/.../FeatureContract.java:1` |
| 数字开头 slug | Gradle/template 前失败，并建议 `shadow-plugin new plugin-071-notes` |

最终 retest 状态：

- active：`3-624e96fc95a69c39` / `1.0.2` / code 3；
- previous healthy：code 2；
- candidate/activating：空；
- `runtimeHealth=HEALTHY`，语义为 `FIRST_FRAME_AND_PROCESS_STABILITY`；
- `dist/active.shadowpkg`、版本产物与 `last-healthy.json` SHA 均为
  `624e96fc95a69c394cadf958f5f0156a0071e13bbb86cfe9177a64794c80dab7`；
- nextVersionCode：4；
- dirtySinceActive：false。

## 失败合同

Java 故障返回一个 JSON 文档，包含：

- `code=JAVA_COMPILE_ERROR`、`phase=compileJava`；
- 子模块相对文件与行号；
- 当前健康 `1.0.2/code 3`；
- `activeChanged=false`、`stateChanged=false`；
- `nextVersionCode=4`；
- `nextAction=FIX_AND_RERUN_DEV`；
- `resumeCommand=shadow-plugin dev`；
- evidence ID。

对应 history `op-dev-21017-1784656579364-7` 的 `versionCode`、`versionName` 和
`artifactSha256` 全部为 null，证明失败没有提交版本。运行超时是 retryable；最终源码已改为返回
`nextAction=RETRY_DEV`，并由单测覆盖。

关键 evidence：

| Evidence | 内容 |
| --- | --- |
| `op-dev-21017-1784655972820-2` | 原始 Library marker 离线解析失败 |
| `op-dev-21017-1784656315538-3` | 完整冷构建、注册和运行超时；Gradle 日志完整 |
| `op-dev-21017-1784656434035-4` | candidate 无重建恢复并健康激活 |
| `op-dev-21017-1784656579364-7` | 子模块 Java 编译失败且版本未提交 |
| `op-dev-10755-1784657007540-3` | 最终版离线 mapping 早期诊断 |
| `op-dev-10755-1784657043821-4` | 最终稳定态 NO_CHANGES |

## 本地门禁

- `cargo fmt --check`；
- 76/76 Rust unit tests；
- `cargo clippy --all-targets -- -D warnings`；
- ARM64 API 23 PIE，解释器 `/system/bin/linker64`；
- canonical template `tooling-test.sh`；
- `:shadow-termux-host:testDebugUnitTest`；
- `:app:testDebugUnitTest`；
- `:app:assembleDebug`，`BUILD SUCCESSFUL`；
- APK staged CLI、独立 CLI、设备 CLI SHA 一致。

## 隔离确认

- Notes active 仍为 `25-2647def5be6dd73d` / `2.1.13`；
- Stress Lab active 仍为 `10-03faa0c6a77d67d2` / `1.0.8`；
- 两个项目均未被构建、发布或运行；
- 临时故障源码与设备 cache 备份均已删除；
- retest 工程保留了正确的 Library 根声明与离线 resolution mapping。

