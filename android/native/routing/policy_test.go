package main

import("context";"io";"net";"net/http";"net/http/httptest";"time";"github.com/xtls/xray-core/core";xdns "github.com/xtls/xray-core/features/dns";"golang.org/x/net/proxy";"encoding/json";"os";"path/filepath";"testing";"strings";"github.com/xtls/xray-core/infra/conf/serial";"encoding/binary";"sync/atomic";"golang.org/x/net/dns/dnsmessage";"strconv")

func TestTunaDNSCacheAndAddressFamily(t *testing.T) {
 for _,disabled:=range []bool{false,true} {t.Run(strconv.FormatBool(disabled),func(t *testing.T){
  upstream:=startTestChannel(t,"127.0.0.1:0",73)
  reservation,e:=net.Listen("tcp","127.0.0.1:0");if e!=nil{t.Fatal(e)};address:=reservation.Addr().String();reservation.Close()
  raw,e:=buildSocks5XrayConfig(upstream.listener.Addr().String(),"channel-73","pass-73",address,"",[]string{"192.0.2.7"},false,"local-user","local-pass","","",false);if e!=nil{t.Fatal(e)}
  off:=false
  policy:=tunaPolicy{Mode:"ALL_VPN",Sniffing:&off,DNS:&tunaSplitDNS{
   Remote:tunaResolver{Transport:"TCP",Endpoint:"192.0.2.7:53"},Domestic:tunaResolver{Transport:"TCP",Endpoint:"192.0.2.8:53"},QueryStrategy:"UseIPv4",DisableCache:disabled}}
  data,_:=json.Marshal(policy);path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,data,0600)
  raw,e=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
  var c map[string]any;json.Unmarshal(raw,&c)
  for _, outbound := range c["outbounds"].([]any) {
   out := outbound.(map[string]any)
   if out["tag"] == "tuna-bypass" {
    if _, deprecated := out["settings"].(map[string]any)["domainStrategy"]; deprecated {t.Fatal("deprecated freedom domainStrategy")}
    if out["streamSettings"].(map[string]any)["sockopt"].(map[string]any)["domainStrategy"] != "UseIP" {t.Fatal("DIRECT DNS strategy changed")}
   }
  }
  if c["inbounds"].([]any)[0].(map[string]any)["sniffing"].(map[string]any)["enabled"]!=false {t.Fatal("sniff not disabled")}
  parsed,e:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if e!=nil{t.Fatal(e)}
  server,e:=core.New(parsed);if e!=nil{t.Fatal(e)};defer server.Close();if e=server.Start();e!=nil{t.Fatal(e)}
  dns:=server.GetFeature(xdns.ClientType()).(xdns.Client)
  for i:=0;i<2;i++ {ips,_,e:=dns.LookupIP("cache.invalid",xdns.IPOption{IPv4Enable:true});if e!=nil||len(ips)==0 {t.Fatalf("DNS query: %v",e)}}
  want:=int32(1);if disabled {want=2};if upstream.dnsRequests.Load()!=want {t.Fatalf("cache disabled %v: queries %d, want %d",disabled,upstream.dnsRequests.Load(),want)}
  ips,_,_:=dns.LookupIP("aaaa.invalid",xdns.IPOption{IPv6Enable:true});if len(ips)>0 || upstream.dnsRequests.Load()!=want {t.Fatal("IPv4-only sent AAAA query")}
 })}
}

