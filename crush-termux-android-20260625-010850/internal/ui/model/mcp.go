package model

import (
	"fmt"
	"strings"

	"charm.land/lipgloss/v2"
	"github.com/charmbracelet/crush/internal/agent/tools/mcp"
	"github.com/charmbracelet/crush/internal/config"
	"github.com/charmbracelet/crush/internal/ui/common"
	"github.com/charmbracelet/crush/internal/ui/styles"
)

// mcpInfo renders the MCP status section showing active MCP clients and their
// tool/prompt counts.
func (m *UI) mcpInfo(width, maxItems int, isSection bool) string {
	var mcps []mcp.ClientInfo
	t := m.com.Styles

	for _, mcp := range m.com.Config().MCP.Sorted() {
		if state, ok := m.mcpStates[mcp.Name]; ok {
			mcps = append(mcps, state)
		}
	}

	title := t.Resource.Heading.Render("MCP 服务")
	if isSection {
		title = common.Section(t, title, width)
	}
	list := t.Resource.AdditionalText.Render("无")
	if len(mcps) > 0 {
		list = mcpList(t, mcps, width, maxItems)
	}

	return lipgloss.NewStyle().Width(width).Render(fmt.Sprintf("%s\n\n%s", title, list))
}

// mcpCounts formats tool, prompt, and resource counts for display.
func mcpCounts(t *styles.Styles, counts mcp.Counts) string {
	var parts []string
	if counts.Tools > 0 {
		parts = append(parts, t.Resource.CapabilityCount.Render(fmt.Sprintf("%d 个工具", counts.Tools)))
	}
	if counts.Prompts > 0 {
		parts = append(parts, t.Resource.CapabilityCount.Render(fmt.Sprintf("%d 个提示词", counts.Prompts)))
	}
	if counts.Resources > 0 {
		parts = append(parts, t.Resource.CapabilityCount.Render(fmt.Sprintf("%d 个资源", counts.Resources)))
	}
	return strings.Join(parts, " ")
}

// mcpList renders a list of MCP clients with their status and counts,
// truncating to maxItems if needed.
func mcpList(t *styles.Styles, mcps []mcp.ClientInfo, width, maxItems int) string {
	if maxItems <= 0 {
		return ""
	}
	var renderedMcps []string

	for _, m := range mcps {
		var icon string
		title := m.Name
		// Show "Docker MCP" instead of the config name for Docker MCP.
		if m.Name == config.DockerMCPName {
			title = "Docker MCP"
		}
		title = t.Resource.Name.Render(title)
		var description string
		var extraContent string

		switch m.State {
		case mcp.StateStarting:
			icon = t.Resource.BusyIcon.String()
			description = t.Resource.StatusText.Render("启动中...")
		case mcp.StateConnected:
			icon = t.Resource.OnlineIcon.String()
			extraContent = mcpCounts(t, m.Counts)
		case mcp.StateError:
			icon = t.Resource.ErrorIcon.String()
			description = t.Resource.StatusText.Render("错误")
			if m.Error != nil {
				description = t.Resource.StatusText.Render(fmt.Sprintf("错误: %s", m.Error.Error()))
			}
		case mcp.StateDisabled:
			icon = t.Resource.DisabledIcon.String()
			description = t.Resource.StatusText.Render("已停用")
		default:
			icon = t.Resource.OfflineIcon.String()
		}

		renderedMcps = append(renderedMcps, common.Status(t, common.StatusOpts{
			Icon:         icon,
			Title:        title,
			Description:  description,
			ExtraContent: extraContent,
		}, width))
	}

	if len(renderedMcps) > maxItems {
		visibleItems := renderedMcps[:maxItems-1]
		remaining := len(renderedMcps) - maxItems
		visibleItems = append(visibleItems, t.Resource.AdditionalText.Render(fmt.Sprintf("…另有 %d 个", remaining)))
		return lipgloss.JoinVertical(lipgloss.Left, visibleItems...)
	}
	return lipgloss.JoinVertical(lipgloss.Left, renderedMcps...)
}
