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
# 「<열쇠>.by-clock」 이 있으면 가짜 시계(사례의 clock — 있으면 date -u +%s 와
# sleep 을 덮음) 이하의 첫 칸을 가진 마지막 줄의 나머지를 낸다. ecs list-tasks
# 가 --started-by 를 다른 필터와 함께 받으면 실제 API 처럼 거부한다(종료 254).
# 「<열쇠>.fail」 이 있으면 종료 254(조회 실패)다. 응답이 없으면 종료 255 로
# 실패하고 그 열쇠를 표준 오류에 적는다.
#
# 사례(판정 줄 = 표준 출력의 마지막 줄 · 종료 코드 0 통과 · 1 멈춤 · 2 판정불가):
#   비밀 관문 — 회전 창 밖 = 진행 · 회전 창 안 = 멈춤 · clip 태스크 정의의 비밀
#     셋이 다름 = 판정불가 · 조회 실패 = 판정불가 · 확인 중 버전 그대로 = 유효 ·
#     버전 바뀜 = 무효.
#   SQS 표본(vpce_sqs_sample 이 가짜 시계로 만든 파일 그대로) — 정상 = 통과 ·
#     실패 뒤 연장 = 대기 뒤 통과 · 연장 뒤 unhealthy = 실패.
#   멈춘 새 태스크 — 그 배포의 것만 셈 · 사유 기록 · 없음 = 0 · 조회 실패와
#     목록 읽기 오류 = 판정불가.
#   배포 대기 — COMPLETED = 0 · FAILED = 1 · 재기동 10분 안에 안 끝남 · 조회
#     실패만 이어짐 = 판정불가(가짜 시계로 재기동 시각부터의 상한 단언).
#   새 태스크 대기 — 기동 = 0 · 멈춘 새 태스크 = 1 · 재기동 4분 안에 기동 없음 =
#     판정불가(가짜 시계로 상한 단언).
#   재기동 시각 기록 없음 — 새 태스크 대기 · 배포 대기 모두 기다리지 않고
#     판정불가.
#   증거 옮기기 — 되돌리기 1단계 (나) · 다시 켜기 3 의 블록이 같고, 두 번 돌리면
#     회차마다 새 디렉터리로 옮김.
#   확인 (ㄷ) 전 절차 — 새 태스크가 끝내 기동하지 않으면 재기동 · SQS · 판정
#     블록을 README 그대로 이어 돌려 재기동 10분 안에 판정불가로 끝남.
#   SQS 묶음 판정 — 새 태스크 0개 = 판정불가 · 하나 통과 = 통과.
#   새 태스크 조회 · 기동 시각 — describe-tasks 의 failures · 반환 수가 요청과
#     다르거나 목록 읽기 오류면 판정불가.
#   사이드카 오류 세기(상자 스크립트를 가짜 journalctl 로) — 0건 = 「0」 종료 0 ·
#     journalctl 실패 = 숫자 아닌 줄 종료 2.
#   보조 jq — (ㄱ) 상태 줄 · plan 요지(create 의 before null) · 배포 요약 ·
#     로그 스트림 신선도(첫 이벤트는 그 태스크의 기동 시각 기준 — 재기동 190초
#     뒤 기동한 정상 태스크 = 통과).
#   정의 순서 — README 셸 블록의 vpce_* 첫 등장이 정의(함수 · 변수)다.
#   SQS 판정(대리 — 새 태스크의 대상 상태 표본 · poll_failed 시각) — SQS 실패 ·
#     첫 2분 healthy = 실패 · 일시 실패 뒤 갱신 = 통과 · 연속 실패 = 실패 ·
#     창 끝 가까운 실패 = 대기 · 기동 15분 뒤까지 미뤄지는 실패 = 판정불가 · 한
#     번도 healthy 아님 = 실패 · 조회 실패 표본 = 판정불가.
#   이름 해석 — 켬 · 끔 판정 · 상자 키는 media_ids 에서 · 상자가 하나가 아님 ·
#     SSM 실패 = 판정불가.
#   rm — README 셸 블록의 변수 rm 경로는 모두 ${X:?} · work 정리 블록 있음.
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
# Shell 가이드의 100줄 권고를 넘지만 셸로 둔다 — 시험 대상이 README 의 셸
# 블록이라 같은 셸에서 eval 해야 하고, 같은 디렉터리 시험(put_secret_test ·
# kvs_put_test)과 같은 틀(fail · pass · 임시 디렉터리 잔여 단언)을 쓴다. 그
# 틀을 공용 함수 파일로 빼면 그 시험들도 함께 바꿔야 해 이 변경의 범위를
# 넘는다.
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
  'vpce_new_tasks() {'
  'vpce_sqs_verdict() {'
  'vpce_stopped_tasks() {'
  'vpce_resolve_verdict() {'
  'vpce_ref_gate() {'
  'vpce_metric_calibrate() {'
  'vpce_wait_rollout() {'
  'vpce_endpoint_states() {'
  'vpce_plan_changes() {'
  'vpce_sidecar_script() {'
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
  'sqs: 표본이 창 끝을 넘기기 전에 멈추기'
  'stopped: 배포 거르기 빼기'
  'describe: 목록 읽기 오류를 0건으로'
  'describe: 반환 수 대조 빼기'
  'rollout: 대기 상한 늘리기'
  'sidecar: 0건 종료 코드 삼키기 빼기'
  'sidecar: journalctl 실패 감지 빼기'
  'resolve: 켬 · 끔 갈래 뒤집기'
  'ref: 자기 인그레스 양성 대조 빼기'
  'ref: 참조 행 대신 SG 의 모든 행 세기'
  'ref: 참조 수 판정 늦추기'
  'metric: 차원 집합 고르기 빼기'
  'metric: 교정 양성 대조 빼기'
  'metric: 트래픽 판정 뒤집기'
  'new-tasks: 대기 상한 늘리기'
  'new-tasks: 멈춘 태스크 갈래 빼기'
  'rollout: 마감을 재기동 시각 대신 지금부터'
  'sqs-tasks: 새 태스크 0개 판정불가 빼기'
  'sqs: 15분 상한 빼기'
  'resolve: SSM 실패 갈래 빼기'
  'resolve: 상자 키 고르기 빼기'
  'log: 새 태스크 0개 판정불가 빼기'
  'log: 첫 이벤트 기준을 재기동 시각으로 되돌리기'
  'restart-at: 기록 없음 갈래 빼기'
)
# shellcheck disable=SC2016
readonly MUTATION_FROMS=(
  '[ "${changed}" -ge "${start}" ]'
  'then $t - $off else $t + $off end'
  '[ "${now}" != "${version}" ]'
  'else if (seen) {'
  'last_fail + after_fail > need'
  'if (s[n] != "healthy")'
  '[ "${now}" -lt "$3" ] || break'
  'select(.startedBy == $d)'
  "length' \"\$1\")\"; then"
  '((.failures // []) | length) == 0 and (.tasks | length) == $n'
  '+ 600))"'
  '| grep -cE "${pattern}" || :'
  'if ! log="$(journalctl'
  '(($2 in eni) != (mode == "on"))'
  'any(.GroupId == $sg and .IsEgress == false and .CidrIpv4 != null)'
  'select(.ReferencedGroupInfo.GroupId == $sg)'
  '[ "${refs}" -ne 0 ]'
  '| select(map(.Name) | sort == $want)'
  '(.Datapoints | length) > 0'
  '([.Datapoints[].Sum] | add // 0) > 0'
  '+ 240))"'
  '[ "${rc}" -eq 1 ]'
  '$((restart + 600))'
  '[ "${total}" -eq 0 ]'
  'if (need > started + cap)'
  '[ "${ssm_rc}" -ne 0 ]'
  "'keys | if length == 1 then .[0] else empty end'"
  'if [ ! -s "${evidence}/42-new-tasks.txt" ]; then'
  '- $started) <= 180'
  "'' | *[!0-9]*) return 2 ;;"
)
# shellcheck disable=SC2016
readonly MUTATION_TOS=(
  '[ "${changed}" -lt "${start}" ]'
  'then $t + $off else $t - $off end'
  '[ "${now}" = "${version}" ]'
  'else if (!seen) {'
  'last_fail + after_fail < need'
  'if (s[n] == "unhealthy")'
  '[ "$((now + 30))" -le "$3" ] || break'
  'select(true)'
  "length' \"\$1\" || echo 0)\"; then"
  'true'
  '+ 6000))"'
  '| grep -cE "${pattern}"'
  'if ! : || log="$(journalctl'
  '(($2 in eni) == (mode == "on"))'
  'true'
  'select(.GroupId == $sg)'
  '[ "${refs}" -gt 1 ]'
  ''
  '(.Datapoints | length) >= 0'
  '([.Datapoints[].Sum] | add // 0) >= 0'
  '+ 2400))"'
  '[ "${rc}" -eq 9 ]'
  '$(($(date -u +%s) + 600))'
  '[ "${total}" -lt 0 ]'
  'if (0)'
  '[ "${ssm_rc}" -gt 99 ]'
  "'keys | .[0]'"
  'if false; then'
  '- $since) <= 180'
  "'' | *[!0-9]*) ;;"
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
# README 에서 줄 머리가 같은 줄(exact 면 그 줄 그대로)을 품은 shell 블록 하나의
# 본문을 낸다.
# Arguments:
#   줄 머리, exact(선택).
# Returns:
#   0 / 1 그 블록이 하나가 아님.
#######################################
readme_block() {
  awk -v anchor="$1" -v exact="${2-}" '
    /^```shell$/ { inside = 1; body = ""; hit = 0; next }
    inside && /^```$/ {
      inside = 0
      if (hit) { found++; out = body }
      next
    }
    inside {
      body = body $0 "\n"
      if (exact ? $0 == anchor : index($0, anchor) == 1) { hit = 1 }
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
  local key="$1.$2" arg want="" n file id now
  printf '%s\n' "$*" >>"${case_dir}/calls"
  local others='service-name|desired-status|launch-type|family'
  others+='|container-instance'
  if [[ "${key}" == ecs.list-tasks && " $* " == *' --started-by '* ]] &&
    grep -qE -- " --(${others}) " <<<" $* "; then
    {
      printf 'An error occurred (InvalidParameterException) when calling the'
      printf ' ListTasks operation: startedBy must be the only filter\n'
    } >&2
    return 254
  fi
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
  if [[ -e "${case_dir}/resp/${key}.by-clock" ]]; then
    now="$(cat -- "${case_dir}/clock")"
    awk -v now="${now}" '$1 <= now { line = $0; sub(/^[^ ]+ /, "", line) }
      END { print line }' "${case_dir}/resp/${key}.by-clock"
    return 0
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
    if [[ -e "${case_dir}/clock" ]]; then
      date() {
        if [[ "$*" == '-u +%s' ]]; then
          cat -- "${case_dir}/clock"
        else
          command date "$@"
        fi
      }
      sleep() {
        printf '%s\n' "$(($(cat -- "${case_dir}/clock") + $1))" \
          >"${case_dir}/clock"
      }
    fi
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
  resp ecs.describe-tasks.clip1 <<JSON
{"tasks": [], "failures": [{"arn": "x", "reason": "MISSING"}]}
JSON
  run_readme vpce_secret_gate
  expect 'secret: 태스크를 못 돌려줌 = 판정불가' '비밀 관문: 판정불가' 2

  new_case && secret_fixture
  printf 'not json\n' | resp ecs.list-tasks.pokeclip-prod-auth
  run_readme vpce_secret_gate
  expect 'secret: 태스크 목록 읽기 오류 = 판정불가' '비밀 관문: 판정불가' 2

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

  new_case && sqs_obs 1750 1090 0 0 1700
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 기동 15분 뒤까지 미뤄지는 실패 = 판정불가' 'SQS: 판정불가' 2

  new_case && sqs_obs 1450 9999 0 0
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 한 번도 healthy 아님 = 실패' 'SQS: 실패 — 창 끝에 healthy 아님' 1

  new_case && sqs_obs 1450 1090 0 0
  printf 'health 1200 error\n' >>"${case_dir}/obs.txt"
  run_readme vpce_sqs_verdict 1000 obs.txt
  expect 'sqs: 조회 실패 표본 = 판정불가' 'SQS: 판정불가' 2
}

#######################################
# 표본 사례의 응답 — 가짜 시계 시작 · 대상 상태 계획(「<시각> <상태>」 줄) ·
# poll_failed 시각(epoch 초 — 없으면 빈 목록).
# Globals:
#   case_dir
# Arguments:
#   시계 시작, 대상 상태 계획(줄바꿈으로 이음), poll_failed 시각 ….
#######################################
sample_fixture() {
  local events="" t
  printf '%s\n' "$1" >"${case_dir}/clock"
  printf '%s\n' "$2" | resp elbv2.describe-target-health.by-clock
  shift 2
  for t in "$@"; do
    events+="${events:+, }{\"timestamp\": $((t * 1000)), \"message\": \"x\"}"
  done
  resp logs.filter-log-events <<JSON
{"events": [${events}]}
JSON
}

#######################################
# vpce_sqs_sample 로 끝 시각까지 표본을 받고 poll_failed 를 붙여 판정한다. 대상
# 그룹 기록을 먼저 둔다. 다시 부르면 README 안내대로 poll_failed 줄을 지운
# 뒤 받는다.
# Arguments:
#   끝 시각.
#######################################
sample_and_judge() {
  run_readme eval "printf '[\"tg-clip\"]' \
      > \"\${evidence}/40-target-groups.json\"
    if [ -e \"\${evidence}/50-sqs-t1.txt\" ]; then
      sed -i.bak '/^poll_failed /d' \"\${evidence}/50-sqs-t1.txt\"
    fi
    vpce_sqs_sample t1 192.0.2.40 $1 &&
      vpce_sqs_failures t1 1000 &&
      vpce_sqs_verdict 1000 \"\${evidence}/50-sqs-t1.txt\""
}

#######################################
# SQS 표본 사례 — 표본 파일을 손으로 쓰지 않고 vpce_sqs_sample 이 가짜 시계로
# 만든 것을 판정한다(기동 1000 · 창 끝 1420 · 시계는 창 끝과 30초 간격이
# 어긋나게 1035 에서 시작).
#######################################
test_sqs_sampled() {
  local last
  new_case && sample_fixture 1035 $'0 initial\n1090 healthy'
  sample_and_judge 1420
  expect 'sqs-sample: 정상 = 통과' 'SQS: 통과' 0
  last="$(awk '$1 == "health" { t = $2 } END { print t }' \
    "$(evidence_dir)/50-sqs-t1.txt")"
  if [[ "${last}" -lt 1420 ]]; then
    fail "sqs-sample: 마지막 표본 ${last} 이 창 끝 1420 앞"
  fi

  new_case && sample_fixture 1035 $'0 initial\n1090 healthy' 1300
  sample_and_judge 1420
  expect 'sqs-sample: 실패 뒤 연장 = 대기' 'SQS: 대기 — 표본을 1540 까지' 3
  sample_and_judge 1540
  expect 'sqs-sample: 연장 뒤 healthy = 통과' 'SQS: 통과 — poll_failed 1건' 0

  new_case &&
    sample_fixture 1035 $'0 initial\n1090 healthy\n1480 unhealthy' 1300
  sample_and_judge 1420
  sample_and_judge 1540
  expect 'sqs-sample: 연장 뒤 unhealthy = 실패' \
    'SQS: 실패 — healthy 뒤 unhealthy' 1
}

#######################################
# 멈춘 새 태스크 사례 — 서비스의 STOPPED 태스크 중 이 배포(startedBy)의 것만
# 세고 사유를 남긴다.
#######################################
test_stopped_tasks() {
  local ours='"startedBy": "ecs-svc/111"' other='"startedBy": "ecs-svc/999"'
  new_case
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/s1", "${TASK_ARN_HEAD}/s2"]}
JSON
  resp ecs.describe-tasks.s1 <<JSON
{"tasks": [{"taskArn": "${TASK_ARN_HEAD}/s1", ${ours},
  "stoppedReason": "CannotPullContainerError: pull access denied"},
 {"taskArn": "${TASK_ARN_HEAD}/s2", ${other},
  "stoppedReason": "Scaling activity initiated by deployment ecs-svc/999"}],
 "failures": []}
JSON
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: 이 배포의 것만 셈 = 멈춤' '멈춘 새 태스크: 1건' 1
  if ! grep -q 'CannotPullContainerError' "$(evidence_dir)/43-stopped.txt" ||
    grep -q 'ecs-svc/999' "$(evidence_dir)/43-stopped.txt"; then
    fail 'stopped: 사유 기록이 이 배포의 태스크만이 아님'
  fi

  new_case
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/s2"]}
JSON
  resp ecs.describe-tasks.s2 <<JSON
{"tasks": [{"taskArn": "${TASK_ARN_HEAD}/s2", ${other},
  "stoppedReason": "Scaling activity"}], "failures": []}
JSON
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: 다른 배포만 = 0' '멈춘 새 태스크: 0건' 0

  new_case
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": []}
JSON
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: 멈춘 태스크 없음 = 0' '멈춘 새 태스크: 0건' 0

  new_case
  : >"${case_dir}/resp/ecs.list-tasks.pokeclip-prod-clip.fail"
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: 조회 실패 = 판정불가' '멈춘 새 태스크: 판정불가' 2

  new_case
  printf 'not json\n' | resp ecs.list-tasks.pokeclip-prod-clip
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: 목록 읽기 오류 = 판정불가' '멈춘 새 태스크: 판정불가' 2

  new_case
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/s1"]}
JSON
  : >"${case_dir}/resp/ecs.describe-tasks.s1.fail"
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: describe 실패 = 판정불가' '멈춘 새 태스크: 판정불가' 2

  new_case
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/s1"]}
JSON
  resp ecs.describe-tasks.s1 <<JSON
{"tasks": [], "failures": [{"arn": "${TASK_ARN_HEAD}/s1", "reason": "MISSING"}]}
JSON
  run_readme eval 'deployment=ecs-svc/111; vpce_stopped_tasks'
  expect 'stopped: describe 가 태스크를 못 돌려줌 = 판정불가' \
    '멈춘 새 태스크: 판정불가' 2
}

#######################################
# 가짜 aws 의 started-by 단독 규칙이 README 의 새 태스크 조회를 막지 않는지
# 본다(started-by 하나만 씀).
#######################################
test_new_tasks_filter() {
  new_case
  resp ecs.list-tasks <<JSON
{"taskArns": []}
JSON
  run_readme eval 'deployment=ecs-svc/111; vpce_new_tasks; echo "새 태스크 조회 $?"'
  expect 'new-tasks: started-by 단독 필터' '새 태스크 조회 0' 0
}

#######################################
# 정의 순서 — README 셸 블록을 문서 순서대로 읽을 때 vpce_* 이름의 첫 등장이
# 그 함수 · 변수의 정의인지 본다(문서 순서대로 붙여 넣으면 「command not
# found」 가 나지 않게).
#######################################
test_defined_before_use() {
  local bad
  if ! bad="$(awk '
      /^[[:space:]]*```shell$/ { inside = 1; next }
      inside && /^[[:space:]]*```$/ { inside = 0; next }
      !inside { next }
      {
        rest = $0
        sub(/^[[:space:]]+/, "", rest)
        line = $0
        while (match(line, /vpce_[a-z_]+/)) {
          name = substr(line, RSTART, RLENGTH)
          line = substr(line, RSTART + RLENGTH)
          if (name in seen) { continue }
          seen[name] = 1
          if (index(rest, name "() {") != 1 && index(rest, name "=") != 1) {
            printf "%s(%d행) ", name, NR
            found = 1
          }
        }
      }
      END { exit found }' "${README}")"; then
    fail "order: 정의보다 먼저 쓰인 이름: ${bad}"
    return 1
  fi
  pass 'order: vpce_* 첫 등장 = 정의'
}

#######################################
# 배포 대기 사례(가짜 시계 2000 에서 시작 — 상한 600초 · 간격 15초).
#######################################
test_wait_rollout() {
  local key='ecs.describe-services.pokeclip-prod-clip' clock
  local head='{"services": [{"deployments": [{"id": "ecs-svc/111",'
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  local call='printf "2000\n" > "${evidence}/40-restart-at.txt"
    deployment=ecs-svc/111; vpce_wait_rollout'

  new_case && printf '2000\n' >"${case_dir}/clock"
  resp "${key}.by-clock" <<EOF
0 ${head} "rolloutState": "IN_PROGRESS"}]}]}
2045 ${head} "rolloutState": "COMPLETED"}]}]}
EOF
  run_readme eval "${call}"
  expect 'rollout: COMPLETED = 0' '배포: COMPLETED' 0

  new_case && printf '2000\n' >"${case_dir}/clock"
  resp "${key}.by-clock" <<EOF
