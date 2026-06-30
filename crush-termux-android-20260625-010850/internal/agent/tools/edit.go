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

type EditParams struct {
	FilePath   string `json:"file_path" description:"要修改的文件绝对路径"`
	OldString  string `json:"old_string" description:"要替换的文本"`
	NewString  string `json:"new_string" description:"替换后的文本"`
	ReplaceAll bool   `json:"replace_all,omitempty" description:"替换 old_string 的所有出现位置（默认 false）"`
}

type EditPermissionsParams struct {
	FilePath   string `json:"file_path"`
	OldContent string `json:"old_content,omitempty"`
	NewContent string `json:"new_content,omitempty"`
}

type EditResponseMetadata struct {
	Additions  int    `json:"additions"`
	Removals   int    `json:"removals"`
	OldContent string `json:"old_content,omitempty"`
	NewContent string `json:"new_content,omitempty"`
}

const EditToolName = "edit"

var (
	oldStringNotFoundErr        = fantasy.NewTextErrorResponse("文件中找不到要替换的文本。请确认内容完全匹配，包括空白字符和换行。")
	oldStringMultipleMatchesErr = fantasy.NewTextErrorResponse("要替换的文本在文件中出现多次。请提供更多上下文确保唯一匹配，或启用全部替换。")
)

//go:embed edit.md
var editDescription string

type editContext struct {
	ctx         context.Context
	permissions permission.Service
	files       history.Service
	filetracker filetracker.Service
	workingDir  string
}

func NewEditTool(
	lspManager *lsp.Manager,
	permissions permission.Service,
	files history.Service,
	filetracker filetracker.Service,
	workingDir string,
) fantasy.AgentTool {
	return fantasy.NewAgentTool(
		EditToolName,
		editDescription,
		func(ctx context.Context, params EditParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
			if params.FilePath == "" {
				return fantasy.NewTextErrorResponse("文件路径为必填项"), nil
			}

			params.FilePath = filepathext.SmartJoin(workingDir, params.FilePath)

			var response fantasy.ToolResponse
			var err error

			editCtx := editContext{ctx, permissions, files, filetracker, workingDir}

			if params.OldString == "" {
				response, err = createNewFile(editCtx, params.FilePath, params.NewString, call)
			} else if params.NewString == "" {
				response, err = deleteContent(editCtx, params.FilePath, params.OldString, params.ReplaceAll, call)
			} else {
				response, err = replaceContent(editCtx, params.FilePath, params.OldString, params.NewString, params.ReplaceAll, call)
			}

			if err != nil {
				return response, err
			}
			if response.IsError {
				// Return early if there was an error during content replacement
				// This prevents unnecessary LSP diagnostics processing
				return response, nil
			}

			notifyLSPs(ctx, lspManager, params.FilePath)

			text := fmt.Sprintf("<result>\n%s\n</result>\n", response.Content)
			text += getDiagnostics(params.FilePath, lspManager)
			response.Content = text
			return response, nil
		},
	)
}

func createNewFile(edit editContext, filePath, content string, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
	fileInfo, err := os.Stat(filePath)
	if err == nil {
		if fileInfo.IsDir() {
			return fantasy.NewTextErrorResponse(fmt.Sprintf("路径是目录，不是文件：%s", filePath)), nil
		}
		return fantasy.NewTextErrorResponse(fmt.Sprintf("文件已存在：%s", filePath)), nil
	} else if !os.IsNotExist(err) {
		return fantasy.ToolResponse{}, fmt.Errorf("访问文件失败：%w", err)
	}

	dir := filepath.Dir(filePath)
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("创建父目录失败：%w", err)
	}

	sessionID := GetSessionFromContext(edit.ctx)
	if sessionID == "" {
		return fantasy.ToolResponse{}, fmt.Errorf("创建新文件需要 session ID")
	}

	_, additions, removals := diff.GenerateDiff(
		"",
		content,
		strings.TrimPrefix(filePath, edit.workingDir),
	)
	p, err := edit.permissions.Request(
		edit.ctx,
		permission.CreatePermissionRequest{
			SessionID:   sessionID,
			Path:        fsext.PathOrPrefix(filePath, edit.workingDir),
			ToolCallID:  call.ID,
			ToolName:    EditToolName,
			Action:      "write",
			Description: fmt.Sprintf("创建文件 %s", filePath),
			Params: EditPermissionsParams{
				FilePath:   filePath,
				OldContent: "",
				NewContent: content,
			},
		},
	)
	if err != nil {
		return fantasy.ToolResponse{}, err
	}
	if !p {
		resp := NewPermissionDeniedResponse()
		resp = fantasy.WithResponseMetadata(resp, EditResponseMetadata{
			OldContent: "",
			NewContent: content,
			Additions:  additions,
			Removals:   removals,
		})
		return resp, nil
	}

	err = os.WriteFile(filePath, []byte(content), 0o644)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("写入文件失败：%w", err)
	}

	// File can't be in the history so we create a new file history
	_, err = edit.files.Create(edit.ctx, sessionID, filePath, "")
	if err != nil {
		// Log error but don't fail the operation
		return fantasy.ToolResponse{}, fmt.Errorf("创建文件历史失败：%w", err)
	}

	// Add the new content to the file history
	_, err = edit.files.CreateVersion(edit.ctx, sessionID, filePath, content)
	if err != nil {
		// Log error but don't fail the operation
		slog.Error("Error creating file history version", "error", err)
	}

	edit.filetracker.RecordRead(edit.ctx, sessionID, filePath)

	return fantasy.WithResponseMetadata(
		fantasy.NewTextResponse("文件已创建："+filePath),
		EditResponseMetadata{
			OldContent: "",
			NewContent: content,
			Additions:  additions,
			Removals:   removals,
		},
	), nil
}

