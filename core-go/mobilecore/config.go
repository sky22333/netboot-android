package mobilecore

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
)

const (
	ModeProxy = "proxy"
	ModeDHCP  = "dhcp"
)

// scriptNames are served from config.ipxeScript instead of the device root.
var scriptNames = []string{"autoexec.ipxe", "boot.ipxe"}

func isScriptName(name string) bool {
	for _, script := range scriptNames {
		if name == script {
			return true
		}
	}
	return false
}

type config struct {
	ListenIP     string     `json:"listenIp"`
	AdvertiseIP  string     `json:"advertiseIp"`
	Mode         string     `json:"mode"`
	Root         string     `json:"root"`
	HTTPPort     int        `json:"httpPort"`
	BootFile     string     `json:"bootFile"`
	IPXEScript   string     `json:"ipxeScript"`
	MaxTransfers int        `json:"maxTransfers"`
	DHCP         dhcpConfig `json:"dhcp"`
}

type dhcpConfig struct {
	PoolStart    string `json:"poolStart"`
	PoolEnd      string `json:"poolEnd"`
	SubnetMask   string `json:"subnetMask"`
	Router       string `json:"router"`
	DNS          string `json:"dns"`
	LeaseSeconds int    `json:"leaseSeconds"`
}

func parseConfig(raw string) (config, error) {
	var cfg config
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return cfg, fmt.Errorf("decode config: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return cfg, errors.New("decode config: trailing data")
	}
	if net.ParseIP(cfg.ListenIP).To4() == nil {
		return cfg, errors.New("listenIp must be an IPv4 address")
	}
	if net.ParseIP(cfg.AdvertiseIP).To4() == nil {
		return cfg, errors.New("advertiseIp must be an IPv4 address")
	}
	if cfg.Mode != ModeProxy && cfg.Mode != ModeDHCP {
		return cfg, errors.New("mode must be proxy or dhcp")
	}
	if cfg.HTTPPort < 1024 || cfg.HTTPPort > 65535 {
		return cfg, errors.New("httpPort must be between 1024 and 65535")
	}
	if cfg.Root == "" {
		return cfg, errors.New("root is required")
	}
	absRoot, err := filepath.Abs(cfg.Root)
	if err != nil {
		return cfg, fmt.Errorf("resolve root: %w", err)
	}
	info, err := os.Stat(absRoot)
	if err != nil {
		return cfg, fmt.Errorf("inspect root: %w", err)
	}
	if !info.IsDir() {
		return cfg, errors.New("root must be a directory")
	}
	cfg.Root = absRoot
	if cfg.MaxTransfers == 0 {
		cfg.MaxTransfers = 16
	}
	if cfg.MaxTransfers < 1 || cfg.MaxTransfers > 64 {
		return cfg, errors.New("maxTransfers must be between 1 and 64")
	}
	// Left empty on purpose: bootFileFor resolves it from the client architecture.
	if cfg.IPXEScript == "" {
		cfg.IPXEScript = defaultIPXEScript(cfg)
	}
	if cfg.Mode == ModeDHCP {
		if err := validateDHCP(&cfg.DHCP, cfg.AdvertiseIP); err != nil {
			return cfg, err
		}
	}
	return cfg, nil
}

func validateDHCP(cfg *dhcpConfig, advertiseIP string) error {
	start := net.ParseIP(cfg.PoolStart).To4()
	end := net.ParseIP(cfg.PoolEnd).To4()
	mask := net.ParseIP(cfg.SubnetMask).To4()
	if start == nil || end == nil || mask == nil {
		return errors.New("DHCP poolStart, poolEnd and subnetMask must be IPv4 addresses")
	}
	if ipToUint(start) > ipToUint(end) {
		return errors.New("DHCP poolStart must not be greater than poolEnd")
	}
	if ipToUint(end)-ipToUint(start) > 4095 {
		return errors.New("DHCP pool must contain at most 4096 addresses")
	}
	if (cfg.Router != "" && net.ParseIP(cfg.Router).To4() == nil) || (cfg.DNS != "" && net.ParseIP(cfg.DNS).To4() == nil) {
		return errors.New("DHCP router and dns must be IPv4 addresses")
	}
	if cfg.LeaseSeconds == 0 {
		cfg.LeaseSeconds = 86400
	}
	if cfg.LeaseSeconds < 60 || cfg.LeaseSeconds > 604800 {
		return errors.New("DHCP leaseSeconds must be between 60 and 604800")
	}
	return nil
}

func defaultIPXEScript(cfg config) string {
	return fmt.Sprintf("#!ipxe\necho NetBoot is running at http://%s:%d/\nshell\n", cfg.AdvertiseIP, cfg.HTTPPort)
}
