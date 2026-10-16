//go:build with_trusttunnel_cronet

// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from AlwaysBusyStack/NekoBoxForAndroid e4a4e055 for TUNA.

package trusttunnel

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	

	"github.com/sagernet/cronet-go"
	_ "github.com/sagernet/cronet-go/all"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type cronetRoundTripper struct {
	engine       cronet.Engine
	streamEngine cronet.StreamEngine
	originURL    string
	forceQUIC    bool
	cancel       context.CancelFunc
	logger       logger.ContextLogger
	waitGroup    sync.WaitGroup
	lifecycleMu  sync.Mutex
	closed       bool
}

func checkCronetAvailable(options ClientOptions) error {
	return nil
}

func (t *cronetRoundTripper) beginActivity() bool {
	t.lifecycleMu.Lock()
	defer t.lifecycleMu.Unlock()
	if t.closed {
		return false
	}
	t.waitGroup.Add(1)
	return true
}

func (t *cronetRoundTripper) RoundTrip(request *http.Request) (*http.Response, error) {
	if !t.beginActivity() { return nil, net.ErrClosed }
	defer t.waitGroup.Done()
	conn := t.streamEngine.CreateConn(request.Context(), t.logger, true, true)
	headers := make(map[string]string, len(request.Header)+2)
	for key, values := range request.Header {
		if len(values) > 0 {
			headers[key] = values[0]
		}
	}
	if request.Host != "" {
		headers["-connect-authority"] = request.Host
	}
	if t.forceQUIC {
		headers["-force-quic"] = "true"
	}
	err := conn.Start(request.Method, t.originURL, headers, 0, false)
	if err != nil {
		conn.Close()
		return nil, err
	}
	responseHeaders, err := conn.WaitForHeadersContext(request.Context())
	if err != nil {
		conn.Close()
		return nil, err
	}
	if request.Body != nil {
		t.waitGroup.Add(1)
		go func() {
			defer t.waitGroup.Done()
			tunaCopyRequestBody(request.Context(), conn, request.Body)
			_ = conn.Close()
		}()
	}
	status := http.StatusOK
	if statusValue := responseHeaders[":status"]; statusValue != "" {
		if parsedStatus, parseErr := strconv.Atoi(statusValue); parseErr == nil {
			status = parsedStatus
		}
	}
	response := &http.Response{
		StatusCode: status,
		Status:     strconv.Itoa(status) + " " + http.StatusText(status),
		Header:     make(http.Header),
		Body:       conn,
		Request:    request,
		Proto:      "HTTP/2.0",
		ProtoMajor: 2,
		ProtoMinor: 0,
	}
	for key, value := range responseHeaders {
		if strings.HasPrefix(key, ":") {
			continue
		}
		response.Header.Set(key, value)
	}
	return response, nil
}

func (t *cronetRoundTripper) CloseIdleConnections() {
	t.engine.CloseAllConnections()
}

func (t *cronetRoundTripper) Close() error {
	t.lifecycleMu.Lock()
	if t.closed { t.lifecycleMu.Unlock(); return nil }
	t.closed = true
	t.lifecycleMu.Unlock()
	t.cancel()
	t.engine.CloseAllConnections()
	t.waitGroup.Wait()
	t.engine.Shutdown()
	t.engine.Destroy()
	return nil
}

// Closing the engine cannot unblock a read from an idle CONNECT request's pipe.
// Cancellation must also close that reader before Close waits for upload workers.
func tunaCopyRequestBody(ctx context.Context, destination io.Writer, body io.ReadCloser) {
	stop := context.AfterFunc(ctx, func() { _ = body.Close() })
	defer stop()
	defer body.Close()
	_, _ = io.Copy(destination, body)
}

func (c *Client) newCronetRoundTripper(options ClientOptions, quic bool) (http.RoundTripper, error) {
	if options.ClientRandomPrefix != "" { return nil, E.New("Cronet does not support client random prefix") }
	engine := cronet.NewEngine()
	if options.TrustedRootCertificates != "" && !engine.SetTrustedRootCertificates(options.TrustedRootCertificates) {
		engine.Destroy()
		return nil, E.New("failed to set trusted CA certificates")
	}

	ctx, cancel := context.WithCancel(options.Ctx)
	transport := &cronetRoundTripper{
		cancel: cancel,
		engine: engine,
		logger: options.Logger,
	}
	transport.originURL = c.cronetOriginURL(options)
	transport.forceQUIC = quic && options.ForceQUIC
	engine.SetDialer(transport.tcpDialer(ctx, c))
	if quic {
		engine.SetUDPDialer(transport.udpDialer(ctx, c))
	}

	params := cronet.NewEngineParams()
	params.SetUserAgent(tcpUserAgent)

	if quic && options.ForceQUIC {
		params.SetEnableHTTP2(false)
	} else {
		params.SetEnableHTTP2(true)
	}

	params.SetEnableQuic(quic)
	params.SetEnableBrotli(true)
	if hostResolverRules := c.cronetHostResolverRules(options); hostResolverRules != "" {
		if err := params.SetHostResolverRules(hostResolverRules); err != nil {
			params.Destroy()
			cancel()
			engine.Destroy()
			return nil, err
		}
	}
	if quic {
		if err := params.SetQUICOptions("", cronetCongestionControl(options.CongestionControl), 6*1024*1024, 15*1024*1024); err != nil {
			params.Destroy()
			cancel()
			engine.Destroy()
			return nil, err
		}
	} else if err := params.SetHTTP2Options(15*1024*1024, 15*1024*1024/2); err != nil {
		params.Destroy()
		cancel()
		engine.Destroy()
		return nil, err
	}
	result := engine.StartWithParams(params)
	params.Destroy()
	if result != cronet.ResultSuccess {
		cancel()
		engine.Destroy()
		return nil, E.New("failed to start Cronet engine: ", int(result))
	}

	transport.streamEngine = engine.StreamEngine()
	return transport, nil
}

