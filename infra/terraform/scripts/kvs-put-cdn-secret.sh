#!/usr/bin/env bash
#
# CDN 비밀(MediaMTX hlsCDNSecret 원문)을 Secrets Manager 에서 읽어 CloudFront
# KeyValueStore 의 키 cdn-secret 에 넣는다(설계 5-4 의 9 · 계획 M10 c9). 엣지의
# live-auth 함수가 이 값을 원본 요청의 Authorization: Bearer 로 덮어쓴다.
#
# 값은 aws 출력 → 셸 변수 → 표준 입력(put-key --value file:///dev/stdin)으로만
# 지나간다 — 명령 인자 · 환경 변수 · 임시 파일 · 출력에 두지 않는다. 그래서
# here-doc · here-string(bash 3.2 는 본문을 임시 파일에 씀)과 jq 를 쓰지 않고,
# 값은 셸 내장 printf 의 파이프로만 넘긴다. --cli-input-json file:///dev/stdin
# 은 쓰지 않는다 — aws-cli 가 file:// 값을 두 번 읽어 둘째 읽기가 빈다
# (2026-10-24 aws-cli 2.38.1 실측 — --value file:///dev/stdin 은 한 번 읽어 요청
# 본문까지 감). --debug 도 쓰지 않는다(값을 표준 오류에 찍음).
#
# 사용: kvs-put-cdn-secret.sh --kvs-arn KVS_ARN --secret-id SECRET_ID
#   KVS_ARN    CloudFront KVS 의 ARN(prod/edge 출력 cdn_kvs_arn)
#   SECRET_ID  cdn-secret 비밀의 ARN 또는 이름(pokeclip/prod/cdn-secret)
#   Secrets Manager 리전은 ap-northeast-2, KVS 호출 리전은 us-east-1 고정이다.
#
# 순서(앞 단계가 실패하면 뒤 단계를 부르지 않는다 · 다시 시도하지 않는다):
#   1. KVS 데이터면 호스트 이름 풀이(dig +short <계정>.cloudfront-kvs.global.
#      api.aws). 호스트 꼴과 계정 번호는 SDK 의 KVS 엔드포인트 규칙과 같다 —
#      계정 번호는 KVS ARN 의 계정 칸이다(sts 를 부르지 않는다 · 출력하지
#      않는다). 주소 줄이 없으면 멈춘다(값을 읽기 전).
#   2. 값 읽기(secretsmanager get-secret-value). 빈 값 · None(SecretString 없음)
#      · 한 줄 ASCII 인쇄 문자 밖의 값은 넣지 않는다.
#   3. 현재 ETag(describe-key-value-store).
#   4. 넣기(put-key --if-match ETag).
#
# 출력(값 · 계정 번호 · ARN 은 어떤 경로로도 내보내지 않는다 — aws 원문도):
#   성공      표준 출력 「KVS PUT OK」 한 줄
#   이름 풀이 표준 오류 「KVS HOST UNRESOLVED」 + 사유 한 줄(dig 실패 · KVS
#             호출의 Could not connect). SCP 거부와 섞지 않는다.
#   KVS 거부  표준 오류 「KVS PUT DENIED」 + SCP 멈춤 안내
#   값 거부   표준 오류 「SECRET READ DENIED」 + SCP 멈춤 안내(KVS 호출 0)
#   값 검사   표준 오류 「SECRET VALUE EMPTY」 · 「SECRET VALUE INVALID」
#   그 밖     표준 오류 「KVS PUT FAILED <단계> <코드>」. 단계는 PREREQ(aws ·
#             dig 없음 — 코드 자리에 명령 이름) · SECRET · ETAG · PUT.
#             코드는 AWS 오류 코드, 코드 없는 네트워크 단계 실패(원문에 endpoint
#             URL)는 ENDPOINT, 그 밖은 UNKNOWN, 응답에 ETag 가 없으면 NO-ETAG.
#
# 종료 코드:
#   0 넣음
#   1 그 밖의 실패(값 검사 포함) — 다시 시도하지 말고 kty 에게 보고
#   2 사용법 오류(인자 · KVS ARN 꼴 arn:aws:cloudfront::<12자리>:key-value-store/
#     <id> · 비밀 ID 꼴)
#   3 이름 풀이 실패 · KVS 엔드포인트 연결 실패 — 런북의 우회 절차로
#   4 KVS 호출(ETag 읽기 · PutKey)의 AccessDenied
#   5 Secrets Manager 값 읽기의 AccessDenied

