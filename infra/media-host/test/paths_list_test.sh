#!/usr/bin/env bash
#
# bin/paths-list.sh 시험 — 계획 M2 c5 「c5 운영 확인 스크립트」 의 paths-list
# (송출 중 판정 = 사이드카 폴러 규칙 · 전수성 · 빈 목록)와 kty 85 · 86차.
#
# 판정: 성공이면 표준 출력에 송출 중 경로 이름만 한 줄에 하나(목록 파일로
# 저장된다)와 표준 오류에 개수 줄 「PATHS LIST <n>」(force-diff 가 잘린 목록을
# 가린다), 실패면 표준 출력은 비우고 판정 줄은 표준 오류 — 실패 줄이 목록
# 파일에 섞여 force-diff 의 ID 로 읽히지 않게. 실행 도구는 paths_count_test 와
# 같다(test/lib/ops_fixture.sh).
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/paths_list_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"
# shellcheck source=lib/ops_fixture.sh
source "${TEST_DIR}/lib/ops_fixture.sh"
# shellcheck source=lib/paths_cases.sh
source "${TEST_DIR}/lib/paths_cases.sh"

readonly CASE_COUNT=15
# paths_cases.sh 가 읽는다.
# shellcheck disable=SC2034
readonly paths_script='bin/paths-list.sh'

#######################################
# 실패 판정 줄(표준 오류 한 줄)과 빈 표준 출력 · 종료 코드를 본다.
# Arguments:
#   판정 줄, 기대 종료 코드.
#######################################
expect_fail_line() {
  local ok=0
  if [[ -s "${ops_dir}/out" ]]; then
    harness::add_reason "실패인데 표준 출력이 비지 않음: $(harness::log_tail \
"${ops_dir}/out")"
    ok=1
  fi
  if [[ "$(awk 'END { print NR }' "${ops_dir}/err")" != 1 ]] \
    || ! grep -q -x -F -- "$1" "${ops_dir}/err"; then
    harness::add_reason "표준 오류가 「$1」 한 줄이 아님: $(harness::log_tail \
"${ops_dir}/err")"
    ok=1
  fi
  ops_fixture::expect_status "$2" || ok=1
  return "${ok}"
}

#######################################
# 실패 판정(paths_cases.sh 가 부른다).
# Arguments:
#   단계, 기대 종료 코드(기본 nonzero).
#######################################
paths_expect_fail() {
  expect_fail_line "PATHS FAIL $1" "${2:-nonzero}"
}

#######################################
# 성공 판정 — 표준 출력이 정확히 이 줄들(순서대로) · 표준 오류가 개수 줄
# 「PATHS LIST <n>」 하나 · 종료 0.
# Arguments:
#   기대 이름 …(없으면 빈 출력).
#######################################
expect_names() {
  local ok=0 expected=""
  if (( $# > 0 )); then
    expected="$(printf '%s\n' "$@")"
  fi
  if [[ "$(cat "${ops_dir}/out")" != "${expected}" ]]; then
    harness::add_reason "목록이 다름: $(harness::log_tail "${ops_dir}/out")"
    ok=1
  fi
  if [[ "$(cat "${ops_dir}/err")" != "PATHS LIST $#" ]]; then
    harness::add_reason "표준 오류가 「PATHS LIST $#」 한 줄이 아님: \
$(harness::log_tail "${ops_dir}/err")"
    ok=1
  fi
  ops_fixture::expect_status 0 || ok=1
  return "${ok}"
}

# 사례 1 — 픽스처 꼴 → 송출 중 이름만(online true 이고 source 가 null 이
# 아님) · 그 밖 필드 0건 · 자격은 요청에만.
case_mixed() {
  local ok=0
  paths::setup "${PATHS_MIXED_JSON}"
  paths::run || return 1
  expect_names "${PATHS_ID_A}" "${PATHS_ID_B}" || ok=1
  if grep -q -F -e srtConn -e pcbench-should-not-print -e pcbench-offline \
    "${ops_dir}/out"; then
    harness::add_reason '이름 밖 필드가 출력에 있음'
    ok=1
  fi
  paths::expect_request_and_no_leak || ok=1
  return "${ok}"
}

# 사례 2 — 빈 목록 → 빈 출력 · 종료 0.
case_empty() {
  paths::setup "${PATHS_EMPTY_JSON}"
  paths::run || return 1
  expect_names
}

# 사례 3 — pageCount 2 → PATHS LIST INCOMPLETE · 표준 출력 빈.
case_multi_page() {
  paths::setup "${PATHS_MULTI_PAGE_JSON}"
  paths::run || return 1
  expect_fail_line 'PATHS LIST INCOMPLETE' nonzero
}

# 사례 4 — 100 · 101 경계.
case_boundary() {
  local ok=0 names=() i
  for (( i = 1; i <= 101; i++ )); do
    names+=("$(printf 'pcbench-%03d' "${i}")")
  done
  paths::setup "$(paths::json_items 100 1)"
  paths::run || return 1
  expect_names "${names[@]:0:100}" || ok=1
  paths::setup "$(paths::json_items 101 2)"
  paths::run || return 1
  expect_fail_line 'PATHS LIST INCOMPLETE' nonzero || ok=1
  paths::setup "$(paths::json_items 101 1)"
  paths::run || return 1
  expect_names "${names[@]}" || ok=1
  return "${ok}"
}

# 사례 13 — 요청 실패와 상한.
case_request_and_timeout() {
  local ok=0
  paths::case_request_failure || ok=1
  paths::case_timeout || ok=1
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/paths_list_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap ops_fixture::cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops_fixture::init paths-list

  harness::run_case 1 '픽스처 꼴 → 송출 중 이름만 · 자격 노출 0' case_mixed
  harness::run_case 2 '빈 목록 → 빈 출력 · 종료 0' case_empty
  harness::run_case 3 'pageCount 2 → PATHS LIST INCOMPLETE' case_multi_page
  harness::run_case 4 '100 · 101 경계' case_boundary
  harness::run_case 5 '자격 하나라도 없음 → credentials · 요청 0' \
    paths::case_credentials_missing
  harness::run_case 6 '비밀번호 끝 줄바꿈 없음 → 그대로 요청' \
    paths::case_password_no_newline
  harness::run_case 7 '401 → auth · 500 → http' paths::case_http_status
  harness::run_case 8 '깨진 JSON → response' paths::case_malformed
  harness::run_case 9 '깨진 HTTP 틀 → response' paths::case_malformed_http
  harness::run_case 10 'Content-Length 없음 → 본문 읽음' \
    paths::case_no_content_length
  harness::run_case 11 '사이드카 0 · 2 · 정지 · ps 실패 → sidecar · exec 0' \
    paths::case_sidecar_lookup
  harness::run_case 12 '프로젝트 기본값 pokeclip-media' paths::case_project_default
  harness::run_case 13 'nc 실패 → request · 끝나지 않음 → timeout' \
    case_request_and_timeout
  harness::run_case 14 '인자 · 상한 값 오류 → docker 0' \
    paths::case_usage_and_config
  harness::run_case 15 '8 MiB 넘는 응답 → response · 항목 1000 개 상한 안' \
    paths::case_large_response

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
