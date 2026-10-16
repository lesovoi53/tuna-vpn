//go:build !with_trusttunnel_cronet

package trusttunnel
import ("net/http";"fmt")
func(c *Client) newCronetRoundTripper(o ClientOptions,q bool)(http.RoundTripper,error){return nil,fmt.Errorf("TrustTunnel Cronet is not included in this build")}
