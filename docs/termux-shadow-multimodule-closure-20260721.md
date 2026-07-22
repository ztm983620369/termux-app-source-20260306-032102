# Shadow Plugin 多模块闭环验收（2026-07-21）

> 状态：`DEVICE_ACCEPTED`
>
> 范围：CLI `0.7.1` 的多模块输入指纹、`--fresh` 强语义、提交式版本分配、首次 deploy、
> Worker 升级兼容和 stop 生命周期。

## 结论

压力测试反馈中的四个问题已经按状态机不变量修复，并在 Android 真机上的
`termux-shadow-stress-lab` 双模块工程完成故障注入验收。实现不是按 `core-logic` 名称打补丁：
扫描规则对任意 Gradle 模块名和嵌套深度生效。

最终用户入口保持不变：

```sh
shadow-plugin deploy --bump patch --run --agent
```

## 实现契约

1. Source fingerprint v2 收集任意模块的 `src`、`libs`、Gradle scripts、`buildSrc`、
   `build-logic`、wrapper 和 Shadow 输入；任意深度的 `build/.gradle/.cxx/dist` 被排除。
2. `deploy --fresh` 不允许进入 registered-source resume，因此源码未变也会真实执行构建。
3. 自动版本水位只取 Host registry、发布 receipt 和带发布 SHA 的 operation history。
   Doctor/standalone build cache 是可复用工作，不是 release reservation。
4. 新工程未注册时 runtime artifact reconciliation 是幂等空操作，首次 deploy 可直接进入构建。
5. Worker 握手同时校验 protocol、CLI version 和 binary SHA；升级后的旧 inode 不可接收请求。
6. `stop` 响应为 `workerStatus=STOPPING`，Supervisor 完成后 `info` 为 `STOPPED`。

## 真机证据

设备：`AGI-AN00`，ADB 仅用于工作站安装和受控故障注入；CLI 内部链路零 ADB。

| 场景 | 结果 |
| --- | --- |
| standalone fresh build code 8 | `VALIDATED`；active 保持 code 7；next 仍为 8 |
| build 后普通 deploy | `VALIDATED_REUSE`，build 17 ms，无第二次 Gradle；code 8 HEALTHY |
| 相同源码 deploy `--fresh` | 未走 NO_CHANGES；`FRESH` + daemon REUSED；code 9 HEALTHY |
| `core-logic` 普通 deploy 故障 | `JAVA_COMPILE_ERROR`，精确到 `StressReducer.java:64` |
| `core-logic` fresh deploy 故障 | 同样执行编译并失败，不被前置短路 |
| 两次编译失败 | 均 `stateChanged:false`；active code 9、next code 10 不变 |
| 恢复子模块源码 | SHA 恢复为 `731e22fd...e0232`；195 ms `NO_CHANGES` |
| 新工程 doctor full | doctor 后 next 仍为 1 |
| 新工程首次 deploy | `versionCode:1 / 1.0.0`，没有错误跳到 code 2 |
| stop | 响应 `STOPPING`；进程/socket 清除；`info=STOPPED` |
| stop 后恢复 | 新 Worker 自动启动；CLI 指纹升级完成一次健康重建，随后 186 ms `NO_CHANGES` |

跨模块失败 evidence：

```text
op-deploy-11684-1784650296839-4
op-deploy-11684-1784650348830-5
```

第一份 evidence manifest 为 `complete=true`，保存 11 个带 SHA/bytes 的文件；
`state-before.json` 与 `state-after.json` SHA 同为
`f8a85678f247ea5f0b36268fd6f946279f316a68497febf4e56e63f34084bd00`，证明失败未改 registry。

## 最终设备状态

- CLI：`0.7.1`。
- CLI SHA：`0d9af79ac84b4c6c5c226e5c3e879ae3ad40f851d73d90137b36d3c8adbe19dc`。
- APK SHA：`82fd4058e2ad83c7cba45e690f08011cdf82eaf1efa8b2bea3eded7473e8a155`。
- Worker：PID 28982，CLI 0.7.1，binary SHA 与安装文件一致，Daemon WARM。
- Stress Lab：`1.0.8 / code 10 / 10-03faa0c6a77d67d2`，`HEALTHY / INTEGRITY_VERIFIED`。
- Stress active SHA：`03faa0c6a77d67d282e1c9a5141d88ad2f1229d3ed51bff764b01094f63e16b1`，
  与 `dist/active.shadowpkg` 一致。
- 运行证明：`completed=true`、48 events、2 warnings、完整 SHA-256 digest。
- Notes：仍为 `2.1.13 / code 25 / 25-2647def5be6dd73d`，active SHA 未改变。
- 版本事务探针已通过受管 delete 移除，项目和临时备份均已清理。

## 门禁

- `cargo fmt --check`
- 68 项 `cargo test --locked`
- `cargo clippy --all-targets -- -D warnings`
- template `tooling-test.sh`
- `:shadow-termux-host:testDebugUnitTest`
- `:app:assembleDebug`
- ARM64 PIE，解释器 `/system/bin/linker64`
- APK manifest CLI SHA、本地 CLI SHA、设备 CLI SHA 三者一致
