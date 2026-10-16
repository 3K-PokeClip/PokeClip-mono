#!/usr/bin/env bash
#
# 파일 하나의 내용을 prod 엣지 비밀(Secrets Manager)의 새 버전으로 넣는다
# (runbooks/edge-secrets.md). 값은 파일 → 셸 변수 → 표준 입력(요청 JSON)으로만
# 지나간다 — 명령 인자 · 환경 변수 · 임시 파일 · 출력에 두지 않는다. 그래서
# here-doc · here-string(bash 3.2 는 본문을 임시 파일에 씀)과 jq 를 쓰지 않고,
# 요청 JSON 은 셸 내장으로 만들어 printf 파이프로 넘긴다.
#
# 사용: put-secret-from-file.sh <종류> <파일>
#   종류            비밀 이름(SecretId)             파일 내용
#   signing-key     pokeclip/prod/signing-key       PKCS#8 PEM — 그대로 넣음
#   cdn-secret      pokeclip/prod/cdn-secret        한 줄 — 끝 줄바꿈 하나를 뗌
#   test-stream-key pokeclip/prod/test-stream-key   한 줄 — 끝 줄바꿈 하나를 뗌
#   비밀 이름으로 지정한다(ARN 은 계정 ID 를 품으므로 쓰지 않음). 리전은
#   ap-northeast-2 고정이다. 이미 값이 있으면 새 버전이 되고 옛 값은
#   AWSPREVIOUS 로 남는다.
#
# 검사(aws 를 부르기 전 — 하나라도 걸리면 넣지 않는다):
#   모든 종류: 파일 없음 · 일반 파일 아님 · 읽을 수 없음 · 빈 파일 · 공백만 ·
#     NUL 바이트.
#   signing-key: 줄바꿈 밖의 제어 문자 · ASCII 밖 문자, 첫 줄이 PKCS#8 머리
#     줄(BEGIN PRIVATE KEY)이 아님(PKCS#1 RSA · 암호화된 PKCS#8 거부), 끝 줄이
#     PKCS#8 꼬리 줄(END PRIVATE KEY)이 아님.
#   한 줄 비밀: 끝 줄바꿈 하나를 뗀 뒤 남은 줄바꿈 · 공백 · 제어 문자 · ASCII
#     밖 문자.
#
# 출력(값은 어떤 경로로도 내보내지 않는다 — aws 원문도 내보내지 않는다):
#   성공      표준 출력 「SECRET PUT OK <비밀 이름> <VersionId>」 한 줄
#   거부      표준 오류 「SECRET PUT REJECTED: <이유>」
#   접근 거부 표준 오류 「SECRET PUT DENIED」 — 다시 시도하지 않는다(SCP 멈춤
#             규칙: 멈추고 kty 에게 보고)
#   그 밖     표준 오류 「SECRET PUT FAILED <AWS 오류 코드>」(코드를 못 읽으면
#             UNKNOWN). 원문에는 ARN · 계정 ID 가 들어 있어 코드만 적는다.
#
# 종료 코드:
#   0 넣음
#   2 사용법 오류(인자 수 · 모르는 종류)
#   3 입력 거부(위 검사)
#   4 접근 거부(AccessDenied — AccessDeniedException 등 부분 일치 포함)
#   5 그 밖의 실패(AWS 오류 · aws CLI 없음 · 응답에 VersionId 없음)

set -uo pipefail

readonly REGION='ap-northeast-2'
readonly SECRET_PREFIX='pokeclip/prod/'
readonly EXIT_USAGE=2
readonly EXIT_REJECTED=3
readonly EXIT_DENIED=4
readonly EXIT_FAILED=5
# PKCS#8 머리 · 꼬리 줄. 비밀 스캐너가 키로 오인하지 않게 나눠 쓴다
# (PlaybackProperties.java 와 같은 방식).
readonly PKCS8_BEGIN='-----BEGIN ''PRIVATE KEY-----'
readonly PKCS8_END='-----END ''PRIVATE KEY-----'
readonly ERROR_CODE_PATTERN='An error occurred \(([A-Za-z0-9]+)\)'
readonly VERSION_ID_PATTERN='"VersionId": *"([A-Za-z0-9-]+)"'

# 비밀 값과 요청 JSON. 함수 인자로 넘기지 않고 전역으로만 다룬다.
secret_value=""
request=""

#######################################
# 사용법을 알리고 끝낸다.
#######################################
usage() {
  printf '%s\n' "사용: put-secret-from-file.sh" \
    "  <signing-key|cdn-secret|test-stream-key> <파일>" >&2
  exit "${EXIT_USAGE}"
}

#######################################
# 입력을 거부하고 끝낸다. 이유에 값을 넣지 않는다.
# Arguments:
#   이유.
#######################################
reject() {
  printf 'SECRET PUT REJECTED: %s\n' "$*" >&2
  exit "${EXIT_REJECTED}"
}

#######################################
# 파일을 읽어 secret_value 에 그대로 담는다(끝 줄바꿈 보존). NUL 은 셸 변수에
# 담기지 않아 그 뒤가 조용히 잘리므로 거부한다 — read -d '' 는 NUL 을 만나면
# 0, 파일 끝이면 1 을 낸다.
# Globals:
#   secret_value
# Arguments:
#   파일 경로.
#######################################
read_secret_file() {
  local file="$1"
  [[ -e "${file}" ]] || reject "파일이 없음: ${file}"
  [[ -f "${file}" ]] || reject "일반 파일이 아님: ${file}"
  [[ -r "${file}" ]] || reject "읽을 수 없음: ${file}"
  if IFS= read -r -d '' secret_value <"${file}"; then
    reject "NUL 바이트가 있음: ${file}"
  fi
  [[ -n "${secret_value}" ]] || reject "빈 파일: ${file}"
  [[ -n "${secret_value//[[:space:]]/}" ]] || reject "공백만 있음: ${file}"
}

