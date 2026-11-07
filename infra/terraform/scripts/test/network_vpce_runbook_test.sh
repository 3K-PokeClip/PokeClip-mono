#!/usr/bin/env bash
#
# prod/network README(인터페이스 엔드포인트의 확인 · 되돌리기 — POK-408)의
# 판정 함수를 가짜 aws 로 시험한다. AWS 에 접속하지 않고 terraform 도 쓰지
# 않는다.
#
# README 의 셸 블록을 그대로 뽑는다(사본 없음 — README 글자를 바꾸면 이 시험이
# 바뀐 글자를 잰다). 블록은 그 안의 한 줄(ANCHORS — 줄 머리가 같아야 함)로
# 찾고, 그 줄을 품은 블록이 README 에 정확히 하나여야 한다. 뽑은 블록을 사례마다
# 하위 셸에서 읽는다. HOME · TMPDIR 은 사례 디렉터리이고, aws 는 셸 함수
# (fake_aws)로 덮는다. 가짜 aws 는 받은 인자를 사례의 calls 에 적고, 응답은
# 사례의 resp/ 에서 고른다.
#
# 응답 파일 이름은 「<서비스>.<동작>」 에 식별 인자를 이어 붙인 열쇠다. 식별
# 인자는 --secret-id · --services · --service-name · --task-definition 의 값과
# --dimensions · --cli-input-json 속의 vpce- · task/ id 다. 같은 열쇠의 n 번째
# 호출은 「<열쇠>.<n>」 이 있으면 그것을, 없으면 「<열쇠>」 를 받는다.
# 「<열쇠>.fail」 이 있으면 종료 254(조회 실패)다. 응답이 없으면 종료 255 로
# 실패하고 그 열쇠를 표준 오류에 적는다.
#
# 사례(판정 줄 = 표준 출력의 마지막 줄 · 종료 코드 0 통과 · 1 멈춤 · 2 판정불가):
#   비밀 관문 — 회전 창 밖 = 진행 · 회전 창 안 = 멈춤 · clip 태스크 정의의 비밀
#     셋이 다름 = 판정불가 · 조회 실패 = 판정불가 · 확인 중 버전 그대로 = 유효 ·
#     버전 바뀜 = 무효.
#   SQS 판정(대리 — 새 태스크의 대상 상태 표본 · poll_failed 시각) — SQS 실패 ·
#     첫 2분 healthy = 실패 · 일시 실패 뒤 갱신 = 통과 · 연속 실패 = 실패 ·
#     창 끝 가까운 실패 = 대기 · 한 번도 healthy 아님 = 실패 · 조회 실패 표본 =
#     판정불가.
#   이름 해석 — 켬 · 끔 판정.
#   삭제 관문 (가) — 참조 행만 셈(자기 인그레스 제외) · 조회 실패와 자기 인그레스
#     없음 = 판정불가.
#   삭제 관문 (나) — 교정 성공 · 양성 대조 없음 = 판정불가 · 교정된 조회 + 점
#     없음 = 통과 · 트래픽 남음 = 멈춤 · 미교정 = 판정불가 · 조회 실패 =
#     판정불가.
#
# 변이: 판정 규칙 한 줄을 뒤집은 README 사본(MUTATIONS)마다 사례 묶음을 다시
# 돌려 하나라도 실패해야 한다. 바꿀 글자가 README 블록에 정확히 한 번 있어야
# 한다.
#
# 임시 디렉터리는 한 뿌리($TMPDIR/network_vpce_runbook_test.*) 아래에 두고, 끝에
# 지운 뒤 남지 않았는지 본다(중단 신호에도 지운다).
#
# 사용: bash infra/terraform/scripts/test/network_vpce_runbook_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly README="${SCRIPT_DIR}/../../prod/network/README.md"

# 뽑을 블록을 찾는 줄(줄 머리). 차례대로 이어 붙여 읽는다.
readonly ANCHORS=(
  'cluster=pokeclip-prod'
  'vpce_task_starts() {'
  'vpce_secret_gate() {'
  'vpce_sqs_verdict() {'
  'vpce_resolve_verdict() {'
  'vpce_ref_gate() {'
  'vpce_metric_calibrate() {'
)

