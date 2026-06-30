package sessiontitle

import (
	"context"
	"encoding/json"
	"testing"

	"charm.land/fantasy"
	"github.com/charmbracelet/crush/internal/db"
	"github.com/charmbracelet/crush/internal/session"
	"github.com/stretchr/testify/require"
)

func TestSetToolRenamesCurrentSession(t *testing.T) {
	sessions := newSessionService(t)
	created, err := sessions.Create(t.Context(), "old")
	require.NoError(t, err)

	tool := NewSetTool(sessions, func(context.Context) string { return created.ID })
	resp := runTool(t, tool, SetToolName, SetParams{Title: "  new\n title  "})
	require.Contains(t, resp.Content, "new title")

	var meta Metadata
	require.NoError(t, json.Unmarshal([]byte(resp.Metadata), &meta))
	require.Equal(t, created.ID, meta.SessionID)
	require.Equal(t, "new title", meta.Title)

	renamed, err := sessions.Get(t.Context(), created.ID)
	require.NoError(t, err)
	require.Equal(t, "new title", renamed.Title)
}

func TestGetToolReadsCurrentSessionTitle(t *testing.T) {
	sessions := newSessionService(t)
	created, err := sessions.Create(t.Context(), "authoritative title")
	require.NoError(t, err)

	tool := NewGetTool(sessions, func(context.Context) string { return created.ID })
	resp := runTool(t, tool, GetToolName, GetParams{})
	require.Contains(t, resp.Content, "authoritative title")

	var meta Metadata
	require.NoError(t, json.Unmarshal([]byte(resp.Metadata), &meta))
	require.Equal(t, created.ID, meta.SessionID)
	require.Equal(t, "authoritative title", meta.Title)
}

func TestGetToolReadsCurrentSessionAmongManySessions(t *testing.T) {
	sessions := newSessionService(t)
	_, err := sessions.Create(t.Context(), "older session")
	require.NoError(t, err)
	current, err := sessions.Create(t.Context(), "current session")
	require.NoError(t, err)
	_, err = sessions.Create(t.Context(), "newer session")
	require.NoError(t, err)

	tool := NewGetTool(sessions, func(context.Context) string { return current.ID })
	resp := runTool(t, tool, GetToolName, GetParams{})
	require.Contains(t, resp.Content, "current session")
	require.NotContains(t, resp.Content, "older session")
	require.NotContains(t, resp.Content, "newer session")

	var meta Metadata
	require.NoError(t, json.Unmarshal([]byte(resp.Metadata), &meta))
	require.Equal(t, current.ID, meta.SessionID)
	require.Equal(t, "current session", meta.Title)
}

func TestToolsRequireCurrentSession(t *testing.T) {
	setTool := NewSetTool(nil, func(context.Context) string { return "" })
	_, err := setTool.Run(context.Background(), toolCall(t, SetToolName, SetParams{Title: "Title"}))
	require.Error(t, err)
	require.Contains(t, err.Error(), "session ID")

	getTool := NewGetTool(nil, func(context.Context) string { return "" })
	_, err = getTool.Run(context.Background(), toolCall(t, GetToolName, GetParams{}))
	require.Error(t, err)
	require.Contains(t, err.Error(), "session ID")
}

func newSessionService(t *testing.T) session.Service {
	t.Helper()

	dataDir := t.TempDir()
	t.Cleanup(func() {
		require.NoError(t, db.Release(dataDir))
		db.ResetPool()
	})

	conn, err := db.Connect(t.Context(), dataDir)
	require.NoError(t, err)
	return session.NewService(db.New(conn), conn)
}

func runTool(t *testing.T, tool fantasy.AgentTool, name string, params any) fantasy.ToolResponse {
	t.Helper()

	resp, err := tool.Run(context.Background(), toolCall(t, name, params))
	require.NoError(t, err)
	return resp
}

func toolCall(t *testing.T, name string, params any) fantasy.ToolCall {
	t.Helper()

	input, err := json.Marshal(params)
	require.NoError(t, err)
	return fantasy.ToolCall{
		ID:    "test-call",
		Name:  name,
		Input: string(input),
	}
}
