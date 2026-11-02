#!/usr/bin/env bash
#
# 송출 중인 MediaMTX 경로(ingestStreamId) 목록 — force 1 · 6(설계 5-3)과 리스
# 만료 중단 통지가 쓴다. 상자에서 SSM 으로 돌린다(실행 직전 승인). 출력은 kty
# 로컬에 force-<시각>-A.txt · -B.txt 로 저장해 ops/force-diff.sh 로 대조한다.
#
# 사이드카 컨테이너 안의 고정 sh -c 로 9997 을 자격과 함께 조회한다(조회 방식 ·
# 자격 · 송출 중 판정은 lib/mtx_paths.sh — 사이드카 폴러와 같은 규칙).
#
# 출력:
#   성공 — 표준 출력에 송출 중 경로 이름만 한 줄에 하나(응답 순서). 송출 중이
#          없으면 빈 출력. 표준 오류에는 개수 줄 「PATHS LIST <n>」 하나 —
#          저장한 목록이 SSM 출력 한도 등으로 잘렸는지 force-diff.sh 가 이
#          개수로 대조한다. 종료 0. 목록은 종료 0 일 때만 저장한다.
#   실패 — 표준 출력은 비우고 판정 줄 하나를 표준 오류에 낸다(목록 파일에 실패
#          줄이 섞여 ID 로 읽히지 않게). 종료 1(인자 오류는 2).
#          PATHS LIST INCOMPLETE  여러 페이지(pageCount > 1) — force · 복구 완료
#                                 판정을 하지 않는다.
#          PATHS FAIL <단계>      paths-count.sh 와 같은 단계 이름.
#
# 인자는 받지 않는다. 읽는 변수는 lib/ops.sh 머리 주석.

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
  local count=0
  if (( $# != 0 )); then
    ops::say 'PATHS FAIL usage' >&2
    exit 2
  fi
  trap ops::on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # 표준 오류는 판정 줄 자리다 — mktemp 의 진단은 버리고 단계 이름만 낸다.
  if ! ops::make_scratch 2>/dev/null; then
    ops::say 'PATHS FAIL scratch' >&2
    exit 1
  fi
  if ! mtx_paths::query; then
    ops::say "PATHS FAIL ${mtx_paths_step}" >&2
    exit 1
  fi
  if (( mtx_paths_page_count > 1 )); then
    ops::say 'PATHS LIST INCOMPLETE' >&2
    exit 1
  fi
  if [[ -n "${mtx_paths_publishing}" ]]; then
    printf '%s\n' "${mtx_paths_publishing}"
    count="$(printf '%s\n' "${mtx_paths_publishing}" | awk 'END { print NR }')"
  fi
  ops::say "PATHS LIST ${count}" >&2
}

main "$@"
