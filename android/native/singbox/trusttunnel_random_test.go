package trusttunnel

import (
 "bytes"
 "context"
 "encoding/binary"
 "io"
 "net"
 "testing"
 "time"
 boxTLS "github.com/sagernet/sing-box/common/tls"
 "github.com/sagernet/sing-box/log"
 "github.com/sagernet/sing-box/option"
)
func TestTunaClientRandomPrefixOnWire(t *testing.T) {
 for _,fp:=range []string{"", "firefox", "chrome"} { t.Run("tls_"+fp,func(t *testing.T){
  opts:=option.OutboundTLSOptions{Enabled:true,ServerName:"example.invalid",Insecure:true}
  if fp!="" { opts.UTLS=&option.OutboundUTLSOptions{Enabled:true,Fingerprint:fp} }
  cfg,err:=boxTLS.NewClient(context.Background(),log.NewNOPFactory().NewLogger("test"),"example.invalid",opts)
  if err!=nil {t.Fatal(err)}
  a,b:=net.Pipe();defer a.Close();defer b.Close();ctx,cancel:=context.WithTimeout(context.Background(),time.Second);defer cancel()
  done:=make(chan error,1);go func(){_,err:=tunaPrefixHandshake(ctx,a,cfg,"352665e0");done<-err}()
  b.SetReadDeadline(time.Now().Add(time.Second));header:=make([]byte,5)
  if _,err=io.ReadFull(b,header);err!=nil {t.Fatal(err)}
  packet:=make([]byte,int(binary.BigEndian.Uint16(header[3:])))
  if _,err=io.ReadFull(b,packet);err!=nil {t.Fatal(err)}
  if len(packet)<38 || packet[0]!=1 || !bytes.Equal(packet[6:10],[]byte{0x35,0x26,0x65,0xe0}) { t.Fatalf("prefix missing from actual ClientHello for %q",fp) }
  b.Close();<-done
 }) }
}
func TestTunaClientRandomMaskAndFreshness(t *testing.T){
 var previous []byte
 for i:=0;i<16;i++ {r,err:=tunaRandomReader("abcd/ff00");if err!=nil{t.Fatal(err)};v:=make([]byte,64);if _,err=io.ReadFull(r,v);err!=nil{t.Fatal(err)}
  if v[0]!=0xab{t.Fatal("mask not applied")};if bytes.Equal(v,previous){t.Fatal("random reused")};previous=v
 }
 for _,value:=range []string{"", "z0", "abc", "abcd/ff", "abcd/ffff00"}{if _,err:=tunaRandomReader(value);err==nil{t.Fatalf("accepted invalid prefix %q",value)}}
}