# 변이 — 이름 · 찾을 글자 · 바꿀 글자(같은 차례). 글자는 README 의 셸 · jq ·
# awk 코드 그대로라 펼치지 않는다.
readonly MUTATION_NAMES=(
  'secret: 회전 비교 뒤집기'
  'secret: 시각 오프셋 부호 뒤집기'
  'secret: 버전 대조 뒤집기'
  'sqs: healthy 뒤 갈래 뒤집기'
  'sqs: 마지막 실패 뒤 창 늘리기 뒤집기'
  'sqs: 창 끝 healthy 판정 늦추기'
  'resolve: 켬 · 끔 갈래 뒤집기'
  'ref: 자기 인그레스 양성 대조 빼기'
  'ref: 참조 행 대신 SG 의 모든 행 세기'
  'ref: 참조 수 판정 늦추기'
  'metric: 차원 집합 고르기 빼기'
  'metric: 교정 양성 대조 빼기'
  'metric: 트래픽 판정 뒤집기'
)
# shellcheck disable=SC2016
readonly MUTATION_FROMS=(
  '[ "${changed}" -ge "${start}" ]'
  'then $t - $off else $t + $off end'
  '[ "${now}" != "${version}" ]'
  'else if (seen) {'
  'last_fail + after_fail > need'
  'if (s[n] != "healthy")'
  '(($2 in eni) != (mode == "on"))'
  'any(.GroupId == $sg and .IsEgress == false and .CidrIpv4 != null)'
  'select(.ReferencedGroupInfo.GroupId == $sg)'
  '[ "${refs}" -ne 0 ]'
  '| select(map(.Name) | sort == $want)'
  '(.Datapoints | length) > 0'
  '([.Datapoints[].Sum] | add // 0) > 0'
)
# shellcheck disable=SC2016
readonly MUTATION_TOS=(
  '[ "${changed}" -lt "${start}" ]'
  'then $t + $off else $t - $off end'
  '[ "${now}" = "${version}" ]'
  'else if (!seen) {'
  'last_fail + after_fail < need'
  'if (s[n] == "unhealthy")'
  '(($2 in eni) == (mode == "on"))'
  'true'
  'select(.GroupId == $sg)'
  '[ "${refs}" -gt 1 ]'
  ''
  '(.Datapoints | length) >= 0'
  '([.Datapoints[].Sum] | add // 0) >= 0'
)

# 가짜 계정 ID(12자리 — 0 으로 채운 값이라 실제 계정이 아니다).
FAKE_ACCOUNT="$(printf '%012d' 42)"
readonly FAKE_ACCOUNT
readonly ECS_ARN_HEAD="arn:aws:ecs:ap-northeast-2:${FAKE_ACCOUNT}"
readonly TASKDEF_ARN="${ECS_ARN_HEAD}:task-definition/pokeclip-prod-clip:7"
readonly TASK_ARN_HEAD="${ECS_ARN_HEAD}:task/pokeclip-prod"
SECRET_ARN_HEAD="arn:aws:secretsmanager:ap-northeast-2:${FAKE_ACCOUNT}"
SECRET_ARN_HEAD+=':secret:pokeclip/prod'
readonly SECRET_ARN_HEAD
readonly SERVICE_HEAD='com.amazonaws.ap-northeast-2'
readonly VPCE_SG='sg-0vpce'
readonly ENDPOINT_IDS=(vpce-0a vpce-0b vpce-0c vpce-0d vpce-0e)
readonly ENDPOINT_SERVICES=(ecr.api ecr.dkr logs secretsmanager sqs)

failures=0
passes=0
tmp_root=""
case_dir=""
readme_text=""
status=0
# quiet 이면 사례 결과를 적지 않고 실패만 센다(변이 묶음).
quiet=""
suite_failures=0

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩).
# Globals:
#   tmp_root
#######################################
remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    rm -rf -- "${tmp_root}"
  fi
}

