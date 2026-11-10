#!/usr/bin/env bash
#
# prod/workers README(일꾼 감시의 수신 불능 · 무완료 · 기동 판정 — POK-408)의
# 판정 함수를 가짜 aws 로 시험한다. AWS 에 접속하지 않고 terraform 도 쓰지
# 않는다.
#
# README 의 셸 블록을 그대로 뽑는다(사본 없음 — README 글자를 바꾸면 이 시험이
# 바뀐 글자를 잰다). 블록은 그 안의 한 줄(ANCHORS — 줄 머리가 같아야 함)로
# 찾고, 그 줄을 품은 블록이 README 에 정확히 하나여야 한다. 뽑은 블록을 사례마다
# 하위 셸에서 읽는다. TMPDIR 은 사례 디렉터리이고, aws 는 셸 함수(fake_aws)로
# 덮는다. 가짜 aws 는 받은 인자를 사례의 calls 에 적고, 응답은 사례의 resp/
# 에서 고른다.
#
# 응답 파일 이름은 「<서비스>.<동작>」 에 식별 인자를 이어 붙인 열쇠다. 식별
# 인자는 --log-group-name · --queue-name · --metric-name · --attribute-names 의
# 값, --queue-url 의 끝 조각, --dimensions 의 Value 다. 「<열쇠>.fail」 이 있으면
# 종료 254(조회 실패)다. 응답이 없으면 종료 255 로 실패하고 그 열쇠를 표준
# 오류에 적는다. 가짜 aws 는 --start-time · --filter-pattern 으로 거르지 않는다 —
# 판정 함수가 시각 · 줄 이름을 다시 거르는지도 이 시험이 본다.
#
# 사례(판정 줄 = 표준 출력의 마지막 줄 · 종료 코드 0 통과 · 1 정지 · 2
# 판정불가 · 3 대기). 설계 3-6 의 다섯은 「설계」 를 붙인다.
#   설계 — 정상 6분 렌더 + 뒤 대기 주문 = 수신 · 무완료 모두 통과.
#   설계 — 연결 시간 초과 반복 = 1분 넘으면 수신 정지(50초는 통과).
#   설계 — 빈 큐 정상 + 지표 10분 지연 = 로그 잠정 통과 → 지표 대기 → 통과.
#   설계 — 빈 큐 연결 불능 = 수신 정지 · 로그 판정 실패.
#   설계 — DELAY 로 숨긴 메시지만 = 무완료 통과.
#   수신 — 줄 이름이 다른 실패는 잇지 않음 · 180초 넘는 틈(0 · 190)은 끊김 ·
#     SDK 재시도 상한 꼴의 틈(0 · 126 · 252 · 378 과 0 · 134 · 268)은 정지 ·
#     같은 줄의 시작 · 완료(0 실패 · 10 started · 145 message_done · 160 실패)와
#     upload.begin 은 이어짐을 끊음 · 다른 줄의 완료는 끊지 않음 · 사이 신호
#     없는 고립 실패 0 · 170 은 정지 · 회복한 지난 장애(0–72초 · 지금 3600초)는
#     통과 · 지금 epoch 없음 · 조회 실패 = 판정불가.
#   감시 반복 — 정지면 첫 주기에 1(기다리지 않음) · 30초마다 보고 끝 시각에 0 ·
#     조회 실패 두 번 이어짐 = 판정불가 · 한 번은 다음 주기에 다시 · 끝 시각의
#     조회 실패는 한 주기 더. date · sleep 은 사례의 가짜 시계다.
#   무완료 — 20분 무완료 = 정지 · 창 밖 완료는 세지 않음 · 기동 20분 전 = 대기 ·
#     나이 20분 이하 = 통과 · 나이 지표 없음 · 깊이 조회 실패 = 판정불가.
#   로그 판정 — 깊이 > 0 인데 시작 · 완료 줄 없음 = 실패 · 3분 전(150초) =
#     대기 · 시작 줄(render.started · 진행 중 업로드의 upload.begin)로 통과.
#   지표 판정 — 기동 앞 분의 점은 세지 않음 · 20분 넘어 점 없음 = 판정불가 ·
#     조회 실패 = 판정불가.
#   기동 시각 — 첫 worker_started 의 초 · 없음 = 판정불가.
#   블록 글자 — 줄 머리나 공백 뒤의 `#` 가 0개(부르지 않는 갈래의 주석까지).
#   대화형 zsh — 블록을 `zsh -f -i -s` 에 표준 입력으로 넣은 것(제어 터미널
#     없음 · 새 PTY 를 제어 터미널로 준 것 둘)과 PTY 의 bracketed
#     paste(python3)로 블록마다 붙여 넣은 것, 셋에서 판정 함수 아홉이 모두
#     whence -w 로 function 이고, 정의하는 동안 가짜 aws 가 한 번도 불리지
#     않고, 함수 아홉을 차례로 한 번씩 부른 결과(출력 · 오류 · 종료 코드)가
#     같은 입력의 bash 와 바이트 단위로 같고 `command not found` 가 없음. 음성
#     대조로 수신 판정 본문 첫 줄에 `# …` 를 넣으면 이 검사가 잡는지 본다.
#     -s 는 제어 터미널이 있어도 명령을 표준 입력에서 읽게 한다 — 없으면 zsh 가
#     터미널에서 읽어, 사람이 터미널에서 시험을 돌릴 때 프롬프트를 띄운 채
#     멈춘다(터미널을 준 사례가 20초 상한으로 잡는다). zsh(PTY 둘은 python3
#     도)가 없으면 skip 으로 센다. 대화형 zsh 의 기본값은 interactivecomments
#     꺼짐 · 이력 확장 켜짐이다. 그래서 `<…>` · 글자가 붙은 `!` 처럼 정의를
#     깨는 글자는 정의 검사가, 정의는 되지만 부를 때 `command not found: #` 가
#     되는 본문 주석은 부르는 검사가 잡는다. 부르는 검사는 실제로 탄 갈래만
#     보므로, 타지 않은 갈래의 `#` 는 블록 글자 사례가 맡는다. bash eval 과
#     `zsh -n` 은 셋 다 못 잡는다.
#
# 변이: 판정 규칙 한 줄을 뒤집은 README 사본(MUTATIONS)마다 사례 묶음을 다시
# 돌려 하나라도 실패해야 한다. 바꿀 글자가 README 블록에 정확히 한 번 있어야
# 한다. 대화형 zsh 사례는 변이 묶음에 넣지 않는다(한 번만 돈다).
#
# 임시 디렉터리는 한 뿌리($TMPDIR/workers_watch_runbook_test.*) 아래에 두고, 끝에
# 지운 뒤 남지 않았는지 본다(중단 신호에도 지운다). 끝에 통과와 skip 건수의
# 합이 사례와 변이를 더한 수(EXPECTED_CASES)와 같은지 본다. 틀은 같은 디렉터리의
# network_vpce_runbook_test 와 같다.
#
# 사용: bash infra/terraform/scripts/test/workers_watch_runbook_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly README="${SCRIPT_DIR}/../../prod/workers/README.md"

