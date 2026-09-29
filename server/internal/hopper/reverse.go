package hopper

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/aengix/hopper/server/internal/iptunnel"
	"github.com/aengix/hopper/server/internal/log"
)

const (
	reverseWaitTimeout    = 45 * time.Second
	reverseDialBackoff    = 3 * time.Second
	reverseDialBackoffMax = 30 * time.Second
	reverseIdleStale      = 90 * time.Second
	// Keep one spare reverse link ready so the next phone session does not wait.
	reverseSpareTarget = 1
)

// provideDownstream: dialer is next hop; receiver parks for phone session downstream.
// provideIngress: dialer is previous hop; receiver runs an ingress session on the conn.
const (
	provideDownstream = "downstream"
	provideIngress    = "ingress"
)

type reverseOfferPayload struct {
	ChainID    string `json:"chain_id"`
	Addr       string `json:"addr"`
	Role       string `json:"role"`
	Provide    string `json:"provide"`
	DialerHost string `json:"dialer_host,omitempty"`
}

type reverseOffer struct {
	conn     *frameConn
	payload  reverseOfferPayload
	lastSeen time.Time
	claimed  chan struct{}
	parkDone chan struct{}
}

// ReversePool holds reverse links dialed by a peer toward this hop.
type ReversePool struct {
	mu      sync.Mutex
	waiters []chan *frameConn
	idle    []*reverseOffer
}

func NewReversePool() *ReversePool {
	return &ReversePool{}
}

// IdleCount returns how many parked reverse links are waiting to be claimed.
func (p *ReversePool) IdleCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.pruneLocked()
	return len(p.idle)
}

// Add parks conn for a future Take, or hands it to a waiting session immediately.
// The returned offer can be waited on without reading the socket (claimed vs dead).
func (p *ReversePool) Add(conn net.Conn, payload reverseOfferPayload) *reverseOffer {
	offer := &reverseOffer{
		conn:     &frameConn{Conn: conn},
		payload:  payload,
		lastSeen: time.Now(),
		claimed:  make(chan struct{}),
		parkDone: make(chan struct{}),
	}

	p.mu.Lock()
	if len(p.waiters) > 0 {
		ch := p.waiters[0]
		p.waiters = p.waiters[1:]
		p.mu.Unlock()
		close(offer.claimed)
		close(offer.parkDone)
		ch <- offer.conn
		return offer
	}
	p.idle = append(p.idle, offer)
	p.mu.Unlock()

	go p.park(offer)
	return offer
}

func (p *ReversePool) park(offer *reverseOffer) {
	defer close(offer.parkDone)

	stop := make(chan struct{})
	defer close(stop)
	go keepaliveLoop(offer.conn, stop)

	go func() {
		<-offer.claimed
		_ = offer.conn.SetReadDeadline(time.Now())
	}()

	for {
		select {
		case <-offer.claimed:
			_ = offer.conn.SetReadDeadline(time.Time{})
			return
		default:
		}

		_ = offer.conn.SetReadDeadline(time.Now().Add(iptunnel.KeepaliveSecs * 2 * time.Second))
		frame, err := iptunnel.ReadFrame(offer.conn)
		select {
		case <-offer.claimed:
			_ = offer.conn.SetReadDeadline(time.Time{})
			return
		default:
		}
		if err != nil {
			p.removeIdle(offer)
			_ = offer.conn.Close()
			return
		}
		if frame.Type != iptunnel.TypeKeepalive {
			log.Warnf("reverse park unexpected frame type=%d from %s", frame.Type, offer.payload.Addr)
			p.removeIdle(offer)
			_ = offer.conn.Close()
			return
		}
		offer.lastSeen = time.Now()
	}
}

func (p *ReversePool) removeIdle(offer *reverseOffer) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for i, o := range p.idle {
		if o == offer {
			p.idle = append(p.idle[:i], p.idle[i+1:]...)
			return
		}
	}
}

