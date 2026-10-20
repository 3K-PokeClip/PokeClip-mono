#!/usr/bin/env bash
#
# ops/force-diff.sh 시험 — 계획 M2 c5 「복귀 대조 = force-diff.sh」(설계 5-3
# force 6: A − B(송출 중) − 종료 확인 = ∅ 이면 복구 완료).
#
# 입력은 실물 꼴이다 — A · B 는 paths-list.sh 출력(한 줄에 ID 하나, 정렬 안
# 됨), ended 는 사람이 확인 순서대로 덧붙인 탭 구분 3열(ID · 확인 시각 · 확인
# 경로)이다. 중복 줄 · 하이픈 · 대소문자 섞인 ID 를 넣는다.
#
# 제품 스크립트가 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/force_diff_test.sh
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

readonly SCRIPT="${MEDIA_HOST_DIR}/ops/force-diff.sh"
readonly CASE_COUNT=10
# write_inputs 의 A · B 파일 ID 줄 수(paths-list 의 「PATHS LIST <n>」).
readonly A_LINES=8
readonly B_LINES=3
readonly RUN_TIMEOUT=20

root=""
dir=""
status=0

#######################################
# 사례 디렉터리를 새로 만든다.
# Globals:
#   root, dir
#######################################
new_case() {
  dir="$(mktemp -d "${root}/case.XXXXXX")"
  mkdir -p -- "${dir}/tmp"
}