set -uo pipefail
# 물려받은 추적 · allexport 를 값을 읽기 전에 끈다(bash -x · SHELLOPTS) — 켜져
# 있으면 값이 든 줄이 표준 오류에 찍히거나 값이 aws 의 환경으로 간다.
set +o xtrace +o allexport +o verbose

readonly SECRET_REGION='ap-northeast-2'
readonly KVS_REGION='us-east-1'
readonly KVS_KEY='cdn-secret'
readonly KVS_HOST_SUFFIX='cloudfront-kvs.global.api.aws'
readonly EXIT_FAILED=1
readonly EXIT_USAGE=2
readonly EXIT_UNRESOLVED=3
readonly EXIT_KVS_DENIED=4
readonly EXIT_SECRET_DENIED=5
# 계정 칸을 잡는다(BASH_REMATCH[1] — 이름 풀이 호스트의 계정 번호).
KVS_ARN_PATTERN='^arn:aws:cloudfront::([0-9]{12}):key-value-store/'
KVS_ARN_PATTERN+='[A-Za-z0-9-]+$'
readonly KVS_ARN_PATTERN
readonly SECRET_ID_PATTERN='^[A-Za-z0-9/_+=.@:-]+$'
readonly ETAG_PATTERN='^[A-Za-z0-9]+$'
readonly IPV4_PATTERN='^[0-9]{1,3}(\.[0-9]{1,3}){3}$'
readonly IPV6_PATTERN='^[0-9A-Fa-f]*:[0-9A-Fa-f:.]*$'
readonly ERROR_CODE_PATTERN='An error occurred \(([A-Za-z0-9]+)\)'
# 코드 없는 네트워크 단계 실패(Could not connect to · Read timeout on 등)의 원문.
readonly ENDPOINT_PATTERN='endpoint URL'
readonly CONNECT_PATTERN='Could not connect to the endpoint URL'
readonly NOTE_DIG='이름 풀이(dig) 실패 — 값을 넣지 않음 · 런북의 우회 절차로'
readonly NOTE_CONNECT='KVS 엔드포인트 연결 실패(Could not connect) — SCP 거부 아님'
readonly NOTE_DENIED='SCP 멈춤 규칙 — 다시 시도하지 말고 kty 에게 보고'

# 비밀 값. 함수 인자로 넘기지 않고 전역으로만 다룬다. 호출자가 같은 이름을
# export 해 두었으면 대입만으로는 export 속성이 남아 값이 aws 의 환경으로 가므로,
# 먼저 지운다.
unset secret_value
secret_value=""
# 마지막 aws 호출의 결과(aws_call 이 채움).
aws_output=""
aws_status=0

#######################################
# 사용법을 알리고 끝낸다. 인자 값(ARN — 계정 번호를 품음)은 내보내지 않는다.
#######################################
usage() {
  printf '%s\n' "사용: kvs-put-cdn-secret.sh --kvs-arn KVS_ARN" \
    "  --secret-id SECRET_ID(비밀 ARN 또는 이름)" >&2
  exit "${EXIT_USAGE}"
}

#######################################
# 고정 줄들을 표준 오류에 적고 끝낸다.
# Arguments:
#   종료 코드, 줄들.
#######################################
die() {
  local code="$1"
  shift
  printf '%s\n' "$@" >&2
  exit "${code}"
}