// dropIfIdle removes an unclaimed parked offer and closes its conn.
func (p *ReversePool) dropIfIdle(offer *reverseOffer) {
	p.mu.Lock()
	found := false
	for i, o := range p.idle {
		if o == offer {
			p.idle = append(p.idle[:i], p.idle[i+1:]...)
			found = true
			break
		}
	}
	p.mu.Unlock()
	if !found {
		return
	}
	select {
	case <-offer.claimed:
	default:
		close(offer.claimed)
	}
	_ = offer.conn.Close()
}

// WaitUntilGone blocks until the offer is claimed by a session or the park dies.
// nil means claimed (dialer should refill a spare); non-nil means the link died idle.
func (o *reverseOffer) WaitUntilGone(stop <-chan struct{}) error {
	select {
	case <-stop:
		return fmt.Errorf("stopped")
	case <-o.claimed:
		return nil
	case <-o.parkDone:
		select {
		case <-o.claimed:
			return nil
		default:
			return fmt.Errorf("parked reverse link closed")
		}
	}
}

func (p *ReversePool) Take(timeout time.Duration) (*frameConn, error) {
	p.mu.Lock()
	p.pruneLocked()
	if len(p.idle) > 0 {
		offer := p.idle[0]
		p.idle = p.idle[1:]
		p.mu.Unlock()
		close(offer.claimed)
		time.Sleep(20 * time.Millisecond)
		_ = offer.conn.SetReadDeadline(time.Time{})
		log.Infof("reverse claimed from %s", offer.payload.Addr)
		return offer.conn, nil
	}
	ch := make(chan *frameConn, 1)
	p.waiters = append(p.waiters, ch)
	p.mu.Unlock()

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case conn := <-ch:
		if conn == nil {
			return nil, fmt.Errorf("reverse offer canceled")
		}
		_ = conn.SetReadDeadline(time.Time{})
		log.Infof("reverse claimed (waiter)")
		return conn, nil
	case <-timer.C:
		p.mu.Lock()
		for i, w := range p.waiters {
			if w == ch {
				p.waiters = append(p.waiters[:i], p.waiters[i+1:]...)
				break
			}
		}
		p.mu.Unlock()
		return nil, fmt.Errorf("timed out waiting %s for reverse link from next hop", timeout)
	}
}

func (p *ReversePool) pruneLocked() {
	now := time.Now()
	kept := p.idle[:0]
	for _, o := range p.idle {
		if now.Sub(o.lastSeen) > reverseIdleStale {
			select {
			case <-o.claimed:
			default:
				close(o.claimed)
			}
			_ = o.conn.Close()
			continue
		}
		kept = append(kept, o)
	}
	p.idle = kept
}

func (s *Server) handleReverseOffer(conn net.Conn, frame iptunnel.Frame) {
	var payload reverseOfferPayload
	if len(frame.Payload) > 0 {
		_ = json.Unmarshal(frame.Payload, &payload)
	}
	if payload.ChainID != "" && s.cfg.ChainID != "" && payload.ChainID != s.cfg.ChainID {
		log.Warnf("reverse offer chain mismatch got=%s want=%s", payload.ChainID, s.cfg.ChainID)
		_ = conn.Close()
		return
	}
	fc := &frameConn{Conn: conn}
	if err := fc.writeFrame(iptunnel.Frame{Type: iptunnel.TypeReverseAck}); err != nil {
		log.Warnf("reverse ack failed: %v", err)
		_ = conn.Close()
		return
	}
	if payload.Provide == "" {
		payload.Provide = provideDownstream
	}
	log.Infof("reverse offer addr=%s role=%s provide=%s dialer=%s", payload.Addr, payload.Role, payload.Provide, payload.DialerHost)
	switch {
	case payload.DialerHost != "":
		rememberInbound(payload.DialerHost)
	case payload.Provide == provideDownstream && s.cfg.HasDownstream():
		rememberInbound(s.cfg.Downstream.Host)
	case payload.Provide == provideIngress && s.cfg.HasUpstream():
		rememberInbound(s.cfg.Upstream.Host)
	}

	if payload.Provide == provideIngress {
		go s.serveIngressConn(conn)
		return
	}
	s.reverse.Add(conn, payload)
}

