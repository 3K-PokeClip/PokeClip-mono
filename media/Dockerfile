# segment-indexer 사이드카 · janitor(로컬 ② 정리기) — 멀티스테이지 빌드. 한 이미지에 두 명령이 들고,
# 기본 ENTRYPOINT 는 사이드카다(janitor 는 compose 의 entrypoint 로 고른다).
# 빌드 스테이지의 Go 버전은 media/go.mod 의 go 지시자와 같은 1.27 로 유지한다.

FROM golang:1.27-alpine AS build
WORKDIR /src

# 의존만 먼저 받아 두면 소스만 바뀐 재빌드가 빨라진다.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
# RDS CA 통합 번들의 sha256 정본은 이 줄 하나다. 번들을 바꾸면 이 값도 같은 커밋에서 바꾼다 —
# 어긋나면 빌드가 여기서 멈춘다(출처 · 취득 기록은 README 「RDS CA 번들」 절).
RUN echo "90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b  /src/certs/rds-global-bundle.pem" | sha256sum -c -
# CGO_ENABLED=0 = 순수 정적 바이너리. 빌드 이미지의 libc 에 묶이지 않는다.
RUN CGO_ENABLED=0 go build -trimpath -o /out/segment-indexer ./cmd/segment-indexer
RUN CGO_ENABLED=0 go build -trimpath -o /out/janitor ./cmd/janitor

# 최종 스테이지를 scratch 가 아니라 alpine 으로 두는 이유:
# G4(사이드카가 파일을 쓰지도 지우지도 않는다)의 검증이 "touch /recordings/x 가
# Read-only 로 실패한다"는 물리적 실증인데, scratch 면 그 명령을 실행할 셸이 없다.
# 설정값 확인은 "그렇게 적혀 있다"는 증거일 뿐 "실제로 막힌다"는 증거가 아니다.
# 이미지 크기 몇 MB보다 검증 가능성을 택한다.
FROM alpine:3

# tzdata = 컨테이너 안 date 가 TZ 를 제대로 해석하게 한다(D6 UTC 고정 확인용).
RUN apk add --no-cache tzdata ca-certificates

COPY --from=build /out/segment-indexer /usr/local/bin/segment-indexer
COPY --from=build /out/janitor /usr/local/bin/janitor
# root 소유 0644 — USER 10001 이 읽기만 한다. --chmod 로 못 박아 빌드 입력(작업 트리)의 권한과
# 떼어 놓는다. --chmod 는 COPY 가 새로 만드는 부모 디렉터리에도 0644(실행 비트 없음)를 주므로
# 디렉터리는 먼저 만든다(755 — 2026-10-22 실측). 이 경로를 쓰게 하는 POSTGRES_SSLROOTCERT 는 배포
# 정의가 준다(ENV 로 두지 않는다 — 로컬 compose 의 password · prefer 경로에 끼어들지 않게).
RUN mkdir -p /etc/pokeclip/rds-ca
COPY --from=build --chmod=0644 /src/certs/rds-global-bundle.pem /etc/pokeclip/rds-ca/global-bundle.pem

# 비특권 사용자로 돌린다. recordings 는 :ro 로 붙지만, 권한을 낮춰 두면
# 컨테이너가 뚫렸을 때 할 수 있는 일이 한 단계 더 줄어든다.
RUN adduser -D -H -u 10001 sidecar
USER 10001:10001

ENTRYPOINT ["/usr/local/bin/segment-indexer"]
