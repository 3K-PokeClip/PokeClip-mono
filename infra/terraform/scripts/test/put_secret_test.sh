#!/usr/bin/env bash
#
# put-secret-from-file.sh 를 가짜 aws(fakebin/aws)로 시험한다. AWS 에 접속하지
# 않는다.
#
# 가짜 aws 를 PATH 맨 앞에 두고, 진짜 aws 가 잘못 불려도 자격 증명이 없게
# AWS 프로필 · 키 환경을 지운 하위 셸에서 스크립트를 돌린다. 가짜 aws 는 받은
# 인자 · 환경 · 표준 입력(요청 JSON)을 사례 디렉터리에 적는다. 시험은 요청
# JSON 의 SecretId · SecretString 을 대조하고, 값이 가짜 aws 의 인자 · 환경과
# 스크립트의 표준 출력 · 표준 오류 어디에도 없는지 본다. 거부 사례에서는 aws
# 호출이 0회인지 본다.
#
# 키 꼴 입력은 시험 중 임시 디렉터리에 printf 로 만든다 — 머리 · 꼬리 줄과 가짜
# 본문뿐이고 실제 키가 아니다. 임시 디렉터리는 한 뿌리($TMPDIR/put_secret_test.*)
# 아래에 두고, 끝에 지운 뒤 남지 않았는지 확인한다(중단 신호에도 지운다).
# JSON 대조에 python3 를 쓴다.
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
# 가짜 키 본문(base64 글자만 맞춘 것 — 키가 아니다). 값 0건 검사의 표지다.
readonly FAKE_KEY_BODY='FAKEKEYBODYonlyForTests0123456789abcdefABCDEF+/'
# 한 줄 비밀 값. JSON 이스케이프를 보려고 " 와 \ 를 넣었다. 되울린 JSON
# 속에서는 이스케이프된 꼴이라, 값 0건 검사는 이스케이프에 안 걸리는 앞
# 토막(ONE_LINE_MARKER)으로도 한다.
readonly ONE_LINE_VALUE='FakeCdnSecret"quote\back/slash+_-.~=Z9'
readonly ONE_LINE_MARKER='FakeCdnSecret'
# 성공 때 가짜 aws 가 받아야 할 인자(한 줄로 이은 것).
EXPECTED_ARGV='secretsmanager put-secret-value --region ap-northeast-2'
EXPECTED_ARGV+=' --output json --cli-input-json file:///dev/stdin'
readonly EXPECTED_ARGV

