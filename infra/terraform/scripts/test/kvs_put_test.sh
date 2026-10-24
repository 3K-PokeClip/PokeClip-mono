#!/usr/bin/env bash
#
# kvs-put-cdn-secret.sh 를 가짜 aws · dig · jq(kvs_fakebin/)로 시험한다. AWS 에
# 접속하지 않고 이름도 풀지 않는다.
#
# 구조는 put_secret_test.sh 를 따른다. PATH 를 가짜 명령 디렉터리 하나로 두고
# (스크립트가 aws · dig 밖의 외부 명령을 부르면 실패한다), 호출자의 AWS_ 환경
# 변수를 이름과 상관없이 모두 지운 하위 셸에서 스크립트를 돌린다. 사례마다
# TMPDIR · HOME 은 빈 디렉터리, SHELL 은 없는 경로, 로캘은 UTF-8(있으면)이다.
# 가짜 명령은 부른 차례(calls) · 받은 인자(<동작>.argv) · put-key 가 받은 값
# (stdin)을 사례 디렉터리에 적는다. 시험이 넣는 비밀 값은 사례의 cmd/secret 에
# 있고 가짜 aws 가 get-secret-value 의 응답으로 낸다.
#
# 사례마다 보는 것:
#   - 종료 코드 · 표준 출력 · 표준 오류가 정해진 줄과 바이트 그대로 같음.
#   - 부른 차례가 정확히 같음(거부 뒤 KVS 를 부르지 않음 · 다시 시도 없음 ·
#     jq 0회). 부른 동작마다 인자가 정확히 같음(ETag 사용 포함).
#   - put-key 가 받은 값이 비밀 값과 바이트 그대로 같음.
#   - 비밀 값이 모든 인자(dig · jq 포함) · 표준 출력 · 표준 오류 · 가짜 aws 가
#     받은 환경 변수 · 사례 디렉터리의 다른 파일에 0건.
#   - 이름 풀이 호스트의 계정 번호는 --kvs-arn 의 계정 칸에서 온다(SDK 의 KVS
#     엔드포인트 규칙과 같음 — sts 는 부르지 않음). 가짜 aws 의 sts 응답은
#     ARN 과 다른 번호라, 그 번호가 어느 인자 · 출력에 나오면 잡힌다. KVS
#     ARN 의 계정 번호 · ARN 은 출력에 0건.
#   - TMPDIR · HOME 에 남은 것이 없음.
# 추적(bash -x · SHELLOPTS) · allexport 를 켠 호출과 호출자가 값 변수 이름을
# export 해 둔 호출도 값을 내지 않는지 본다. aws CLI 명령 기록이 켜져
# 있거나(프로필의 cli_history · AWS_CLI_HISTORY_FILE) 그 검사를 하지 못하면
# 이름 풀이 · 값 읽기 전에 종료 1 로 멈추는지 본다(가짜 aws 의 configure get
# 답 — kvs_fakebin/aws 머리 주석).
#
# 정적 검사(no-heredoc · no-external-commands · no-file-writes ·
# no-endpoint-override · no-debug-or-cli-input-json)는 흔한 회귀를 잡는
# 울타리다. no-file-writes 는 put_secret_test 보다 단순해서, 따옴표 문자열
# 안의 > 도 쓰기로 본다(스크립트는 문자열에 > 를 쓰지 않는다).
#
# 실물 CLI 계약: PATH 에 진짜 aws 가 있으면, 가짜 aws 가 put-key 만 진짜 aws
# 에 넘기게 하고(FAKE_AWS_REAL — 다른 호출은 가짜 응답) 스크립트를 끝까지
# 돌린다. 가짜 자격 · 빈 설정 · 격리 HOME 에 엔드포인트와 프록시 모두 닫힌
# 로컬 주소 127.0.0.1:9 다(put_secret_test 와 같은 울타리). 진짜 aws 는 KVS
# 데이터면 주소 앞에 ARN 의 계정 번호를 붙이므로 그 이름(가짜 계정 번호 ·
# 127.0.0.1)을 실행 위치의 리졸버에 묻고 못 풀어 연결 단계에서 끝난다 — AWS 에
# 닿지 않는다. 가짜 aws 가 이 호출에만 --debug 를 붙여 요청 본문을 남기고,
# 시험은 본문이 비밀 값을 그대로 품는지(표준 입력을 한 번만 읽어 요청까지
# 갔는지)와 스크립트가 종료 3 · 연결 실패 두 줄을 내는지 본다. 진짜 aws 가
# 없으면 skip 으로 센다(CI 는 skip 을 실패로 본다).
#
# 임시 디렉터리는 한 뿌리($TMPDIR/kvs_put_test.*) 아래에 두고, 끝에 지운 뒤
# 남지 않았는지 확인한다(중단 신호에도 지운다). 끝에 통과 + skip 건수가 사례
# 수(EXPECTED_CASES)와 같은지 본다.
#
# 사용: bash infra/terraform/scripts/test/kvs_put_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly KVS_PUT="${SCRIPT_DIR}/../kvs-put-cdn-secret.sh"
readonly FAKEBIN="${SCRIPT_DIR}/kvs_fakebin"

# kvs-put-cdn-secret.sh 의 종료 코드.
readonly EXIT_FAILED=1
readonly EXIT_USAGE=2
readonly EXIT_UNRESOLVED=3
readonly EXIT_KVS_DENIED=4
readonly EXIT_SECRET_DENIED=5

