package iptunnel

import (
	"encoding/binary"
	"errors"
	"io"
)

const (
	Version       = 2
	MaxPacketLen  = 65535
	HeaderLen     = 4
	KeepaliveSecs = 25
)

const (
	TypeData         byte = 1
	TypeKeepalive    byte = 2
	TypeAssignReq    byte = 3
	TypeAssignResp   byte = 4
	TypeReverseOffer byte = 5
	TypeReverseAck   byte = 6
)

var (
	ErrBadVersion  = errors.New("unsupported iptunnel version")
	ErrBadType     = errors.New("unsupported iptunnel frame type")
	ErrPacketLimit = errors.New("packet exceeds limit")
	ErrTruncated   = errors.New("truncated iptunnel frame")
)

type Frame struct {
	Type    byte
	Payload []byte
}

func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [HeaderLen]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}

	if hdr[0] != Version {
		return Frame{}, ErrBadVersion
	}

	frame := Frame{Type: hdr[1]}
	payloadLen := binary.BigEndian.Uint16(hdr[2:4])
	if payloadLen > MaxPacketLen {
		return Frame{}, ErrPacketLimit
	}

	switch frame.Type {
	case TypeData:
		if payloadLen == 0 {
			return Frame{}, ErrTruncated
		}
		buf := make([]byte, payloadLen)
		if _, err := io.ReadFull(r, buf); err != nil {
			return Frame{}, err
		}
		frame.Payload = buf
	case TypeKeepalive, TypeAssignReq, TypeAssignResp, TypeReverseOffer, TypeReverseAck:
		if payloadLen > 0 {
			buf := make([]byte, payloadLen)
			if _, err := io.ReadFull(r, buf); err != nil {
				return Frame{}, err
			}
			frame.Payload = buf
		}
	default:
		return Frame{}, ErrBadType
	}

	return frame, nil
}

func WriteFrame(w io.Writer, frame Frame) error {
	switch frame.Type {
	case TypeData:
		if len(frame.Payload) == 0 || len(frame.Payload) > MaxPacketLen {
			return ErrPacketLimit
		}
	case TypeKeepalive:
		frame.Payload = nil
	case TypeAssignReq, TypeAssignResp, TypeReverseOffer, TypeReverseAck:
		if len(frame.Payload) > MaxPacketLen {
			return ErrPacketLimit
		}
	default:
		return ErrBadType
	}

	hdr := [HeaderLen]byte{
		Version,
		frame.Type,
	}
	binary.BigEndian.PutUint16(hdr[2:4], uint16(len(frame.Payload)))

	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	if len(frame.Payload) == 0 {
		return nil
	}
	_, err := w.Write(frame.Payload)
	return err
}
