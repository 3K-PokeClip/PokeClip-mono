#!/usr/bin/env bash
#
# 앞 판정 뒤의 권한 변경 이벤트를 이벤트 기록(CloudTrail lookup-events)에서
# 센다(계획 M17a · 설계 11-5 의 2-3-10). 2-3 판정의 보조이고 판정을 대신하지
# 않는다. 읽기만 하고 무료다.
#
# data/event_names.txt 의 행(이벤트 출처 · 이벤트 이름 · 조회 리전)마다
# 그 리전에서 이름으로 한 번 조회한다. 리전 규칙(IAM · CloudFront =
# us-east-1 · STS = us-east-1 과 서울 · 그 밖 = 서울)은 데이터가 정한다.
# 같은 이름이 여러 서비스에 있으므로(예: SNS · SQS 의 AddPermission) 결과의
# 이벤트 출처가 그 행의 출처인 것만 센다. 전체는 이벤트 ID 로 한 번만 센다
# (STS 두 리전에 같은 이벤트가 섞여 와도 하나).
#
# 표준 출력(계약)은 「EVENTS <건수>」 한 줄이다. 이벤트 이름 · 시각 · 주체
# 원본과 행별 건수는 증거 디렉터리(lookup-<n>.txt · summary.tsv ·
# event_ids.txt)에만 둔다. 조회는 리전마다 초당 2회로 제한되며 aws CLI 의
# 재시도에 맡긴다.
#
# 사용: events.sh --since <YYYY-MM-DDTHH:MM:SSZ>
#       --since 는 같은 묶음의 앞 판정 시각(UTC)이다. 90일보다 이르면
#       이벤트 기록의 보관 한계(90일)를 표준 오류로 경고하고 조회는 한다.
# 종료 코드: 0 조회 끝(건수는 표준 출력) / 2 검사 불가(사용법 · 지금보다
#            늦은 --since · 증거 경로 · 조회 실패 · 호출 자체의
#            AccessDenied · 응답 꼴 — 칸 수 · 이벤트 ID · 출처 · 이름 · 시각)
#            — 0건으로 넘어가지 않는다(fail-closed).

set -uo pipefail
# 증거 파일(이벤트 원본)은 본인만 읽는다.
umask 077

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly EVENT_NAMES="${SCRIPT_DIR}/data/event_names.txt"
readonly SINCE_PATTERN='^[0-9]{4}(-[0-9]{2}){2}T[0-9]{2}(:[0-9]{2}){2}Z$'
readonly SOURCE_PATTERN='^[a-z0-9.-]+\.amazonaws\.com$'
readonly NAME_PATTERN='^[A-Za-z0-9]+$'
# 조회 응답의 이벤트 ID(CloudTrail — UUID 꼴)와 시각. 시각은 aws CLI 가 낼 수
# 있는 꼴 둘 — ISO 8601(cli_timestamp_format = iso8601, 기본) · epoch 초
# (none).
readonly EVENT_ID_PATTERN='^[A-Za-z0-9-]+$'
readonly ISO_TIME_PATTERN='^[0-9]{4}(-[0-9]{2}){2}T[0-9]{2}(:[0-9]{2}){2}'
readonly EPOCH_TIME_PATTERN='^[0-9]+(\.[0-9]+)?$'
readonly REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]$'
LOOKUP_QUERY='Events[].[EventId,EventSource,EventName,EventTime,'
LOOKUP_QUERY+='Username]'
readonly LOOKUP_QUERY
readonly ISO_FORMAT='+%Y-%m-%dT%H:%M:%SZ'

# shellcheck source=infra/terraform/scripts/precheck/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh" || {
  printf 'events: 검사 불가: 증거 경로 함수를 읽지 못함\n' >&2
  exit 2
}

# event_names.txt 의 행 「출처<탭>이름<탭>리전」.
NAMES=()
# 지금까지 센 이벤트 ID(줄바꿈으로 감싼 목록)와 건수.
SEEN_IDS=$'\n'
SEEN_COUNT=0
# lookup_row 가 남기는 그 행의 맞은 이벤트 수.
ROW_MATCHED=0

#######################################
# 검사를 이어갈 수 없을 때 멈춘다(0건으로 넘어가지 않는다).
# Arguments:
#   설명.
#######################################
die() {
  printf 'events: 검사 불가: %s\n' "$*" >&2
  exit 2
}

