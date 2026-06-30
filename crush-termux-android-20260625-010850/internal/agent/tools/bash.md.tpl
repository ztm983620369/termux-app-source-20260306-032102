执行 shell 命令；长时间运行的命令会自动转入后台并返回 shell ID。

<cross_platform>
使用 mvdan/sh 解释器（在所有平台上兼容 Bash，包括 Windows）。
路径请使用正斜杠："ls C:/foo/bar"，不要使用 "ls C:\foo\bar"。
Windows 上也可使用常见 shell 内建命令和核心工具。
</cross_platform>

<execution_steps>
1. 目录验证：如果要创建目录/文件，先用 LS 工具确认父目录存在
2. 安全检查：禁用命令（{{ .BannedCommands }}）会返回错误，请向用户说明。安全的只读命令无需提示即可执行
3. 命令执行：使用正确引用执行命令并捕获输出
4. 自动后台：超过 1 分钟的命令（默认值，可通过 `auto_background_after` 配置）会自动转入后台并返回 shell ID
5. 输出处理：超过 {{ .MaxOutputLength }} 个字符时截断
6. 返回结果：包含错误和带 <cwd></cwd> 标签的元数据
</execution_steps>

<usage_notes>
- command 为必填项，working_dir 可选（默认当前目录）
- 重要：用 Grep/Glob/Agent 工具代替 `find`/`grep`。用 View/LS 工具代替 `cat`/`head`/`tail`/`ls`
- 使用 `;` 或 `&&` 串联命令；除非在带引号字符串中，否则避免换行
- 每条命令都在独立 shell 中运行（调用之间不保留状态）
- 优先使用绝对路径而不是 `cd`（只有用户明确要求时才使用 `cd`）
{{- if .RgAvailable }}
- Ripgrep (`rg`) 可用；优先使用它而不是 `grep`，搜索更快且更直观
{{- end }}
</usage_notes>

<background_execution>
- 设置 run_in_background=true 可在独立后台 shell 中运行命令
- 返回 shell ID，用于管理后台进程
- 使用 job_output 工具查看后台 shell 当前输出
- 使用 job_kill 工具终止后台 shell
- 重要：不要在命令末尾使用 `&` 来后台运行，请改用 run_in_background 参数
- 适合后台运行的命令：
  * 长时间运行的服务器（例如 `npm start`、`python -m http.server`、`node server.js`）
  * 监听/监控任务（例如 `npm run watch`、`tail -f logfile`）
  * 不会自行退出的持续进程
  * 任何预期无限运行的命令
- 不应后台运行的命令：
  * 构建命令（例如 `npm run build`、`go build`）
  * 测试套件（例如 `npm test`、`pytest`）
  * Git 操作
  * 文件操作
  * 短生命周期脚本
</background_execution>

<git_message_quality>
创建或更新 commit message、PR 标题、PR 正文时适用以下规则：

- 信息必须让不熟悉代码库的人也能理解。
- 创建或更新信息前，用这个标准检查：新贡献者只读 commit message 或 PR 标题/正文，不打开文件、不看 diff、不知道内部代码名，也应理解它解决什么问题、为什么重要、影响是什么。
- 除非理解用户可见影响必须，否则避免使用代码标识符、文件名、函数名和实现细节。
- 差："添加带 sync.Once 懒加载的 NameFromHex"
- 好："提升颜色名称查询性能并保持快速启动"
</git_message_quality>

<commit_messages>
Commit message 是给未来查看历史的人读的。提交前：

- 遵守 <git_message_quality>。
- 起草 1-2 句简洁说明，重点说明为什么需要这个改动以及它带来什么结果，而不是列文件或实现细节。
- 使用清晰准确的动词（"add"=新增能力，"update"=增强，"fix"=修复 bug），避免泛泛而谈。
- 第一行必须少于 72 个字符。
- 只有需要解释原因、取舍或重要背景时才添加正文；正文按 72 字符换行。
- 如果改动仅限内部，也要描述收益或维护结果，而不是只命名内部代码。
- 差："fix: session.go 中的 nil 指针"
- 好："fix: 避免缺少元数据时加载会话崩溃"
- 差："refactor: 把 PromptBuilder 移到 internal/agent"
- 好："refactor: 让提示词组装更易维护"
</commit_messages>

