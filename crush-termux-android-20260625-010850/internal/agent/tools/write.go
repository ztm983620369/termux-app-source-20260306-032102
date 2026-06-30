package tools

import (
	"context"
	_ "embed"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"charm.land/fantasy"
	"github.com/charmbracelet/crush/internal/diff"
	"github.com/charmbracelet/crush/internal/filepathext"
	"github.com/charmbracelet/crush/internal/filetracker"
	"github.com/charmbracelet/crush/internal/fsext"
	"github.com/charmbracelet/crush/internal/history"

	"github.com/charmbracelet/crush/internal/lsp"
	"github.com/charmbracelet/crush/internal/permission"
)

//go:embed write.md
var writeDescription string

type WriteParams struct {
	FilePath string `json:"file_path" description:"要写入的文件路径"`
	Content  string `json:"content" description:"要写入文件的内容"`
}

type WritePermissionsParams struct {
	FilePath   string `json:"file_path"`
	OldContent string `json:"old_content,omitempty"`
	NewContent string `json:"new_content,omitempty"`
}

type WriteResponseMetadata struct {
	Diff      string `json:"diff"`
	Additions int    `json:"additions"`
	Removals  int    `json:"removals"`
}

const WriteToolName = "write"

func NewWriteTool(
	lspManager *lsp.Manager,
	permissions permission.Service,
	files history.Service,
	filetracker filetracker.Service,
	workingDir string,
) fantasy.AgentTool {
	return fantasy.NewAgentTool(
		WriteToolName,
		writeDescription,
		func(ctx context.Context, params WriteParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
			if params.FilePath == "" {
				return fantasy.NewTextErrorResponse("文件路径为必填项"), nil
			}

			sessionID := GetSessionFromContext(ctx)
			if sessionID == "" {
				return fantasy.ToolResponse{}, fmt.Errorf("缺少 session_id")
			}

			filePath := filepathext.SmartJoin(workingDir, params.FilePath)

			fileInfo, err := os.Stat(filePath)
			if err == nil {
				if fileInfo.IsDir() {
					return fantasy.NewTextErrorResponse(fmt.Sprintf("路径是目录，不是文件：%s", filePath)), nil
				}

				modTime := fileInfo.ModTime().Truncate(time.Second)
				lastRead := filetracker.LastReadTime(ctx, sessionID, filePath)
				if modTime.After(lastRead) {
					return fantasy.NewTextErrorResponse(fmt.Sprintf("文件 %s 在上次读取后已被修改。\n最后修改时间：%s\n最后读取时间：%s\n\n请重新读取文件后再修改。",
						filePath, modTime.Format(time.RFC3339), lastRead.Format(time.RFC3339))), nil
				}

				oldContent, readErr := os.ReadFile(filePath)
				if readErr == nil && string(oldContent) == params.Content {
					return fantasy.NewTextErrorResponse(fmt.Sprintf("文件 %s 已经包含完全相同的内容，未做更改。", filePath)), nil
				}
			} else if !os.IsNotExist(err) {
				return fantasy.ToolResponse{}, fmt.Errorf("检查文件失败：%w", err)
			}

			dir := filepath.Dir(filePath)
			if err = os.MkdirAll(dir, 0o755); err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("创建目录失败：%w", err)
			}

			oldContent := ""
			if fileInfo != nil && !fileInfo.IsDir() {
				oldBytes, readErr := os.ReadFile(filePath)
				if readErr == nil {
					oldContent = string(oldBytes)
				}
			}

			diff, additions, removals := diff.GenerateDiff(
				oldContent,
				params.Content,
				strings.TrimPrefix(filePath, workingDir),
			)

			p, err := permissions.Request(
				ctx,
				permission.CreatePermissionRequest{
					SessionID:   sessionID,
					Path:        fsext.PathOrPrefix(filePath, workingDir),
					ToolCallID:  call.ID,
					ToolName:    WriteToolName,
					Action:      "write",
					Description: fmt.Sprintf("创建文件 %s", filePath),
					Params: WritePermissionsParams{
						FilePath:   filePath,
						OldContent: oldContent,
						NewContent: params.Content,
					},
				},
			)
			if err != nil {
				return fantasy.ToolResponse{}, err
			}
			if !p {
				resp := NewPermissionDeniedResponse()
				resp = fantasy.WithResponseMetadata(resp, WriteResponseMetadata{
					Diff:      diff,
					Additions: additions,
					Removals:  removals,
				})
				return resp, nil
			}

			err = os.WriteFile(filePath, []byte(params.Content), 0o644)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("写入文件失败：%w", err)
			}

			// Check if file exists in history
			file, err := files.GetByPathAndSession(ctx, filePath, sessionID)
			if err != nil {
				_, err = files.Create(ctx, sessionID, filePath, oldContent)
				if err != nil {
					// Log error but don't fail the operation
					return fantasy.ToolResponse{}, fmt.Errorf("创建文件历史失败：%w", err)
				}
			}
			if file.Content != oldContent {
				// User manually changed the content; store an intermediate version
				_, err = files.CreateVersion(ctx, sessionID, filePath, oldContent)
				if err != nil {
					slog.Error("Error creating file history version", "error", err)
				}
			}
			// Store the new version
			_, err = files.CreateVersion(ctx, sessionID, filePath, params.Content)
			if err != nil {
				slog.Error("Error creating file history version", "error", err)
			}

			filetracker.RecordRead(ctx, sessionID, filePath)

			notifyLSPs(ctx, lspManager, params.FilePath)

			result := fmt.Sprintf("文件已成功写入：%s", filePath)
			result = fmt.Sprintf("<result>\n%s\n</result>", result)
			result += getDiagnostics(filePath, lspManager)
			return fantasy.WithResponseMetadata(
				fantasy.NewTextResponse(result),
				WriteResponseMetadata{
					Diff:      diff,
					Additions: additions,
					Removals:  removals,
				},
			), nil
		},
	)
}