# 가짜 값(AWS 문서의 예시 계정 번호). 가짜 aws 의 sts 응답(STS_ACCOUNT)을 KVS
# ARN 의 계정과 일부러 달리 둔다 — 스크립트가 sts 의 계정을 쓰면 잡히게.
readonly STS_ACCOUNT='111122223333'
readonly ARN_ACCOUNT='123456789012'
KVS_ARN="arn:aws:cloudfront::${ARN_ACCOUNT}:key-value-store/"
KVS_ARN+='a1b2c3d4-5678-90ab-cdef-example11111'
readonly KVS_ARN
readonly SECRET_ID='pokeclip/prod/cdn-secret'
# 가짜 aws 의 describe-key-value-store 응답 ETag.
readonly ETAG='ETVPDKIKX0DER'
# 비밀 값. 실물 CLI 사례의 요청 본문(JSON)에서 그대로 찾도록 JSON 이스케이프가
# 붙는 글자(" · \)는 넣지 않았다. 값 0건 검사의 표지는 앞 토막이다.
readonly SECRET_VALUE='FakeCdnSecret+/=_-.~Z9xyz'
readonly SECRET_MARKER='FakeCdnSecret'

# 부른 동작마다 기대하는 인자(한 줄로 이은 것). CLI 기록 검사(aws configure
# get)의 인자는 get.argv 에 적힌다.
readonly ARGV_HISTORY='configure get cli_history --no-cli-auto-prompt'
readonly ARGV_DIG="+short ${ARN_ACCOUNT}.cloudfront-kvs.global.api.aws"
ARGV_SECRET='secretsmanager get-secret-value --region ap-northeast-2'
ARGV_SECRET+=" --no-cli-auto-prompt --secret-id ${SECRET_ID}"
ARGV_SECRET+=' --query SecretString --output text'
readonly ARGV_SECRET
ARGV_DESCRIBE='cloudfront-keyvaluestore describe-key-value-store'
ARGV_DESCRIBE+=" --region us-east-1 --no-cli-auto-prompt --kvs-arn ${KVS_ARN}"
ARGV_DESCRIBE+=' --query ETag --output text'
readonly ARGV_DESCRIBE
ARGV_PUT='cloudfront-keyvaluestore put-key --region us-east-1'
ARGV_PUT+=" --no-cli-auto-prompt --output json --kvs-arn ${KVS_ARN}"
ARGV_PUT+=" --key cdn-secret --if-match ${ETAG} --value file:///dev/stdin"
readonly ARGV_PUT

# 부른 차례(쉼표로 이은 것). 단계마다 앞 단계를 품는다.
readonly CALLS_HISTORY='aws configure get'
readonly CALLS_DIG="${CALLS_HISTORY},dig"
readonly CALLS_SECRET="${CALLS_DIG},aws secretsmanager get-secret-value"
CALLS_DESCRIBE="${CALLS_SECRET},aws cloudfront-keyvaluestore"
CALLS_DESCRIBE+=' describe-key-value-store'
readonly CALLS_DESCRIBE
readonly CALLS_ALL="${CALLS_DESCRIBE},aws cloudfront-keyvaluestore put-key"

# 고정 문자열.
readonly NOTE_DIG='이름 풀이(dig) 실패 — 값을 넣지 않음 · 런북의 우회 절차로'
readonly NOTE_CONNECT='KVS 엔드포인트 연결 실패(Could not connect) — SCP 거부 아님'
readonly NOTE_DENIED='SCP 멈춤 규칙 — 다시 시도하지 말고 kty 에게 보고'
readonly HISTORY_ENABLED='CLI HISTORY ENABLED'
readonly HISTORY_UNCHECKED='CLI HISTORY UNCHECKED'
NOTE_HISTORY_PROFILE='프로필의 cli_history 가 enabled — 값을 읽지 않음 ·'
NOTE_HISTORY_PROFILE+=' 기록을 끄고 다시'
readonly NOTE_HISTORY_PROFILE
NOTE_HISTORY_FILE='AWS_CLI_HISTORY_FILE 이 설정됨 — 값을 읽지 않음 ·'
NOTE_HISTORY_FILE+=' 변수를 지우고 다시'
readonly NOTE_HISTORY_FILE
NOTE_HISTORY_FAIL='CLI 기록 설정(cli_history)을 읽지 못함 — 값을 읽지 않음 ·'
NOTE_HISTORY_FAIL+=' 켜짐과 같이 멈춤'
readonly NOTE_HISTORY_FAIL

# 사례 수(ok · skip 줄의 수). 사례를 더하거나 빼면 함께 고친다.
readonly EXPECTED_CASES=50

failures=0
passes=0
skips=0
utf8_locale=""
tmp_root=""
case_dir=""
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
# 사례 디렉터리(가짜 명령 기록 cmd/ · 빈 TMPDIR tmp/ · 빈 HOME home/)를 새로
# 만들고, 가짜 aws 가 환경 변수와 대조할 값 표지(cmd/markers)와 비밀 값
# (cmd/secret — 기본은 SECRET_VALUE)을 적는다. 못 만들면 실패로 센다.
# Globals:
#   tmp_root, case_dir
# Returns:
#   만들지 못하면 1.
#######################################
new_case() {
  if case_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" &&
    mkdir -- "${case_dir}/cmd" "${case_dir}/tmp" "${case_dir}/home" &&
    printf '%s\n' "${SECRET_MARKER}" >"${case_dir}/cmd/markers" &&
    printf '%s' "${SECRET_VALUE}" >"${case_dir}/cmd/secret"; then
    return 0
  fi
  fail "사례 디렉터리를 만들지 못함"
  return 1
}

#######################################
# 사례의 비밀 값을 printf 형식 그대로 바꾼다.
# Globals:
#   case_dir
# Arguments:
#   printf 형식(\n · \t 등을 해석), 형식 인자.
#######################################
set_secret() {
  # shellcheck disable=SC2059
  printf "$@" >"${case_dir}/cmd/secret"
}