0 ${head} "rolloutState": "FAILED"}]}]}
EOF
  run_readme eval "${call}"
  expect 'rollout: FAILED = 1' '배포: FAILED' 1

  new_case && printf '2000\n' >"${case_dir}/clock"
  resp "${key}.by-clock" <<EOF
0 ${head} "rolloutState": "IN_PROGRESS"}]}]}
EOF
  run_readme eval "${call}"
  expect 'rollout: 10분 안에 안 끝남 = 판정불가' '배포: 판정불가' 2
  clock="$(cat -- "${case_dir}/clock")"
  if [[ "${clock}" -gt 2615 ]]; then
    fail "rollout: 상한 600초를 넘겨 기다림(시계 ${clock})"
  fi

  # 재기동(1800)이 대기 시작(2000)보다 앞 — 마감은 재기동 뒤 600초.
  new_case && printf '2000\n' >"${case_dir}/clock"
  resp "${key}.by-clock" <<EOF
0 ${head} "rolloutState": "IN_PROGRESS"}]}]}
EOF
  run_readme eval "${call/2000/1800}"
  expect 'rollout: 마감 = 재기동 시각 + 10분' '배포: 판정불가' 2
  clock="$(cat -- "${case_dir}/clock")"
  if [[ "${clock}" -gt 2415 ]]; then
    fail "rollout: 재기동 뒤 600초를 넘겨 기다림(시계 ${clock})"
  fi

  new_case && printf '2000\n' >"${case_dir}/clock"
  : >"${case_dir}/resp/${key}.fail"
  run_readme eval "${call}"
  expect 'rollout: 조회 실패만 이어짐 = 판정불가' '배포: 판정불가' 2
}

