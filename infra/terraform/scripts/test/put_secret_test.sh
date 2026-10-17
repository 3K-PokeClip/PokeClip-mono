#!/usr/bin/env bash
#
# put-secret-from-file.sh 를 가짜 aws(fakebin/aws)로 시험한다. AWS 에 접속하지
# 않는다.
#
# PATH 를 가짜 aws 디렉터리 하나로 두고(스크립트가 aws 밖의 외부 명령을 부르면
# 실패한다), AWS 프로필 · 키 환경을 지운 하위 셸에서 스크립트를 돌린다. 사례마다
# TMPDIR · HOME 은 빈 디렉터리로, SHELL 은 없는 경로로 주고, 로캘은 UTF-8 로
# 고정한다(없으면 skip). 가짜 aws 는 aws-cli 2.36.7 처럼 값을 받고(fakebin/aws
# 머리 주석), 받은 인자 · 환경 · 값을 사례 디렉터리에 적는다. 시험은 인자(비밀
# 이름 · 값 넘기는 법)와 받은 값을 대조하고, 값이 가짜 aws 의 인자 · 환경,
# 스크립트의 표준 출력 · 표준 오류, 사례 디렉터리의 다른 파일 어디에도 없는지
# 본다. 거부 · 실패 사례에서는 표준 오류가 정해진 줄과 바이트 그대로 같은지(끝
# 줄바꿈 · 빈 줄 포함) 보고, aws 호출이 거부는 0회 · aws 실패는 1회인지 본다.
# 추적(bash -x · SHELLOPTS)이나 allexport 를 켠 호출도 값을 내지 않는지 본다.
# 사례 밖으로 쓰는 길(파일 대상 리다이렉션 · 다른 명령 · 셸)은 소스로 본다.
#
# 정적 검사(no-file-writes · no-external-commands · no-endpoint-override ·
# no-heredoc)는 흔한 회귀를 잡는 울타리다. 일부러 숨긴 꼴(문자열 안 명령
# 치환 · 공백 변형 · 리디렉션 없는 쓰기 내장 명령 등)은 PR 리뷰가 막는다 —
# 실제 누출 방어는 실행 시험(실물 CLI 계약 · 잔여 검사 · 출력 완전 일치)이다.
#
# 실물 CLI 계약: PATH 에 진짜 aws 가 있으면 가짜 자격 · 빈 설정 · 격리 HOME ·
# 닫힌 로컬 주소(AWS_ENDPOINT_URL=http://127.0.0.1:9)로 스크립트를 끝까지 돌려,
# CLI 가 값 넘기는 법을 받아들이는지(ParamValidation 없이 네트워크 단계까지
# 가는지) 본다. 127.0.0.1:9 는 이 기계 안의 닫힌 포트라 요청이 AWS 에 닿지
# 않는다. 둘째 울타리로 프록시도 같은 닫힌 주소로 두고 NO_PROXY 는 127.0.0.1
# 만이라, AWS_ENDPOINT_URL 을 읽지 않는 CLI(2.13 미만)의 요청도 이 기계 안에서
# 끊긴다. 스크립트가 엔드포인트를 바꾸는 토큰을 쓰지 않는지는 소스로 본다. 진짜
# aws 가 없으면 skip 으로 센다.
#
# 키 꼴 입력은 시험 중 임시 디렉터리에 printf 로 만든다 — 머리 · 꼬리 줄과 가짜
# 본문뿐이고 실제 키가 아니다. 임시 디렉터리는 한 뿌리($TMPDIR/put_secret_test.*)
# 아래에 두고, 끝에 지운 뒤 남지 않았는지 확인한다(중단 신호에도 지운다). 끝에
# 통과 + skip 건수가 사례 수(EXPECTED_CASES)와 같은지 본다.
#
# 사용: bash infra/terraform/scripts/test/put_secret_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly PUT="${SCRIPT_DIR}/../put-secret-from-file.sh"
readonly FAKEBIN="${SCRIPT_DIR}/fakebin"

# put-secret-from-file.sh 의 종료 코드.
readonly EXIT_USAGE=2
readonly EXIT_REJECTED=3
readonly EXIT_DENIED=4
readonly EXIT_FAILED=5

# 가짜 aws 의 성공 응답 VersionId · 오류 원문의 계정 ID(AWS 문서 예시 값).
readonly VERSION_ID='a1b2c3d4-5678-90ab-cdef-EXAMPLE11111'
readonly FAKE_ACCOUNT='123456789012'
# 키 머리 · 꼬리 줄. 비밀 스캐너가 키로 오인하지 않게 나눠 쓴다
# (PlaybackProperties.java 와 같은 방식).
readonly PKCS8_BEGIN='-----BEGIN ''PRIVATE KEY-----'
readonly PKCS8_END='-----END ''PRIVATE KEY-----'
readonly RSA_BEGIN='-----BEGIN RSA ''PRIVATE KEY-----'
readonly RSA_END='-----END RSA ''PRIVATE KEY-----'
readonly ENC_BEGIN='-----BEGIN ENCRYPTED ''PRIVATE KEY-----'
readonly ENC_END='-----END ENCRYPTED ''PRIVATE KEY-----'
readonly PUBLIC_BEGIN='-----BEGIN PUBLIC KEY-----'
readonly PUBLIC_END='-----END PUBLIC KEY-----'
# 가짜 키 본문(base64 글자만 맞춘 것 — 키가 아니다). 값 0건 검사의 표지다.
readonly FAKE_KEY_BODY='FAKEKEYBODYonlyForTests0123456789abcdefABCDEF+/'
# 한 줄 비밀 값. JSON 이스케이프를 보려고 " 와 \ 를 넣었다. 되울린 JSON
# 속에서는 이스케이프된 꼴이라, 값 0건 검사는 이스케이프에 안 걸리는 앞
# 토막(ONE_LINE_MARKER)으로도 한다.
readonly ONE_LINE_VALUE='FakeCdnSecret"quote\back/slash+_-.~=Z9'
readonly ONE_LINE_MARKER='FakeCdnSecret'
# 성공 때 가짜 aws 가 받아야 할 인자(한 줄로 이은 것). 앞 · 뒤 사이에 비밀
# 이름이 온다.
EXPECTED_ARGV_HEAD='secretsmanager put-secret-value --region ap-northeast-2'
EXPECTED_ARGV_HEAD+=' --output json --no-cli-auto-prompt --secret-id'
readonly EXPECTED_ARGV_HEAD
readonly EXPECTED_ARGV_TAIL='--secret-string file:///dev/stdin'
# 사례 수(ok · skip 줄의 수). 사례를 더하거나 빼면 함께 고친다.
readonly EXPECTED_CASES=49