#######################################
# 사례 하나의 실패를 알리고 센다.
# Globals:
#   failures, suite_failures, quiet
# Arguments:
#   실패 설명.
#######################################
fail() {
  suite_failures=$((suite_failures + 1))
  if [[ -n "${quiet}" ]]; then
    return 0
  fi
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

#######################################
# 사례 하나의 통과를 적고 센다.
# Globals:
#   passes, quiet
# Arguments:
#   사례 이름.
#######################################
pass() {
  if [[ -n "${quiet}" ]]; then
    return 0
  fi
  printf 'ok: %s\n' "$1"
  passes=$((passes + 1))
}

#######################################
# README 에서 줄 머리가 같은 줄을 품은 shell 블록 하나의 본문을 낸다.
# Arguments:
#   줄 머리.
# Returns:
#   0 / 1 그 블록이 하나가 아님.
#######################################
readme_block() {
  awk -v anchor="$1" '
    /^```shell$/ { inside = 1; body = ""; hit = 0; next }
    inside && /^```$/ {
      inside = 0
      if (hit) { found++; out = body }
      next
    }
    inside {
      body = body $0 "\n"
      if (index($0, anchor) == 1) { hit = 1 }
    }
    END { if (found != 1) { exit 1 } printf "%s", out }' "${README}"
}

#######################################
# ANCHORS 의 블록을 차례대로 이어 readme_text 에 둔다.
# Globals:
#   readme_text
# Returns:
#   0 / 1 블록 하나라도 못 찾음.
#######################################
load_readme() {
  local anchor block
  readme_text=""
  for anchor in "${ANCHORS[@]}"; do
    if ! block="$(readme_block "${anchor}")"; then
      fail "README 에 「${anchor}」 블록이 하나가 아님"
      return 1
    fi
    readme_text+="${block}"$'\n'
  done
}

#######################################
# 새 사례 디렉터리(resp/ · home/ · tmp/)를 만든다.
# Globals:
#   tmp_root, case_dir
#######################################
new_case() {
  case_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" &&
    mkdir -- "${case_dir}/resp" "${case_dir}/home" "${case_dir}/tmp"
}

#######################################
# 응답 파일을 쓴다(표준 입력 그대로).
# Globals:
#   case_dir
# Arguments:
#   응답 파일 이름.
#######################################
resp() {
  cat >"${case_dir}/resp/$1"
}

#######################################
# 가짜 aws — 인자를 calls 에 적고 resp/ 의 응답을 낸다(머리 주석).
# Globals:
#   case_dir
# Arguments:
#   aws 인자.
#######################################
fake_aws() {
  local key="$1.$2" arg want="" n file id
  printf '%s\n' "$*" >>"${case_dir}/calls"
  for arg in "$@"; do
    if [[ -n "${want}" ]]; then
      if [[ "${want}" == id ]]; then
        key+=".${arg//[^A-Za-z0-9._-]/_}"
      else
        id="$(grep -oE 'vpce-[0-9a-z]+|task/[^/]+/[0-9a-z]+' <<<"${arg}" |
          head -n 1)"
        [[ -z "${id}" ]] || key+=".${id##*/}"
      fi
      want=""
    fi
    case "${arg}" in
      --secret-id | --services | --service-name | --task-definition) want=id ;;
      --dimensions | --cli-input-json) want=embedded ;;
    esac
  done
  if [[ -e "${case_dir}/resp/${key}.fail" ]]; then
    printf 'An error occurred (Throttling) when calling %s\n' "$2" >&2
    return 254
  fi
  printf '%s\n' "${key}" >>"${case_dir}/keys"
  n="$(grep -cxF -- "${key}" "${case_dir}/keys")"
  file="${case_dir}/resp/${key}.${n}"
  [[ -e "${file}" ]] || file="${case_dir}/resp/${key}"
  if [[ ! -e "${file}" ]]; then
    printf '가짜 aws: 응답 없음 %s\n' "${key}" >&2
    return 255
  fi
  cat -- "${file}"
}

#######################################
# 뽑은 README 블록을 하위 셸에서 읽고 함수를 부른다. 결과는 사례의 out · err ·
# status 다. 한 사례 안의 두 번째 호출부터는 첫 호출의 증거 디렉터리를 이어
# 쓴다(README 를 한 셸에서 이어 도는 것과 같게 — 증거 디렉터리 이름이 초 단위
# 시각이라 호출마다 다를 수 있다).
# Globals:
#   case_dir, readme_text, status
# Arguments:
#   부를 함수와 인자.
#######################################
run_readme() {
  status=0
  # 환경 · 변수(vpc_id · aws)는 이 하위 셸 안에서만 바꾸고 eval 한 README
  # 블록이 쓴다(의도).
  # shellcheck disable=SC2030,SC2034,SC2329
  (
    export HOME="${case_dir}/home" TMPDIR="${case_dir}/tmp"
    cd -- "${case_dir}" || exit 99
    eval "${readme_text}" || exit 98
    if [[ -s "${case_dir}/evidence-path" ]]; then
      first="$(cat -- "${case_dir}/evidence-path")"
      [[ "${evidence}" == "${first}" ]] || rmdir -- "${evidence}" || exit 97
      evidence="${first}"
    else
      printf '%s' "${evidence}" >"${case_dir}/evidence-path"
    fi
    vpc_id='vpc-0prod'
    aws() { fake_aws "$@"; }
    "$@"
  ) >"${case_dir}/out" 2>"${case_dir}/err" || status=$?
}

