# Shadow Plugin × Codex 设备端最终验收（2026-07-21）

> 状态：`DEVICE_CORE_ACCEPTED`
>
> 完整工业封口：`FAULT_HOOK_READY_DEVICE_INJECTION_PENDING`。核心 Worker、发布、真实运行
> 健康、回滚和恢复均已在真机通过。installer 三个事务边界的 debug-only 确定性故障钩子、单测
> 和三组可区分 APK 已完成；无线 ADB 监听在上机前关闭，故三轮设备注入仍未伪造为 PASS。
>
> 后续源码又完成运行证明产物和 Java crash evidence 收尾，本地门禁通过但尚未安装。本文中的
> `DEVICE_CORE_ACCEPTED` 仍只描述原设备基线；新候选状态为 `DEVICE_RETEST_PENDING`。

## 1. 设备与最终制品

- 设备：AGI-AN00，Android 16，Termux `0.118.0` / versionCode 118。
- 设备验收期间工作站 ADB：`192.168.1.60:38623`。
- Termux 内生产链路：Rust CLI + Unix socket + `/system/bin/am`，源码审计确认不调用 ADB。
- 最终 CLI：`shadow-plugin 0.4.0`。

| 制品 | SHA-256 |
| --- | --- |
| 当前设备已验收 CLI | `b5504a1d5fde2e4a282a261277ee004309d80ea5b2f170d96a1746fdeb1368d9` |
| 当前本地候选 CLI 0.5.0 | `1191b420f28a7dbc03ea813fe4eb992d290950a391205b2354e6f1de95003359` |
| 当前设备已安装、已验收 ARM64 debug APK | `6266bedc2d8aa5f532886d8c6d34fb8fa8d737d4a2685b510490e03738f1157b` |
| 当前 canonical 待验收 APK | `9b6a5595d41f65905b44963e256d2e2a0f9fc715d4cdfa6cf48f09bb906dc50a` |
| 当前 APK canonical template tree | `fda7d924717fffddb5d9eab374dc46dc2d76d4c18be90337ccf8d39ef4955d11` |

安装使用 `adb install -r`，没有 clear、卸载、手改 registry 或 destructive baseline。

## 2. 验收中发现并修复的真机缺陷

1. Installer 读取 `tooling-manifest.properties` 却写 `manifest.properties`，导致每次启动重复安装。
   已统一为稳定 marker 名，并增加单测。
2. CLI 的 `/data/data/...` 与 Android Service 的 `/data/user/0/...` 是同一 inode，却被 Worker
   字符串前缀检查误判为越界。已改为仅对 Termux home 别名做映射，并校验 dev/inode。
3. OEM 冻结后台 Termux 时，Worker socket 仍可连接，旧客户端会等待完整 15 分钟。兼容握手
   现最多 1 秒，随后唤醒 Android Supervisor 并重试。
4. Android 16 会静默阻止后台 `startActivity()`，且不抛异常，造成 run 超时和永久 launch
   lease。现使用同 UID immutable `PendingIntent` + Android 14–16 BAL opt-in，并要求 Activity
   在 10 秒内 claim lease。
5. Worker 被杀与 Supervisor `Process.waitFor()` 存在竞态，一次 ENSURE 可能看见垂死旧进程。
   恢复窗口内现每秒重复幂等 ENSURE。
6. Worker 重启可复用旧 Daemon，但原先无法证明所有权，显示 `managed=false`。现给 Shadow
   创建的 Gradle 加内部环境标记；新 Worker 可安全接管并在 stop 时回收，不误杀普通 Gradle。
7. `/data/data` 与 `/data/user/0` 进入工具链指纹后，Worker 与短命 CLI 对同一源码给出不同
   fingerprint。现规范为同一 user-0 路径，`status --compact` 与 deploy 已一致。
8. 签名私钥环境变量现被显式纳入 evidence 强制脱敏；无可用 rollback 返回稳定
   `ROLLBACK_NOT_AVAILABLE`。

## 3. Worker、Daemon 与性能

两个独立客户端命令的实测：

