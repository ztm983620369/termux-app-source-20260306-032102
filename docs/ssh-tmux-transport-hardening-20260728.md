# Termux SSH/tmux 连接与恢复强化（2026-07-28）

## 结论边界

本阶段已经把持久 SSH/tmux 会话的命令生成、连接复用、断线恢复、PTY 传输和
Ghostty 渲染发布边界做成一条可审计链路，并通过本地 JVM、Android、NDK 四 ABI、
应用 APK 和 instrumentation APK 构建门禁。

这里的“恢复”指：远端 tmux server 和 session 仍存活时，Termux 的 SSH client 断开后
自动重新 attach，且不通过抓取历史文本伪造恢复。远端主机重启、tmux server 被杀、
用户主动删除目标 session、凭据失效或主机密钥变化都不是同一种故障，不能承诺无损
恢复。已在一台 HONOR AGI-AN00（Android 16/API 36）上完成真实 control master 和远端
tmux attach 验证；这不等于蜂窝/Wi-Fi 切换、厂商设备矩阵或长时间稳定性已经达标，也不
宣称数学意义上的“绝对正确”。

## tmux 源码基线

完整非浅克隆位于：

```text
/root/tmux
origin  https://github.com/tmux/tmux.git
commit  00b5323d5c2a020198f4e71b028c43c5782608a4
describe 3.7b-672-g00b5323d
tags    43
```

2026-07-28 已执行远端 fetch、`--ff-only` 更新和 `git fsck --full --strict`；对象库无
missing、dangling corruption 或 garbage，工作树干净。

本次直接依据的 tmux 行为：

- tmux server/session 独立于某个 SSH client；网络连接消失只会让 client detach，不能
  用本地终端缓存代替远端 session。
- `has-session`、`new-session -d` 和 `attach-session` 分别承担存在性检查、首次创建和
  重新接入。每个本地托管客户端仅在首次接入时运行 `capture-pane`，把远端 pane 历史放入
  本地回滚缓冲；同一客户端后续重连不重复回放。
- `tmux -T sync` 声明 client terminal 支持 synchronized updates。tmux
  `tty-features.c` 的 `sync` capability 输出 DECSET/DECRST 2026，`tty.c` 在完整更新边界
  调用它；Termux 的 Java fallback 与 Ghostty authority 均实现 2026，并有 250 ms
  失配解冻保护。
- 托管 tmux 客户端显式关闭 `alternate-screen`，让移动端的主屏回滚缓冲保留 shell 根提示符
  与历史输出；这是一项受限于托管接入的交互兼容策略，不是对所有终端能力的声明。

## 运行链路

```text
profile SSH tokens
  -> strict non-evaluating parser
  -> host-key + liveness + control-mux options
  -> local bash reconnect state machine
  -> OpenSSH control socket / encrypted transport
  -> remote tmux server and retained session
  -> SSH PTY bytes
  -> duplicated native PTY descriptors
  -> Ghostty parser/render state
  -> complete synchronized frame
  -> Canvas or Vulkan presentation
```

## SSH 与恢复实现

`OpenSshCommand` 不执行 profile 文本来判断结构。它只接受可证明为单一 OpenSSH 命令的
literal token 序列，允许显式 `env` 和 `sshpass` wrapper，拒绝 shell operator、管道、
重定向、变量/命令展开、glob、换行和不闭合引号。旧 remote command 会被移除，所有
受管参数插在 destination 之前，新的 remote program 作为一个 quoted argv 追加。

未被用户显式覆盖时，每次受管调用加入：

```text
BatchMode=yes                 非 sshpass profile，禁止隐藏交互阻塞
ConnectTimeout=5              建连超时上界
ConnectionAttempts=1          重试由单一外层状态机负责
ServerAliveInterval=3         加密通道存活探测
ServerAliveCountMax=2         失联后有界退出
TCPKeepAlive=yes              内核级死连接辅助探测
StrictHostKeyChecking=yes     禁止静默接受主机密钥变化
ControlMaster=auto            同一受管 transport scope 复用已认证连接
ControlPersist=300            client 退出后短期保留 master
ControlPath=$PREFIX/tmp/tmx-<128-bit-transport-id>
                               按 canonical profile 和持久记录隔离，并限制 socket 路径长度
```