func TestTunaSniffPreservesFakeDNS(t *testing.T) {
 for _,enabled:=range []bool{true,false} {
  raw,e:=buildSocks5XrayConfig("127.0.0.1:2081","u","p","127.0.0.1:1080","",[]string{"192.0.2.53"},false,"u","p","","",true);if e!=nil{t.Fatal(e)}
  policy:=tunaPolicy{Mode:"ALL_VPN",Sniffing:&enabled};data,_:=json.Marshal(policy);path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,data,0600)
  raw,e=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
  if _,e=serial.LoadJSONConfig(strings.NewReader(string(raw)));e!=nil{t.Fatal(e)}
  var c map[string]any;json.Unmarshal(raw,&c);sniff:=c["inbounds"].([]any)[0].(map[string]any)["sniffing"].(map[string]any)
  fake,quic:=false,false;for _,v:=range sniff["destOverride"].([]any) {if v=="fakedns" {fake=true};if v=="quic" {quic=true}}
  if !fake || sniff["enabled"]!=true || quic!=enabled {t.Fatalf("sniff %v: %v",enabled,sniff)}
 }
}

// Opt-in external smoke test. No user servers or credentials are involved.
func TestTunaSplitDNSLiveDoH(t *testing.T) {
 if os.Getenv("TUNA_TEST_LIVE_DOH")!="1" {t.Skip("external DNS smoke test")}
 hop,e:=net.Listen("tcp","127.0.0.1:0");if e!=nil{t.Fatal(e)};defer hop.Close()
 var forwarded atomic.Int32
 go func(){for {c,e:=hop.Accept();if e!=nil{return};go func(){defer c.Close();c.SetDeadline(time.Now().Add(15*time.Second));if !authenticateSOCKS(c,localAuth{Username:"local-user",Password:"local-pass"}){return};h:=make([]byte,4);if _,e:=io.ReadFull(c,h);e!=nil||h[1]!=1{return};n:=0;switch h[3]{case 1:n=4;case 4:n=16;case 3:b:=[]byte{0};if _,e:=io.ReadFull(c,b);e!=nil{return};n=int(b[0]);default:return};b:=make([]byte,n+2);if _,e:=io.ReadFull(c,b);e!=nil{return};host:=string(b[:n]);if h[3]!=3{host=net.IP(b[:n]).String()};port:=int(binary.BigEndian.Uint16(b[n:]));if host!="8.8.8.8"||port!=443{return};up,e:=net.DialTimeout("tcp",net.JoinHostPort(host,strconv.Itoa(port)),5*time.Second);if e!=nil{return};defer up.Close();forwarded.Add(1);c.Write([]byte{5,0,0,1,127,0,0,1,0,0});go io.Copy(up,c);io.Copy(c,up)}()}}()
 reservation,_:=net.Listen("tcp","127.0.0.1:0");address:=reservation.Addr().String();reservation.Close()
 raw,e:=buildSocks5XrayConfig(hop.Addr().String(),"local-user","local-pass",address,"",[]string{"8.8.8.8"},false,"local-user","local-pass","","",false);if e!=nil{t.Fatal(e)}
 policy:=tunaPolicy{Mode:"SELECTED_DIRECT",Rules:[]tunaRule{{"full:example.com","DIRECT",true}},DNS:&tunaSplitDNS{Remote:tunaResolver{Transport:"DOH",Endpoint:"https://8.8.8.8/dns-query"},Domestic:tunaResolver{Transport:"DOH",Endpoint:"https://1.1.1.1/dns-query"}}}
 b,_:=json.Marshal(policy);path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,b,0600);raw,e=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
 parsed,e:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if e!=nil{t.Fatal(e)};server,e:=core.New(parsed);if e!=nil{t.Fatal(e)};defer server.Close();if e=server.Start();e!=nil{t.Fatal(e)}
 dns:=server.GetFeature(xdns.ClientType()).(xdns.Client)
 ips,_,e:=dns.LookupIP("example.com",xdns.IPOption{IPv4Enable:true});if e!=nil||len(ips)==0{t.Fatalf("Direct DoH: %v",e)};if forwarded.Load()!=0{t.Fatal("direct DoH entered proxy")}
 ips,_,e=dns.LookupIP("example.org",xdns.IPOption{IPv4Enable:true});if e!=nil||len(ips)==0{t.Fatalf("Remote DoH: %v",e)};if forwarded.Load()==0{t.Fatal("Remote DoH bypassed proxy")}
}

