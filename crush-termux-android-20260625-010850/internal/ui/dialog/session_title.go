package dialog

import (
	"strings"

	"charm.land/bubbles/v2/help"
	"charm.land/bubbles/v2/key"
	"charm.land/bubbles/v2/textinput"
	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/crush/internal/ui/common"
	uv "github.com/charmbracelet/ultraviolet"
)

const (
	SessionTitleID             = "session-title"
	sessionTitleDialogMaxWidth = 58
)

type SessionTitle struct {
	com       *common.Common
	sessionID string
	input     textinput.Model
	help      help.Model

	keyMap struct {
		Confirm key.Binding
		Close   key.Binding
	}
}

var _ Dialog = (*SessionTitle)(nil)

func NewSessionTitle(com *common.Common, sessionID, currentTitle string) *SessionTitle {
	s := &SessionTitle{
		com:       com,
		sessionID: sessionID,
	}

	s.help = help.New()
	s.help.Styles = com.Styles.DialogHelpStyles()

	s.input = textinput.New()
	s.input.SetVirtualCursor(false)
	s.input.Placeholder = "输入会话标题"
	s.input.SetStyles(com.Styles.TextInput)
	s.input.SetValue(currentTitle)
	s.input.CursorEnd()
	s.input.Focus()

	s.keyMap.Confirm = key.NewBinding(
		key.WithKeys("enter"),
		key.WithHelp("enter", "确认"),
	)
	s.keyMap.Close = CloseKey

	return s
}

func (s *SessionTitle) ID() string {
	return SessionTitleID
}

func (s *SessionTitle) HandleMsg(msg tea.Msg) Action {
	switch msg := msg.(type) {
	case tea.KeyPressMsg:
		switch {
		case key.Matches(msg, s.keyMap.Close):
			return ActionClose{}
		case key.Matches(msg, s.keyMap.Confirm):
			title := strings.TrimSpace(s.input.Value())
			if title == "" {
				return nil
			}
			return ActionRenameSession{
				SessionID: s.sessionID,
				Title:     title,
			}
		default:
			var cmd tea.Cmd
			s.input, cmd = s.input.Update(msg)
			return ActionCmd{Cmd: cmd}
		}
	}
	return nil
}

func (s *SessionTitle) Cursor() *tea.Cursor {
	return InputCursor(s.com.Styles, s.input.Cursor())
}

func (s *SessionTitle) Draw(scr uv.Screen, area uv.Rectangle) *tea.Cursor {
	t := s.com.Styles
	width := max(0, min(sessionTitleDialogMaxWidth, area.Dx()))
	innerWidth := width - t.Dialog.View.GetHorizontalFrameSize()
	s.input.SetWidth(max(0, innerWidth-t.Dialog.InputPrompt.GetHorizontalFrameSize()-1))

	rc := NewRenderContext(t, width)
	rc.Title = "重命名会话"
	rc.AddPart(t.Dialog.InputPrompt.Render(s.input.View()))
	rc.Help = s.help.View(s)

	view := rc.Render()
	cur := s.Cursor()
	DrawCenterCursor(scr, area, view, cur)
	return cur
}

func (s *SessionTitle) ShortHelp() []key.Binding {
	return []key.Binding{s.keyMap.Confirm, s.keyMap.Close}
}

func (s *SessionTitle) FullHelp() [][]key.Binding {
	return [][]key.Binding{{s.keyMap.Confirm, s.keyMap.Close}}
}