failures=0
passes=0
skips=0
utf8_locale=""
tmp_root=""
case_dir=""
input=""
out=""
err=""
status=0
# code_only_line 의 상태(줄을 넘어 이어짐)와 결과.
code_stack=""
code_line=""

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
# 시험 하나의 실패를 알리고 센다.
# Globals:
#   failures
# Arguments:
#   실패 설명.
#######################################
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

#######################################
# 시험 하나의 통과를 적고 센다.
# Globals:
#   passes
# Arguments:
#   사례 이름.
#######################################
pass() {
  printf 'ok: %s\n' "$1"
  passes=$((passes + 1))
}

#######################################
# 돌리지 못한 사례를 적고 센다(실패가 아님 — 건수 단언에 들어감).
# Globals:
#   skips
# Arguments:
#   사례 이름(과 이유).
#######################################
skip() {
  printf 'skip: %s\n' "$1"
  skips=$((skips + 1))
}

#######################################
# 사례 디렉터리(입력 in/ · 가짜 aws 기록 aws/ · 빈 TMPDIR tmp/ · 빈 HOME
# home/)를 새로 만든다. 못 만들면 실패로 센다.
# Globals:
#   tmp_root, case_dir
# Returns:
#   만들지 못하면 1.
#######################################
new_case() {
  if case_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" &&
    mkdir -- "${case_dir}/in" "${case_dir}/aws" "${case_dir}/tmp" \
      "${case_dir}/home"; then
    return 0
  fi
  fail "사례 디렉터리를 만들지 못함"
  return 1
}

#######################################
# 사례의 입력 파일을 printf 형식 그대로 만든다.
# Globals:
#   case_dir, input
# Arguments:
#   printf 형식(\n · \t · \0 등을 해석), 형식 인자.
#######################################
write_input() {
  input="${case_dir}/in/value"
  # shellcheck disable=SC2059
  printf "$@" >"${input}"
}

#######################################
# PEM 꼴 입력을 만든다(머리 줄 · 가짜 본문 · 꼬리 줄 · 끝 줄바꿈).
# Arguments:
#   머리 줄, 꼬리 줄.
#######################################
write_pem() {
  write_input '%s\n%s\n%s\n' "$1" "${FAKE_KEY_BODY}" "$2"
}

#######################################
# put-secret-from-file.sh 를 돌린다. AWS 자격 증명 환경을 지운 하위 셸에서,
# PATH 는 가짜 aws 디렉터리 하나 · TMPDIR 과 HOME 은 사례의 빈 디렉터리 ·
# SHELL 은 없는 경로(스크립트가 "${SHELL}" -c 로 값을 다른 셸의 인자에 넘기면
# 깨지게) · 로캘은 UTF-8(있으면) · 표준 입력은 /dev/null 로.
# Globals:
#   case_dir, out, err, status, utf8_locale
# Arguments:
#   셸 모드(plain · xtrace-flag · xtrace-env · allexport-env · secret-export
#   — 호출자가 secret_value 를 export · no-aws — PATH 에 aws 없음), 가짜
#   aws 모드, 스크립트 인자들.
#######################################
run_put_as() {
  local shell_mode="$1" mode="$2"
  shift 2
  # 환경은 이 하위 셸 안에서만 바꾼다(의도).
  # shellcheck disable=SC2030,SC2031
  (
    unset AWS_PROFILE AWS_DEFAULT_PROFILE AWS_ACCESS_KEY_ID \
      AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_ROLE_ARN \
      AWS_WEB_IDENTITY_TOKEN_FILE
    export AWS_CONFIG_FILE=/nonexistent/put-secret-test
    export AWS_SHARED_CREDENTIALS_FILE=/nonexistent/put-secret-test
    export AWS_EC2_METADATA_DISABLED=true
    export FAKE_AWS_DIR="${case_dir}/aws" FAKE_AWS_MODE="${mode}"
    export TMPDIR="${case_dir}/tmp" HOME="${case_dir}/home"
    export SHELL=/nonexistent/put-secret-test
    if [[ -n "${utf8_locale}" ]]; then
      export LC_ALL="${utf8_locale}"
    fi
    export PATH="${FAKEBIN}"
    case "${shell_mode}" in
      xtrace-flag) exec "${BASH}" -x "${PUT}" "$@" ;;
      no-aws) export PATH=/nonexistent/put-secret-test ;;
      xtrace-env)
        set -o xtrace
        export SHELLOPTS
        ;;
      allexport-env)
        set -o allexport
        export SHELLOPTS
        ;;
      secret-export) export secret_value=FakeInheritedExport ;;
    esac
    exec "${BASH}" "${PUT}" "$@"
  ) </dev/null >"${case_dir}/stdout" 2>"${case_dir}/stderr"
  status=$?
  out="$(<"${case_dir}/stdout")"
  err="$(<"${case_dir}/stderr")"
}

#######################################
# run_put_as plain.
# Arguments:
#   가짜 aws 모드, 스크립트 인자들.
#######################################
run_put() {
  run_put_as plain "$@"
}

