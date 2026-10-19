#!/usr/bin/env bash
#
# 시험 부품 harness.sh 의 상한 실행 · 종료 자체 시험 — docker 없이 돈다.
#
# harness::bounded 는 명령을 자기 프로세스 그룹에서 돌리고, 상한을 넘기면 그
# 그룹을 TERM → 유예 → KILL 로 끝낸다. 아래 꼴에서 상한 안에 끝나고 남는
# 프로세스가 없어야 한다:
#   - TERM 을 무시하는 명령.
#   - TERM 처리기가 새 자식을 띄우고 끝나는 명령 — 새 자식이 표준 출력을 붙잡아
#     출력 수집($(…))이 끝나지 않게 한다.
#   - 부모가 먼저 끝나 재부모화된 자손.
# harness::terminate 는 set -e 아래 정상 경로(이미 다 끝남)에서 0 을 돌려줘야
# 한다 — 부르는 정리 함수의 뒤 줄이 건너뛰어지지 않게.
#
# 각 꼴은 이 실행의 임시 디렉터리 안에 둔 sleep 링크(경로가 고유 표식)를
# 띄우고, 끝난 뒤 그 표식을 가진 프로세스 수를 센다.
#
# 사용: bash infra/media-host/test/harness_selftest.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

SELFTEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SELFTEST_DIR
# shellcheck source=lib/harness.sh
source "${SELFTEST_DIR}/lib/harness.sh"

# 상한(초)과 그 상한에 TERM · KILL 유예를 더한 끝 시간 상한.
readonly LIMIT=1
readonly MAX_ELAPSED=$((LIMIT + 2 * HARNESS_KILL_GRACE + 2))

selftest_root=""
marker=""
marker_sleep=""

remove_selftest_root() {
  local status=$?
  if [[ -n "${marker}" ]]; then
    pkill -KILL -f -- "${marker}" 2>/dev/null || true
  fi
  if [[ -n "${selftest_root}" ]]; then
    rm -rf -- "${selftest_root:?}"
  fi
  exit "${status}"
}

#######################################
# 표식을 가진 프로세스 수를 낸다(조회 실패는 0 이 아니라 실패).
# Globals:
#   marker
#######################################
leftover_count() {
  local out status=0
  out="$(pgrep -f -- "${marker}")" || status=$?
  case "${status}" in
    0) printf '%s\n' "${out}" | awk 'END { print NR }' ;;
    1) printf '0\n' ;;
    *) return "${status}" ;;
  esac
}

#######################################
# 꼴 하나를 돌려 종료 코드 · 걸린 시간 · 잔여를 판정한다.
# Arguments:
#   이름, 기대 종료 코드, 출력 수집 여부(1|0), bash -c 프로그램.
#######################################
check_shape() {
  local name="$1" want="$2" capture="$3" program="$4" status=0 start left out
  start=${SECONDS}
  if (( capture )); then
    out="$(harness::bounded "${LIMIT}" bash -c "${program}" \
      "${marker_sleep}")" || status=$?
  else
    harness::bounded "${LIMIT}" bash -c "${program}" "${marker_sleep}" \
      >/dev/null 2>&1 || status=$?
  fi
  sleep 0.5
  left="$(leftover_count)" || left='조회 실패'
  if [[ "${status}" == "${want}" ]] && (( SECONDS - start <= MAX_ELAPSED )) \
    && [[ "${left}" == 0 ]]; then
    harness::report "${name}" PASS "종료 ${status} · $((SECONDS - start))초"
  else
    harness::report "${name}" FAIL '상한 · 잔여' "종료 ${status}(기대 ${want}) · \
$((SECONDS - start))초(상한 ${MAX_ELAPSED}) · 잔여 ${left}"
  fi
}

#######################################
# harness::terminate — set -e 셸에서 정상 경로(TERM 으로 바로 끝남)도 0 이고
# 뒤 줄이 돈다.
# Globals:
#   selftest_root
#######################################
check_terminate_set_e() {
  local out="${selftest_root}/terminate-set-e.out"
  # 배경 하위 셸로 돌린다 — `$( … ) || …` 꼴이면 그 안에서 set -e 가 꺼진다.
  (
    set -e
    sleep 300 &
    harness::terminate $!
    printf 'after\n'
  ) >"${out}" 2>/dev/null &
  wait "$!" || true
  out="$(cat -- "${out}")"
  if [[ "${out}" == after ]]; then
    harness::report terminate-set-e PASS 'set -e 아래 뒤 줄 실행'
  else
    harness::report terminate-set-e FAIL 'set -e 아래 뒤 줄 실행' \
      "출력 「${out}」"
  fi
}

main() {
  trap remove_selftest_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  selftest_root="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy-hst.XXXXXX")"
  marker="${selftest_root##*/}"
  marker_sleep="${selftest_root}/sleep"
  ln -s -- "$(command -v sleep)" "${marker_sleep}"

  # $0 은 표식 경로의 sleep 이다 — pgrep 으로 찾는다.
  # shellcheck disable=SC2016 # 작은따옴표 안은 새 bash 가 펼친다.
  check_shape term-ignore 124 0 \
    'trap "" TERM; "$0" 60 & wait'
  # shellcheck disable=SC2016
  check_shape term-spawn-holds-stdout 124 1 \
    'trap "\"\$0\" 60 & exit 0" TERM; "$0" 60 & wait'
  # shellcheck disable=SC2016
  check_shape reparented 124 1 \
    '( "$0" 60 & ); "$0" 60'
  check_shape exit-code 7 1 'exit 7'
  check_terminate_set_e

  harness::summary 0 term-ignore term-spawn-holds-stdout reparented \
    exit-code terminate-set-e
}

main "$@"