#######################################
# kvs-put-cdn-secret.sh 를 돌린다. 호출자의 AWS_ 변수를 이름과 상관없이 모두
# 지운 하위 셸에서, PATH 는 가짜 명령 디렉터리 하나 · TMPDIR 과 HOME 은 사례의
# 빈 디렉터리 · SHELL 은 없는 경로 · 로캘은 UTF-8(있으면) · 표준 입력은
# /dev/null 이다.
# Globals:
#   case_dir, out, err, status, utf8_locale
# Arguments:
#   셸 모드(plain · xtrace-flag · xtrace-env · allexport-env · secret-export
#   — 호출자가 secret_value 를 export · no-aws · no-dig — PATH 에서 그 명령을
#   뺌 · history-file — AWS_CLI_HISTORY_FILE 설정 · history-<답> — 가짜 aws
#   의 configure get 답 FAKE_AWS_CLI_HISTORY), 가짜 aws 모드, 가짜 dig 모드,
#   스크립트 인자들.
#######################################
run_kvs_as() {
  local shell_mode="$1" aws_mode="$2" dig_mode="$3" var
  shift 3
  # 환경은 이 하위 셸 안에서만 바꾼다(의도).
  # shellcheck disable=SC2030,SC2031
  (
    for var in $(compgen -e); do
      case "${var}" in
        AWS_*) unset "${var}" ;;
      esac
    done
    export AWS_CONFIG_FILE=/nonexistent/kvs-put-test
    export AWS_SHARED_CREDENTIALS_FILE=/nonexistent/kvs-put-test
    export AWS_EC2_METADATA_DISABLED=true
    export FAKE_CMD_DIR="${case_dir}/cmd" FAKE_AWS_MODE="${aws_mode}"
    export FAKE_DIG_MODE="${dig_mode}"
    export TMPDIR="${case_dir}/tmp" HOME="${case_dir}/home"
    export SHELL=/nonexistent/kvs-put-test
    if [[ -n "${utf8_locale}" ]]; then
      export LC_ALL="${utf8_locale}"
    fi
    export PATH="${FAKEBIN}"
    case "${shell_mode}" in
      xtrace-flag) exec "${BASH}" -x "${KVS_PUT}" "$@" ;;
      no-aws) export PATH="${tmp_root}/bin-no-aws" ;;
      no-dig) export PATH="${tmp_root}/bin-no-dig" ;;
      xtrace-env)
        set -o xtrace
        export SHELLOPTS
        ;;
      allexport-env)
        set -o allexport
        export SHELLOPTS
        ;;
      secret-export) export secret_value=FakeInheritedExport ;;
      history-file)
        export AWS_CLI_HISTORY_FILE="${case_dir}/cmd/qxhistory.db"
        ;;
      history-*) export FAKE_AWS_CLI_HISTORY="${shell_mode#history-}" ;;
    esac
    exec "${BASH}" "${KVS_PUT}" "$@"
  ) </dev/null >"${case_dir}/stdout" 2>"${case_dir}/stderr"
  status=$?
  out="$(<"${case_dir}/stdout")"
  err="$(<"${case_dir}/stderr")"
}

#######################################
# 기본 인자(--kvs-arn · --secret-id)로 run_kvs_as plain.
# Arguments:
#   가짜 aws 모드, 가짜 dig 모드.
#######################################
run_kvs() {
  run_kvs_as plain "$1" "$2" --kvs-arn "${KVS_ARN}" --secret-id "${SECRET_ID}"
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
# 파일이 주어진 줄들(줄마다 끝 줄바꿈 하나)과 바이트 그대로 같은지 본다. 줄이
# 없으면 빈 파일이어야 한다. 명령 치환은 끝 줄바꿈을 지우므로 read -d '' 로
# 끝까지 읽는다(NUL 에서 멈추면 0 을 내므로 다르다고 본다).
# Arguments:
#   파일 경로, 기대 줄들.
# Returns:
#   다르면 1.
#######################################
file_is() {
  local file="$1" actual="" expected=""
  shift
  if IFS= read -r -d '' actual <"${file}"; then
    return 1
  fi
  (($# == 0)) || printf -v expected '%s\n' "$@"
  [[ "${actual}" == "${expected}" ]]
}

#######################################
# 가짜 명령을 부른 차례를 쉼표로 이어 낸다.
# Globals:
#   case_dir
# Outputs:
#   부른 차례.
#######################################
calls_csv() {
  local line joined=""
  if [[ -f "${case_dir}/cmd/calls" ]]; then
    while IFS= read -r line; do
      joined+="${joined:+,}${line}"
    done <"${case_dir}/cmd/calls"
  fi
  printf '%s' "${joined}"
}

#######################################
# 동작이 받은 인자를 한 줄로 이어 낸다.
# Globals:
#   case_dir
# Arguments:
#   동작 이름(dig · put-key 등 — <동작>.argv).
# Outputs:
#   인자.
#######################################
argv_of() {
  local argv
  argv="$(contents_of "${case_dir}/cmd/$1.argv")"
  printf '%s' "${argv//$'\n'/ }"
}

#######################################
# 부른 동작마다 인자가 기대와 같은지 본다.
# Globals:
#   case_dir
# Arguments:
#   사례 이름.
# Returns:
#   다르면 1.
#######################################
expect_argv() {
  local name="$1" op expected
  for op in get dig get-secret-value describe-key-value-store put-key; do
    [[ -f "${case_dir}/cmd/${op}.argv" ]] || continue
    case "${op}" in
      get) expected="${ARGV_HISTORY}" ;;
      dig) expected="${ARGV_DIG}" ;;
      get-secret-value) expected="${ARGV_SECRET}" ;;
      describe-key-value-store) expected="${ARGV_DESCRIBE}" ;;
      put-key) expected="${ARGV_PUT}" ;;
    esac
    if [[ "$(argv_of "${op}")" != "${expected}" ]]; then
      fail "${name}: ${op} 인자가 다름 — $(argv_of "${op}")"
      return 1
    fi
  done
}