failures=0
passes=0
tmp_root=""
case_dir=""
input=""
out=""
err=""
status=0

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
# 사례 디렉터리(입력 in/ · 가짜 aws 기록 aws/)를 새로 만든다.
# Globals:
#   tmp_root, case_dir
# Returns:
#   만들지 못하면 1.
#######################################
new_case() {
  case_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" || return 1
  mkdir -- "${case_dir}/in" "${case_dir}/aws"
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
# 가짜 aws 를 PATH 맨 앞에 두고 표준 입력은 /dev/null 로.
# Globals:
#   case_dir, out, err, status
# Arguments:
#   가짜 aws 모드, 스크립트 인자들.
#######################################
run_put() {
  local mode="$1"
  shift
  (
    unset AWS_PROFILE AWS_DEFAULT_PROFILE AWS_ACCESS_KEY_ID \
      AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_ROLE_ARN \
      AWS_WEB_IDENTITY_TOKEN_FILE
    export AWS_CONFIG_FILE=/nonexistent/put-secret-test
    export AWS_SHARED_CREDENTIALS_FILE=/nonexistent/put-secret-test
    export AWS_EC2_METADATA_DISABLED=true
    export FAKE_AWS_DIR="${case_dir}/aws" FAKE_AWS_MODE="${mode}"
    export PATH="${FAKEBIN}:${PATH}"
    exec "${BASH}" "${PUT}" "$@"
  ) </dev/null >"${case_dir}/stdout" 2>"${case_dir}/stderr"
  status=$?
  out="$(<"${case_dir}/stdout")"
  err="$(<"${case_dir}/stderr")"
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
# 요청 JSON 이 키 둘(SecretId · SecretString)뿐이고 값이 기대와 같은지 본다.
# Arguments:
#   요청 JSON 파일, 기대 SecretId, 기대 SecretString 이 든 파일.
# Returns:
#   다르거나 JSON 이 아니면 1.
#######################################
request_matches() {
  python3 -c '
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    request = json.load(f)
with open(sys.argv[3], encoding="utf-8", newline="") as f:
    expected = f.read()
ok = (set(request) == {"SecretId", "SecretString"}
      and request["SecretId"] == sys.argv[2]
      and request["SecretString"] == expected)
sys.exit(0 if ok else 1)
' "$1" "$2" "$3"
}

#######################################
# 입력을 넣기 전에 거부하는지 본다: 종료 3 · 이유 · aws 호출 0 · 출력에 값 없음.
# Globals:
#   input, out, err, status
# Arguments:
#   사례 이름, 종류, 표준 오류에 나와야 할 이유(부분 문자열), 값 표지.
#######################################
expect_rejected() {
  local name="$1" kind="$2" reason="$3" marker="$4"
  run_put ok "${kind}" "${input}"
  if [[ "${status}" -ne "${EXIT_REJECTED}" ]]; then
    fail "${name}: 종료 ${EXIT_REJECTED} 를 기대했으나 ${status} — ${err}"
    return
  fi
  if [[ "${err}" != *"SECRET PUT REJECTED: "*"${reason}"* ]]; then
    fail "${name}: 이유 「${reason}」 가 없음 — ${err}"
    return
  fi
  if [[ "$(aws_calls)" -ne 0 || -n "${out}" ]]; then
    fail "${name}: 거부인데 aws 가 불렸거나 표준 출력이 있음"
    return
  fi
  expect_absent "${name}" "${marker}" || return
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
  new_case || {
    fail "${name}: 사례 디렉터리를 만들지 못함"
    return
  }
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
# 성공 경로를 본다: 종료 0 · 이름 + VersionId 한 줄 · aws 1회 · 인자 · 요청
# JSON · AWS_PAGER · 값과 계정 ID 0건.
# Globals:
#   case_dir, input, out, err, status
# Arguments:
#   사례 이름, 종류, 기대 SecretString 이 든 파일, 값 표지.
#######################################
expect_put() {
  local name="$1" kind="$2" expected="$3" marker="$4"
  local secret_id="pokeclip/prod/${kind}" argv env
  run_put ok "${kind}" "${input}"
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
  if [[ "$(aws_calls)" -ne 1 || "${argv}" != "${EXPECTED_ARGV}" ]]; then
    fail "${name}: aws 호출(1회) · 인자가 다름 — ${argv}"
    return
  fi
  env=$'\n'"$(contents_of "${case_dir}/aws/env")"$'\n'
  if [[ "${env}" != *$'\nAWS_PAGER=\n'* ]]; then
    fail "${name}: aws 환경에 빈 AWS_PAGER 가 없음"
    return
  fi
  if ! request_matches "${case_dir}/aws/stdin.json" "${secret_id}" \
    "${expected}"; then
    fail "${name}: 요청 JSON 의 SecretId · SecretString 이 기대와 다름"
    return
  fi
  expect_absent "${name}" "${marker}" || return
  expect_absent "${name}" "${FAKE_ACCOUNT}" || return
  pass "${name} → 넣음(${secret_id})"
}

#######################################
# aws 가 실패할 때를 본다: 종료 코드 · 고정 줄 · aws 1회(다시 시도 안 함) ·
# 값 · 계정 ID · ARN 0건.
# Globals:
#   input, out, err, status
# Arguments:
#   사례 이름, 가짜 aws 모드, 기대 종료 코드, 표준 오류의 기대 줄.
#######################################
expect_aws_failure() {
  local name="$1" mode="$2" code="$3" line="$4"
  run_put "${mode}" cdn-secret "${input}"
  if [[ "${status}" -ne "${code}" ]]; then
    fail "${name}: 종료 ${code} 를 기대했으나 ${status} — ${err}"
    return
  fi
  if [[ $'\n'"${err}"$'\n' != *$'\n'"${line}"$'\n'* || -n "${out}" ]]; then
    fail "${name}: 표준 오류에 「${line}」 줄이 없거나 표준 출력이 있음 — ${err}"
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
  pass "${name} → ${line}"
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
  expect_rejected missing-file cdn-secret "파일이 없음" ""
  new_case || return
  input="${case_dir}/in"
  expect_rejected directory cdn-secret "일반 파일이 아님" ""
  if [[ "$(id -u)" -ne 0 ]]; then
    new_case || return
    write_input '%s\n' "${ONE_LINE_VALUE}"
    chmod 000 "${input}"
    expect_rejected unreadable cdn-secret "읽을 수 없음" "${ONE_LINE_MARKER}"
  else
    printf '%s\n' "skip: unreadable(root 는 권한을 무시)"
  fi
  new_case || return
  write_input ''
  expect_rejected empty-file signing-key "빈 파일" ""
  new_case || return
  write_input ' \n\t\n'
  expect_rejected whitespace-only cdn-secret "공백만" ""
  new_case || return
  write_input 'FakeNul\0Tail\n'
  expect_rejected nul-byte cdn-secret "NUL" "FakeNul"
}

#######################################
# 서명 키: 머리 줄 PKCS#8 · 꼬리 줄 PKCS#8 만. 파일 내용 그대로 넣는다.
#######################################
test_signing_key() {
  new_case || return
  write_pem "${PKCS8_BEGIN}" "${PKCS8_END}"
  expect_put pkcs8 signing-key "${input}" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n%s\n%s' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" "${PKCS8_END}"
  expect_put pkcs8-no-final-newline signing-key "${input}" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${RSA_BEGIN}" "${RSA_END}"
  expect_rejected rsa signing-key "PKCS#1" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${ENC_BEGIN}" "${ENC_END}"
  expect_rejected encrypted signing-key "암호화된" "${FAKE_KEY_BODY}"
  new_case || return
  write_pem "${PKCS8_BEGIN}" "${RSA_END}"
  expect_rejected wrong-footer signing-key "끝 줄" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n%s\n%s\nextra\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected trailing-text signing-key "끝 줄" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n' "${PKCS8_BEGIN}"
  expect_rejected header-only signing-key "끝 줄" "${PKCS8_BEGIN}"
  new_case || return
  write_input '%s\r\n%s\r\n%s\r\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected pem-crlf signing-key "제어 문자" "${FAKE_KEY_BODY}"
  new_case || return
  write_input '%s\n\t%s\n%s\n' "${PKCS8_BEGIN}" "${FAKE_KEY_BODY}" \
    "${PKCS8_END}"
  expect_rejected pem-tab signing-key "제어 문자" "${FAKE_KEY_BODY}"
}

#######################################
# 한 줄 비밀(cdn-secret · test-stream-key): 끝 줄바꿈 하나만 떼고, 그 밖의
# 줄바꿈 · 공백 · 제어 문자 · ASCII 밖 문자는 거부.
#######################################
test_one_line() {
  local expected
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
  expect_rejected multi-line cdn-secret "여러 줄" "FakeSecond"
  new_case || return
  write_input 'FakeTwoNewlines\n\n'
  expect_rejected two-final-newlines test-stream-key "여러 줄" "FakeTwo"
  new_case || return
  write_input 'Fake Space\n'
  expect_rejected inner-space cdn-secret "공백" "Fake Space"
  new_case || return
  write_input 'Fake\tTab\n'
  expect_rejected inner-tab cdn-secret "공백" "Tab"
  new_case || return
  write_input 'FakeCarriage\r\n'
  expect_rejected carriage-return cdn-secret "제어 문자" "FakeCarriage"
  new_case || return
  write_input 'Fake\001Ctrl\n'
  expect_rejected control-char test-stream-key "제어 문자" "Ctrl"
  new_case || return
  write_input 'FakeNonAscii\303\251\n'
  expect_rejected non-ascii cdn-secret "ASCII" "FakeNonAscii"
}

#######################################
# aws 가 실패하면: AccessDenied 는 고정 문자열 · 전용 종료 코드, 그 밖은
# 오류 코드만. aws CLI 가 없을 때도 실패.
#######################################
test_aws_failures() {
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure denied denied "${EXIT_DENIED}" "SECRET PUT DENIED"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure other-error error "${EXIT_FAILED}" \
    "SECRET PUT FAILED ResourceNotFoundException"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure unparsed-error unparsed "${EXIT_FAILED}" \
    "SECRET PUT FAILED UNKNOWN"
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  expect_aws_failure no-version-id no-version "${EXIT_FAILED}" \
    "SECRET PUT FAILED UNKNOWN"
  test_aws_missing
}

#######################################
# PATH 에 aws 가 없으면 검사를 통과한 뒤 실패(종료 5)하고 값은 안 나온다.
# Globals:
#   case_dir, input, err, status
#######################################
test_aws_missing() {
  new_case || return
  write_input '%s\n' "${ONE_LINE_VALUE}"
  PATH=/nonexistent/put-secret-test "${BASH}" "${PUT}" cdn-secret "${input}" \
    </dev/null >"${case_dir}/stdout" 2>"${case_dir}/stderr"
  status=$?
  out="$(<"${case_dir}/stdout")"
  err="$(<"${case_dir}/stderr")"
  if [[ "${status}" -ne "${EXIT_FAILED}" || "${err}" != *"aws CLI"* ]]; then
    fail "aws-missing: 종료 ${EXIT_FAILED} · aws CLI 안내를 기대했으나" \
      "${status} — ${err}"
    return
  fi
  expect_absent aws-missing "${ONE_LINE_MARKER}" || return
  pass "aws-missing → 실패"
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
  if ! command -v python3 >/dev/null 2>&1; then
    printf '%s\n' "put_secret_test: python3 가 없습니다(JSON 대조)" >&2
    exit 1
  fi
  # 트랩을 먼저 건다 — 뿌리를 만든 직후에 신호가 와도 남지 않게.
  trap remove_tmp_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/put_secret_test.XXXXXX")" || {
    printf '%s\n' "put_secret_test: 임시 디렉터리를 만들지 못함" >&2
    exit 1
  }
  test_usage
  test_file_checks
  test_signing_key
  test_one_line
  test_aws_failures
  test_no_heredoc

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ "${failures}" -ne 0 ]]; then
    printf 'put_secret_test: %s건 실패(통과 %s건)\n' "${failures}" \
      "${passes}" >&2
    exit 1
  fi
  printf 'put_secret_test: 전부 통과(%s건 · 임시 디렉터리 잔여 0)\n' "${passes}"
}

main "$@"
