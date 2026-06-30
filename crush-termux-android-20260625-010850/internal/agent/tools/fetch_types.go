package tools

// AgenticFetchToolName is the name of the agentic fetch tool.
const AgenticFetchToolName = "agentic_fetch"

// WebFetchToolName is the name of the web_fetch tool.
const WebFetchToolName = "web_fetch"

// WebSearchToolName is the name of the web_search tool for sub-agents.
const WebSearchToolName = "web_search"

// LargeContentThreshold is the size threshold for saving content to a file.
const LargeContentThreshold = 50000 // 50KB

// AgenticFetchParams defines the parameters for the agentic fetch tool.
type AgenticFetchParams struct {
	URL    string `json:"url,omitempty" description:"要抓取内容的 URL（可选；不提供时智能体会搜索网页）"`
	Prompt string `json:"prompt" description:"描述要查找或提取哪些信息的提示词"`
}

// AgenticFetchPermissionsParams defines the permission parameters for the agentic fetch tool.
type AgenticFetchPermissionsParams struct {
	URL    string `json:"url,omitempty"`
	Prompt string `json:"prompt"`
}

// WebFetchParams defines the parameters for the web_fetch tool.
type WebFetchParams struct {
	URL string `json:"url" description:"要抓取内容的 URL"`
}

// WebSearchParams defines the parameters for the web_search tool.
type WebSearchParams struct {
	Query      string `json:"query" description:"用于在网页上查找信息的搜索查询"`
	MaxResults int    `json:"max_results,omitempty" description:"最大返回结果数量（默认 10，最大 20）"`
}

// FetchParams defines the parameters for the simple fetch tool.
type FetchParams struct {
	URL     string `json:"url" description:"要抓取内容的 URL"`
	Format  string `json:"format" description:"返回内容格式（text、markdown 或 html）"`
	Timeout int    `json:"timeout,omitempty" description:"可选超时时间，单位秒（最大 120）"`
}

// FetchPermissionsParams defines the permission parameters for the simple fetch tool.
type FetchPermissionsParams struct {
	URL     string `json:"url"`
	Format  string `json:"format"`
	Timeout int    `json:"timeout,omitempty"`
}
