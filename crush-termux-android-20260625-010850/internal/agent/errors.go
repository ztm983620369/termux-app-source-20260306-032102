package agent

import "errors"

var (
	ErrRequestCancelled = errors.New("用户已取消请求")
	ErrSessionBusy      = errors.New("会话正在处理另一个请求")
	ErrEmptyPrompt      = errors.New("提示词为空")
	ErrSessionMissing   = errors.New("缺少 session id")
)
