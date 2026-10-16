"""Loopback-only native Snell v4 client / v5 server domain and literal echo."""
import socket,threading,subprocess,json,time,struct,os
from pathlib import Path
R=Path(__file__).resolve().parents[1]
P=R/"build/snell-domain-smoke";P.mkdir(parents=True,exist_ok=True)
exe=R/"build/native-cache/singbox-extended-check.exe"
def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def recv(s,n):
 b=b''
 while len(b)<n:
  x=s.recv(n-len(b));assert x,'Unexpected EOF';b+=x
 return b
echo=socket.socket();echo.bind(('127.0.0.1',0));echo.listen();echoport=echo.getsockname()[1]
def serve():
 while True:
  try:c,_=echo.accept()
  except OSError:return
  def conn(c):
   with c:
    while True:
     b=c.recv(4096)
     if not b:return
     c.sendall(b)
  threading.Thread(target=conn,args=(c,),daemon=True).start()
threading.Thread(target=serve,daemon=True).start()
processes=[];logs=[];results=[]
try:
 for reuse in [False,True]:
  serverPort,clientPort=port(),port()
  server={'log':{'level':'info'},'inbounds':[{'type':'snell','listen':'127.0.0.1','listen_port':serverPort,'version':5,'psk':'test-domain-psk','obfs_mode':'http'}],'dns':{'servers':[{'type':'hosts','tag':'qa-dns','predefined':{'snell-qa.invalid':['127.0.0.1']}}]},'outbounds':[{'type':'direct','tag':'direct'}],'route':{'rules':[{'action':'resolve','server':'qa-dns'}],'final':'direct','default_domain_resolver':'qa-dns'}}
  client={'log':{'level':'info'},'inbounds':[{'type':'socks','listen':'127.0.0.1','listen_port':clientPort,'users':[{'username':'qa','password':'test-only'}]}],'outbounds':[{'type':'snell','tag':'vpn','server':'127.0.0.1','server_port':serverPort,'version':4,'psk':'test-domain-psk','obfs_mode':'http','reuse':reuse}],'route':{'final':'vpn'}}
  for name,cfg in [('server',server),('client',client)]:
   f=P/f'snell-local-{reuse}-{name}.json';f.write_text(json.dumps(cfg));log=(P/f'snell-local-{reuse}-{name}.log').open('wb');logs.append(log)
   check=subprocess.run([str(exe),'check','-c',str(f)],capture_output=True);assert check.returncode==0,check.stderr.decode()
   processes.append(subprocess.Popen([str(exe),'run','-c',str(f)],stdout=log,stderr=log,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0)))
  time.sleep(.3)
  for host in ['127.0.0.1','snell-qa.invalid','snell-qa.invalid']:
   with socket.create_connection(('127.0.0.1',clientPort),3) as s:
    s.settimeout(3);s.sendall(b'\x05\x01\x02');assert recv(s,2)==b'\x05\x02'
    s.sendall(b'\x01\x02qa\x09test-only');assert recv(s,2)==b'\x01\x00'
    addr=(b'\x01'+socket.inet_aton(host)) if host=='127.0.0.1' else b'\x03'+bytes([len(host)])+host.encode()
    s.sendall(b'\x05\x01\x00'+addr+struct.pack('!H',echoport));h=recv(s,4);assert h[1]==0
    recv(s,4 if h[3]==1 else 16 if h[3]==4 else recv(s,1)[0]);recv(s,2)
    s.sendall(b'TUNA domain echo');assert recv(s,16)==b'TUNA domain echo'
    results.append({'reuse':reuse,'target':host,'echo':True});print(results[-1],flush=True)
finally:
 for proc in processes:
  if proc.poll() is None:proc.terminate()
  proc.wait(timeout=5)
 for log in logs:log.close()
 echo.close()
(P/'snell-local-domain-results.json').write_text(json.dumps(results,indent=2))