#######################################
# 가짜 aws 가 값 넘기기 규칙 위반을 적지 않았고, 받은 환경 변수에 값 · 표지가
# 없었는지 본다(env-hits 는 이름만 적힘).
# Globals:
#   case_dir
# Arguments:
#   사례 이름.
# Returns:
#   어긋나면 1.
#######################################
expect_clean_handoff() {
  if [[ -e "${case_dir}/cmd/violation" ]]; then
    fail "$1: 값 넘기기 규칙 위반 — $(<"${case_dir}/cmd/violation")"
    return 1
  fi
  if [[ -s "${case_dir}/cmd/env-hits" ]]; then
    fail "$1: aws 가 받은 환경 변수에 값이 있음 —" \
      "$(contents_of "${case_dir}/cmd/env-hits")"
    return 1
  fi
}

#######################################
# 비밀 값 표지가 출력 · 모든 인자 · 사례 디렉터리의 허용 목록 밖 파일에 없고,
# TMPDIR · HOME 이 비었는지 본다. 허용 목록: 시험이 넣은 값(cmd/secret) · 가짜
# aws 가 받은 값(cmd/stdin) · 표지(cmd/markers) · 실물 CLI 의 디버그 출력
# (cmd/real-debug · cmd/real-stdout).
# Globals:
#   case_dir, out, err
# Arguments:
#   사례 이름.
# Returns:
#   어긋나면 1.
#######################################
expect_secret_hidden() {
  local name="$1" file
  if [[ "${out}${err}" == *"${SECRET_MARKER}"* ]]; then
    fail "${name}: 비밀 값이 표준 출력 · 표준 오류에 나옴"
    return 1
  fi
  if [[ -n "$(ls -A "${case_dir}/tmp")" || -n "$(ls -A "${case_dir}/home")" ]]
  then
    fail "${name}: TMPDIR · HOME 에 파일이 남음"
    return 1
  fi
  while IFS= read -r file; do
    case "${file#"${case_dir}/"}" in
      cmd/secret | cmd/stdin | cmd/markers) ;;
      cmd/real-debug | cmd/real-stdout) ;;
      *)
        fail "${name}: 비밀 값이 ${file#"${case_dir}/"} 에 있음"
        return 1
        ;;
    esac
  done < <(grep -rlF -- "${SECRET_MARKER}" "${case_dir}" 2>/dev/null)
}

#######################################
# sts 응답의 계정 번호가 어느 인자 · 출력에도 없고, KVS ARN(과 그 계정
# 번호)이 출력에 없는지 본다. ARN 의 계정 번호는 dig 인자(호스트)와 KVS
# 호출의 --kvs-arn 에만 있다(인자 대조 — expect_argv).
# Globals:
#   case_dir, out, err
# Arguments:
#   사례 이름.
# Returns:
#   어긋나면 1.
#######################################
expect_account_hidden() {
  local name="$1" file
  if [[ "${out}${err}" == *"${STS_ACCOUNT}"* ||
    "${out}${err}" == *"${ARN_ACCOUNT}"* || "${out}${err}" == *arn:aws* ]]; then
    fail "${name}: 계정 번호 · ARN 이 표준 출력 · 표준 오류에 나옴"
    return 1
  fi
  for file in "${case_dir}"/cmd/*.argv; do
    [[ -f "${file}" ]] || continue
    if [[ "$(<"${file}")" == *"${STS_ACCOUNT}"* ]]; then
      fail "${name}: sts 응답의 계정 번호가 ${file##*/} 에 있음"
      return 1
    fi
  done
}

#######################################
# 한 번 돌린 결과를 본다: 종료 코드 · 표준 출력 · 표준 오류(바이트 그대로) ·
# 부른 차례 · 인자 · put-key 가 받은 값 · 값과 계정 번호 0건 · 남은 것 없음.
# Globals:
#   case_dir, out, err, status
# Arguments:
#   사례 이름, 기대 종료 코드, 표준 출력 줄(비면 빈 출력), 기대 차례(쉼표),
#   표준 오류의 기대 줄들(없으면 빈 출력).
# Returns:
#   어긋나면 1.
#######################################
check_run() {
  local name="$1" code="$2" stdout_line="$3" calls="$4"
  shift 4
  expect_clean_handoff "${name}" || return 1
  if [[ "${status}" -ne "${code}" ]]; then
    fail "${name}: 종료 ${code} 를 기대했으나 ${status} — ${err}"
    return 1
  fi
  if [[ -n "${stdout_line}" ]] &&
    ! file_is "${case_dir}/stdout" "${stdout_line}"; then
    fail "${name}: 표준 출력이 「${stdout_line}」 한 줄이 아님 — ${out}"
    return 1
  elif [[ -z "${stdout_line}" && -s "${case_dir}/stdout" ]]; then
    fail "${name}: 표준 출력이 있음 — ${out}"
    return 1
  fi
  if ! file_is "${case_dir}/stderr" "$@"; then
    fail "${name}: 표준 오류가 「$*」 와 같지 않음 — ${err}"
    return 1
  fi
  if [[ "$(calls_csv)" != "${calls}" ]]; then
    fail "${name}: 부른 차례가 다름 — $(calls_csv)"
    return 1
  fi
  expect_argv "${name}" || return 1
  # 실물 CLI 사례(real-debug 가 있음)는 가짜 aws 가 값을 읽지 않고 진짜 aws 에
  # 물려준다 — 그 사례는 요청 본문으로 따로 본다.
  if [[ -f "${case_dir}/cmd/put-key.argv" &&
    ! -e "${case_dir}/cmd/real-debug" ]] &&
    ! cmp -s -- "${case_dir}/cmd/stdin" "${case_dir}/cmd/secret"; then
    fail "${name}: put-key 가 받은 값이 비밀 값과 다름"
    return 1
  fi
  expect_secret_hidden "${name}" || return 1
  expect_account_hidden "${name}" || return 1
}

