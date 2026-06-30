package termuxrestore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/charmbracelet/crush/internal/lock"
	"github.com/google/uuid"
)

const (
	stateVersion  = 1
	lockTimeout   = 800 * time.Millisecond
	stateActive   = "active"
	stateDetached = "detached"
	stateClosed   = "closed"
	hostCommand   = "crush-restore"
)

type Tracker struct {
	instanceID       string
	workingDirectory string
	dataDirectory    string
	enabled          bool
	native           bool
	lastSessionID    string
	lastTitle        string
	lastWrote        bool
}

type State struct {
	Version   int      `json:"version"`
	UpdatedAt int64    `json:"updated_at"`
	Instances []Record `json:"instances"`
}

type Record struct {
	InstanceID       string `json:"instance_id"`
	SessionID        string `json:"session_id"`
	WorkingDirectory string `json:"cwd"`
	DataDirectory    string `json:"data_dir"`
	Title            string `json:"title"`
	State            string `json:"state"`
	PID              int    `json:"pid"`
	Order            int    `json:"order"`
	UpdatedAt        int64  `json:"updated_at"`
}

func New(workingDirectory, dataDirectory string) *Tracker {
	enabled := isTermuxRuntime()
	t := &Tracker{
		instanceID:       strings.TrimSpace(os.Getenv("CRUSH_TERMUX_INSTANCE_ID")),
		workingDirectory: strings.TrimSpace(workingDirectory),
		dataDirectory:    strings.TrimSpace(dataDirectory),
		enabled:          enabled,
		native:           nativeRestoreEnabled(enabled),
	}
	if t.instanceID == "" {
		t.instanceID = uuid.NewString()
	}
	return t
}

func (t *Tracker) Enabled() bool {
	return t != nil && t.enabled
}

func (t *Tracker) Announce(title string) error {
	return t.Update("", title)
}

func (t *Tracker) Update(sessionID, title string) error {
	if !t.Enabled() {
		return nil
	}
	sessionID = strings.TrimSpace(sessionID)
	if sessionID == "" && !t.native {
		return nil
	}
	title = strings.TrimSpace(title)
	if t.lastWrote && t.lastSessionID == sessionID && t.lastTitle == title {
		return nil
	}

	now := time.Now().Unix()
	record := Record{
		InstanceID:       t.instanceID,
		SessionID:        sessionID,
		WorkingDirectory: t.workingDirectory,
		DataDirectory:    t.dataDirectory,
		Title:            title,
		State:            stateActive,
		PID:              os.Getpid(),
		Order:            -1,
		UpdatedAt:        now,
	}
	if t.native {
		if err := t.emitNative("update", record); err != nil {
			return err
		}
		t.lastSessionID = sessionID
		t.lastTitle = title
		t.lastWrote = true
		return nil
	}

	err := withLockedState(func(state *State) error {
		record.Order = nextOrder(state.Instances)

		out := state.Instances[:0]
		preserveClosed := false
		for _, existing := range state.Instances {
			if existing.InstanceID == t.instanceID {
				if existing.State == stateClosed {
					record = existing
					preserveClosed = true
					continue
				}
				if existing.Order >= 0 {
					record.Order = existing.Order
				}
				continue
			}
			out = append(out, existing)
		}
		if preserveClosed {
			state.Instances = append(out, record)
			return nil
		}
		state.Instances = append(out, record)
		return nil
	})
	if err == nil {
		t.lastSessionID = sessionID
		t.lastTitle = title
		t.lastWrote = true
	}
	return err
}