<git_commits>
当用户要求创建 git commit 时：

1. 在单条消息中使用三个 tool_use 块（速度很重要）：
   - git status（未跟踪文件）
   - git diff（已暂存/未暂存改动）
   - git log（最近提交信息风格）

2. 将相关未跟踪文件加入暂存区。除非相关，不要提交对话开始前就已修改的文件。

3. 在 <commit_analysis> 标签中分析已暂存改动：
   - 列出修改/新增文件，概括性质（功能/增强/修复/重构/测试/文档）
   - 思考目的/动机，评估项目影响，检查敏感信息
   - 不要使用 git 上下文之外的工具

4. 起草 commit message：
   - 遵守 <commit_messages>
   - 提交前用可理解性标准检查草稿

5. 使用 HEREDOC 创建 commit{{ if or (eq .Attribution.TrailerStyle "assisted-by") (eq .Attribution.TrailerStyle "co-authored-by")}}，并包含 attribution{{ end }}：
   git commit -m "$(cat <<'EOF'
   这里写提交信息。

{{ if .Attribution.GeneratedWith }}
💘 Generated with Crush
{{ end}}
{{if eq .Attribution.TrailerStyle "assisted-by" }}

Assisted-by: Crush:{{ .ModelID }}
{{ else if eq .Attribution.TrailerStyle "co-authored-by" }}

Co-Authored-By: Crush <crush@charm.land>
{{ end }}

   EOF
   )"

6. 如果 pre-commit hook 失败，重试一次。如果再次失败，说明 hook 阻止提交。如果成功但文件被修改，必须 amend。

7. 运行 git status 验证。

注意：可行时使用 "git commit -am"；不要暂存无关文件；绝不更新 config；不要 push；不要使用 -i 参数；不要空提交；返回空响应；rebase 时始终使用 -m。
</git_commits>

<pull_requests>
{{ if .GhAvailable -}}
   所有 GitHub 任务都使用 `gh` 命令。
{{- end }}

当用户要求创建或更新 PR 时：

1. 在单条消息中使用多个 tool_use 块（速度非常重要）：
   - git status（未跟踪文件）
   - git diff（已暂存/未暂存改动）
   - 检查分支是否跟踪远端且为最新
   - git log 和 'git diff main...HEAD'（从 main 分叉后的完整提交历史）

2. 需要时创建新分支

3. 需要时提交改动

4. 需要时使用 -u 推送到远端

5. 在 <pr_analysis> 标签中分析改动：
   - 列出从 main 分叉后的提交
   - 概括改动性质
   - 思考目的/动机
   - 评估项目影响
   - 不要使用 git 上下文之外的工具
   - 检查敏感信息

6. 起草 PR 信息：
   - 遵守 <git_message_quality>
   - 起草简洁的 PR 摘要（1-2 个要点），重点说明“为什么”
   - 确保摘要覆盖从 main 分叉后的所有改动
   - 使用清晰简洁的语言
   - 准确反映改动和目的
   - 避免泛泛总结；信息应经过认真思考
   - 创建或更新 PR 前用可理解性标准检查草稿

7. 使用 HEREDOC 通过 gh pr create 创建 PR：
   gh pr create --title "标题" --body "$(cat <<'EOF'

   <summary>

{{ if .Attribution.GeneratedWith -}}
   💘 Generated with Crush
{{- end }}

   EOF
   )"

重要：

- 返回空响应，用户会看到 gh 输出
- 绝不更新 git config
</pull_requests>

<examples>
好：pytest /foo/bar/tests
差：cd /foo/bar && pytest tests
</examples>