func TestTunaSplitDNSRealTraffic(t *testing.T) {
 upstream:=startTestChannel(t,"127.0.0.1:0",73)
 direct,e:=net.Listen("tcp","127.0.0.1:0");if e!=nil{t.Fatal(e)};defer direct.Close()
 var directQueries atomic.Int32
 go func(){for {c,e:=direct.Accept();if e!=nil{return};go func(){defer c.Close();c.SetDeadline(time.Now().Add(3*time.Second));var size [2]byte;if _,e:=io.ReadFull(c,size[:]);e!=nil{return};b:=make([]byte,binary.BigEndian.Uint16(size[:]));if _,e:=io.ReadFull(c,b);e!=nil{return};var msg dnsmessage.Message;if msg.Unpack(b)!=nil{return};directQueries.Add(1);msg.Header.Response=true;msg.Header.RecursionAvailable=true;for _,q:=range msg.Questions{if q.Type==dnsmessage.TypeA{msg.Answers=append(msg.Answers,dnsmessage.Resource{Header:dnsmessage.ResourceHeader{Name:q.Name,Type:dnsmessage.TypeA,Class:dnsmessage.ClassINET,TTL:60},Body:&dnsmessage.AResource{A:[4]byte{192,0,2,99}}})}};b,_=msg.Pack();binary.BigEndian.PutUint16(size[:],uint16(len(b)));c.Write(size[:]);c.Write(b)}()}}()
 reservation,_:=net.Listen("tcp","127.0.0.1:0");address:=reservation.Addr().String();reservation.Close()
 raw,e:=buildSocks5XrayConfig(upstream.listener.Addr().String(),"channel-73","pass-73",address,"",[]string{"192.0.2.7"},false,"local-user","local-pass","","",false);if e!=nil{t.Fatal(e)}
 policy:=tunaPolicy{Mode:"SELECTED_DIRECT",Rules:[]tunaRule{{"full:direct.invalid","DIRECT",true},{"full:blocked.invalid","BLOCK",true}},DNS:&tunaSplitDNS{Remote:tunaResolver{Transport:"TCP",Endpoint:"192.0.2.7:53"},Domestic:tunaResolver{Transport:"TCP",Endpoint:direct.Addr().String()},Hosts:map[string]string{"static.invalid":"192.0.2.18"}}}
 data,_:=json.Marshal(policy);path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,data,0600)
 raw,e=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
 parsed,e:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if e!=nil{t.Fatal(e)}
 server,e:=core.New(parsed);if e!=nil{t.Fatal(e)};defer server.Close();if e=server.Start();e!=nil{t.Fatal(e)}
 dns:=server.GetFeature(xdns.ClientType()).(xdns.Client)
 query:=func(name,want string){ips,_,e:=dns.LookupIP(name,xdns.IPOption{IPv4Enable:true});if e!=nil||len(ips)!=1||ips[0].String()!=want{t.Fatalf("%s: %v %v",name,ips,e)}}
 query("direct.invalid","192.0.2.99")
 if directQueries.Load()!=1||upstream.dnsRequests.Load()!=0{t.Fatal("Domestic DNS escaped direct route")}
 query("remote.invalid","192.0.2.42")
 if directQueries.Load()!=1||upstream.dnsRequests.Load()!=1{t.Fatal("Remote DNS escaped VPN")}
 query("static.invalid","192.0.2.18")
 if directQueries.Load()!=1||upstream.dnsRequests.Load()!=1{t.Fatal("Static hosts caused network query")}
 if _,_,e:=dns.LookupIP("blocked.invalid",xdns.IPOption{IPv4Enable:true});e==nil{t.Fatal("blocked DNS resolved")}
 if directQueries.Load()!=1||upstream.dnsRequests.Load()!=1{t.Fatal("blocked DNS leaked to resolver")}
}

