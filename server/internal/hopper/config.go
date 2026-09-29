package hopper

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	DefaultListenHost       = "127.0.0.1"
	DefaultListenPort       = 7400
	DefaultOverlay          = "10.64.0.0/24"
	DefaultTUN              = "hopper0"
	DefaultKeyPath          = "~/.hopper/id_ed25519"
	DefaultClientLeaseTTL   = 3600
	DefaultClientPoolSuffix = ".2/24"
)

const (
	ViaIngress = "ingress"
	ViaNext    = "next"
	ViaTun     = "tun"
)

// HopSSH is SSH reachability to another hop (used as upstream for reverse dials).
type HopSSH struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	User       string `json:"user"`
	KeyPath    string `json:"key_path"`
	TunnelPort int    `json:"tunnel_port"`
}

// NextHop is kept as an alias for older configs that still use "next".
type NextHop = HopSSH

type Route struct {
	Dest string `json:"dest"`
	Via  string `json:"via"`
}

type Config struct {
	ChainID           string  `json:"chain_id"`
	Addr              string  `json:"addr"`
	ClientPool        string  `json:"client_pool"`
	ClientLeaseTTLSec int     `json:"client_lease_ttl_sec"`
	Overlay           string  `json:"overlay"`
	TUN               string  `json:"tun"`
	ListenHost        string  `json:"listen_host"`
	ListenPort        int     `json:"listen_port"`
	Upstream *HopSSH `json:"upstream"`
	// Downstream is the next hop; entry/relay may dial it when sticky says so
	// (e.g. opposite chain order when only this host can reach the peer).
	Downstream *HopSSH `json:"downstream"`
	// Next is legacy forward-dial config; migrated to Downstream + AwaitReverse.
	Next         *HopSSH `json:"next"`
	AwaitReverse bool    `json:"await_reverse"`
	Routes       []Route `json:"routes"`
	NAT          bool    `json:"nat"`
	ChainDir     string  `json:"-"`
}

func LoadConfig(path string) (Config, error) {
	cfg := Config{
		Overlay:           DefaultOverlay,
		TUN:               DefaultTUN,
		ListenHost:        DefaultListenHost,
		ListenPort:        DefaultListenPort,
		ClientLeaseTTLSec: DefaultClientLeaseTTL,
	}

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return cfg.withDefaults(), nil
		}
		return cfg, err
	}

	if err := json.Unmarshal(data, &cfg); err != nil {
		return cfg, fmt.Errorf("parse %s: %w", path, err)
	}

	cfg.ChainDir = filepath.Dir(path)
	return cfg.withDefaults(), nil
}

func (c Config) withDefaults() Config {
	if c.Overlay == "" {
		c.Overlay = DefaultOverlay
	}
	if c.TUN == "" {
		c.TUN = DefaultTUN
	}
	if c.ListenHost == "" {
		c.ListenHost = DefaultListenHost
	}
	if c.ListenPort == 0 {
		c.ListenPort = DefaultListenPort
	}
	if c.ClientLeaseTTLSec <= 0 {
		c.ClientLeaseTTLSec = DefaultClientLeaseTTL
	}
	if c.Upstream == nil && c.Downstream == nil && c.Next != nil && strings.TrimSpace(c.Next.Host) != "" {
		// Legacy "next" meant forward-dial target (downstream peer).
		c.Downstream = c.Next
		c.AwaitReverse = true
		c.Next = nil
	}
	c.Upstream = normalizeHopSSH(c.Upstream)
	c.Downstream = normalizeHopSSH(c.Downstream)
	c.Next = normalizeHopSSH(c.Next)
	if !c.HasUpstream() && !c.AwaitReverse && !c.NAT {
		c.NAT = true
	}
	return c
}

func normalizeHopSSH(h *HopSSH) *HopSSH {
	if h == nil {
		return nil
	}
	if strings.TrimSpace(h.Host) == "" {
		return nil
	}
	if h.Port == 0 {
		h.Port = 22
	}
	if h.TunnelPort == 0 {
		h.TunnelPort = DefaultListenPort
	}
	if h.KeyPath == "" {
		h.KeyPath = DefaultKeyPath
	}
	h.KeyPath = expandHome(h.KeyPath)
	return h
}

func (c Config) HasUpstream() bool {
	return c.Upstream != nil && strings.TrimSpace(c.Upstream.Host) != ""
}

func (c Config) HasDownstream() bool {
	return c.Downstream != nil && strings.TrimSpace(c.Downstream.Host) != ""
}

func (c Config) HasNext() bool {
	return c.AwaitReverse
}

func (c Config) Mode() string {
	if c.AwaitReverse {
		return "relay"
	}
	return "exit"
}

func (c Config) IsEntry() bool {
	return c.ClientPool != ""
}

func (c Config) EffectiveRoutes() []Route {
	if len(c.Routes) > 0 {
		return c.Routes
	}

	routes := []Route{{Dest: c.Overlay, Via: ViaTun}}
	if c.ClientPool != "" {
		routes = append(routes, Route{Dest: c.ClientPool, Via: ViaIngress})
	}
	if c.Addr != "" {
		routes = append(routes, Route{Dest: c.Addr + "/32", Via: ViaTun})
	}
	if c.AwaitReverse {
		routes = append(routes, Route{Dest: "0.0.0.0/0", Via: ViaNext})
	} else {
		routes = append(routes, Route{Dest: "0.0.0.0/0", Via: ViaTun})
	}
	return routes
}

func expandHome(path string) string {
	if strings.HasPrefix(path, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return path
		}
		return filepath.Join(home, path[2:])
	}
	return path
}
