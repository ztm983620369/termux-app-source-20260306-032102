# Python LSP（电脑端）说明

## 服务端结构

- `server.js`: TCP 代理，监听 `0.0.0.0:4389`，每个连接启动一个 `pylsp` 子进程（stdio）。
- `workdir/`: 代理侧工作目录。Android 侧 `file:///storage/...` 文件会映射到这里，便于 `pylsp` 做符号分析等。

## 推荐依赖

`pylsp` 的很多能力由插件提供（例如诊断、格式化）。建议安装：

```bash
python -m pip install -U -r requirements.txt
```

`requirements.txt` 默认包含：

- `python-lsp-server[all]`
- `python-lsp-black`

## 常见现象

- `formatting` 返回 `null`：通常表示未安装格式化插件（`autopep8`/`yapf`/`black` 等）或当前文本无需 edits
- `diagnosticProvider = null`：表示服务端不支持 pull diagnostics，需要依赖 `textDocument/publishDiagnostics` 推送 + 对应 lint 插件

