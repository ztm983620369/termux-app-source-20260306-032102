package tools

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"strings"
	"time"

	"charm.land/fantasy"
)

type SourcegraphParams struct {
	Query         string `json:"query" description:"Sourcegraph 搜索查询"`
	Count         int    `json:"count,omitempty" description:"可选返回结果数量（默认 10，最大 20）"`
	ContextWindow int    `json:"context_window,omitempty" description:"返回匹配位置周围的上下文（默认 10 行）"`
	Timeout       int    `json:"timeout,omitempty" description:"可选超时时间，单位秒（最大 120）"`
}

type SourcegraphResponseMetadata struct {
	NumberOfMatches int  `json:"number_of_matches"`
	Truncated       bool `json:"truncated"`
}

const SourcegraphToolName = "sourcegraph"

//go:embed sourcegraph.md.tpl
var sourcegraphDescriptionTmpl []byte

var sourcegraphDescriptionTpl = template.Must(
	template.New("sourcegraphDescription").
		Parse(string(sourcegraphDescriptionTmpl)),
)

type sourcegraphDescriptionData struct {
	MaxResults int
}

func sourcegraphDescription() string {
	return renderTemplate(sourcegraphDescriptionTpl, sourcegraphDescriptionData{
		MaxResults: 20,
	})
}

func NewSourcegraphTool(client *http.Client) fantasy.AgentTool {
	if client == nil {
		transport := http.DefaultTransport.(*http.Transport).Clone()
		transport.MaxIdleConns = 100
		transport.MaxIdleConnsPerHost = 10
		transport.IdleConnTimeout = 90 * time.Second

		client = &http.Client{
			Timeout:   30 * time.Second,
			Transport: transport,
		}
	}
	return fantasy.NewParallelAgentTool(
		SourcegraphToolName,
		sourcegraphDescription(),
		func(ctx context.Context, params SourcegraphParams, call fantasy.ToolCall) (fantasy.ToolResponse, error) {
			if params.Query == "" {
				return fantasy.NewTextErrorResponse("查询参数为必填项"), nil
			}

			if params.Count <= 0 {
				params.Count = 10
			} else if params.Count > 20 {
				params.Count = 20 // Limit to 20 results
			}

			if params.ContextWindow <= 0 {
				params.ContextWindow = 10 // Default context window
			}

			// Handle timeout with context
			requestCtx := ctx
			if params.Timeout > 0 {
				maxTimeout := 120 // 2 minutes
				if params.Timeout > maxTimeout {
					params.Timeout = maxTimeout
				}
				var cancel context.CancelFunc
				requestCtx, cancel = context.WithTimeout(ctx, time.Duration(params.Timeout)*time.Second)
				defer cancel()
			}

			type graphqlRequest struct {
				Query     string `json:"query"`
				Variables struct {
					Query string `json:"query"`
				} `json:"variables"`
			}

			request := graphqlRequest{
				Query: "query Search($query: String!) { search(query: $query, version: V2, patternType: keyword ) { results { matchCount, limitHit, resultCount, approximateResultCount, missing { name }, timedout { name }, indexUnavailable, results { __typename, ... on FileMatch { repository { name }, file { path, url, content }, lineMatches { preview, lineNumber, offsetAndLengths } } } } } }",
			}
			request.Variables.Query = params.Query

			graphqlQueryBytes, err := json.Marshal(request)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("序列化 GraphQL 请求失败：%w", err)
			}
			graphqlQuery := string(graphqlQueryBytes)

			req, err := http.NewRequestWithContext(
				requestCtx,
				"POST",
				"https://sourcegraph.com/.api/graphql",
				bytes.NewBuffer([]byte(graphqlQuery)),
			)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("创建请求失败：%w", err)
			}

			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("User-Agent", "crush/1.0")

			resp, err := client.Do(req)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("抓取 URL 失败：%w", err)
			}
			defer resp.Body.Close()

			if resp.StatusCode != http.StatusOK {
				body, _ := io.ReadAll(resp.Body)
				if len(body) > 0 {
					return fantasy.NewTextErrorResponse(fmt.Sprintf("请求失败，状态码：%d，响应：%s", resp.StatusCode, string(body))), nil
				}

				return fantasy.NewTextErrorResponse(fmt.Sprintf("请求失败，状态码：%d", resp.StatusCode)), nil
			}
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("读取响应正文失败：%w", err)
			}

			var result map[string]any
			if err = json.Unmarshal(body, &result); err != nil {
				return fantasy.ToolResponse{}, fmt.Errorf("解析响应失败：%w", err)
			}

			formattedResults, err := formatSourcegraphResults(result, params.ContextWindow)
			if err != nil {
				return fantasy.NewTextErrorResponse("格式化结果失败：" + err.Error()), nil
			}

			return fantasy.NewTextResponse(formattedResults), nil
		},
	)
}