#######################################
# 가짜 aws 가 불린 횟수.
# Globals:
#   case_dir
# Outputs:
#   횟수.
#######################################
aws_calls() {
  local calls=0 line
  if [[ -f "${case_dir}/aws/calls" ]]; then
    while IFS= read -r line; do
      calls=$((calls + 1))
    done <"${case_dir}/aws/calls"
  fi
  printf '%s\n' "${calls}"
}

#######################################
# 파일 내용(없으면 빈 값).
# Arguments:
#   파일 경로.
# Outputs:
#   내용.
#######################################
contents_of() {
  if [[ -f "$1" ]]; then
    printf '%s' "$(<"$1")"
  fi
}

#######################################
# 스크립트의 표준 오류가 주어진 줄들(줄마다 끝 줄바꿈 하나)과 바이트 그대로
# 같은지 본다 — 덧붙은 줄 · 빈 줄 · 끝 줄바꿈 차이도 다르다고 본다. 명령 치환은
# 끝 줄바꿈을 지우므로 파일을 read -d '' 로 끝까지 읽는다(NUL 에서 멈추면 0 을
# 내므로 다르다고 본다).
# Globals:
#   case_dir
# Arguments:
#   기대 줄들.
# Returns:
#   다르면 1.
#######################################
stderr_is() {
  local actual="" expected
  if IFS= read -r -d '' actual <"${case_dir}/stderr"; then
    return 1
  fi
  printf -v expected '%s\n' "$@"
  [[ "${actual}" == "${expected}" ]]
}

#######################################
# 표지 문자열이 가짜 aws 의 인자 · 환경과 스크립트 출력 어디에도 없는지 본다.
# Globals:
#   case_dir, out, err
# Arguments:
#   사례 이름, 표지 문자열(비면 보지 않음 — 값이 없는 사례).
# Returns:
#   어디엔가 있으면 1.
#######################################
expect_absent() {
  local name="$1" marker="$2" where
  local argv env
  [[ -n "${marker}" ]] || return 0
  argv="$(contents_of "${case_dir}/aws/argv")"
  env="$(contents_of "${case_dir}/aws/env")"
  for where in out err argv env; do
    if [[ "${!where}" == *"${marker}"* ]]; then
      fail "${name}: 값 · 식별자가 ${where} 에 나옴"
      return 1
    fi
  done
}

#######################################
# 실행 뒤 남은 것을 본다: 사례 TMPDIR · HOME 이 비었는지, 값 표지가 든 파일이
# 허용 목록(입력 in/value · 가짜 aws 가 받은 값 aws/stdin · 기대값
# expected)뿐인지.
# Globals:
#   case_dir
# Arguments:
#   사례 이름, 값 표지(비면 TMPDIR · HOME 만 봄).
# Returns:
#   어긋나면 1.
#######################################
expect_no_leftovers() {
  local name="$1" marker="$2" file
  if [[ -n "$(ls -A "${case_dir}/tmp")" ]]; then
    fail "${name}: TMPDIR 에 파일이 남음"
    return 1
  fi
  if [[ -n "$(ls -A "${case_dir}/home")" ]]; then
    fail "${name}: HOME 에 파일이 남음"
    return 1
  fi
  [[ -n "${marker}" ]] || return 0
  while IFS= read -r file; do
    case "${file#"${case_dir}/"}" in
      in/value | aws/stdin | expected) ;;
      *)
        fail "${name}: 값이 ${file#"${case_dir}/"} 에 남음"
        return 1
        ;;
    esac
  done < <(grep -rlF -- "${marker}" "${case_dir}" 2>/dev/null)
}

#######################################
# 가짜 aws 가 값 넘기기 규칙 위반을 적지 않았는지 본다(인자 · 다른 파일).
# Globals:
#   case_dir
# Arguments:
#   사례 이름.
# Returns:
#   위반이 있으면 1.
#######################################
expect_no_violation() {
  if [[ -e "${case_dir}/aws/violation" ]]; then
    fail "$1: 값 넘기기 규칙 위반 — $(<"${case_dir}/aws/violation")"
    return 1
  fi
}

#######################################
# 입력을 넣기 전에 거부하는지 본다: 종료 3 · 표준 오류가 거부 한 줄과 바이트
# 그대로 같음 · aws 호출 0 · 출력에 값 없음. 이유를 완전한 문자열로 대조해,
# 이유에 값 조각이나 빈 줄이 덧붙으면 잡는다.
# Globals:
#   input, out, err, status
# Arguments:
#   사례 이름, 종류, 거부 이유(「SECRET PUT REJECTED: 」 뒤 전체), 값 표지.
#######################################
expect_rejected() {
  local name="$1" kind="$2" reason="$3" marker="$4"
  run_put ok "${kind}" "${input}"
  if [[ "${status}" -ne "${EXIT_REJECTED}" ]]; then
    fail "${name}: 종료 ${EXIT_REJECTED} 를 기대했으나 ${status} — ${err}"
    return
  fi
  if ! stderr_is "SECRET PUT REJECTED: ${reason}"; then
    fail "${name}: 표준 오류가 「SECRET PUT REJECTED: ${reason}」 한 줄이" \
      "아님 — ${err}"
    return
  fi
  if [[ "$(aws_calls)" -ne 0 || -n "${out}" ]]; then
    fail "${name}: 거부인데 aws 가 불렸거나 표준 출력이 있음"
    return
  fi
  expect_absent "${name}" "${marker}" || return
  expect_no_leftovers "${name}" "${marker}" || return
  pass "${name} → 거부(${reason})"
}

#######################################
# 사용법 오류인지 본다: 종료 2 · 사용법 줄 · aws 호출 0.
# Arguments:
#   사례 이름, 스크립트 인자들.
#######################################
expect_usage() {
  local name="$1"
  shift
  new_case || return
  run_put ok "$@"
  if [[ "${status}" -ne "${EXIT_USAGE}" || "${err}" != *"사용: "* ]]; then
    fail "${name}: 종료 ${EXIT_USAGE} · 사용법을 기대했으나 ${status} — ${err}"
    return
  fi
  if [[ "$(aws_calls)" -ne 0 ]]; then
    fail "${name}: 사용법 오류인데 aws 가 불림"
    return
  fi
  pass "${name} → 사용법 오류"
}

