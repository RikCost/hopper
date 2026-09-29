package hopper

import (
	"fmt"
	"net"
	"os"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/aengix/hopper/server/internal/log"
)

const nextHopDialTimeout = 20 * time.Second

func dialNextHop(next NextHop) (net.Conn, error) {
	addr := fmt.Sprintf("%s:%d", next.Host, next.Port)
	type outcome struct {
		conn net.Conn
		err  error
	}
	ch := make(chan outcome, 1)
	go func() {
		conn, err := dialNextHopOnce(next)
		ch <- outcome{conn, err}
	}()
	select {
	case out := <-ch:
		return out.conn, out.err
	case <-time.After(nextHopDialTimeout):
		return nil, fmt.Errorf("ssh dial %s timed out after %s", addr, nextHopDialTimeout)
	}
}

func dialNextHopOnce(next NextHop) (net.Conn, error) {
	keyBytes, err := os.ReadFile(next.KeyPath)
	if err != nil {
		return nil, fmt.Errorf("read key %s: %w", next.KeyPath, err)
	}

	signer, err := ssh.ParsePrivateKey(keyBytes)
	if err != nil {
		return nil, fmt.Errorf("parse key: %w", err)
	}

	addr := fmt.Sprintf("%s:%d", next.Host, next.Port)
	log.Infof("ssh connect %s@%s", next.User, addr)

	raw, err := net.DialTimeout("tcp", addr, 10*time.Second)
	if err != nil {
		return nil, fmt.Errorf("tcp dial %s: %w", addr, err)
	}
	deadline := time.Now().Add(nextHopDialTimeout)
	_ = raw.SetDeadline(deadline)

	clientConn, chans, reqs, err := ssh.NewClientConn(raw, addr, &ssh.ClientConfig{
		User:            next.User,
		Auth:            []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         nextHopDialTimeout,
	})
	if err != nil {
		_ = raw.Close()
		return nil, fmt.Errorf("ssh dial %s: %w", addr, err)
	}
	client := ssh.NewClient(clientConn, chans, reqs)

	tunnelPort := next.TunnelPort
	if tunnelPort == 0 {
		tunnelPort = DefaultListenPort
	}
	target := fmt.Sprintf("%s:%d", DefaultListenHost, tunnelPort)
	log.Infof("ssh forward -> %s", target)

	channel, chanReqs, err := client.Conn.OpenChannel("direct-tcpip", ssh.Marshal(struct {
		Raddr string
		Rport uint32
		Laddr string
		Lport uint32
	}{
		Raddr: DefaultListenHost,
		Rport: uint32(tunnelPort),
		Laddr: "127.0.0.1",
		Lport: 0,
	}))
	if err != nil {
		_ = client.Close()
		return nil, fmt.Errorf("open direct-tcpip %s: %w", target, err)
	}
	go ssh.DiscardRequests(chanReqs)

	// Long-lived tunnel: clear the dial deadline.
	_ = raw.SetDeadline(time.Time{})

	return &sshConn{Channel: channel, client: client}, nil
}

type sshConn struct {
	ssh.Channel
	client *ssh.Client
}

func (c *sshConn) LocalAddr() net.Addr  { return &net.TCPAddr{IP: net.IPv4zero, Port: 0} }
func (c *sshConn) RemoteAddr() net.Addr { return &net.TCPAddr{IP: net.IPv4zero, Port: 0} }

func (c *sshConn) SetDeadline(t time.Time) error      { return nil }
func (c *sshConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *sshConn) SetWriteDeadline(t time.Time) error { return nil }

func (c *sshConn) Close() error {
	_ = c.Channel.Close()
	return c.client.Close()
}