#######################################
# 새 태스크 조회 사례 — 정상 줄 · 반환 수 어긋남 · 목록 읽기 오류.
#######################################
test_new_tasks() {
  local task='{"taskArn": "x/t1", "startedAt": "2026-11-07T00:00:00Z",'
  task+=' "attachments": [{"details": [{"name": "subnetId", "value": "s"},'
  task+=' {"name": "privateIPv4Address", "value": "192.0.2.40"}]}]}'
  local call='deployment=ecs-svc/111; vpce_new_tasks; echo "새 태스크 조회 $?"'

  new_case
  resp ecs.list-tasks <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/t1"]}
JSON
  resp ecs.describe-tasks.t1 <<JSON
{"tasks": [${task}], "failures": []}
JSON
  run_readme eval "${call}"
  expect 'new-tasks: 태스크 id · 기동 · 주소' '새 태스크 조회 0' 0 \
    't1 1794009600 192.0.2.40'

  new_case
  resp ecs.list-tasks <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/t1", "${TASK_ARN_HEAD}/t2"]}
JSON
  resp ecs.describe-tasks.t1 <<JSON
{"tasks": [${task}], "failures": [{"arn": "x/t2", "reason": "MISSING"}]}
JSON
  run_readme eval "${call}"
  expect 'new-tasks: 반환 수 어긋남 = 판정불가' '새 태스크 조회 2' 0

  new_case
  printf 'not json\n' | resp ecs.list-tasks
  run_readme eval "${call}"
  expect 'new-tasks: 목록 읽기 오류 = 판정불가' '새 태스크 조회 2' 0
}

