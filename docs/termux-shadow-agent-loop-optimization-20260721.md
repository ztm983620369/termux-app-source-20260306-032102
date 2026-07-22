# Shadow Plugin Agent 开发闭环优化验收（2026-07-21）

> 状态：`DEVICE_ACCEPTED`
>
> 范围：CLI `0.6.0` 的 Agent 输出契约、按需 status 历史和编译失败不占版本号。

## 结论

推荐的 Codex 开发入口现为：

```sh
shadow-plugin deploy --bump patch --run --agent
```

`--agent` 是全局选项并隐含 JSON。成功结果只保留当前版本/SHA、源码指纹、四阶段状态与
耗时、诊断计数、真实运行健康、Worker 复用和 `evidenceId`。完整子进程结果、Gradle 日志、
状态前后快照和诊断仍无损保存在 evidence。

版本分配器不再把仅有尝试版本、但没有已验证 artifact SHA 的失败 history 记录视为已提交
版本。编译失败保留审计记录，但修复后复用同一个 versionCode/versionName。产物一旦验证成功，
即使后续发布或运行失败，该版本仍会保留，避免不同内容复用同一发布号。

## 真机结果

设备：`192.168.1.60:43179`，Android 设备型号 `AGI-AN00`。

APK 使用 `install -r` 非清数据升级，Termux 内置工具从 `0.4.0` 原子更新到 `0.6.0`：

| 项目 | 结果 |
| --- | --- |
| 设备 CLI 版本 | `shadow-plugin 0.6.0` |
| 设备 CLI SHA-256 | `ed8741832a65bcce26c3ad301d7a90f14ae7a7226ebdaebfeed7e728cae2c7d2` |
| APK manifest CLI SHA | 与设备及独立 CLI 完全一致 |
| template SHA-256 | `fda7d924717fffddb5d9eab374dc46dc2d76d4c18be90337ccf8d39ef4955d11` |
| Worker | PID `11387`，CLI `0.6.0`，Daemon `WARM` |

旧 Worker `0.4.0` 在第一次 Worker 命令时被安全替换。首次调用端会话结束后，Android
Supervisor 继续完成部署；Worker 最终回到 READY，证明操作不依赖 ADB 或调用 shell 的生命周期。

### Agent 输出

最终无变化结果：

```json
{"activeGeneration":"25-2647def5be6dd73d","diagnosticSummary":{"errors":0,"warnings":0},"durationMs":247,"evidenceId":"op-deploy-11387-1784648351892-5","ok":true,"pluginId":"com.termux.shadow.notes","runtimeHealth":"HEALTHY","sha256":"2647def5be6dd73dcf95ebeecdfbaa216cc1153b20ebed584c23e1782167205b","sourceFingerprint":"75405b20fd15038386508b15bf872bfe78ffaf7699218997248b10d0d824a3e7","stages":{"build":{"status":"SKIPPED"},"doctor":{"durationMs":113,"status":"PASS"},"publish":{"status":"ALREADY_REGISTERED"},"run":{"status":"SKIPPED"}},"stateChanged":false,"status":"NO_CHANGES","versionCode":25,"versionName":"2.1.13","workerPid":11387,"workerReused":true}
```

实测大小为 662 字节，stdout 是单一合法 JSON。它已经足以替代 deploy 后的重复完整 status。

一次真实 warm build/publish/run 返回：

| 阶段 | 结果 |
| --- | ---: |
| doctor | 65 ms |
| build | 5862 ms，Daemon `REUSED` |
| publish | 423 ms |
| run/首帧健康 | 4952 ms |
| 总计 | 11573 ms |

### 编译失败不占版本

在 `NotesActivity.java` 首行注入受控 Java 语法错误，备份放在应用 cache，避免影响项目指纹。

```text
失败前 nextVersionCode = 26
失败后 nextVersionCode = 26
失败退出码 = 1
错误码 = JAVA_COMPILE_ERROR
stateChanged = false
```

最终二进制失败 evidence：`op-deploy-11387-1784648261840-4`。

- diagnostic 精确到 `NotesActivity.java:1`；
- `state-before.json` 与 `state-after.json` SHA 相同；
- history 中失败记录是 versionCode `26`、artifactSha256 `null`；
- 恢复源码后 active 仍是健康版本 25，下一可用版本仍为 26；
- 最终再次 deploy 为 `NO_CHANGES`，没有生成或激活无意义的新版本。

这证明失败尝试不会在 deploy 开始时消耗版本。CLI 0.7.1 进一步收紧为：doctor/standalone
build 的验证缓存也不占号，只有 registry、publish receipt 或已发布 artifact history 才推进水位。

### Status 分层

| 命令 | 实测输出 | 行为 |
| --- | ---: | --- |
| `status --verbose --json` | 36 行 / 1271 字节 | 当前状态与指纹，不含 generations 历史 |
| `status --history --json` | 153 行 / 5483 字节 | 显式展开保留历史 |

`--all` 与 `--raw` 仍是明确的大输出入口。普通 deploy 成功后不需要再执行完整 status。

## 本地门禁

- Rust unit tests：63/63 通过。
- `cargo fmt --check`：通过。
- `cargo clippy --all-targets -- -D warnings`：通过。
- ARM64 Android CLI：构建通过；PIE、API 23、解释器 `/system/bin/linker64`。
- template `tooling-test.sh`：通过。
- `:shadow-termux-host:testDebugUnitTest`：通过。
- `:app:testDebugUnitTest`：通过。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- APK manifest、APK 内嵌 CLI、独立 CLI SHA 一致。

## 制品

| 制品 | SHA-256 |
| --- | --- |
| `termux-shadow-cli/dist/shadow-plugin` | `ed8741832a65bcce26c3ad301d7a90f14ae7a7226ebdaebfeed7e728cae2c7d2` |
| `termux-app_apt-android-7-debug_arm64-v8a.apk` | `6793fae44e3768a66064f887144a417dbd2248dee79d99ef91dd65d6268d4e74` |
| canonical template tree | `fda7d924717fffddb5d9eab374dc46dc2d76d4c18be90337ccf8d39ef4955d11` |

## 最终设备状态

- registry revision：910。
- Notes active：`25-2647def5be6dd73d` / `2.1.13`，真实健康。
- Notes previous healthy：`24-9e9fdf9908afeda5` / `2.1.12`。
- source fingerprint 与 active 一致，`dirtySinceActive=false`。
- candidate/activating 均为空。
- `dist/active.shadowpkg`、`last-healthy.json` 和 last published 均指向健康 active。

故障测试准备阶段曾把一个备份文件误放在源码树内，内容指纹因此正确识别为修改并生成了健康
版本 24；没有坏包被激活。随后使用项目外 cache 备份完成了正式的失败不占号验收，最终源码和
运行状态均已恢复干净。

## 残余观察

APK 升级后的第一次冷部署总计 92.2 秒，其中 build 84.1 秒；后续 warm build 为 5.9 秒，
同一常驻 Worker 上的编译失败为 4.5 秒。最终 APK 再安装后，第一次故障构建因 Worker/Daemon
重新冷启耗时 49.1 秒；随后 NO_CHANGES 为 215–247 ms。冷路径高于既定 20–30 秒目标，是一组
真实设备冷缓存数据点，应作为独立性能问题继续剖析，但不影响本轮 Agent 输出和版本提交正确性。

所有手机侧 `shadow-plugin` 命令均走本地文件、Unix socket、Gradle 和 `/system/bin/am`，未调用
ADB。ADB 仅由工作站用于 APK 部署和本轮受控故障验收。