#######################################
# 기본 인자로 돌리고 check_run 으로 본다.
# Arguments:
#   사례 이름, 가짜 aws 모드, 가짜 dig 모드, 기대 종료 코드, 표준 출력 줄,
#   기대 차례, 표준 오류의 기대 줄들.
#######################################
expect_run() {
  local name="$1" aws_mode="$2" dig_mode="$3"
  shift 3
  run_kvs "${aws_mode}" "${dig_mode}"
  check_run "${name}" "$@" || return
  pass "${name} → 종료 $1"
}

#######################################
# 사용법 오류인지 본다: 종료 2 · 사용법 줄 · 아무것도 부르지 않음.
# Arguments:
#   사례 이름, 스크립트 인자들.
#######################################
expect_usage() {
  local name="$1"
  shift
  new_case || return
  run_kvs_as plain ok ok "$@"
  if [[ "${status}" -ne "${EXIT_USAGE}" || "${err}" != *"사용: "* ||
    -n "${out}" ]]; then
    fail "${name}: 종료 ${EXIT_USAGE} · 사용법을 기대했으나 ${status} — ${err}"
    return
  fi
  if [[ -n "$(calls_csv)" ]]; then
    fail "${name}: 사용법 오류인데 명령을 부름 — $(calls_csv)"
    return
  fi
  expect_account_hidden "${name}" || return
  pass "${name} → 사용법 오류"
}

#######################################
# 정상: 값을 넣는다. sts 를 부르지 않고(부른 차례 대조 · get-caller-identity
# 기록 없음), 이름 풀이 호스트는 ARN 의 계정 번호이고, ETag 를 put-key 의
# --if-match 로 쓰며(인자 대조), aws 의 페이저를 끈다(AWS_PAGER 빈 값). dig 가
# IPv6 주소만 내도 풀린 것으로 본다.
# Globals:
#   case_dir
#######################################
test_success() {
  local op
  new_case || return
  run_kvs ok ok
  check_run success 0 "KVS PUT OK" "${CALLS_ALL}" || return
  if [[ -e "${case_dir}/cmd/get-caller-identity.argv" ]]; then
    fail "success: sts get-caller-identity 를 부름"
    return
  fi
  for op in get-secret-value describe-key-value-store put-key; do
    if [[ "$(contents_of "${case_dir}/cmd/${op}.pager")" != AWS_PAGER= ]]
    then
      fail "success: ${op} 의 AWS_PAGER 가 빈 값이 아님"
      return
    fi
  done
  pass "success → 넣음(ETag ${ETAG} · 값은 표준 입력으로만)"
  new_case || return
  expect_run success-ipv6 ok ok-v6 0 "KVS PUT OK" "${CALLS_ALL}"
}

#######################################
# 종료 3: KVS 데이터면 호스트 이름을 못 풀면(값을 읽기 전 — Secrets Manager ·
# KVS 를 부르지 않음), 또 KVS 호출이 「Could not connect」 로 끝나면.
#######################################
test_unresolved() {
  local mode
  for mode in empty cname-only timeout; do
    new_case || return
    expect_run "dig-${mode}" ok "${mode}" "${EXIT_UNRESOLVED}" "" \
      "${CALLS_DIG}" "KVS HOST UNRESOLVED" "${NOTE_DIG}"
  done
  new_case || return
  expect_run describe-connect describe-connect ok "${EXIT_UNRESOLVED}" "" \
    "${CALLS_DESCRIBE}" "KVS HOST UNRESOLVED" "${NOTE_CONNECT}"
  new_case || return
  expect_run put-connect put-connect ok "${EXIT_UNRESOLVED}" "" \
    "${CALLS_ALL}" "KVS HOST UNRESOLVED" "${NOTE_CONNECT}"
}

#######################################
# 종료 4: KVS 호출(ETag 읽기 · PutKey)의 AccessDenied. 종료 5: Secrets
# Manager 의 AccessDenied — KVS 를 부르지 않는다.
#######################################
test_denied() {
  new_case || return
  expect_run describe-denied describe-denied ok "${EXIT_KVS_DENIED}" "" \
    "${CALLS_DESCRIBE}" "KVS PUT DENIED" "${NOTE_DENIED}"
  new_case || return
  expect_run put-denied put-denied ok "${EXIT_KVS_DENIED}" "" \
    "${CALLS_ALL}" "KVS PUT DENIED" "${NOTE_DENIED}"
  new_case || return
  expect_run secret-denied secret-denied ok "${EXIT_SECRET_DENIED}" "" \
    "${CALLS_SECRET}" "SECRET READ DENIED" "${NOTE_DENIED}"
}

#######################################
# 종료 1: 그 밖의 실패 — 단계와 AWS 오류 코드만 적는다(원문은 ARN · 계정
# 번호 · 되울린 값을 품는다). 코드 없는 네트워크 단계 실패는 ENDPOINT 다
# (Secrets Manager 의 연결 실패와 PutKey 의 시간 초과는 종료 3 이 아님).
#######################################
test_failures() {
  new_case || return
  expect_run secret-error secret-error ok "${EXIT_FAILED}" "" \
    "${CALLS_SECRET}" "KVS PUT FAILED SECRET ResourceNotFoundException"
  new_case || return
  expect_run secret-connect secret-connect ok "${EXIT_FAILED}" "" \
    "${CALLS_SECRET}" "KVS PUT FAILED SECRET ENDPOINT"
  new_case || return
  expect_run describe-none describe-none ok "${EXIT_FAILED}" "" \
    "${CALLS_DESCRIBE}" "KVS PUT FAILED ETAG NO-ETAG"
  new_case || return
  expect_run put-conflict put-conflict ok "${EXIT_FAILED}" "" \
    "${CALLS_ALL}" "KVS PUT FAILED PUT ConflictException"
  new_case || return
  expect_run put-timeout put-timeout ok "${EXIT_FAILED}" "" \
    "${CALLS_ALL}" "KVS PUT FAILED PUT ENDPOINT"
}