# 뽑을 블록을 찾는 줄(줄 머리). 차례대로 이어 붙여 읽는다.
readonly ANCHORS=(
  'region=ap-northeast-2'
  'workers_events() {'
  'workers_receive_verdict() {'
  'workers_receive_watch() {'
  'workers_nodone_verdict() {'
  'workers_start_log_verdict() {'
  'workers_empty_receive_verdict() {'
)

# 변이 — 이름 · 찾을 글자 · 바꿀 글자(같은 차례). 글자는 README 의 셸 · jq ·
# awk 코드 그대로라 펼치지 않는다.
readonly MUTATION_NAMES=(
  'receive: 이어짐 판정 뒤집기'
  'receive: 줄 이름별로 나누기 빼기'
  'receive: 틈 판정 빼기'
  'receive: 틈 상한을 120초로 되돌리기'
  'events: 시작 시각 거르기 빼기'
  'nodone: 보이는 깊이 대신 숨긴 깊이'
  'nodone: 기동 뒤 20분 대기 빼기'
  'nodone: 나이 판정 뒤집기'
  'start-log: 깊이 > 0 의 시작 줄 확인 빼기'
  'empty: 기동 앞 분 거르기 빼기'
  'empty: 20분 상한 빼기'
  'receive: 같은 줄의 정상 신호로 끊기 빼기'
  'receive: 지금 이어지는 중인지(최근성) 빼기'
  'start-log: upload.begin 을 진행 신호에서 빼기'
  'watch: 판정불가 한 번에 멈추기'
  'watch: 주기 30초를 5분으로'
  'watch: 끝 시각의 조회 실패에도 끝내기'
)
# shellcheck disable=SC2016
readonly MUTATION_FROMS=(
  'last[name] - first[name] > limit'
  'name = substr($0, RSTART, dot - 1)'
  '$1 - last[name] <= gap'
  '-v gap=180 '
  'select(.timestamp >= $from)'
  '--attribute-names ApproximateNumberOfMessages '
  '[ "$(($5 - $4))" -lt 1200 ]'
  '[ "${age}" -le 1200 ]'
  '[ "${depth}" -gt 0 ] && [ "$(grep -c . "${work}/progress-$2.txt")" -eq 0 ]'
  'fromdateiso8601) >= $from'
  '[ "$(($3 - $2))" -lt 1200 ]'
  'delete last[name]'
  'now - last[name] <= gap'
  '"$2\\.(started|begin|message_done) "'
  '[ "${misses}" -ge 2 ]'
  'sleep 30'
  '[ "${misses}" -eq 0 ] && [ "${now}" -ge "$3" ]'
)
# shellcheck disable=SC2016
readonly MUTATION_TOS=(
  'last[name] - first[name] < limit'
  'name = "all"'
  '1'
  '-v gap=120 '
  'select(true)'
  '--attribute-names ApproximateNumberOfMessagesNotVisible '
  'false'
  '[ "${age}" -gt 1200 ]'
  'false'
  'fromdateiso8601) >= 0'
  'true'
  'n = n'
  '1'
  '"$2\\.(started|message_done) "'
  '[ "${misses}" -ge 1 ]'
  'sleep 300'
  '[ "${now}" -ge "$3" ]'
)

# 사례 시각의 기준(2026-11-10T00:00:00Z) — 사례는 여기서 몇 초 뒤로 적는다.
readonly T0=1794268800
# 가짜 계정 ID(12자리 — 0 으로 채운 값이라 실제 계정이 아니다).
FAKE_ACCOUNT="$(printf '%012d' 42)"
readonly FAKE_ACCOUNT
# 사례 50(대화형 zsh 넷 · 블록 글자 하나 포함) + 변이 17. 사례나 변이를
# 더하거나 빼면 함께 고친다.
readonly EXPECTED_CASES=67
# 블록을 표준 입력으로 넣는 대화형 zsh 의 옵션(터미널 없음 · 있음 두 사례가
# 같이 쓴다). -s 가 없으면 제어 터미널이 있을 때 zsh 가 표준 입력 대신
# 터미널에서 읽어 멈춘다.
readonly ZSH_STDIN_FLAGS=(-f -i -s)
# 대화형 zsh 사례가 정의를 확인하는 판정 함수(README 블록이 정의하는 전부).
readonly ZSH_FUNCTIONS=(
  workers_iso
  workers_events
  workers_started_at
  workers_queue_depth
  workers_receive_verdict
  workers_receive_watch
  workers_nodone_verdict
  workers_start_log_verdict
  workers_empty_receive_verdict
)

failures=0
passes=0
skips=0
tmp_root=""
case_dir=""
readme_text=""
# README 블록 하나씩(ANCHORS 차례) — 대화형 zsh 의 블록별 붙여 넣기에 쓴다.
readme_blocks=()
# 대화형 zsh 사례에서 zsh 를 띄울 환경(env -i …) — new_zsh_case 가 둔다.
zsh_env=()
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
    rm -rf -- "${tmp_root:?}"
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
# 사례 하나를 건너뛰었다고 적고 센다.
# Globals:
#   skips
# Arguments:
#   사례 이름(건너뛴 이유 포함).
#######################################
skip() {
  printf 'skip: %s\n' "$1"
  skips=$((skips + 1))
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
# ANCHORS 의 블록을 차례대로 이어 readme_text 에 두고, 하나씩 readme_blocks
# 에도 둔다.
# Globals:
#   readme_text, readme_blocks
# Returns:
#   0 / 1 블록 하나라도 못 찾음.
#######################################
load_readme() {
  local anchor block
  readme_text=""
  readme_blocks=()
  for anchor in "${ANCHORS[@]}"; do
    if ! block="$(readme_block "${anchor}")"; then
      fail "README 에 「${anchor}」 블록이 하나가 아님"
      return 1
    fi
    readme_text+="${block}"$'\n'
    readme_blocks+=("${block}"$'\n')
  done
}

#######################################
# 새 사례 디렉터리(resp/ · tmp/)를 만든다.
# Globals:
#   tmp_root, case_dir
#######################################
new_case() {
  case_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" &&
    mkdir -- "${case_dir}/resp" "${case_dir}/tmp"
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
  local key="$1.$2" arg want="" value
  printf '%s\n' "$*" >>"${case_dir}/calls"
  for arg in "$@"; do
    if [[ -n "${want}" ]]; then
      case "${want}" in
        url) value="${arg##*/}" ;;
        dims) value="${arg##*Value=}" ;;
        *) value="${arg}" ;;
      esac
      key+=".${value//[^A-Za-z0-9._-]/_}"
      want=""
    fi
    case "${arg}" in
      --log-group-name | --queue-name | --metric-name | --attribute-names)
        want=plain
        ;;
      --queue-url) want=url ;;
      --dimensions) want=dims ;;
    esac
  done
  if [[ -e "${case_dir}/resp/${key}.fail" ]]; then
    printf 'An error occurred (Throttling) when calling %s\n' "$2" >&2
    return 254
  fi
  if [[ ! -e "${case_dir}/resp/${key}" ]]; then
    printf '가짜 aws: 응답 없음 %s\n' "${key}" >&2
    return 255
  fi
  cat -- "${case_dir}/resp/${key}"
}