func (t *Tracker) Clear() error {
	if !t.Enabled() {
		return nil
	}
	now := time.Now().Unix()
	tombstone := Record{
		InstanceID:       t.instanceID,
		SessionID:        t.lastSessionID,
		WorkingDirectory: t.workingDirectory,
		DataDirectory:    t.dataDirectory,
		Title:            t.lastTitle,
		State:            stateDetached,
		PID:              os.Getpid(),
		Order:            -1,
		UpdatedAt:        now,
	}
	if t.native {
		if err := t.emitNative("clear", tombstone); err != nil {
			return err
		}
		t.lastWrote = false
		return nil
	}

	return withLockedState(func(state *State) error {
		tombstone.Order = nextOrder(state.Instances)
		out := state.Instances[:0]
		preserveClosed := false
		for _, existing := range state.Instances {
			if existing.InstanceID == t.instanceID {
				if existing.State == stateClosed {
					tombstone = existing
					preserveClosed = true
					continue
				}
				if existing.Order >= 0 {
					tombstone.Order = existing.Order
				}
				if tombstone.SessionID == "" {
					tombstone.SessionID = existing.SessionID
				}
				if tombstone.WorkingDirectory == "" {
					tombstone.WorkingDirectory = existing.WorkingDirectory
				}
				if tombstone.DataDirectory == "" {
					tombstone.DataDirectory = existing.DataDirectory
				}
				if tombstone.Title == "" {
					tombstone.Title = existing.Title
				}
				continue
			}
			out = append(out, existing)
		}
		if preserveClosed {
			state.Instances = append(out, tombstone)
			t.lastWrote = false
			return nil
		}
		state.Instances = append(out, tombstone)
		t.lastWrote = false
		return nil
	})
}

func (t *Tracker) emitNative(event string, record Record) error {
	payload := map[string]any{
		"event":       event,
		"instance_id": record.InstanceID,
		"session_id":  record.SessionID,
		"cwd":         record.WorkingDirectory,
		"data_dir":    record.DataDirectory,
		"title":       record.Title,
		"state":       record.State,
		"pid":         record.PID,
		"updated_at":  record.UpdatedAt,
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(os.Stdout, "\x1b]8900;%s;%s\a", hostCommand, data)
	return err
}

func withLockedState(mutator func(*State) error) error {
	path, err := statePath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), lockTimeout)
	defer cancel()

	release, err := lock.File(ctx, path+".lock")
	if err != nil {
		return err
	}
	defer release()

	state, err := readState(path)
	if err != nil {
		return err
	}
	if err := mutator(&state); err != nil {
		return err
	}
	state.Version = stateVersion
	state.UpdatedAt = time.Now().Unix()
	return writeState(path, state)
}

func readState(path string) (State, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return State{Version: stateVersion}, nil
	}
	if err != nil {
		return State{}, err
	}
	if len(strings.TrimSpace(string(data))) == 0 {
		return State{Version: stateVersion}, nil
	}

	var state State
	if err := json.Unmarshal(data, &state); err != nil {
		slog.Debug("Ignoring corrupt Termux restore state", "path", path, "error", err)
		return State{Version: stateVersion}, nil
	}
	if state.Instances == nil {
		state.Instances = []Record{}
	}
	return state, nil
}

func writeState(path string, state State) error {
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func statePath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil || strings.TrimSpace(home) == "" {
		home = os.Getenv("HOME")
	}
	if strings.TrimSpace(home) == "" {
		return "", errors.New("home directory is not available")
	}
	return filepath.Join(home, ".crush", "termux-active-sessions.json"), nil
}

func isTermuxRuntime() bool {
	if runtime.GOOS == "android" {
		return true
	}
	for _, value := range []string{
		os.Getenv("TERMUX_VERSION"),
		os.Getenv("PREFIX"),
		os.Getenv("HOME"),
	} {
		if strings.Contains(value, "/com.termux/") ||
			strings.Contains(value, "/com.termux.") {
			return true
		}
	}
	return false
}

func nativeRestoreEnabled(termuxRuntime bool) bool {
	value := strings.TrimSpace(os.Getenv("CRUSH_TERMUX_NATIVE_RESTORE"))
	switch strings.ToLower(value) {
	case "0", "false", "no", "off":
		return false
	case "1", "true", "yes", "on":
		return termuxRuntime
	default:
		return termuxRuntime
	}
}

func nextOrder(records []Record) int {
	maxOrder := -1
	for _, record := range records {
		if record.Order > maxOrder && record.Order < 1000000 {
			maxOrder = record.Order
		}
	}
	return maxOrder + 1
}