#######################################
# 값 검사(KVS 를 부르기 전): 빈 값 · None(SecretString 없음 — 바이너리 비밀)은
# SECRET VALUE EMPTY, 줄바꿈 · 공백 · 탭 · 제어 문자 · ASCII 밖 문자는 SECRET
# VALUE INVALID. 모두 종료 1 이고 KVS 를 부르지 않는다.
#######################################
test_secret_values() {
  local invalid="SECRET VALUE INVALID" name format
  local -a names=(multi-line inner-space inner-tab carriage-return non-ascii)
  local -a formats=('%sA\n%sB' '%s A' '%s\tB' '%s\rC' '%s\303\251')
  local i
  new_case || return
  set_secret ''
  expect_run secret-empty ok ok "${EXIT_FAILED}" "" "${CALLS_SECRET}" \
    "SECRET VALUE EMPTY"
  new_case || return
  set_secret 'None'
  expect_run secret-none ok ok "${EXIT_FAILED}" "" "${CALLS_SECRET}" \
    "SECRET VALUE EMPTY"
  for ((i = 0; i < ${#names[@]}; i++)); do
    name="${names[i]}"
    format="${formats[i]}"
    new_case || return
    set_secret "${format}" "${SECRET_MARKER}" "${SECRET_MARKER}"
    expect_run "secret-${name}" ok ok "${EXIT_FAILED}" "" "${CALLS_SECRET}" \
      "${invalid}"
  done
}

#######################################
# 인자가 틀리면 사용법 오류(종료 2) — 아무것도 부르지 않는다.
#######################################
test_usage() {
  local sm_arn='arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:x'
  expect_usage usage-no-args
  expect_usage usage-only-kvs --kvs-arn "${KVS_ARN}"
  expect_usage usage-only-secret --secret-id "${SECRET_ID}"
  expect_usage usage-missing-value --secret-id "${SECRET_ID}" --kvs-arn
  expect_usage usage-unknown-option --kvs-arn "${KVS_ARN}" --secret-id \
    "${SECRET_ID}" --region us-east-1
  expect_usage usage-equals-form "--kvs-arn=${KVS_ARN}" --secret-id \
    "${SECRET_ID}"
  expect_usage usage-duplicate --kvs-arn "${KVS_ARN}" --kvs-arn "${KVS_ARN}" \
    --secret-id "${SECRET_ID}"
  expect_usage usage-bad-kvs-arn --kvs-arn "${sm_arn}" --secret-id \
    "${SECRET_ID}"
  expect_usage usage-kvs-arn-short-account --kvs-arn \
    "arn:aws:cloudfront::12345678901:key-value-store/a1" --secret-id \
    "${SECRET_ID}"
  expect_usage usage-kvs-arn-other-resource --kvs-arn \
    "arn:aws:cloudfront::${ARN_ACCOUNT}:distribution/E1" --secret-id \
    "${SECRET_ID}"
  expect_usage usage-bad-secret-id --kvs-arn "${KVS_ARN}" --secret-id \
    "pokeclip/prod/cdn secret"
}

#######################################
# aws CLI 명령 기록: 프로필의 cli_history 가 enabled 이거나
# AWS_CLI_HISTORY_FILE 이 설정돼 있으면 「CLI HISTORY ENABLED」, 설정을 읽지
# 못하면 「CLI HISTORY UNCHECKED」 로 종료 1 — 기록 검사 밖에는 아무것도
# 부르지 않는다(이름 풀이 · 값 읽기 · KVS 0 · 환경 변수 갈래는 설정 조회도
# 0). 기록 파일 경로는 되울리지 않는다
# (표준 오류 바이트 대조). disabled 는 그대로 넣는다(값 없음은 다른 사례).
#######################################
test_cli_history() {
  local spec note head calls
  for spec in enabled file fail; do
    head="${HISTORY_ENABLED}"
    calls="${CALLS_HISTORY}"
    case "${spec}" in
      enabled) note="${NOTE_HISTORY_PROFILE}" ;;
      file)
        note="${NOTE_HISTORY_FILE}"
        calls=""
        ;;
      fail)
        head="${HISTORY_UNCHECKED}"
        note="${NOTE_HISTORY_FAIL}"
        ;;
    esac
    new_case || return
    run_kvs_as "history-${spec}" ok ok --kvs-arn "${KVS_ARN}" --secret-id \
      "${SECRET_ID}"
    check_run "history-${spec}" "${EXIT_FAILED}" "" "${calls}" \
      "${head}" "${note}" || continue
    pass "history-${spec} → 종료 ${EXIT_FAILED}"
  done
  new_case || return
  run_kvs_as history-disabled ok ok --kvs-arn "${KVS_ARN}" --secret-id \
    "${SECRET_ID}"
  check_run history-disabled 0 "KVS PUT OK" "${CALLS_ALL}" || return
  pass "history-disabled → 넣음"
}

#######################################
# PATH 에 aws · dig 가 없으면 아무것도 부르지 않고 실패(종료 1)한다.
# Globals:
#   tmp_root
#######################################
test_missing_tools() {
  local mode tool
  if ! mkdir -- "${tmp_root}/bin-no-aws" "${tmp_root}/bin-no-dig" ||
    ! ln -s -- "${FAKEBIN}/dig" "${FAKEBIN}/jq" "${tmp_root}/bin-no-aws/" ||
    ! ln -s -- "${FAKEBIN}/aws" "${FAKEBIN}/jq" "${tmp_root}/bin-no-dig/"; then
    fail "missing-tools: 가짜 명령 디렉터리를 만들지 못함"
    return
  fi
  for tool in aws dig; do
    mode="no-${tool}"
    new_case || return
    run_kvs_as "${mode}" ok ok --kvs-arn "${KVS_ARN}" --secret-id "${SECRET_ID}"
    check_run "${mode}" "${EXIT_FAILED}" "" "" \
      "KVS PUT FAILED PREREQ ${tool}" || continue
    pass "${mode} → 종료 ${EXIT_FAILED}"
  done
}