#######################################
# 탭 구분 줄의 칸 수가 정확하고 빈 칸이 없는지 본다. 탭 IFS 의 read 는 빈
# 칸을 접어 칸을 밀기 때문에, 칸을 나누기 전에 이것으로 먼저 거른다.
# Arguments:
#   줄, 칸 수.
# Returns:
#   맞으면 0.
#######################################
tsv_ok() {
  local line="$1" fields="$2" tab=$'\t' tabs
  tabs="${line//[!${tab}]/}"
  [[ "${#tabs}" -eq $((fields - 1)) && "${line}" != "${tab}"* &&
    "${line}" != *"${tab}" && "${line}" != *"${tab}${tab}"* ]]
}

#######################################
# event_names.txt 를 읽어 NAMES 에 담는다. 「#」 으로 시작하는 줄은 주석,
# 빈 줄은 건너뛴다.
# Globals:
#   NAMES
#######################################
load_names() {
  local line source name region
  [[ -f "${EVENT_NAMES}" && ! -L "${EVENT_NAMES}" ]] ||
    die "event_names.txt 가 없음"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" || "${line}" == '#'* ]] && continue
    tsv_ok "${line}" 3 || die "event_names.txt 의 행 꼴이 틀림: ${line}"
    IFS=$'\t' read -r source name region <<<"${line}"
    if ! [[ "${source}" =~ ${SOURCE_PATTERN} &&
      "${name}" =~ ${NAME_PATTERN} && "${region}" =~ ${REGION_PATTERN} ]]; then
      die "event_names.txt 의 행 꼴이 틀림: ${line}"
    fi
    NAMES+=("${source}"$'\t'"${name}"$'\t'"${region}")
  done <"${EVENT_NAMES}" || die "event_names.txt 를 읽지 못함"
  [[ "${#NAMES[@]}" -gt 0 ]] || die "event_names.txt 에 행이 없음"
}

#######################################
# 90일 전 시각(UTC)을 낸다(GNU date, 안 되면 BSD date).
# Outputs:
#   YYYY-MM-DDTHH:MM:SSZ.
# Returns:
#   둘 다 안 되면 1.
#######################################
ninety_days_ago() {
  date -u -d '90 days ago' "${ISO_FORMAT}" 2>/dev/null ||
    date -u -v-90d "${ISO_FORMAT}" 2>/dev/null
}

#######################################
# 조회 응답 줄 하나(ID · 출처 · 이름 · 시각 · 주체)의 꼴을 본다. 칸 수를
# 먼저 세고(빈 칸 접힘), 칸마다 꼴을 본다. 주체 칸은 비어 있지 않기만 하면
# 된다(없으면 aws CLI 가 None 으로 적음). here-string 대신 매개변수 확장으로
# 나눈다 — bash 3.2 의 here-string 은 임시 파일을 써서 원본이 증거 디렉터리
# 밖에 잠시라도 남기 때문이다.
# Arguments:
#   응답 줄.
# Outputs:
#   「ID<탭>출처<탭>이름」.
# Returns:
#   꼴이 틀리면 1.
#######################################
lookup_fields() {
  local line="$1" id source name time rest
  tsv_ok "${line}" 5 || return 1
  id="${line%%$'\t'*}"
  rest="${line#*$'\t'}"
  source="${rest%%$'\t'*}"
  rest="${rest#*$'\t'}"
  name="${rest%%$'\t'*}"
  rest="${rest#*$'\t'}"
  time="${rest%%$'\t'*}"
  [[ "${id}" =~ ${EVENT_ID_PATTERN} && "${source}" =~ ${SOURCE_PATTERN} &&
    "${name}" =~ ${NAME_PATTERN} ]] || return 1
  [[ "${time}" =~ ${ISO_TIME_PATTERN} ||
    "${time}" =~ ${EPOCH_TIME_PATTERN} ]] || return 1
  printf '%s\t%s\t%s\n' "${id}" "${source}" "${name}"
}

