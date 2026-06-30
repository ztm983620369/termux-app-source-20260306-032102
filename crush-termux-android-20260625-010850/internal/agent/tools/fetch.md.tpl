从 URL 抓取原始内容，格式可为 text、markdown 或 html（最大 {{ .MaxFetchSizeKB }}KB）；不做 AI 处理。需要分析或提取信息时请使用 agentic_fetch。
{{- if .GhAvailable }} 如果用户提供了精确的 GitHub 仓库、issue 或 PR 链接，请改用 bash 中的 `gh` CLI。{{- end }}
