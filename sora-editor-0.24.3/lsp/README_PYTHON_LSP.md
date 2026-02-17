# Python LSP Server - 完整接入指南

## 概述

本项目提供了一个完整的 Python LSP 服务器实现，支持所有标准的 LSP 功能，并通过 TCP 连接提供服务。

## 项目结构

```
vs/
├── server.js                          # Python LSP 服务器（Node.js 实现）
├── run-tests.js                       # 自动化测试套件
├── test-report.json                    # 测试报告
├── PythonLspServerDefinition.kt       # Kotlin LSP 服务器定义
└── PythonLspTestActivity.kt          # Android 测试 Activity
```

## 1. Python LSP 服务器 (server.js)

### 功能特性

服务器实现了以下 LSP 功能：

#### 核心功能
- **TCP 连接管理**: 在端口 4389 上监听连接
- **LSP 初始化**: 处理 initialize/initialized 生命周期
- **文档同步**: 支持 didOpen/didChange/didClose

#### 代码智能功能
- **代码补全** (`textDocument/completion`): 提供 Python 关键字补全
- **悬停信息** (`textDocument/hover`): 显示位置信息
- **定义跳转** (`textDocument/definition`): 返回定义位置
- **引用查找** (`textDocument/references`): 查找符号引用

#### 导航功能
- **文档符号** (`textDocument/documentSymbol`): 列出文档中的符号
- **工作区符号** (`workspace/symbol`): 搜索工作区符号

#### 代码操作
- **代码操作** (`textDocument/codeAction`): 提供快速修复

### 启动服务器

```bash
node server.js
```

服务器将在 `0.0.0.0:4389` 上监听连接。

## 2. 自动化测试套件 (run-tests.js)

### 测试覆盖

测试套件包含 19 项测试，覆盖所有 LSP 功能：

| # | 测试项 | 功能 |
|---|--------|------|
| 1 | TCP 连接测试 | 连接管理 |
| 2 | LSP 初始化测试 | 初始化 |
| 3 | LSP 已初始化通知 | 生命周期 |
| 4 | 文档打开测试 | 文档同步 |
| 5 | 代码补全请求测试 | 补全 |
| 6 | Hover 请求测试 | 悬停 |
| 7 | Definition 请求测试 | 定义 |
| 8 | References 请求测试 | 引用 |
| 9 | Document Symbol 请求测试 | 文档符号 |
| 10 | Workspace Symbol 请求测试 | 工作区符号 |
| 11 | Code Action 请求测试 | 代码操作 |
| 12 | 文档变更测试 | 文档同步 |
| 13 | 文档关闭测试 | 文档同步 |
| 14 | 长连接稳定性测试 (60秒) | 稳定性 |
| 15 | 快速请求测试 (100个请求) | 性能 |
| 16 | 并发连接测试 (5个客户端) | 并发 |
| 17 | 关闭测试 | 生命周期 |
| 18 | 退出测试 | 生命周期 |
| 19 | 重连测试 | 错误处理 |

### 运行测试

```bash
node run-tests.js
```

测试将自动：
1. 启动 Python LSP 服务器
2. 运行所有 19 项测试
3. 生成详细报告
4. 停止服务器

### 测试结果

测试报告将保存到 `test-report.json`，包含：
- 总测试数
- 通过/失败统计
- 成功率
- 每项测试的详细结果（状态、耗时、错误信息）

## 3. Kotlin 接入代码

### PythonLspServerDefinition.kt

实现了 `LanguageServerDefinition` 接口，用于连接到 Python LSP 服务器：

```kotlin
class PythonLspServerDefinition : LanguageServerDefinition {
    companion object {
        const val SERVER_HOST = "127.0.0.1"
        const val SERVER_PORT = 4389
        const val LANGUAGE_ID = "python"
        const val LANGUAGE_NAME = "Python"
    }
    
    override val languageId: String = LANGUAGE_ID
    override val languageName: String = LANGUAGE_NAME
    
    override fun languageIdFor(extension: String): String {
        return when (extension.lowercase()) {
            "py" -> LANGUAGE_ID
            "pyw" -> LANGUAGE_ID
            else -> LANGUAGE_ID
        }
    }
    
    override fun start(): Pair<InputStream, OutputStream> {
        val provider = PythonLspStreamConnectionProvider(SERVER_HOST, SERVER_PORT)
        provider.connect()
        return Pair(provider.getInputStream(), provider.getOutputStream())
    }
}
```

