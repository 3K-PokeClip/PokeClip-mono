# shellcheck shell=bash
#
# 단계 직전 점검 도구(scp-recheck.sh · events.sh · principal-check.sh)가
# 함께 쓰는 도우미 — 값 꼴 검사 · 탭 구분 줄 검사 · 파일 해시 · 증거 파일
# 쓰기 · 호출 주체 확인 · CLI 기록 꺼짐 확인. 계약: 도우미의 뜻(받는 값 · 멈추는 조건 · 문구)은
# 세 도구에서 같고, 구현은 여기 한 곳이다. 증거 경로 검사는 갖지 않는다
# (lib/evidence.sh 가 한 곳이다).
#
# 부르는 쪽 계약:
#   - die <설명> 을 정의한다(표준 오류에 「검사 불가」 를 적고 종료 2). 이
#     파일의 함수는 멈출 일에 die 를 부른다.
#   - `umask 077` 로 돈다 — 증거 파일의 0600 은 부르는 쪽 umask 가 정한다.
#   - 아래 상수 · 호출 주체 변수를 다시 선언하지 않는다. 다시 선언하면
#     readonly 오류가 나지만 source 는 0 을 돌려줘 `|| die` 로 잡히지 않는다
#     — 다시 선언하지 않는 것이 유일한 방어다.
#
# 쓰는 도구: aws(sts get-caller-identity · configure get), sha256sum 또는
# shasum.
#
# 사용: source lib/precheck_common.sh

# 증거에 남기는 sha256(64자리 16진수).
readonly SHA256_PATTERN='^[0-9a-f]{64}$'
# 호출 주체가 IAM 사용자인 ARN(경로 포함).
readonly USER_ARN_PATTERN='^arn:aws:iam::[0-9]{12}:user/[A-Za-z0-9+=,.@_/-]+$'

# precheck::caller_identity 가 남기는 호출 주체(값은 출력하지 않는다).
# shellcheck disable=SC2034 # 부르는 도구가 읽는다.
caller_arn=""
# shellcheck disable=SC2034 # 부르는 도구가 읽는다.
account=""

