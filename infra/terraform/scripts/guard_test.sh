#!/usr/bin/env bash
#
# guard.sh 와 그 Rego 정책을 시험한다.
#
# 먼저 `conftest verify` 로 정책 단위 시험(policy/guard/*_test.rego)을 돌린다.
# 그다음 fixture 로 guard.sh 를 끝에서 끝까지 돌린다.
# testdata/guard/bad_<규칙>[_<변형>]/ 는 그 규칙 하나만 어기는 Terraform 트리다.
# guard.sh 가 그 트리에서 종료 1 로 실패하고, 출력에 그 규칙 이름이 나와야
# 통과다. testdata/guard/ok/ 는 모든 규칙을 지키는 트리라 guard.sh 가 통과해야
# 한다. testdata/guard/err_<이름>/ 은 Terraform 도 받지 않는(파싱되지 않는)
# 트리라 guard.sh 가 종료 코드 2 로 실패해야 한다(fail-closed). 읽을 수 없는
# 파일은 git 에 둘 수 없어 시험이 임시 디렉터리에 만든다.
# `terraform fmt -recursive` 를 깨는 fixture(파싱 오류 · fmt 가 고쳐 쓸 꼴)는
# *.tf.in 으로 두고, 시험이 임시 디렉터리에 *.tf 로 복사해 돌린다.
# 경로를 겨누는 규칙(R4 · R11 · R12)의 fixture 는 그 경로를 트리 안에 그대로
# 둔다.
#
# R12 는 표지에 따라 다르게 판정한다. 제자리(scripts/testdata 아래)에서 돈
# fixture 는 fixture 표지라 공개 존 루트의 부재를 건너뛰고, 저장소 밖 사본은
# 운영 표지라 부재가 위반이다. testdata/guard/ok_R12/ 는 두 표지 모두 통과해야
# 하고, testdata/guard/op_<규칙>_<변형>/ 은 저장소 밖 사본(운영 표지)으로만
# 돌린다. 저장소 밖 사본은 돌린 뒤 지워 남지 않았는지 본다.
#
# 시험의 임시 디렉터리는 모두 한 뿌리($TMPDIR/guard_test.*) 아래에 만들고,
# 끝날 때와 중단 신호(INT · TERM)를 받을 때 뿌리를 통째로 지운다.
#
# 사용: bash infra/terraform/scripts/guard_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly GUARD="${SCRIPT_DIR}/guard.sh"
readonly FIXTURES="${SCRIPT_DIR}/testdata/guard"
readonly POLICY_DIR="${SCRIPT_DIR}/../policy/guard"
# guard.sh 가 정책에 넘기는 R12 표지(저장소 고정 경로)와, 표지 주입 fixture 의
# conftest.toml 에서 그 경로로 바꿀 자리표시자.
readonly MARKER_DIR="${SCRIPT_DIR}/guard-data"
readonly MARKER_PLACEHOLDER='@GUARD_FIXTURE_MARKER@'

failures=0
run_output=""
run_status=0
# 시험이 만드는 임시 디렉터리의 뿌리. 끝날 때와 중단 신호에 통째로 지운다.
tmp_root=""

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩 — INT · TERM 도 exit 로 여기 온다).
# Globals:
#   tmp_root
#######################################
remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    rm -rf -- "${tmp_root}"
  fi
}

#######################################
# 시험용 임시 디렉터리를 뿌리(tmp_root) 아래에 만든다.
# Globals:
#   tmp_root
# Outputs:
#   새 디렉터리 경로.
#######################################
new_tmp_dir() {
  mktemp -d "${tmp_root}/case.XXXXXX"
}

