package tools

import (
	"cmp"
	"context"
	_ "embed"
	"fmt"
	"sort"
	"strings"

	"charm.land/fantasy"
	"github.com/charmbracelet/crush/internal/agent/tools/mcp"
	"github.com/charmbracelet/crush/internal/config"
	"github.com/charmbracelet/crush/internal/filepathext"
	"github.com/charmbracelet/crush/internal/permission"
)

type ListMCPResourcesParams struct {
	MCPName string `json:"mcp_name" description:"MCP 服务器名称"`
}

type ListMCPResourcesPermissionsParams struct {
	MCPName string `json:"mcp_name"`
}

const ListMCPResourcesToolName = "list_mcp_resources"

//go:embed list_mcp_resources.md
var listMCPResourcesDescription string

func NewListMCPResourcesTool(cfg *config.ConfigStore, permissions permission.Service) fantasy.AgentTool {
	return fantasy.NewParallelAgentTool(
		ListMCPResourcesToolName,
		listMCPResourcesDescription,
		func(ctx context.Context, params ListMCPResourcesParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
			params.MCPName = strings.TrimSpace(params.MCPName)
			if params.MCPName == "" {
				return fantasy.NewTextErrorResponse("MCP 名称参数为必填项"), nil
			}

			sessionID := GetSessionFromContext(ctx)
			if sessionID == "" {
				return fantasy.ToolResponse{}, fmt.Errorf("列出 MCP 资源需要 session ID")
			}

			relPath := filepathext.SmartJoin(cfg.WorkingDir(), params.MCPName)
			p, err := permissions.Request(
				ctx,
				permission.CreatePermissionRequest{
					SessionID:   sessionID,
					Path:        relPath,
					ToolCallID:  call.ID,
					ToolName:    ListMCPResourcesToolName,
					Action:      "list",
					Description: fmt.Sprintf("列出 %s 的 MCP 资源", params.MCPName),
					Params:      ListMCPResourcesPermissionsParams(params),
				},
			)
			if err != nil {
				return fantasy.ToolResponse{}, err
			}
			if !p {
				return NewPermissionDeniedResponse(), nil
			}

			resources, err := mcp.ListResources(ctx, cfg, params.MCPName)
			if err != nil {
				return fantasy.NewTextErrorResponse(err.Error()), nil
			}
			if len(resources) == 0 {
				return fantasy.NewTextResponse("未找到资源"), nil
			}

			lines := make([]string, 0, len(resources))
			for _, resource := range resources {
				if resource == nil {
					continue
				}
				title := cmp.Or(resource.Title, resource.Name, resource.URI)
				line := fmt.Sprintf("- %s", title)
				if resource.URI != "" {
					line = fmt.Sprintf("%s (%s)", line, resource.URI)
				}
				if resource.Description != "" {
					line = fmt.Sprintf("%s: %s", line, resource.Description)
				}
				if resource.MIMEType != "" {
					line = fmt.Sprintf("%s [mime: %s]", line, resource.MIMEType)
				}
				if resource.Size > 0 {
					line = fmt.Sprintf("%s [大小：%d]", line, resource.Size)
				}
				lines = append(lines, line)
			}

			sort.Strings(lines)
			return fantasy.NewTextResponse(strings.Join(lines, "\n")), nil
		},
	)
}