#######################################
# 추적(bash -x · SHELLOPTS 의 xtrace) · allexport 를 물려받은 호출, 호출자가
# 값 변수 이름(secret_value)을 export 해 둔 호출도 값을 내지 않는다. 추적은
# 스크립트가 끄기 전의 줄을 표준 오류에 낼 수 있어 표준 오류는 값 0건만 본다.
#######################################
test_quiet_shells() {
  local mode name
  for mode in xtrace-flag xtrace-env allexport-env secret-export; do
    name="quiet-${mode}"
    new_case || return
    run_kvs_as "${mode}" ok ok --kvs-arn "${KVS_ARN}" --secret-id "${SECRET_ID}"
    expect_clean_handoff "${name}" || continue
    if [[ "${status}" -ne 0 ]] || ! file_is "${case_dir}/stdout" "KVS PUT OK"
    then
      fail "${name}: 종료 0 · 성공 줄을 기대했으나 ${status} — ${out}"
      continue
    fi
    if ! cmp -s -- "${case_dir}/cmd/stdin" "${case_dir}/cmd/secret"; then
      fail "${name}: put-key 가 받은 값이 비밀 값과 다름"
      continue
    fi
    expect_secret_hidden "${name}" || continue
    if [[ "${mode}" == xtrace-env ]]; then
      # 시험 하위 셸 자신의 추적 두 줄(export · exec — exec 줄은 입력 ARN 을
      # 품음)은 스크립트의 출력이 아니므로 떼고 계정 번호를 본다.
      if [[ "${err}" != "+ export SHELLOPTS"$'\n''+ exec '*$'\n'* ]]; then
        fail "${name}: 표준 오류 앞머리가 시험 하위 셸의 추적 두 줄이 아님"
        continue
      fi
      err="${err#*$'\n'}"
      err="${err#*$'\n'}"
    fi
    expect_account_hidden "${name}" || continue
    pass "${name} → 넣음 · 값 0건"
  done
}

