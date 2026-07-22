# Shadow Plugin 运行证明收尾（2026-07-21）

> 状态：`LOCAL_GATES_PASSED`
>
> 设备状态：`DEVICE_RETEST_PENDING`。本轮按要求未使用 ADB，未安装新 APK，也未把本地测试
> 结果写成设备验收通过。
>
> 后续 CLI 0.5.0 输出契约优化已生成新的 canonical candidate；本文“最终本地制品”的哈希保留为
> 本阶段历史制品，当前候选见 `docs/termux-shadow-output-contract-optimization-20260721.md`。

## 结论

本轮封闭了两个容易误导开发者和智能体的状态缺口：

1. 发布成功不再等同于运行健康。新发布产物先标记为 `UNPROVEN`；只有 Host 返回精确
   generation/SHA 的 `HEALTHY` 后，才更新安全运行指针。
2. Host 运行时崩溃不再只存在于 Android 日志。崩溃报告按 operationId 关联进入 evidence，
   JSON 失败响应同时返回精简、结构化的运行诊断。

## 运行证明产物

插件工程 `dist/` 现在区分“最后发布”和“最后健康”：

| 文件 | 语义 |
| --- | --- |
| `last-published.json` | 最后一次发布；初始为 `runtimeStatus=UNPROVEN` |
| `<artifact>.shadowpkg.runtime.json` | 指定产物的运行证明或失败状态 |
| `last-runtime.json` | 最后发布产物的当前运行结论 |
| `last-healthy.json` | 最近一次精确通过运行证明的版本、generation 和 SHA |
| `active.shadowpkg` | 与 `last-healthy.json` SHA 完全一致的可安全取用包 |

`active.shadowpkg` 使用临时文件、fsync 和原子 rename 更新；来源必须是普通文件、不能是符号
链接，并在写入前后校验 SHA。构建清理会保留该文件。

激活失败或回滚时：

- 坏版本及 `last-published.json` 标记为 `ACTIVATION_FAILED`、`runtimeProven=false`；
- `active.shadowpkg` 和 `last-healthy.json` 保持上一健康版本；
- 如果健康版本的普通 dist 文件已被清理，可从校验过 SHA 的托管仓库恢复安全指针；
- `status --compact` 直接暴露 safe active、last healthy 和 last published proof 状态。

## 运行崩溃证据

Host 的未捕获异常报告升级为 schema 2，并保存：

- operationId、pluginId、generation、Activity；
- exception type、message 和有界完整 Java stack trace；
- archival crash 文件与稳定的 `reports/runtime-crash/<operationId>.json`；
- 写入前的凭据形态脱敏和长度上限。

Worker-backed `deploy --run`、项目内 `run` 和 `rollback` 会把精确匹配的报告复制到：

```text
~/.termux-shadow/evidence/<operationId>/runtime-crash.json
~/.termux-shadow/evidence/<operationId>/runtime-crash.log
```

证据文件继续参与 evidence manifest 的 SHA、字节数、complete 和脱敏计数。原先为空的
`diagnostics.json` 会得到 `RUNTIME_CRASH` 摘要；CLI 失败信封含 Activity、异常类型、消息和
原始 Host crash report 路径。匹配必须同时满足 operationId、pluginId 和 generation，避免把
其他插件或旧进程的崩溃串入当前操作。

## 本地门禁

- `termux-shadow-basic-plugin/scripts/tests/tooling-test.sh`：通过。
- Rust：47 项测试通过。
- `cargo fmt --check`：通过。
- `cargo clippy --all-targets -- -D warnings`：通过。
- ARM64 Android CLI 构建：通过；PIE，解释器 `/system/bin/linker64`。
- `:shadow-termux-host:testDebugUnitTest`：通过。
- `:app:testDebugUnitTest`：通过。
- `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- APK 内嵌 CLI SHA 与独立 CLI 完全一致。

最终本地制品：

| 制品 | SHA-256 |
| --- | --- |
| `termux-shadow-cli/dist/shadow-plugin` | `d706348b4fd7ab2ef469d427b5c2cb057dbf57bac0d7c0258171a94fa039d899` |
| APK 内嵌 `shadow-plugin` | `d706348b4fd7ab2ef469d427b5c2cb057dbf57bac0d7c0258171a94fa039d899` |
| `termux-app_apt-android-7-debug_arm64-v8a.apk` | `8eadf593b7c63973fced073f91864e09fb35278e5f28d61e4381f3be8c432546` |
| APK canonical template tree | `58daa12680956397b69628f7ca9745f7d2d8e459a4720867141dd68daed8e3b6` |

## 设备复验清单

恢复 ADB 后只需完成以下真机证据，不在手机内的生产 CLI 链路使用 ADB：

1. 对当前 Termux 执行非清数据的 `install -r`，确认 CLI/template 原子升级及重启持久性。
2. 在 Notes 工程执行 `status --compact --json`，确认已回滚坏版本明确显示
   `ACTIVATION_FAILED`，`active.shadowpkg` SHA 指向当前健康 active。
3. 使用隔离验收插件制造 onCreate/onResume 崩溃，确认 evidence 内同时出现
   `runtime-crash.json`、`runtime-crash.log` 和非空 diagnostics。
4. 确认失败后 active/last-healthy 不变，last-published 明确为未证明或激活失败。
5. 重放 status/run，确认恢复与校验不会增加重复代际或把坏包提升为 active。

## 主要实现

- `termux-shadow-cli/src/runtime_artifacts.rs`：安全运行产物与证明状态。
- `termux-shadow-cli/src/runtime_crash.rs`：精确关联、解析和渲染 Host crash report。
- `termux-shadow-cli/src/evidence.rs`：崩溃证据归档、脱敏、manifest 和 diagnostics。
- `termux-shadow-cli/src/control.rs`：HEALTHY/失败转换与结构化运行错误。
- `shadow-termux-host/.../ShadowCrashHandler.java`：带关联字段的有界崩溃报告。
- `termux-shadow-basic-plugin/build.gradle`：构建清理保留 `active.shadowpkg`。
