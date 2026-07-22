# Shadow Plugin × Codex 工业化升级交接（2026-07-21）

> 项目状态：`DEVICE_CORE_ACCEPTED`。
>
> 核心开发闭环已在 Android 16 真机完成；APK installer 三个事务边界的 debug-only fault hook
> 已实现并通过本地门禁，但无线 ADB 监听在设备注入前关闭，因此仍不能写成无保留的“全部工业级
> 封口”。
>
> 完整设备证据：`docs/termux-shadow-device-acceptance-20260721.md`。
>
> 运行证明收尾更新：源码已修复“回滚后 dist/receipt 仍像健康包”和“evidence 缺少 Java 崩溃
> 栈”两个缺口，本地门禁通过；新 APK 尚未安装，状态为
> `LOCAL_GATES_PASSED / DEVICE_RETEST_PENDING`。详见
> `docs/termux-shadow-runtime-proof-closure-20260721.md`。
>
> 输出契约更新：CLI `0.5.0` 已实现默认 compact、verbose 和 evidence 三级输出，当前仍为
> `LOCAL_GATES_PASSED / DEVICE_RETEST_PENDING`。详见
> `docs/termux-shadow-output-contract-optimization-20260721.md`。
>
> Agent 闭环更新：CLI `0.6.0` 已在真机完成 `--agent`、显式 `status --history` 和“编译失败
> 不占版本号”验收，状态为 `DEVICE_ACCEPTED`。最终 Notes active 为 `25 / 2.1.13`；详见
> `docs/termux-shadow-agent-loop-optimization-20260721.md`。
>
> 多模块正确性更新：CLI `0.7.1` 已在真机完成跨模块指纹、`deploy --fresh`、提交式版本水位、
> 首次 deploy、Worker 二进制兼容握手和 stop 生命周期验收，状态为 `DEVICE_ACCEPTED`。详见
> `docs/termux-shadow-multimodule-closure-20260721.md`。本节结论覆盖下方较早的 CLI/设备快照。
>
> 意图式开发入口更新：CLI `0.8.0` 已把日常闭环收敛为默认运行健康的 `shadow-plugin dev`，
> `dev --json` 自动采用 agent 合同，`retry`/`resume` 复用同一状态机；失败上下文、失败不占版本、
> 多模块 Android Library 离线声明/mapping 诊断和 canonical template 均已真机验收，状态为
> `DEVICE_ACCEPTED`。详见 `docs/termux-shadow-dev-intent-loop-20260722.md`。本节结论覆盖下方
> `0.7.1` 快照。

## 结论

`shadow-plugin 0.7.1` 已作为 APK-owned ARM64 原生工具安装到 Termux。Codex 只需执行普通短命
命令，工具链负责 Worker、Gradle、指纹、版本、构建、发布、真实健康、回滚、恢复和 evidence：

```sh
cd ~/termux-shadow-notes
shadow-plugin deploy --bump patch --run --json
```

手机生产链路不调用 ADB。ADB 只在本轮由工作站用于 `install -r`、故障注入观察和系统进程证据。

## 已交付能力

- 短生命周期 Rust CLI + 同 UID 私有 Unix RPC。
- TermuxService 监督的常驻 Worker，60 分钟空闲策略，跨命令 Gradle Daemon。
- 内容寻址缓存；build→publish 复用；相同 SHA 幂等。
- `deploy --bump patch|minor|major --run --json` 一条命令闭环。
- Host registry / receipt / 已提交 history 驱动自动版本；验证缓存不占号，裸发布与降级 fail closed。
- Rust 唯一 inbox publisher，私有 `.part`、fsync、原子 rename 与精确 SHA 注册确认。
- 单一 JSON 错误信封、编译诊断分类、compact status/context 与 revision delta。
- 强制秘密脱敏后的无损 evidence；每文件 SHA/bytes/complete 可校验。
- schema 3 `candidate` / `activating` / `active` 分离。
- Activity create/resume、首帧和稳定窗口 proof；进程死亡优先于 HEALTHY。
- proof-only previous/rollback；失败 generation 不可成为 rollback 目标。
- APK 内置 CLI/template，启动时校验并原子升级，不改用户工程。
- Debug APK 具有默认关闭、私有 trigger 驱动的三个 installer 事务边界确定性故障钩子。
- Android 14–16 后台启动使用可信 immutable PendingIntent；launch lease 有 admission watchdog。
- Worker/OEM freezer 自愈、Worker 崩溃重复 ENSURE、Shadow Gradle 所有权跨 Worker 恢复。

## 真机关键结果