#######################################
# aws 원문에서 오류 코드를 고른다(원문은 내보내지 않는다).
# Globals:
#   aws_output
# Outputs:
#   AWS 오류 코드 · ENDPOINT · UNKNOWN.
#######################################
aws_error_code() {
  if [[ "${aws_output}" =~ ${ERROR_CODE_PATTERN} ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  elif [[ "${aws_output}" == *"${ENDPOINT_PATTERN}"* ]]; then
    printf '%s' ENDPOINT
  else
    printf '%s' UNKNOWN
  fi
}

#######################################
# 값을 받지 않는 aws 호출. 표준 출력 · 표준 오류를 함께 aws_output 에 담는다.
# Globals:
#   aws_output, aws_status
# Arguments:
#   aws 인자들.
#######################################
aws_call() {
  aws_output="$(AWS_PAGER="" aws "$@" 2>&1)"
  aws_status=$?
}

#######################################
# KVS 호출의 실패를 갈래에 맞춰 끝낸다: 연결 실패 3 · AccessDenied 4 · 그 밖 1.
# Globals:
#   aws_output
# Arguments:
#   단계 이름(ETAG · PUT).
#######################################
die_kvs_failure() {
  if [[ "${aws_output}" == *"${CONNECT_PATTERN}"* ]]; then
    die "${EXIT_UNRESOLVED}" "KVS HOST UNRESOLVED" "${NOTE_CONNECT}"
  fi
  if [[ "${aws_output}" == *AccessDenied* ]]; then
    die "${EXIT_KVS_DENIED}" "KVS PUT DENIED" "${NOTE_DENIED}"
  fi
  die "${EXIT_FAILED}" "KVS PUT FAILED $1 $(aws_error_code)"
}

#######################################
# KVS 데이터면 호스트 이름이 이 실행 위치에서 풀리는지 본다. dig 출력에 주소
# 줄(IPv4 · IPv6)이 하나라도 있어야 풀린 것이다 — CNAME 줄만 · 빈 출력 ·
# 「;;」 시간 초과 줄은 풀리지 않은 것이다.
# Arguments:
#   계정 번호.
#######################################
check_kvs_host() {
  local output rest line
  output="$(dig +short "$1.${KVS_HOST_SUFFIX}" 2>&1)" ||
    die "${EXIT_UNRESOLVED}" "KVS HOST UNRESOLVED" "${NOTE_DIG}"
  rest="${output}"
  while [[ -n "${rest}" ]]; do
    line="${rest%%$'\n'*}"
    rest="${rest#"${line}"}"
    rest="${rest#$'\n'}"
    if [[ "${line}" =~ ${IPV4_PATTERN} || "${line}" =~ ${IPV6_PATTERN} ]]; then
      return 0
    fi
  done
  die "${EXIT_UNRESOLVED}" "KVS HOST UNRESOLVED" "${NOTE_DIG}"
}

#######################################
# 값을 검사한다: 빈 값 · None(SecretString 없음)은 EMPTY, 한 줄 ASCII 인쇄
# 문자 밖의 바이트(줄바꿈 · 공백 · 제어 문자 · ASCII 밖)는 INVALID — 성공
# 응답에 경고가 섞인 경우도 여기서 걸린다. 걸리면 값 변수를 비우고 끝낸다.
# Globals:
#   secret_value
#######################################
check_secret_value() {
  local LC_ALL=C
  if [[ -z "${secret_value}" || "${secret_value}" == None ]]; then
    secret_value=""
    die "${EXIT_FAILED}" "SECRET VALUE EMPTY"
  fi
  if [[ "${secret_value}" == *[![:graph:]]* ]]; then
    secret_value=""
    die "${EXIT_FAILED}" "SECRET VALUE INVALID"
  fi
}

#######################################
# 값을 읽어 secret_value 에 담는다. 실패하면 값 변수를 비우고 끝낸다(원문에서
# 오류 코드만 고른다).
# Globals:
#   secret_value, aws_output
# Arguments:
#   비밀 ARN 또는 이름.
#######################################
read_secret() {
  local status code
  secret_value="$(AWS_PAGER="" aws secretsmanager get-secret-value \
    --region "${SECRET_REGION}" --no-cli-auto-prompt --secret-id "$1" \
    --query SecretString --output text 2>&1)"
  status=$?
  if ((status != 0)); then
    aws_output="${secret_value}"
    secret_value=""
    if [[ "${aws_output}" == *AccessDenied* ]]; then
      die "${EXIT_SECRET_DENIED}" "SECRET READ DENIED" "${NOTE_DENIED}"
    fi
    code="$(aws_error_code)"
    die "${EXIT_FAILED}" "KVS PUT FAILED SECRET ${code}"
  fi
}

#######################################
# KVS 의 현재 ETag 를 얻는다.
# Arguments:
#   KVS ARN.
# Outputs:
#   ETag.
#######################################
current_etag() {
  aws_call cloudfront-keyvaluestore describe-key-value-store \
    --region "${KVS_REGION}" --no-cli-auto-prompt --kvs-arn "$1" \
    --query ETag --output text
  ((aws_status == 0)) || die_kvs_failure ETAG
  if [[ "${aws_output}" == None || ! "${aws_output}" =~ ${ETAG_PATTERN} ]]; then
    die "${EXIT_FAILED}" "KVS PUT FAILED ETAG NO-ETAG"
  fi
  printf '%s' "${aws_output}"
}

#######################################
# 값을 넣는다. 키 · ETag 는 인자, 값은 표준 입력이다.
# Globals:
#   secret_value, aws_output
# Arguments:
#   KVS ARN, ETag.
#######################################
put_value() {
  local status
  aws_output="$(printf '%s' "${secret_value}" | AWS_PAGER="" aws \
    cloudfront-keyvaluestore put-key --region "${KVS_REGION}" \
    --no-cli-auto-prompt --output json --kvs-arn "$1" --key "${KVS_KEY}" \
    --if-match "$2" --value file:///dev/stdin 2>&1)"
  status=$?
  secret_value=""
  ((status == 0)) || die_kvs_failure PUT
  printf '%s\n' "KVS PUT OK"
}

#######################################
# 인자를 읽어 kvs_arn · secret_id 에 담고, KVS ARN 의 계정 칸을 kvs_account
# 에 담는다. 둘 다 정확히 한 번씩.
# Globals:
#   kvs_arn, secret_id, kvs_account
# Arguments:
#   스크립트 인자들.
#######################################
parse_args() {
  kvs_arn=""
  secret_id=""
  while [[ "$#" -gt 0 ]]; do
    [[ "$#" -ge 2 ]] || usage
    case "$1" in
      --kvs-arn)
        [[ -z "${kvs_arn}" ]] || usage
        kvs_arn="$2"
        ;;
      --secret-id)
        [[ -z "${secret_id}" ]] || usage
        secret_id="$2"
        ;;
      *) usage ;;
    esac
    shift 2
  done
  [[ "${kvs_arn}" =~ ${KVS_ARN_PATTERN} ]] || usage
  kvs_account="${BASH_REMATCH[1]}"
  [[ "${secret_id}" =~ ${SECRET_ID_PATTERN} ]] || usage
}

main() {
  local kvs_arn secret_id kvs_account etag tool
  parse_args "$@"
  for tool in aws dig; do
    command -v "${tool}" >/dev/null 2>&1 ||
      die "${EXIT_FAILED}" "KVS PUT FAILED PREREQ ${tool}"
  done
  check_kvs_host "${kvs_account}"
  read_secret "${secret_id}"
  check_secret_value
  etag="$(current_etag "${kvs_arn}")" || exit
  put_value "${kvs_arn}" "${etag}"
}

main "$@"
