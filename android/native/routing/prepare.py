"""Overlay the app routing adapter without changing the pinned Xray version."""
import json
import os
import re
import shutil
from pathlib import Path
r=Path(__file__).resolve().parents[2]
src=r/'external/vless-client'; out=r/'build/routing-overlay';out.mkdir(parents=True,exist_ok=True)
p=src/'main.go';s=p.read_text(encoding="utf-8")
assert s.count('\tflag.Parse()') == 1
s=s.replace('\tflag.Parse()', '\tif err := tunaParseArgs(flag.CommandLine, os.Args[1:]); err != nil { log.Fatal("Private launch configuration rejected") }')
anchor='\t// Load config using serial.LoadJSONConfig (requires io.Reader)'
assert s.count(anchor)==1
s=s.replace(anchor,'\tif fileCfg == nil || fileCfg.RawXray == nil {\n\tif err = configureTunaPolicyBootstrap(os.Getenv("TUNA_ROUTING_FILE")); err != nil { log.Fatal("Bootstrap policy: ", err) }\n\tjsonConfig, err = applyTunaPolicy(jsonConfig, os.Getenv("TUNA_ROUTING_FILE"))\n\tif err != nil { log.Fatal("Routing policy: ", err) }\n\t}\n'+anchor)
s=s.replace(anchor,'\tif fileCfg == nil || fileCfg.RawXray == nil {\n\tjsonConfig, err = applyTunaSocksResolution(jsonConfig, os.Getenv("TUNA_RESOLVE_SOCKS_DESTINATION") == "1")\n\tif err != nil { log.Fatal("SOCKS destination resolver: ", err) }\n\t}\n'+anchor)
s=s.replace('"tcp://"+d','tunaDNSAddress(d)')
(out/'main.go').write_text(s, encoding='utf-8')
mapping={str(p):str(out/'main.go')}
for name in ('policy.go','policy_test.go','adaptive_policy_test.go','emergency_policy_test.go','bootstrap.go','bootstrap_test.go','socks_resolution_test.go','args_config.go','args_config_test.go'):
 mapping[str(src/('tuna_'+name))]=str(r/'native/routing'/name)
# Patch only the pinned dependency through a Go overlay, never the module cache.
version = re.search(r'github.com/xtls/xray-core\s+(\S+)', (src/'go.mod').read_text()).group(1)
cache = Path(os.environ.get('GOMODCACHE', str(Path.home()/'go/pkg/mod')))
module_source = cache/f'github.com/xtls/xray-core@{version}'
module_copy = out/f'xray-core-{version}'
if not module_copy.exists():
 shutil.copytree(module_source, module_copy)