| 场景 | 结果 |
| --- | --- |
| 冷构建 | Gradle 20.687 秒，`daemon=STARTED` |
| 独立热构建 | 2.717–3.625 秒，`daemon=REUSED` |
| 无修改 build | 0.289 秒（含 ADB transport），`cache=HIT`、无 Gradle |
| 固定 requestId 首次 | 0.500 秒 |
| 同 requestId 重放 | 0.091 秒；同 operationId/evidence，不新增操作 |
| 无修改 deploy | Worker 内 82 ms，`NO_CHANGES` |
| 相同 SHA publish | 0.190 秒，`ALREADY_PUBLISHED`、无 Gradle、revision 不变 |

监督关系与权限：

- Worker PID `18938` 的 PPID 是 Termux Android 进程 PID `17179`，不是 CLI。
- Worker 目录/requests 为 `0700`；socket、state、request cache 和 evidence 文件为 `0600`。
- shell UID 无法读取 socket；RPC 另有 `SO_PEERCRED` 同 UID 校验。
- 指向 `$PREFIX/share/...` 的 home 内符号链接项目被拒绝为
  `Worker project escapes allowed Termux home`，stdout 仍是单一 JSON。

后台冻结恢复实测：Worker 确实进入 `__refrigerator`，下一条命令在 1.485 秒内唤醒
Supervisor；PID 和暖 Daemon 保持不变。

## 4. 构建、版本、JSON 与 evidence

- Java 故障准确返回 `JAVA_COMPILE_ERROR`、`compileJava`、文件与第 20 行；完整 Gradle 输出只在
  evidence，active 与 revision 不变。
- 裸 publish 在 0.202 秒返回 `VERSION_REQUIRED`；versionCode 下降在 0.271 秒返回
  `DOWNGRADE_BLOCKED`。
- 相同 source/active 的 deploy 返回 `build=SKIPPED`、`publish=ALREADY_REGISTERED`、
  `run=SKIPPED`。
- `context --since-revision 568` 无变化时严格只有
  `protocolVersion/changed/registryRevision` 三字段。
- 失败与成功 `--json` stdout 均为单一可解析对象；JSON 模式 stderr 未追加文本。
- 实机 evidence manifest 的 10 个文件逐一复算 SHA/bytes，0 mismatch；manifest SHA 与响应
  evidence SHA 相同，`complete=true`。
- 设备已验收的 CLI 0.4.0 默认失败响应约 78–226 token；成功 build/deploy 约 319–440 token。
  CLI 0.5.0 已在源码侧完成 compact/verbose/evidence 分层和体积门禁，但该改进仍待设备复测。

## 5. 真实运行健康故障矩阵

测试仅使用临时 `com.termux.shadow.acceptance.e2e`；Notes 从未注入故障。健康 v1 为旧 active，
每个故障使用新的 versionCode。

| 场景 | generation / operationId | 结果 |
| --- | --- | --- |
| `onCreate` 抛异常 | `3-4889c1c1728567dd` / `259155a8-360c-407f-8d8f-ff9e06164dbf` | 2.461 秒 `ACTIVATION_FAILED/ROLLED_BACK`；Android FATAL 精确到第 20 行 |
| `onResume` 抛异常 | `4-8b8b7947199bf0a2` / `dc529d5a-67ee-49dc-b94b-44d578966da5` | 1.254 秒 `ACTIVATION_FAILED/ROLLED_BACK` |
| READY 后、稳定前死亡 | `5-e375ad51cb54b295` / `aa921eb8-d67e-4be1-a970-b2c18b588364` | 先记录 `PLUGIN_FIRST_FRAME_READY`，841 ms 后死亡并回滚，未产生 HEALTHY |
| 不产生 READY | `6-ded8d0f04ba9b660` / `4e8be9c1-9d70-4474-af94-ba1adb11b7b0` | 15 秒 Host watchdog，`ACTIVATION_FAILED/ROLLED_BACK` |

四种失败均满足：旧 active 不变；candidate/activating 终态清空；失败计数为 1；lastError 为真实
进程死亡或健康超时；runtime proof 为 0；失败版本不进入 previousHealthy。

随后健康 v7 `7-77aaf5875e9845c1` 通过 first-frame + stability；proof-only rollback 只选择健康
v1，operationId `c79d03f8-143f-41c6-8cde-a58e8cc7b428`，3.727 秒重新 HEALTHY。v2–v6
均未被选择。

## 6. 中断与恢复

- CLI 中断：构建客户端被 `SIGKILL`，Worker 状态保持 BUSY 并继续完成；
  `op-build-30184-1784622716022-6` evidence 为 `complete=true/ok=true`。
