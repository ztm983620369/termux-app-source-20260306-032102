package model

import "charm.land/bubbles/v2/key"

type KeyMap struct {
	Editor struct {
		AddFile     key.Binding
		SendMessage key.Binding
		OpenEditor  key.Binding
		Newline     key.Binding
		AddImage    key.Binding
		PasteImage  key.Binding
		MentionFile key.Binding
		Commands    key.Binding

		// Attachments key maps
		AttachmentDeleteMode key.Binding
		Escape               key.Binding
		DeleteAllAttachments key.Binding

		// History navigation
		HistoryPrev key.Binding
		HistoryNext key.Binding
	}

	Chat struct {
		NewSession     key.Binding
		AddAttachment  key.Binding
		Cancel         key.Binding
		Tab            key.Binding
		Details        key.Binding
		TogglePills    key.Binding
		PillLeft       key.Binding
		PillRight      key.Binding
		Down           key.Binding
		Up             key.Binding
		UpDown         key.Binding
		DownOneItem    key.Binding
		UpOneItem      key.Binding
		UpDownOneItem  key.Binding
		PageDown       key.Binding
		PageUp         key.Binding
		HalfPageDown   key.Binding
		HalfPageUp     key.Binding
		Home           key.Binding
		End            key.Binding
		Copy           key.Binding
		ClearHighlight key.Binding
		Expand         key.Binding
		ScrollLeft     key.Binding
		ScrollRight    key.Binding
	}

	Initialize struct {
		Yes,
		No,
		Enter,
		Switch key.Binding
	}

	// Global key maps
	Quit       key.Binding
	Help       key.Binding
	Commands   key.Binding
	Models     key.Binding
	Suspend    key.Binding
	Sessions   key.Binding
	Tab        key.Binding
	ToggleYolo key.Binding
}