#######################################
# 값이 JSON 문자열 · TSV 칸에 그대로 들어가도 되는지 본다(큰따옴표 ·
# 역슬래시 · 제어 문자 금지).
# Arguments:
#   값.
# Returns:
#   들어가도 되면 0.
#######################################
precheck::is_plain() {
  [[ -n "$1" && "$1" != *[\"\\]* && "$1" != *[[:cntrl:]]* ]]
}

#######################################
# 탭 구분 줄의 칸 수가 정확하고 빈 칸이 없는지 본다. 탭 IFS 의 read 는 빈
# 칸을 접어 칸을 밀기 때문에, 칸을 나누기 전에 이것으로 먼저 거른다.
# Arguments:
#   줄, 칸 수.
# Returns:
#   맞으면 0.
#######################################
precheck::tsv_ok() {
  local line="$1" fields="$2" tab=$'\t' tabs
  tabs="${line//[!${tab}]/}"
  [[ "${#tabs}" -eq $((fields - 1)) && "${line}" != "${tab}"* &&
    "${line}" != *"${tab}" && "${line}" != *"${tab}${tab}"* ]]
}

#######################################
# 파일의 sha256 을 낸다(GNU sha256sum, 없으면 shasum -a 256).
# Globals:
#   SHA256_PATTERN
# Arguments:
#   파일.
# Outputs:
#   64자리 16진수.
# Returns:
#   계산하지 못하면 1.
#######################################
precheck::file_sha256() {
  local out
  out="$(sha256sum -- "$1" 2>/dev/null || shasum -a 256 -- "$1" 2>/dev/null)" ||
    return 1
  out="${out%% *}"
  [[ "${out}" =~ ${SHA256_PATTERN} ]] || return 1
  printf '%s\n' "${out}"
}

#######################################
# 증거 파일 하나를 printf 로 쓴다. 쓰지 못하면(디렉터리 권한 · 디스크 등)
# bash 의 원시 진단(절대 경로가 든다)은 버리고 파일 이름만 적어 멈춘다.
# Arguments:
#   파일, printf 형식, 값들.
#######################################
precheck::write_evidence() {
  local file="$1"
  shift
  # shellcheck disable=SC2059 # 형식은 부르는 쪽의 고정 값이다.
  { printf "$@"; } 2>/dev/null >"${file}" ||
    die "증거를 쓰지 못함(${file##*/})"
}

#######################################
# 호출 주체를 확인한다. IAM 사용자가 아니면 멈춘다(시뮬레이터는 역할 세션
# ARN 을 받지 않는다). 값은 변수에만 두고 원본은 증거 디렉터리의 sts.txt 에
# 남긴다. sts.err 는 호출 전에 비운 파일로 먼저 만든다 — 그 쓰기가 실패하면
# 호출 실패가 아니라 증거 쓰기 실패로 멈추게.
# Globals:
#   caller_arn, account, USER_ARN_PATTERN
# Arguments:
#   증거 디렉터리.
#######################################
precheck::caller_identity() {
  local dir="$1" out
  precheck::write_evidence "${dir}/sts.err" ''
  # 바깥 2>/dev/null 은 안쪽 리다이렉션이 실패할 때의 원시 진단만 버린다.
  out="$({ aws sts get-caller-identity --query '[Arn,Account]' \
    --output text 2>"${dir}/sts.err"; } 2>/dev/null)" ||
    die "호출 주체를 확인하지 못함(증거 디렉터리의 sts.err)"
  precheck::write_evidence "${dir}/sts.txt" '%s\n' "${out}"
  caller_arn="${out%%$'\t'*}"
  account="${out#*$'\t'}"
  if [[ "${out}" != *$'\t'* ]] || ! [[ "${account}" =~ ^[0-9]{12}$ ]]; then
    die "호출 주체 응답의 꼴이 다름(증거 디렉터리의 sts.txt)"
  fi
  if ! [[ "${caller_arn}" =~ ${USER_ARN_PATTERN} ]] ||
    [[ "${caller_arn}" != "arn:aws:iam::${account}:user/"* ]]; then
    die "호출 주체가 IAM 사용자가 아님(시뮬레이터는 역할 세션 ARN 을 받지 않음)"
  fi
}

#######################################
# CLI 기록이 꺼져 있는지 본다 — 켜져 있으면 요청 · 응답 원문이 증거 밖 기록
# 파일에 남는다. 첫 AWS 호출 전에 부른다. 값 · 경로는 되울리지 않는다.
# AWS_CLI_HISTORY_FILE 은 기록 위치 변수다(aws CLI 2.38.1
# customizations/history/__init__.py:50-55) — 켜짐 증거가 아니라 보수적
# 거부다. aws 호출에 --profile 을 붙이게 되면 configure get 에도 같은
# --profile 을 붙인다(둘 다 그 프로필 범위의 설정을 읽는다).
# Globals:
#   AWS_CLI_HISTORY_FILE
#######################################
precheck::check_cli_history() {
  local value status=0
  if [[ -n "${AWS_CLI_HISTORY_FILE+set}" ]]; then
    die "CLI 기록이 켜져 있음 — 증거 밖에 원문이 남음(AWS_CLI_HISTORY_FILE" \
      "설정됨)"
  fi
  # 값이 없으면 aws configure get 은 빈 출력 · 종료 1 이다.
  value="$(aws configure get cli_history 2>/dev/null)" || status=$?
  if [[ "${status}" -ne 0 && "${status}" -ne 1 ]]; then
    die "CLI 기록 설정(cli_history)을 읽지 못함"
  fi
  if [[ "${value}" == enabled ]]; then
    die "CLI 기록이 켜져 있음 — 증거 밖에 원문이 남음(프로필의 cli_history)"
  fi
}
