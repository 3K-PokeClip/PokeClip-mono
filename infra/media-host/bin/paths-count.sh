#!/usr/bin/env bash
#
# MediaMTX 경로 목록 건수 — 교체 런북 5-3 의 4(ingest 전환 직전 게이트)와 운영
# MediaMTX 설정의 로컬 compose 실측이 쓴다. 상자에서 SSM 으로 돌린다(실행 직전 승인).
#
# 사이드카 컨테이너 안의 고정 sh -c 로 9997 을 자격과 함께 조회한다(조회 방식 ·
# 자격 규칙은 lib/mtx_paths.sh). 경로 이름(streamid)은 내지 않는다.
#
# 출력(표준 출력 한 줄):
#   PATHS <n>               경로 항목 수(송출 중이 아닌 항목 포함). 빈 목록은 0.
#   PATHS FAIL incomplete   응답이 여러 페이지(pageCount > 1) — 원자 스냅샷 아님.
#   PATHS FAIL <단계>       usage · config · sidecar · credentials · request ·
#                           timeout · auth(401) · http · response.
# 종료 코드: 0 셈 / 1 실패 / 2 인자 오류.
#
# 인자는 받지 않는다. 읽는 변수는 lib/ops.sh 머리 주석(MEDIA_COMPOSE_PROJECT ·
# MEDIA_OPS_TIMEOUT).

# shellcheck source-path=SCRIPTDIR

set -euo pipefail
set +x +a
umask 077

BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/ops.sh
source "${BIN_DIR}/lib/ops.sh"
# shellcheck source=lib/mtx_paths.sh
source "${BIN_DIR}/lib/mtx_paths.sh"

main() {
  if (( $# != 0 )); then
    ops::say 'PATHS FAIL usage'
    exit 2
  fi
  trap ops::on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops::make_scratch
  if ! mtx_paths::query; then
    ops::say "PATHS FAIL ${mtx_paths_step}"
    exit 1
  fi
  if (( mtx_paths_page_count > 1 )); then
    ops::say 'PATHS FAIL incomplete'
    exit 1
  fi
  ops::say "PATHS ${mtx_paths_item_count}"
}

main "$@"
