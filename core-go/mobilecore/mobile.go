package mobilecore

import (
	"encoding/json"
	"errors"
	"sync"
	"time"
)

var global = struct {
	sync.Mutex
	server *server
}{}

func ValidateConfig(configJSON string) error {
	_, err := parseConfig(configJSON)
	return err
}

func Start(configJSON string, listener Listener) error {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return err
	}
	global.Lock()
	defer global.Unlock()
	if global.server != nil {
		return errors.New("netboot is already running")
	}
	srv := newServer(cfg, listener)
	if err := srv.start(); err != nil {
		return err
	}
	global.server = srv
	return nil
}

func Stop() error {
	global.Lock()
	srv := global.server
	global.server = nil
	global.Unlock()
	if srv == nil {
		return nil
	}
	return srv.stop(5 * time.Second)
}

func StatusJSON() string {
	global.Lock()
	srv := global.server
	global.Unlock()
	status := statusSnapshot{Running: srv != nil}
	if srv != nil {
		status.Mode = srv.cfg.Mode
		status.ListenIP = srv.cfg.ListenIP
		status.HTTPPort = srv.cfg.HTTPPort
		status.StartedAt = srv.startedAt.UnixMilli()
	}
	raw, _ := json.Marshal(status)
	return string(raw)
}

type statusSnapshot struct {
	Running   bool   `json:"running"`
	Mode      string `json:"mode,omitempty"`
	ListenIP  string `json:"listenIp,omitempty"`
	HTTPPort  int    `json:"httpPort,omitempty"`
	StartedAt int64  `json:"startedAt,omitempty"`
}