只要 profile 已配置任一 ControlMaster/ControlPersist/ControlPath，代码就尊重整组自定义
mux 策略，不拼出半套互相冲突的配置。默认 ControlPath 使用 Termux 私有 tmp 目录和
canonical base command、持久记录 ID 的域分离 128 位 SHA-256 前缀；profile 级管理命令
使用独立的 128 位 profile 指纹。不同 pinned tab 即使使用相同 SSH profile，也不会共享
ControlMaster/TCP transport；同一 tab 的 bootstrap 和 attach 才会复用。路径不写入用户
名、地址、密码或密钥路径。当前安装路径下配置值为 72 字节；OpenSSH bind 前追加 17
字节临时后缀后为 89 字节，低于 Android `sockaddr_un.sun_path` 的 108 字节上限。代码另设
80 字节配置上限，
超限时放弃自动 mux 而不是生成必然失败的连接。

第一次 bootstrap 负责原子地确保 session 存在并同步显示名。它不分配远端 TTY；随后
attach 使用强制 TTY。两次调用使用同一 control transport，因此正常启动只付一次密钥
交换和认证成本。断线后 attach client 退出，状态机按 `0, 0.25, 0.5, 1, 2, 5, 10`
秒上界退避；连接稳定至少 10 秒后清零失败计数。session 被明确删除时以专用退出码停止
循环，避免违反用户意图地创建新 session；tmux 缺失采用慢重试，本地 ssh 不存在则停止。

持久记录携带 `ssh_loop_protocol=7`、transport scope 指纹和 canonical profile 身份。
应用恢复时会识别并替换 v6 及更旧的 reconnect loop，也会替换 scope/profile 不匹配的
脚本，避免旧终端绕过隔离策略。只有用户显式创建/锁定新会话的首个 bootstrap 可以创建
缺失 session；恢复已有记录时必须先找到原 session，否则以删除状态停止，不能在应用离线
期间把用户删除的 session 重新创建。该删除状态以专用退出码返回本地 runtime，runtime 会
移除对应持久记录，避免恢复调度反复创建只会立刻退出的本地 tab。tmux session 名、显示名和历史上限均在进入 shell 前
规范化或编码；history limit 被限制在 1,000 到 200,000 行。显示名只在 bootstrap 或
显式重命名时写入，断线 attach 不会用旧脚本中的名称覆盖新名称。

### 2026-07-28 真机故障回归

首个 mux 实现组合了 16 位 profile id 与 40 位 OpenSSH `%C`。OpenSSH 为竞态安全 bind
再追加 `.` 和 16 位随机串，真机最终路径达到 118 字节并以
`unix_listener: path ... too long for Unix domain socket`、退出码 255 持续重试。该故障发生
在 TCP、FRP、认证和远端 tmux 之前。

历史修复部署后，旧 v2 循环全部迁移为 v3，设备创建了 56 字节的
`tmx-<profile-id>` socket。
只读在线验证返回 `Master running`；通过同一 master 查询目标远端会话返回
`attached=1` 和 tmux `3.4`。因此本次故障的 socket bind、SSH 复用和远端 tmux attach 三层
均有真实设备证据，而不只是单元测试推断。当前 v7 的 72 字节 scoped socket 已通过
路径上界、生成脚本、OpenSSH 9.6 配置解析和 tmux 3.4 `-T sync` 本地门禁；本轮未覆盖
安装后的蜂窝/Wi-Fi 故障注入，不能把本地门禁写成新的真机网络结论。

### 2026-07-28 最终信任与隔离收口

