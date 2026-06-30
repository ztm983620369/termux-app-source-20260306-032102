# Crush Termux Android 复刻版

这是从 Charmbracelet Crush 源码复刻出来的完整源码仓库，并保留了当前本地修改。

## 当前改动

- 基于上游 Crush 源码。
- 增加 Termux/Android 环境的 TUI 鼠标模式默认策略：在 Android 或 Termux 下默认关闭 mouse tracking，避免 Termux 软键盘无法弹出。
- 可以通过 CRUSH_MOUSE=cell 恢复点击/高亮鼠标模式，也可以用 CRUSH_MOUSE=all 或 CRUSH_MOUSE=off 显式控制。

## Termux/Android arm64 构建

在 Linux x86_64 机器上交叉编译：

```bash
cd crush-termux-android
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X github.com/charmbracelet/crush/internal/version.Version=termux-android" -o crush-android-arm64 .
```

复制到 Termux 后运行：

```bash
chmod +x ./crush-android-arm64
./crush-android-arm64 --yolo
```

## 配置说明

Crush 使用 JSON 配置，不使用 Codex 的 TOML 配置。Termux 中通常读取：

```text
~/.crush/crush.json
```

OpenAI Responses 兼容代理示例：

```json
{
  "providers": {
    "ugf": {
      "type": "openai",
      "base_url": "https://ai.ugf.cc/v1",
      "api_key": "YOUR_KEY_HERE",
      "models": [{
        "id": "gpt-5.5",
        "name": "GPT-5.5",
        "context_window": 400000,
        "default_max_tokens": 50000,
        "can_reason": true
      }]
    }
  },
  "models": {
    "large": {
      "provider": "ugf",
      "model": "gpt-5.5",
      "reasoning_effort": "xhigh"
    },
    "small": {
      "provider": "ugf",
      "model": "gpt-5.5",
      "reasoning_effort": "xhigh"
    }
  }
}
```

## 完整性

源码、go.mod、go.sum、docs、internal、scripts、GitHub workflow 等项目文件均保留。重新 clone 后可直接按 Go 项目方式构建。
