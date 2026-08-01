# segment-indexer 사이드카 — 멀티스테이지 빌드
# 빌드 스테이지의 Go 버전은 media/go.mod 의 go 지시자와 같은 1.26 으로 유지한다.

FROM golang:1.26-alpine AS build
WORKDIR /src

# 의존만 먼저 받아 두면 소스만 바뀐 재빌드가 빨라진다.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
# CGO_ENABLED=0 = 순수 정적 바이너리. 빌드 이미지의 libc 에 묶이지 않는다.
RUN CGO_ENABLED=0 go build -trimpath -o /out/segment-indexer ./cmd/segment-indexer

# 최종 스테이지를 scratch 가 아니라 alpine 으로 두는 이유:
# G4(사이드카가 파일을 쓰지도 지우지도 않는다)의 검증이 "touch /recordings/x 가
# Read-only 로 실패한다"는 물리적 실증인데, scratch 면 그 명령을 실행할 셸이 없다.
# 설정값 확인은 "그렇게 적혀 있다"는 증거일 뿐 "실제로 막힌다"는 증거가 아니다.
# 이미지 크기 몇 MB보다 검증 가능성을 택한다.
FROM alpine:3

# tzdata = 컨테이너 안 date 가 TZ 를 제대로 해석하게 한다(D6 UTC 고정 확인용).
RUN apk add --no-cache tzdata ca-certificates

COPY --from=build /out/segment-indexer /usr/local/bin/segment-indexer

ENTRYPOINT ["/usr/local/bin/segment-indexer"]
