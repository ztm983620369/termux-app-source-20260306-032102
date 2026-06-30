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

type MultiEditOperation struct {
	OldString  string `json:"old_string" description:"要替换的文本"`
	NewString  string `json:"new_string" description:"替换后的文本"`
	ReplaceAll bool   `json:"replace_all,omitempty" description:"替换 old_string 的所有出现位置（默认 false）"`
}

type MultiEditParams struct {
	FilePath string               `json:"file_path" description:"要修改的文件绝对路径"`
	Edits    []MultiEditOperation `json:"edits" description:"要按顺序在文件上执行的编辑操作数组"`
}

type MultiEditPermissionsParams struct {
	FilePath   string `json:"file_path"`
	OldContent string `json:"old_content,omitempty"`
	NewContent string `json:"new_content,omitempty"`
}

type FailedEdit struct {
	Index int                `json:"index"`
	Error string             `json:"error"`
	Edit  MultiEditOperation `json:"edit"`
}

type MultiEditResponseMetadata struct {
	Additions    int          `json:"additions"`
	Removals     int          `json:"removals"`
	OldContent   string       `json:"old_content,omitempty"`
	NewContent   string       `json:"new_content,omitempty"`
	EditsApplied int          `json:"edits_applied"`
	EditsFailed  []FailedEdit `json:"edits_failed,omitempty"`
}

const MultiEditToolName = "multiedit"

//go:embed multiedit.md
var multieditDescription string

func NewMultiEditTool(
	lspManager *lsp.Manager,
	permissions permission.Service,
	files history.Service,
	filetracker filetracker.Service,
	workingDir string,
) fantasy.AgentTool {
	return fantasy.NewAgentTool(
		MultiEditToolName,
		multieditDescription,
		func(ctx context.Context, params MultiEditParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
			if params.FilePath == "" {
				return fantasy.NewTextErrorResponse("文件路径为必填项"), nil
			}

			if len(params.Edits) == 0 {
				return fantasy.NewTextErrorResponse("至少需要一个编辑操作"), nil
			}

			params.FilePath = filepathext.SmartJoin(workingDir, params.FilePath)

			// Validate all edits before applying any
			if err := validateEdits(params.Edits); err != nil {
				return fantasy.NewTextErrorResponse(err.Error()), nil
			}

			var response fantasy.ToolResponse
			var err error

			editCtx := editContext{ctx, permissions, files, filetracker, workingDir}
			// Handle file creation case (first edit has empty old_string)
			if len(params.Edits) > 0 && params.Edits[0].OldString == "" {
				response, err = processMultiEditWithCreation(editCtx, params, call)
			} else {
				response, err = processMultiEditExistingFile(editCtx, params, call)
			}

			if err != nil {
				return response, err
			}

			if response.IsError {
				return response, nil
			}

			// Notify LSP clients about the change
			notifyLSPs(ctx, lspManager, params.FilePath)

			// Wait for LSP diagnostics and add them to the response
			text := fmt.Sprintf("<result>\n%s\n</result>\n", response.Content)
			text += getDiagnostics(params.FilePath, lspManager)
			response.Content = text
			return response, nil
		},
	)
}

func validateEdits(edits []MultiEditOperation) error {
	for i, edit := range edits {
		// Only the first edit can have empty old_string (for file creation)
		if i > 0 && edit.OldString == "" {
			return fmt.Errorf("第 %d 个编辑：只有第一个编辑可以使用空的待替换文本（用于创建文件）", i+1)
		}
	}
	return nil
}