#######################################
# 가짜 date — `date -u +%s` 에만 사례의 시계(clock)를 낸다.
# Globals:
#   case_dir
# Arguments:
#   date 인자.
#######################################
fake_date() {
  if [[ "$*" != "-u +%s" || ! -e "${case_dir}/clock" ]]; then
    printf '가짜 date: 모르는 호출 %s\n' "$*" >&2
    return 255
  fi
  cat -- "${case_dir}/clock"
}

#######################################
# 가짜 sleep — 기다리지 않고 시계를 그만큼 옮기며, 초를 sleeps 에 적는다.
# heal 이 있으면 조회 실패 응답(resp/*.fail)을 지운다(다음 주기에 풀린 실패).
# Globals:
#   case_dir
# Arguments:
#   초.
#######################################
fake_sleep() {
  printf '%s\n' "$1" >>"${case_dir}/sleeps"
  printf '%s\n' "$(($(cat -- "${case_dir}/clock") + $1))" >"${case_dir}/clock"
  if [[ -e "${case_dir}/heal" ]]; then
    rm -f -- "${case_dir}"/resp/*.fail
  fi
}

#######################################
# 뽑은 README 블록을 하위 셸에서 읽고 함수를 부른다. 결과는 사례의 out · err ·
# status 다.
# Globals:
#   case_dir, readme_text, status
# Arguments:
#   부를 함수와 인자.
#######################################
run_readme() {
  status=0
  # 환경 · aws 는 이 하위 셸 안에서만 바꾸고 eval 한 README 블록이 쓴다(의도).
  # shellcheck disable=SC2030,SC2329
  (
    export TMPDIR="${case_dir}/tmp"
    cd -- "${case_dir}" || exit 99
    eval "${readme_text}" || exit 98
    aws() { fake_aws "$@"; }
    date() { fake_date "$@"; }
    sleep() { fake_sleep "$@"; }
    "$@"
  ) >"${case_dir}/out" 2>"${case_dir}/err" || status=$?
}

#######################################
# 판정 줄 · 종료 코드를 본다.
# Globals:
#   case_dir, status
# Arguments:
#   사례 이름, 기대 마지막 줄 머리, 기대 종료 코드.
#######################################
expect() {
  local name="$1" last="$2" want="$3" got
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
  pass "${name}"
}

#######################################
# 로그 사건 응답(filter-log-events)을 쓴다. 줄마다 「T0 뒤 초 · 메시지」 다.
# 메시지는 render 이미지의 logback 줄 꼴(머리 · 스레드 · 로거 뒤 이벤트)이다.
# Globals:
#   case_dir
# Arguments:
#   로그 그룹, 「초 메시지」 줄 ….
#######################################
log_resp() {
  local group="$1" line
  shift
  {
    printf '{"events": ['
    local sep=""
    for line in "$@"; do
      printf '%s{"timestamp": %s, "message": "2026-11-10 WARN 1 --- [q] %s"}' \
        "${sep}" "$(((T0 + ${line%% *}) * 1000))" "${line#* }"
      sep=", "
    done
    printf ']}\n'
  } | resp "logs.filter-log-events.${group}"
}

#######################################
# 큐 응답(get-queue-url · 보이는 깊이 · 숨긴 깊이)을 쓴다.
# Arguments:
#   큐 이름, 보이는 깊이, 숨긴 깊이.
#######################################
queue_resp() {
  printf 'https://sqs.ap-northeast-2.amazonaws.com/%s/%s\n' "${FAKE_ACCOUNT}" \
    "$1" |
    resp "sqs.get-queue-url.$1"
  printf '%s\n' "$2" |
    resp "sqs.get-queue-attributes.$1.ApproximateNumberOfMessages"
  printf '%s\n' "$3" |
    resp "sqs.get-queue-attributes.$1.ApproximateNumberOfMessagesNotVisible"
}

#######################################
# 지표 응답(get-metric-statistics)을 쓴다. 줄마다 「T0 뒤 초 · 값」 이다.
# Arguments:
#   지표 이름, 큐 이름, 통계(Sum · Maximum), 「초 값」 줄 ….
#######################################
metric_resp() {
  local metric="$1" queue="$2" stat="$3" line sep=""
  shift 3
  {
    printf '{"Label": "%s", "Datapoints": [' "${metric}"
    for line in "$@"; do
      printf '%s{"Timestamp": "%s", "%s": %s}' "${sep}" \
        "$(jq -rn --argjson t "$((T0 + ${line%% *}))" \
          '$t | todate | sub("Z$"; "+00:00")')" "${stat}" "${line#* }"
      sep=", "
    done
    printf ']}\n'
  } | resp "cloudwatch.get-metric-statistics.${metric}.${queue}"
}

readonly G=pokeclip-prod-render
readonly RQ=pokeclip-prod-jobs-render
readonly TQ=pokeclip-prod-jobs-thumbnail
readonly UG=pokeclip-prod-upload
readonly UQ=pokeclip-prod-jobs-upload

#######################################
# 연결 시간 초과 꼴의 receive_failed 줄을 12초마다 낸다.
# Arguments:
#   줄 이름, 첫 초, 끝 초.
#######################################
timeouts() {
  local t
  for ((t = $2; t <= $3; t += 12)); do
    printf '%s %s.receive_failed err=Unable to execute HTTP request: ' "${t}" "$1"
    printf 'Connect timed out\n'
  done
}

#######################################
# 설계 3-6 의 다섯.
#######################################
test_design_cases() {
  local lines=()
  # 정상 6분 렌더: 기동 0 · 시작 300 · 완료 660 · 뒤 대기 주문(깊이 1 · 나이
  # 25분). 지금 = 기동 30분 뒤.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '300 render.started jobId=j1 clipId=c1 attempt=1' \
    '660 render.message_done messageId=m1 receiveCount=1 disposition=DELETE' &&
    queue_resp "${RQ}" 1 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 1500'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 1800))"
  expect '설계: 정상 6분 렌더 = 수신 통과' '수신: 통과' 0
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect '설계: 정상 6분 렌더 + 대기 주문 = 무완료 통과' '무완료 render: 통과' 0

  # 연결 시간 초과 반복: 12초마다 72초 동안.
  new_case
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect '설계: 연결 시간 초과 72초 = 수신 정지' '수신: 정지' 1

  new_case
  lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 48)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect '설계: 연결 시간 초과 48초 = 수신 통과' '수신: 통과' 0

  # 빈 큐 정상 + 지표 10분 지연: 3분 로그 = 잠정 통과, 5분 = 대기(점 없음),
  # 11분 = 통과(기동 뒤 분의 점).
  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 0 0 &&
    metric_resp NumberOfEmptyReceives "${RQ}" Sum
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect '설계: 빈 큐 정상 = 로그 잠정 통과' '로그 render: 잠정 통과' 0
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 300))"
  expect '설계: 빈 큐 + 지표 지연 5분 = 대기' '지표 '"${RQ}"': 대기' 3
  metric_resp NumberOfEmptyReceives "${RQ}" Sum '60 3' '120 3'
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 660))"
  expect '설계: 빈 큐 + 지표 10분 뒤 도착 = 통과' '지표 '"${RQ}"': 통과' 0

  # 빈 큐 연결 불능.
  new_case
  lines=('0 render.worker_started queue=x')
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 5 77)
  log_resp "${G}" "${lines[@]}"
  queue_resp "${RQ}" 0 0
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 180))"
  expect '설계: 빈 큐 연결 불능 = 수신 정지' '수신: 정지' 1
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect '설계: 빈 큐 연결 불능 = 로그 판정 실패' '로그 render: 실패' 1

  # DELAY 로 숨긴 메시지만: 보이는 0 · 숨긴 3 · 나이 40분 · 완료 0.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 0 3 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 2400'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect '설계: DELAY 숨김만 = 무완료 통과' '무완료 render: 통과' 0
}

#######################################
# 수신 불능 판정의 경계.
#######################################
test_receive() {
  local lines=() line
  # 렌더 줄 0 초 · 사진 줄 70 초 — 줄마다 한 번뿐이라 잇지 않는다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '70 thumbnail.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect 'receive: 다른 줄 이름은 잇지 않음' '수신: 통과' 0

  # 같은 줄이지만 틈이 190초(사이에 성공한 수신이 있었다고 본다).
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '190 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 200))"
  expect 'receive: 180초 넘는 틈은 끊김' '수신: 통과' 0

  # 틈 100초도 이어진 실패다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '100 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 110))"
  expect 'receive: 100초 틈 두 번 = 정지' '수신: 정지' 1

  # 읽기 시간 초과 꼴의 실측 간격(125.3초 — SDK LEGACY 시도 4회 · 소켓 30초 ·
  # 재시도 대기 · 루프 5초 쉼)으로 이어진 실패.
  new_case && log_resp "${G}" '0 render.receive_failed err=Read timed out' \
    '126 render.receive_failed err=Read timed out' \
    '252 render.receive_failed err=Read timed out' \
    '378 render.receive_failed err=Read timed out'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 390))"
  expect 'receive: 126초 간격(실측) 연속 = 정지' '수신: 정지' 1

  # 같은 꼴의 최악 간격(약 134초).
  new_case && log_resp "${G}" '0 render.receive_failed err=Read timed out' \
    '134 render.receive_failed err=Read timed out' \
    '268 render.receive_failed err=Read timed out'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 280))"
  expect 'receive: 134초 간격(상한) 연속 = 정지' '수신: 정지' 1

  # 실패 사이에 같은 줄의 시작 · 완료가 있으면 받기가 그 사이에 됐다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '10 render.started jobId=j1 clipId=c1 attempt=1' \
    '145 render.message_done messageId=m1 receiveCount=1 disposition=DELETE' \
    '160 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 170))"
  expect 'receive: 사이의 시작 · 완료 줄은 이어짐을 끊음' '수신: 통과' 0

  # 사이 신호 없는 두 실패(틈 170초 — SDK 최악 134초와 180초 사이)는 잇는다.
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '170 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 175))"
  expect 'receive: 사이 신호 없는 고립 실패 0 · 170초 = 정지' '수신: 정지' 1

  # 사진 줄의 완료는 렌더 줄의 이어짐을 끊지 않는다(큐마다 스레드가 다름).
  new_case && log_resp "${G}" '0 render.receive_failed err=x' \
    '30 thumbnail.message_done messageId=m1 receiveCount=1 disposition=DELETE' \
    '72 render.receive_failed err=x'
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 80))"
  expect 'receive: 다른 줄의 완료는 끊지 않음' '수신: 정지' 1

  # 업로드 줄은 upload.begin(유튜브로 보내기 시작)도 받기가 됐다는 신호다.
  new_case && log_resp "${UG}" '0 upload.receive_failed err=x' \
    '20 upload.begin uploadId=1 bytes=10 resuming=false tokenValid=true' \
    '100 upload.receive_failed err=x'
  run_readme workers_receive_verdict "${UG}" "${T0}" "$((T0 + 110))"
  expect 'receive: upload.begin 도 끊음' '수신: 통과' 0

  # 지난 장애(0–72초)는 회복된 뒤(지금 3600초) 정지가 아니다.
  new_case
  lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 3600))"
  expect 'receive: 회복한 지난 장애(마지막 실패 3528초 전) = 통과' '수신: 통과' 0

  # 지금 epoch 를 빠뜨리면(옛 두 인자 꼴) 늘 통과가 되지 않게 판정불가다.
  new_case
  lines=()
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_verdict "${G}" "${T0}"
  expect 'receive: 지금 epoch 없음 = 판정불가' '수신: 판정불가' 2

  new_case && : >"${case_dir}/resp/logs.filter-log-events.${G}.fail"
  run_readme workers_receive_verdict "${G}" "${T0}" "$((T0 + 60))"
  expect 'receive: 조회 실패 = 판정불가' '수신: 판정불가' 2
}

#######################################
# 무완료 판정의 경계.
#######################################
test_nodone() {
  # 보이는 2 · 나이 25분 · 완료는 창(지금 - 20분) 밖에만.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '100 render.message_done messageId=m0 receiveCount=1 disposition=DELETE' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 1500'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 20분 무완료(창 밖 완료) = 정지' '무완료 render: 정지' 1

  # 사진 줄의 failed 도 완료로 센다(렌더 줄의 완료는 세지 않음).
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '1500 render.message_done messageId=m0 receiveCount=1 disposition=DELETE' \
    '1600 thumbnail.failed kind=clip targetId=1 retryable=true code=INTERNAL' &&
    queue_resp "${TQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${TQ}" Maximum '1740 1500'
  run_readme workers_nodone_verdict "${G}" thumbnail "${TQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 그 줄의 failed 는 완료' '무완료 thumbnail: 통과' 0

  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '540 1500'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 600))"
  expect 'nodone: 기동 20분 전 = 대기' '무완료 render: 대기' 3

  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 900'
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 나이 20분 이하 = 통과' '무완료 render: 통과' 0

  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 2 0 &&
    metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 나이 지표 없음 = 판정불가' '무완료 render: 판정불가' 2

  new_case && queue_resp "${RQ}" 2 0 &&
    : >"${case_dir}/resp/sqs.get-queue-attributes.${RQ}.ApproximateNumberOfMessages.fail"
  run_readme workers_nodone_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 1800))"
  expect 'nodone: 깊이 조회 실패 = 판정불가' '무완료 render: 판정불가' 2
}

#######################################
# 로그 판정(3분)의 경계.
#######################################
test_start_log() {
  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '30 render.started_rejected jobId=j1 status=503' &&
    queue_resp "${RQ}" 1 0
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect 'start-log: 깊이 > 0 · 시작 줄 없음 = 실패' '로그 render: 실패' 1

  new_case && log_resp "${G}" '0 render.worker_started queue=x' \
    '30 render.started jobId=j1 clipId=c1 attempt=1' &&
    queue_resp "${RQ}" 1 0
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 180))"
  expect 'start-log: 긴 렌더의 시작 줄 = 잠정 통과' '로그 render: 잠정 통과' 0

  # 업로드 하나가 진행 중(begin 뒤 조각 전송 — 조각 하나 최대 5분)이고 다음
  # 주문이 보이는 깊이에 있다.
  new_case && log_resp "${UG}" '0 upload.worker_started queue=x' \
    '30 upload.begin uploadId=1 bytes=10 resuming=false tokenValid=true' &&
    queue_resp "${UQ}" 1 0
  run_readme workers_start_log_verdict "${UG}" upload "${UQ}" "${T0}" \
    "$((T0 + 180))"
  expect 'start-log: 진행 중 업로드의 upload.begin = 잠정 통과' \
    '로그 upload: 잠정 통과' 0

  # 첫 receive_failed 는 기동 뒤 약 120.5초에 찍힌다 — 150초는 아직 이르다.
  new_case && log_resp "${G}" '0 render.worker_started queue=x' &&
    queue_resp "${RQ}" 0 0
  run_readme workers_start_log_verdict "${G}" render "${RQ}" "${T0}" \
    "$((T0 + 150))"
  expect 'start-log: 3분 전(150초) = 대기' '로그 render: 대기' 3
}

#######################################
# 지표 판정(20분)과 기동 시각의 경계.
#######################################
test_empty_and_started() {
  # 기동(30초) 앞 분(0초)의 점은 옛 태스크의 빈 수신일 수 있어 세지 않는다.
  new_case && metric_resp NumberOfEmptyReceives "${RQ}" Sum '0 5' '60 0'
  run_readme workers_empty_receive_verdict "${RQ}" "$((T0 + 30))" \
    "$((T0 + 600))"
  expect 'empty: 기동 앞 분의 점은 세지 않음' '지표 '"${RQ}"': 대기' 3

  new_case && metric_resp NumberOfEmptyReceives "${RQ}" Sum
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 1260))"
  expect 'empty: 20분 넘어 점 없음 = 판정불가' '지표 '"${RQ}"': 판정불가' 2

  new_case &&
    : >"${case_dir}/resp/cloudwatch.get-metric-statistics.NumberOfEmptyReceives.${RQ}.fail"
  run_readme workers_empty_receive_verdict "${RQ}" "${T0}" "$((T0 + 600))"
  expect 'empty: 조회 실패 = 판정불가' '지표 '"${RQ}"': 판정불가' 2

  new_case && log_resp "${G}" '5 render.receive_failed err=x' \
    '42 render.worker_started queue=x' '43 thumbnail.worker_started queue=y'
  run_readme workers_started_at "${G}" render "${T0}"
  expect 'started: 첫 worker_started 의 초' "$((T0 + 42))" 0

  new_case && log_resp "${G}" '43 thumbnail.worker_started queue=y'
  run_readme workers_started_at "${G}" render "${T0}"
  expect 'started: 없음 = 판정불가' '기동 render: 판정불가' 2
}

#######################################
# 사례의 시계를 둔다(가짜 date 가 낸다).
# Globals:
#   case_dir
# Arguments:
#   T0 뒤 초.
#######################################
clock_at() {
  printf '%s\n' "$((T0 + $1))" >"${case_dir}/clock"
}

#######################################
# 가짜 sleep 이 받은 초를 한 줄로 이어 본다(30초마다 · 몇 번).
# Globals:
#   case_dir
# Arguments:
#   사례 이름, 기대(「30 30」 꼴 · 없으면 빈 글자).
#######################################
expect_sleeps() {
  local got=""
  if [[ -e "${case_dir}/sleeps" ]]; then
    got="$(tr '\n' ' ' <"${case_dir}/sleeps")"
    got="${got% }"
  fi
  if [[ "${got}" != "$2" ]]; then
    fail "$1: sleep 「${got}」 (기대 「$2」)"
    return 1
  fi
  pass "$1"
}

#######################################
# 수신 감시 반복(workers_receive_watch)의 주기 · 종료 · 조회 실패.
#######################################
test_watch() {
  local lines=() line
  # 이어지는 실패 중이면 첫 주기에 정지하고 기다리지 않는다.
  new_case && clock_at 80
  while IFS= read -r line; do lines+=("${line}"); done < <(timeouts render 0 72)
  log_resp "${G}" "${lines[@]}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 600))"
  expect 'watch: 정지면 첫 주기에 1' '수신: 정지' 1
  expect_sleeps 'watch: 정지면 기다리지 않음' ''

  # 실패가 없으면 30초마다 보고 끝 시각에 0 — 0 · 30 · 60초에 판정.
  new_case && clock_at 0 && log_resp "${G}"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 60))"
  expect 'watch: 끝 시각까지 보면 0' '수신 감시: 끝' 0
  expect_sleeps 'watch: 30초마다 두 번 기다림' '30 30'

  # 조회 실패가 두 주기 이어지면 판정불가.
  new_case && clock_at 0 &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 600))"
  expect 'watch: 조회 실패 두 번 이어짐 = 판정불가' '수신 감시: 판정불가' 2

  # 조회 실패 한 번은 다음 주기에 다시 본다.
  new_case && clock_at 0 && log_resp "${G}" &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail" &&
    : >"${case_dir}/heal"
  run_readme workers_receive_watch "${G}" "${T0}" "$((T0 + 60))"
  expect 'watch: 조회 실패 한 번은 다음 주기에 다시' '수신 감시: 끝' 0

  # 끝 시각의 판정이 조회 실패면 끝내지 않고 한 주기 더 본다.
  new_case && clock_at 0 && log_resp "${G}" &&
    : >"${case_dir}/resp/logs.filter-log-events.${G}.fail" &&
    : >"${case_dir}/heal"
  run_readme workers_receive_watch "${G}" "${T0}" "${T0}"
  expect 'watch: 끝 시각의 조회 실패 뒤 통과 = 끝' '수신 감시: 끝' 0
  expect_sleeps 'watch: 끝 시각의 조회 실패는 한 주기 더' '30'
}

# PTY 에 띄운 대화형 zsh 에 파일마다 bracketed paste(ESC[200~ … ESC[201~ 와
# Enter)로 붙여 넣는 python3 프로그램. 붙여 넣은 뒤마다 print 로 표지를 찍어
# 끝났는지 보고, 쓰는 동안 출력을 읽어 PTY 버퍼가 차 멈추지 않게 한다. 한 걸음
# 20초를 넘기면 그만두고 종료 1 이다.
# 사용: python3 -I -c "${PTY_DRIVER}" <zsh> <기록 파일> <꼬리 명령 파일>
#   <붙여 넣을 파일> ….
PTY_DRIVER="$(
  cat <<'PY'
import fcntl
import os
import pty
import select
import struct
import sys
import termios
import time

PASTE_START = b"\x1b[200~"
PASTE_END = b"\x1b[201~"
STEP_SECONDS = 20


class Session:
    """PTY 의 zsh 하나. 쓰는 동안에도 출력을 읽는다."""

    def __init__(self, fd):
        self.fd = fd
        self.seen = bytearray()
        self.closed = False

    def _step(self, pending, deadline):
        left = deadline - time.monotonic()
        if left <= 0 or self.closed:
            return False
        writers = [self.fd] if pending else []
        readable, writable, _ = select.select([self.fd], writers, [], left)
        if readable:
            try:
                chunk = os.read(self.fd, 65536)
            except OSError:
                chunk = b""
            if not chunk:
                self.closed = True
                return False
            self.seen.extend(chunk)
        if writable and pending:
            try:
                del pending[:os.write(self.fd, bytes(pending[:512]))]
            except BlockingIOError:
                pass
        return True

    def send(self, data):
        pending = bytearray(data)
        deadline = time.monotonic() + STEP_SECONDS
        while pending:
            if not self._step(pending, deadline):
                return False
        return True

    def wait_for(self, token):
        deadline = time.monotonic() + STEP_SECONDS
        while token not in self.seen:
            if not self._step(None, deadline):
                return False
        return True

    def sync(self, n):
        return (self.send(b"print -r -- Z$((%d+100))Z\r" % n)
                and self.wait_for(b"Z%dZ" % (n + 100)))


def main():
    zsh, transcript, tail_file = sys.argv[1:4]
    pastes = []
    for path in sys.argv[4:]:
        with open(path, "rb") as f:
            pastes.append(f.read())
    with open(tail_file, "rb") as f:
        tail = f.read()
    pid, fd = pty.fork()
    if pid == 0:
        os.execv(zsh, [zsh, "-f", "-i"])
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 200, 0, 0))
    flags = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
    session = Session(fd)
    ok = session.sync(0)
    for i, text in enumerate(pastes, 1):
        if not ok:
            break
        paste = PASTE_START + text.replace(b"\n", b"\r") + PASTE_END + b"\r"
        ok = session.send(paste) and session.sync(i)
    if ok:
        ok = (session.send(tail.replace(b"\n", b"\r"))
              and session.sync(len(pastes) + 1))
        session.send(b"exit\r")
        session.wait_for(b"\x00")
    try:
        os.kill(pid, 9)
    except ProcessLookupError:
        pass
    os.waitpid(pid, 0)
    with open(transcript, "wb") as f:
        f.write(bytes(session.seen))
    return 0 if ok else 1


sys.exit(main())
PY
)"
readonly PTY_DRIVER

# 새 PTY 를 제어 터미널로 준 채 명령 하나를 띄우는 python3 프로그램. 표준
# 입력 · 출력 · 오류는 파일이다 — 사람이 터미널에서 이 시험을 돌릴 때 zsh 가
# 보는 꼴이다. 그때처럼 PTY 의 슬레이브를 이 프로그램이 열어 둔다(아무도 열어
# 두지 않으면 zsh 가 터미널을 쓰지 못해 멈춤이 재현되지 않는다). PTY 에 나오는
# 것(프롬프트 등)은 읽어 버린다. 20초 안에 끝나지 않으면 그 세션을 죽이고
# 종료 1 이다.
# 사용: python3 -I -c "${TTY_RUNNER}" <표준 입력 파일> <표준 출력 파일>
#   <표준 오류 파일> <명령> <인자> ….
TTY_RUNNER="$(
  cat <<'PY'
import fcntl
import os
import select
import signal
import sys
import termios
import time

RUN_SECONDS = 20


def redirect(path, target, flags):
    fd = os.open(path, flags, 0o600)
    os.dup2(fd, target)
    os.close(fd)


def main():
    stdin_path, out_path, err_path = sys.argv[1:4]
    fd, slave = os.openpty()
    pid = os.fork()
    if pid == 0:
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
        written = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
        redirect(stdin_path, 0, os.O_RDONLY)
        redirect(out_path, 1, written)
        redirect(err_path, 2, written)
        os.close(fd)
        os.close(slave)
        os.execvp(sys.argv[4], sys.argv[4:])
    deadline = time.monotonic() + RUN_SECONDS
    while time.monotonic() < deadline:
        if os.waitpid(pid, os.WNOHANG)[0]:
            return 0
        readable, _, _ = select.select([fd], [], [], 0.1)
        if not readable:
            continue
        try:
            chunk = os.read(fd, 65536)
        except OSError:
            chunk = b""
        if not chunk:
            time.sleep(0.1)
    os.killpg(pid, signal.SIGKILL)
    os.waitpid(pid, 0)
    return 1


sys.exit(main())
PY
)"
readonly TTY_RUNNER

# 대화형 zsh 와 bash 에 같이 넣는 가짜 aws. 받은 인자를 WORKERS_ZSH_CALLS 에
# 적고, 응답은 WORKERS_ZSH_RESP 에서 fake_aws 와 같은 열쇠로 고른다(없으면
# cat 이 실패). 대화형 zsh 에 들어가므로 `#` · `!` 를 쓰지 않는다. 명령 치환
# 안의 here-doc 은 bash 3.2 가 case 의 `)` 를 치환의 끝으로 읽어 read 로 받는다.
IFS= read -r -d '' SHARED_FAKE_AWS <<'SH'
aws() {
  printf '%s\n' "$*" >> "${WORKERS_ZSH_CALLS}"
  local key="$1.$2" want="" arg
  for arg in "$@"; do
    case "${want}" in
      plain) key="${key}.${arg}" ;;
      url) key="${key}.${arg##*/}" ;;
      dims) key="${key}.${arg##*Value=}" ;;
    esac
    want=""
    case "${arg}" in
      --log-group-name | --queue-name | --metric-name | --attribute-names)
        want=plain
        ;;
      --queue-url) want=url ;;
      --dimensions) want=dims ;;
    esac
  done
  cat -- "${WORKERS_ZSH_RESP}/${key}"
}
SH
readonly SHARED_FAKE_AWS