#######################################
# 새 태스크 대기의 기본 응답 — 재기동 2000 · 가짜 시계 2000 · 새 태스크 t1 은
# 기동 전(PENDING) · 멈춘 태스크 없음.
# Globals:
#   case_dir
#######################################
pending_fixture() {
  printf '2000\n' >"${case_dir}/clock"
  resp ecs.list-tasks <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/t1"]}
JSON
  resp ecs.describe-tasks.t1 <<JSON
{"tasks": [{"taskArn": "x/t1", "lastStatus": "PENDING", "attachments": []}],
 "failures": []}
JSON
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": []}
JSON
}

#######################################
# 새 태스크 대기 사례(상한 = 재기동 뒤 240초 · 간격 15초).
#######################################
test_wait_new_tasks() {
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  local call='printf "2000\n" > "${evidence}/40-restart-at.txt"
    deployment=ecs-svc/111; vpce_wait_new_tasks' clock
  local running='{"tasks": [{"taskArn": "x/t1", "startedAt": 2030,'
  running+=' "attachments": [{"details": [{"name": "privateIPv4Address",'
  running+=' "value": "192.0.2.40"}]}]}], "failures": []}'

  new_case && pending_fixture
  resp ecs.describe-tasks.t1.by-clock <<EOF
0 {"tasks": [{"taskArn": "x/t1", "attachments": []}], "failures": []}
2030 ${running}
EOF
  run_readme eval "${call}"
  expect 'new-tasks: 기동 = 0' '새 태스크: 1개 기동' 0 't1 2030 192.0.2.40'
  if ! grep -qx 't1 2030 192.0.2.40' "$(evidence_dir)/42-new-tasks.txt"; then
    fail 'new-tasks: 42-new-tasks.txt 에 새 태스크 줄이 없음'
  fi

  new_case && pending_fixture
  resp ecs.list-tasks.pokeclip-prod-clip <<JSON
{"taskArns": ["${TASK_ARN_HEAD}/s1"]}
JSON
  resp ecs.describe-tasks.s1 <<JSON
{"tasks": [{"taskArn": "${TASK_ARN_HEAD}/s1", "startedBy": "ecs-svc/111",
  "stoppedReason": "CannotPullContainerError: x"}], "failures": []}
JSON
  run_readme eval "${call}"
  expect 'new-tasks: 멈춘 새 태스크 = 실패' '새 태스크: 실패' 1

  new_case && pending_fixture
  run_readme eval "${call}"
  expect 'new-tasks: 4분 안에 기동 없음 = 판정불가' '새 태스크: 판정불가' 2
  clock="$(cat -- "${case_dir}/clock")"
  if [[ "${clock}" -gt 2255 ]]; then
    fail "new-tasks: 재기동 뒤 240초를 넘겨 기다림(시계 ${clock})"
  fi
  if [[ -s "$(evidence_dir)/42-new-tasks.txt" ]]; then
    fail 'new-tasks: 기동 없음인데 42-new-tasks.txt 가 비지 않음'
  fi
}

