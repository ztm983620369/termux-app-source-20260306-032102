package termuxrestore

import (
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTrackerClearWritesDetachedTombstone(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-1")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "0")

	tracker := New("/work", "/data")
	if !tracker.Enabled() {
		t.Fatal("tracker should be enabled under Termux-style environment")
	}

	if err := tracker.Update("session-1", "Crush one"); err != nil {
		t.Fatalf("Update() error = %v", err)
	}
	if err := tracker.Clear(); err != nil {
		t.Fatalf("Clear() error = %v", err)
	}

	state := readStateForTest(t, home)
	if len(state.Instances) != 1 {
		t.Fatalf("instances len = %d, want 1", len(state.Instances))
	}
	record := state.Instances[0]
	if record.InstanceID != "instance-1" {
		t.Fatalf("instance id = %q", record.InstanceID)
	}
	if record.State != stateDetached {
		t.Fatalf("state = %q, want %q", record.State, stateDetached)
	}
	if record.SessionID != "session-1" {
		t.Fatalf("session id = %q", record.SessionID)
	}
	if record.WorkingDirectory != "/work" {
		t.Fatalf("cwd = %q", record.WorkingDirectory)
	}
	if record.DataDirectory != "/data" {
		t.Fatalf("data dir = %q", record.DataDirectory)
	}
	if record.Title != "Crush one" {
		t.Fatalf("title = %q", record.Title)
	}
	if record.Order != 0 {
		t.Fatalf("order = %d, want 0", record.Order)
	}
}

func TestTrackerClearPreservesClosedTombstone(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-closed")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "0")

	tracker := New("/work", "/data")
	if err := tracker.Update("session-closed", "Closed by Android"); err != nil {
		t.Fatalf("Update() error = %v", err)
	}
	if err := withLockedState(func(state *State) error {
		state.Instances[0].State = stateClosed
		return nil
	}); err != nil {
		t.Fatalf("withLockedState() error = %v", err)
	}
	if err := tracker.Clear(); err != nil {
		t.Fatalf("Clear() error = %v", err)
	}

	state := readStateForTest(t, home)
	if len(state.Instances) != 1 {
		t.Fatalf("instances len = %d, want 1", len(state.Instances))
	}
	if state.Instances[0].State != stateClosed {
		t.Fatalf("state = %q, want %q", state.Instances[0].State, stateClosed)
	}
}

func TestTrackerUpdateReopensDetachedRecord(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-2")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "0")

	tracker := New("/work", "/data")
	if err := tracker.Update("session-1", "First"); err != nil {
		t.Fatalf("Update() error = %v", err)
	}
	if err := tracker.Clear(); err != nil {
		t.Fatalf("Clear() error = %v", err)
	}
	if err := tracker.Update("session-2", "Second"); err != nil {
		t.Fatalf("second Update() error = %v", err)
	}

	state := readStateForTest(t, home)
	if len(state.Instances) != 1 {
		t.Fatalf("instances len = %d, want 1", len(state.Instances))
	}
	record := state.Instances[0]
	if record.State != stateActive {
		t.Fatalf("state = %q, want %q", record.State, stateActive)
	}
	if record.SessionID != "session-2" {
		t.Fatalf("session id = %q", record.SessionID)
	}
	if record.Title != "Second" {
		t.Fatalf("title = %q", record.Title)
	}
	if record.Order != 0 {
		t.Fatalf("order = %d, want stable 0", record.Order)
	}
}

func TestTrackerUpdatePreservesClosedTombstone(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-closed-update")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "0")

	tracker := New("/work", "/data")
	if err := tracker.Update("session-1", "First"); err != nil {
		t.Fatalf("Update() error = %v", err)
	}
	if err := withLockedState(func(state *State) error {
		state.Instances[0].State = stateClosed
		return nil
	}); err != nil {
		t.Fatalf("withLockedState() error = %v", err)
	}
	if err := tracker.Update("session-2", "Second"); err != nil {
		t.Fatalf("second Update() error = %v", err)
	}

	state := readStateForTest(t, home)
	if len(state.Instances) != 1 {
		t.Fatalf("instances len = %d, want 1", len(state.Instances))
	}
	record := state.Instances[0]
	if record.State != stateClosed {
		t.Fatalf("state = %q, want %q", record.State, stateClosed)
	}
	if record.SessionID != "session-1" {
		t.Fatalf("session id = %q, want preserved session-1", record.SessionID)
	}
	if record.Title != "First" {
		t.Fatalf("title = %q, want preserved First", record.Title)
	}
}

func TestTrackerNativeRestoreEmitsHostControl(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-native")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "1")

	output := captureStdoutForTest(t, func() {
		tracker := New("/work", "/data")
		if !tracker.Enabled() {
			t.Fatal("tracker should be enabled under Termux-style environment")
		}
		if err := tracker.Update("session-native", "Native title"); err != nil {
			t.Fatalf("Update() error = %v", err)
		}
		if err := tracker.Clear(); err != nil {
			t.Fatalf("Clear() error = %v", err)
		}
	})

	if !strings.Contains(output, "\x1b]8900;crush-restore;") {
		t.Fatalf("native output missing host control prefix: %q", output)
	}
	for _, want := range []string{
		`"event":"update"`,
		`"event":"clear"`,
		`"instance_id":"instance-native"`,
		`"session_id":"session-native"`,
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("native output missing %s in %q", want, output)
		}
	}
	if _, err := os.Stat(filepath.Join(home, ".crush", "termux-active-sessions.json")); !os.IsNotExist(err) {
		t.Fatalf("native mode should not write bridge file, stat err = %v", err)
	}
}