#######################################
# 대화형 zsh 사례의 입력 파일을 사례 디렉터리에 쓴다 — fake(가짜 aws) · run
# (판정 함수 아홉을 차례로 한 번씩 불러 결과와 종료 코드를 WORKERS_ZSH_RUN 에
# 적음) · tail(함수마다 「whence: <whence -w 출력>」) · stdin(fake · 블록 ·
# run · tail) · bash.stdin(fake · 블록 · run — bash 기준 실행). run 의 첫 줄이
# 가짜 aws 의 기록 파일을 바꿔, 정의하는 동안의 호출과 부른 뒤의 호출을
# 가른다. 모두 대화형 zsh 에 들어가므로 `#` · `!` 를 쓰지 않는다.
# Globals:
#   case_dir, readme_text, ZSH_FUNCTIONS, SHARED_FAKE_AWS, G, RQ, T0
# Arguments:
#   블록 글자(없으면 readme_text — 음성 대조가 바꾼 글자를 준다).
#######################################
write_zsh_inputs() {
  local text="${1-${readme_text}}" call
  printf '%s' "${SHARED_FAKE_AWS}" >"${case_dir}/fake"
  # shellcheck disable=SC2016
  {
    printf '%s\n' 'WORKERS_ZSH_CALLS="${WORKERS_ZSH_CALLS}.run"'
    for call in \
      "workers_iso ${T0}" \
      "workers_started_at ${G} render ${T0}" \
      "workers_start_log_verdict ${G} render ${RQ} ${T0} $((T0 + 200))" \
      "workers_receive_verdict ${G} ${T0} $((T0 + 200))" \
      "workers_nodone_verdict ${G} render ${RQ} ${T0} $((T0 + 1800))" \
      "workers_empty_receive_verdict ${RQ} ${T0} $((T0 + 600))" \
      "workers_receive_watch ${G} ${T0} 0"; do
      printf '{ %s; echo "rc=$?"; } >> "${WORKERS_ZSH_RUN}" 2>&1\n' "${call}"
    done
  } >"${case_dir}/run"
  # shellcheck disable=SC2016
  printf 'for f in %s; do print -r -- "whence: $(whence -w $f)"; done\n' \
    "${ZSH_FUNCTIONS[*]}" >"${case_dir}/tail"
  printf '%s' "${text}" >"${case_dir}/blocks"
  cat -- "${case_dir}/fake" "${case_dir}/blocks" "${case_dir}/run" \
    >"${case_dir}/bash.stdin"
  cat -- "${case_dir}/bash.stdin" "${case_dir}/tail" >"${case_dir}/stdin"
  mkdir -- "${case_dir}/home"
  # 판정 함수가 끝까지 도는 응답 — 기동 0 · 시작 30 · 완료 1700 · 깊이 1 ·
  # 나이 25분 · 기동 뒤 분의 빈 수신.
  log_resp "${G}" '0 render.worker_started queue=x' \
    '30 render.started jobId=j1 clipId=c1 attempt=1' \
    '1700 render.message_done messageId=m1 receiveCount=1 disposition=DELETE'
  queue_resp "${RQ}" 1 0
  metric_resp ApproximateAgeOfOldestMessage "${RQ}" Maximum '1740 1500'
  metric_resp NumberOfEmptyReceives "${RQ}" Sum '60 3'
}

