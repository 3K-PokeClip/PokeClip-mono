module github.com/3K-PokeClip/pokeclip-mono/infra/media-host/test/echo

// 앞단 프록시 계약 시험 전용 도구다. Go 표준 라이브러리만 쓴다(의존 0 — go.sum 없음).
// go 지시자는 Dockerfile 빌드 스테이지(golang:1.27-alpine3.24)와 같은 1.27 로 둔다.
go 1.27.0
