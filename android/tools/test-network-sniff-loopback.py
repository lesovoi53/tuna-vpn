"""Real HTTP traffic to an IP verifies sniff-based DIRECT/BLOCK vs disabled sniff.

Only loopback servers and synthetic credentials. Does not test TLS/ECH or Android.
"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('dns_lab', ROOT / 'tools/test-network-dns-loopback.py')
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)

def run(engine, enabled):
    direct_hits, proxy_hits, dns_hits = [], [], []
    class HTTP(lab.socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(3)
            data = self.request.recv(8192)
            direct_hits.append(data)
            self.request.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nDIRECT')
    class Socks(lab.socketserver.BaseRequestHandler):
        def handle(self):
            s = self.request
            s.settimeout(3)
            try:
                assert lab.read_exact(s,1)==b'\x05'
                assert 2 in lab.read_exact(s,lab.read_exact(s,1)[0])
                s.sendall(b'\x05\x02')
                assert lab.read_exact(s,1)==b'\x01'
                user=lab.read_exact(s,lab.read_exact(s,1)[0])
                password=lab.read_exact(s,lab.read_exact(s,1)[0])
                assert (user,password)==(b'fixture-out',b'fixture-out-password')
                s.sendall(b'\x01\x00')
                h=lab.read_exact(s,4)
                assert h[:2]==b'\x05\x01'
                n=4 if h[3]==1 else 16 if h[3]==4 else lab.read_exact(s,1)[0]
                lab.read_exact(s,n+2)
                proxy_hits.append(1)
                s.sendall(b'\x05\x00\x00\x01'+b'\0'*6)
                s.recv(8192)
                s.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nVPN')
            except (OSError, EOFError):
                pass
    direct=lab.Server(('127.0.0.1',0),HTTP)
    proxy=lab.Server(('127.0.0.1',0),Socks)
    dns=lab.dns_server('127.0.0.1',dns_hits)
    servers=[direct,proxy,dns]
    for server in servers: threading.Thread(target=server.serve_forever,daemon=True).start()
    with socket.socket() as reserved:
        reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
    directory=ROOT / f'build/network-sniff-{engine}-{enabled}'
    directory.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ)
    env.pop('TUNA_ROUTING_FILE',None)
    env.pop('TUNA_BOOTSTRAP_DNS',None)
    fixtures=ROOT/'app/build/network-compiler-fixtures'
    if engine=='extended':
        config=json.loads((fixtures/'extended-SELECTED_DIRECT.json').read_text())
        config['inbounds'][0]['listen_port']=port
        config['outbounds'][0]['server_port']=proxy.server_address[1]
        if not enabled: config['route']['rules']=[r for r in config['route']['rules'] if r['action']!='sniff']
        for i,tag in enumerate(('dns-vpn','dns-direct')):
            config['dns']['servers'][i]=dict(type='tcp',tag=tag,server='127.0.0.1',server_port=dns.server_address[1],detour='direct' if i else 'vpn')
        path=directory/'config.json';path.write_text(json.dumps(config))
        command=[str(ROOT/'build/native-cache/singbox-extended-check.exe'),'run','-c',str(path)]
    else:
        policy=json.loads((fixtures/'xray-policy-SELECTED_DIRECT.json').read_text())
        policy['sniffing']=enabled
        for key in ('remote','domestic'): policy['splitDns'][key]=dict(transport='TCP',endpoint=f'127.0.0.1:{dns.server_address[1]}')
        path=directory/'policy.json';path.write_text(json.dumps(policy));env['TUNA_ROUTING_FILE']=str(path)
        command=[str(ROOT/'build/native-cache/xray-network-check.exe'),'-listen',f'127.0.0.1:{port}',
                 '-proxy-user','fixture-in','-proxy-pass','fixture-in-password',
                 '-local-socks5',f'fixture-out:fixture-out-password@127.0.0.1:{proxy.server_address[1]}']
    proc=None
    try:
        with (directory/'core.log').open('w') as output:
            proc=subprocess.Popen(command,env=env,stdout=output,stderr=output,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            for _ in range(80):
                assert proc.poll() is None,'Core exited'
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.1): break
                except OSError: time.sleep(.1)
            else: raise TimeoutError('No listener')
            def fetch(host):
                try:
                    with lab.socks_connect(port,'fixture-in','fixture-in-password',direct.server_address[1]) as s:
                        s.sendall(f'GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n'.encode())
                        answer=b''
                        while True:
                            chunk=s.recv(8192)
                            if not chunk: return answer
                            answer+=chunk
                except (EOFError,OSError): return b''
            assert fetch('www.direct.example').endswith(b'DIRECT' if enabled else b'VPN')
            before=(len(direct_hits),len(proxy_hits),len(dns_hits))
            blocked=fetch('blocked.example')
            if enabled:
                assert not blocked
                assert before==(len(direct_hits),len(proxy_hits),len(dns_hits)), 'BLOCK reached an outbound'
            else: assert blocked.endswith(b'VPN')
            result=dict(status='PASS',engine=engine,sniff=enabled,direct_http=len(direct_hits),vpn_http=len(proxy_hits),android_tested=False)
            (directory/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired: proc.kill();proc.wait(timeout=5)
        for server in servers: server.shutdown();server.server_close()

if __name__=='__main__':
    for core in ('xray','extended'):
        for enabled in (True,False): run(core,enabled)