#######################################
# 성공 경로를 본다: 종료 0 · 이름 + VersionId 한 줄 · aws 1회 · 인자 · 받은
# 값 · AWS_PAGER · 고정한 로캘(LC_ALL — 시험 로캘이 실제로 걸렸는지) · 사례
# HOME · TMPDIR · SHELL(격리가 실제로 걸렸는지) · 값과 계정 ID 0건 · 남은 것
# 없음.
# Globals:
#   case_dir, input, out, err, status, utf8_locale
# Arguments:
#   사례 이름, 종류, 기대값이 든 파일, 값 표지.
#######################################
expect_put() {
  local name="$1" kind="$2" expected="$3" marker="$4"
  local secret_id="pokeclip/prod/${kind}" argv env
  run_put ok "${kind}" "${input}"
  expect_no_violation "${name}" || return
  if [[ "${status}" -ne 0 ]]; then
    fail "${name}: 종료 0 을 기대했으나 ${status} — ${err}"
    return
  fi
  if [[ "${out}" != "SECRET PUT OK ${secret_id} ${VERSION_ID}" ||
    -n "${err}" ]]; then
    fail "${name}: 성공 줄이 다름 — 출력 「${out}」 · 오류 「${err}」"
    return
  fi
  argv="$(contents_of "${case_dir}/aws/argv")"
  argv="${argv//$'\n'/ }"
  if [[ "$(aws_calls)" -ne 1 ||
    "${argv}" != "${EXPECTED_ARGV_HEAD} ${secret_id} ${EXPECTED_ARGV_TAIL}" ]]
  then
    fail "${name}: aws 호출(1회) · 인자가 다름 — ${argv}"
    return
  fi
  env=$'\n'"$(contents_of "${case_dir}/aws/env")"$'\n'
  if [[ "${env}" != *$'\nAWS_PAGER=\n'* ]]; then
    fail "${name}: aws 환경에 빈 AWS_PAGER 가 없음"
    return
  fi
  if [[ -n "${utf8_locale}" &&
    "${env}" != *$'\n'"LC_ALL=${utf8_locale}"$'\n'* ]]; then
    fail "${name}: aws 환경에 LC_ALL=${utf8_locale} 가 없음(로캘 고정 안 됨)"
    return
  fi
  if [[ "${env}" != *$'\n'"HOME=${case_dir}/home"$'\n'* ||
    "${env}" != *$'\n'"TMPDIR=${case_dir}/tmp"$'\n'* ||
    "${env}" != *$'\nSHELL=/nonexistent/put-secret-test\n'* ]]; then
    fail "${name}: aws 환경의 HOME · TMPDIR · SHELL 이 사례 값이 아님(격리" \
      "안 됨)"
    return
  fi
  if ! cmp -s -- "${case_dir}/aws/stdin" "${expected}"; then
    fail "${name}: aws 가 받은 값이 기대와 다름"
    return
  fi
  expect_absent "${name}" "${marker}" || return
  expect_absent "${name}" "${FAKE_ACCOUNT}" || return
  expect_no_leftovers "${name}" "${marker}" || return
  pass "${name} → 넣음(${secret_id})"
}

#######################################
# 추적 · allexport 를 켠 셸에서 부른 성공 경로를 본다: 종료 0 · 성공 줄 · 값이
# 표준 오류(추적 출력) · 가짜 aws 환경 · 인자에 없음 · 남은 것 없음.
# Globals:
#   input, out, err, status
# Arguments:
#   사례 이름, 셸 모드, 값 표지.
#######################################
expect_put_quiet() {
  local name="$1" shell_mode="$2" marker="$3"
  run_put_as "${shell_mode}" ok cdn-secret "${input}"
  expect_no_violation "${name}" || return
  if [[ "${status}" -ne 0 ||
    "${out}" != "SECRET PUT OK pokeclip/prod/cdn-secret ${VERSION_ID}" ]]; then
    fail "${name}: 종료 0 · 성공 줄을 기대했으나 ${status} — ${out}"
    return
  fi
  expect_absent "${name}" "${marker}" || return
  expect_no_leftovers "${name}" "${marker}" || return
  pass "${name} → 넣음 · 값 0건(${shell_mode})"
}

#######################################
# aws 가 실패할 때를 본다: 종료 코드 · 표준 오류가 고정 줄들과 바이트 그대로
# 같음(값 조각 · 빈 줄이 덧붙으면 잡음) · aws 1회(다시 시도 안 함) · 값 · 계정
# ID · ARN 0건.
# Globals:
#   input, out, err, status
# Arguments:
#   사례 이름, 가짜 aws 모드, 기대 종료 코드, 표준 오류의 기대 줄들.
#######################################
expect_aws_failure() {
  local name="$1" mode="$2" code="$3"
  shift 3
  run_put "${mode}" cdn-secret "${input}"
  expect_no_violation "${name}" || return
  if [[ "${status}" -ne "${code}" ]]; then
    fail "${name}: 종료 ${code} 를 기대했으나 ${status} — ${err}"
    return
  fi
  if ! stderr_is "$@" || [[ -n "${out}" ]]; then
    fail "${name}: 표준 오류가 「$*」 와 같지 않거나 표준 출력이 있음 — ${err}"
    return
  fi
  if [[ "$(aws_calls)" -ne 1 ]]; then
    fail "${name}: aws 호출이 1회가 아님(다시 시도하면 안 됨)"
    return
  fi
  expect_absent "${name}" "${ONE_LINE_VALUE}" || return
  expect_absent "${name}" "${ONE_LINE_MARKER}" || return
  expect_absent "${name}" "${FAKE_ACCOUNT}" || return
  expect_absent "${name}" "arn:aws" || return
  expect_no_leftovers "${name}" "${ONE_LINE_MARKER}" || return
  pass "${name} → $1"
}

