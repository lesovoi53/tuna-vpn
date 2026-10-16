package main

import (
 "context"
 "encoding/binary"
 "encoding/json"
 "io"
 "net"
 "net/http"
 "os"
 "path/filepath"
 "strconv"
 "strings"
 "sync/atomic"
 "testing"
 "time"

 "github.com/xtls/xray-core/core"
 "github.com/xtls/xray-core/infra/conf/serial"
 "golang.org/x/net/proxy"
)

// Model the reported Snell server: DNS queries through the tunnel work, but
// CONNECT with a domain fails. Keep TLS/HTTP sniffing on, as on Android.
func TestTunaSocksDestinationResolutionTraffic(t *testing.T) {
 upstream := startTestChannel(t, "127.0.0.1:0", 73)
 gate, err := net.Listen("tcp", "127.0.0.1:0"); if err != nil { t.Fatal(err) }; defer gate.Close()
 var domains, ips atomic.Int32
 go func() { for {
  conn, err := gate.Accept(); if err != nil { return }
  go func() {
   defer conn.Close(); conn.SetDeadline(time.Now().Add(3*time.Second))
   if !authenticateSOCKS(conn, localAuth{Username:"u",Password:"p"}) { return }
   h := make([]byte,4); if _,err:=io.ReadFull(conn,h);err!=nil{return}
   if h[3]==3 { domains.Add(1);return }
   size:=4;if h[3]==4{size=16}else if h[3]!=1{return}
   dst:=make([]byte,size+2);if _,err:=io.ReadFull(conn,dst);err!=nil{return}
   if binary.BigEndian.Uint16(dst[size:])!=53 {ips.Add(1)}
   d,_:=proxy.SOCKS5("tcp",upstream.listener.Addr().String(),&proxy.Auth{User:"channel-73",Password:"pass-73"},&net.Dialer{Timeout:time.Second})
   remote,err:=d.Dial("tcp",net.JoinHostPort(net.IP(dst[:size]).String(),strconv.Itoa(int(binary.BigEndian.Uint16(dst[size:])))));if err!=nil{return};defer remote.Close()
   conn.Write([]byte{5,0,0,1,127,0,0,1,0,0});go io.Copy(remote,conn);io.Copy(conn,remote)
  }()
 } }()
 reservation,_:=net.Listen("tcp","127.0.0.1:0");address:=reservation.Addr().String();reservation.Close()
 raw,err:=buildSocks5XrayConfig(gate.Addr().String(),"u","p",address,"",[]string{"192.0.2.53"},false,"u","p","","",true);if err!=nil{t.Fatal(err)}
 p:=tunaPolicy{Mode:"ALL_VPN",DNS:&tunaSplitDNS{Remote:tunaResolver{Transport:"TCP",Endpoint:"192.0.2.53"},Domestic:tunaResolver{Transport:"TCP",Endpoint:"192.0.2.54"},QueryStrategy:"UseIPv4"}}
 b,_:=json.Marshal(p);path:=filepath.Join(t.TempDir(),"policy.json");os.WriteFile(path,b,0600)
 raw,err=applyTunaPolicy(raw,path);if err!=nil{t.Fatal(err)}
 raw,err=applyTunaSocksResolution(raw,true);if err!=nil{t.Fatal(err)}
 parsed,err:=serial.LoadJSONConfig(strings.NewReader(string(raw)));if err!=nil{t.Fatal(err)}
 server,err:=core.New(parsed);if err!=nil{t.Fatal(err)};defer server.Close();if err=server.Start();err!=nil{t.Fatal(err)}
 d,_:=proxy.SOCKS5("tcp",address,&proxy.Auth{User:"u",Password:"p"},&net.Dialer{Timeout:time.Second})
 tr:=&http.Transport{DialContext:func(ctx context.Context,n,a string)(net.Conn,error){return d.(proxy.ContextDialer).DialContext(ctx,n,a)}};defer tr.CloseIdleConnections()
 client:=&http.Client{Transport:tr,Timeout:2*time.Second}
 response,err:=client.Get("http://snell-destination.invalid/");if err!=nil{t.Fatalf("domain HTTP through IP-only upstream: %v (domain requests %d)",err,domains.Load())};defer response.Body.Close()
 body,_:=io.ReadAll(response.Body);if string(body)!="73"{t.Fatalf("unexpected body %q",body)}
 if domains.Load()!=0 || ips.Load()==0 || upstream.dnsRequests.Load()==0 {t.Fatalf("domain=%d ip=%d tunneled DNS=%d",domains.Load(),ips.Load(),upstream.dnsRequests.Load())}
}

func TestTunaSocksDestinationStrategyScope(t *testing.T) {
 for query,want:=range map[string]string{"":"ForceIPv4v6","UseIP":"ForceIPv4v6","UseIPv4":"ForceIPv4","UseIPv6":"ForceIPv6"} {
  original:=`{"dns":{"queryStrategy":"`+query+`"},"outbounds":[{"protocol":"socks","tag":"proxy"},{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"other"}]}`
  off,e:=applyTunaSocksResolution([]byte(original),false);if e!=nil||string(off)!=original{t.Fatal("disabled changed config")}
  result,e:=applyTunaSocksResolution([]byte(original),true);if e!=nil{t.Fatal(e)}
  var c map[string]any;json.Unmarshal(result,&c)
  for _,item:=range c["outbounds"].([]any){o:=item.(map[string]any);if o["protocol"]=="socks" || o["protocol"]=="vless"{if o["targetStrategy"]!=want{t.Fatalf("%s: %v",query,o)}}else if _,ok:=o["targetStrategy"];ok{t.Fatal("unrelated outbound changed")}}
 }
}