split = module_copy/'transport/internet/splithttp'
mod_text = (src/'go.mod').read_text(encoding='utf-8')
assert not re.search(r'replace\s+github.com/xtls/xray-core', mod_text), 'Review existing Xray replacement'
(out/'native.mod').write_text(mod_text + '\nreplace github.com/xtls/xray-core => '+json.dumps(module_copy.as_posix())+'\n', encoding='utf-8')
shutil.copyfile(src/'go.sum', out/'native.sum')
mapping[str(split/'tuna_alpn_test.go')] = str(r/'native/routing/xhttp_alpn_test.go')
(out/'overlay-baseline.json').write_text(json.dumps({'Replace':mapping}))
dialer = (split/'dialer.go').read_text(encoding='utf-8')
old = '''\t\t\t\tif err := conn.(*tls.UConn).HandshakeContext(ctxInner); err != nil {
\t\t\t\t\treturn nil, err
\t\t\t\t}'''
new = '''\t\t\t\tvar handshakeErr error
\t\t\t\tif httpVersion == "1.1" {
\t\t\t\t\t// uTLS presets otherwise retain h2, contradicting the HTTP/1 transport.
\t\t\t\t\thandshakeErr = conn.(*tls.UConn).WebsocketHandshakeContext(ctxInner)
\t\t\t\t} else {
\t\t\t\t\thandshakeErr = conn.(*tls.UConn).HandshakeContext(ctxInner)
\t\t\t\t}
\t\t\t\tif handshakeErr != nil {
\t\t\t\t\tconn.Close()
\t\t\t\t\treturn nil, handshakeErr
\t\t\t\t}'''
assert dialer.count(old) == 1, 'Pinned XHTTP handshake changed; review the overlay'
(out/'xhttp_dialer.go').write_text(dialer.replace(old,new), encoding='utf-8')
mapping[str(split/'dialer.go')] = str(out/'xhttp_dialer.go')
dns = module_copy/'app/dns'
nameserver = (dns/'nameserver.go').read_text(encoding='utf-8')
anchor = '\t\tswitch {\n'
assert nameserver.count(anchor) == 1, 'Pinned DNS server factory changed'
nameserver = nameserver.replace(anchor, anchor + '\t\tcase strings.EqualFold(u.Scheme, "tuna-dns"):\n\t\t\treturn newTunaDNSServer(ctx, address.Domain(), dispatcher, disableCache, serveStale, serveExpiredTTL, clientIP)\n')
nameserver = nameserver.replace(anchor, anchor + '\t\tcase strings.EqualFold(u.Scheme, "tuna-doh"):\n\t\t\treturn newTunaDoHServer(address.Domain(), dispatcher, disableCache, serveStale, serveExpiredTTL, clientIP)\n')
nameserver = nameserver.replace(anchor, anchor + '\t\tcase u.Scheme == "tuna-system" && address.Domain() == "tuna-system://android":\n\t\t\treturn &tunaSystemDNSServer{}, nil\n')
(out/'dns_nameserver.go').write_text(nameserver, encoding='utf-8')
mapping[str(dns/'nameserver.go')] = str(out/'dns_nameserver.go')
dns_main = (dns/'dns.go').read_text(encoding='utf-8')
anchor = 'func New(ctx context.Context, config *Config) (*DNS, error) {\n'
assert dns_main.count(anchor) == 1, 'Pinned DNS constructor changed'
dns_main = dns_main.replace(anchor, anchor + '\tctx = tunaPoolContext(ctx)\n')
(out/'dns_main.go').write_text(dns_main, encoding='utf-8')
mapping[str(dns/'dns.go')] = str(out/'dns_main.go')
cached = (dns/'nameserver_cached.go').read_text(encoding='utf-8')
old = '''\tv, _, _ := s.getCacheController().requestGroup.Do(key, func() (any, error) {
\t\treturn doFetch(ctx, s, fqdn, option), nil
\t})
\tret := v.(result)

\treturn ret.ips, ret.ttl, ret.error'''
new = '''\tpending := s.getCacheController().requestGroup.DoChan(key, func() (any, error) {
\t\treturn doFetch(ctx, s, fqdn, option), nil
\t})
\tselect {
\tcase <-ctx.Done():
\t\treturn nil, 0, ctx.Err()
\tcase completed := <-pending:
\t\tret := completed.Val.(result)
\t\treturn ret.ips, ret.ttl, ret.error
\t}'''
assert cached.count(old) == 1, 'Pinned DNS coalescing changed; review cancellation overlay'
(out/'dns_cached.go').write_text(cached.replace(old,new), encoding='utf-8')
mapping[str(dns/'nameserver_cached.go')] = str(out/'dns_cached.go')
mapping[str(dns/'tuna_adaptive_dns.go')] = str(r/'native/routing/adaptive_dns.go')
mapping[str(dns/'tuna_system_dns.go')] = str(r/'native/routing/system_dns.go')
mapping[str(dns/'tuna_system_dns_test.go')] = str(r/'native/routing/system_dns_test.go')
mapping[str(dns/'tuna_emergency_dns_test.go')] = str(r/'native/routing/emergency_dns_test.go')
mapping[str(dns/'tuna_adaptive_dns_test.go')] = str(r/'native/routing/adaptive_dns_test.go')
mapping[str(dns/'tuna_doh_bootstrap.go')] = str(r/'native/routing/doh_bootstrap.go')
mapping[str(dns/'tuna_doh_bootstrap_test.go')] = str(r/'native/routing/doh_bootstrap_test.go')
(out/'overlay.json').write_text(json.dumps({'Replace':mapping}))
