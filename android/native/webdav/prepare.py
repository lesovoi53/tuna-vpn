"""Pinned upstream plus reviewable Android resolver/SOCKS overlays."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'external/webdav-tunnel'
OUT=ROOT/'build/webdav-overlay'
OUT.mkdir(parents=True,exist_ok=True)
expected=json.loads((ROOT/'native/webdav/upstream-sha256.json').read_text())
for name,digest in expected.items():
 b=(SOURCE/name).read_bytes()
 assert digest in {hashlib.sha256(b).hexdigest(),hashlib.sha256(b.replace(b'\r\n',b'\n')).hexdigest()}, 'Unexpected upstream source: '+name
assert {str(p.relative_to(SOURCE)).replace('\\','/') for p in SOURCE.rglob('*.go')}=={n for n in expected if n.endswith('.go')}
replace={}
def overlay(name,text):
 target=OUT/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8');replace[str(SOURCE/name)]=str(target)
def change(t,a,b):
 assert a in t,a
 return t.replace(a,b)
t=(SOURCE/'tunnel/webdav.go').read_text(encoding='utf-8')
a=t.index('\tif dnsServer != "" {');b=t.index('\n\ttransport :=',a)
t=t[:a]+'\tif dnsServer != "" { dialer.Resolver = tunaStorageResolver(dnsServer) }'+t[b:]
overlay('tunnel/webdav.go',t)
t=(SOURCE/'tunnel/socks5.go').read_text(encoding='utf-8')
t=change(t,'uname := make([]byte, subHdr[1])','''if subHdr[0] != 1 || subHdr[1] == 0 {
            conn.Write([]byte{1,1}); err = fmt.Errorf("invalid SOCKS authentication"); return
        }
        uname := make([]byte, subHdr[1])''')
t=change(t,'if req[0] != socks5Version {','if req[0] != socks5Version || req[2] != 0 {')
overlay('tunnel/socks5.go',t)
t=(SOURCE/'tunnel/mux.go').read_text(encoding='utf-8')
t=change(t,'cmd, host, port, err := socks5Handshake(conn, user, pass)','''conn.SetDeadline(time.Now().Add(15*time.Second))
    cmd, host, port, err := socks5Handshake(conn, user, pass)
    conn.SetDeadline(time.Time{})''')
if '"time"' not in t:t=change(t,'import (','import (\n "time"')
t=change(t,'handleUDPAssociate(ctx, conn, mux)','handleUDPAssociate(ctx, conn, mux, port)')
t=change(t,'func handleUDPAssociate(ctx context.Context, conn net.Conn, mux *yamux.Session) {','''func handleUDPAssociate(ctx context.Context, conn net.Conn, mux *yamux.Session, requestedPort uint16) {
    peerIP := conn.RemoteAddr().(*net.TCPAddr).IP
    peerPort := int(requestedPort)
    // Bound outstanding DNS requests per authenticated association.
    slots := make(chan struct{}, 32)''')
t=change(t,'pkt := buf[:n]','''source, ok := src.(*net.UDPAddr)
        if !ok || !source.IP.Equal(peerIP) || (peerPort != 0 && source.Port != peerPort) { continue }
        pkt := buf[:n]''')
t=change(t,'len(pkt) < 4 || pkt[2] != 0','len(pkt) < 4 || pkt[0] != 0 || pkt[1] != 0 || pkt[2] != 0')
t=change(t,'query := make([]byte, n-dataOff)','''if peerPort == 0 { peerPort = source.Port }
        select { case slots <- struct{}{}: default: continue }
        query := make([]byte, n-dataOff)''')
t=change(t,'go relayDNS(pc, src, mux, dstHost, dstPort, query)','''go func(source net.Addr, host string, port uint16, payload []byte) {
            defer func(){ <-slots }()
            relayDNS(pc, source, mux, host, port, payload)
        }(src, dstHost, dstPort, query)''')
overlay('tunnel/mux.go',t)
for name in ('bootstrap.go','tuna_test.go'):
 replace[str(SOURCE/'tunnel'/('tuna_'+name if name=='bootstrap.go' else name))]=str(ROOT/'native/webdav'/name)
replace[str(SOURCE/'tuna_config_test.go')]=str(ROOT/'native/webdav/config_test.go')
(OUT/'overlay.json').write_text(json.dumps({'Replace':replace},indent=2),encoding='utf-8')
