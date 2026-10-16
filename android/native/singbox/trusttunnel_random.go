// SPDX-License-Identifier: GPL-3.0-or-later
// Prefix/mask behavior follows AlwaysBusyStack/NekoBoxForAndroid e4a4e055.
// Adapted for TUNA's pinned transport and TLS interfaces.
package trusttunnel

import (
    "bytes"
    "context"
    "crypto/rand"
    stdtls "crypto/tls"
    "encoding/hex"
    "fmt"
    "io"
    "net"
    "strings"

    "github.com/sagernet/sing-box/common/tls"
    "github.com/sagernet/sing/common/bufio"
    "github.com/sagernet/quic-go"
)

func tunaRandomReader(value string) (io.Reader, error) {
    prefixHex, maskHex, hasMask := strings.Cut(value, "/")
    prefix, err := hex.DecodeString(prefixHex)
    if err != nil || len(prefix) == 0 || len(prefix) > 32 { return nil, fmt.Errorf("invalid TrustTunnel client random prefix") }
    var mask []byte
    if hasMask {
        mask, err = hex.DecodeString(maskHex)
        if err != nil || len(mask) != len(prefix) { return nil, fmt.Errorf("invalid TrustTunnel client random mask") }
    }
    random := make([]byte, 32)
    if _, err = io.ReadFull(rand.Reader, random); err != nil { return nil, err }
    for i, b := range prefix {
        if hasMask { random[i] = (random[i] &^ mask[i]) | (b & mask[i]) } else { random[i] = b }
    }
    return io.MultiReader(bytes.NewReader(random), rand.Reader), nil
}

func tunaPrefixHandshake(ctx context.Context, conn net.Conn, config tls.Config, prefix string) (net.Conn, error) {
    reader, err := tunaRandomReader(prefix)
    if err != nil { return nil, err }
    if standard, stdErr := config.STDConfig(); stdErr == nil {
        standard = standard.Clone(); standard.Rand = reader
        secured := stdtls.Client(conn, standard)
        return secured, secured.HandshakeContext(ctx)
    }
    secured, err := config.Client(conn)
    if err != nil { return nil, err }
    target := any(secured)
    for i := 0; i < 8; i++ {
        if randomConn, ok := target.(interface { BuildHandshakeState() error; SetClientRandom([]byte) error }); ok {
            if err = randomConn.BuildHandshakeState(); err != nil { return nil, err }
            value := make([]byte,32)
            if _,err = io.ReadFull(reader,value); err != nil { return nil,err }
            if err = randomConn.SetClientRandom(value); err != nil { return nil,err }
            return secured, secured.HandshakeContext(ctx)
        }
        upstream, ok := target.(interface { Upstream() any }); if !ok { break }; target = upstream.Upstream()
    }
    return nil, fmt.Errorf("TLS implementation does not support client random prefix")
}

func tunaPrefixQUIC(ctx context.Context, conn net.Conn, config tls.Config, settings *quic.Config, prefix string) (*quic.Conn, error) {
    standard, err := config.STDConfig()
    if err != nil { return nil, err }
    standard = standard.Clone()
    standard.Rand, err = tunaRandomReader(prefix)
    if err != nil { return nil, err }
    return quic.DialEarly(ctx, bufio.NewUnbindPacketConn(conn), conn.RemoteAddr(), standard, settings)
}