#######################################
# 서명 키(PKCS#8 PEM)를 검사한다. 값은 바꾸지 않는다.
# Globals:
#   secret_value
#######################################
check_signing_key() {
  local LC_ALL=C
  local text first last
  # 바이트 단위로 본다: 줄바꿈 밖의 제어 문자(CR · 탭 포함)와 ASCII 밖 문자.
  if [[ "${secret_value//$'\n'/}" == *[![:print:]]* ]]; then
    reject "서명 키에 제어 문자 · ASCII 밖 문자가 있음(CRLF 포함)"
  fi
  text="${secret_value%$'\n'}"
  first="${text%%$'\n'*}"
  last="${text##*$'\n'}"
  case "${first}" in
    "${PKCS8_BEGIN}") ;;
    *"RSA PRIVATE KEY"*)
      reject "PKCS#1(BEGIN RSA) 거부 — PKCS#8(BEGIN PRIVATE KEY)만"
      ;;
    *"ENCRYPTED PRIVATE KEY"*)
      reject "암호화된 PKCS#8 거부 — 암호 없는 PKCS#8 만"
      ;;
    *) reject "첫 줄이 ${PKCS8_BEGIN} 가 아님" ;;
  esac
  if [[ "${text}" != *$'\n'* || "${last}" != "${PKCS8_END}" ]]; then
    reject "끝 줄이 ${PKCS8_END} 가 아님"
  fi
}

#######################################
# 한 줄 비밀을 검사하고 끝 줄바꿈 하나를 뗀다.
# Globals:
#   secret_value
#######################################
check_one_line() {
  local LC_ALL=C
  secret_value="${secret_value%$'\n'}"
  if [[ "${secret_value}" == *$'\n'* ]]; then
    reject "여러 줄 거부(끝 줄바꿈 하나만 뗌)"
  fi
  if [[ "${secret_value}" == *[[:blank:]]* ]]; then
    reject "공백 · 탭 거부"
  fi
  if [[ "${secret_value}" == *[[:cntrl:]]* ]]; then
    reject "제어 문자 거부(CR 포함)"
  fi
  if [[ "${secret_value}" == *[![:graph:]]* ]]; then
    reject "ASCII 밖 문자 거부"
  fi
}

#######################################
# 요청 JSON 을 만든다. 검사를 지난 값에는 줄바꿈 밖의 제어 문자가 없으므로
# 역슬래시 · 큰따옴표 · 줄바꿈만 이스케이프하면 된다.
# Globals:
#   secret_value, request
# Arguments:
#   비밀 이름.
#######################################
build_request() {
  local escaped="${secret_value}"
  escaped="${escaped//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  escaped="${escaped//$'\n'/\\n}"
  request="{\"SecretId\":\"$1\",\"SecretString\":\"${escaped}\"}"
}

#######################################
# 요청을 aws 에 넘기고 결과 한 줄을 적는다. aws 원문(ARN · 계정 ID · 되울린
# 요청이 들 수 있음)은 내보내지 않는다.
# Globals:
#   request
# Arguments:
#   비밀 이름.
#######################################
put_request() {
  local name="$1" output status code=""
  if ! command -v aws >/dev/null 2>&1; then
    printf '%s\n' "SECRET PUT FAILED UNKNOWN" "aws CLI 가 PATH 에 없음" >&2
    exit "${EXIT_FAILED}"
  fi
  output="$(printf '%s' "${request}" | AWS_PAGER="" aws secretsmanager \
    put-secret-value --region "${REGION}" --output json \
    --cli-input-json file:///dev/stdin 2>&1)"
  status=$?
  if [[ "${output}" =~ ${ERROR_CODE_PATTERN} ]]; then
    code="${BASH_REMATCH[1]}"
  fi
  if [[ "${status}" -eq 0 && "${output}" =~ ${VERSION_ID_PATTERN} ]]; then
    printf 'SECRET PUT OK %s %s\n' "${name}" "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ "${status}" -ne 0 && "${output}" == *AccessDenied* ]]; then
    printf '%s\n' "SECRET PUT DENIED" \
      "SCP 멈춤 규칙 — 다시 시도하지 말고 kty 에게 보고" >&2
    exit "${EXIT_DENIED}"
  fi
  printf 'SECRET PUT FAILED %s\n' "${code:-UNKNOWN}" >&2
  if [[ "${status}" -eq 0 ]]; then
    printf '%s\n' "응답에 VersionId 가 없음 — describe-secret 으로 버전 확인" >&2
  fi
  exit "${EXIT_FAILED}"
}

main() {
  local kind name
  [[ "$#" -eq 2 ]] || usage
  kind="$1"
  case "${kind}" in
    signing-key | cdn-secret | test-stream-key) ;;
    *) usage ;;
  esac
  name="${SECRET_PREFIX}${kind}"
  read_secret_file "$2"
  if [[ "${kind}" == signing-key ]]; then
    check_signing_key
  else
    check_one_line
  fi
  build_request "${name}"
  put_request "${name}"
}

main "$@"
