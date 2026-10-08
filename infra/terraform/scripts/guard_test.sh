#!/usr/bin/env bash
#
# guard.sh 의 규칙을 fixture 로 시험한다.
#
# testdata/guard/bad_<규칙>[_<변형>]/ 는 그 규칙 하나만 어기는 Terraform 트리다.
# guard.sh 가 그 트리에서 실패하고, 출력에 그 규칙 이름이 나와야 통과다.
# testdata/guard/ok/ 는 모든 규칙을 지키는 트리라 guard.sh 가 통과해야 한다.
# testdata/guard/err_<이름>/ 은 검사할 수 없는 트리라 guard.sh 가 종료 코드 2
# 로 실패해야 한다(fail-closed). 읽을 수 없는 파일은 git 에 둘 수 없어 시험이
# 임시 디렉터리에 만든다. HCL 로 틀린 err fixture 는 `terraform fmt
# -recursive` 를 깨지 않게 *.tf.in 으로 두고, 시험이 임시 디렉터리에 *.tf 로
# 복사해 돌린다.
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
  if [[ $'\n'"${output}" != *$'\n'"guard: ${rule} "* ]]; then
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

#######################################
# 검사할 수 없는 트리에서 guard.sh 가 종료 코드 2 로 멈추는지 본다.
# Arguments:
#   시험 이름, 트리 디렉터리 경로.
#######################################
expect_error() {
  local name="$1" dir="$2"
  local output status
  output="$(bash "${GUARD}" "${dir}" 2>&1)"
  status=$?
  if [[ "${status}" -ne 2 ]]; then
    fail "${name}: 종료 코드 2 를 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: ${name} → 검사 불가(종료 2)"
}

#######################################
# err fixture 하나를 시험한다. *.tf.in 이 있으면 임시 디렉터리에 *.tf 로
# 복사해 돌린다.
# Arguments:
#   fixture 디렉터리 경로.
#######################################
expect_fixture_error() {
  local dir="$1"
  local name tmp src
  name="$(basename -- "${dir}")"
  if ! compgen -G "${dir}/*.tf.in" >/dev/null; then
    expect_error "${name}" "${dir}"
    return
  fi
  tmp="$(mktemp -d)" || {
    fail "${name}: 임시 디렉터리를 만들지 못함"
    return
  }
  for src in "${dir}"/*.tf.in; do
    cp -- "${src}" "${tmp}/$(basename -- "${src}" .in)"
  done
  expect_error "${name}" "${tmp}"
  rm -rf -- "${tmp}"
}

#######################################
# 읽을 수 없는 .tf 가 있으면 위반 0 으로 넘어가지 않고 멈추는지 본다.
# root 는 권한과 상관없이 읽으므로 그때는 건너뛴다.
#######################################
expect_unreadable_error() {
  local tmp
  tmp="$(mktemp -d)" || {
    fail "unreadable: 임시 디렉터리를 만들지 못함"
    return
  }
  cp -R "${FIXTURES}/ok/." "${tmp}/"
  chmod 000 "${tmp}/main.tf"
  if [[ -r "${tmp}/main.tf" ]]; then
    echo "skip: unreadable(현재 사용자가 권한과 상관없이 읽음)"
  else
    expect_error unreadable "${tmp}"
  fi
  chmod 644 "${tmp}/main.tf"
  rm -rf -- "${tmp}"
}

#######################################
# 임시 디렉터리를 못 쓰는 환경에서도 위반을 놓치지 않는지 본다(guard 는
# 임시 파일을 쓰지 않는다).
#######################################
expect_no_tmpfile_dependency() {
  local output status
  output="$(TMPDIR=/nonexistent/guard-test bash "${GUARD}" "${FIXTURES}/bad_R1" 2>&1)"
  status=$?
  if [[ "${status}" -ne 1 || "${output}" != *"guard: R1 "* ]]; then
    fail "no-tmpdir: 종료 1 · R1 위반을 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: no-tmpdir → R1 위반"
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
  for dir in "${FIXTURES}"/err_*/; do
    [[ -d "${dir}" ]] || continue
    expect_fixture_error "${dir%/}"
  done
  expect_unreadable_error
  expect_no_tmpfile_dependency

  if [[ "${failures}" -ne 0 ]]; then
    echo "guard_test: ${failures}건 실패" >&2
    exit 1
  fi
  echo "guard_test: 전부 통과(bad ${count}개 · ok 1개)"
}

main "$@"