#######################################
# 판정 줄 · 종료 코드 · 품어야 할 줄을 본다.
# Globals:
#   case_dir, status
# Arguments:
#   사례 이름, 기대 마지막 줄 머리, 기대 종료 코드, 품어야 할 줄 머리 ….
#######################################
expect() {
  local name="$1" last="$2" want="$3" line got
  shift 3
  got="$(tail -n 1 -- "${case_dir}/out")"
  if [[ "${got}" != "${last}"* ]]; then
    fail "${name}: 마지막 줄 「${got}」 (기대 「${last}…」) / 오류: $(tr '\n' '|' \
      <"${case_dir}/err")"
    return 1
  fi
  if [[ "${status}" -ne "${want}" ]]; then
    fail "${name}: 종료 코드 ${status} (기대 ${want})"
    return 1
  fi
  for line in "$@"; do
    if ! grep -q -F -- "${line}" "${case_dir}/out"; then
      fail "${name}: 「${line}」 줄이 없음"
      return 1
    fi
  done
  pass "${name}"
}

#######################################
# 사례의 증거 디렉터리를 낸다.
# Globals:
#   case_dir
#######################################
evidence_dir() {
  cat -- "${case_dir}/evidence-path"
}

#######################################
# 비밀 관문의 기본 응답(회전 창 밖)을 둔다 — clip 태스크 정의의 비밀 셋 ·
# 서비스 넷 ACTIVE · render · upload 없음 · 태스크 하나씩 · 비밀 셋.
# Globals:
#   case_dir
#######################################
secret_fixture() {
  local svc
  for svc in auth clip chat-collector chat-detector; do
    resp "ecs.describe-services.pokeclip-prod-${svc}" <<JSON
{"services": [{"serviceName": "pokeclip-prod-${svc}", "status": "ACTIVE",
  "taskDefinition": "${TASKDEF_ARN}"}], "failures": []}
JSON
    resp "ecs.list-tasks.pokeclip-prod-${svc}" <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/${svc//-/}1"]}
JSON
    resp "ecs.describe-tasks.${svc//-/}1" <<JSON
{"tasks": [{"taskArn": "x", "startedAt": "2026-11-05T09:00:00.456000+09:00"}],
 "failures": []}
JSON
  done
  for svc in render upload; do
    resp "ecs.describe-services.pokeclip-prod-${svc}" <<JSON
{"services": [], "failures": [{"arn": "x", "reason": "MISSING"}]}
JSON
  done
  resp ecs.describe-task-definition."${TASKDEF_ARN//[^A-Za-z0-9._-]/_}" <<JSON
{"taskDefinition": {"containerDefinitions": [{"name": "clip", "secrets": [
  {"name": "JWT_SECRET",
   "valueFrom": "${SECRET_ARN_HEAD}/jwt-secret-AbCdEf"},
  {"name": "INTERNAL_API_TOKEN",
   "valueFrom": "${SECRET_ARN_HEAD}/internal-api-token-GhIjKl"},
  {"name": "CLOUDFRONT_PRIVATE_KEY_PEM",
   "valueFrom": "${SECRET_ARN_HEAD}/signing-key-MnOpQr"}]}]}}
JSON
  secret_resp jwt-secret '2026-11-01T10:00:00.123000+09:00' v-jwt-1
  secret_resp internal-api-token '2026-11-01T01:00:00Z' v-token-1
  secret_resp signing-key 1730000000.5 v-sign-1
}

#######################################
# describe-secret 응답 하나를 둔다.
# Arguments:
#   그릇 이름 끝(pokeclip/prod/ 뒤), LastChangedDate(JSON 값 — 문자열이면
#   따옴표 없이), AWSCURRENT 버전 id, 호출 차례(없으면 모든 호출).
#######################################
secret_resp() {
  local changed="$2"
  case "${changed}" in
    *[!0-9.]*) changed="\"${changed}\"" ;;
  esac
  resp "secretsmanager.describe-secret.pokeclip_prod_$1${4:+.$4}" <<JSON
{"Name": "pokeclip/prod/$1", "LastChangedDate": ${changed},
 "VersionIdsToStages": {"$3": ["AWSCURRENT"], "$3-old": ["AWSPREVIOUS"]}}
JSON
}

