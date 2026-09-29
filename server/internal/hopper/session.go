package hopper

import (
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/aengix/hopper/server/internal/iptunnel"
	"github.com/aengix/hopper/server/internal/log"
)

type Session struct {
	srv        *Server
	cfg        Config
	routes     *RouteTable
	ingress    *frameConn
	downstream *frameConn
	clientIP   net.IP
	deviceID   string
	info       *SessionInfo
	mu         sync.Mutex
}

func NewSession(srv *Server, ingress net.Conn) (*Session, error) {
	routes, err := NewRouteTable(srv.cfg.EffectiveRoutes())
	if err != nil {
		return nil, err
	}

	return &Session{
		srv:     srv,
		cfg:     srv.cfg,
		routes:  routes,
		ingress: &frameConn{Conn: ingress},
	}, nil
}

func (s *Session) Run() error {
	return s.RunWithFirstFrame(iptunnel.Frame{})
}

func (s *Session) RunWithFirstFrame(first iptunnel.Frame) error {
	log.Infof("session start mode=%s remote=%s", s.cfg.Mode(), s.ingress.RemoteAddr())

	s.info = s.srv.status.Register(s, s.ingress.RemoteAddr().String())
	defer func() {
		s.srv.status.Unregister(s)
		if s.clientIP != nil {
			s.srv.registry.Unregister(s.clientIP)
		}
		if s.downstream != nil {
			_ = s.downstream.Close()
		}
	}()

	// Assign before waiting on the reverse link so a missing next hop cannot
	// block the phone on the hopper stream / assign handshake.
	if s.srv.leases != nil {
		if err := s.handleAssign(first); err != nil {
			log.Infof("assign failed: %v", err)
			return err
		}
		first = iptunnel.Frame{}
	}

	stop := make(chan struct{})
	s.srv.status.StartPeriodicFlush(stop)

	// Keep the ingress alive with pings while waiting for the next hop's reverse dial.
	go keepaliveLoop(s.ingress, stop)

	if s.cfg.AwaitReverse && s.downstream == nil {
		down, err := s.waitReverseWithIngressDrain(stop)
		if err != nil {
			close(stop)
			log.Errorf("reverse wait failed: %v", err)
			return fmt.Errorf("reverse wait: %w", err)
		}
		s.downstream = down
	}
	_ = s.ingress.SetReadDeadline(time.Time{})

	var wg sync.WaitGroup
	errCh := make(chan error, 3)

	startPump := func(name string, conn *frameConn, from string) {
		if conn == nil {
			return
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := pumpFrames(conn.Conn, func(packet []byte) error {
				return s.routePacket(packet, from)
			}); err != nil {
				log.Infof("%s pump ended: %v", name, err)
				errCh <- err
			}
		}()
	}

	if s.downstream != nil {
		go keepaliveLoop(s.downstream, stop)
	}

	if first.Type == iptunnel.TypeData && len(first.Payload) > 0 {
		if err := s.routePacket(first.Payload, ViaIngress); err != nil {
			close(stop)
			return err
		}
	} else if first.Type == iptunnel.TypeKeepalive {
		// already consumed
	} else if first.Type != 0 {
		close(stop)
		return fmt.Errorf("unexpected first frame type %d", first.Type)
	}

	startPump("ingress", s.ingress, ViaIngress)
	startPump("downstream", s.downstream, ViaNext)

	var err error
	select {
	case err = <-errCh:
	}

	close(stop)
	wg.Wait()
	log.Infof("session end: %v", err)
	return err
}

