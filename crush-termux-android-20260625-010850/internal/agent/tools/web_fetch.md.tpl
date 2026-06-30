抓取网页 URL 并以 markdown 返回内容；供子智能体内部使用。大页面（>50KB）会保存到临时文件，便于 grep/view。
{{- if .GhAvailable }} 如果用户提供了精确的 GitHub 仓库、issue 或 PR 链接，请改用 bash 中的 `gh` CLI。{{- end }}