受管 OpenSSH 命令现在固定使用应用私有 known-hosts，并显式关闭所有可能把其他信任源
重新带回链路的入口：`GlobalKnownHostsFile=/dev/null`、`KnownHostsCommand=none`、
`VerifyHostKeyDNS=no`、`UpdateHostKeys=no` 和 `CheckHostIP=no`。已有命令中的冲突项会被
移除后规范化写回；旧持久记录在恢复时迁移，不能继续沿用宽松配置。指纹严格按 OpenSSH
的 SHA-256/Base64 语义比较，不对 Base64 做大小写折叠。

JSch/SFTP 不再通过进程级 `activeEndpoint` 选择主机信任上下文。每个新 JSch 实例取得一个
绑定到 canonical authority 的只读 repository view；即使两个连接收到完全相同的 JSch
host callback，它们的 pending、approve、lookup 和 remove 仍只能落到各自端点。对应的
双端点隔离 Robolectric 测试使用不同 Ed25519 key 验证了这一合同。

SFTP 预热固定为 2 个 worker 和 16 个任务的有界队列。队列满时只撤销预热标记，后续由
前台按需连接，不再为每次拒绝创建无上限 fallback thread。这里没有把主机密钥检查、认证
或连接超时换成缓存命中，因此吞吐优化不改变安全语义。

tmux 已用真实 3.4 server 复核目标语法。已存在 session 的查询、attach 和 kill 使用
`=name` 精确目标；`new-session -s` 仍接收普通名称。新受管名称限制为
`[A-Za-z0-9_-]{1,64}`，避开 tmux 会改写或按 target separator 解释的 `.` 和 `:`，防止
名称相似时误接入另一会话。

最低版本 API 23 的构建门禁另外发现并修复了 5 处运行时兼容缺陷：SFTP mount 命令不再
直接调用 API 26 的 timed `Process.waitFor`、`isAlive`；强制终止仅在 API 26+ 使用；目录
缓存清理不再调用 API 24 的 `Collection.removeIf`。超时仍基于单调时钟，并在中断时恢复
线程 interrupt 标志。

## PTY 与原生稳定性

`termux.c` 在 fork 前完成 executable 解析和堆分配；child 只执行可用于 fork/exec 间
隔离区的系统调用。child 创建独立 session/process group，取得 controlling PTY，检查
三个 `dup2`，恢复 signal disposition/mask，关闭继承 fd 后 `execve`。错误路径关闭 fd，
创建后 Java 回写 PID 失败会同时杀进程组和 leader 并 `waitpid` 回收。

Java 不再反射窃取 `FileDescriptor`。native `F_DUPFD_CLOEXEC` 为 PTY 输入、输出建立独立
所有权，control fd 只负责 resize/终止；读、写、waiter 都是 daemon worker。32 KiB
批量读取会在一次阻塞唤醒后排空当前已可用字节，降低 PTY 堵塞和 parser 调用次数。

用户输入先走 1 MiB 无分配原子 `tryWrite`；拥塞时交给严格有序的单线程 overflow writer，
异步在途内存上限为 16 MiB。传输关闭前不静默丢字节；关闭、异常、进程退出和 startup
失败都有幂等唤醒、fd 关闭、进程组终止与 reap 路径。shell 退出后最多等待 2 秒排空 PTY
尾部，再封闭 dispatch，保证退出标记之后不会出现迟到的进程字节。

Ghostty hot path 将 terminal mutation 与 render serialization 分离：持 terminal lock 只做
viewport、begin-update 和 immutable render-state 捕获，随后在 render lock 下编码完整
delta/snapshot。resize、reset、recovery 和 close 遵守同一锁序，避免高吞吐 SSH 输出把
渲染线程长期锁在 parser 后，也避免 backend close 与 JNI render 发生 use-after-free。

## 正确性合同