#######################################
# 스크립트를 빈 환경에서 돌린다(표준 출력 out · 표준 오류 err).
# Globals:
#   dir, status
# Arguments:
#   환경(NAME=값 …), --, 스크립트 인자 ….
#######################################
run_diff() {
  local envs=()
  while (( $# > 0 )) && [[ "$1" != -- ]]; do
    envs+=("$1")
    shift
  done
  (( $# == 0 )) || shift
  status=0
  if [[ ! -x "${SCRIPT}" ]]; then
    harness::add_reason 'ops/force-diff.sh 없음'
    status=127
    return 1
  fi
  # 스크립트의 표준 오류는 그 프로세스 안에서 err 로 돌린다 — 작업 제어
  # 경고(child setpgid 경쟁)가 섞이지 않게.
  # shellcheck disable=SC2016 # $0 · $@ 는 sh -c 안에서 펼친다.
  harness::bounded "${RUN_TIMEOUT}" /bin/sh -c 'exec "$@" 2>"$0"' \
    "${dir}/err" env -i "PATH=/usr/bin:/bin" "TMPDIR=${dir}/tmp" \
    ${envs[@]+"${envs[@]}"} "${SCRIPT}" "$@" </dev/null >"${dir}/out" \
    2>"${dir}/harness.err" || status=$?
  if (( status == 124 )); then
    harness::add_reason "${RUN_TIMEOUT}초 안에 안 끝남"
    return 1
  fi
}

#######################################
# 출력 · 종료 코드 · 스크래치 잔여를 본다.
# Globals:
#   dir, status
# Arguments:
#   기대 출력(줄바꿈으로 이은 전체), 기대 종료 코드.
#######################################
expect_output() {
  local ok=0
  if [[ "$(cat "${dir}/out")" != "$1" ]]; then
    harness::add_reason "출력이 다름: $(harness::log_tail "${dir}/out") / \
오류: $(harness::log_tail "${dir}/err")"
    ok=1
  fi
  if (( status != $2 )); then
    harness::add_reason "종료 코드 ${status}(기대 $2)"
    ok=1
  fi
  if [[ -n "$(find "${dir}/tmp" -mindepth 1 | head -n 1)" ]]; then
    harness::add_reason '스크래치 잔여'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 실물 꼴 입력 셋을 쓴다.
# Globals:
#   dir
#######################################
write_inputs() {
  printf '%s\n' 01JZZZZZZZZZZZZZZZZZZZZZZ9 probe-stream-B \
    01JAAAAAAAAAAAAAAAAAAAAAA1 Mixed-Case-Id probe-stream-B \
    01JMMMMMMMMMMMMMMMMMMMMMM5 lower-case-id Zeta-upper >"${dir}/A.txt"
  printf '%s\n' 01JMMMMMMMMMMMMMMMMMMMMMM5 new-since-force probe-stream-B \
    >"${dir}/B.txt"
  printf '%s\t%s\t%s\n' \
    Mixed-Case-Id '2026-10-20T01:05:00+09:00' '전화' \
    unrelated-id '2026-10-20T01:06:00+09:00' 'DM' \
    Mixed-Case-Id '2026-10-20T01:07:00+09:00' '전화(재확인)' \
    >"${dir}/ended.txt"
}

#######################################
# 실물 꼴 입력 셋을 개수 인자와 함께 넘긴다.
# Globals:
#   dir
# Arguments:
#   환경(NAME=값 …)(없어도 됨).
#######################################
run_inputs() {
  run_diff "$@" -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" \
    "${A_LINES}" "${B_LINES}"
}

# 사례 1 — 정렬 안 된 A · B 와 실물 3열 ended → 남은 목록(C 정렬) · 건수.
case_remaining() {
  new_case
  write_inputs
  run_inputs || return 1
  expect_output "FORCE REMAINING 4
01JAAAAAAAAAAAAAAAAAAAAAA1
01JZZZZZZZZZZZZZZZZZZZZZZ9
Zeta-upper
lower-case-id" 0
}

# 사례 2 — ended 가 미복귀 전부를 덮으면 FORCE REMAINING 0 한 줄.
case_all_covered() {
  new_case
  write_inputs
  printf '%s\t%s\t%s\n' 01JAAAAAAAAAAAAAAAAAAAAAA1 t p \
    01JZZZZZZZZZZZZZZZZZZZZZZ9 t p lower-case-id t p \
    Zeta-upper t p >>"${dir}/ended.txt"
  run_inputs || return 1
  expect_output 'FORCE REMAINING 0' 0
}

# 사례 3 — 빈 줄은 무시하고, 빈 B · 빈 ended 도 받는다.
case_blank_and_empty() {
  new_case
  printf '\nprobe-a\n\nprobe-b\n' >"${dir}/A.txt"
  : >"${dir}/B.txt"
  : >"${dir}/ended.txt"
  run_diff -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" 2 0 \
    || return 1
  expect_output 'FORCE REMAINING 2
probe-a
probe-b' 0
}

# 사례 4 — 호출 환경의 로캘과 상관없이 같은 결과(LC_ALL=C 고정 — 대문자
# Zeta-upper 가 소문자 lower-case-id 앞).
case_locale() {
  new_case
  write_inputs
  run_inputs LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 || return 1
  expect_output "FORCE REMAINING 4
01JAAAAAAAAAAAAAAAAAAAAAA1
01JZZZZZZZZZZZZZZZZZZZZZZ9
Zeta-upper
lower-case-id" 0
}

# 사례 5 — ID 꼴이 아닌 줄(실패 판정 줄 · CR · 공백)이 섞인 입력은 거부한다 —
# 남은 목록을 지어내지 않는다.
case_bad_lines() {
  local ok=0 which line
  for which in A B ended; do
    for line in 'PATHS FAIL auth' $'probe-a\r' ' probe-a'; do
      new_case
      write_inputs
      printf '%s\n' "${line}" >>"${dir}/${which}.txt"
      run_inputs || return 1
      if ! expect_output "FORCE FAIL input ${which}" 1; then
        harness::add_reason "(${which})"
        ok=1
      fi
    done
  done
  return "${ok}"
}

# 사례 6 — 없는 · 읽을 수 없는 입력 파일 → FORCE FAIL input <이름>.
case_missing_input() {
  local ok=0
  new_case
  write_inputs
  run_diff -- "${dir}/A.txt" "${dir}/nope.txt" "${dir}/ended.txt" \
    "${A_LINES}" "${B_LINES}" || return 1
  expect_output 'FORCE FAIL input B' 1 || ok=1
  new_case
  write_inputs
  run_diff -- "${dir}" "${dir}/B.txt" "${dir}/ended.txt" "${A_LINES}" \
    "${B_LINES}" || return 1
  expect_output 'FORCE FAIL input A' 1 || ok=1
  return "${ok}"
}

# 사례 7 — 인자 수가 다섯이 아니거나 개수가 음 아닌 정수가 아니면 사용법
# (종료 2).
case_usage() {
  local ok=0 count
  new_case
  write_inputs
  run_diff -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" || return 1
  expect_output 'FORCE FAIL usage' 2 || ok=1
  run_diff -- || return 1
  expect_output 'FORCE FAIL usage' 2 || ok=1
  for count in '' x -1 08; do
    run_diff -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" \
      "${count}" "${B_LINES}" || return 1
    if ! expect_output 'FORCE FAIL usage' 2; then
      harness::add_reason "(A 개수 「${count}」)"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 8 — 파일의 ID 줄 수가 개수 인자와 다르면(SSM 출력 한도 등으로 목록이
# 잘림) 대조하지 않는다.
case_count_mismatch() {
  local ok=0
  new_case
  write_inputs
  run_diff -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" \
    "$((A_LINES + 1))" "${B_LINES}" || return 1
  expect_output 'FORCE FAIL count A' 1 || ok=1
  run_diff -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" \
    "${A_LINES}" "$((B_LINES - 1))" || return 1
  expect_output 'FORCE FAIL count B' 1 || ok=1
  return "${ok}"
}

# 사례 9 — A 에 ID 가 없으면(실패한 조회를 저장한 빈 파일) 「복구 완료」 가
# 아니라 거부한다.
case_empty_a() {
  new_case
  write_inputs
  : >"${dir}/A.txt"
  run_diff -- "${dir}/A.txt" "${dir}/B.txt" "${dir}/ended.txt" 0 \
    "${B_LINES}" || return 1
  expect_output 'FORCE FAIL empty A' 1
}

# 사례 10 — 꼴 검사의 grep 이 오류로 끝나면(종료 2) 「꼴 밖 줄 없음」 이 아니라
# 그 입력을 거부한다.
case_grep_error() {
  new_case
  write_inputs
  mkdir -p -- "${dir}/bin"
  printf '#!/bin/sh\nexit 2\n' >"${dir}/bin/grep"
  chmod +x "${dir}/bin/grep"
  run_inputs "PATH=${dir}/bin:/usr/bin:/bin" || return 1
  expect_output 'FORCE FAIL input A' 1
}

cleanup() {
  local code=$?
  harness::end_last_group
  if [[ -n "${root}" ]]; then
    rm -rf -- "${root:?}"
  fi
  exit "${code}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/force_diff_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  root="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy-force.XXXXXX")"

  harness::run_case 1 '정렬 안 된 A · B · 실물 ended → 남은 목록' case_remaining
  harness::run_case 2 'ended 가 미복귀 전부 → FORCE REMAINING 0' case_all_covered
  harness::run_case 3 '빈 줄 무시 · 빈 B · ended' case_blank_and_empty
  harness::run_case 4 '로캘 무관(LC_ALL=C)' case_locale
  harness::run_case 5 'ID 꼴 아닌 줄 → FORCE FAIL input' case_bad_lines
  harness::run_case 6 '없는 입력 → FORCE FAIL input' case_missing_input
  harness::run_case 7 '인자 수 · 개수 꼴 → 사용법' case_usage
  harness::run_case 8 'ID 줄 수 ≠ 개수 → FORCE FAIL count' case_count_mismatch
  harness::run_case 9 '빈 A → FORCE FAIL empty A' case_empty_a
  harness::run_case 10 '꼴 검사 grep 오류 → FORCE FAIL input' case_grep_error

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
