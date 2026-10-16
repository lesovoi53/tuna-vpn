module tuna-mieru-adapter

go 1.26.3

require (
	github.com/enfein/mieru/v3 v3.0.0
	google.golang.org/protobuf v1.34.2
)

require (
	github.com/google/btree v1.1.3 // indirect
	golang.org/x/crypto v0.33.0 // indirect
	golang.org/x/net v0.26.0 // indirect
	golang.org/x/sys v0.30.0 // indirect
	golang.org/x/text v0.22.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20240610135401-a8a62080eff3 // indirect
	google.golang.org/grpc v1.64.1 // indirect
)

replace github.com/enfein/mieru/v3 => ../mieru