func (s *Server) serveIngressConn(conn net.Conn) {
	s.sessions.Add(1)
	defer func() {
		s.sessions.Add(-1)
		_ = conn.Close()
	}()
	sess, err := NewSession(s, conn)
	if err != nil {
		log.Errorf("ingress session setup: %v", err)
		return
	}
	if err := sess.Run(); err != nil {
		log.Infof("ingress session ended: %v", err)
	}
}

// StartReverseDialer maintains reverse SSH links. Dial direction sticks once a
// peer link works, so an opposite logical chain reuses it instead of flipping.
func (s *Server) StartReverseDialer(stop <-chan struct{}) {
	if s.cfg.HasUpstream() {
		go s.reverseDialLoop(stop, *s.cfg.Upstream, provideDownstream)
	}
	if s.cfg.HasDownstream() {
		go s.reverseIngressSpareLoop(stop, *s.cfg.Downstream)
	}
}

// reverseIngressSpareLoop keeps reverseSpareTarget parked downstream links toward
// the next hop. When a phone session claims one, we dial another immediately.
func (s *Server) reverseIngressSpareLoop(stop <-chan struct{}, peer HopSSH) {
	backoff := reverseDialBackoff
	for {
		select {
		case <-stop:
			return
		default:
		}

		if !shouldDialPeer(peer.Host) {
			log.Infof("link sticky: skip dialing %s (peer already dials us)", peer.Host)
			select {
			case <-stop:
				return
			case <-time.After(reverseWaitTimeout):
			}
			continue
		}

		if s.reverse.IdleCount() >= reverseSpareTarget {
			select {
			case <-stop:
				return
			case <-time.After(time.Second):
			}
			continue
		}

		err := s.reverseDialIngressSpare(stop, peer)
		if err != nil {
			log.Warnf("reverse dial %s: %v", peer.Host, err)
			select {
			case <-stop:
				return
			case <-time.After(backoff):
			}
			if backoff < reverseDialBackoffMax {
				backoff *= 2
				if backoff > reverseDialBackoffMax {
					backoff = reverseDialBackoffMax
				}
			}
			continue
		}
		backoff = reverseDialBackoff
	}
}

func (s *Server) reverseDialLoop(stop <-chan struct{}, peer HopSSH, provide string) {
	backoff := reverseDialBackoff
	for {
		select {
		case <-stop:
			return
		default:
		}

		if !shouldDialPeer(peer.Host) {
			log.Infof("link sticky: skip dialing %s (peer already dials us)", peer.Host)
			select {
			case <-stop:
				return
			case <-time.After(reverseWaitTimeout):
			}
			continue
		}

		err := s.reverseDialOnce(stop, peer, provide)
		if err != nil {
			log.Warnf("reverse dial %s: %v", peer.Host, err)
		}

		select {
		case <-stop:
			return
		case <-time.After(backoff):
		}
		if err != nil {
			if backoff < reverseDialBackoffMax {
				backoff *= 2
				if backoff > reverseDialBackoffMax {
					backoff = reverseDialBackoffMax
				}
			}
		} else {
			backoff = reverseDialBackoff
		}
	}
}

