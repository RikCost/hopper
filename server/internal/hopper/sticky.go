package hopper

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/aengix/hopper/server/internal/log"
)

// linkSticky remembers which host successfully dialed which peer so opposite
// logical chains reuse that direction instead of flipping dialers.
type linkSticky struct {
	// OutboundTo is set when this host dialed peer successfully.
	OutboundTo string `json:"outbound_to,omitempty"`
	// InboundFrom is set when peer dialed this host successfully.
	InboundFrom string `json:"inbound_from,omitempty"`
}

var stickyMu sync.Mutex

func stickyPath(peerHost string) string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = "/root"
	}
	safe := strings.ReplaceAll(strings.TrimSpace(peerHost), "/", "_")
	return filepath.Join(home, ".hopper", "link-sticky", safe+".json")
}

func loadSticky(peerHost string) linkSticky {
	stickyMu.Lock()
	defer stickyMu.Unlock()
	data, err := os.ReadFile(stickyPath(peerHost))
	if err != nil {
		return linkSticky{}
	}
	var s linkSticky
	_ = json.Unmarshal(data, &s)
	return s
}

func saveSticky(peerHost string, s linkSticky) {
	stickyMu.Lock()
	defer stickyMu.Unlock()
	dir := filepath.Dir(stickyPath(peerHost))
	_ = os.MkdirAll(dir, 0o700)
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return
	}
	if err := os.WriteFile(stickyPath(peerHost), append(data, '\n'), 0o600); err != nil {
		log.Warnf("sticky write %s: %v", peerHost, err)
	}
}

func rememberOutbound(peerHost string) {
	s := loadSticky(peerHost)
	s.OutboundTo = peerHost
	// Clear contradictory inbound preference for this peer.
	if s.InboundFrom == peerHost {
		s.InboundFrom = ""
	}
	saveSticky(peerHost, s)
	log.Infof("link sticky: prefer dialing %s", peerHost)
}

func rememberInbound(peerHost string) {
	if strings.TrimSpace(peerHost) == "" {
		return
	}
	s := loadSticky(peerHost)
	s.InboundFrom = peerHost
	if s.OutboundTo == peerHost {
		s.OutboundTo = ""
	}
	saveSticky(peerHost, s)
	log.Infof("link sticky: prefer waiting for %s to dial us", peerHost)
}

// shouldDialPeer reports whether this hop should initiate SSH to peer.
// Sticky inbound_from means the peer already dials us — do not flip.
func shouldDialPeer(peerHost string) bool {
	s := loadSticky(peerHost)
	if s.InboundFrom != "" && s.InboundFrom == peerHost {
		return false
	}
	if s.OutboundTo != "" && s.OutboundTo == peerHost {
		return true
	}
	// No sticky yet: allow dial attempt.
	return true
}

// shouldAwaitPeer reports whether we should wait for peer to dial us.
func shouldAwaitPeer(peerHost string) bool {
	s := loadSticky(peerHost)
	if s.OutboundTo != "" && s.OutboundTo == peerHost {
		// We are the dialer; still await briefly for races, but primarily dial.
		return true
	}
	if s.InboundFrom != "" && s.InboundFrom == peerHost {
		return true
	}
	return true
}