func deleteContent(edit editContext, filePath, oldString string, replaceAll bool, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return fantasy.NewTextErrorResponse(fmt.Sprintf("找不到文件：%s", filePath)), nil
		}
		return fantasy.ToolResponse{}, fmt.Errorf("访问文件失败：%w", err)
	}

	if fileInfo.IsDir() {
		return fantasy.NewTextErrorResponse(fmt.Sprintf("路径是目录，不是文件：%s", filePath)), nil
	}

	sessionID := GetSessionFromContext(edit.ctx)
	if sessionID == "" {
		return fantasy.ToolResponse{}, fmt.Errorf("删除内容需要 session ID")
	}

	lastRead := edit.filetracker.LastReadTime(edit.ctx, sessionID, filePath)
	if lastRead.IsZero() {
		return fantasy.NewTextErrorResponse("编辑文件前必须先读取文件。请先使用 View 工具"), nil
	}

	modTime := fileInfo.ModTime().Truncate(time.Second)
	if modTime.After(lastRead) {
		return fantasy.NewTextErrorResponse(
			fmt.Sprintf(
				"文件 %s 在上次读取后已被修改（修改时间：%s，读取时间：%s）",
				filePath, modTime.Format(time.RFC3339), lastRead.Format(time.RFC3339),
			),
		), nil
	}

	content, err := os.ReadFile(filePath)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("读取文件失败：%w", err)
	}

	oldContent, isCrlf := fsext.ToUnixLineEndings(string(content))

	var newContent string

	if replaceAll {
		newContent = strings.ReplaceAll(oldContent, oldString, "")
		if newContent == oldContent {
			return oldStringNotFoundErr, nil
		}
	} else {
		index := strings.Index(oldContent, oldString)
		if index == -1 {
			return oldStringNotFoundErr, nil
		}

		lastIndex := strings.LastIndex(oldContent, oldString)
		if index != lastIndex {
			return oldStringMultipleMatchesErr, nil
		}

		newContent = oldContent[:index] + oldContent[index+len(oldString):]
	}

	_, additions, removals := diff.GenerateDiff(
		oldContent,
		newContent,
		strings.TrimPrefix(filePath, edit.workingDir),
	)

	p, err := edit.permissions.Request(
		edit.ctx,
		permission.CreatePermissionRequest{
			SessionID:   sessionID,
			Path:        fsext.PathOrPrefix(filePath, edit.workingDir),
			ToolCallID:  call.ID,
			ToolName:    EditToolName,
			Action:      "write",
			Description: fmt.Sprintf("从文件 %s 删除内容", filePath),
			Params: EditPermissionsParams{
				FilePath:   filePath,
				OldContent: oldContent,
				NewContent: newContent,
			},
		},
	)
	if err != nil {
		return fantasy.ToolResponse{}, err
	}
	if !p {
		resp := NewPermissionDeniedResponse()
		resp = fantasy.WithResponseMetadata(resp, EditResponseMetadata{
			OldContent: oldContent,
			NewContent: newContent,
			Additions:  additions,
			Removals:   removals,
		})
		return resp, nil
	}

	if isCrlf {
		newContent, _ = fsext.ToWindowsLineEndings(newContent)
	}

	err = os.WriteFile(filePath, []byte(newContent), 0o644)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("写入文件失败：%w", err)
	}

	// Check if file exists in history
	file, err := edit.files.GetByPathAndSession(edit.ctx, filePath, sessionID)
	if err != nil {
		_, err = edit.files.Create(edit.ctx, sessionID, filePath, oldContent)
		if err != nil {
			// Log error but don't fail the operation
			return fantasy.ToolResponse{}, fmt.Errorf("创建文件历史失败：%w", err)
		}
	}
	if file.Content != oldContent {
		// User manually changed the content; store an intermediate version
		_, err = edit.files.CreateVersion(edit.ctx, sessionID, filePath, oldContent)
		if err != nil {
			slog.Error("Error creating file history version", "error", err)
		}
	}
	// Store the new version
	_, err = edit.files.CreateVersion(edit.ctx, sessionID, filePath, newContent)
	if err != nil {
		slog.Error("Error creating file history version", "error", err)
	}

	edit.filetracker.RecordRead(edit.ctx, sessionID, filePath)

	return fantasy.WithResponseMetadata(
		fantasy.NewTextResponse("已从文件删除内容："+filePath),
		EditResponseMetadata{
			OldContent: oldContent,
			NewContent: newContent,
			Additions:  additions,
			Removals:   removals,
		},
	), nil
}