1. profile 文本不能成为第二段本地 shell program；解析失败必须 fail closed。
2. 主机密钥变化不能通过速度优化被忽略；known-hosts 与连接复用必须同时成立。
3. 任意时刻只有一个 reconnect loop 负责一个 pinned record，旧协议必须迁移。
4. tmux session 存活才允许无损恢复；用户删除 session 后不得自动重建。
5. SSH transport 复用只省略重复握手，不能绕过认证、host-key 或目标隔离。
6. PTY 输入保持提交顺序；输出在 process-exit marker 前完成有界 drain。
7. Ghostty 是解析和 render-state 权威；tmux sync frame 不能造成永久冻结。
8. 任一 native/GPU 恢复失败必须保留 Canvas/兼容路径，不能用黑屏换吞吐。

## 本地门禁

2026-07-28 最终执行：

```text
:terminal-emulator:testDebugUnitTest          PASS
:terminal-view:testDebugUnitTest              PASS
:terminal-session-surface:testDebugUnitTest   PASS
:terminal-session-core:testDebugUnitTest      PASS
:ssh-connection-core:testDebugUnitTest        PASS
:session-sync-core:testDebugUnitTest          PASS
:terminal-session-runtime:testDebugUnitTest   PASS
:app:testDebugUnitTest                        PASS
:ssh-connection-core:lintDebug                PASS
:session-sync-core:lintDebug                  PASS
:terminal-session-core:lintDebug              PASS
:terminal-session-runtime:lintDebug           PASS
:app:assembleDebug                            PASS
:app:assembleDebugAndroidTest                 PASS
NDK arm64-v8a/armeabi-v7a/x86/x86_64          PASS
focused SSH/runtime/app compile gate          PASS
focused Vulkan ownership and native gate      PASS
generated reconnect loop `bash -n`             PASS
isolated tmux 3.4 `-T sync` behavior probe     PASS
git diff --check                              PASS
network/session combined regression           502 tasks, BUILD SUCCESSFUL
network/session lint gate                     260 tasks, BUILD SUCCESSFUL
final APK assembly                            631 tasks, BUILD SUCCESSFUL
ADB install AWLK025930002550                  SUCCESS
TermuxActivity post-install launch            STATUS OK
```

主要产物：

```text
app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
sha256 2c74c8e5c1cc49b9e2b561bfef08cbd0fbbfb83de0adac5ade48ccfdb7fade63

app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sha256 1af1c635698fc1883d0a0bb27dc2b1b89af987feed13abedf7bc9ed42e8094b8
```

## 仍需扩展的真机门禁

单设备在线 attach 证据不能替代以下指标：

1. 首连、warm control-mux 连接和断线恢复分别记录 p50/p95/p99；恢复计时从网络重新可达
   到 tmux 第一帧完整 present，不以日志或 socket 建立代替画面。
2. Wi-Fi/蜂窝切换、飞行模式、NAT idle、服务后台/前台、屏幕锁定和进程压力下各运行
   至少 100 次；检查重复 session、重复字符、输入乱序、黑屏和永久 sync freeze。
3. 分别杀 SSH client、control master、远端 tmux client 和 tmux server；验证前三者按合同
   恢复，最后一种明确报告不可恢复，不伪造历史。
4. 主动删除目标 tmux session、改变 host key、输错密码、撤销密钥和停止 sshd；验证
   fail-closed、退避上界、无重连风暴且不自动重建已删除 session。
5. 同时执行大 paste、持续高速输出、resize、滚动、pinch 和 tab 切换；要求输入 dropped
   bytes 为 0、parser main-thread calls 为 0、无 fd/thread 泄漏，并用实际像素门禁确认
   第一帧和恢复帧完整。

综合真机 instrumentation 已证明约 80 MB PTY 数据保持 parser main-thread calls 为 0，且
Ghostty 最终帧完整；但该 HONOR 设备上的合成 pinch expand 断言仍未改变字号。第二次运行
已确认 scale lifecycle 能正确结束（`scaleActive=false`），剩余问题是合成手势资格/注入，
因此不能把这部分记为通过，也不以它否定上面的真实 SSH/tmux 在线验证。

只有设备矩阵取得上述证据后，才能对特定设备/网络组合给出量化稳定性和恢复时延结论。
