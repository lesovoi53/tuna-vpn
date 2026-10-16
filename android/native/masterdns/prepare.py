"""Reproducible log-only overlay: lifecycle markers must not depend on LOG_LEVEL."""
from pathlib import Path
import json
r=Path(__file__).resolve().parents[2]
source=r/'external/masterdnsvpn'
cache=r/'build/masterdns-overlay'
cache.mkdir(parents=True,exist_ok=True)
replacements={
 'internal/client/tcp_listener.go': [('l.listeners = listeners','l.listeners = listeners\n\tfmt.Println("TUNA_MASTER_READY")')],
 'internal/client/client.go': [('c.successMTUChecks = false\n\tc.log.Infof','c.successMTUChecks = false\n\tfmt.Println("TUNA_MASTER_CONNECTING")\n\tc.log.Infof'),('case <-c.sessionResetSignal:', 'case <-c.sessionResetSignal:\n\t\t\t\tfmt.Println("TUNA_MASTER_CONNECTING")')]
}
overlay={}
for name,patches in replacements.items():
 p=source/name
 data=p.read_text(encoding='utf-8')
 for old,new in patches:
  assert data.count(old)==1, name
  data=data.replace(old,new)
 out=cache/p.name
 out.write_text(data,encoding='utf-8')
 overlay[str(p)]=str(out)
(cache/'overlay.json').write_text(json.dumps({'Replace':overlay}),encoding='utf-8')
