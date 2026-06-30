package nativeext

import (
	"charm.land/fantasy"
	"github.com/charmbracelet/crush/internal/nativeext/tools/sessiontitle"
	"github.com/charmbracelet/crush/internal/session"
)

type Deps struct {
	Sessions         session.Service
	CurrentSessionID sessiontitle.CurrentSessionIDFunc
}

func Tools(deps Deps) []fantasy.AgentTool {
	return []fantasy.AgentTool{
		sessiontitle.NewSetTool(deps.Sessions, deps.CurrentSessionID),
		sessiontitle.NewGetTool(deps.Sessions, deps.CurrentSessionID),
	}
}

func ToolNames() []string {
	return []string{
		sessiontitle.SetToolName,
		sessiontitle.GetToolName,
	}
}