func TestTunaSplitDNSDoHConfig(t *testing.T) {
 raw,e:=buildSocks5XrayConfig("127.0.0.1:2081","u","p","127.0.0.1:1080","",[]string{"8.8.8.8"},false,"u","p","","",false);if e!=nil{t.Fatal(e)}
 p:=tunaPolicy{Mode:"SELECTED_DIRECT",DomainStrategy:"IPIfNonMatch",DNS:&tunaSplitDNS{Remote:tunaResolver{Transport:"DOH",Endpoint:"https://dns.google/dns-query",BootstrapIP:"8.8.8.8"},Domestic:tunaResolver{Transport:"DOH",Endpoint:"https://77.88.8.8/dns-query"}}}
 path:=filepath.Join(t.TempDir(),"policy.json");b,_:=json.Marshal(p);os.WriteFile(path,b,0600)
 got,e:=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)};if _,e=serial.LoadJSONConfig(strings.NewReader(string(got)));e!=nil{t.Fatal(e)}
 var c map[string]any;json.Unmarshal(got,&c);hosts:=c["dns"].(map[string]any)["hosts"].(map[string]any);if hosts["dns.google"]!="8.8.8.8"{t.Fatal("missing bootstrap")}
 p.DNS.Remote.BootstrapIP="";b,_=json.Marshal(p);os.WriteFile(path,b,0600);automatic,e:=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
 var automaticConfig map[string]any;json.Unmarshal(automatic,&automaticConfig)
 automaticAddress:=automaticConfig["dns"].(map[string]any)["servers"].([]any)[0].(map[string]any)["address"].(string)
 if !strings.HasPrefix(decodeTunaPolicyPool(t,automaticAddress).Addresses[0],"tuna-doh://"){t.Fatal("missing isolated endpoint bootstrap")}
 if _,e=serial.LoadJSONConfig(strings.NewReader(string(automatic)));e!=nil{t.Fatal(e)}
}

func TestTunaPolicy(t *testing.T) {
 raw,e:=buildSocks5XrayConfig("127.0.0.1:2081","u","p","127.0.0.1:1080","",[]string{"https://1.1.1.1/dns-query"},false,"u","p","","",false);if e!=nil{t.Fatal(e)}
 if strings.Contains(string(raw),"tcp://https") {t.Fatal("invalid DoH address")}
 for _,mode:=range []string{"ALL_VPN","SELECTED_VPN","SELECTED_DIRECT"} {
  path:=filepath.Join(t.TempDir(),"policy.json")
  p:=tunaPolicy{Mode:mode,Rules:[]tunaRule{{"domain:example.com","VPN",true},{"192.0.2.0/24","DIRECT",true},{"domain:ads.example","BLOCK",true}}}
  data,_:=json.Marshal(p);os.WriteFile(path,data,0600)
  got,e:=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
  if _,e=serial.LoadJSONConfig(strings.NewReader(string(got)));e!=nil{t.Fatal(e)}
  var cfg map[string]any;json.Unmarshal(got,&cfg)
  rules:=cfg["routing"].(map[string]any)["rules"].([]any)
  last:=rules[len(rules)-1].(map[string]any)
  want:="proxy";if mode=="SELECTED_VPN"{want="tuna-bypass"};if last["outboundTag"]!=want{t.Fatal(last)}
  dns:=rules[1].(map[string]any);if dns["outboundTag"]!="proxy"{t.Fatal("DNS escaped VPN")}
  if mode=="ALL_VPN" && len(rules)!=5 {t.Fatal("all VPN must ignore exceptions but retain block")}
 }
}