func (c *Client) cronetOriginURL(options ClientOptions) string {
	serverName := options.TLSServerName
	if serverName == "" {
		serverName = c.server.AddrString()
	}
	return (&url.URL{
		Scheme: "https",
		Host:   net.JoinHostPort(serverName, strconv.Itoa(int(c.server.Port))),
	}).String()
}

func (c *Client) cronetHostResolverRules(options ClientOptions) string {
	serverName := options.TLSServerName
	if serverName == "" || strings.EqualFold(serverName, c.server.AddrString()) {
		return ""
	}
	return "MAP " + serverName + " " + c.server.AddrString()
}

func cronetCongestionControl(name string) string {
	switch name {
	case "bbr":
		return string(cronet.QUICCongestionControlBBR)
	case "bbr2":
		return string(cronet.QUICCongestionControlBBRv2)
	case "cubic":
		return string(cronet.QUICCongestionControlCubic)
	case "reno":
		return string(cronet.QUICCongestionControlReno)
	default:
		return string(cronet.QUICCongestionControlDefault)
	}
}

func (t *cronetRoundTripper) tcpDialer(ctx context.Context, c *Client) cronet.Dialer {
	return func(address string, port uint16) int {
		if !t.beginActivity() { return cronet.NetErrorConnectionFailed.Code() }
		defer t.waitGroup.Done()
		destination := M.ParseSocksaddrHostPort(address, port)
		conn, err := c.detour.DialContext(ctx, N.NetworkTCP, destination)
		if err != nil {
			return cronetNetError(err).Code()
		}
		if tcpConn, ok := N.CastReader[*net.TCPConn](conn); ok {
			fd, duplicateErr := dupSocketFD(tcpConn)
			if duplicateErr == nil {
				conn.Close()
				return fd
			}
		}
		fd, pipeConn, err := createSocketPair()
		if err != nil {
			conn.Close()
			return cronet.NetErrorConnectionFailed.Code()
		}
		t.waitGroup.Add(1)
		go func() {
			defer t.waitGroup.Done()
			bufio.CopyConn(ctx, conn, pipeConn)
			conn.Close()
			pipeConn.Close()
		}()
		return fd
	}
}

func (t *cronetRoundTripper) udpDialer(ctx context.Context, c *Client) cronet.UDPDialer {
	return func(address string, port uint16) (int, string, uint16, func()) {
		if !t.beginActivity() { return cronet.NetErrorConnectionFailed.Code(), "", 0, nil }
		defer t.waitGroup.Done()
		destination := M.ParseSocksaddrHostPort(address, port)
		conn, err := c.detour.DialContext(ctx, N.NetworkUDP, destination)
		if err != nil {
			return cronetNetError(err).Code(), "", 0, nil
		}
		localAddr := M.SocksaddrFromNet(conn.LocalAddr())
		var localAddress string
		var localPort uint16
		if localAddr.IsValid() {
			localAddress = localAddr.AddrString()
			localPort = localAddr.Port
		}
		if udpConn, ok := N.CastReader[*net.UDPConn](conn); ok {
			fd, duplicateErr := dupSocketFD(udpConn)
			if duplicateErr == nil {
				conn.Close()
				return fd, localAddress, localPort, nil
			}
		}
		fd, pipeConn, err := createPacketSocketPair(false)
		if err != nil {
			conn.Close()
			return cronet.NetErrorConnectionFailed.Code(), "", 0, nil
		}
		remoteAddress := M.SocksaddrFromNet(conn.RemoteAddr())
		packetConn := bufio.NewUnbindPacketConn(conn)
		pipePacketConn := bufio.NewUnbindPacketConnWithAddr(pipeConn.(net.Conn), remoteAddress)
		t.waitGroup.Add(1)
		go func() {
			defer t.waitGroup.Done()
			defer conn.Close()
			defer pipeConn.Close()
			_ = bufio.CopyPacketConn(ctx, packetConn, pipePacketConn)
		}()
		return fd, localAddress, localPort, func() { conn.Close(); pipeConn.Close() }
	}
}

func cronetNetError(err error) cronet.NetError {
	if err == nil {
		return 0
	}
	if urlErr, ok := err.(*url.Error); ok {
		err = urlErr.Err
	}
	switch {
	case strings.Contains(err.Error(), "refused"):
		return cronet.NetErrorConnectionRefused
	case strings.Contains(err.Error(), "timeout"):
		return cronet.NetErrorConnectionTimedOut
	case strings.Contains(err.Error(), "network is unreachable"), strings.Contains(err.Error(), "no route"):
		return cronet.NetErrorAddressUnreachable
	default:
		return cronet.NetErrorConnectionFailed
	}
}