| 场景 | 实测 |
| --- | ---: |
| 冷构建 | 20.687 秒 |
| 独立热构建 | 2.717–3.625 秒，`daemon=REUSED` |
| cache HIT | 0.289 秒，无 Gradle |
| requestId 重放 | 0.091 秒，同 operation/evidence |
| NO_CHANGES deploy | Worker 内 82 ms |
| 相同 SHA publish | 0.190 秒，无 Gradle、revision 不变 |
| Worker 被杀后恢复 | 3.780 秒，新 PID、Daemon REUSED、仍为 WORKER |

运行健康矩阵全部通过：`onCreate` 崩溃、`onResume` 崩溃、READY 后稳定前死亡、READY timeout
均返回结构化 `ACTIVATION_FAILED`，旧 active 未改变。健康 v7 建立后，rollback 只选择带 proof 的
健康 v1。

## 本地门禁

- Rust `cargo fmt --check`。
- 68 项 Rust unit test。
- `cargo clippy --all-targets -- -D warnings`。
- `:shadow-termux-host:testDebugUnitTest`。
- `:app:testDebugUnitTest`。
- 最终 `:app:assembleDebug`：`BUILD SUCCESSFUL`。
- ARM64 PIE、API 23、解释器 `/system/bin/linker64`。
- APK manifest、内嵌 CLI、独立 CLI 和模板树 SHA 逐项一致。

最终制品：

| 产物 | SHA-256 |
| --- | --- |
| 当前本地/设备/内嵌 CLI | `0d9af79ac84b4c6c5c226e5c3e879ae3ad40f851d73d90137b36d3c8adbe19dc` |
| 当前设备已验收 APK | `82fd4058e2ad83c7cba45e690f08011cdf82eaf1efa8b2bea3eded7473e8a155` |
| 当前 canonical template tree | `fda7d924717fffddb5d9eab374dc46dc2d76d4c18be90337ccf8d39ef4955d11` |

## 真机最终状态

- registry revision 957；测试探针已受管删除。
- Basic active `1-c96f7c2b60e44b23`，无 candidate/activating。
- Notes active `25-2647def5be6dd73d` / 2.1.13，升级前后 SHA 未变。
- Stress Lab active `10-03faa0c6a77d67d2` / 1.0.8，previous healthy 为 code 9。
- Notes 无 candidate/activating；用户项目配置哈希保持升级前值。
- 临时 acceptance 插件和项目已删除，故障证据仍按 operationId 保留。
- Worker/Daemon 已通过 `STOPPING -> STOPPED -> 自动重启` 门禁；最终 PID 28982、Daemon WARM。

## 本轮设备验收新增修复

- Installer marker 文件名不一致。
- `/data/data` 与 `/data/user/0` 同 inode home 别名误拒绝。
- Worker 冻结 socket 长等待。
- Android 16 background activity launch 静默拦截与永久 lease。
- Supervisor cleanup/ENSURE 竞态。
- Worker 重启后的 Gradle 所有权接管。
- Worker/direct 工具链指纹不一致。
- signing PKCS8 evidence 脱敏与 rollback 错误码。

## 保留项

设备待验收项之一：fault hook 与三组可区分 APK 已准备；待无线 ADB 监听恢复后，分别在
staging/share rename、binary-old rename、binary-new rename 边界硬终止并重启，证明每个边界都
恢复为完整旧版或完整新版，再安装 canonical candidate。当前设备端口拒绝连接，未以本地模拟冒充
真机通过。

runtime-proof、compact/verbose/evidence、公共 ID、跨模块诊断和 `stop --json` 的设备复验均已
完成；当前仅保留上面的 installer 三事务边界硬终止专项，不把它混同为本轮 CLI 正确性缺口。

## 主要源码

- `termux-shadow-cli/src/worker.rs`：RPC、Worker、冻结/崩溃恢复、Daemon 所有权。
- `termux-shadow-cli/src/build.rs`：缓存、版本门、原子 publisher。
- `termux-shadow-cli/src/runtime_artifacts.rs`：发布产物与健康运行产物分离。
- `termux-shadow-cli/src/runtime_crash.rs`、`evidence.rs`：崩溃关联、证据与诊断。
- `termux-shadow-cli/src/capsule.rs`：增量上下文。
- `app/src/main/java/com/termux/app/ShadowPluginWorkerSupervisor.java`：Android Supervisor。
- `app/src/main/java/com/termux/app/ShadowPluginToolingInstaller.java`：APK tooling installer。
- `shadow-termux-host/.../ShadowControlReceiver.java`：控制入口、PendingIntent 与 launch admission。
- `shadow-termux-host/.../PluginLoadActivity.java`、`ShadowRuntimeHealthReporter.java`：健康协议。
- `shadow-termux-host/.../ShadowCrashHandler.java`：带 operationId 的 Java 崩溃证据。
- `shadow-termux-host/.../ShadowRegistry.java`、`ShadowPlatform.java`：schema 3 与状态不变量。
