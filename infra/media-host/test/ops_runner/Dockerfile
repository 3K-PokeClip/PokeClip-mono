# 송출 인증 계약 시험(ingest_auth_contract.sh)의 운영 스크립트 보조 이미지 —
# 시험 전용(prod 에 올리지 않는다). 태그 pcbench-m15-ingest-auth/ops:local.
#
# C13 · C15 · C18 이 bin/ 의 운영 스크립트를 이 이미지 컨테이너 안에서 돌린다.
# 거부 목록을 쓰는 쪽(deny-sync)과 읽는 쪽(응답기)을 한 리눅스 커널에 두려는
# 것이다 — 맥 호스트가 rename 한 파일을 컨테이너가 virtiofs 로 읽으면 첫
# 읽기가 옛 크기로 잘렸다(M15 c18-flake 조사 · 2026-10-24).
#
# 판은 모두 다이제스트 고정이고, 빌드 때 아무것도 받아 설치하지 않는다
# (COPY --from 만).
#   - 실행 바탕: prod 상자와 같은 Amazon Linux 2023(bash 5 · GNU coreutils ·
#     gawk). 판 = infra/media-host README 「판 고정」 의 AL2023 저장소 조회
#     기준 release 2023.12.20260930.
#   - compose 플러그인: 같은 표의 prod 판 5.5.0.
#   - docker CLI: 이 시험이 지금까지 운영 스크립트를 돌린 맥 호스트 CLI 와 같은
#     29.8.2(정적 바이너리).
FROM docker.io/docker/compose-bin:v5.5.0@sha256:962d5ea7017e5ef425bfa47e492efa2c27d0492e8af58c497011e73b2ce98ee0 AS compose
FROM docker.io/library/docker:29.8.2-cli@sha256:35cfcf893c20f2931f4909151b5407aaaeab711277de7ce8b8f521f095d54c32 AS cli

FROM docker.io/library/amazonlinux:2023.12.20260930.0@sha256:eb81ff44c54c58c2895b0ef38aa6b7e0fcff797074a4c27b4321a523297f9ffc
COPY --chown=0:0 --from=cli /usr/local/bin/docker /usr/local/bin/docker
COPY --chown=0:0 --from=compose /docker-compose \
  /usr/local/libexec/docker/cli-plugins/docker-compose
LABEL com.pokeclip.bench=m15-ingest-auth
# prod 처럼 root 로 돈다 — 운영 스크립트가 거부 목록 · env 파일의 소유를 root
# 로 맞춘다(chown).
USER 0:0