#######################################
# 대화형 zsh 사례 디렉터리를 새로 만들어 입력 파일을 쓰고, 그 사례에서 zsh 를
# 띄울 환경(env -i …)을 zsh_env 에 둔다. HOME · TMPDIR 은 사례 디렉터리이고
# AWS 설정 파일은 /dev/null 이다. bash 기준 실행(run.bash)을 여기서 돌린다.
# Globals:
#   case_dir, zsh_env
# Arguments:
#   블록 글자(write_zsh_inputs 와 같음).
#######################################
new_zsh_case() {
  new_case && write_zsh_inputs "$@" || return 1
  zsh_env=(env -i "PATH=${PATH}" "HOME=${case_dir}/home" "TERM=xterm-256color"
    "LANG=${LANG:-C.UTF-8}" "TMPDIR=${case_dir}/tmp"
    "AWS_CONFIG_FILE=/dev/null" "AWS_SHARED_CREDENTIALS_FILE=/dev/null"
    "WORKERS_ZSH_CALLS=${case_dir}/calls" "WORKERS_ZSH_RESP=${case_dir}/resp"
    "WORKERS_ZSH_RUN=${case_dir}/run.out")
  "${zsh_env[@]}" "WORKERS_ZSH_RUN=${case_dir}/run.bash" bash -s \
    <"${case_dir}/bash.stdin" >/dev/null 2>&1
}

