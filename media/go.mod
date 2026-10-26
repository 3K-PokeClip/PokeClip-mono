module github.com/3K-PokeClip/pokeclip-mono/media

// go 지시자는 8단계 Dockerfile 빌드 스테이지(golang:1.27.3-alpine)와 같은 1.27 로 유지한다.
// 패치 판은 Dockerfile 에서만 고정한다 — CI 는 이 줄로 Go 를 고르며, 1.27.2 이상이면
// golangci-lint v2.13.2 가 깨진다(media-ci.yml lint 단계 주석).
// 호스트 실측 툴체인은 go1.27.2.
go 1.27.0

require (
	github.com/abema/go-mp4 v1.7.3
	github.com/aws/aws-sdk-go-v2 v1.43.3
	github.com/aws/aws-sdk-go-v2/config v1.32.34
	github.com/aws/aws-sdk-go-v2/feature/rds/auth v1.6.34
	github.com/aws/aws-sdk-go-v2/service/s3 v1.106.3
	github.com/aws/smithy-go v1.27.6
	github.com/bluenviron/mediacommon/v2 v2.9.5
	github.com/fsnotify/fsnotify v1.10.1
	github.com/jackc/pgx/v5 v5.10.0
)

require (
	github.com/aws/aws-sdk-go-v2/aws/protocol/eventstream v1.7.16 // indirect
	github.com/aws/aws-sdk-go-v2/credentials v1.19.33 // indirect
	github.com/aws/aws-sdk-go-v2/feature/ec2/imds v1.18.34 // indirect
	github.com/aws/aws-sdk-go-v2/internal/configsources v1.4.34 // indirect
	github.com/aws/aws-sdk-go-v2/internal/endpoints/v2 v2.7.34 // indirect
	github.com/aws/aws-sdk-go-v2/internal/v4a v1.4.35 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/accept-encoding v1.13.15 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/checksum v1.9.27 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/presigned-url v1.13.34 // indirect
	github.com/aws/aws-sdk-go-v2/service/internal/s3shared v1.19.35 // indirect
	github.com/aws/aws-sdk-go-v2/service/signin v1.5.3 // indirect
	github.com/aws/aws-sdk-go-v2/service/sso v1.33.3 // indirect
	github.com/aws/aws-sdk-go-v2/service/ssooidc v1.38.3 // indirect
	github.com/aws/aws-sdk-go-v2/service/sts v1.45.3 // indirect
	github.com/google/uuid v1.3.0 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/sys v0.13.0 // indirect
	golang.org/x/text v0.41.0 // indirect
)
