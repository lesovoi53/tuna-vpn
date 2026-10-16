package tunnel

import (
 "context"
 "fmt"
 "net"
 "net/url"
 "strconv"
 "strings"
 "time"
)

// Only storage lookups use this resolver. Invalid overrides fail closed.
func tunaStorageResolver(value string) *net.Resolver {
 return &net.Resolver{PreferGo:true, Dial:func(ctx context.Context, network, _ string)(net.Conn,error){
  raw:=value
  if !strings.Contains(raw, "://") { raw="udp://"+raw }
  u,err:=url.Parse(raw)
  if err!=nil || (u.Scheme!="tcp" && u.Scheme!="udp") || u.User!=nil || u.Path!="" || u.RawQuery!="" || u.Fragment!="" || net.ParseIP(u.Hostname())==nil { return nil,fmt.Errorf("invalid storage bootstrap DNS") }
  port:=u.Port(); if port=="" {port="53"}
  p,err:=strconv.Atoi(port);if err!=nil || p<1 || p>65535{return nil,fmt.Errorf("invalid storage bootstrap port")}
  if u.Scheme=="tcp" {network="tcp"}
  return (&net.Dialer{Timeout:10*time.Second}).DialContext(ctx,network,net.JoinHostPort(u.Hostname(),port))
 }}
}