#######################################
# 인자 수 · 종류가 틀리면 사용법 오류.
#######################################
test_usage() {
  expect_usage usage-no-args
  expect_usage usage-one-arg cdn-secret
  expect_usage usage-three-args cdn-secret "${tmp_root}/x" extra
  expect_usage usage-unknown-kind api-key "${tmp_root}/x"
  expect_usage usage-arn-kind "arn:aws:secretsmanager:example" "${tmp_root}/x"
}

#######################################
# 파일 자체의 검사(종류와 무관): 없음 · 일반 파일 아님 · 읽기 불가 · 빈 파일 ·
# 공백만 · NUL.
#######################################
test_file_checks() {
  new_case || return
  input="${case_dir}/in/missing"
  expect_rejected missing-file cdn-secret "파일이 없음: ${input}" ""
  new_case || return
  input="${case_dir}/in"
  expect_rejected directory cdn-secret "일반 파일이 아님: ${input}" ""
  if [[ "$(id -u)" -ne 0 ]]; then
    new_case || return
    write_input '%s\n' "${ONE_LINE_VALUE}"
    chmod 000 "${input}"
    expect_rejected unreadable cdn-secret "읽을 수 없음: ${input}" \
      "${ONE_LINE_MARKER}"
  else
    skip "unreadable(root 는 권한을 무시)"
  fi
  new_case || return
  write_input ''
  expect_rejected empty-file signing-key "빈 파일: ${input}" ""
  new_case || return
  write_input ' \n\t\n'
  expect_rejected whitespace-only cdn-secret "공백만 있음: ${input}" ""
  new_case || return
  write_input 'FakeNul\0Tail\n'
  expect_rejected nul-byte cdn-secret "NUL 바이트가 있음: ${input}" "FakeNul"
}

#######################################
# 서명 키: 머리 줄 PKCS#8 · 꼬리 줄 PKCS#8 만. 파일 내용 그대로 넣는다.
#######################################
test_signing_key() {
  local bad_footer="끝 줄이 ${PKCS8_END} 가 아님"
  local bad_header="첫 줄이 ${PKCS8_BEGIN} 가 아님"
  local bad_chars="서명 키에 제어 문자 · ASCII 밖 문자가 있음(CRLF 포함)"
  new_case || return
  write_pem "${PKCS8_BEGIN}" "${PKCS8_END}"
  expect_put pkcs8 signing-key "${input}" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n%s\n%s' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" "${PKCS8_END}"
  expect_put pkcs8-no-final-newline signing-key "${input}" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${RSA_BEGIN}" "${RSA_END}"
  expect_rejected rsa signing-key \
    "PKCS#1(BEGIN RSA) 거부 — PKCS#8(BEGIN PRIVATE KEY)만" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${ENC_BEGIN}" "${ENC_END}"
  expect_rejected encrypted signing-key \
    "암호화된 PKCS#8 거부 — 암호 없는 PKCS#8 만" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${PKCS8_BEGIN}" "${RSA_END}"
  expect_rejected wrong-footer signing-key "${bad_footer}" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n%s\n%s\nextra\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected trailing-text signing-key "${bad_footer}" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n' "${PKCS8_BEGIN}"
  expect_rejected header-only signing-key "${bad_footer}" "${PKCS8_BEGIN}"
  new_case || return
  write_input '%s\r\n%s\r\n%s\r\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected pem-crlf signing-key "${bad_chars}" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n\t%s\n%s\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected pem-tab signing-key "${bad_chars}" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n%s\303\251\n%s\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected pem-non-ascii signing-key "${bad_chars}" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${PUBLIC_BEGIN}" "${PUBLIC_END}"
  expect_rejected public-key signing-key "${bad_header}" "${FAKE_KEY_BODY}"
  # 머리 줄이 빠진 PEM — 첫 줄이 키 본문이라, 이유에 첫 줄을 넣으면 값이
  # 나간다.
  new_case || return
  write_input '%s\n%s\n' "${FAKE_KEY_BODY}" "${PKCS8_END}"
  expect_rejected pem-no-header signing-key "${bad_header}" "${FAKE_KEY_BODY}"
}

#######################################
# 한 줄 비밀(cdn-secret · test-stream-key): 끝 줄바꿈 하나만 떼고, 그 밖의
# 줄바꿈 · 공백 · 제어 문자 · ASCII 밖 문자는 거부.
#######################################
test_one_line() {
  local expected
  local multi_line="여러 줄 거부(끝 줄바꿈 하나만 뗌)"
  local blank="공백 · 탭 거부" control="제어 문자 거부(CR 포함)"
  new_case || return
  expected="${case_dir}/expected"
  printf '%s' "${ONE_LINE_VALUE}" >"${expected}"
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_put one-line-cdn cdn-secret "${expected}" "${ONE_LINE_MARKER}"
  new_case || return
  expected="${case_dir}/expected"
  printf '%s' "${ONE_LINE_VALUE}" >"${expected}"
  write_input '%s' "${ONE_LINE_VALUE}"
  expect_put one-line-no-newline test-stream-key "${expected}" \
    "${ONE_LINE_MARKER}"
  new_case || return
  write_input 'FakeFirst\nFakeSecond\n'
  expect_rejected multi-line cdn-secret "${multi_line}" "FakeSecond"
  new_case || return
  write_input 'FakeTwoNewlines\n\n'
  expect_rejected two-final-newlines test-stream-key "${multi_line}" "FakeTwo"
  new_case || return
  write_input 'Fake Space\n'
  expect_rejected inner-space cdn-secret "${blank}" "Fake Space"
  new_case || return
  write_input 'Fake\tTab\n'
  expect_rejected inner-tab cdn-secret "${blank}" "Tab"
  new_case || return
  write_input 'FakeCarriage\r\n'
  expect_rejected carriage-return cdn-secret "${control}" "FakeCarriage"
  new_case || return
  write_input 'Fake\001Ctrl\n'
  expect_rejected control-char test-stream-key "${control}" "Ctrl"
  new_case || return
  write_input 'FakeNonAscii\303\251\n'
  expect_rejected non-ascii cdn-secret "ASCII 밖 문자 거부" "FakeNonAscii"
}

