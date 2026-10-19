#!/usr/bin/env bash
#
# SCP 재판정의 비교 기준이 된 판정 뒤의 이벤트를 이벤트 기록(CloudTrail
# lookup-events)에서 센다(계획 M17a · 설계 11-5 의 2-3-10). 2-3 판정의
# 보조이고 판정을 대신하지 않는다. 읽기만 하고 무료다.
#
# 조회 대상(data/event_names.txt)은 두 묶음이다 — 권한을 바꾸는 관리
# 이벤트(정책 부착 · 신뢰 정책 · 경계 · 사용자 · 키 · 그룹 가입 · 새 역할 ·
# OIDC 공급자 · 태그 · 리소스 정책)와 STS 역할 받기(AssumeRole ·
# AssumeRoleWithWebIdentity). 역할 받기는 권한 변경이 아니다.
#
# data/event_names.txt 의 행(이벤트 출처 · 이벤트 이름 · 조회 리전)마다
# 그 리전에서 이름으로 한 번 조회한다. 리전 규칙(IAM · CloudFront =
# us-east-1 · STS = us-east-1 과 서울 · 그 밖 = 서울)은 데이터가 정한다.
# 같은 이름이 여러 서비스에 있으므로(예: SNS · SQS 의 AddPermission) 결과의
# 이벤트 출처가 그 행의 출처인 것만 센다. 전체는 이벤트 ID 로 한 번만 센다
# (STS 두 리전에 같은 이벤트가 섞여 와도 하나).
#
# 표준 출력(계약 — kty 59차로 계획 390행을 넓힘)은 한 줄이다:
#   EVENTS <n> (권한 변경 <a> · 역할 받기 <b>)
# n = 두 묶음을 합쳐 이벤트 ID 로 한 번씩 센 고유 이벤트 수 · b = STS 역할
# 받기 행으로 센 고유 이벤트 수 · a = 그 밖(권한 변경 묶음) · a + b = n.
# 같은 ID 가 두 묶음 행에서 다 나오면(실제로는 없는 꼴) a 로 센다. 평소에는
# 역할 받기가 대부분이다 — 권한 변경이 있었는지는 n 이 아니라 a 와 증거
# summary.tsv 의 역할 받기 아닌 0 아닌 행으로 본다. 식별자(이름 · ARN ·
# 계정)는 표준 출력에 없다. 이벤트 이름 · 시각 · 주체 원본과 행별 건수 ·
# 범주별 ID 는 증거 디렉터리(lookup-<n>.txt · summary.tsv · event_ids.txt)에만
# 둔다. 조회는 리전마다 초당 2회로 제한되며 aws CLI 의
# 재시도에 맡긴다.
#
# 사용: events.sh --since <YYYY-MM-DDTHH:MM:SSZ>
#       --since 는 scp-recheck.sh 가 표준 오류에 낸 「비교 기준」 이
#       가리키는 판정의 시각(UTC)이다 — 승인 기준이면 그 증거 디렉터리
#       이름의 시각, baseline.tsv 면 baseline 전체에서 가장 이른 판정
#       시각(다섯째 칸) 2026-10-13T01:59:00Z(10:59 KST). 이번
#       scp-recheck.sh 가 만든 디렉터리의 시각이 아니다. 묶음이 둘인
#       단계(O7e · O7f)는 공통 묶음의 비교 기준을 쓴다.
#       90일보다 이르면 이벤트 기록의 보관 한계(90일)를 표준 오류로 경고하고
#       조회는 한다.
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
# 조회 응답의 이벤트 ID 와 시각(문자열 전체를 맞춘다). ID 는 CloudTrail 서비스
# 모델에 꼴이 없는 문자열이라(실제로는 UUID) 글자만 묶는다. 시각은 aws CLI
# 가 낼 수 있는 꼴 둘이다(awscli customizations/timestampformat.py) — ISO
# 8601(cli_timestamp_format = iso8601, 기본 — datetime.isoformat() 이라 로컬
# 오프셋 ±HH:MM · 소수 초가 붙을 수 있다) · epoch 초(wire — CloudTrail 은
# JSON 프로토콜이라 받은 그대로가 epoch 초).
readonly EVENT_ID_PATTERN='^[A-Za-z0-9-]+$'
ISO_TIME_PATTERN='^[0-9]{4}(-[0-9]{2}){2}T[0-9]{2}(:[0-9]{2}){2}'
ISO_TIME_PATTERN+='(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
readonly ISO_TIME_PATTERN
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
# shellcheck source=infra/terraform/scripts/precheck/lib/precheck_common.sh
source "${SCRIPT_DIR}/lib/precheck_common.sh" || {
  printf 'events: 검사 불가: 공용 도우미를 읽지 못함\n' >&2
  exit 2
}