#######################################
# 비밀 관문 사례.
#######################################
test_secret_gate() {
  local jwt_key='secretsmanager.describe-secret.pokeclip_prod_jwt-secret'
  new_case && secret_fixture
  run_readme vpce_secret_gate
  expect 'secret: 회전 창 밖 = 진행' '비밀 관문: 진행' 0 \
    '건너뜀 pokeclip/prod/internal-api-token render: 서비스 없음' \
    '건너뜀 pokeclip/prod/internal-api-token upload: 서비스 없음'
  if [[ "$(wc -l <"$(evidence_dir)/30-secret-versions.txt")" -ne 3 ]]; then
    fail 'secret: 버전 기록이 그릇 셋이 아님'
  fi
  run_readme vpce_secret_recheck
  expect 'secret: 확인 중 버전 그대로 = 유효' '비밀 버전: 그대로' 0

  new_case && secret_fixture
  secret_resp internal-api-token '2026-11-01T01:00:00Z' v-token-1 1
  secret_resp internal-api-token '2026-11-01T01:00:00Z' v-token-2 2
  run_readme vpce_secret_gate
  run_readme vpce_secret_recheck
  expect 'secret: 확인 중 버전 바뀜 = 무효' '비밀 버전: 무효' 1 \
    '바뀜 pokeclip/prod/internal-api-token'

  # 회전(00:30Z)이 chat-detector 태스크 기동(09:00+09:00 = 00:00Z) 뒤.
  new_case && secret_fixture
  secret_resp internal-api-token '2026-11-05T00:30:00Z' v-token-1
  run_readme vpce_secret_gate
  expect 'secret: 회전 창 안 = 멈춤' '비밀 관문: 멈춤' 1 \
    '멈춤 pokeclip/prod/internal-api-token chat-detector'

  new_case && secret_fixture
  resp ecs.describe-task-definition."${TASKDEF_ARN//[^A-Za-z0-9._-]/_}" <<JSON
{"taskDefinition": {"containerDefinitions": [{"name": "clip", "secrets": [
  {"name": "JWT_SECRET",
   "valueFrom": "${SECRET_ARN_HEAD}/jwt-secret-AbCdEf"}]}]}}
JSON
  run_readme vpce_secret_gate
  expect 'secret: clip 비밀 셋이 다름 = 판정불가' '비밀 관문: 판정불가' 2

  new_case && secret_fixture
  : >"${case_dir}/resp/${jwt_key}.fail"
  run_readme vpce_secret_gate
  expect 'secret: 조회 실패 = 판정불가' '비밀 관문: 판정불가' 2
}

#######################################
# SQS 판정의 관찰 파일을 쓴다 — 기동 1000 초, 30초마다 대상 상태 표본.
# Globals:
#   case_dir
# Arguments:
#   표본 끝 시각, healthy 가 되는 시각, unhealthy 구간 시작, 끝(없으면 0 0),
#   poll_failed 시각 ….
#######################################
sqs_obs() {
  local last="$1" up="$2" down_from="$3" down_to="$4" t state
  shift 4
  : >"${case_dir}/obs.txt"
  for ((t = 1030; t <= last; t += 30)); do
    state=initial
    if ((t >= up)); then
      state=healthy
    fi
    if ((t >= down_from && t <= down_to)); then
      state=unhealthy
    fi
    printf 'health %s %s\n' "${t}" "${state}" >>"${case_dir}/obs.txt"
  done
  for t in "$@"; do
    printf 'poll_failed %s\n' "${t}" >>"${case_dir}/obs.txt"
  done
}