#######################################
# 확인 (ㄷ) 전 절차 — 새 태스크가 끝내 기동하지 않을 때 재기동 블록 · SQS 블록 ·
# 판정 블록을 README 그대로 이어 돌려 재기동 뒤 10분 안에 판정불가로 끝나는지
# 본다(가짜 시계 2000 에서 시작 · 배포는 IN_PROGRESS 그대로).
#######################################
test_restart_never_starts() {
  local blocks="" block anchor clock
  local key='ecs.describe-services.pokeclip-prod-clip'
  # shellcheck disable=SC2016 # README 글자 그대로.
  for anchor in 'date -u +%s > "${evidence}/40-restart-at.txt"' \
    '  vpce_sqs_sample "${task}"'; do
    if ! block="$(readme_block "${anchor}")"; then
      fail "procedure: README 에 「${anchor}」 블록이 하나가 아님"
      return 1
    fi
    blocks+="${block}"$'\n'
  done
  if ! block="$(readme_block vpce_wait_rollout exact)"; then
    fail 'procedure: README 에 판정 블록이 하나가 아님'
    return 1
  fi
  blocks+="${block}"

  new_case && pending_fixture
  resp ecs.update-service <<JSON
{"service": {"deployments": [{"id": "ecs-svc/111", "status": "PRIMARY"}]}}
JSON
  printf '["tg-clip"]\n' | resp "${key}.1"
  resp "${key}" <<JSON
{"services": [{"deployments": [{"id": "ecs-svc/111",
  "rolloutState": "IN_PROGRESS", "runningCount": 0, "desiredCount": 1}],
  "events": []}]}
JSON
  run_readme eval "${blocks}"
  expect 'procedure: 새 태스크 미기동 = 재기동 10분 안 판정불가' \
    '로그 스트림: 판정불가' 2 '새 태스크: 판정불가' \
    'SQS 판정: 판정불가 — 새 태스크 0개' '배포: 판정불가'
  clock="$(cat -- "${case_dir}/clock")"
  if [[ "${clock}" -gt 2615 ]]; then
    fail "procedure: 재기동 뒤 600초를 넘겨 끝남(시계 ${clock})"
  fi
}