#######################################
# 셸에 남은 CONFTEST_* 설정(다른 프로젝트의 namespace 등)을 지운다.
#######################################
clear_conftest_env() {
  local name
  for name in $(compgen -e); do
    if [[ "${name}" == CONFTEST_* ]]; then
      unset "${name}"
    fi
  done
}

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
# fixture 에 guard.sh 를 돌린다. *.tf.in 이 있으면 임시 디렉터리에 *.tf 로
# 복사해 돌린다(그 fixture 는 *.tf.in 만 둔다).
# Globals:
#   run_output, run_status
# Arguments:
#   fixture 디렉터리 경로.
# Returns:
#   임시 디렉터리를 만들거나 복사하지 못하면 1(준비 실패 — 빈 입력으로
#   종료 2 를 성공으로 오인하지 않게 한다).
#######################################
run_guard() {
  local dir="$1"
  local tmp src
  if ! compgen -G "${dir}/*.tf.in" >/dev/null; then
    run_output="$(bash "${GUARD}" "${dir}" 2>&1)"
    run_status=$?
    return 0
  fi
  tmp="$(new_tmp_dir)" || return 1
  for src in "${dir}"/*.tf.in; do
    if ! cp -- "${src}" "${tmp}/$(basename -- "${src}" .in)"; then
      rm -rf -- "${tmp}"
      return 1
    fi
  done
  run_output="$(bash "${GUARD}" "${tmp}" 2>&1)"
  run_status=$?
  rm -rf -- "${tmp}"
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
  run_guard "${dir}" || {
    fail "${name}: 시험 준비 실패(임시 디렉터리 · 복사)"
    return
  }
  output="${run_output}"
  status="${run_status}"
  if [[ "${status}" -ne 1 ]]; then
    fail "${name}: 종료 코드 1 을 기대했으나 ${status}"
    return
  fi
  if [[ $'\n'"${output}" != *$'\n'"guard: ${rule} "* ]]; then
    fail "${name}: 출력에 ${rule} 위반이 없음 — ${output}"
    return
  fi
  # 제자리(fixture 표지)에서 돈 다른 규칙의 fixture 에는 R12 가 끼지 않는다.
  if [[ "${rule}" != R12 ]] && ! compgen -G "${dir}/*.tf.in" >/dev/null &&
    [[ $'\n'"${output}" == *$'\n'"guard: R12 "* ]]; then
    fail "${name}: fixture 표지인데 R12 위반이 나옴 — ${output}"
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
  local name output status
  name="$(basename -- "${dir}")"
  output="$(bash "${GUARD}" "${dir}" 2>&1)"
  status=$?
  if [[ "${status}" -ne 0 ]]; then
    fail "${name}: 종료 코드 0 을 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: ${name} → 위반 0"
}

#######################################
# fixture 를 저장소 밖 임시 디렉터리에 복사해 guard.sh 를 돌린다 — 루트가
# scripts/testdata 밖이라 guard.sh 는 운영 표지를 넘긴다. 사본 루트의
# conftest.toml 에 든 자리표시자는 저장소의 fixture 표지 절대 경로로 바꾼다.
# 임시 디렉터리는 돌린 뒤 지우고, 남았으면 실패로 센다.
# Globals:
#   run_output, run_status
# Arguments:
#   fixture 디렉터리 경로.
# Returns:
#   임시 디렉터리를 만들거나 복사 · 치환하지 못하면 1.
#######################################
run_guard_outside() {
  local dir="$1"
  local tmp config content
  local marker="${MARKER_DIR}/fixture"
  tmp="$(new_tmp_dir)" || return 1
  config="${tmp}/conftest.toml"
  if ! cp -R "${dir}/." "${tmp}/"; then
    rm -rf -- "${tmp}"
    return 1
  fi
  if [[ -f "${config}" ]]; then
    content="$(<"${config}")"
    # 바꿀 값은 인용하지 않는다 — bash 3.2 는 그 따옴표를 글자로 남긴다.
    content="${content//"${MARKER_PLACEHOLDER}"/${marker}}"
    if ! printf '%s\n' "${content}" >"${config}"; then
      rm -rf -- "${tmp}"
      return 1
    fi
  fi
  run_output="$(bash "${GUARD}" "${tmp}" 2>&1)"
  run_status=$?
  rm -rf -- "${tmp}"
  if [[ -e "${tmp}" ]]; then
    fail "$(basename -- "${dir}"): 임시 디렉터리가 남음 — ${tmp}"
  fi
}

#######################################
# 저장소 밖 사본(운영 표지)이 기대한 위반으로 실패하는지 본다.
# Arguments:
#   fixture 디렉터리 경로, 출력에 있어야 할 위반 줄의 앞부분.
#######################################
expect_outside_violation() {
  local dir="$1" expected="$2"
  local name
  name="$(basename -- "${dir}")"
  run_guard_outside "${dir}" || {
    fail "${name}(저장소 밖): 시험 준비 실패(임시 디렉터리 · 복사)"
    return
  }
  if [[ "${run_status}" -ne 1 ]]; then
    fail "${name}(저장소 밖): 종료 코드 1 을 기대했으나 ${run_status}" \
      "— ${run_output}"
    return
  fi
  if [[ $'\n'"${run_output}" != *$'\n'"${expected}"* ]]; then
    fail "${name}(저장소 밖): 출력에 「${expected}」 가 없음 — ${run_output}"
    return
  fi
  echo "ok: ${name}(저장소 밖) → ${expected}"
}

#######################################
# 저장소 밖 사본(운영 표지)이 위반 0 으로 통과하는지 본다.
# Arguments:
#   fixture 디렉터리 경로.
#######################################
expect_outside_clean() {
  local dir="$1"
  local name
  name="$(basename -- "${dir}")"
  run_guard_outside "${dir}" || {
    fail "${name}(저장소 밖): 시험 준비 실패(임시 디렉터리 · 복사)"
    return
  }
  if [[ "${run_status}" -ne 0 ]]; then
    fail "${name}(저장소 밖): 종료 코드 0 을 기대했으나 ${run_status}" \
      "— ${run_output}"
    return
  fi
  echo "ok: ${name}(저장소 밖) → 위반 0"
}

#######################################
# 설정 파일로 R12 표지를 fixture 로 바꾸려 해도 위반 0 으로 새지 않는지 본다.
# 설정 파일과 명시 인자의 병합 거동에 기대지 않도록 종료 코드는 「0 이
# 아님」(1 또는 2) 만, 출력은 R0 줄만 단언한다.
#######################################
expect_marker_injection_rejected() {
  local name="op_R12_conftest_inject"
  run_guard_outside "${FIXTURES}/${name}" || {
    fail "${name}(저장소 밖): 시험 준비 실패(임시 디렉터리 · 복사 · 치환)"
    return
  }
  if [[ "${run_status}" -eq 0 ]]; then
    fail "${name}(저장소 밖): 종료 코드가 0 이 아니길 기대했으나 0" \
      "— ${run_output}"
    return
  fi
  if [[ $'\n'"${run_output}" != *$'\n'"guard: R0 conftest.toml: "* ]]; then
    fail "${name}(저장소 밖): 출력에 R0 conftest.toml 위반이 없음" \
      "— ${run_output}"
    return
  fi
  echo "ok: ${name}(저장소 밖) → 종료 ${run_status} · R0 설정 파일"
}

#######################################
# 저장소의 실제 트리(운영 표지)가 위반 0 인지 본다. fixture 안의
# conftest.toml(scripts/testdata)은 검사 범위 밖이라 잡히지 않아야 한다.
#######################################
expect_operational_tree_clean() {
  local output status
  output="$(bash "${GUARD}" 2>&1)"
  status=$?
  if [[ "${status}" -ne 0 ]]; then
    fail "operational-tree: 종료 코드 0 을 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: operational-tree → 위반 0"
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
# err fixture 하나가 종료 코드 2 로 멈추는지 본다.
# Arguments:
#   fixture 디렉터리 경로.
#######################################
expect_fixture_error() {
  local dir="$1"
  local name
  name="$(basename -- "${dir}")"
  run_guard "${dir}" || {
    fail "${name}: 시험 준비 실패(임시 디렉터리 · 복사)"
    return
  }
  if [[ "${run_status}" -ne 2 ]]; then
    fail "${name}: 종료 코드 2 를 기대했으나 ${run_status} — ${run_output}"
    return
  fi
  echo "ok: ${name} → 검사 불가(종료 2)"
}

#######################################
# 읽을 수 없는 .tf 가 있으면 위반 0 으로 넘어가지 않고 멈추는지 본다.
# root 는 권한과 상관없이 읽으므로 그때는 건너뛴다.
#######################################
expect_unreadable_error() {
  local tmp
  tmp="$(new_tmp_dir)" || {
    fail "unreadable: 임시 디렉터리를 만들지 못함"
    return
  }
  if ! cp -R "${FIXTURES}/ok/." "${tmp}/" || ! chmod 000 "${tmp}/main.tf"; then
    fail "unreadable: 시험 준비 실패(복사 · 권한)"
    rm -rf -- "${tmp}"
    return
  fi
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

#######################################
# 정책 디렉터리 하나에 정책 단위 시험(conftest verify)을 돌린다.
# Arguments:
#   정책 디렉터리.
# Outputs:
#   통과면 요약 줄, 아니면 conftest 출력.
# Returns:
#   통과면 0, 아니면 1.
#######################################
run_policy_tests() {
  local policy="$1"
  local output
  # 셸에 남은 CONFTEST_* 와 호출한 디렉터리 설정의 namespace 가 시험을
  # 고르지 못하게, 환경을 지운 하위 셸에서 namespace 를 명시해 돌린다.
  if ! output="$(clear_conftest_env
    conftest verify --policy "${policy}" --namespace main --strict \
      --show-builtin-errors --no-color 2>&1)"; then
    echo "${output}"
    return 1
  fi
  output="${output##*$'\n'}"
  # 시험이 하나도 돌지 않았으면(namespace · 정책 경로 실수) 통과가 아니다.
  if [[ ! "${output}" =~ ^([0-9]+)\ tests?, ]] ||
    [[ "${BASH_REMATCH[1]}" -eq 0 ]]; then
    echo "시험이 하나도 돌지 않음: ${output}"
    return 1
  fi
  echo "${output}"
}

#######################################
# 정책 단위 시험(conftest verify)을 돌린다.
#######################################
expect_policy_tests_pass() {
  local summary
  if ! summary="$(run_policy_tests "${POLICY_DIR}")"; then
    fail "conftest verify 실패 — ${summary}"
    return
  fi
  echo "ok: conftest verify → ${summary}"
}

#######################################
# 셸에 남은 CONFTEST_* 설정과 호출한 디렉터리의 conftest.toml(다른
# namespace)이 정책 단위 시험을 0 개로 비우지 못하는지 본다.
#######################################
expect_policy_tests_ignore_config() {
  local tmp summary
  tmp="$(new_tmp_dir)" || {
    fail "verify-config: 시험 준비 실패(임시 디렉터리)"
    return
  }
  if ! printf 'namespace = ["missing"]\n' >"${tmp}/conftest.toml"; then
    fail "verify-config: 시험 준비 실패(설정 파일)"
    rm -rf -- "${tmp}"
    return
  fi
  summary="$(export CONFTEST_NAMESPACE=missing_namespace
    run_policy_tests "${POLICY_DIR}")"
  if [[ ! "${summary}" =~ ^([1-9][0-9]*)\ tests?, ]]; then
    fail "verify-env: 시험이 1개 이상 돌기를 기대했으나 — ${summary}"
  else
    echo "ok: verify-env → ${summary}"
  fi
  summary="$(cd -- "${tmp}" && run_policy_tests "${POLICY_DIR}")"
  rm -rf -- "${tmp}"
  if [[ ! "${summary}" =~ ^([1-9][0-9]*)\ tests?, ]]; then
    fail "verify-config: 시험이 1개 이상 돌기를 기대했으나 — ${summary}"
    return
  fi
  echo "ok: verify-config → ${summary}"
}

#######################################
# 시험이 하나도 없는 정책 디렉터리는 「통과」 가 아니라 실패인지 본다.
#######################################
expect_zero_policy_tests_error() {
  local tmp summary status
  tmp="$(new_tmp_dir)" || {
    fail "verify-zero: 시험 준비 실패(임시 디렉터리)"
    return
  }
  if ! printf 'package main\n\nnote := "no tests"\n' >"${tmp}/empty.rego"; then
    fail "verify-zero: 시험 준비 실패(정책)"
    rm -rf -- "${tmp}"
    return
  fi
  summary="$(run_policy_tests "${tmp}")"
  status=$?
  rm -rf -- "${tmp}"
  if [[ "${status}" -eq 0 ]]; then
    fail "verify-zero: 시험 0개를 실패로 기대했으나 통과 — ${summary}"
    return
  fi
  echo "ok: verify-zero → 실패(시험 0개)"
}

#######################################
# conftest 가 없으면 위반 0 으로 넘어가지 않고 종료 2 로 멈추는지 본다.
# PATH 에서 conftest 가 든 디렉터리만 뺀다.
#######################################
expect_missing_conftest_error() {
  local conftest_dir path_without dir output status
  local old_ifs="${IFS}"
  conftest_dir="$(dirname -- "$(command -v conftest)")"
  path_without=""
  IFS=":"
  for dir in ${PATH}; do
    [[ "${dir}" == "${conftest_dir}" ]] && continue
    path_without="${path_without:+${path_without}:}${dir}"
  done
  IFS="${old_ifs}"
  if PATH="${path_without}" command -v conftest >/dev/null 2>&1; then
    echo "skip: no-conftest(PATH 의 다른 곳에도 conftest 가 있음)"
    return
  fi
  output="$(PATH="${path_without}" "${BASH}" "${GUARD}" \
    "${FIXTURES}/bad_R1" 2>&1)"
  status=$?
  if [[ "${status}" -ne 2 ]]; then
    fail "no-conftest: 종료 코드 2 를 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: no-conftest → 검사 불가(종료 2)"
}

#######################################
# 셸에 남은 CONFTEST_* 설정(다른 프로젝트의 namespace 등)이 정책을 끄지
# 못하는지 본다.
#######################################
expect_conftest_env_ignored() {
  local output status
  output="$(CONFTEST_NAMESPACE=missing_namespace \
    CONFTEST_POLICY=/nonexistent/policy \
    bash "${GUARD}" "${FIXTURES}/bad_R1" 2>&1)"
  status=$?
  if [[ "${status}" -ne 1 || "${output}" != *"guard: R1 "* ]]; then
    fail "conftest-env: 종료 1 · R1 위반을 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: conftest-env → R1 위반"
}

#######################################
# deny 규칙이 하나도 안 돌면(패키지 이름 · deny 철자 실수) 위반 0 이 아니라
# 종료 2 인지 본다. guard.sh 사본 옆에 deny 없는 정책과 표지만 둔다(표지가
# 없어 멈추는 다른 종료 2 와 가리려고 출력의 0 tests 도 본다).
#######################################
expect_no_rules_error() {
  local tmp output status
  tmp="$(new_tmp_dir)" || {
    fail "no-rules: 시험 준비 실패(임시 디렉터리)"
    return
  }
  if ! mkdir -p "${tmp}/scripts" "${tmp}/policy/guard" ||
    ! cp -- "${GUARD}" "${tmp}/scripts/guard.sh" ||
    ! cp -R -- "${MARKER_DIR}" "${tmp}/scripts/" ||
    ! printf 'package main\n\nimport rego.v1\n\nnote := "no deny"\n' \
      >"${tmp}/policy/guard/empty.rego"; then
    fail "no-rules: 시험 준비 실패(복사)"
    rm -rf -- "${tmp}"
    return
  fi
  output="$(bash "${tmp}/scripts/guard.sh" "${FIXTURES}/bad_R1" 2>&1)"
  status=$?
  rm -rf -- "${tmp}"
  if [[ "${status}" -ne 2 || "${output}" != *"0 tests"* ]]; then
    fail "no-rules: 종료 2 · 0 tests 를 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: no-rules → 검사 불가(종료 2)"
}

#######################################
# 검사 루트에 남은 conftest.toml(ignore 등)이 일부 파일을 검사에서 빼지
# 못하는지 본다. bad_R1 사본에 정상 파일을 하나 더 두고 위반 파일만
# 무시하는 설정을 둔다(전부 무시는 conftest 가 「파일 없음」 오류로 멈춘다).
#######################################
expect_conftest_config_ignored() {
  local tmp output status
  tmp="$(new_tmp_dir)" || {
    fail "conftest-config: 시험 준비 실패(임시 디렉터리)"
    return
  }
  if ! cp -R "${FIXTURES}/bad_R1/." "${tmp}/" ||
    ! printf 'resource "aws_secretsmanager_secret" "ok" {\n  name = "x"\n}\n' \
      >"${tmp}/other.tf" ||
    ! printf 'ignore = "main"\nnamespace = ["missing"]\n' \
      >"${tmp}/conftest.toml"; then
    fail "conftest-config: 시험 준비 실패(복사)"
    rm -rf -- "${tmp}"
    return
  fi
  output="$(bash "${GUARD}" "${tmp}" 2>&1)"
  status=$?
  rm -rf -- "${tmp}"
  if [[ "${status}" -ne 1 || "${output}" != *"guard: R1 "* ]]; then
    fail "conftest-config: 종료 1 · R1 위반을 기대했으나 ${status} — ${output}"
    return
  fi
  echo "ok: conftest-config → R1 위반"
}

#######################################
# 실제 트리의 하위 디렉터리를 루트로 주면 경로 규칙(R4 · R11)이 조용히
# 빠지므로 종료 2 인지 본다.
#######################################
expect_subroot_error() {
  expect_error subroot "${SCRIPT_DIR}/../nonprod/dev"
}

#######################################
# ok_R12 를 저장소 밖 임시 디렉터리에 복사하고, 준비 함수로 사례를 만든 뒤
# guard.sh 를 돌린다. 숨김 · 줄바꿈 · 제어 문자 이름은 저장소에 두지 않고
# 여기서 만든다.
# Globals:
#   run_output, run_status
# Arguments:
#   준비 함수 이름(인자 = 사본 루트).
# Returns:
#   임시 디렉터리를 만들거나 준비하지 못하면 1.
#######################################
run_guard_prepared() {
  local setup="$1"
  local tmp
  tmp="$(new_tmp_dir)" || return 1
  if ! cp -R "${FIXTURES}/ok_R12/." "${tmp}/" || ! "${setup}" "${tmp}"; then
    rm -rf -- "${tmp}"
    return 1
  fi
  run_output="$(bash "${GUARD}" "${tmp}" 2>&1)"
  run_status=$?
  rm -rf -- "${tmp}"
}

#######################################
# 준비한 사례가 기대한 위반 줄과 함께 종료 1 로 실패하는지 본다.
# Arguments:
#   사례 이름, 준비 함수 이름, 출력에 있어야 할 위반 줄 앞부분(하나 이상).
#######################################
expect_prepared_violation() {
  local name="$1" setup="$2"
  local expected rule
  shift 2
  # 「guard: R0 <경로>: 」 → R0
  rule="${1#guard: }"
  rule="${rule%% *}"
  run_guard_prepared "${setup}" || {
    fail "${name}: 시험 준비 실패(임시 디렉터리 · 복사 · 준비)"
    return
  }
  if [[ "${run_status}" -ne 1 ]]; then
    fail "${name}: 종료 코드 1 을 기대했으나 ${run_status} — ${run_output}"
    return
  fi
  for expected in "$@"; do
    if [[ $'\n'"${run_output}" != *$'\n'"${expected}"* ]]; then
      fail "${name}: 출력에 「${expected}」 가 없음 — ${run_output}"
      return
    fi
  done
  echo "ok: ${name} → ${rule} 위반"
}

#######################################
# 준비한 사례가 위반 0 으로 통과하는지 본다.
# Arguments:
#   사례 이름, 준비 함수 이름.
#######################################
expect_prepared_clean() {
  local name="$1" setup="$2"
  run_guard_prepared "${setup}" || {
    fail "${name}: 시험 준비 실패(임시 디렉터리 · 복사 · 준비)"
    return
  }
  if [[ "${run_status}" -ne 0 ]]; then
    fail "${name}: 종료 코드 0 을 기대했으나 ${run_status} — ${run_output}"
    return
  fi
  echo "ok: ${name} → 위반 0"
}

# 준비 함수들. 인자는 ok_R12 사본 루트다.

# 공개 존 루트의 main.tf 를 숨김 이름으로(Terraform 은 읽지 않는다).
hide_root_main() {
  mv -- "$1/nonprod/dns-legacy/main.tf" "$1/nonprod/dns-legacy/.main.tf"
}

# 숨김 이름 여럿: main 과 함께 .outputs.tf · .imports.tf · .extra.tf.json.
hide_root_files() {
  hide_root_main "$1" &&
    printf 'locals {}\n' >"$1/nonprod/dns-legacy/.outputs.tf" &&
    printf 'locals {}\n' >"$1/nonprod/dns-legacy/.imports.tf" &&
    printf '{}\n' >"$1/nonprod/dns-legacy/.extra.tf.json"
}

# 줄바꿈 이름: 진짜 override 는 a.tf<줄바꿈>b_override.tf 이고, 줄 단위로
# 쪼개면 나오는 두 이름(a.tf · 루트의 b_override.tf)을 미끼로 둔다.
make_newline_override() {
  local dir="$1/nonprod/dns-legacy"
  printf '%s\n' 'resource "aws_route53_zone" "legacy" {' '  lifecycle {' \
    '    prevent_destroy = false' '  }' '}' \
    >"${dir}/a.tf"$'\n'"b_override.tf" &&
    printf 'locals {}\n' >"${dir}/a.tf" &&
    printf 'locals {}\n' >"$1/b_override.tf"
}

# 제어 문자(탭) 이름의 .tf.
make_control_char_name() {
  printf 'locals {}\n' >"$1/x"$'\t'"y.tf"
}

# 줄바꿈이 든 디렉터리 아래의 .tf.
make_newline_dir() {
  mkdir -- "$1/mod"$'\n'"x" && printf 'locals {}\n' >"$1/mod"$'\n'"x/main.tf"
}

# 이름이 .terraform 인 심볼릭 링크(진짜 provider 캐시는 디렉터리다).
make_terraform_link() {
  ln -s missing "$1/nonprod/dns-legacy/.terraform"
}

# scripts/testdata 자리의 심볼릭 링크(fixture 를 빼는 자리).
make_testdata_link() {
  mkdir -p -- "$1/scripts" && ln -s ../nonprod "$1/scripts/testdata"
}

# 진짜 .terraform 디렉터리 안의 .tf 는 검사하지 않는다(provider 캐시).
make_terraform_dir() {
  mkdir -p -- "$1/nonprod/dns-legacy/.terraform" &&
    printf '%s\n' 'resource "aws_secretsmanager_secret_version" "x" {}' \
      >"$1/nonprod/dns-legacy/.terraform/cached.tf"
}

# conftest 가 읽는 설정 이름(확장자가 다른 것 · 대문자).
make_conftest_yaml() {
  printf 'namespace:\n  - main\n' >"$1/conftest.yaml"
}

make_conftest_upper() {
  printf 'namespace = ["main"]\n' >"$1/CONFTEST.TOML"
}

# conftest 가 읽지 않는 이름(문서 · pytest 관례).
make_conftest_lookalikes() {
  printf '# notes\n' >"$1/conftest.md" &&
    printf '# pytest\n' >"$1/conftest.py"
}

#######################################
# 숨김 이름 · 줄바꿈 · 제어 문자 · 링크(guard 가 보는 파일 집합이 Terraform
# 이 읽는 집합을 덮는가)와 conftest 설정 파일 이름을 본다.
#######################################
expect_enumeration_boundaries() {
  local newline_file newline_dir
  newline_file="$(printf '%q' "nonprod/dns-legacy/a.tf"$'\n'"b_override.tf")"
  newline_dir="$(printf '%q' "mod"$'\n'"x/main.tf")"
  expect_prepared_violation hidden-main hide_root_main \
    "guard: R0 nonprod/dns-legacy/.main.tf: "
  expect_prepared_violation hidden-many hide_root_files \
    "guard: R0 nonprod/dns-legacy/.main.tf: " \
    "guard: R0 nonprod/dns-legacy/.outputs.tf: " \
    "guard: R0 nonprod/dns-legacy/.imports.tf: " \
    "guard: R0 nonprod/dns-legacy/.extra.tf.json: "
  expect_prepared_violation newline-name make_newline_override \
    "guard: R0 ${newline_file}: "
  expect_prepared_violation control-char-name make_control_char_name \
    "guard: R0 $(printf '%q' "x"$'\t'"y.tf"): "
  expect_prepared_violation newline-dir make_newline_dir \
    "guard: R0 ${newline_dir}: "
  expect_prepared_violation terraform-link make_terraform_link \
    "guard: R0 nonprod/dns-legacy/.terraform: "
  expect_prepared_violation testdata-link make_testdata_link \
    "guard: R0 scripts/testdata: "
  expect_prepared_clean terraform-dir make_terraform_dir
  expect_prepared_violation conftest-yaml make_conftest_yaml \
    "guard: R0 conftest.yaml: "
  expect_prepared_violation conftest-upper make_conftest_upper \
    "guard: R0 CONFTEST.TOML: "
  expect_prepared_clean conftest-lookalikes make_conftest_lookalikes
}

#######################################
# 설정 파일이 정책을 내려받게(update) 해 둔 트리에서 guard.sh 가 conftest 를
# 한 번도 부르지 않고 R0 로 끝나, 정책 디렉터리가 그대로인지 본다.
# guard.sh · 정책 · 표지 사본과, 부를 때마다 기록하는 conftest 대역을 쓴다.
#######################################
expect_config_stops_before_conftest() {
  local tmp kit before after output status real
  tmp="$(new_tmp_dir)" || {
    fail "config-first: 시험 준비 실패(임시 디렉터리)"
    return
  }
  kit="${tmp}/kit"
  real="$(command -v conftest)"
  if ! mkdir -p "${kit}/scripts" "${kit}/policy" "${tmp}/bin" "${tmp}/tree" ||
    ! cp -- "${GUARD}" "${kit}/scripts/guard.sh" ||
    ! cp -R -- "${MARKER_DIR}" "${kit}/scripts/" ||
    ! cp -R -- "${POLICY_DIR}" "${kit}/policy/guard" ||
    ! cp -R "${FIXTURES}/ok_R12/." "${tmp}/tree/" ||
    ! printf 'package main\n\nfixture_tree if true\n' \
      >"${tmp}/planted.rego" ||
    ! printf 'update = ["%s"]\n' "${tmp}/planted.rego" \
      >"${tmp}/tree/conftest.toml" ||
    ! printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >>%q\nexec %q "$@"\n' \
      "${tmp}/calls.log" "${real}" >"${tmp}/bin/conftest" ||
    ! chmod +x "${tmp}/bin/conftest"; then
    fail "config-first: 시험 준비 실패(복사)"
    rm -rf -- "${tmp}"
    return
  fi
  before="$(ls -A "${kit}/policy/guard")"
  output="$(PATH="${tmp}/bin:${PATH}" bash "${kit}/scripts/guard.sh" \
    "${tmp}/tree" 2>&1)"
  status=$?
  after="$(ls -A "${kit}/policy/guard")"
  if [[ -s "${tmp}/calls.log" ]]; then
    fail "config-first: conftest 가 불림 — $(<"${tmp}/calls.log")"
  elif [[ "${before}" != "${after}" ]]; then
    fail "config-first: 정책 디렉터리가 바뀜 — ${after}"
  elif [[ "${status}" -ne 1 ||
    $'\n'"${output}" != *$'\n'"guard: R0 conftest.toml: "* ]]; then
    fail "config-first: 종료 1 · R0 conftest.toml 을 기대했으나 ${status}" \
      "— ${output}"
  else
    echo "ok: config-first → R0 · conftest 미호출 · 정책 무변경"
  fi
  rm -rf -- "${tmp}"
}

main() {
  local dir
  local count=0
  if ! command -v conftest >/dev/null 2>&1; then
    echo "guard_test: conftest 가 없습니다. 설치: brew install conftest" >&2
    exit 1
  fi
  # 트랩을 먼저 건다 — 뿌리를 만든 직후에 신호가 와도 남지 않게.
  trap remove_tmp_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/guard_test.XXXXXX")" || {
    echo "guard_test: 임시 디렉터리를 만들지 못함" >&2
    exit 1
  }
  expect_policy_tests_pass
  expect_policy_tests_ignore_config
  expect_zero_policy_tests_error
  for dir in "${FIXTURES}"/bad_*/; do
    [[ -d "${dir}" ]] || continue
    expect_violation "${dir%/}"
    count=$((count + 1))
  done
  if [[ "${count}" -eq 0 ]]; then
    fail "bad_* fixture 가 하나도 없음"
  fi
  expect_clean "${FIXTURES}/ok"
  expect_clean "${FIXTURES}/ok_R12"
  for dir in "${FIXTURES}"/err_*/; do
    [[ -d "${dir}" ]] || continue
    expect_fixture_error "${dir%/}"
  done
  expect_unreadable_error
  expect_no_tmpfile_dependency
  expect_missing_conftest_error
  expect_conftest_env_ignored
  expect_no_rules_error
  expect_subroot_error
  expect_conftest_config_ignored
  # 운영 표지: 저장소 밖 사본과 저장소의 실제 트리.
  expect_outside_violation "${FIXTURES}/ok" \
    "guard: R12 nonprod/dns-legacy: .tf 없음"
  expect_outside_violation "${FIXTURES}/op_R12_rename" \
    'guard: R12 nonprod/dns-legacy: resource "aws_route53_zone" "legacy" 선언 없음'
  expect_outside_clean "${FIXTURES}/ok_R12"
  expect_marker_injection_rejected
  expect_operational_tree_clean
  # 열거 경계 · conftest 설정 파일(저장소 밖에서 만든 트리).
  expect_enumeration_boundaries
  expect_config_stops_before_conftest

  if [[ "${failures}" -ne 0 ]]; then
    echo "guard_test: ${failures}건 실패" >&2
    exit 1
  fi
  echo "guard_test: 전부 통과(bad ${count}개 · ok 2개 · 운영 표지 5개)"
}

main "$@"
