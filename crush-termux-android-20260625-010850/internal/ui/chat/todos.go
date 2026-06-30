package chat

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"github.com/charmbracelet/crush/internal/agent/tools"
	"github.com/charmbracelet/crush/internal/message"
	"github.com/charmbracelet/crush/internal/session"
	"github.com/charmbracelet/crush/internal/ui/styles"
	"github.com/charmbracelet/x/ansi"
)

// -----------------------------------------------------------------------------
// Todos Tool
// -----------------------------------------------------------------------------

// TodosToolMessageItem is a message item that represents a todos tool call.
type TodosToolMessageItem struct {
	*baseToolMessageItem
}

var _ ToolMessageItem = (*TodosToolMessageItem)(nil)

// NewTodosToolMessageItem creates a new [TodosToolMessageItem].
func NewTodosToolMessageItem(
	sty *styles.Styles,
	toolCall message.ToolCall,
	result *message.ToolResult,
	canceled bool,
) ToolMessageItem {
	return newBaseToolMessageItem(sty, toolCall, result, &TodosToolRenderContext{}, canceled)
}

// TodosToolRenderContext renders todos tool messages.
type TodosToolRenderContext struct{}

// RenderTool implements the [ToolRenderer] interface.
func (t *TodosToolRenderContext) RenderTool(sty *styles.Styles, width int, opts *ToolRenderOpts) string {
	cappedWidth := cappedMessageWidth(width)
	if opts.IsPending() {
		return pendingTool(sty, "待办", opts.Anim, opts.Compact)
	}

	var params tools.TodosParams
	var meta tools.TodosResponseMetadata
	var headerText string
	var body string

	// Parse params for pending state (before result is available).
	if err := json.Unmarshal([]byte(opts.ToolCall.Input), &params); err == nil {
		completedCount := 0
		inProgressTask := ""
		for _, todo := range params.Todos {
			if todo.Status == "completed" {
				completedCount++
			}
			if todo.Status == "in_progress" {
				if todo.ActiveForm != "" {
					inProgressTask = todo.ActiveForm
				} else {
					inProgressTask = todo.Content
				}
			}
		}

		// Default display from params (used when pending or no metadata).
		ratio := sty.Tool.TodoRatio.Render(fmt.Sprintf("%d/%d", completedCount, len(params.Todos)))
		headerText = ratio
		if inProgressTask != "" {
			headerText = fmt.Sprintf("%s · %s", ratio, inProgressTask)
		}

		// If we have metadata, use it for richer display.
		if opts.HasResult() && opts.Result.Metadata != "" {
			if err := json.Unmarshal([]byte(opts.Result.Metadata), &meta); err == nil {
				if meta.IsNew {
					if meta.JustStarted != "" {
						headerText = fmt.Sprintf("已创建 %d 个待办，开始第一个", meta.Total)
					} else {
						headerText = fmt.Sprintf("已创建 %d 个待办", meta.Total)
					}
					body = FormatTodosList(sty, meta.Todos, styles.ArrowRightIcon, cappedWidth)
				} else {
					// Build header based on what changed.
					hasCompleted := len(meta.JustCompleted) > 0
					hasStarted := meta.JustStarted != ""
					allCompleted := meta.Completed == meta.Total

					ratio := sty.Tool.TodoRatio.Render(fmt.Sprintf("%d/%d", meta.Completed, meta.Total))
					if hasCompleted && hasStarted {
						text := sty.Tool.TodoStatusNote.Render(fmt.Sprintf(" · 已完成 %d 个，开始下一个", len(meta.JustCompleted)))
						headerText = fmt.Sprintf("%s%s", ratio, text)
					} else if hasCompleted {
						text := sty.Tool.TodoStatusNote.Render(fmt.Sprintf(" · 已完成 %d 个", len(meta.JustCompleted)))
						if allCompleted {
							text = sty.Tool.TodoStatusNote.Render(" · 全部完成")
						}
						headerText = fmt.Sprintf("%s%s", ratio, text)
					} else if hasStarted {
						headerText = fmt.Sprintf("%s%s", ratio, sty.Tool.TodoStatusNote.Render(" · 开始任务"))
					} else {
						headerText = ratio
					}

					// Build body with details.
					if allCompleted {
						// Show all todos when all are completed, like when created.
						body = FormatTodosList(sty, meta.Todos, styles.ArrowRightIcon, cappedWidth)
					} else if meta.JustStarted != "" {
						body = sty.Tool.TodoInProgressIcon.Render(styles.ArrowRightIcon+" ") +
							sty.Tool.TodoJustStarted.Render(meta.JustStarted)
					}
				}
			}
		}
	}

	toolParams := []string{headerText}
	header := toolHeader(sty, opts.Status, "待办", cappedWidth, opts.Compact, toolParams...)
	if opts.Compact {
		return header
	}

	if earlyState, ok := toolEarlyStateContent(sty, opts, cappedWidth); ok {
		return joinToolParts(header, earlyState)
	}

	if body == "" {
		return header
	}

	return joinToolParts(header, sty.Tool.Body.Render(body))
}

// FormatTodosList formats a list of todos for display.
func FormatTodosList(sty *styles.Styles, todos []session.Todo, inProgressIcon string, width int) string {
	if len(todos) == 0 {
		return ""
	}

	sorted := make([]session.Todo, len(todos))
	copy(sorted, todos)
	sortTodos(sorted)

	var lines []string
	for _, todo := range sorted {
		var prefix string
		textStyle := sty.Tool.TodoItem

		switch todo.Status {
		case session.TodoStatusCompleted:
			prefix = sty.Tool.TodoCompletedIcon.Render(styles.TodoCompletedIcon) + " "
		case session.TodoStatusInProgress:
			prefix = sty.Tool.TodoInProgressIcon.Render(inProgressIcon + " ")
		default:
			prefix = sty.Tool.TodoPendingIcon.Render(styles.TodoPendingIcon) + " "
		}

		text := todo.Content
		if todo.Status == session.TodoStatusInProgress && todo.ActiveForm != "" {
			text = todo.ActiveForm
		}
		line := prefix + textStyle.Render(text)
		line = ansi.Truncate(line, width, "…")

		lines = append(lines, line)
	}

	return strings.Join(lines, "\n")
}

// sortTodos sorts todos by status: completed, in_progress, pending.
func sortTodos(todos []session.Todo) {
	slices.SortStableFunc(todos, func(a, b session.Todo) int {
		return statusOrder(a.Status) - statusOrder(b.Status)
	})
}

// statusOrder returns the sort order for a todo status.
func statusOrder(s session.TodoStatus) int {
	switch s {
	case session.TodoStatusCompleted:
		return 0
	case session.TodoStatusInProgress:
		return 1
	default:
		return 2
	}
}