#######################################
# aws 가 실패하면: AccessDenied(부분 일치)는 고정 문자열 · 전용 종료 코드, 그
# 밖은 오류 코드만(코드 없는 네트워크 단계 실패는 ENDPOINT). aws CLI 가 없을
# 때도 실패.
#######################################
test_aws_failures() {
  local denied_note="SCP 멈춤 규칙 — 다시 시도하지 말고 kty 에게 보고"
  local no_version_note="응답에 VersionId 가 없음 — describe-secret 으로 버전 확인"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure denied denied "${EXIT_DENIED}" "SECRET PUT DENIED" \
    "${denied_note}"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure denied-short denied-short "${EXIT_DENIED}" \
    "SECRET PUT DENIED" "${denied_note}"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure other-error error "${EXIT_FAILED}" \
    "SECRET PUT FAILED ResourceNotFoundException"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure endpoint-error endpoint "${EXIT_FAILED}" \
    "SECRET PUT FAILED ENDPOINT"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure unparsed-error unparsed "${EXIT_FAILED}" \
    "SECRET PUT FAILED UNKNOWN"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure no-version-id no-version "${EXIT_FAILED}" \
    "SECRET PUT FAILED UNKNOWN" "${no_version_note}"
  test_aws_missing
}

#######################################
# PATH 에 aws 가 없으면 검사를 통과한 뒤 실패(종료 5)하고 값은 안 나온다 —
# 표준 오류는 고정 두 줄과 바이트 그대로 같다. 다른 사례와 같이 격리한
# 셸(run_put_as)에서 돌리고 남은 것도 본다.
# Globals:
#   input, err, status
#######################################
test_aws_missing() {
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  run_put_as no-aws ok cdn-secret "${input}"
  if [[ "${status}" -ne "${EXIT_FAILED}" ]] ||
    ! stderr_is "SECRET PUT FAILED UNKNOWN" "aws CLI 가 PATH 에 없음"; then
    fail "aws-missing: 종료 ${EXIT_FAILED} · aws CLI 안내 두 줄을 기대했으나" \
      "${status} — ${err}"
    return
  fi
  expect_absent aws-missing "${ONE_LINE_MARKER}" || return
  expect_no_leftovers aws-missing "${ONE_LINE_MARKER}" || return
  pass "aws-missing → 실패"
}

#######################################
# 추적(bash -x · SHELLOPTS 의 xtrace)이나 allexport 를 물려받은 호출, 호출자가
# 같은 이름(secret_value)을 export 해 둔 호출도 값을 내지 않는다.
#######################################
test_quiet_shells() {
  local mode
  for mode in xtrace-flag xtrace-env allexport-env secret-export; do
    new_case || return
    write_input '%s\n' "${ONE_LINE_VALUE}"
    expect_put_quiet "quiet-${mode}" "${mode}" "${ONE_LINE_MARKER}"
  done
}

#######################################
# 실물 CLI 계약: 진짜 aws 가 값 넘기는 법을 받아들여 네트워크 단계까지 가는지
# 본다. 가짜 자격 · 빈 설정 · 격리 HOME 이고 울타리는 둘이다 — 엔드포인트
# (AWS_ENDPOINT_URL)를 닫힌 로컬 주소 127.0.0.1:9 로 두고, 프록시도 같은
# 주소로 두되 NO_PROXY 는 127.0.0.1 만이다. 엔드포인트 설정을 읽지 않는 CLI 의
# 요청도 닫힌 로컬 프록시에서 끊겨 AWS 에 닿지 않는다. SHELL 도 가짜 사례처럼
# 없는 경로로 둔다(진짜 aws 가 SHELL 없이 도는지도 함께 본다). 진짜 aws 가
# 없으면 skip.
# Globals:
#   case_dir, input, out, err, status
#######################################
test_real_cli_contract() {
  local real_aws version var
  real_aws="$(command -v aws)" || real_aws=""
  if [[ -z "${real_aws}" ]]; then
    skip "real-cli-contract(PATH 에 진짜 aws 없음)"
    return
  fi
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  # 진짜 aws 는 HOME 에 세션 캐시(.aws/cli/cache)를 쓴다. 사례 HOME(home/ —
  # 비었는지 봄)과 따로 두고, 그 안의 값 표지는 expect_no_leftovers 의 grep 이
  # 본다.
  mkdir -- "${case_dir}/cli-home"
  # 환경은 이 하위 셸 안에서만 바꾼다(의도).
  # shellcheck disable=SC2030,SC2031
  (
    for var in $(compgen -e); do
      case "${var}" in
        AWS_* | HTTP_PROXY | HTTPS_PROXY | ALL_PROXY) unset "${var}" ;;
        http_proxy | https_proxy | all_proxy) unset "${var}" ;;
      esac
    done
    # 둘째 울타리: 127.0.0.1 밖으로 가는 요청은 닫힌 로컬 프록시로 간다.
    export HTTPS_PROXY=http://127.0.0.1:9 https_proxy=http://127.0.0.1:9
    export HTTP_PROXY=http://127.0.0.1:9 http_proxy=http://127.0.0.1:9
    export NO_PROXY=127.0.0.1 no_proxy=127.0.0.1
    export HOME="${case_dir}/cli-home" TMPDIR="${case_dir}/tmp"
    export SHELL=/nonexistent/put-secret-test
    export AWS_ACCESS_KEY_ID=testing AWS_SECRET_ACCESS_KEY=testing
    export AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null
    export AWS_EC2_METADATA_DISABLED=true AWS_MAX_ATTEMPTS=1
    export AWS_ENDPOINT_URL=http://127.0.0.1:9
    "${real_aws}" --version >"${case_dir}/version" 2>&1
    exec "${BASH}" "${PUT}" cdn-secret "${input}"
  ) </dev/null >"${case_dir}/stdout" 2>"${case_dir}/stderr"
  status=$?
  out="$(<"${case_dir}/stdout")"
  err="$(<"${case_dir}/stderr")"
  version="$(contents_of "${case_dir}/version")"
  version="${version%% *}"
  if [[ "${err}" == *ParamValidation* || "${err}" == *cli-input-json* ]]; then
    fail "real-cli-contract(${version}): CLI 가 값 넘기는 법을 거부 — ${err}"
    return
  fi
  if [[ "${status}" -ne "${EXIT_FAILED}" || -n "${out}" ]] ||
    ! stderr_is "SECRET PUT FAILED ENDPOINT"; then
    fail "real-cli-contract(${version}): 종료 ${EXIT_FAILED} · ENDPOINT 를" \
      "기대했으나 ${status} — ${err}"
    return
  fi
  expect_absent real-cli-contract "${ONE_LINE_MARKER}" || return
  expect_no_leftovers real-cli-contract "${ONE_LINE_MARKER}" || return
  pass "real-cli-contract(${version}) → 네트워크 단계까지 감(ENDPOINT)"
}