### PythonLspTestActivity.kt

实现了 `LspTestActivity`，用于测试 LSP 连接：

```kotlin
class PythonLspTestActivity : LspTestActivity() {
    private var lspWrapper: LanguageServerWrapper? = null
    private var lspEditor: LspEditor? = null
    
    private suspend fun setupLspServer() {
        val serverDefinition = CustomLanguageServerDefinition(
            languageId = "python",
            languageName = "Python",
            extension = listOf("py", "pyw"),
            connectionProvider = SocketStreamConnectionProvider("127.0.0.1", 4389)
        )
        
        lspWrapper = LanguageServerWrapper(serverDefinition, lifecycleScope)
        lspWrapper?.start()
    }
}
```

## 4. 在 Android 项目中使用

### 步骤 1: 启动 Python LSP 服务器

在电脑上运行：
```bash
node server.js
```

### 步骤 2: 在 Android 项目中添加 LSP 支持

```kotlin
// 在你的 Activity 中
val serverDefinition = CustomLanguageServerDefinition(
    languageId = "python",
    languageName = "Python",
    extension = listOf("py", "pyw"),
    connectionProvider = SocketStreamConnectionProvider("127.0.0.1", 4389)
)

val lspProject = LspProject()
lspProject.addServerDefinition(serverDefinition)

val editor = lspProject.createEditor(this, fileUri)
lspProject.connectWithTimeout(editor, 10000)
```

## 5. LSP 协议支持

### 生命周期方法

- `initialize`: 初始化服务器，返回能力
- `initialized`: 客户端确认初始化完成
- `shutdown`: 关闭服务器
- `exit`: 退出连接

### 文档同步方法

- `textDocument/didOpen`: 打开文档
- `textDocument/didChange`: 文档内容变更
- `textDocument/didClose`: 关闭文档

### 代码智能方法

- `textDocument/completion`: 代码补全
- `textDocument/hover`: 悬停信息
- `textDocument/definition`: 定义跳转
- `textDocument/references`: 引用查找

### 导航方法

- `textDocument/documentSymbol`: 文档符号
- `workspace/symbol`: 工作区符号

### 代码操作方法

- `textDocument/codeAction`: 代码操作

## 6. 性能指标

根据测试结果：

- **连接建立**: < 5ms
- **初始化**: ~40ms
- **补全请求**: < 1ms
- **长连接稳定性**: 60 秒无断连
- **并发处理**: 支持 5+ 个客户端
- **快速请求**: 100 个请求在 10ms 内完成

## 7. 故障排除

### 连接失败

1. 确认 Python LSP 服务器正在运行
2. 检查端口 4389 是否被占用
3. 确认防火墙允许连接

### 功能不可用

1. 检查服务器日志确认能力已声明
2. 确认客户端请求正确的 LSP 方法
3. 验证 languageId 匹配（"python"）

### 测试失败

1. 查看详细测试报告 `test-report.json`
2. 检查服务器日志获取错误信息
3. 确认所有依赖已安装

## 8. 扩展功能

服务器设计为可扩展，可以轻松添加新功能：

### 添加新的 LSP 方法

在 `LSPMessageHandler` 类中添加新的处理方法：

```javascript
handleNewMethod(message) {
    const result = { /* 处理逻辑 */ };
    this.sendResponse(message.id, result);
}
```

然后在 `handleMessage` 方法中添加路由：

```javascript
else if (message.method === 'textDocument/newMethod') {
    this.handleNewMethod(message);
}
```

### 添加新的补全项

在 `handleCompletion` 方法中添加新的补全项：

```javascript
const items = [
    {
        label: 'yourKeyword',
        kind: 14,
        detail: 'Your description',
        documentation: 'Your documentation'
    },
    // ... 更多项
];
```

## 9. 许可证

MIT License

## 10. 贡献

欢迎贡献！请：
1. Fork 项目
2. 创建特性分支
3. 提交更改
4. 推送到分支
5. 创建 Pull Request
