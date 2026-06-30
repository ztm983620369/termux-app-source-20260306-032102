获取 Crush 当前运行状态：当前模型、provider、LSP/MCP 状态、技能、hooks、权限和已禁用工具。无需参数。

<usage>
- 显示当前模型和 provider、LSP/MCP 服务器状态、技能、hooks、权限模式、已禁用工具和关键选项
- 用于诊断某些功能为什么不工作（缺失诊断、provider 错误、MCP 断开等）
- 无需参数，始终返回完整当前状态
</usage>

<tips>
- 查看 [lsp] 和 [mcp] 区段了解服务健康状态
- 查看 [providers] 了解哪些 provider 已启用且可用
- 查看 [skills] 了解可用技能，以及本会话是否已经加载
- 查看 [hooks] 了解配置了哪些 hook 事件，以及 hook runner 是否活跃
- 修复配置问题时可配合 crush-config 技能使用
</tips>