#######################################
# 대화형 zsh 의 결과에서 어긋남을 한 줄씩 낸다(없으면 출력 없음). 판정 함수
# 아홉이 모두 function 인지, 정의하는 동안 가짜 aws 가 불리지 않았는지, 함수를
# 부른 결과(run.out — 표준 출력 · 오류 · 종료 코드)가 bash 기준(run.bash)과
# 바이트 단위로 같은지, `command not found` 가 없는지 본다.
# Globals:
#   case_dir, ZSH_FUNCTIONS
#######################################
zsh_problems() {
  local fn missing=()
  for fn in "${ZSH_FUNCTIONS[@]}"; do
    if ! grep -aq "whence: ${fn}: function" "${case_dir}/out"; then
      missing+=("${fn}")
    fi
  done
  if [[ "${#missing[@]}" -ne 0 ]]; then
    printf '정의 안 된 함수 %s개(%s) / 오류: %s\n' "${#missing[@]}" \
      "${missing[*]}" "$(grep -aoh 'zsh: [^[:cntrl:]]*' "${case_dir}/out" \
        "${case_dir}/err" | sort | uniq -c | tr '\n' '|')"
  fi
  if [[ -s "${case_dir}/calls" ]]; then
    printf '정의하는 동안 가짜 aws 가 불림 — %s\n' \
      "$(tr '\n' '|' <"${case_dir}/calls")"
  fi
  if [[ "$(grep -c '^rc=' "${case_dir}/run.bash" 2>/dev/null)" -ne 7 ]]; then
    printf 'bash 기준 실행의 결과가 일곱 줄이 아님\n'
  fi
  if ! cmp -s -- "${case_dir}/run.bash" "${case_dir}/run.out"; then
    printf '부른 결과가 bash 와 다름 — zsh 「%s」\n' \
      "$(tr '\n' '|' <"${case_dir}/run.out" 2>/dev/null)"
  fi
  if grep -aqi 'command not found' -- "${case_dir}/out" "${case_dir}/err" \
    "${case_dir}/run.out" 2>/dev/null; then
    printf 'command not found 가 있음\n'
  fi
}