#######################################
# 재기동 시각 기록(40-restart-at.txt)이 없으면 새 태스크 대기 · 배포 대기가
# 기다리지 않고 그 사유로 판정불가인지 본다(가짜 시계 2000 — 시계가 그대로).
#######################################
test_restart_at_missing() {
  local clock
  new_case && pending_fixture
  run_readme eval 'deployment=ecs-svc/111
    vpce_wait_new_tasks; vpce_wait_rollout'
  expect 'restart-at: 기록 없음 = 대기 없이 판정불가' \
    '배포: 판정불가 — 재기동 시각 기록 없음' 2 \
    '새 태스크: 판정불가 — 재기동 시각 기록 없음'
  clock="$(cat -- "${case_dir}/clock")"
  if [[ "${clock}" -ne 2000 ]]; then
    fail "restart-at: 기록 없음인데 기다림(시계 ${clock})"
  fi
}

#######################################
# 되돌리기 1단계 (나) · 다시 켜기 3 의 증거 옮기기 블록 — 두 곳이 같은 블록인지,
# 그 블록을 두 번 돌리면 회차마다 새 디렉터리로 30–59번 파일만 옮기는지 본다
# (date 는 사례가 정한 회차 이름을 낸다).
#######################################
test_try_move() {
  local block ev
  # shellcheck disable=SC2016 # README 글자 그대로.
  if ! block="$(awk '
      match($0, /^[[:space:]]*```shell$/) {
        inside = 1
        pad = RLENGTH - 8
        body = ""
        next
      }
      inside && /^[[:space:]]*```$/ {
        inside = 0
        if (index(body, "try_dir=") == 1) { n++; got[n] = body }
        next
      }
      inside { body = body substr($0, pad + 1) "\n" }
      END { if (n != 2 || got[1] != got[2]) { exit 1 } printf "%s", got[1] }' \
      "${README}")"; then
    fail 'try: README 에 같은 증거 옮기기 블록이 두 곳(1단계 (나) · 다시 켜기 3)이 아님'
    return 1
  fi
  new_case
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval 'date() { cat -- "${case_dir}/stamp"; }
    printf "T1\n" > "${case_dir}/stamp"
    touch "${evidence}/30-a.txt" "${evidence}/52-b.txt" "${evidence}/70-c.jsonl"
    '"${block}"'
    printf "T2\n" > "${case_dir}/stamp"
    touch "${evidence}/41-d.json"
    '"${block}"'
    echo "옮기기 끝"'
  expect 'try: 옮기기 블록 실행' '옮기기 끝' 0
  ev="$(evidence_dir)"
  if [[ ! -f "${ev}/try-T1/30-a.txt" || ! -f "${ev}/try-T1/52-b.txt" ||
    ! -f "${ev}/try-T2/41-d.json" || ! -f "${ev}/70-c.jsonl" ||
    -e "${ev}/30-a.txt" || -e "${ev}/41-d.json" ]]; then
    fail "try: 회차 디렉터리 옮기기 결과가 다름($(cd -- "${ev}" && find . | sort |
      tr '\n' ' '))"
    return 1
  fi
  pass 'try: 회차마다 새 디렉터리 · 70번대 그대로'
}

#######################################
# SQS 묶음 판정 사례 — 새 태스크 목록(42-new-tasks.txt) 전부를 판정한다.
#######################################
test_sqs_tasks() {
  new_case
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval ': > "${evidence}/42-new-tasks.txt"; vpce_sqs_tasks'
  expect 'sqs-tasks: 새 태스크 0개 = 판정불가' \
    'SQS 판정: 판정불가 — 새 태스크 0개' 2

  new_case && sqs_obs 1450 1090 0 0
  resp logs.filter-log-events <<JSON
{"events": []}
JSON
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval 'printf "t1 1000 192.0.2.40\n" \
      > "${evidence}/42-new-tasks.txt"
    cp obs.txt "${evidence}/50-sqs-t1.txt"; vpce_sqs_tasks'
  expect 'sqs-tasks: 하나 통과 = 통과' 'SQS 판정: 통과 — 새 태스크 1개' 0 \
    'SQS: 통과 — poll_failed 0건'
}