func processMultiEditWithCreation(edit editContext, params MultiEditParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
	// First edit creates the file
	firstEdit := params.Edits[0]
	if firstEdit.OldString != "" {
		return fantasy.NewTextErrorResponse("创建文件时，第一个编辑的待替换文本必须为空"), nil
	}

	// Check if file already exists
	if _, err := os.Stat(params.FilePath); err == nil {
		return fantasy.NewTextErrorResponse(fmt.Sprintf("文件已存在：%s", params.FilePath)), nil
	} else if !os.IsNotExist(err) {
		return fantasy.ToolResponse{}, fmt.Errorf("访问文件失败：%w", err)
	}

	// Create parent directories
	dir := filepath.Dir(params.FilePath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("创建父目录失败：%w", err)
	}

	// Start with the content from the first edit
	currentContent := firstEdit.NewString

	// Apply remaining edits to the content, tracking failures
	var failedEdits []FailedEdit
	for i := 1; i < len(params.Edits); i++ {
		edit := params.Edits[i]
		newContent, err := applyEditToContent(currentContent, edit)
		if err != nil {
			failedEdits = append(failedEdits, FailedEdit{
				Index: i + 1,
				Error: err.Error(),
				Edit:  edit,
			})
			continue
		}
		currentContent = newContent
	}

	// Get session and message IDs
	sessionID := GetSessionFromContext(edit.ctx)
	if sessionID == "" {
		return fantasy.ToolResponse{}, fmt.Errorf("创建新文件需要 session ID")
	}

	// Check permissions
	_, additions, removals := diff.GenerateDiff("", currentContent, strings.TrimPrefix(params.FilePath, edit.workingDir))

	editsApplied := len(params.Edits) - len(failedEdits)
	var description string
	if len(failedEdits) > 0 {
		description = fmt.Sprintf("创建文件 %s，并应用 %d/%d 个编辑（%d 个失败）", params.FilePath, editsApplied, len(params.Edits), len(failedEdits))
	} else {
		description = fmt.Sprintf("创建文件 %s，并应用 %d 个编辑", params.FilePath, editsApplied)
	}
	p, err := edit.permissions.Request(edit.ctx, permission.CreatePermissionRequest{
		SessionID:   sessionID,
		Path:        fsext.PathOrPrefix(params.FilePath, edit.workingDir),
		ToolCallID:  call.ID,
		ToolName:    MultiEditToolName,
		Action:      "write",
		Description: description,
		Params: MultiEditPermissionsParams{
			FilePath:   params.FilePath,
			OldContent: "",
			NewContent: currentContent,
		},
	})
	if err != nil {
		return fantasy.ToolResponse{}, err
	}
	if !p {
		resp := NewPermissionDeniedResponse()
		resp = fantasy.WithResponseMetadata(resp, MultiEditResponseMetadata{
			OldContent:   "",
			NewContent:   currentContent,
			Additions:    additions,
			Removals:     removals,
			EditsApplied: editsApplied,
			EditsFailed:  failedEdits,
		})
		return resp, nil
	}

	// Write the file
	err = os.WriteFile(params.FilePath, []byte(currentContent), 0o644)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("写入文件失败：%w", err)
	}

	// Update file history
	_, err = edit.files.Create(edit.ctx, sessionID, params.FilePath, "")
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("创建文件历史失败：%w", err)
	}

	_, err = edit.files.CreateVersion(edit.ctx, sessionID, params.FilePath, currentContent)
	if err != nil {
		slog.Error("Error creating file history version", "error", err)
	}

	edit.filetracker.RecordRead(edit.ctx, sessionID, params.FilePath)

	var message string
	if len(failedEdits) > 0 {
		message = fmt.Sprintf("文件已创建，并应用 %d/%d 个编辑：%s（%d 个编辑失败）", editsApplied, len(params.Edits), params.FilePath, len(failedEdits))
	} else {
		message = fmt.Sprintf("文件已创建，并应用 %d 个编辑：%s", len(params.Edits), params.FilePath)
	}

	return fantasy.WithResponseMetadata(
		fantasy.NewTextResponse(message),
		MultiEditResponseMetadata{
			OldContent:   "",
			NewContent:   currentContent,
			Additions:    additions,
			Removals:     removals,
			EditsApplied: editsApplied,
			EditsFailed:  failedEdits,
		},
	), nil
}