#######################################
# SQS 판정 사례(기동 1000 초 — 창 끝 = 1420, 마지막 실패 뒤 240초).
#######################################
test_sqs_verdict() {
  local fails=() t
  # 수신이 처음부터 실패 — 헬스 유예(starting) 동안 healthy 였다가 DOWN.
  for ((t = 1040; t <= 1440; t += 30)); do
    fails+=("${t}")
  done
  new_case && sqs_obs 1450 1090 1330 9999 "${fails[@]}"
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: SQS 실패 · 첫 2분 healthy = 실패' 'SQS: 실패 — healthy 뒤 unhealthy' 1

  new_case && sqs_obs 1690 1090 0 0 1100
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 일시 실패 뒤 성공 시각 갱신 = 통과' 'SQS: 통과' 0

  new_case && sqs_obs 1690 1090 1360 1390 1200 1220 1240 1260 1280 1300 1330
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 연속 실패 = 실패' 'SQS: 실패 — healthy 뒤 unhealthy' 1

  new_case && sqs_obs 1450 1090 0 0 1400
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 창 끝 가까운 실패 = 대기' 'SQS: 대기 — 표본을 1640 까지' 3

  new_case && sqs_obs 1450 9999 0 0
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 한 번도 healthy 아님 = 실패' 'SQS: 실패 — 창 끝에 healthy 아님' 1

  new_case && sqs_obs 1450 1090 0 0
  printf 'health 1200 error\n' >>"${case_dir}/obs.txt"
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 조회 실패 표본 = 판정불가' 'SQS: 판정불가' 2
}

#######################################
# 이름 해석 판정 사례(상자 출력 · ENI 주소 파일은 사례가 직접 쓴다).
#######################################
test_resolve_verdict() {
  local names=(api.ecr.ap-northeast-2.amazonaws.com
    "${FAKE_ACCOUNT}.dkr.ecr.ap-northeast-2.amazonaws.com"
    logs.ap-northeast-2.amazonaws.com
    secretsmanager.ap-northeast-2.amazonaws.com
    sqs.ap-northeast-2.amazonaws.com)
  local i
  new_case
  printf '192.0.2.%s\n' 11 12 13 14 15 >"${case_dir}/eni.txt"
  for i in 0 1 2 3 4; do
    printf '%s 192.0.2.%s\n' "${names[i]}" "$((11 + i))"
  done >"${case_dir}/on.txt"
  printf 'SSM Success\n' >>"${case_dir}/on.txt"
  for i in 0 1 2 3 4; do
    printf '%s 198.51.100.%s\n' "${names[i]}" "$((1 + i))"
  done >"${case_dir}/off.txt"
  sed '5s/192.0.2.15/198.51.100.9/' "${case_dir}/on.txt" \
    >"${case_dir}/mixed.txt"
  sed '1d' "${case_dir}/on.txt" >"${case_dir}/four.txt"

  run_readme vpce_resolve_verdict on on.txt eni.txt
  expect 'resolve: 켬 · 다섯 모두 ENI = 통과' '이름 해석(on): 통과' 0
  run_readme vpce_resolve_verdict on mixed.txt eni.txt
  expect 'resolve: 켬 · 하나가 공용 = 실패' '이름 해석(on): 실패' 1 \
    '어긋남 sqs.ap-northeast-2.amazonaws.com'
  run_readme vpce_resolve_verdict on four.txt eni.txt
  expect 'resolve: 켬 · 이름 하나 안 풀림 = 실패' '이름 해석(on): 실패' 1
  run_readme vpce_resolve_verdict off off.txt eni.txt
  expect 'resolve: 끔 · 다섯 모두 공용 = 통과' '이름 해석(off): 통과' 0
  run_readme vpce_resolve_verdict off on.txt eni.txt
  expect 'resolve: 끔 · ENI 로 풀림 = 실패' '이름 해석(off): 실패' 1 \
    '어긋남 <계정>.dkr.ecr.ap-northeast-2.amazonaws.com'
}

#######################################
# describe-security-group-rules 응답을 둔다.
# Arguments:
#   rules 배열 안 JSON(쉼표로 이은 객체들).
#######################################
rules_resp() {
  resp ec2.describe-security-group-rules <<JSON
{"SecurityGroupRules": [$1]}
JSON
}