#######################################
# 사이드카 오류 세기 — README 함수가 낸 상자 스크립트를 가짜 journalctl 로
# /bin/sh 에서 돌린다(SSM 의 AWS-RunShellScript 꼴).
# Arguments:
#   사례 이름, 가짜 journalctl 출력(fail 이면 실패), 기대 출력, 기대 종료.
#######################################
sidecar_case() {
  local name="$1" journal="$2" want="$3" code="$4" got rc=0
  new_case && mkdir -- "${case_dir}/bin"
  run_readme eval 'vpce_sidecar_script > sidecar.sh'
  if [[ "${journal}" == fail ]]; then
    printf '#!/bin/sh\necho "Failed to open journal" >&2\nexit 1\n' \
      >"${case_dir}/bin/journalctl"
  else
    printf '%s\n' "${journal}" >"${case_dir}/journal.txt"
    printf '#!/bin/sh\ncat "%s"\n' "${case_dir}/journal.txt" \
      >"${case_dir}/bin/journalctl"
  fi
  chmod +x "${case_dir}/bin/journalctl"
  got="$(env -i "PATH=${case_dir}/bin:/usr/bin:/bin" /bin/sh \
    "${case_dir}/sidecar.sh" 2>/dev/null)" || rc=$?
  if [[ "${got}" != "${want}" || "${rc}" -ne "${code}" ]]; then
    fail "${name}: 출력 「${got}」 종료 ${rc} (기대 「${want}」 종료 ${code})"
    return 1
  fi
  pass "${name}"
}

test_sidecar_errors() {
  sidecar_case 'sidecar: 오류 0건 = 0' $'segment uploaded\nok' 0 0
  sidecar_case 'sidecar: 오류 둘' \
    $'dial tcp 10.0.0.1:443: i/o timeout\nok\nAccessDenied: x' 2 0
  sidecar_case 'sidecar: journalctl 실패 = 판정불가' fail \
    'journalctl 실패 — 판정불가' 2
}

#######################################
# 보조 jq 사례 — (ㄱ) 상태 · plan 요지 · 배포 요약 · 로그 스트림.
#######################################
test_helper_jq() {
  local i list="" plan
  new_case
  for i in 0 1 2 3 4; do
    list+="${list:+, }{\"ServiceName\":"
    list+=" \"${SERVICE_HEAD}.${ENDPOINT_SERVICES[i]}\","
    list+=' "State": "available", "PrivateDnsEnabled": true,'
    list+=" \"NetworkInterfaceIds\": [\"eni-${i}\"]}"
  done
  run_readme eval "printf '%s' '{\"VpcEndpoints\": [${list}]}' \
    > \"\${evidence}/10-endpoints.json\"; vpce_endpoint_states | grep -c ."
  expect 'jq: (ㄱ) 상태 다섯 줄' '5' 0
  run_readme eval 'vpce_endpoint_states'
  expect 'jq: (ㄱ) 상태 줄 꼴' 'sqs available dns=true eni=1' 0 \
    'ecr.api available dns=true eni=1'

  plan='{"resource_changes": ['
  plan+='{"address": "a.ep", "change": {"actions": ["update"],'
  plan+=' "before": {"private_dns_enabled": true, "tags": {"k": "v"}},'
  plan+=' "after": {"private_dns_enabled": false, "tags": {"k": "v"}},'
  plan+=' "after_unknown": {}}},'
  plan+='{"address": "b.sg", "change": {"actions": ["create"],'
  plan+=' "before": null, "after": {"name": "x"},'
  plan+=' "after_unknown": {"id": true, "arn": false}}},'
  plan+='{"address": "c.keep", "change": {"actions": ["no-op"],'
  plan+=' "before": {}, "after": {}, "after_unknown": {}}}]}'
  new_case && printf '%s\n' "${plan}" >"${case_dir}/plan.json"
  run_readme eval 'vpce_plan_changes < plan.json'
  expect 'jq: plan 요지(create 의 before null 포함)' \
    'update a.ep private_dns_enabled' 0 \
    'create b.sg id,name'

  new_case
  resp logs.describe-log-streams <<JSON
{"logStreams": [{"creationTime": 1001500, "firstEventTimestamp": 1002000}]}
JSON
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval 'printf "1000\n" > "${evidence}/40-restart-at.txt"
    printf "t1 1000 192.0.2.40\n" > "${evidence}/42-new-tasks.txt"
    vpce_log_streams'
  expect 'jq: 로그 스트림 신선' 't1 생성 뒤=true 기동 뒤 첫 이벤트 3분 안=true' 0
  resp logs.describe-log-streams <<JSON
{"logStreams": [{"creationTime": 1001500}]}
JSON
  run_readme eval 'vpce_log_streams'
  expect 'jq: 첫 이벤트 없음' 't1 생성 뒤=true 기동 뒤 첫 이벤트 3분 안=false' 0

  # 재기동 190초 뒤 기동한 정상 태스크 — 첫 이벤트는 재기동 뒤 195초지만 기동
  # 뒤 5초다(기준 = 그 태스크의 startedAt).
  new_case
  resp logs.describe-log-streams <<JSON
{"logStreams": [{"creationTime": 1185000, "firstEventTimestamp": 1195000}]}
JSON
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval 'printf "1000\n" > "${evidence}/40-restart-at.txt"
    printf "t1 1190 192.0.2.40\n" > "${evidence}/42-new-tasks.txt"
    vpce_log_streams'
  expect 'jq: 재기동 190초 뒤 기동 = 첫 이벤트는 기동 기준' \
    't1 생성 뒤=true 기동 뒤 첫 이벤트 3분 안=true' 0
  resp logs.describe-log-streams <<JSON
{"logStreams": [{"creationTime": 1185000, "firstEventTimestamp": 1371000}]}
JSON
  run_readme eval 'vpce_log_streams'
  expect 'jq: 기동 181초 뒤 첫 이벤트 = 3분 넘음' \
    't1 생성 뒤=true 기동 뒤 첫 이벤트 3분 안=false' 0

  new_case
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval 'deployment=ecs-svc/111
    printf "1000\n" > "${evidence}/40-restart-at.txt"
    cat > "${evidence}/41-service.json" <<JSON
{"services": [{"deployments": [{"id": "ecs-svc/111",
  "rolloutState": "COMPLETED",
  "runningCount": 1, "desiredCount": 1}, {"id": "ecs-svc/000"}],
  "events": [{"createdAt": 1100.5, "message": "CannotPullContainerError: x"},
   {"createdAt": 900, "message": "ResourceInitializationError: old"},
   {"createdAt": "1970-01-01T09:20:00+09:00", "message": "steady state"}]}]}
JSON
    vpce_rollout_summary'
  expect 'jq: 배포 요약' '오류 이벤트 1' 0 \
    'rollout=COMPLETED running=1 desired=1'
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

  resolve_case 'resolve: 상자 키는 media_ids 에서 = 통과' '{"b": "i-0b"}' ok \
    '이름 해석(on): 통과' 0
  resolve_case 'resolve: SSM 실패 = 판정불가' '{"b": "i-0b"}' ssm-fail \
    '이름 해석(on): 판정불가' 2
  # 둘째 상자 c 는 b 뒤에 정렬된다 — 첫 키를 고르면 응답하는 b 가 뽑힌다.
  resolve_case 'resolve: 상자가 둘 = 판정불가' '{"b": "i-0b", "c": "i-0c"}' ok \
    '이름 해석(on): 판정불가' 2
  resolve_case 'resolve: 계정 조회 실패 = 판정불가' '{"b": "i-0b"}' sts-fail \
    '이름 해석(on): 판정불가' 2
}