#######################################
# 실물 CLI 계약: 진짜 aws 가 --value file:///dev/stdin 의 표준 입력을 한 번만
# 읽어 요청 본문까지 만드는지, 그리고 연결 실패를 스크립트가 종료 3 으로
# 적는지 본다(머리 주석의 울타리). 진짜 aws 가 없으면 skip.
# Globals:
#   case_dir, out, err, status
#######################################
test_real_cli_contract() {
  local real_aws version var debug body
  real_aws="$(command -v aws)" || real_aws=""
  if [[ -z "${real_aws}" || "${real_aws}" != /* ]]; then
    skip "real-cli-contract(PATH 에 진짜 aws 없음)"
    return
  fi
  new_case || return
  # 진짜 aws 는 HOME 에 캐시를 쓸 수 있다. 사례 HOME(home/ — 비었는지 봄)과
  # 따로 둔다.
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
    export HTTPS_PROXY=http://127.0.0.1:9 https_proxy=http://127.0.0.1:9
    export HTTP_PROXY=http://127.0.0.1:9 http_proxy=http://127.0.0.1:9
    export NO_PROXY=127.0.0.1 no_proxy=127.0.0.1
    export HOME="${case_dir}/cli-home" TMPDIR="${case_dir}/tmp"
    export SHELL=/nonexistent/kvs-put-test
    export AWS_ACCESS_KEY_ID=testing AWS_SECRET_ACCESS_KEY=testing
    export AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null
    export AWS_EC2_METADATA_DISABLED=true AWS_MAX_ATTEMPTS=1
    export AWS_ENDPOINT_URL=http://127.0.0.1:9
    export FAKE_CMD_DIR="${case_dir}/cmd" FAKE_AWS_MODE=ok FAKE_DIG_MODE=ok
    export FAKE_AWS_REAL="${real_aws}"
    "${real_aws}" --version >"${case_dir}/version" 2>&1
    export PATH="${FAKEBIN}"
    exec "${BASH}" "${KVS_PUT}" --kvs-arn "${KVS_ARN}" --secret-id \
      "${SECRET_ID}"
  ) </dev/null >"${case_dir}/stdout" 2>"${case_dir}/stderr"
  status=$?
  out="$(<"${case_dir}/stdout")"
  err="$(<"${case_dir}/stderr")"
  version="$(contents_of "${case_dir}/version")"
  version="${version%% *}"
  debug="$(contents_of "${case_dir}/cmd/real-debug")"
  if [[ "${debug}" == *ParamValidation* ]]; then
    fail "real-cli-contract(${version}): CLI 가 값 넘기는 법을 거부"
    return
  fi
  body="'body': b'{\"Value\": \"${SECRET_VALUE}\"}'"
  if [[ "${debug}" != *"${body}"* ]]; then
    fail "real-cli-contract(${version}): 요청 본문에 비밀 값이 그대로 없음" \
      "(표준 입력을 두 번 읽었거나 요청까지 가지 않음)"
    return
  fi
  check_run real-cli-contract "${EXIT_UNRESOLVED}" "" "${CALLS_ALL}" \
    "KVS HOST UNRESOLVED" "${NOTE_CONNECT}" || return
  pass "real-cli-contract(${version}) → 본문까지 만들고 연결 단계에서 끝남"
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
# 스크립트의 주석 아닌 줄마다 패턴을 찾는다.
# Arguments:
#   검사 이름, 패턴들(글자 그대로).
# Returns:
#   찾으면 1(실패로 셈).
#######################################
expect_no_code_pattern() {
  local name="$1" line number=0 comment_re='^[[:space:]]*#' pattern
  shift
  if [[ ! -r "${KVS_PUT}" ]]; then
    fail "${name}: 스크립트를 읽을 수 없음 — ${KVS_PUT}"
    return 1
  fi
  while IFS= read -r line; do
    number=$((number + 1))
    [[ "${line}" =~ ${comment_re} ]] && continue
    for pattern in "$@"; do
      if [[ "${line}" == *"${pattern}"* ]]; then
        fail "${name}: ${number}행에 「${pattern}」"
        return 1
      fi
    done
  done <"${KVS_PUT}"
}

#######################################
# here-doc · here-string 이 없다(bash 3.2 는 본문을 임시 파일에 씀). 주석 줄도
# 본다.
#######################################
test_no_heredoc() {
  local line number=0
  if [[ ! -r "${KVS_PUT}" ]]; then
    fail "no-heredoc: 스크립트를 읽을 수 없음 — ${KVS_PUT}"
    return
  fi
  while IFS= read -r line; do
    number=$((number + 1))
    if [[ "${line}" == *'<<'* ]]; then
      fail "no-heredoc: ${number}행에 here-doc · here-string"
      return
    fi
  done <"${KVS_PUT}"
  pass "no-heredoc → 0건"
}

#######################################
# aws · dig 밖의 다른 프로세스에 값을 넘길 길을 소스로 본다 — 절대 경로 · jq ·
# command -p · $BASH · eval · exec · env 는 PATH 를 가짜 디렉터리 하나로 둔
# 시험을 비켜 간다.
#######################################
test_no_external_commands() {
  # 글자 그대로 찾는 패턴이다(펼치지 않음).
  # shellcheck disable=SC2016
  expect_no_code_pattern no-external-commands '/usr/' '/bin/' 'jq' \
    'command -p' '${BASH}' '$BASH' 'eval' 'exec ' 'env ' || return
  pass "no-external-commands → 0건"
}

#######################################
# --debug(값을 표준 오류에 찍음) · --cli-input-json(file:// 이중 읽기)을 쓰지
# 않는다.
#######################################
test_no_debug_or_cli_input_json() {
  expect_no_code_pattern no-debug-or-cli-input-json '--debug' \
    'cli-input-json' || return
  pass "no-debug-or-cli-input-json → 0건"
}

#######################################
# 파일에 쓰지 않는다 — 주석 아닌 줄의 > · >> 대상은 fd 복제(&1 · &2)와
# /dev/null 만 허용한다. 따옴표 문자열 안의 > 도 쓰기로 본다(보수적). 백틱은
# 검사기가 읽지 못하므로 실패로 센다.
#######################################
test_no_file_writes() {
  local line number=0 rest comment_re='^[[:space:]]*#'
  local allowed_re='^(.*[^0-9>])?[0-9]*>>?[[:space:]]*(&[12]|/dev/null)'
  allowed_re+='([^[:alnum:]_./-].*)?$'
  if [[ ! -r "${KVS_PUT}" ]]; then
    fail "no-file-writes: 스크립트를 읽을 수 없음 — ${KVS_PUT}"
    return
  fi
  while IFS= read -r line; do
    number=$((number + 1))
    [[ "${line}" =~ ${comment_re} ]] && continue
    if [[ "${line}" == *'`'* ]]; then
      fail "no-file-writes: ${number}행에 백틱"
      return
    fi
    rest="${line}"
    while [[ "${rest}" =~ ${allowed_re} ]]; do
      rest="${BASH_REMATCH[1]}${BASH_REMATCH[3]}"
    done
    if [[ "${rest}" == *'>'* ]]; then
      fail "no-file-writes: ${number}행 — > · >> 대상이 &1 · &2 · /dev/null 밖"
      return
    fi
  done <"${KVS_PUT}"
  pass "no-file-writes → 0건"
}

#######################################
# 엔드포인트를 바꾸지 않는다(실물 CLI 사례의 닫힌 로컬 주소를 비켜 갈 수
# 있음). 주석 줄도 보고 대소문자는 가리지 않는다.
#######################################
test_no_endpoint_override() {
  local line number=0 found=0
  if [[ ! -r "${KVS_PUT}" ]]; then
    fail "no-endpoint-override: 스크립트를 읽을 수 없음 — ${KVS_PUT}"
    return
  fi
  shopt -s nocasematch
  while IFS= read -r line; do
    number=$((number + 1))
    if [[ "${line}" == *endpoint[-_]url* ]]; then
      found="${number}"
      break
    fi
  done <"${KVS_PUT}"
  shopt -u nocasematch
  if ((found > 0)); then
    fail "no-endpoint-override: ${found}행에 endpoint-url · endpoint_url 꼴"
    return
  fi
  pass "no-endpoint-override → 0건"
}

main() {
  local tool
  # 실행 비트가 없으면 PATH 의 가짜 명령이 불리지 않는다 — 시작하지 않는다.
  for tool in aws dig jq; do
    if [[ ! -x "${FAKEBIN}/${tool}" ]]; then
      printf '%s\n' "kvs_put_test: 가짜 ${tool} 에 실행 비트가 없음" \
        "  ${FAKEBIN}/${tool}" >&2
      exit 1
    fi
  done
  # 트랩을 먼저 건다 — 뿌리를 만든 직후에 신호가 와도 남지 않게.
  trap remove_tmp_root EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # 여기의 TMPDIR 은 호출자의 것이다(사례 하위 셸의 TMPDIR 과 무관).
  # shellcheck disable=SC2031
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/kvs_put_test.XXXXXX")" || {
    printf '%s\n' "kvs_put_test: 임시 디렉터리를 만들지 못함" >&2
    exit 1
  }
  test_locale
  test_usage
  test_success
  test_unresolved
  test_denied
  test_failures
  test_secret_values
  test_cli_history
  test_missing_tools
  test_quiet_shells
  test_real_cli_contract
  test_no_heredoc
  test_no_external_commands
  test_no_debug_or_cli_input_json
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
    printf 'kvs_put_test: %s건 실패(통과 %s건 · skip %s건)\n' \
      "${failures}" "${passes}" "${skips}" >&2
    exit 1
  fi
  printf 'kvs_put_test: 전부 통과(%s건 · skip %s건 · 임시 디렉터리 잔여 0)\n' \
    "${passes}" "${skips}"
}

main "$@"