#######################################
# 삭제 관문 (가) 사례.
#######################################
test_ref_gate() {
  local self other render
  self="{\"GroupId\": \"${VPCE_SG}\", \"IsEgress\": false, \"FromPort\": 443,"
  self+=' "CidrIpv4": "192.0.2.0/24"}'
  other='{"GroupId": "sg-0auth", "IsEgress": false, "FromPort": 8082,'
  other+=' "ReferencedGroupInfo": {"GroupId": "sg-0clip"}}'
  render='{"GroupId": "sg-0render", "IsEgress": true, "FromPort": 443,'
  render+=" \"ReferencedGroupInfo\": {\"GroupId\": \"${VPCE_SG}\"}}"

  new_case && rules_resp "${self}, ${other}, ${render}"
  run_readme vpce_ref_gate "${VPCE_SG}"
  expect 'ref: 참조 행 하나 = 멈춤' '참조 관문: 멈춤 — 참조 1건' 1 \
    '참조 sg-0render 이그레스 443'

  new_case && rules_resp "${self}, ${other}"
  run_readme vpce_ref_gate "${VPCE_SG}"
  expect 'ref: 자기 인그레스만 = 통과' '참조 관문: 통과' 0

  new_case && : >"${case_dir}/resp/ec2.describe-security-group-rules.fail"
  run_readme vpce_ref_gate "${VPCE_SG}"
  expect 'ref: 조회 실패 = 판정불가' '참조 관문: 판정불가' 2

  new_case && rules_resp "${other}"
  run_readme vpce_ref_gate "${VPCE_SG}"
  expect 'ref: 자기 인그레스 없음 = 판정불가' '참조 관문: 판정불가' 2
}

#######################################
# 교정에 쓰는 응답(엔드포인트 다섯 · 차원 집합 · 양성 대조 점)을 둔다.
# Globals:
#   case_dir
# Arguments:
#   list-metrics 에서 뺄 엔드포인트 차례(없으면 빼지 않음).
#######################################
metric_fixture() {
  local i id service endpoints="" metrics="" dims subnet
  subnet='{"Name": "Subnet Id", "Value": "subnet-0a"}'
  for i in 0 1 2 3 4; do
    id="${ENDPOINT_IDS[i]}"
    service="${SERVICE_HEAD}.${ENDPOINT_SERVICES[i]}"
    endpoints+="${endpoints:+, }{\"VpcEndpointId\": \"${id}\","
    endpoints+=" \"ServiceName\": \"${service}\"}"
    dims="{\"Name\": \"Endpoint Type\", \"Value\": \"Interface\"},"
    dims+=" {\"Name\": \"Service Name\", \"Value\": \"${service}\"},"
    dims+=" {\"Name\": \"VPC Endpoint Id\", \"Value\": \"${id}\"},"
    dims+=' {"Name": "VPC Id", "Value": "vpc-0prod"}'
    if [[ "${1-}" != "${i}" ]]; then
      metrics+="${metrics:+, }{\"Dimensions\": [${dims}]},"
      metrics+=" {\"Dimensions\": [${dims}, ${subnet}]}"
    fi
    resp "cloudwatch.get-metric-statistics.${ENDPOINT_IDS[i]}.1" <<JSON
{"Label": "BytesProcessed", "Datapoints": [{"Sum": 2048.0, "Unit": "Bytes"}]}
JSON
  done
  resp ec2.describe-vpc-endpoints <<JSON
{"VpcEndpoints": [${endpoints}]}
JSON
  resp cloudwatch.list-metrics <<JSON
{"Metrics": [${metrics}]}
JSON
}

#######################################
# 판정 조회(두 번째 get-metric-statistics) 응답을 엔드포인트 다섯에 둔다.
# Arguments:
#   Datapoints 배열 안 JSON.
#######################################
judge_resp() {
  local id
  for id in "${ENDPOINT_IDS[@]}"; do
    resp "cloudwatch.get-metric-statistics.${id}.2" <<JSON
{"Label": "BytesProcessed", "Datapoints": [$1]}
JSON
  done
}