func replaceContent(edit editContext, filePath, oldString, newString string, replaceAll bool, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return fantasy.NewTextErrorResponse(fmt.Sprintf("找不到文件：%s", filePath)), nil
		}
		return fantasy.ToolResponse{}, fmt.Errorf("访问文件失败：%w", err)
	}

	if fileInfo.IsDir() {
		return fantasy.NewTextErrorResponse(fmt.Sprintf("路径是目录，不是文件：%s", filePath)), nil
	}

	sessionID := GetSessionFromContext(edit.ctx)
	if sessionID == "" {
		return fantasy.ToolResponse{}, fmt.Errorf("编辑文件需要 session ID")
	}

	lastRead := edit.filetracker.LastReadTime(edit.ctx, sessionID, filePath)
	if lastRead.IsZero() {
		return fantasy.NewTextErrorResponse("编辑文件前必须先读取文件。请先使用 View 工具"), nil
	}

	modTime := fileInfo.ModTime().Truncate(time.Second)
	if modTime.After(lastRead) {
		return fantasy.NewTextErrorResponse(
			fmt.Sprintf(
				"文件 %s 在上次读取后已被修改（修改时间：%s，读取时间：%s）",
				filePath, modTime.Format(time.RFC3339), lastRead.Format(time.RFC3339),
			),
		), nil
	}

	content, err := os.ReadFile(filePath)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("读取文件失败：%w", err)
	}

	oldContent, isCrlf := fsext.ToUnixLineEndings(string(content))

	var newContent string

	if replaceAll {
		newContent = strings.ReplaceAll(oldContent, oldString, newString)
	} else {
		index := strings.Index(oldContent, oldString)
		if index == -1 {
			return oldStringNotFoundErr, nil
		}

		lastIndex := strings.LastIndex(oldContent, oldString)
		if index != lastIndex {
			return oldStringMultipleMatchesErr, nil
		}

		newContent = oldContent[:index] + newString + oldContent[index+len(oldString):]
	}

	if oldContent == newContent {
		return fantasy.NewTextErrorResponse("新内容与旧内容相同，未做更改。"), nil
	}
	_, additions, removals := diff.GenerateDiff(
		oldContent,
		newContent,
		strings.TrimPrefix(filePath, edit.workingDir),
	)

	p, err := edit.permissions.Request(
		edit.ctx,
		permission.CreatePermissionRequest{
			SessionID:   sessionID,
			Path:        fsext.PathOrPrefix(filePath, edit.workingDir),
			ToolCallID:  call.ID,
			ToolName:    EditToolName,
			Action:      "write",
			Description: fmt.Sprintf("替换文件 %s 中的内容", filePath),
			Params: EditPermissionsParams{
				FilePath:   filePath,
				OldContent: oldContent,
				NewContent: newContent,
			},
		},
	)
	if err != nil {
		return fantasy.ToolResponse{}, err
	}
	if !p {
		resp := NewPermissionDeniedResponse()
		resp = fantasy.WithResponseMetadata(resp, EditResponseMetadata{
			OldContent: oldContent,
			NewContent: newContent,
			Additions:  additions,
			Removals:   removals,
		})
		return resp, nil
	}

	if isCrlf {
		newContent, _ = fsext.ToWindowsLineEndings(newContent)
	}

	err = os.WriteFile(filePath, []byte(newContent), 0o644)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("写入文件失败：%w", err)
	}

	// Check if file exists in history
	file, err := edit.files.GetByPathAndSession(edit.ctx, filePath, sessionID)
	if err != nil {
		_, err = edit.files.Create(edit.ctx, sessionID, filePath, oldContent)
		if err != nil {
			// Log error but don't fail the operation
			return fantasy.ToolResponse{}, fmt.Errorf("创建文件历史失败：%w", err)
		}
	}
	if file.Content != oldContent {
		// User manually changed the content; store an intermediate version
		_, err = edit.files.CreateVersion(edit.ctx, sessionID, filePath, oldContent)
		if err != nil {
			slog.Debug("Error creating file history version", "error", err)
		}
	}
	// Store the new version
	_, err = edit.files.CreateVersion(edit.ctx, sessionID, filePath, newContent)
	if err != nil {
		slog.Error("Error creating file history version", "error", err)
	}

	edit.filetracker.RecordRead(edit.ctx, sessionID, filePath)

	return fantasy.WithResponseMetadata(
		fantasy.NewTextResponse("已替换文件中的内容："+filePath),
		EditResponseMetadata{
			OldContent: oldContent,
			NewContent: newContent,
			Additions:  additions,
			Removals:   removals,
		},
	), nil
}