func TestTunaPolicyKeepsBalancer(t *testing.T) {
 raw:=[]byte(`{"dns":{"servers":["tcp://1.1.1.1"]},"outbounds":[],"routing":{"balancers":[{"tag":"b"}],"rules":[{"network":"tcp,udp","balancerTag":"b"}]}}`)
 path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,[]byte(`{"mode":"SELECTED_DIRECT","rules":[{"match":"domain:example.com","action":"VPN","enabled":true}]}`),0600)
 out,e:=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
 if strings.Count(string(out),`"balancerTag":"b"`)!=4 {t.Fatal(string(out))}
}

// Actual local TCP requests distinguish the VPN exit, direct exit and blocked
// destination. DNS must reach the fake VPN upstream even with direct default.
func TestTunaRoutingTrafficAndDNS(t *testing.T) {
 upstream:=startTestChannel(t,"127.0.0.1:0",73)
 direct:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){io.WriteString(w,"DIRECT")}));defer direct.Close()
 for _,mode:=range []string{"SELECTED_VPN","SELECTED_DIRECT","ALL_VPN"} {t.Run(mode,func(t *testing.T){
  reservation,e:=net.Listen("tcp","127.0.0.1:0");if e!=nil{t.Fatal(e)};address:=reservation.Addr().String();reservation.Close()
  raw,e:=buildSocks5XrayConfig(upstream.listener.Addr().String(),"channel-73","pass-73",address,"",[]string{"192.0.2.7"},false,"local-user","local-pass","","",false);if e!=nil{t.Fatal(e)}
  policy:=tunaPolicy{Mode:mode,Rules:[]tunaRule{{"domain:blocked.invalid","BLOCK",true},{"domain:vpn.invalid","VPN",true},{"127.0.0.1","DIRECT",true}}}
  if mode=="SELECTED_VPN" {policy.Rules=policy.Rules[:2]}
  if mode=="ALL_VPN" {policy.Rules[1].Action="DIRECT"}
  p,_:=json.Marshal(policy);path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,p,0600)
  raw,e=applyTunaPolicy(raw,path);if e!=nil{t.Fatal(e)}
  raw,e=applyTunaSocksResolution(raw,true);if e!=nil{t.Fatal(e)}
  var cfg map[string]any;json.Unmarshal(raw,&cfg);cfg["inbounds"].([]any)[0].(map[string]any)["settings"].(map[string]any)["udp"]=false;raw,_=json.Marshal(cfg)
  parsed,e:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if e!=nil{t.Fatal(e)}
  server,e:=core.New(parsed);if e!=nil{t.Fatal(e)};defer server.Close();if e=server.Start();e!=nil{t.Fatal(e)}
  dialer,_:=proxy.SOCKS5("tcp",address,&proxy.Auth{User:"local-user",Password:"local-pass"},&net.Dialer{Timeout:time.Second})
  transport:=&http.Transport{DialContext:func(ctx context.Context,network,addr string)(net.Conn,error){return dialer.(proxy.ContextDialer).DialContext(ctx,network,addr)}};defer transport.CloseIdleConnections()
  client:=&http.Client{Transport:transport,Timeout:2*time.Second}
  fetch:=func(url string)(string,error){r,e:=client.Get(url);if e!=nil{return "",e};defer r.Body.Close();b,e:=io.ReadAll(r.Body);return string(b),e}
  if b,e:=fetch("http://vpn.invalid/");e!=nil||b!="73"{t.Fatalf("VPN: %q %v",b,e)}
  if mode!="ALL_VPN" {if b,e:=fetch(direct.URL);e!=nil||b!="DIRECT"{t.Fatalf("direct: %q %v",b,e)}}
  if _,e:=fetch("http://blocked.invalid/");e==nil{t.Fatal("block bypassed")}
  before:=upstream.dnsRequests.Load()
  _,_,e=server.GetFeature(xdns.ClientType()).(xdns.Client).LookupIP("dns-routing.invalid",xdns.IPOption{IPv4Enable:true});if e!=nil{t.Fatal(e)}
  if upstream.dnsRequests.Load()<=before{t.Fatal("DNS did not use VPN")}
 })}
}
