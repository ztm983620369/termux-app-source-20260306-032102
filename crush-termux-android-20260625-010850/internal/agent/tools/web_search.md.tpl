通过 DuckDuckGo 搜索网页；返回标题、URL 和摘要。随后可用 web_fetch 获取完整页面内容。
{{- if .GhAvailable }} 如果是 GitHub 搜索，并且用户提供了精确仓库名、issue 或链接，请改用 bash 中的 `gh search`。{{- end }}
