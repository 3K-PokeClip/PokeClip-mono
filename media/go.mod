module github.com/3K-PokeClip/pokeclip-mono/media

// go 지시자는 8단계 Dockerfile 빌드 스테이지(golang:1.26-alpine)와 같은 버전으로 유지한다.
// 호스트 실측 툴체인은 go1.26.5.
go 1.26

require (
	github.com/abema/go-mp4 v1.7.1
	github.com/fsnotify/fsnotify v1.10.1
	github.com/jackc/pgx/v5 v5.10.0
)

require (
	github.com/google/uuid v1.1.2 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	golang.org/x/sync v0.17.0 // indirect
	golang.org/x/sys v0.13.0 // indirect
	golang.org/x/text v0.29.0 // indirect
)
