#!/usr/bin/env bash
#
# bin/paths-count.sh 시험 — 계획 M2 c5 「c5 운영 확인 스크립트」 의
# paths-count 와 kty 85 · 86차(자격 필수 · 401 은 auth).
#
# 스크립트를 빈 환경(env -i)에서 돌린다. PATH 맨 앞에는 가짜 docker · aws ·
# curl · journalctl 과 grep 감시 래퍼(test/ops_fakebin)만 둔다. 가짜 docker
# exec 는 사이드카 컨테이너 안을 흉내 내 스크립트의 고정 sh -c 를 진짜
# /bin/sh 로 돌리고, 그 안의 nc 는 가짜(요청 기록 · 심은 응답), base64 · tr 는
# 감시 래퍼다.
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/paths_count_test.sh
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
readonly paths_script='bin/paths-count.sh'

#######################################
# 실패 판정 — 표준 출력이 「PATHS FAIL <단계>」 한 줄이고 종료 코드가 0 이
# 아니다(0 건 줄이 아니다).
# Arguments:
#   단계, 기대 종료 코드(기본 nonzero).
#######################################
paths_expect_fail() {
  ops_fixture::expect_line "PATHS FAIL $1" "${2:-nonzero}"
}

#######################################
# 성공 판정 — 「PATHS <n>」 한 줄 · 종료 0 · 경로 이름 0건.
# Arguments:
#   건수, 출력에 없어야 할 이름 ….
#######################################
expect_count() {
  local ok=0 n="$1" name
  shift
  ops_fixture::expect_line "PATHS ${n}" 0 || ok=1
  for name in "$@"; do
    if grep -q -F -- "${name}" "${ops_dir}/out" "${ops_dir}/err"; then
      harness::add_reason "출력에 경로 이름 ${name}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 1 — 픽스처 꼴(송출 중 · 오프라인 · 리더만 섞임) → 항목 전부 센 한 줄 ·
# 이름 0건 · 자격은 요청에만.
case_mixed() {
  local ok=0
  paths::setup "${PATHS_MIXED_JSON}"
  paths::run || return 1
  expect_count 6 "${PATHS_ID_A}" "${PATHS_ID_B}" pcbench-offline \
    pcbench-should-not-print || ok=1
  paths::expect_request_and_no_leak || ok=1
  return "${ok}"
}

# 사례 2 — 빈 목록(pageCount 0) → PATHS 0.
case_empty() {
  paths::setup "${PATHS_EMPTY_JSON}"
  paths::run || return 1
  expect_count 0
}

# 사례 3 — pageCount 2 → PATHS FAIL incomplete.
case_multi_page() {
  paths::setup "${PATHS_MULTI_PAGE_JSON}"
  paths::run || return 1
  paths_expect_fail incomplete
}

# 사례 4 — 100 · 101 경계(101 개는 pageCount 2 로 옴).
case_boundary() {
  local ok=0
  paths::setup "$(paths::json_items 100 1)"
  paths::run || return 1
  expect_count 100 || ok=1
  paths::setup "$(paths::json_items 101 2)"
  paths::run || return 1
  paths_expect_fail incomplete || ok=1
  paths::setup "$(paths::json_items 101 1)"
  paths::run || return 1
  expect_count 101 || ok=1
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/paths_count_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap ops_fixture::cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  ops_fixture::init paths-count

  harness::run_case 1 '픽스처 꼴 → PATHS 6 한 줄 · 이름 0 · 자격 노출 0' case_mixed
  harness::run_case 2 '빈 목록 → PATHS 0' case_empty
  harness::run_case 3 'pageCount 2 → PATHS FAIL incomplete' case_multi_page
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

# 사례 13 — 요청 실패와 상한.
case_request_and_timeout() {
  local ok=0
  paths::case_request_failure || ok=1
  paths::case_timeout || ok=1
  return "${ok}"
}

main "$@"
