package mobilecore

import (
	"encoding/json"
	"strings"
	"sync"
	"time"
)

type Listener interface {
	OnEvent(eventJSON string)
}

type event struct {
	Timestamp int64             `json:"timestamp"`
	Level     string            `json:"level"`
	Source    string            `json:"source"`
	Code      string            `json:"code"`
	Arguments map[string]string `json:"arguments,omitempty"`
}

type eventSink struct {
	mu       sync.Mutex
	listener Listener
}

func (s *eventSink) emit(level, source, code string, arguments map[string]string) {
	if s == nil {
		return
	}
	s.mu.Lock()
	listener := s.listener
	s.mu.Unlock()
	if listener == nil {
		return
	}
	raw, err := json.Marshal(event{
		Timestamp: time.Now().UnixMilli(),
		Level:     level,
		Source:    source,
		Code:      code,
		Arguments: cleanEventArguments(arguments),
	})
	if err == nil {
		listener.OnEvent(string(raw))
	}
}

func cleanEventArguments(arguments map[string]string) map[string]string {
	if len(arguments) == 0 {
		return nil
	}
	cleaned := make(map[string]string, len(arguments))
	for key, value := range arguments {
		value = strings.NewReplacer("\r", " ", "\n", " ", "\x00", " ").Replace(value)
		if len(value) > 512 {
			value = value[:512]
		}
		cleaned[key] = value
	}
	return cleaned
}

func (s *eventSink) clear() {
	if s == nil {
		return
	}
	s.mu.Lock()
	s.listener = nil
	s.mu.Unlock()
}