#######################################
# vpce_resolve 사례 하나 — 가짜 media_ids · media_ssm(상자 b 에만 on.txt 로 응답,
# ssm-fail 이면 SSM 상태 Failed 로 종료 1). 지금 사례 디렉터리의 on.txt ·
# eni.txt 를 쓴다.
# Globals:
#   case_dir
# Arguments:
#   사례 이름, media_ids 출력, ok|ssm-fail|sts-fail, 기대 마지막 줄 머리, 기대
#   종료.
#######################################
resolve_case() {
  local sts="${case_dir}/resp/sts.get-caller-identity"
  rm -f -- "${sts}.fail"
  printf '%s\n' "${FAKE_ACCOUNT}" >"${sts}"
  if [[ "$3" == sts-fail ]]; then
    : >"${sts}.fail"
  fi
  # shellcheck disable=SC2016 # README 셸에서 펼친다.
  run_readme eval "media_ids() { printf '%s' '$2'; }
    media_ssm() {
      if [ \"\$1\" != b ] || [ '$3' = ssm-fail ]; then
        echo 'SSM Failed'
        return 1
      fi
      cat on.txt
    }"'
    cp eni.txt "${evidence}/12-eni-ips.txt"
    vpce_resolve on resolve-on.txt'
  expect "$1" "$4" "$5"
}

#######################################
# README 셸 블록의 rm 이 변수 경로를 모두 ${X:?} 로 쓰는지, work 정리 블록이
# 둘(확인 끝 · 되돌리기 끝) 이상 있는지 본다.
#######################################
test_rm_guarded() {
  local bad
  bad="$(awk '
      /^[[:space:]]*```shell$/ { inside = 1; next }
      inside && /^[[:space:]]*```$/ { inside = 0; next }
      !inside { next }
      /rm -[a-z]+ (-- )?"[$][{][A-Za-z_]+[}]/ { printf "%d행 ", NR }
      index($0, "rm -r -- \"${work:?}\" && unset work") { guarded++ }
      END { if (guarded < 2) { printf "work 정리 %d곳 ", guarded } }' \
    "${README}")"
  if [[ -n "${bad}" ]]; then
    fail "rm: 가드 없는 변수 rm 경로 또는 정리 블록 부족: ${bad}"
    return 1
  fi
  # shellcheck disable=SC2016 # 글자 그대로.
  pass 'rm: 변수 경로 ${X:?} · work 정리 블록 둘'
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
# Globals:
#   quiet, suite_failures
#######################################
run_suite() {
  local test_name
  for test_name in \
    test_defined_before_use \
    test_secret_gate \
    test_wait_rollout \
    test_new_tasks \
    test_wait_new_tasks \
    test_restart_never_starts \
    test_restart_at_missing \
    test_try_move \
    test_sqs_tasks \
    test_rm_guarded \
    test_sidecar_errors \
    test_helper_jq \
    test_sqs_verdict \
    test_sqs_sampled \
    test_stopped_tasks \
    test_new_tasks_filter \
    test_resolve_verdict \
    test_ref_gate \
    test_metric_gate; do
    # 변이 묶음은 첫 실패에서 멈춘다 — 잡혔는지만 보면 되고 시간이 준다.
    if [[ -n "${quiet}" && "${suite_failures}" -gt 0 ]]; then
      return 0
    fi
    "${test_name}"
  done
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
      pass "변이 「${MUTATION_NAMES[i]}」 잡힘"
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
