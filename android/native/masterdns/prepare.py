"""Pinned upstream overlay: lifecycle markers and standalone repeated tunnel scanner."""
from pathlib import Path
import json
import argparse
r=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--source',type=Path,default=r/'external/masterdnsvpn')
source=parser.parse_args().source.resolve()
cache=r/'build/masterdns-overlay'
cache.mkdir(parents=True,exist_ok=True)
replacements={
 'internal/client/tcp_listener.go': [('l.listeners = listeners','l.listeners = listeners\n\tl.client.tunaEmitDiagnosticPolicy()\n\tfmt.Println("TUNA_MASTER_READY")')],
 'internal/client/session.go': [('c.logSessionClientPolicyChanges(before, settings, policy)', 'c.logSessionClientPolicyChanges(before, settings, policy)\n\tc.tunaEmitDiagnosticPolicy()')],
 'internal/client/client.go': [('c.successMTUChecks = false\n\tc.log.Infof','c.successMTUChecks = false\n\tfmt.Println("TUNA_MASTER_CONNECTING")\n\tc.log.Infof'),('case <-c.sessionResetSignal:', 'case <-c.sessionResetSignal:\n\t\t\t\tfmt.Println("TUNA_MASTER_CONNECTING")')]
}
overlay={}
pool_udp = source/'internal/client/socks_manager.go'
pool_udp_data = pool_udp.read_text(encoding='utf-8')
pool_udp_patches = [
    ('udpConn, err := net.ListenUDP("udp", bindAddr)',
     'if TunaPoolChildMode {\n\t\tbindAddr.IP = replyIP\n\t}\n\tudpConn, err := net.ListenUDP("udp", bindAddr)'),
    ('defer udpConn.Close()\n\n\tboundAddr := udpConn.LocalAddr().(*net.UDPAddr)',
     'defer udpConn.Close()\n\tstopPoolWatch := tunaPoolUDPWatch(ctx, conn, udpConn)\n\tdefer stopPoolWatch()\n\tvar poolPeer *net.UDPAddr\n\n\tboundAddr := udpConn.LocalAddr().(*net.UDPAddr)'),
    ('if n < 6 {\n\t\t\tcontinue\n\t\t}',
     '''if TunaPoolChildMode {
            controlPeer, ok := conn.RemoteAddr().(*net.TCPAddr)
            if !ok || !peerAddr.IP.IsLoopback() || !peerAddr.IP.Equal(controlPeer.IP) ||
                (clientPort != 0 && peerAddr.Port != int(clientPort)) {
                continue
            }
            if poolPeer != nil && (!peerAddr.IP.Equal(poolPeer.IP) || peerAddr.Port != poolPeer.Port) {
                continue
            }
        }
        if n < 6 || (TunaPoolChildMode && (buf[0] != 0 || buf[1] != 0)) {
            continue
        }'''),
    ('dnsQuery := buf[payloadOffset:n]',
     '''dnsQuery := buf[payloadOffset:n]
        if TunaPoolChildMode {
            if len(dnsQuery) < 12 {
                continue
            }
            if poolPeer == nil {
                poolPeer = peerAddr
            }
        }'''),
]
for old,new in pool_udp_patches:
    assert pool_udp_data.count(old)==1, 'pool UDP overlay anchor'
    pool_udp_data=pool_udp_data.replace(old,new)
pool_udp_output=cache/'socks_manager.go'
pool_udp_output.write_text(pool_udp_data+'\n'+(Path(__file__).parent/'pool_child.go.inc').read_text(encoding='utf-8'),encoding='utf-8')
overlay[str(pool_udp)]=str(pool_udp_output)
for name,patches in replacements.items():
 p=source/name
 data=p.read_text(encoding='utf-8')
 for old,new in patches:
  assert data.count(old)==1, name
  data=data.replace(old,new)
 out=cache/p.name
 out.write_text(data,encoding='utf-8')
 overlay[str(p)]=str(out)
mtu=source/'internal/client/mtu.go'
mtu_output=cache/'mtu.go'
mtu_output.write_text(mtu.read_text(encoding='utf-8')+'\n'+(Path(__file__).parent/'scan.go.inc').read_text(encoding='utf-8'),encoding='utf-8')
overlay[str(mtu)]=str(mtu_output)
overlay[str(source/'internal/client/tuna_scan_test.go')]=str(Path(__file__).parent/'scan_test.go')
overlay[str(source/'internal/client/tuna_diagnostic.go')]=str(Path(__file__).parent/'diagnostic.go')
(cache/'overlay.json').write_text(json.dumps({'Replace':overlay}),encoding='utf-8')
