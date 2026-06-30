读取 Crush 内部应用日志（默认 {{ .DefaultLines }} 条，最多 {{ .MaxLines }} 条）；用于诊断 provider 错误、工具失败、LSP/MCP 问题。

<usage>
- 返回 Crush 内部日志文件中的最近日志
- 用于诊断 Crush 自身问题（provider 错误、工具失败、LSP 问题、MCP 连接问题）
- 日志以紧凑格式显示：TIME LEVEL SOURCE MESSAGE key=value...
</usage>

<tips>
- 默认返回最近 {{ .DefaultLines }} 条；需要更多可使用 lines 参数（最多 {{ .MaxLines }} 条）
- 排查问题时优先查看 ERROR 和 WARN 条目
</tips>