#######################################
# zsh_problems 가 비었으면 통과, 아니면 첫 어긋남으로 실패한다.
# Arguments:
#   사례 이름.
#######################################
expect_zsh_clean() {
  local problems
  problems="$(zsh_problems)"
  if [[ -n "${problems}" ]]; then
    fail "$1: ${problems%%$'\n'*}"
    return 1
  fi
  pass "$1"
}

#######################################
# 블록 글자를 대화형 zsh 에 표준 입력으로 넣는다(제어 터미널 없음). 결과는
# 사례의 out · err · run.out 이다.
# Globals:
#   case_dir, zsh_env, ZSH_STDIN_FLAGS
# Arguments:
#   zsh 경로, 블록 글자(write_zsh_inputs 와 같음).
#######################################
run_zsh_stdin() {
  local zsh="$1"
  shift
  new_zsh_case "$@" || return 1
  "${zsh_env[@]}" "${zsh}" "${ZSH_STDIN_FLAGS[@]}" <"${case_dir}/stdin" \
    >"${case_dir}/out" 2>"${case_dir}/err"
}

#######################################
# 블록에 줄 머리나 공백 뒤의 `#`(대화형 zsh 에서 주석이 아니라 명령 · 인자가
# 되는 글자)가 없는지 본다. 부르지 않은 갈래의 주석은 실행해 보는 사례가 못
# 보므로 글자로 본다.
# Globals:
#   readme_text
#######################################
test_no_comment_in_blocks() {
  local hits
  hits="$(printf '%s' "${readme_text}" | grep -nE '(^|[[:space:]])#')"
  if [[ -n "${hits}" ]]; then
    fail "블록에 # 주석: $(printf '%s' "${hits}" | tr '\n' '|')"
    return 1
  fi
  pass '블록: 줄 머리 · 공백 뒤 # 0개'
}