// waitReverseWithIngressDrain waits for the next hop to reverse-dial while
// discarding ingress keepalives so the TCP window does not stall.
func (s *Session) waitReverseWithIngressDrain(stop <-chan struct{}) (*frameConn, error) {
	type result struct {
		conn *frameConn
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		conn, err := s.srv.reverse.Take(reverseWaitTimeout)
		ch <- result{conn, err}
	}()

	for {
		select {
		case <-stop:
			return nil, fmt.Errorf("session stopped")
		case out := <-ch:
			return out.conn, out.err
		default:
		}

		_ = s.ingress.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
		frame, err := iptunnel.ReadFrame(s.ingress)
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				continue
			}
			return nil, err
		}
		if frame.Type == iptunnel.TypeKeepalive {
			continue
		}
		if frame.Type == iptunnel.TypeData {
			// Unexpected data before reverse is up — drop until link is ready.
			continue
		}
		return nil, fmt.Errorf("unexpected frame type %d while waiting for reverse", frame.Type)
	}
}

func (s *Session) handleAssign(first iptunnel.Frame) error {
	frame := first
	var err error
	if frame.Type == 0 {
		frame, err = iptunnel.ReadFrame(s.ingress)
		if err != nil {
			return err
		}
	}
	if frame.Type != iptunnel.TypeAssignReq {
		return iptunnel.ErrBadType
	}

	respPayload, err := s.srv.leases.HandleAssign(frame.Payload)
	if err != nil {
		return err
	}

	var resp assignResponse
	if err := json.Unmarshal(respPayload, &resp); err != nil {
		return err
	}
	if resp.Error != "" {
		return &assignError{resp.Error}
	}

	if err := s.ingress.writeFrame(iptunnel.Frame{Type: iptunnel.TypeAssignResp, Payload: respPayload}); err != nil {
		return err
	}

	s.clientIP = net.ParseIP(resp.Addr)
	var req assignRequest
	_ = json.Unmarshal(frame.Payload, &req)
	s.deviceID = req.DeviceID
	s.srv.registry.Register(s.clientIP, s)
	s.srv.status.BindClient(s, resp.Addr, req.DeviceID)
	log.Infof("client assigned addr=%s device=%s", resp.Addr, req.DeviceID)
	return nil
}

type assignError struct{ msg string }

func (e *assignError) Error() string { return e.msg }

func (s *Session) bindFromPacket(packet []byte) {
	if s.clientIP != nil {
		return
	}
	src, err := IPv4Source(packet)
	if err != nil || src == nil {
		return
	}
	if !s.srv.ownsClientIP(src) {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.clientIP != nil {
		return
	}
	s.clientIP = src
	s.srv.registry.Register(src, s)
	s.srv.status.BindClient(s, src.String(), "")
	log.Infof("session bound to client src=%s", src)
}

func (s *Session) routePacket(packet []byte, from string) error {
	if from == ViaIngress {
		s.bindFromPacket(packet)
	}
	if s.clientIP != nil {
		if s.srv.leases != nil {
			s.srv.leases.Touch(s.clientIP.String())
		}
		s.srv.status.Touch(s)
	}

	dest, err := IPv4Dest(packet)
	if err != nil {
		log.Debugf("skip non-ipv4 packet from %s: %v", from, err)
		return nil
	}

	if from == ViaNext && s.srv.ownsClientIP(dest) {
		target := s.srv.sessionForDest(dest)
		if target != nil {
			return writeData(target.ingress, packet)
		}
		return writeData(s.ingress, packet)
	}

	via := s.routes.Lookup(dest)
	if from == via {
		switch from {
		case ViaIngress:
			if s.downstream != nil {
				via = ViaNext
			} else if s.srv.tun != nil {
				via = ViaTun
			}
		case ViaNext:
			via = ViaIngress
		case ViaTun:
			via = ViaIngress
		}
	}

	log.Debugf("route %s -> %s via %s", from, dest, via)

	switch via {
	case ViaIngress:
		return writeData(s.ingress, packet)
	case ViaNext:
		if s.downstream == nil {
			if s.srv.tun != nil {
				_, err := s.srv.tun.Write(packet)
				return err
			}
			return writeData(s.ingress, packet)
		}
		return writeData(s.downstream, packet)
	case ViaTun:
		if s.srv.tun == nil {
			if s.downstream != nil {
				return writeData(s.downstream, packet)
			}
			return writeData(s.ingress, packet)
		}
		_, err := s.srv.tun.Write(packet)
		return err
	default:
		return nil
	}
}