func formatSourcegraphResults(result map[string]any, contextWindow int) (string, error) {
	var buffer strings.Builder

	if errors, ok := result["errors"].([]any); ok && len(errors) > 0 {
		buffer.WriteString("## Sourcegraph API 错误\n\n")
		for _, err := range errors {
			if errMap, ok := err.(map[string]any); ok {
				if message, ok := errMap["message"].(string); ok {
					fmt.Fprintf(&buffer, "- %s\n", message)
				}
			}
		}
		return buffer.String(), nil
	}

	data, ok := result["data"].(map[string]any)
	if !ok {
		return "", fmt.Errorf("响应格式无效：缺少 data 字段")
	}

	search, ok := data["search"].(map[string]any)
	if !ok {
		return "", fmt.Errorf("响应格式无效：缺少 search 字段")
	}

	searchResults, ok := search["results"].(map[string]any)
	if !ok {
		return "", fmt.Errorf("响应格式无效：缺少 results 字段")
	}

	matchCount, _ := searchResults["matchCount"].(float64)
	resultCount, _ := searchResults["resultCount"].(float64)
	limitHit, _ := searchResults["limitHit"].(bool)

	buffer.WriteString("# Sourcegraph 搜索结果\n\n")
	fmt.Fprintf(&buffer, "在 %d 条结果中找到 %d 处匹配\n", int(resultCount), int(matchCount))

	if limitHit {
		buffer.WriteString("（已达到结果上限，请尝试更具体的查询）\n")
	}

	buffer.WriteString("\n")

	results, ok := searchResults["results"].([]any)
	if !ok || len(results) == 0 {
		buffer.WriteString("没有找到结果。请尝试其他查询。\n")
		return buffer.String(), nil
	}

	maxResults := 10
	if len(results) > maxResults {
		results = results[:maxResults]
	}

	for i, res := range results {
		fileMatch, ok := res.(map[string]any)
		if !ok {
			continue
		}

		typeName, _ := fileMatch["__typename"].(string)
		if typeName != "FileMatch" {
			continue
		}

		repo, _ := fileMatch["repository"].(map[string]any)
		file, _ := fileMatch["file"].(map[string]any)
		lineMatches, _ := fileMatch["lineMatches"].([]any)

		if repo == nil || file == nil {
			continue
		}

		repoName, _ := repo["name"].(string)
		filePath, _ := file["path"].(string)
		fileURL, _ := file["url"].(string)
		fileContent, _ := file["content"].(string)

		fmt.Fprintf(&buffer, "## 结果 %d：%s/%s\n\n", i+1, repoName, filePath)

		if fileURL != "" {
			fmt.Fprintf(&buffer, "URL: %s\n\n", fileURL)
		}

		if len(lineMatches) > 0 {
			for _, lm := range lineMatches {
				lineMatch, ok := lm.(map[string]any)
				if !ok {
					continue
				}

				lineNumber, _ := lineMatch["lineNumber"].(float64)
				preview, _ := lineMatch["preview"].(string)

				if fileContent != "" {
					lines := strings.Split(fileContent, "\n")

					buffer.WriteString("```\n")

					startLine := max(1, int(lineNumber)-contextWindow)

					for j := startLine - 1; j < int(lineNumber)-1 && j < len(lines); j++ {
						if j >= 0 {
							fmt.Fprintf(&buffer, "%d| %s\n", j+1, lines[j])
						}
					}

					fmt.Fprintf(&buffer, "%d|  %s\n", int(lineNumber), preview)

					endLine := int(lineNumber) + contextWindow

					for j := int(lineNumber); j < endLine && j < len(lines); j++ {
						if j < len(lines) {
							fmt.Fprintf(&buffer, "%d| %s\n", j+1, lines[j])
						}
					}

					buffer.WriteString("```\n\n")
				} else {
					buffer.WriteString("```\n")
					fmt.Fprintf(&buffer, "%d| %s\n", int(lineNumber), preview)
					buffer.WriteString("```\n\n")
				}
			}
		}
	}

	return buffer.String(), nil
}