# event_names.txt 의 행 「출처<탭>이름<탭>리전」.
names=()
# 범주별로 센 이벤트 ID(줄바꿈으로 감싼 목록 — 범주 안에서는 한 번씩).
perm_ids=$'\n'
role_ids=$'\n'
# lookup_row 가 남기는 그 행의 맞은 이벤트 수.
row_matched=0

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
# event_names.txt 를 읽어 names 에 담는다. 「#」 으로 시작하는 줄은 주석,
# 빈 줄은 건너뛴다.
# Globals:
#   EVENT_NAMES, SOURCE_PATTERN, NAME_PATTERN, REGION_PATTERN, names
#######################################
load_names() {
  local line source name region
  [[ -f "${EVENT_NAMES}" && ! -L "${EVENT_NAMES}" ]] ||
    die "event_names.txt 가 없음"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" || "${line}" == '#'* ]] && continue
    precheck::tsv_ok "${line}" 3 || die "event_names.txt 의 행 꼴이 틀림: ${line}"
    source="${line%%$'\t'*}"
    region="${line##*$'\t'}"
    name="${line#*$'\t'}"
    name="${name%%$'\t'*}"
    if ! [[ "${source}" =~ ${SOURCE_PATTERN} &&
      "${name}" =~ ${NAME_PATTERN} && "${region}" =~ ${REGION_PATTERN} ]]; then
      die "event_names.txt 의 행 꼴이 틀림: ${line}"
    fi
    names+=("${source}"$'\t'"${name}"$'\t'"${region}")
  done <"${EVENT_NAMES}" || die "event_names.txt 를 읽지 못함"
  [[ "${#names[@]}" -gt 0 ]] || die "event_names.txt 에 행이 없음"
}

#######################################
# 90일 전 시각(UTC)을 낸다(GNU date, 안 되면 BSD date).
# Globals:
#   ISO_FORMAT
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
# event_names.txt 의 행이 STS 역할 받기 묶음인지 본다(그 밖은 권한 변경
# 묶음). 받는 이름은 event_names.txt 맨 아래 묶음의 이름과 같아야 한다 —
# 여기를 고치면 그 묶음도 함께 고친다(data_files_test 가 대조한다).
# Arguments:
#   이벤트 출처, 이벤트 이름.
# Returns:
#   역할 받기면 0.
#######################################
is_role_assumption() {
  [[ "$1" == sts.amazonaws.com ]] &&
    [[ "$2" == AssumeRole || "$2" == AssumeRoleWithWebIdentity ]]
}

#######################################
# 조회 응답 줄 하나(ID · 출처 · 이름 · 시각 · 주체)의 꼴을 본다. 칸 수를
# 먼저 세고(빈 칸 접힘 · 빈 ID), 칸마다 꼴을 본다. ID · 이름이 aws CLI text
# 출력의 결측 표기 None 이면 받지 않는다(이름이 None 이면 조용히 세지 않게
# 된다). 주체 칸은 비어 있지 않기만 하면 된다 — 주체가 없는 이벤트는 aws
# CLI 가 None 으로 적고, 세는 데 쓰지 않는다. here-string 대신 매개변수
# 확장으로 나눈다 — bash 3.2 의 here-string 은 임시 파일을 써서 원본이 증거
# 디렉터리 밖에 잠시라도 남기 때문이다.
# Globals:
#   EVENT_ID_PATTERN, SOURCE_PATTERN, NAME_PATTERN, ISO_TIME_PATTERN,
#   EPOCH_TIME_PATTERN
# Arguments:
#   응답 줄.
# Outputs:
#   「ID<탭>출처<탭>이름」.
# Returns:
#   꼴이 틀리면 1.
#######################################
lookup_fields() {
  local line="$1" id source name time rest
  precheck::tsv_ok "${line}" 5 || return 1
  id="${line%%$'\t'*}"
  rest="${line#*$'\t'}"
  source="${rest%%$'\t'*}"
  rest="${rest#*$'\t'}"
  name="${rest%%$'\t'*}"
  rest="${rest#*$'\t'}"
  time="${rest%%$'\t'*}"
  [[ "${id}" =~ ${EVENT_ID_PATTERN} && "${id}" != None &&
    "${source}" =~ ${SOURCE_PATTERN} && "${name}" =~ ${NAME_PATTERN} &&
    "${name}" != None ]] || return 1
  [[ "${time}" =~ ${ISO_TIME_PATTERN} ||
    "${time}" =~ ${EPOCH_TIME_PATTERN} ]] || return 1
  printf '%s\t%s\t%s\n' "${id}" "${source}" "${name}"
}

