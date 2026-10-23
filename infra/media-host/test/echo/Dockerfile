# 계약 시험의 에코 상류 · 역할 도구 이미지(시험 전용 — prod 에 올리지 않는다).
#
# 쓰는 시험 둘이 각자 태그로 빌드한다:
#   - 앞단 프록시 계약(proxy_contract.sh) — pcbench-edge-proxy/echo:local. 태그 접두
#     pcbench-edge-proxy/ 와 아래 라벨(edge-proxy)이 그 실험 정리 명령의 표적이다.
#   - 송출 인증 계약(ingest_auth_contract.sh — 가짜 응답기 authstub · 가짜 4C
#     resolvestub) — pcbench-m15-ingest-auth/echo:local. 빌드할 때
#     --label com.pokeclip.bench=m15-ingest-auth 로 아래 라벨을 덮어쓴다.

# 빌드 스테이지는 저장소의 다른 Go 빌드와 같은 1.27 계열이다. alpine 판까지 적은 태그를 쓰는
# 이유: 로컬에 받아 둔 이미지가 이 태그라 시험이 망 없이 빌드된다.
FROM golang:1.27-alpine3.24 AS build
WORKDIR /src
COPY go.mod *.go ./
# CGO_ENABLED=0 = 순수 정적 바이너리. 실행 스테이지가 scratch 라 동적 링커가 없다.
RUN CGO_ENABLED=0 go build -trimpath -o /out/echo .

# scratch — 셸이 없어도 된다. 하위 명령(probe · fetch)이 셸 · curl 몫을 한다.
FROM scratch
COPY --from=build /out/echo /echo
LABEL com.pokeclip.bench=edge-proxy
# MediaMTX 이미지와 같은 비root 계정 번호다(media/Dockerfile.mtxhook 의 USER).
USER 10002:10002
ENTRYPOINT ["/echo"]