#######################################
# 시험 로캘을 UTF-8 로 고른다 — 스크립트의 local LC_ALL=C 가 빠지면 UTF-8
# 로캘에서 ASCII 밖 사례가 잡힌다. 없으면 skip(그 뮤테이션은 못 잡음).
# Globals:
#   utf8_locale
#######################################
test_locale() {
  local candidate
  for candidate in $(locale -a 2>/dev/null); do
    case "${candidate}" in
      C.UTF-8 | C.utf8 | en_US.UTF-8 | en_US.utf8)
        utf8_locale="${candidate}"
        pass "utf8-locale → ${utf8_locale}"
        return
        ;;
    esac
  done
  skip "utf8-locale(UTF-8 로캘 없음 — local LC_ALL=C 뮤테이션은 못 잡음)"
}

#######################################
# 스크립트가 aws 밖의 다른 프로세스에 값을 넘길 길을 소스로 본다. 절대 경로
# (/usr/ · /bin/)와 jq 는 PATH 를 fakebin 하나로 둔 시험을 비켜 가고, command
# -p · $BASH · ${BASH} · eval · exec · env 는 PATH 밖의 명령이나 다른 셸을
# 부른다. 주석 · shebang 줄은 보지 않는다.
#######################################
test_no_external_commands() {
  local line number=0 comment_re='^[[:space:]]*#' pattern
  # 글자 그대로 찾는 패턴이다(펼치지 않음).
  # shellcheck disable=SC2016
  local patterns=('/usr/' '/bin/' 'jq' 'command -p' '${BASH}' '$BASH' 'eval'
    'exec ' 'env ')
  if [[ ! -r "${PUT}" ]]; then
    fail "no-external-commands: 스크립트를 읽을 수 없음 — ${PUT}"
    return
  fi
  while IFS= read -r line; do
    number=$((number + 1))
    [[ "${line}" =~ ${comment_re} ]] && continue
    for pattern in "${patterns[@]}"; do
      if [[ "${line}" == *"${pattern}"* ]]; then
        fail "no-external-commands: ${number}행에 「${pattern}」"
        return
      fi
    done
  done <"${PUT}"
  pass "no-external-commands → 0건"
}

#######################################
# 스크립트가 엔드포인트를 바꾸지 않는지 소스로 본다 — --endpoint-url ·
# AWS_ENDPOINT_URL* · AWS_IGNORE_CONFIGURED_ENDPOINT_URLS · 설정 endpoint_url
# 을 쓰면 실물 CLI 사례의 닫힌 로컬 주소를 비켜 갈 수 있다. 주석 줄도 보고
# 대소문자는 가리지 않는다.
#######################################
test_no_endpoint_override() {
  local line number=0 found=0
  if [[ ! -r "${PUT}" ]]; then
    fail "no-endpoint-override: 스크립트를 읽을 수 없음 — ${PUT}"
    return
  fi
  shopt -s nocasematch
  while IFS= read -r line; do
    number=$((number + 1))
    if [[ "${line}" == *endpoint[-_]url* ]]; then
      found="${number}"
      break
    fi
  done <"${PUT}"
  shopt -u nocasematch
  if ((found > 0)); then
    fail "no-endpoint-override: ${found}행에 endpoint-url · endpoint_url 꼴"
    return
  fi
  pass "no-endpoint-override → 0건"
}