func (s *Server) reverseDialIngressSpare(stop <-chan struct{}, peer HopSSH) error {
	log.Infof("reverse dial %s@%s:%d tunnel=%d provide=%s", peer.User, peer.Host, peer.Port, peer.TunnelPort, provideIngress)
	raw, err := dialNextHop(peer)
	if err != nil {
		return err
	}

	fc := &frameConn{Conn: raw}
	payload, _ := json.Marshal(reverseOfferPayload{
		ChainID:    s.cfg.ChainID,
		Addr:       s.cfg.Addr,
		Role:       s.cfg.Mode(),
		Provide:    provideIngress,
		DialerHost: advertiseDialerHost(peer),
	})
	if err := fc.writeFrame(iptunnel.Frame{Type: iptunnel.TypeReverseOffer, Payload: payload}); err != nil {
		_ = raw.Close()
		return fmt.Errorf("reverse offer: %w", err)
	}

	_ = raw.SetReadDeadline(time.Now().Add(15 * time.Second))
	ack, err := iptunnel.ReadFrame(raw)
	if err != nil {
		_ = raw.Close()
		return fmt.Errorf("reverse ack: %w", err)
	}
	if ack.Type != iptunnel.TypeReverseAck {
		_ = raw.Close()
		return fmt.Errorf("reverse ack: unexpected type %d", ack.Type)
	}
	_ = raw.SetReadDeadline(time.Time{})
	rememberOutbound(peer.Host)
	log.Infof("reverse linked to %s provide=%s", peer.Host, provideIngress)

	offer := s.reverse.Add(raw, reverseOfferPayload{
		ChainID: s.cfg.ChainID,
		Addr:    s.cfg.Addr,
		Role:    s.cfg.Mode(),
		Provide: provideDownstream,
	})

	// Do not read the socket here — park/session own it. When claimed, return so
	// the spare loop dials another; when park dies idle, surface the error.
	err = offer.WaitUntilGone(stop)
	if err != nil {
		if err.Error() == "stopped" {
			s.reverse.dropIfIdle(offer)
		}
		return err
	}
	log.Infof("reverse spare claimed toward %s — refilling", peer.Host)
	return nil
}

func (s *Server) reverseDialOnce(stop <-chan struct{}, peer HopSSH, provide string) error {
	log.Infof("reverse dial %s@%s:%d tunnel=%d provide=%s", peer.User, peer.Host, peer.Port, peer.TunnelPort, provide)
	raw, err := dialNextHop(peer)
	if err != nil {
		return err
	}

	fc := &frameConn{Conn: raw}
	payload, _ := json.Marshal(reverseOfferPayload{
		ChainID:    s.cfg.ChainID,
		Addr:       s.cfg.Addr,
		Role:       s.cfg.Mode(),
		Provide:    provide,
		DialerHost: advertiseDialerHost(peer),
	})
	if err := fc.writeFrame(iptunnel.Frame{Type: iptunnel.TypeReverseOffer, Payload: payload}); err != nil {
		_ = raw.Close()
		return fmt.Errorf("reverse offer: %w", err)
	}

	_ = raw.SetReadDeadline(time.Now().Add(15 * time.Second))
	ack, err := iptunnel.ReadFrame(raw)
	if err != nil {
		_ = raw.Close()
		return fmt.Errorf("reverse ack: %w", err)
	}
	if ack.Type != iptunnel.TypeReverseAck {
		_ = raw.Close()
		return fmt.Errorf("reverse ack: unexpected type %d", ack.Type)
	}
	_ = raw.SetReadDeadline(time.Time{})
	rememberOutbound(peer.Host)
	log.Infof("reverse linked to %s provide=%s", peer.Host, provide)

	done := make(chan struct{})
	go func() {
		select {
		case <-stop:
			_ = raw.Close()
		case <-done:
		}
	}()

	switch provide {
	case provideDownstream:
		// We are next hop dialing previous: channel is our ingress.
		sess, err := NewSession(s, raw)
		if err != nil {
			close(done)
			_ = raw.Close()
			return err
		}
		runErr := sess.Run()
		close(done)
		return runErr

	default:
		close(done)
		_ = raw.Close()
		return fmt.Errorf("unknown provide %q", provide)
	}
}

// advertiseDialerHost tells the peer which sticky key to use for inbound_from.
// Prefer HOPPER_ADVERTISE_HOST; otherwise the peer host we dial (receiver maps
// via its configured Upstream/Downstream host lists when matching).
func advertiseDialerHost(dialing HopSSH) string {
	if v := strings.TrimSpace(os.Getenv("HOPPER_ADVERTISE_HOST")); v != "" {
		return v
	}
	// Fallback: peer will sticky by the host they have for us if they set
	// DialerHost to match; without advertise host, use overlay later — for now
	// leave empty and sticky outbound on dialer + inbound skipped if empty.
	_ = dialing
	return strings.TrimSpace(os.Getenv("HOPPER_PUBLIC_HOST"))
}
