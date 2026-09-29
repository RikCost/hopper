package hopper

import (
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/aengix/hopper/server/internal/iptunnel"
	"github.com/aengix/hopper/server/internal/log"
)

type Server struct {
	cfg      Config
	sessions atomic.Int32
	tun      io.ReadWriteCloser
	registry *SessionRegistry
	leases   *LeaseManager
	status   *StatusWriter
	reverse  *ReversePool
	tunOnce  sync.Once
}

func NewServer(cfg Config) (*Server, error) {
	leases, err := NewLeaseManager(cfg)
	if err != nil {
		return nil, err
	}
	return &Server{
		cfg:      cfg,
		registry: NewSessionRegistry(),
		leases:   leases,
		status:   NewStatusWriter(cfg),
		reverse:  NewReversePool(),
	}, nil
}

func (s *Server) Prepare() error {
	if s.cfg.Addr == "" {
		log.Infof("no overlay addr configured — pure forwarder")
		return nil
	}

	tun, err := iptunnel.OpenTUN(s.cfg.TUN)
	if err != nil {
		return fmt.Errorf("open tun %s: %w", s.cfg.TUN, err)
	}
	if err := iptunnel.ConfigureTUN(s.cfg.TUN, s.cfg.Addr, s.cfg.Overlay); err != nil {
		_ = tun.Close()
		return fmt.Errorf("configure tun %s: %w", s.cfg.TUN, err)
	}
	s.tun = tun
	s.startTUNReader()
	if s.cfg.NAT {
		if err := setupNAT(s.cfg.Overlay, s.cfg.TUN); err != nil {
			log.Warnf("NAT setup: %v", err)
		}
	}
	log.Infof("overlay %s on %s (%s)", s.cfg.Addr, s.cfg.TUN, s.cfg.Overlay)
	return nil
}

func (s *Server) startTUNReader() {
	if s.tun == nil {
		return
	}
	s.tunOnce.Do(func() {
		go func() {
			buf := make([]byte, 1500)
			for {
				n, err := s.tun.Read(buf)
				if err != nil {
					log.Warnf("tun read ended: %v", err)
					return
				}
				packet := append([]byte(nil), buf[:n]...)
				dest, err := IPv4Dest(packet)
				if err != nil {
					log.Debugf("tun packet skip: %v", err)
					continue
				}
				sess := s.registry.Lookup(dest)
				if sess == nil {
					log.Debugf("tun packet dropped (no session for %s)", dest)
					continue
				}
				if err := sess.routePacket(packet, ViaTun); err != nil {
					log.Warnf("tun route: %v", err)
				}
			}
		}()
	})
}

func (s *Server) Close() {
	if s.tun != nil {
		_ = s.tun.Close()
	}
}

func (s *Server) Listen() (net.Listener, int, error) {
	addr := fmt.Sprintf("%s:%d", s.cfg.ListenHost, s.cfg.ListenPort)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, 0, err
	}
	port := ln.Addr().(*net.TCPAddr).Port
	log.Infof("hopperd bound %s mode=%s overlay=%s chain=%s", addr, s.cfg.Mode(), s.cfg.Overlay, s.cfg.ChainID)
	return ln, port, nil
}

func (s *Server) ServeConn(conn net.Conn) {
	_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	frame, err := iptunnel.ReadFrame(conn)
	if err != nil {
		log.Errorf("session peek from %s: %v", conn.RemoteAddr(), err)
		_ = conn.Close()
		return
	}
	_ = conn.SetReadDeadline(time.Time{})

	if frame.Type == iptunnel.TypeReverseOffer {
		// Pool owns the connection until claim or idle close.
		s.handleReverseOffer(conn, frame)
		return
	}

	s.sessions.Add(1)
	defer func() {
		s.sessions.Add(-1)
		_ = conn.Close()
	}()

	sess, err := NewSession(s, conn)
	if err != nil {
		log.Errorf("session setup from %s: %v", conn.RemoteAddr(), err)
		return
	}
	if err := sess.RunWithFirstFrame(frame); err != nil {
		log.Infof("session ended: %v", err)
	}
}

func (s *Server) clientPoolContains(ip net.IP) bool {
	if s.cfg.ClientPool == "" || ip == nil {
		return false
	}
	_, pool, err := net.ParseCIDR(s.cfg.ClientPool)
	if err != nil {
		return false
	}
	return pool.Contains(ip)
}

// ownsClientIP reports whether this hop should track the IP as a phone/client
// overlay address. Entry uses client_pool; relays/exits use the whole overlay
// so TUN replies can find the reverse session that carried the request.
func (s *Server) ownsClientIP(ip net.IP) bool {
	if s.clientPoolContains(ip) {
		return true
	}
	if s.cfg.ClientPool != "" || s.cfg.Overlay == "" || ip == nil {
		return false
	}
	_, overlay, err := net.ParseCIDR(s.cfg.Overlay)
	if err != nil {
		return false
	}
	if !overlay.Contains(ip) {
		return false
	}
	// Do not bind the hop's own overlay address as a client.
	if s.cfg.Addr != "" {
		if hop := net.ParseIP(s.cfg.Addr); hop != nil && hop.Equal(ip) {
			return false
		}
	}
	return true
}

func (s *Server) sessionForDest(dest net.IP) *Session {
	return s.registry.Lookup(dest)
}