#######################################
# 소스 한 줄에서 따옴표 문자열과 주석을 빼고 코드만 code_line 에 담는다(문자열
# 자리는 Q 한 글자). 줄을 넘는 문자열 · 명령 치환은 code_stack 으로 이어
# 본다. 맨 끝 글자가 지금 자리다 — 비면 바깥 코드, p 괄호, m 명령 치환 $( ),
# d 겹따옴표, s 홑따옴표, a $'…'. 바깥 코드의 짝 없는 ) (case 패턴)는 그냥
# 둔다. here-doc 은 모른다(no-heredoc 이 따로 막음).
# Globals:
#   code_stack, code_line
# Arguments:
#   소스 한 줄.
#######################################
code_only_line() {
  local line="$1" i ch pair top prev=' '
  code_line=""
  for ((i = 0; i < ${#line}; i++)); do
    ch="${line:i:1}"
    pair="${line:i:2}"
    top="${code_stack: -1}"
    if [[ "${top}" == s ]]; then
      [[ "${ch}" != "'" ]] || code_stack="${code_stack%?}"
    elif [[ "${top}" == [ad] ]]; then
      if [[ "${ch}" == "\\" ]]; then
        i=$((i + 1))
      elif [[ "${top}${ch}" == "a'" || "${top}${ch}" == 'd"' ]]; then
        code_stack="${code_stack%?}"
      elif [[ "${top}${pair}" == "d\$(" ]]; then
        code_stack+=m
        code_line+="\$("
        i=$((i + 1))
      fi
    elif [[ "${ch}" == '#' && "${prev}" == [[:space:]\;\&\|\(\)] ]]; then
      break
    elif [[ "${ch}" == "\\" ]]; then
      code_line+=Q
      i=$((i + 1))
    elif [[ "${pair}" == "\$'" ]]; then
      code_stack+=a
      code_line+=Q
      i=$((i + 1))
    elif [[ "${ch}" == "'" ]]; then
      code_stack+=s
      code_line+=Q
    elif [[ "${ch}" == '"' ]]; then
      code_stack+=d
      code_line+=Q
    elif [[ "${pair}" == "\$(" ]]; then
      code_stack+=m
      code_line+="\$("
      i=$((i + 1))
    elif [[ "${ch}" == '(' ]]; then
      code_stack+=p
      code_line+='('
    else
      [[ "${ch}" != ')' || "${top}" != [pm] ]] || code_stack="${code_stack%?}"
      code_line+="${ch}"
    fi
    prev="${ch}"
  done
}

#######################################
# 스크립트가 파일에 쓰지 않는지 소스로 본다 — 코드(따옴표 문자열 · 주석을 뺀
# 것)의 > · >> 대상은 fd 복제(&1 · &2)와 /dev/null 만 허용한다. 리터럴
# /tmp · "${HOME}/…" 같은 파일 대상은 사례 디렉터리 밖에 남을 수 있어 실행
# 뒤 검사로는 다 보지 못한다. 문자열 · 괄호 짝이 끝까지 맞지 않으면(검사기가
# 코드를 문자열로 잘못 읽었을 수 있음) 실패로 센다. 검사기는 백틱 명령 치환을
# 읽지 못하므로, 주석 아닌 줄에 백틱이 하나라도 있으면 실패로 센다(스크립트는
# $( ) 만 씀 — shellguide Command Substitution).
# Globals:
#   code_stack, code_line
#######################################
test_no_file_writes() {
  local line number=0 rest comment_re='^[[:space:]]*#'
  local allowed_re='^(.*[^0-9>])?[0-9]*>>?[[:space:]]*(&[12]|/dev/null)'
  allowed_re+='([^[:alnum:]_./-].*)?$'
  if [[ ! -r "${PUT}" ]]; then
    fail "no-file-writes: 스크립트를 읽을 수 없음 — ${PUT}"
    return
  fi
  code_stack=""
  while IFS= read -r line; do
    number=$((number + 1))
    if [[ ! "${line}" =~ ${comment_re} && "${line}" == *'`'* ]]; then
      fail "no-file-writes: ${number}행에 백틱 — 검사기가 읽지 못하는 꼴"
      return
    fi
    code_only_line "${line}"
    rest="${code_line}"
    while [[ "${rest}" =~ ${allowed_re} ]]; do
      rest="${BASH_REMATCH[1]}${BASH_REMATCH[3]}"
    done
    if [[ "${rest}" == *'>'* ]]; then
      fail "no-file-writes: ${number}행 — > · >> 대상이 &1 · &2 · /dev/null 밖"
      return
    fi
  done <"${PUT}"
  if [[ -n "${code_stack}" ]]; then
    fail "no-file-writes: 소스 끝에서 문자열 · 괄호 짝이 남음(${code_stack})"
    return
  fi
  pass "no-file-writes → 0건"
}

#######################################
# 스크립트에 here-doc · here-string 이 없는지 본다. bash 3.2 는 그 본문을
# 임시 파일에 쓰고 곧바로 지우므로 실행 뒤에는 관찰할 수 없어 소스로 본다.
#######################################
test_no_heredoc() {
  local line number=0
  if [[ ! -r "${PUT}" ]]; then
    fail "no-heredoc: 스크립트를 읽을 수 없음 — ${PUT}"
    return
  fi
  while IFS= read -r line; do
    number=$((number + 1))
    if [[ "${line}" == *'<<'* ]]; then
      fail "no-heredoc: ${number}행에 here-doc · here-string"
      return
    fi
  done <"${PUT}"
  pass "no-heredoc → 0건"
}

main() {
  # 실행 비트가 없으면 PATH 의 가짜 aws 가 불리지 않는다 — 시작하지 않는다.
  if [[ ! -x "${FAKEBIN}/aws" ]]; then
    printf '%s\n' "put_secret_test: 가짜 aws 에 실행 비트가 없음" \
      "  ${FAKEBIN}/aws" >&2
    exit 1
  fi
  # 트랩을 먼저 건다 — 뿌리를 만든 직후에 신호가 와도 남지 않게.
  trap remove_tmp_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # 여기의 TMPDIR 은 호출자의 것이다(사례 하위 셸의 TMPDIR 과 무관).
  # shellcheck disable=SC2031
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/put_secret_test.XXXXXX")" || {
    printf '%s\n' "put_secret_test: 임시 디렉터리를 만들지 못함" >&2
    exit 1
  }
  test_locale
  test_usage
  test_file_checks
  test_signing_key
  test_one_line
  test_quiet_shells
  test_aws_failures
  test_real_cli_contract
  test_no_heredoc
  test_no_external_commands
  test_no_file_writes
  test_no_endpoint_override

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ $((passes + skips)) -ne "${EXPECTED_CASES}" ]]; then
    fail "사례 수가 다름 — 통과 ${passes} + skip ${skips} ≠ ${EXPECTED_CASES}"
  fi
  if [[ "${failures}" -ne 0 ]]; then
    printf 'put_secret_test: %s건 실패(통과 %s건 · skip %s건)\n' \
      "${failures}" "${passes}" "${skips}" >&2
    exit 1
  fi
  printf 'put_secret_test: 전부 통과(%s건 · skip %s건 · 임시 디렉터리 잔여 0)\n' \
    "${passes}" "${skips}"
}

main "$@"