#######################################
# 대화형 zsh(interactivecomments 꺼짐 · 이력 확장 켜짐)에서 README 블록이
# 판정 함수를 모두 정의하고, 불렀을 때 bash 와 같은 결과를 내는지 본다.
# 표준 입력(터미널 없음 · 있음)과 PTY bracketed paste 세 사례와, 함수 본문에
# `#` 주석을 되돌린 음성 대조.
# Globals:
#   case_dir, zsh_env, readme_blocks, readme_text
#######################################
test_zsh_interactive() {
  local zsh i pastes=() head commented
  if ! zsh="$(command -v zsh)"; then
    skip 'zsh: 표준 입력 붙여 넣기(zsh 없음)'
    skip 'zsh: 음성 대조 — 본문 # 주석(zsh 없음)'
    skip 'zsh: 터미널 + 표준 입력(zsh 없음)'
    skip 'zsh: PTY 붙여 넣기(zsh 없음)'
    return 0
  fi

  run_zsh_stdin "${zsh}"
  expect_zsh_clean 'zsh: 표준 입력 붙여 넣기 = 함수 아홉 정의 · aws 0회 · bash 와 같은 결과'

  # 음성 대조: 수신 판정 본문 첫 줄에 주석을 되돌리면 잡혀야 한다.
  # bash 3.2 의 ${…/…/…} 따옴표 문제를 피해 앞 · 뒤로 잇는다(test_mutations).
  head="workers_receive_verdict() {"$'\n'
  commented="${readme_text%%"${head}"*}${head}  # comment_reintroduced"$'\n'
  commented+="${readme_text#*"${head}"}"
  # 자리가 없으면 앞 조각이 글자 전체라, 바꾼 글자가 원래 글자로 시작한다.
  if [[ "${commented}" == "${readme_text}"* ]]; then
    fail 'zsh: 음성 대조 — 주석을 넣을 자리를 못 찾음'
  else
    run_zsh_stdin "${zsh}" "${commented}"
    if [[ -n "$(zsh_problems)" ]]; then
      pass 'zsh: 음성 대조 — 본문 # 주석이 잡힘'
    else
      fail 'zsh: 음성 대조 — 본문 # 주석을 넣어도 통과함'
    fi
  fi

  if ! command -v python3 >/dev/null; then
    skip 'zsh: 터미널 + 표준 입력(python3 없음)'
    skip 'zsh: PTY 붙여 넣기(python3 없음)'
    return 0
  fi
  new_zsh_case
  if python3 -I -c "${TTY_RUNNER}" "${case_dir}/stdin" "${case_dir}/out" \
    "${case_dir}/err" "${zsh_env[@]}" "${zsh}" "${ZSH_STDIN_FLAGS[@]}"; then
    expect_zsh_clean 'zsh: 터미널 + 표준 입력 = 20초 안에 끝남 · 함수 아홉 · bash 와 같은 결과'
  else
    fail 'zsh: 터미널 + 표준 입력이 20초 안에 끝나지 않음(터미널 입력 대기)'
  fi

  new_zsh_case
  pastes=("${case_dir}/fake")
  for i in "${!readme_blocks[@]}"; do
    printf '%s' "${readme_blocks[i]}" >"${case_dir}/block.${i}"
    pastes+=("${case_dir}/block.${i}")
  done
  # 부르는 줄도 붙여 넣는다 — 함수가 도는 동안 쳐 둔 글자는 터미널의 줄
  # 버퍼(macOS 1024바이트)를 넘으면 잘려, 친 글자로는 꼬리 한 줄만 보낸다.
  pastes+=("${case_dir}/run")
  if ! "${zsh_env[@]}" python3 -I -c "${PTY_DRIVER}" "${zsh}" \
    "${case_dir}/out" "${case_dir}/tail" "${pastes[@]}" \
    2>"${case_dir}/err"; then
    fail "zsh: PTY 붙여 넣기가 끝나지 않음(이어 쓰기 대기 등) / 오류: $(
      grep -ao 'zsh: [^[:cntrl:]]*' "${case_dir}/out" | tr '\n' '|')"
    return 1
  fi
  expect_zsh_clean 'zsh: PTY bracketed paste = 함수 아홉 정의 · aws 0회 · bash 와 같은 결과'
}

#######################################
# 사례 묶음 전부를 지금 readme_text 로 돈다.
# Globals:
#   quiet, suite_failures
#######################################
run_suite() {
  local test_name
  for test_name in \
    test_design_cases \
    test_receive \
    test_nodone \
    test_start_log \
    test_empty_and_started \
    test_watch; do
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
    # bash 3.2 의 ${base/…/…} 따옴표 문제를 피해 앞 · 뒤로 잇는다
    # (network_vpce_runbook_test 와 같은 이유).
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
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/workers_watch_runbook_test.XXXXXX")" ||
    exit 1
  trap remove_tmp_root EXIT
  trap 'exit 1' INT TERM

  if load_readme; then
    run_suite
    test_no_comment_in_blocks
    test_zsh_interactive
    test_mutations
  fi

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ $((passes + skips)) -ne "${EXPECTED_CASES}" ]]; then
    fail "사례 수가 다름 — 통과 ${passes} + skip ${skips} ≠ ${EXPECTED_CASES}"
  fi
  if [[ "${failures}" -ne 0 ]]; then
    printf 'workers_watch_runbook_test: %s건 실패(통과 %s건 · skip %s건)\n' \
      "${failures}" "${passes}" "${skips}" >&2
    exit 1
  fi
  printf 'workers_watch_runbook_test: 전부 통과(%s건 · skip %s건 · %s\n' \
    "${passes}" "${skips}" '임시 디렉터리 잔여 0)'
}

main "$@"
