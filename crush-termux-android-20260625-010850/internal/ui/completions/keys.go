package completions

import (
	"charm.land/bubbles/v2/key"
)

// KeyMap defines the key bindings for the completions component.
type KeyMap struct {
	Down,
	Up,
	Select,
	Cancel key.Binding
	DownInsert,
	UpInsert key.Binding
}

// DefaultKeyMap returns the default key bindings for completions.
func DefaultKeyMap() KeyMap {
	return KeyMap{
		Down: key.NewBinding(
			key.WithKeys("down"),
			key.WithHelp("down", "下移"),
		),
		Up: key.NewBinding(
			key.WithKeys("up"),
			key.WithHelp("up", "上移"),
		),
		Select: key.NewBinding(
			key.WithKeys("enter", "tab", "ctrl+y"),
			key.WithHelp("enter", "选择"),
		),
		Cancel: key.NewBinding(
			key.WithKeys("esc", "alt+esc"),
			key.WithHelp("esc", "取消"),
		),
		DownInsert: key.NewBinding(
			key.WithKeys("ctrl+n"),
			key.WithHelp("ctrl+n", "插入下一个"),
		),
		UpInsert: key.NewBinding(
			key.WithKeys("ctrl+p"),
			key.WithHelp("ctrl+p", "插入上一个"),
		),
	}
}

// KeyBindings returns all key bindings as a slice.
func (k KeyMap) KeyBindings() []key.Binding {
	return []key.Binding{
		k.Down,
		k.Up,
		k.Select,
		k.Cancel,
	}
}

// FullHelp returns the full help for the key bindings.
func (k KeyMap) FullHelp() [][]key.Binding {
	m := [][]key.Binding{}
	slice := k.KeyBindings()
	for i := 0; i < len(slice); i += 4 {
		end := min(i+4, len(slice))
		m = append(m, slice[i:end])
	}
	return m
}

// ShortHelp returns the short help for the key bindings.
func (k KeyMap) ShortHelp() []key.Binding {
	return []key.Binding{
		k.Up,
		k.Down,
	}
}