func processMultiEditExistingFile(edit editContext, params MultiEditParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
	// Validate file exists and is readable
	fileInfo, err := os.Stat(params.FilePath)
	if err != nil {
		if os.IsNotExist(err) {
			return fantasy.NewTextErrorResponse(fmt.Sprintf("找不到文件：%s", params.FilePath)), nil
		}
		return fantasy.ToolResponse{}, fmt.Errorf("访问文件失败：%w", err)
	}

	if fileInfo.IsDir() {
		return fantasy.NewTextErrorResponse(fmt.Sprintf("路径是目录，不是文件：%s", params.FilePath)), nil
	}

	sessionID := GetSessionFromContext(edit.ctx)
	if sessionID == "" {
		return fantasy.ToolResponse{}, fmt.Errorf("编辑文件需要 session ID")
	}

	// Check if file was read before editing
	lastRead := edit.filetracker.LastReadTime(edit.ctx, sessionID, params.FilePath)
	if lastRead.IsZero() {
		return fantasy.NewTextErrorResponse("编辑文件前必须先读取文件。请先使用 View 工具"), nil
	}

	// Check if file was modified since last read.
	modTime := fileInfo.ModTime().Truncate(time.Second)
	if modTime.After(lastRead) {
		return fantasy.NewTextErrorResponse(
			fmt.Sprintf(
				"文件 %s 在上次读取后已被修改（修改时间：%s，读取时间：%s）",
				params.FilePath, modTime.Format(time.RFC3339), lastRead.Format(time.RFC3339),
			),
		), nil
	}

	// Read current file content
	content, err := os.ReadFile(params.FilePath)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("读取文件失败：%w", err)
	}

	oldContent, isCrlf := fsext.ToUnixLineEndings(string(content))
	currentContent := oldContent

	// Apply all edits sequentially, tracking failures
	var failedEdits []FailedEdit
	for i, edit := range params.Edits {
		newContent, err := applyEditToContent(currentContent, edit)
		if err != nil {
			failedEdits = append(failedEdits, FailedEdit{
				Index: i + 1,
				Error: err.Error(),
				Edit:  edit,
			})
			continue
		}
		currentContent = newContent
	}

	// Check if content actually changed
	if oldContent == currentContent {
		// If we have failed edits, report them
		if len(failedEdits) > 0 {
			return fantasy.WithResponseMetadata(
				fantasy.NewTextErrorResponse(fmt.Sprintf("未做更改，%d 个编辑全部失败", len(failedEdits))),
				MultiEditResponseMetadata{
					EditsApplied: 0,
					EditsFailed:  failedEdits,
				},
			), nil
		}
		return fantasy.NewTextErrorResponse("未做更改，所有编辑产生的内容都与原内容相同"), nil
	}

	// Generate diff and check permissions
	_, additions, removals := diff.GenerateDiff(oldContent, currentContent, strings.TrimPrefix(params.FilePath, edit.workingDir))

	editsApplied := len(params.Edits) - len(failedEdits)
	var description string
	if len(failedEdits) > 0 {
		description = fmt.Sprintf("对文件 %s 应用 %d/%d 个编辑（%d 个失败）", params.FilePath, editsApplied, len(params.Edits), len(failedEdits))
	} else {
		description = fmt.Sprintf("对文件 %s 应用 %d 个编辑", params.FilePath, editsApplied)
	}
	p, err := edit.permissions.Request(edit.ctx, permission.CreatePermissionRequest{
		SessionID:   sessionID,
		Path:        fsext.PathOrPrefix(params.FilePath, edit.workingDir),
		ToolCallID:  call.ID,
		ToolName:    MultiEditToolName,
		Action:      "write",
		Description: description,
		Params: MultiEditPermissionsParams{
			FilePath:   params.FilePath,
			OldContent: oldContent,
			NewContent: currentContent,
		},
	})
	if err != nil {
		return fantasy.ToolResponse{}, err
	}
	if !p {
		resp := NewPermissionDeniedResponse()
		resp = fantasy.WithResponseMetadata(resp, MultiEditResponseMetadata{
			OldContent:   oldContent,
			NewContent:   currentContent,
			Additions:    additions,
			Removals:     removals,
			EditsApplied: editsApplied,
			EditsFailed:  failedEdits,
		})
		return resp, nil
	}

	if isCrlf {
		currentContent, _ = fsext.ToWindowsLineEndings(currentContent)
	}

	// Write the updated content
	err = os.WriteFile(params.FilePath, []byte(currentContent), 0o644)
	if err != nil {
		return fantasy.ToolResponse{}, fmt.Errorf("写入文件失败：%w", err)
	}

	// Update file history
	file, err := edit.files.GetByPathAndSession(edit.ctx, params.FilePath, sessionID)
	if err != nil {
		_, err = edit.files.Create(edit.ctx, sessionID, params.FilePath, oldContent)
		if err != nil {
			return fantasy.ToolResponse{}, fmt.Errorf("创建文件历史失败：%w", err)
		}
	}
	if file.Content != oldContent {
		// User manually changed the content, store an intermediate version
		_, err = edit.files.CreateVersion(edit.ctx, sessionID, params.FilePath, oldContent)
		if err != nil {
			slog.Error("Error creating file history version", "error", err)
		}
	}

	// Store the new version
	_, err = edit.files.CreateVersion(edit.ctx, sessionID, params.FilePath, currentContent)
	if err != nil {
		slog.Error("Error creating file history version", "error", err)
	}

	edit.filetracker.RecordRead(edit.ctx, sessionID, params.FilePath)

	var message string
	if len(failedEdits) > 0 {
		message = fmt.Sprintf("已对文件应用 %d/%d 个编辑：%s（%d 个编辑失败）", editsApplied, len(params.Edits), params.FilePath, len(failedEdits))
	} else {
		message = fmt.Sprintf("已对文件应用 %d 个编辑：%s", len(params.Edits), params.FilePath)
	}

	return fantasy.WithResponseMetadata(
		fantasy.NewTextResponse(message),
		MultiEditResponseMetadata{
			OldContent:   oldContent,
			NewContent:   currentContent,
			Additions:    additions,
			Removals:     removals,
			EditsApplied: editsApplied,
			EditsFailed:  failedEdits,
		},
	), nil
}

func applyEditToContent(content string, edit MultiEditOperation) (string, error) {
	if edit.OldString == "" && edit.NewString == "" {
		return content, nil
	}

	if edit.OldString == "" {
		return "", fmt.Errorf("内容替换时待替换文本不能为空")
	}

	var newContent string
	var replacementCount int

	if edit.ReplaceAll {
		newContent = strings.ReplaceAll(content, edit.OldString, edit.NewString)
		replacementCount = strings.Count(content, edit.OldString)
		if replacementCount == 0 {
			return "", fmt.Errorf("内容中找不到待替换文本。请确认内容完全匹配，包括空白字符和换行")
		}
	} else {
		index := strings.Index(content, edit.OldString)
		if index == -1 {
			return "", fmt.Errorf("内容中找不到待替换文本。请确认内容完全匹配，包括空白字符和换行")
		}

		lastIndex := strings.LastIndex(content, edit.OldString)
		if index != lastIndex {
			return "", fmt.Errorf("待替换文本在内容中出现多次。请提供更多上下文确保唯一匹配，或启用全部替换")
		}

		newContent = content[:index] + edit.NewString + content[index+len(edit.OldString):]
		replacementCount = 1
	}

	return newContent, nil
}