- Worker 中断：BUSY Worker PID `30561` 被杀，3.780 秒自动换为 PID `31558`，请求成功，
  `executionMode=WORKER`、`daemon=REUSED`。
- Daemon 所有权：恢复后的 Worker 显示 `gradleDaemonManaged=true`；`shadow-plugin stop --json`
  后 Worker、Daemon 和 socket 全部消失。
- Host/App 重启：多轮 `install -r` 均保持 schema 3、Notes active/previous 和用户工程；一次持有
  未运行 candidate 的重启最终按真实 process-death proof 回滚，没有提升坏版本。
- schema 2→3：revision 保留，新增 `activatingGeneration=null`，既有健康 proof 和指针保留。

## 7. APK 工具升级

从设备旧 CLI `0.3.0`（SHA
`ff5b93027f6420198822b974a5923011deffd24d01a55e93e81c660c4bbb8780`）覆盖升级到
最终 0.4.0。最终 marker、APK manifest 与落盘 CLI/template 完全一致；binary `0700`、marker
`0600`、模板目录 `0700`；没有 `.old/.new/staging` 残留。

用户工程配置哈希升级前后不变：

```text
basic 7ef270eced5395ec573682dee46b0b695bc3c3e55524ab5fd27faeede57a96f7
notes f617901c05380d093821905a7cb99a8800ce28338ad799b253e1671e1700b25d
```

已增加仅 `BuildConfig.DEBUG` 可达、默认无 trigger 的确定性故障钩子。它分别位于
`after-share-rename`、`after-binary-old-rename`、`after-binary-new-rename`；命中时先原子消费
私有 trigger，再 fsync `reached` 证据，最后硬终止 App 进程，重启不会重复自杀。明确闭集单测和
最终 canonical assemble 均通过。

三组可区分 probe APK 已准备：

| 边界 | APK SHA-256 | template SHA-256 |
| --- | --- | --- |
| share rename | `1d005ced7e6a943f5561a6084c845be250d7a40f52808c67193db3a7ce5fbbc2` | `04509a2bf6d4b3b5e08007bd14e0c37968f17d1b58cf203debe4d61e2094696e` |
| binary old rename | `317b7a87465facbdf3630bf509231e9f0a21f4ebedf3c1183f28733085cc627e` | `bb177852caa35a618783c0b87c867be80bb21cbad8757823764a808d11134bf3` |
| binary new rename | `6bb5aa2748e67f621d182bf7ca43e9c908f829f1b434a23fd9c78fa5a0cc00c0` | `a0700ca984bd6a2f27650c4556b4d8977d6d70d9bf19830881bd4e17eb9651d4` |

设备注入仍为 `NOT_RUN`：设备继续通过 mDNS 广播 `192.168.1.60:39015`，但该端口与原
`38623` 均在 TCP 层 `Connection refused`，ADB device list 为空。没有用随机杀进程、手工移动
tooling 或仅本地模拟冒充真机通过；设备仍保留上表已验收 APK，待验收 candidate 尚未安装。

## 8. 清理后的生产状态

- 临时 acceptance 插件、项目和源码备份已删除；audit/evidence 按设计保留。
- 屏幕超时已恢复为 15000 ms，`stayon` 已关闭。
- registry revision 687：2 个插件、5 个版本。
- Basic：active `1-c96f7c2b60e44b23`，candidate/activating 均空。
- Notes：active `13-52d727104cfb144a` / 2.1.1；previous healthy
  `12-c5883432aed42e35` / 2.1.0；candidate/activating 均空。
- Worker 被显式停止；下一条 build/deploy 会由 Android Supervisor 透明启动。

## 9. 未封口项

1. 无线调试监听恢复后，使用已实现的 debug-only fault hook 确定性覆盖三个 rename 边界，随后
   安装 canonical candidate；通过后把状态提升为无保留 `DEVICE_ACCEPTED`。
2. 安装当前 runtime-proof candidate，复验失败版本标记、safe active/last healthy 指针，以及
   onCreate/onResume 崩溃的 operationId 关联 evidence，并验收 CLI 0.5.0 的
   compact/verbose/evidence 三级输出。当前仅为本地门禁通过。
3. P2：`stop --json` 响应是在 shutdown 前拍摄的 Worker 快照，可改为明确 `STOPPING`；实际
   进程、Daemon 与 socket 已验证停止。