func TestTrackerNativeRestoreAnnouncesBlankInstance(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-blank")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "1")

	output := captureStdoutForTest(t, func() {
		tracker := New("/blank", "/data")
		if err := tracker.Announce("Crush"); err != nil {
			t.Fatalf("Announce() error = %v", err)
		}
		if err := tracker.Update("session-created", "Created"); err != nil {
			t.Fatalf("Update() error = %v", err)
		}
	})

	for _, want := range []string{
		`"event":"update"`,
		`"instance_id":"instance-blank"`,
		`"session_id":""`,
		`"session_id":"session-created"`,
	} {
		if !strings.Contains(output, want) {
			t.Fatalf("native output missing %s in %q", want, output)
		}
	}
	if _, err := os.Stat(filepath.Join(home, ".crush", "termux-active-sessions.json")); !os.IsNotExist(err) {
		t.Fatalf("native blank announce should not write bridge file, stat err = %v", err)
	}
}

func TestTrackerFileModeIgnoresBlankInstance(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("PREFIX", "/data/data/com.termux/files/usr")
	t.Setenv("CRUSH_TERMUX_INSTANCE_ID", "instance-file-blank")
	t.Setenv("CRUSH_TERMUX_NATIVE_RESTORE", "0")

	tracker := New("/blank", "/data")
	if err := tracker.Announce("Crush"); err != nil {
		t.Fatalf("Announce() error = %v", err)
	}
	if _, err := os.Stat(filepath.Join(home, ".crush", "termux-active-sessions.json")); !os.IsNotExist(err) {
		t.Fatalf("file mode blank announce should not write bridge file, stat err = %v", err)
	}
}

func captureStdoutForTest(t *testing.T, fn func()) string {
	t.Helper()
	old := os.Stdout
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("Pipe() error = %v", err)
	}
	os.Stdout = writer
	defer func() {
		os.Stdout = old
	}()

	fn()
	if err := writer.Close(); err != nil {
		t.Fatalf("Close() error = %v", err)
	}
	data, err := io.ReadAll(reader)
	if err != nil {
		t.Fatalf("ReadAll() error = %v", err)
	}
	return string(data)
}

func readStateForTest(t *testing.T, home string) State {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(home, ".crush", "termux-active-sessions.json"))
	if err != nil {
		t.Fatalf("ReadFile() error = %v", err)
	}
	var state State
	if err := json.Unmarshal(data, &state); err != nil {
		t.Fatalf("Unmarshal() error = %v", err)
	}
	return state
}