func DefaultKeyMap() KeyMap {
	km := KeyMap{
		Quit: key.NewBinding(
			key.WithKeys("ctrl+c"),
			key.WithHelp("ctrl+c", "退出"),
		),
		Help: key.NewBinding(
			key.WithKeys("ctrl+g"),
			key.WithHelp("ctrl+g", "更多"),
		),
		Commands: key.NewBinding(
			key.WithKeys("ctrl+p"),
			key.WithHelp("ctrl+p", "命令"),
		),
		Models: key.NewBinding(
			key.WithKeys("ctrl+m", "ctrl+l"),
			key.WithHelp("ctrl+l", "模型"),
		),
		Suspend: key.NewBinding(
			key.WithKeys("ctrl+z"),
			key.WithHelp("ctrl+z", "挂起"),
		),
		Sessions: key.NewBinding(
			key.WithKeys("ctrl+s"),
			key.WithHelp("ctrl+s", "会话"),
		),
		Tab: key.NewBinding(
			key.WithKeys("tab"),
			key.WithHelp("tab", "切换焦点"),
		),
		ToggleYolo: key.NewBinding(
			key.WithKeys("ctrl+y"),
			key.WithHelp("ctrl+y", "切换 YOLO"),
		),
	}

	km.Editor.AddFile = key.NewBinding(
		key.WithKeys("/"),
		key.WithHelp("/", "添加文件"),
	)
	km.Editor.SendMessage = key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "发送"),
	)
	km.Editor.OpenEditor = key.NewBinding(
		key.WithKeys("ctrl+o"),
		key.WithHelp("ctrl+o", "打开编辑器"),
	)
	km.Editor.Newline = key.NewBinding(
		key.WithKeys("shift+enter", "ctrl+j"),
		// "ctrl+j" is a common keybinding for newline in many editors. If
		// the terminal supports "shift+enter", we substitute the help tex
		// to reflect that.
		key.WithHelp("ctrl+j", "换行"),
	)
	km.Editor.AddImage = key.NewBinding(
		key.WithKeys("ctrl+f"),
		key.WithHelp("ctrl+f", "添加图片"),
	)
	km.Editor.PasteImage = key.NewBinding(
		key.WithKeys("ctrl+v", "super+v"),
		key.WithHelp("ctrl+v", "粘贴剪贴板图片"),
	)
	km.Editor.MentionFile = key.NewBinding(
		key.WithKeys("@"),
		key.WithHelp("@", "提及文件"),
	)
	km.Editor.Commands = key.NewBinding(
		key.WithKeys("/"),
		key.WithHelp("/", "命令"),
	)
	km.Editor.AttachmentDeleteMode = key.NewBinding(
		key.WithKeys("ctrl+r"),
		key.WithHelp("ctrl+r+{i}", "删除第 i 个附件"),
	)
	km.Editor.Escape = key.NewBinding(
		key.WithKeys("esc", "alt+esc"),
		key.WithHelp("esc", "取消删除模式"),
	)
	km.Editor.DeleteAllAttachments = key.NewBinding(
		key.WithKeys("r"),
		key.WithHelp("ctrl+r+r", "删除全部附件"),
	)
	km.Editor.HistoryPrev = key.NewBinding(
		key.WithKeys("up"),
	)
	km.Editor.HistoryNext = key.NewBinding(
		key.WithKeys("down"),
	)

	km.Chat.NewSession = key.NewBinding(
		key.WithKeys("ctrl+n"),
		key.WithHelp("ctrl+n", "新会话"),
	)
	km.Chat.AddAttachment = key.NewBinding(
		key.WithKeys("ctrl+f"),
		key.WithHelp("ctrl+f", "添加附件"),
	)
	km.Chat.Cancel = key.NewBinding(
		key.WithKeys("esc", "alt+esc"),
		key.WithHelp("esc", "取消"),
	)
	km.Chat.Tab = key.NewBinding(
		key.WithKeys("tab"),
		key.WithHelp("tab", "切换焦点"),
	)
	km.Chat.Details = key.NewBinding(
		key.WithKeys("ctrl+d"),
		key.WithHelp("ctrl+d", "切换详情"),
	)
	km.Chat.TogglePills = key.NewBinding(
		key.WithKeys("ctrl+t", "ctrl+space"),
		key.WithHelp("ctrl+t", "切换任务"),
	)
	km.Chat.PillLeft = key.NewBinding(
		key.WithKeys("left"),
		key.WithHelp("←/→", "切换区域"),
	)
	km.Chat.PillRight = key.NewBinding(
		key.WithKeys("right"),
		key.WithHelp("←/→", "切换区域"),
	)

	km.Chat.Down = key.NewBinding(
		key.WithKeys("down", "ctrl+j", "j"),
		key.WithHelp("↓", "下移"),
	)
	km.Chat.Up = key.NewBinding(
		key.WithKeys("up", "ctrl+k", "k"),
		key.WithHelp("↑", "上移"),
	)
	km.Chat.UpDown = key.NewBinding(
		key.WithKeys("up", "down"),
		key.WithHelp("↑↓", "滚动"),
	)
	km.Chat.UpOneItem = key.NewBinding(
		key.WithKeys("shift+up", "K"),
		key.WithHelp("shift+↑", "上移一项"),
	)
	km.Chat.DownOneItem = key.NewBinding(
		key.WithKeys("shift+down", "J"),
		key.WithHelp("shift+↓", "下移一项"),
	)
	km.Chat.UpDownOneItem = key.NewBinding(
		key.WithKeys("shift+up", "shift+down"),
		key.WithHelp("shift+↑↓", "按项滚动"),
	)
	km.Chat.HalfPageDown = key.NewBinding(
		key.WithKeys("d"),
		key.WithHelp("d", "下半页"),
	)
	km.Chat.PageDown = key.NewBinding(
		key.WithKeys("pgdown", " ", "f"),
		key.WithHelp("f/pgdn", "下一页"),
	)
	km.Chat.PageUp = key.NewBinding(
		key.WithKeys("pgup", "b"),
		key.WithHelp("b/pgup", "上一页"),
	)
	km.Chat.HalfPageUp = key.NewBinding(
		key.WithKeys("u"),
		key.WithHelp("u", "上半页"),
	)
	km.Chat.Home = key.NewBinding(
		key.WithKeys("g", "home"),
		key.WithHelp("g", "顶部"),
	)
	km.Chat.End = key.NewBinding(
		key.WithKeys("G", "end"),
		key.WithHelp("G", "底部"),
	)
	km.Chat.Copy = key.NewBinding(
		key.WithKeys("c", "y", "C", "Y"),
		key.WithHelp("c/y", "复制"),
	)
	km.Chat.ClearHighlight = key.NewBinding(
		key.WithKeys("esc", "alt+esc"),
		key.WithHelp("esc", "清除选择"),
	)
	km.Chat.Expand = key.NewBinding(
		key.WithKeys("space"),
		key.WithHelp("space", "展开/收起"),
	)
	km.Chat.ScrollLeft = key.NewBinding(
		key.WithKeys("shift+left", "H"),
		key.WithHelp("shift+←/H", "左滚"),
	)
	km.Chat.ScrollRight = key.NewBinding(
		key.WithKeys("shift+right", "L"),
		key.WithHelp("shift+→/L", "右滚"),
	)
	km.Initialize.Yes = key.NewBinding(
		key.WithKeys("y", "Y"),
		key.WithHelp("y", "是"),
	)
	km.Initialize.No = key.NewBinding(
		key.WithKeys("n", "N", "esc", "alt+esc"),
		key.WithHelp("n", "否"),
	)
	km.Initialize.Switch = key.NewBinding(
		key.WithKeys("left", "right", "tab"),
		key.WithHelp("tab", "切换"),
	)
	km.Initialize.Enter = key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "选择"),
	)

	return km
}
