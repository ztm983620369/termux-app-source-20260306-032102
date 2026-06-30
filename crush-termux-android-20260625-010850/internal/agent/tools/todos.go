package tools

import (
	"context"
	_ "embed"
	"fmt"

	"charm.land/fantasy"
	"github.com/charmbracelet/crush/internal/session"
)

//go:embed todos.md
var todosDescription string

const TodosToolName = "todos"

type TodosParams struct {
	Todos []TodoItem `json:"todos" description:"更新后的待办列表"`
}

type TodoItem struct {
	Content    string `json:"content" description:"需要完成的事项（祈使句形式）"`
	Status     string `json:"status" description:"任务状态：pending、in_progress 或 completed"`
	ActiveForm string `json:"active_form" description:"进行时形式（例如：正在运行测试）"`
}

type TodosResponseMetadata struct {
	IsNew         bool           `json:"is_new"`
	Todos         []session.Todo `json:"todos"`
	JustCompleted []string       `json:"just_completed,omitempty"`
	JustStarted   string         `json:"just_started,omitempty"`
	Completed     int            `json:"completed"`
	Total         int            `json:"total"`
}

func NewTodosTool(sessions session.Service) fantasy.AgentTool {
	return fantasy.NewAgentTool(
		TodosToolName,
		todosDescription,
		func(ctx context.Context, params TodosParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
			sessionID := GetSessionFromContext(ctx)
			if sessionID == "" {
				return fantasy.ToolResponse{}, fmt.Errorf("管理待办需要 session ID")
			}

			currentSession, err := sessions.Get(ctx, sessionID)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("获取会话失败：%w", err)
			}

			isNew := len(currentSession.Todos) == 0
			oldStatusByContent := make(map[string]session.TodoStatus)
			for _, todo := range currentSession.Todos {
				oldStatusByContent[todo.Content] = todo.Status
			}

			for _, item := range params.Todos {
				switch item.Status {
				case "pending", "in_progress", "completed":
				default:
					return fantasy.ToolResponse{}, fmt.Errorf("待办 %q 的状态无效：%q", item.Content, item.Status)
				}
			}

			todos := make([]session.Todo, len(params.Todos))
			var justCompleted []string
			var justStarted string
			completedCount := 0

			for i, item := range params.Todos {
				todos[i] = session.Todo{
					Content:    item.Content,
					Status:     session.TodoStatus(item.Status),
					ActiveForm: item.ActiveForm,
				}

				newStatus := session.TodoStatus(item.Status)
				oldStatus, existed := oldStatusByContent[item.Content]

				if newStatus == session.TodoStatusCompleted {
					completedCount++
					if existed && oldStatus != session.TodoStatusCompleted {
						justCompleted = append(justCompleted, item.Content)
					}
				}

				if newStatus == session.TodoStatusInProgress {
					if !existed || oldStatus != session.TodoStatusInProgress {
						if item.ActiveForm != "" {
							justStarted = item.ActiveForm
						} else {
							justStarted = item.Content
						}
					}
				}
			}

			currentSession.Todos = todos
			_, err = sessions.Save(ctx, currentSession)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("保存待办失败：%w", err)
			}

			response := "待办列表已更新。\n\n"

			pendingCount := 0
			inProgressCount := 0

			for _, todo := range todos {
				switch todo.Status {
				case session.TodoStatusPending:
					pendingCount++
				case session.TodoStatusInProgress:
					inProgressCount++
				}
			}

			response += fmt.Sprintf("状态：%d 个待处理，%d 个进行中，%d 个已完成\n",
				pendingCount, inProgressCount, completedCount)

			response += "待办已成功修改。请继续使用待办列表跟踪进度，并在适用时推进当前任务。"

			metadata := TodosResponseMetadata{
				IsNew:         isNew,
				Todos:         todos,
				JustCompleted: justCompleted,
				JustStarted:   justStarted,
				Completed:     completedCount,
				Total:         len(todos),
			}

			return fantasy.WithResponseMetadata(fantasy.NewTextResponse(response), metadata), nil
		},
	)
}
