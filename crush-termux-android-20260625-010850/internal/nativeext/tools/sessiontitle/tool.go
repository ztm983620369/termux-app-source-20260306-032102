package sessiontitle

import (
	"context"
	_ "embed"
	"fmt"

	"charm.land/fantasy"
	"github.com/charmbracelet/crush/internal/session"
)

//go:embed set.md
var setDescription string

//go:embed get.md
var getDescription string

const (
	SetToolName = "set_session_title"
	GetToolName = "get_session_title"
)

type CurrentSessionIDFunc func(context.Context) string

type SetParams struct {
	Title string `json:"title" description:"New title for the current conversation"`
}

type GetParams struct{}

type Metadata struct {
	SessionID string `json:"session_id"`
	Title     string `json:"title"`
}

func NewSetTool(sessions session.Service, currentSessionID CurrentSessionIDFunc) fantasy.AgentTool {
	return fantasy.NewAgentTool(
		SetToolName,
		setDescription,
		func(ctx context.Context, params SetParams, _ fantasy.ToolCall) (fantasy.ToolResponse, error) {
			sessionID := currentSessionID(ctx)
			if sessionID == "" {
				return fantasy.ToolResponse{}, fmt.Errorf("renaming a conversation requires a session ID")
			}
			title := session.NormalizeTitle(params.Title)
			if title == "" {
				return fantasy.ToolResponse{}, fmt.Errorf("conversation title cannot be empty")
			}
			if err := sessions.Rename(ctx, sessionID, title); err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("rename conversation: %w", err)
			}
			return fantasy.WithResponseMetadata(
				fantasy.NewTextResponse(fmt.Sprintf("Conversation title renamed to %q.", title)),
				Metadata{SessionID: sessionID, Title: title},
			), nil
		},
	)
}

func NewGetTool(sessions session.Service, currentSessionID CurrentSessionIDFunc) fantasy.AgentTool {
	return fantasy.NewAgentTool(
		GetToolName,
		getDescription,
		func(ctx context.Context, _ GetParams, _ fantasy.ToolCall) (fantasy.ToolResponse, error) {
			sessionID := currentSessionID(ctx)
			if sessionID == "" {
				return fantasy.ToolResponse{}, fmt.Errorf("reading a conversation title requires a session ID")
			}
			current, err := sessions.Get(ctx, sessionID)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("read conversation title: %w", err)
			}
			return fantasy.WithResponseMetadata(
				fantasy.NewTextResponse(fmt.Sprintf("Current conversation title: %s", current.Title)),
				Metadata{SessionID: current.ID, Title: current.Title},
			), nil
		},
	)
}