#######################################
# 행 하나를 조회하고, 그 행의 출처 · 이름과 맞는 이벤트를 ID 로 한 번만
# 센다. 원본은 증거 디렉터리의 lookup-<n>.txt · lookup-<n>.err 에 남는다.
# Globals:
#   SEEN_IDS, SEEN_COUNT, ROW_MATCHED
# Arguments:
#   증거 디렉터리, 행 번호, 출처, 이름, 리전, --since 값.
#######################################
lookup_row() {
  local dir="$1" n="$2" source="$3" name="$4" region="$5" since="$6"
  local raw="${dir}/lookup-${n}.txt" line fields id got
  ROW_MATCHED=0
  if ! aws cloudtrail lookup-events \
    --lookup-attributes "AttributeKey=EventName,AttributeValue=${name}" \
    --start-time "${since}" --region "${region}" --query "${LOOKUP_QUERY}" \
    --output text >"${raw}" 2>"${dir}/lookup-${n}.err"; then
    if grep -qF 'AccessDenied' -- "${dir}/lookup-${n}.err"; then
      die "조회 호출 자체가 거부됨(AccessDenied — lookup-${n}.err)"
    fi
    die "조회 실패(lookup-${n}.err)"
  fi
  while IFS= read -r line || [[ -n "${line}" ]]; do
    fields="$(lookup_fields "${line}")" ||
      die "조회 응답의 꼴이 다름(lookup-${n}.txt)"
    id="${fields%%$'\t'*}"
    got="${fields#*$'\t'}"
    [[ "${got}" == "${source}"$'\t'"${name}" ]] || continue
    ROW_MATCHED=$((ROW_MATCHED + 1))
    if [[ "${SEEN_IDS}" != *$'\n'"${id}"$'\n'* ]]; then
      SEEN_IDS+="${id}"$'\n'
      SEEN_COUNT=$((SEEN_COUNT + 1))
    fi
  done <"${raw}" || die "조회 원본을 읽지 못함(lookup-${n}.txt)"
}

main() {
  local since now dir cutoff row source name region n=0 summary=""
  if [[ "$#" -ne 2 || "$1" != --since ]]; then
    die "사용: events.sh --since <YYYY-MM-DDTHH:MM:SSZ>"
  fi
  since="$2"
  [[ "${since}" =~ ${SINCE_PATTERN} ]] ||
    die "--since 는 UTC 「YYYY-MM-DDTHH:MM:SSZ」 꼴이어야 함: ${since}"
  now="$(date -u "${ISO_FORMAT}")" || die "지금 시각을 읽지 못함"
  cutoff="$(ninety_days_ago)" || die "90일 전 시각을 계산하지 못함"
  # 같은 꼴의 시각을 숫자만 남겨 비교한다(로캘과 무관). 지금보다 늦은
  # --since 는 조회 결과가 0건으로 보일 수 있어 받지 않는다.
  if (( 10#${since//[!0-9]/} > 10#${now//[!0-9]/} )); then
    die "--since 가 지금(UTC)보다 늦음: ${since}"
  fi
  if (( 10#${since//[!0-9]/} < 10#${cutoff//[!0-9]/} )); then
    printf 'events: 경고: --since 가 90일보다 이릅니다 — %s\n' \
      "이벤트 기록은 90일만 보관하므로 그보다 앞의 이벤트는 조회되지 않음" >&2
  fi
  load_names
  dir="$(evidence::new_dir events)" || exit 2
  printf 'events: 증거 ~/%s/%s\n' "${EVIDENCE_ROOT_NAME}" "${dir##*/}" >&2
  for row in "${NAMES[@]}"; do
    n=$((n + 1))
    source="${row%%$'\t'*}"
    region="${row##*$'\t'}"
    name="${row#*$'\t'}"
    name="${name%%$'\t'*}"
    lookup_row "${dir}" "${n}" "${source}" "${name}" "${region}" "${since}"
    summary+="${row}"$'\t'"${ROW_MATCHED}"$'\n'
  done
  if ! printf '# 출처\t이름\t리전\t맞은 수 — --since %s\n%s' "${since}" \
    "${summary}" >"${dir}/summary.tsv" ||
    ! printf '%s' "${SEEN_IDS#$'\n'}" >"${dir}/event_ids.txt"; then
    die "증거를 쓰지 못함"
  fi
  printf 'EVENTS %s\n' "${SEEN_COUNT}"
}

main "$@"
