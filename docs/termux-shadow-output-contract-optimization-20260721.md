# Shadow Plugin CLI 输出契约优化（2026-07-21）

> 后续更新：CLI `0.6.0` 已增加 `--agent`、阶段耗时、失败不占版本以及显式
> `status --history`，并通过真机验收。见
> `docs/termux-shadow-agent-loop-optimization-20260721.md`。本文以下内容保留 CLI `0.5.0`
> 候选阶段的历史记录。

> 状态：`LOCAL_GATES_PASSED`
>
> 设备状态：`DEVICE_RETEST_PENDING`。本轮未使用 ADB，CLI `0.5.0` 与新 APK 尚未安装到手机。

## 结论

CLI 输出现分为三个明确层级：

| 层级 | 用途 | 内容 |
| --- | --- | --- |
| 默认 `--json` | 每轮开发决策 | 单行 compact；目标约 100–250 token |
| `--verbose --json` | 阶段与性能分析 | 指纹、完整阶段、Worker、历史和运行证明 |
| `evidence <evidenceId> --full` | 无损取证 | 完整脱敏 stdout/stderr、诊断、快照、日志与 manifest |

压缩发生在 Worker 的公共响应边界。Worker 在压缩前已经把完整子进程 JSON、stdout/stderr、
Gradle 日志和状态快照写入 evidence，因此默认输出变短不会损失取证信息。

## 默认 deploy

`NO_CHANGES` 只返回决策所需字段：

```json
{"activeGeneration":"16-abcdef","durationMs":87,"evidenceId":"op-...","ok":true,"pluginId":"com.termux.shadow.notes","stateChanged":false,"status":"NO_CHANGES","workerPid":123,"workerReused":true}
```

源码门禁将该响应限制在 320 字节以内。它不再重复：

- source/toolchain fingerprint；
- nextVersionCode；
- version 对象；
- doctor/build/publish/run 的全 SKIPPED 状态；
- historyPath；
- request/operation/evidence 三份同值标识；
- 完整 Worker 对象。

发生变更时，默认结果增加：

- versionCode、versionName、generation、SHA；
- nextVersionCode；
- build cache、Daemon、构建耗时和 warning 数；
- publish/run 结果；
-真实运行产生的 hostOperationId。

## 标识语义

公共输出不再使用含义不清的裸 `operationId`：

| 字段 | 含义 | 默认是否出现 |
| --- | --- | --- |
| `workerRequestId` | Worker 幂等请求键 | 仅 verbose |
| `workerOperationId` | Worker 执行与历史关联 | 仅 verbose |
| `hostOperationId` | Android Host 启动/健康关联 | 发生真实运行时 |
| `evidenceId` | evidence 目录与查询键 | 默认出现 |

verbose deploy 将原 `launch` 重复对象收敛为 `runtimeProof`，不再重复 pluginId、action、status
和 generation。旧 Worker cache 中的 `evidence.id` 仍可反序列化，旧崩溃结果中的
`operationId` 仍可用于关联；新公共输出只生成新字段。

## 其他命令

- `build/publish/upgrade`：默认只给版本、SHA、cache/Gradle 和 stateChanged。
- `ALREADY_PUBLISHED`：保留精确 SHA，并严格保持 `stateChanged=false`。
- `doctor --json`：默认只给 PASS/FAILED、插件/资源 ID、错误和警告计数。
- `doctor --full --json`：默认给 package cache/SHA/Daemon/耗时/构建警告数；完整 checks 在
  evidence，`--verbose` 可直接展开。
- `status --json`：当前项目默认 compact；`--verbose`、`--all`、`--raw` 才展开完整历史。
- `context --json`：默认移除协议/CLI/路径与空字段，Worker 只保留 status/pid/reused/daemon。
- `evidence <evidenceId>`：默认摘要单行；`--full` 和 `--tail` 保持显式完整输出。

失败结果不会为压缩牺牲诊断。`phase`、`code`、`retryable`、message、diagnostics、
stateChanged、logPath、generation、hostOperationId 和 evidenceId 均保留。Java 运行崩溃仍会
保存 `runtime-crash.json`、`runtime-crash.log` 和非空 `RUNTIME_CRASH` diagnostic。

## 本地门禁

- Rust：56 项测试通过。
- `cargo fmt --check`：通过。
- `cargo clippy --all-targets -- -D warnings`：通过。
- ARM64 Android CLI 构建：通过；PIE，解释器 `/system/bin/linker64`。
- 模板 `tooling-test.sh`：通过。
- `:shadow-termux-host:testDebugUnitTest`：通过。
- `:app:testDebugUnitTest`：通过。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`，607 项任务。
- APK manifest、独立 CLI 和 APK 内嵌 CLI SHA 一致。

本地候选制品：

| 制品 | SHA-256 |
| --- | --- |
| `termux-shadow-cli/dist/shadow-plugin` | `1191b420f28a7dbc03ea813fe4eb992d290950a391205b2354e6f1de95003359` |
| APK 内嵌 `shadow-plugin` | `1191b420f28a7dbc03ea813fe4eb992d290950a391205b2354e6f1de95003359` |
| `termux-app_apt-android-7-debug_arm64-v8a.apk` | `9b6a5595d41f65905b44963e256d2e2a0f9fc715d4cdfa6cf48f09bb906dc50a` |
| APK canonical template tree | `fda7d924717fffddb5d9eab374dc46dc2d76d4c18be90337ccf8d39ef4955d11` |

构建仅出现现有 aapt2 experimental 与 Gradle/Kotlin deprecated 配置警告，无新增编译、测试或
打包失败。

## 设备复验

恢复设备连接后：

1. 使用非清数据 `install -r` 安装当前 APK，确认 tooling manifest 显示 CLI `0.5.0`。
2. 分别执行 NO_CHANGES、热构建、ALREADY_PUBLISHED、运行崩溃和 doctor --full。
3. 统计默认/verbose JSON 的实际 bytes/token，并验证 stdout 始终是单一 JSON。
4. 用默认返回的 evidenceId 读取 `evidence --full`，证明指纹、history、原始 Gradle 日志和
   Java crash stack 都仍存在。
5. 用两个独立 Codex 命令确认 Worker PID 不变、workerReused=true、Gradle Daemon REUSED。

## 主要实现

- `termux-shadow-cli/src/worker.rs`：公共响应压缩、ID 归一、verbose 与体积回归测试。
- `termux-shadow-cli/src/dev.rs`：结构化 build stage cache/Daemon/耗时。
- `termux-shadow-cli/src/status.rs`：默认 compact status。
- `termux-shadow-cli/src/capsule.rs`：compact context Worker/空字段裁剪。
- `termux-shadow-cli/src/doctor.rs`：默认诊断摘要。
- `termux-shadow-cli/src/evidence.rs`：evidenceId 公共命名与旧 cache 兼容。
