#!/usr/bin/env bash
#
# guard.sh 의 규칙을 fixture 로 시험한다.
#
# testdata/guard/bad_<규칙>[_<변형>]/ 는 그 규칙 하나만 어기는 Terraform 트리다.
# guard.sh 가 그 트리에서 실패하고, 출력에 그 규칙 이름이 나와야 통과다.
# testdata/guard/ok/ 는 모든 규칙을 지키는 트리라 guard.sh 가 통과해야 한다.
# 경로를 겨누는 규칙(R4 등)의 fixture 는 그 경로를 트리 안에 그대로 둔다.
#
# 사용: bash infra/terraform/scripts/guard_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly GUARD="${SCRIPT_DIR}/guard.sh"
readonly FIXTURES="${SCRIPT_DIR}/testdata/guard"

failures=0

#######################################
# 시험 하나의 실패를 알리고 센다.
# Globals:
#   failures
# Arguments:
#   실패 설명.
#######################################
fail() {
  echo "FAIL: $*" >&2
  failures=$((failures + 1))
}

#######################################
# bad fixture 하나가 기대한 규칙으로 실패하는지 본다.
# Arguments:
#   fixture 디렉터리 경로.
#######################################
expect_violation() {
  local dir="$1"
  local name rule output status
  name="$(basename -- "${dir}")"
  # bad_R1_data → R1: 첫 밑줄 뒤 토막이 규칙 이름이다.
  rule="${name#bad_}"
  rule="${rule%%_*}"
  output="$(bash "${GUARD}" "${dir}" 2>&1)"
  status=$?
  if [[ "${status}" -ne 1 ]]; then
    fail "${name}: 종료 코드 1 을 기대했으나 ${status}"
    return
  fi
  if ! grep -q "^guard: ${rule} " <<<"${output}"; then
    fail "${name}: 출력에 ${rule} 위반이 없음 — ${output}"
    return
  fi
  echo "ok: ${name} → ${rule} 위반"
}

#######################################
# ok fixture 가 위반 0 으로 통과하는지 본다.
# Arguments:
#   fixture 디렉터리 경로.
#######################################
expect_clean() {
  local dir="$1"
  local output status
  output="$(bash "${GUARD}" "${dir}" 2>&1)"
  status=$?
  if [[ "${status}" -ne 0 ]]; then
    fail "ok: 종료 코드 0 을 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: ok → 위반 0"
}

main() {
  local dir
  local count=0
  for dir in "${FIXTURES}"/bad_*/; do
    [[ -d "${dir}" ]] || continue
    expect_violation "${dir%/}"
    count=$((count + 1))
  done
  if [[ "${count}" -eq 0 ]]; then
    fail "bad_* fixture 가 하나도 없음"
  fi
  expect_clean "${FIXTURES}/ok"

  if [[ "${failures}" -ne 0 ]]; then
    echo "guard_test: ${failures}건 실패" >&2
    exit 1
  fi
  echo "guard_test: 전부 통과(bad ${count}개 · ok 1개)"
}

main "$@"
