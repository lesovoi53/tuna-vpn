"""Loopback-only WebDAV storage + exit + authenticated client; no phone/remote endpoints."""
import json,socket,subprocess,tempfile,threading,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BIN=ROOT/'build/native-cache/webdav-check.exe'

def port():
 with socket.socket() as s:
  s.bind(('127.0.0.1',0));return s.getsockname()[1]
def recv(s,n):
 out=b''
 while len(out)<n:
  chunk=s.recv(n-len(out))
  if not chunk:raise RuntimeError('connection closed')
  out+=chunk
 return out
def wait_port(p,child):
 end=time.monotonic()+20
 while time.monotonic()<end:
  if child.poll() is not None:raise RuntimeError('native process exited')
  try:
   with socket.create_connection(('127.0.0.1',p),.2):return
  except OSError:time.sleep(.1)
 raise RuntimeError('listener timeout')
def check(encrypted):
 children=[]
 with tempfile.TemporaryDirectory(prefix='webdav-local-',dir=ROOT/'build') as tmp:
  tmp=Path(tmp);storage,socks,echo=port(),port(),socket.socket();echo.bind(('127.0.0.1',0));echo.listen();echo.settimeout(30)
  def echo_once():
   try:
    c,_=echo.accept()
    with c:
     while True:
      b=c.recv(65536)
      if not b:break
      c.sendall(b)
   except OSError:pass
  th=threading.Thread(target=echo_once,daemon=True);th.start()
  logs=[]
  try:
   for name in ('server','client'):logs.append(open(tmp/(name+'.log'),'wb'))
   server=[str(BIN),'-mode','selfhosted','-webdav-listen',f'127.0.0.1:{storage}','-webdav-storage',str(tmp/'storage'),'-login','test','-password','local:pass%+@雪']
   if encrypted:server+=['-enc']
   children.append(subprocess.Popen(server,stdout=logs[0],stderr=subprocess.STDOUT,cwd=tmp));wait_port(storage,children[0])
   config={'mode':'client','socks-listen':f'127.0.0.1:{socks}','socks-user':'u','socks-pass':'p','enc':encrypted,'timeout':'5s','backends':[{'url':f'http://127.0.0.1:{storage}','login':'test','password':'local:pass%+@雪'}],'tuning':{'poll-min':'20ms','poll-max':'50ms','coalesce':'1ms','chunk-size':131071,'puts':8,'read-min':3,'read-max':8}}
   file=tmp/'client.json';file.write_text(json.dumps(config),encoding='utf-8')
   children.append(subprocess.Popen([str(BIN),'-config',str(file)],stdout=logs[1],stderr=subprocess.STDOUT,cwd=tmp));wait_port(socks,children[1])
   with socket.create_connection(('127.0.0.1',socks),20) as c:
    c.sendall(bytes([5,1,0]));assert recv(c,2)==bytes([5,255]),'unauthenticated connection accepted'
   with socket.create_connection(('127.0.0.1',socks),20) as c:
    c.sendall(bytes([5,1,2]));assert recv(c,2)==bytes([5,2]);c.sendall(bytes([1,1,117,1,112]));assert recv(c,2)==bytes([1,0])
    ep=echo.getsockname()[1];c.sendall(bytes([5,1,0,1,127,0,0,1,ep>>8,ep&255]));assert recv(c,10)[1]==0
    payload=bytes(range(256))*64;c.sendall(payload);assert recv(c,len(payload))==payload,'payload mismatch'
   print('PASS storage -> exit -> authenticated SOCKS TCP, enc='+str(encrypted))
  finally:
   echo.close()
   for c in reversed(children):
    c.terminate()
    try:c.wait(5)
    except subprocess.TimeoutExpired:c.kill();c.wait(5)
   for log in logs:log.close()
   th.join(1)
if __name__=='__main__':
 check(False);check(True)
 print('PASS child processes stopped; temporary storage removed')