#######################################
# 삭제 관문 (나) 사례.
#######################################
test_metric_gate() {
  local window=(2026-11-07T00:00:00Z 2026-11-07T01:00:00Z)
  local later=(2026-11-07T02:00:00Z 2026-11-07T03:00:00Z)

  new_case && metric_fixture && judge_resp ''
  run_readme eval "vpce_endpoints && vpce_metric_calibrate ${window[*]}"
  expect 'metric: 교정 성공' '교정: 성공' 0
  if [[ "$(wc -l <"$(evidence_dir)/70-calibration.jsonl")" -ne 5 ]]; then
    fail 'metric: 교정 기록이 다섯 줄이 아님'
  fi
  run_readme vpce_metric_judge "${later[@]}"
  expect 'metric: 교정된 조회 + 점 없음 = 통과' '트래픽 관문: 통과' 0
  # 판정 조회가 교정과 같은 차원 집합(서브넷 차원 없는 넷)을 썼는가.
  if grep 'get-metric-statistics' "${case_dir}/calls" | sed -n '6,10p' |
    grep -q -e 'Subnet Id' -e '--start-time 2026-11-07T00'; then
    fail 'metric: 판정 조회가 교정과 다른 차원 · 창을 씀'
  fi

  new_case && metric_fixture && judge_resp '{"Sum": 0.0, "Unit": "Bytes"}'
  resp cloudwatch.get-metric-statistics.vpce-0e.2 <<JSON
{"Label": "BytesProcessed", "Datapoints": [{"Sum": 0.0}, {"Sum": 512.0}]}
JSON
  run_readme eval "vpce_endpoints && vpce_metric_calibrate ${window[*]}"
  run_readme vpce_metric_judge "${later[@]}"
  expect 'metric: 트래픽 남음 = 멈춤' '트래픽 관문: 멈춤' 1 \
    '트래픽 vpce-0e: 512'

  new_case && metric_fixture
  resp cloudwatch.get-metric-statistics.vpce-0c.1 <<JSON
{"Label": "BytesProcessed", "Datapoints": []}
JSON
  run_readme eval "vpce_endpoints && vpce_metric_calibrate ${window[*]}"
  expect 'metric: 교정 점 없음 = 판정불가' '교정: 판정불가' 2

  new_case && metric_fixture 3
  run_readme eval "vpce_endpoints && vpce_metric_calibrate ${window[*]}"
  expect 'metric: 차원 집합 없음 = 판정불가' '교정: 판정불가' 2

  new_case && metric_fixture && judge_resp ''
  run_readme vpce_metric_judge "${later[@]}"
  expect 'metric: 미교정 = 판정불가' '트래픽 관문: 판정불가' 2

  new_case && metric_fixture && judge_resp ''
  run_readme eval "vpce_endpoints && vpce_metric_calibrate ${window[*]}"
  : >"${case_dir}/resp/cloudwatch.get-metric-statistics.vpce-0b.fail"
  run_readme vpce_metric_judge "${later[@]}"
  expect 'metric: 판정 조회 실패 = 판정불가' '트래픽 관문: 판정불가' 2
}

#######################################
# 사례 묶음 전부를 지금 readme_text 로 돈다.
#######################################
run_suite() {
  test_secret_gate
  test_sqs_verdict
  test_resolve_verdict
  test_ref_gate
  test_metric_gate
}

#######################################
# 변이마다 README 사본으로 사례 묶음을 돌려 실패가 나오는지 본다.
# Globals:
#   readme_text, quiet, suite_failures
#######################################
test_mutations() {
  local base="${readme_text}" i from to replaced count
  for i in "${!MUTATION_NAMES[@]}"; do
    from="${MUTATION_FROMS[i]}"
    to="${MUTATION_TOS[i]}"
    replaced="${base//"${from}"/}"
    count=$(((${#base} - ${#replaced}) / ${#from}))
    if [[ "${count}" -ne 1 ]]; then
      fail "변이 「${MUTATION_NAMES[i]}」: 바꿀 글자가 ${count}번 있음"
      continue
    fi
    # ${base/"${from}"/"${to}"} 는 bash 3.2 에서 바꿀 글자의 따옴표를 그대로
    # 남기는 경우가 있어(변이가 문법 오류로 「잡힌」 것처럼 보임) 앞 · 뒤로 잇는다.
    readme_text="${base%%"${from}"*}${to}${base#*"${from}"}"
    quiet=1
    suite_failures=0
    run_suite
    quiet=""
    if [[ "${suite_failures}" -eq 0 ]]; then
      fail "변이 「${MUTATION_NAMES[i]}」: 사례가 하나도 실패하지 않음"
    else
      pass "변이 「${MUTATION_NAMES[i]}」 잡힘(실패 ${suite_failures}건)"
    fi
  done
  readme_text="${base}"
}

main() {
  if ! command -v jq >/dev/null; then
    printf 'FAIL: jq 가 없다\n' >&2
    exit 1
  fi
  # shellcheck disable=SC2031
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/network_vpce_runbook_test.XXXXXX")" ||
    exit 1
  trap remove_tmp_root EXIT
  trap 'exit 1' INT TERM

  if load_readme; then
    run_suite
    test_mutations
  fi

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ "${failures}" -ne 0 ]]; then
    printf 'network_vpce_runbook_test: %s건 실패(통과 %s건)\n' \
      "${failures}" "${passes}" >&2
    exit 1
  fi
  printf 'network_vpce_runbook_test: 전부 통과(%s건 · 임시 디렉터리 잔여 0)\n' \
    "${passes}"
}

main "$@"