#######################################
# 행 하나를 조회하고, 그 행의 출처 · 이름과 맞는 이벤트를 그 행의 범주
# (권한 변경 · 역할 받기) 목록에 ID 로 한 번만 담는다. 원본은 증거
# 디렉터리의 lookup-<n>.txt · lookup-<n>.err 에 남는다.
# Globals:
#   row_matched, LOOKUP_QUERY, role_ids, perm_ids
# Arguments:
#   증거 디렉터리, 행 번호, 출처, 이름, 리전, --since 값.
#######################################
lookup_row() {
  local dir="$1" n="$2" source="$3" name="$4" region="$5" since="$6"
  local raw="${dir}/lookup-${n}.txt" line fields id got role=0
  row_matched=0
  is_role_assumption "${source}" "${name}" && role=1
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
    row_matched=$((row_matched + 1))
    if [[ "${role}" -eq 1 && "${role_ids}" != *$'\n'"${id}"$'\n'* ]]; then
      role_ids+="${id}"$'\n'
    elif [[ "${role}" -eq 0 && "${perm_ids}" != *$'\n'"${id}"$'\n'* ]]; then
      perm_ids+="${id}"$'\n'
    fi
  done <"${raw}" || die "조회 원본을 읽지 못함(lookup-${n}.txt)"
}

#######################################
# 범주별 고유 이벤트 수를 센다. 같은 ID 가 두 범주에 다 있으면(실제로는
# 없는 꼴) 권한 변경으로 센다 — 권한 변경을 가리지 않는 쪽. 그래서 권한
# 변경 + 역할 받기 = 전체다. 증거 event_ids.txt 에 「ID<탭>범주」 로 남긴다.
# Globals:
#   perm_ids, role_ids
# Arguments:
#   증거 디렉터리.
# Outputs:
#   「EVENTS <전체> (권한 변경 <a> · 역할 받기 <b>)」.
#######################################
report_counts() {
  local dir="$1" rest id perm=0 role=0 ids=""
  # 목록은 「\n<ID>\n<ID>\n…」 꼴이다. 매개변수 확장으로 한 줄씩 떼어 낸다 —
  # here-string 은 ID 를 증거 디렉터리 밖 임시 파일에 쓰고, 그 파일을 만들지
  # 못하면 읽기가 조용히 실패해 건수가 0 으로 보였다(거짓 0).
  rest="${perm_ids#$'\n'}"
  while [[ -n "${rest}" ]]; do
    id="${rest%%$'\n'*}"
    rest="${rest#"${id}"}"
    rest="${rest#$'\n'}"
    perm=$((perm + 1))
    ids+="${id}"$'\t'"권한 변경"$'\n'
  done
  rest="${role_ids#$'\n'}"
  while [[ -n "${rest}" ]]; do
    id="${rest%%$'\n'*}"
    rest="${rest#"${id}"}"
    rest="${rest#$'\n'}"
    [[ "${perm_ids}" != *$'\n'"${id}"$'\n'* ]] || continue
    role=$((role + 1))
    ids+="${id}"$'\t'"역할 받기"$'\n'
  done
  printf '%s' "${ids}" >"${dir}/event_ids.txt" || die "증거를 쓰지 못함"
  printf 'EVENTS %s (권한 변경 %s · 역할 받기 %s)\n' "$((perm + role))" \
    "${perm}" "${role}"
}

#######################################
# --since 를 검사하고, event_names.txt 의 행마다 조회해 범주별 건수를 낸다.
# Globals:
#   SINCE_PATTERN, ISO_FORMAT, EVIDENCE_ROOT_NAME, names, row_matched
# Arguments:
#   --since, UTC 시각.
# Outputs:
#   표준 출력에 EVENTS 줄(report_counts), 표준 오류에 증거 디렉터리 이름 ·
#   90일 경고.
#######################################
main() {
  local since now dir cutoff row source name region n=0 summary=""
  if [[ "$#" -ne 2 || "$1" != --since ]]; then
    die "사용: events.sh --since <YYYY-MM-DDTHH:MM:SSZ>"
  fi
  since="$2"
  # 꼴 밖 값은 되울리지 않는다(경로 등이 표준 오류에 나오지 않게).
  [[ "${since}" =~ ${SINCE_PATTERN} ]] ||
    die "--since 는 UTC 「YYYY-MM-DDTHH:MM:SSZ」 꼴이어야 함"
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
  for row in "${names[@]}"; do
    n=$((n + 1))
    source="${row%%$'\t'*}"
    region="${row##*$'\t'}"
    name="${row#*$'\t'}"
    name="${name%%$'\t'*}"
    lookup_row "${dir}" "${n}" "${source}" "${name}" "${region}" "${since}"
    summary+="${row}"$'\t'"${row_matched}"$'\n'
  done
  printf '# 출처\t이름\t리전\t맞은 수 — --since %s\n%s' "${since}" \
    "${summary}" >"${dir}/summary.tsv" || die "증거를 쓰지 못함"
  report_counts "${dir}"
}

main "$@"
