#!/usr/bin/env bash
#
# 단계 묶음의 동작을 IAM 정책 시뮬레이터로 다시 판정한다(SCP 재판정 — 계획
# M17a · 설계 13-1 재판정 규칙). 읽기만 하고 무료다.
#
# 호출 주체는 caller identity(지금은 kty IAM 사용자 — 결정 44 ②)다. 묶음
# 파일(data/stage_actions/<묶음>.tsv)의 행(동작 · 리전 · 자원 패턴)마다
# `aws iam simulate-principal-policy` 를 한 번 부르고, 리전은 문맥 키
# aws:RequestedRegion 으로 넣는다. 요청은 증거 디렉터리 안의 파일로 만들어
# --cli-input-json file://… 로 넘긴다 — ARN · 계정 ID 가 argv 에 오르지 않는다.
# 판정 = EvalDecision 이 allowed 면 허용, implicitDeny · explicitDeny 면 거부.
# 응답의 AllowedByOrganizations 가 True · False 가 아닌 행(None 등)이 있으면
# 판정은 그대로 두고 그 행 수를 표준 오류와 report.txt 에 한 줄로 알린다:
#   조직 판정 없음 <n> 행(AllowedByOrganizations 가 True · False 아님) —
#   판정은 EvalDecision 그대로
#
# 판정은 같은 묶음의 앞 기준과 비교한다. 앞 기준 = 같은 묶음의 가장 최근
# 승인 결과(--accept), 없으면 baseline.tsv 의 그 묶음 행(설계 13-1). 기준에
# 없는 (동작, 리전)도 바뀜으로 센다(kty 58차) — 그래서 묶음마다 첫 실행은
# 보통 종료 3 이다.
#
# 표준 출력(계약): 첫 줄 하나와 13-1 덧붙임 줄(행마다 — 마크다운 표 행:
# 동작 · 리전 · 판정 · 날짜 시각(KST) · 묶음). 계정 ID · ARN · 사용자 이름은
# 표준 출력 · 표준 오류에 적지 않는다. 원본은 증거 디렉터리에만 둔다. 앞
# 기준을 읽는 cat · 표지를 옮기는 mv 가 실패하면 그 명령의 진단(로컬 절대
# 경로가 든)은 버리고 die 문구가 원인을 말한다.
#   SCP RECHECK OK <판정한 행 수>
#   SCP RECHECK DENIED <거부 행 수>
#   SCP RECHECK CHANGED <앞 기준과 다른 행 수>
#
# 판정은 모든 증거(result.tsv · reference.tsv · report.txt)를 쓴 뒤 마지막에
# 완료 표지 complete.txt(「UTC 시각<탭>result.tsv sha256」 한 줄 — 임시
# 이름에 다 쓴 뒤 옮김)를 남긴다. 표지가 없거나 꼴이 틀리거나, 표지의
# 해시와 지금의 result.tsv 가 다른 판정은 승인할 수 없고 앞 기준도 되지
# 않는다 — kty 가 본 판정 그대로만 기준이 된다. 판정의 증거 디렉터리 이름은
# 표준 오류로 알린다(kty 에게 보고하는 이름).
#
# 사용: scp-recheck.sh <묶음>
#       scp-recheck.sh --accept <묶음> --dir <증거 디렉터리 이름> --reason <이유>
#         kty 가 보고받은 그 판정(이름으로 고름)을 그 묶음의 다음 비교 기준으로
#         승인한다. 이유는 kty 답 원문(한 줄 · 탭 없이)이다. 승인 시각 · 판정
#         때 result.tsv 해시(완료 표지의 sha256) · 이유가 그 디렉터리의
#         accepted.tsv 에 남고, 승인한 디렉터리 이름을 표준 오류로 알린다.
#         표준 출력은 「SCP RECHECK ACCEPTED <행 수>」 한 줄이다. 끝나지 않은
#         판정 · 판정 뒤 result.tsv 가 바뀐 판정 · 이미 승인한 판정 · 이미
#         승인된(또는 승인 여부를 볼 수 없는) 더 최근 판정보다 오래된
#         판정은 승인하지 않는다(종료 2).
#         거부가 든 결과는 승인해도 다음 판정이 종료 1 이다.
#       앞 기준(승인 결과)을 고를 때 승인 기록이 있는지 볼 수 없는 디렉터리
#       (검색 불가)도 건너뛰지 않는다 — 읽을 때 증거 경로 검사가 종료 2.
#       앞 기준을 읽을 때는 증거 경로 검사에 더해 complete.txt ·
#       accepted.tsv · result.tsv · sts.txt 가 링크 아닌 0600 일반 파일인지
#       (내용 · 해시를 읽기 전에), 꼴(열 수 · 값 · 중복 (동작, 리전) 없음),
#       result.tsv 와 승인 기록의 해시가 완료 표지의 판정 때 해시와 같은지,
#       그 판정의 호출 주체 계정이 이번과 같은지 본다 — 어긋나면 종료 2.
# 종료 코드: 0 OK / 1 DENIED(바뀜과 겹쳐도 1) / 3 CHANGED(거부 없음) /
#            2 검사 불가(사용법 · 묶음 없음 · 증거 경로 · 호출 실패 · 호출
#            자체의 AccessDenied · 응답 꼴 · IAM 사용자가 아닌 주체 · 앞
#            기준 손상 · 계정 다름) —
#            OK 로 넘어가지 않는다(fail-closed).

set -uo pipefail
# 증거 파일(요청 · 응답 원본)은 본인만 읽는다.
umask 077

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly STAGE_DIR="${SCRIPT_DIR}/data/stage_actions"
readonly BASELINE="${STAGE_DIR}/baseline.tsv"
readonly BUNDLE_PATTERN='^[A-Za-z0-9][A-Za-z0-9-]*$'
readonly ACTION_PATTERN='^[a-z0-9-]+:[A-Za-z0-9]+$'
readonly REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]$'
# 자원 패턴의 계정 자리. 판정 때 caller identity 의 계정 ID 로 바꾼다.
# shellcheck disable=SC2016 # 펼치지 않는 글자 그대로다.
readonly ACCOUNT_PLACEHOLDER='${ACCOUNT}'
# 증거 디렉터리 이름 = 「scp-<묶음>-<UTC 시각>」. 도구 접두로 다른 도구
# (events · M17b collect 등)의 디렉터리와 이름이 겹치지 않는다.
readonly DIR_PREFIX='scp'
# 증거 디렉터리 이름의 시각 부분(evidence::new_dir 의 UTC 시각).
STAMP_GLOB='[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]T'
STAMP_GLOB+='[0-9][0-9][0-9][0-9][0-9][0-9]Z'
readonly STAMP_GLOB
# 호출 주체가 IAM 사용자인 ARN(경로 포함).
readonly USER_ARN_PATTERN='^arn:aws:iam::[0-9]{12}:user/[A-Za-z0-9+=,.@_/-]+$'
# 증거에 남기는 UTC 시각(완료 표지 · 승인 기록).
readonly UTC_PATTERN='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
readonly SHA256_PATTERN='^[0-9a-f]{64}$'
SIM_QUERY='EvaluationResults[].[EvalActionName,EvalDecision,'
SIM_QUERY+='OrganizationsDecisionDetail.AllowedByOrganizations]'
readonly SIM_QUERY

# shellcheck source=infra/terraform/scripts/precheck/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh" || {
  printf 'scp-recheck: 검사 불가: 증거 경로 함수를 읽지 못함\n' >&2
  exit 2
}

# 묶음 행 「동작<탭>리전<탭>자원 패턴」.
rows=()
# 앞 기준 「동작<탭>리전<탭>판정(allow · deny)」 줄들과 그 출처.
reference=""
reference_source=""
# 앞 기준이 승인 결과면 그 증거 디렉터리(계정 대조에 씀).
reference_dir=""
# read_result_reference 가 센 결과 행 수 · 거부 행 수.
result_rows=0
result_denied=0
# 호출 주체(값은 출력하지 않는다).
caller_arn=""
account=""
# judge_row 가 남기는 행 판정.
row_decision=""
row_line=""

#######################################
# 검사를 이어갈 수 없을 때 멈춘다(OK 로 넘어가지 않는다).
# Arguments:
#   설명.
#######################################
die() {
  printf 'scp-recheck: 검사 불가: %s\n' "$*" >&2
  exit 2
}

#######################################
# 값이 JSON 문자열 · TSV 칸에 그대로 들어가도 되는지 본다(큰따옴표 ·
# 역슬래시 · 제어 문자 금지).
# Arguments:
#   값.
# Returns:
#   들어가도 되면 0.
#######################################
is_plain() {
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
tsv_ok() {
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
file_sha256() {
  local out
  out="$(sha256sum -- "$1" 2>/dev/null || shasum -a 256 -- "$1" 2>/dev/null)" ||
    return 1
  out="${out%% *}"
  [[ "${out}" =~ ${SHA256_PATTERN} ]] || return 1
  printf '%s\n' "${out}"
}

#######################################
# 묶음 파일을 읽어 rows 에 담는다. 「#」 으로 시작하는 줄은 주석, 빈 줄은
# 건너뛴다. 열 수 · 동작 · 리전 꼴이 틀리거나, (동작, 리전)이 겹치거나, 행이
# 없으면 멈춘다.
# Globals:
#   BUNDLE_PATTERN, STAGE_DIR, ACTION_PATTERN, REGION_PATTERN, rows
# Arguments:
#   묶음 이름.
#######################################
load_bundle() {
  local bundle="$1" file line action region resource seen=$'\n'
  # 꼴 밖 이름(경로 등)은 되울리지 않는다 — 셸이 펼친 HOME 경로가 표준 오류에
  # 나오지 않게.
  [[ "${bundle}" =~ ${BUNDLE_PATTERN} ]] ||
    die "묶음 이름이 아님(영문 · 숫자 · 「-」 — 경로가 아니라 이름)"
  file="${STAGE_DIR}/${bundle}.tsv"
  if [[ "${bundle}" == baseline || ! -f "${file}" || -L "${file}" ]]; then
    die "묶음 없음: ${bundle}"
  fi
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" || "${line}" == '#'* ]] && continue
    tsv_ok "${line}" 3 || die "묶음 ${bundle} 의 행 꼴이 틀림: ${line}"
    action="${line%%$'\t'*}"
    resource="${line#*$'\t'}"
    region="${resource%%$'\t'*}"
    resource="${resource#*$'\t'}"
    if ! [[ "${action}" =~ ${ACTION_PATTERN} &&
      "${region}" =~ ${REGION_PATTERN} ]] || ! is_plain "${resource}"; then
      die "묶음 ${bundle} 의 행 꼴이 틀림: ${line}"
    fi
    if [[ "${seen}" == *$'\n'"${action} ${region}"$'\n'* ]]; then
      die "묶음 ${bundle} 에 ${action} ${region} 이 겹침"
    fi
    seen+="${action} ${region}"$'\n'
    rows+=("${action}"$'\t'"${region}"$'\t'"${resource}")
  done <"${file}" || die "묶음 파일을 읽지 못함: ${bundle}"
  [[ "${#rows[@]}" -gt 0 ]] || die "묶음 ${bundle} 에 행이 없음"
}

#######################################
# 증거 디렉터리 이름의 비교 키를 낸다 — 시각 숫자 14자리 + 같은 초
# 순번(없으면 1). 로캘과 무관하게 숫자로 비교한다.
# Globals:
#   DIR_PREFIX
# Arguments:
#   묶음 이름, 「scp-<묶음>-<시각>[-<순번>]」 디렉터리 경로.
# Outputs:
#   15자리 숫자.
#######################################
dir_key() {
  local tail="${2##*/}"
  tail="${tail#"${DIR_PREFIX}-$1"-}"
  if [[ "${tail}" == *Z-? ]]; then
    printf '%s\n' "${tail:0:8}${tail:9:6}${tail: -1}"
  else
    printf '%s\n' "${tail:0:8}${tail:9:6}1"
  fi
}

#######################################
# 같은 묶음의 가장 최근 scp 증거 디렉터리를 찾는다. 다른 도구의 디렉터리와
# 이름이 겹치는 묶음(O7f 와 O7f-stop2)은 섞지 않는다 — 「scp-<묶음>」 바로
# 뒤가 시각이어야 한다. 파일 이름을 주면 그 파일이 확실히 없는 디렉터리만
# 건너뛴다 — 있는지 볼 수 없는 것(검색할 수 없는 디렉터리 · 디렉터리가
# 아닌 것)은 후보로 남겨, 읽을 때 검사가 거부한다(종료 2).
# Globals:
#   DIR_PREFIX, STAMP_GLOB
# Arguments:
#   묶음 이름, [그 안에 있어야 할 파일 이름].
# Outputs:
#   디렉터리 경로(없으면 빈 출력).
# Returns:
#   루트를 정할 수 없으면 2.
#######################################
latest_dir() {
  local bundle="$1" required="${2:-}" root candidate file key
  local best="" best_key=0
  root="$(evidence::root)" || return 2
  [[ -d "${root}" ]] || return 0
  # shellcheck disable=SC2086,SC2231 # STAMP_GLOB 은 펼칠 글롭이다.
  for candidate in "${root}/${DIR_PREFIX}-${bundle}-"${STAMP_GLOB} \
    "${root}/${DIR_PREFIX}-${bundle}-"${STAMP_GLOB}-[2-9]; do
    [[ -e "${candidate}" || -L "${candidate}" ]] || continue
    # 확실히 없을 때 = 검색할 수 있는 디렉터리인데 파일도 링크(대상 없는
    # 링크 포함)도 없을 때. 검색할 수 없으면 -e 가 늘 거짓인데, 그때
    # 건너뛰면 앞 기준이 조용히 이전 승인 · baseline 으로 바뀐다(장부 r3 7).
    file="${candidate}/${required}"
    if [[ -n "${required}" && -d "${candidate}" && -x "${candidate}" &&
      ! -e "${file}" && ! -L "${file}" ]]; then
      continue
    fi
    key="$(dir_key "${bundle}" "${candidate}")"
    if (( 10#${key} > 10#${best_key} )); then
      best="${candidate}"
      best_key="${key}"
    fi
  done
  [[ -z "${best}" ]] || printf '%s\n' "${best}"
}

#######################################
# 판정 결과 파일(result.tsv — 동작 · 리전 · 판정 · 시뮬레이터 판정 ·
# AllowedByOrganizations)을 검사해 앞 기준 줄로 옮긴다. 링크 아닌 0600
# 일반 파일 · 다섯 칸 · 값 꼴 · 판정과 시뮬레이터 판정의 짝 · (동작, 리전)
# 중복 없음 · 행 1개 이상이어야 한다.
# Globals:
#   reference, result_rows, result_denied, ACTION_PATTERN, REGION_PATTERN
# Arguments:
#   증거 디렉터리.
#######################################
read_result_reference() {
  local file="$1/result.tsv" line action region decision sim orgs rest
  local seen=$'\n'
  evidence::check_file "${file}" || exit 2
  reference=""
  result_rows=0
  result_denied=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    tsv_ok "${line}" 5 || die "결과 파일의 꼴이 틀림(${1##*/}/result.tsv)"
    action="${line%%$'\t'*}"
    rest="${line#*$'\t'}"
    region="${rest%%$'\t'*}"
    rest="${rest#*$'\t'}"
    decision="${rest%%$'\t'*}"
    rest="${rest#*$'\t'}"
    sim="${rest%%$'\t'*}"
    orgs="${rest#*$'\t'}"
    if ! [[ "${action}" =~ ${ACTION_PATTERN} &&
      "${region}" =~ ${REGION_PATTERN} ]] ||
      [[ "${decision}:${sim}" != allow:allowed &&
        "${decision}:${sim}" != deny:implicitDeny &&
        "${decision}:${sim}" != deny:explicitDeny ]] ||
      ! is_plain "${orgs}"; then
      die "결과 파일의 꼴이 틀림(${1##*/}/result.tsv)"
    fi
    if [[ "${seen}" == *$'\n'"${action} ${region}"$'\n'* ]]; then
      die "결과 파일에 (동작, 리전)이 겹침(${1##*/}/result.tsv)"
    fi
    seen+="${action} ${region}"$'\n'
    reference+="${action}"$'\t'"${region}"$'\t'"${decision}"$'\n'
    result_rows=$((result_rows + 1))
    if [[ "${decision}" == deny ]]; then
      result_denied=$((result_denied + 1))
    fi
  done <"${file}" || die "결과 파일을 읽지 못함(${1##*/}/result.tsv)"
  [[ "${result_rows}" -gt 0 ]] || die "결과 파일이 비었음(${1##*/}/result.tsv)"
}

#######################################
# 판정이 끝났고 결과 파일이 판정 때 그대로인지 본다. 완료 표지(complete.txt
# — 「UTC 시각<탭>result.tsv sha256」 한 줄)의 꼴을 보고, 그 해시를 지금의
# result.tsv 와 대조한다. 두 파일 모두 링크 아닌 0600 일반 파일인지를 내용 ·
# 해시를 읽기 전에 본다(FIFO 등에서 걸리지 않게).
# Globals:
#   UTC_PATTERN, SHA256_PATTERN
# Arguments:
#   증거 디렉터리.
# Outputs:
#   판정 때 result.tsv 의 sha256.
#######################################
require_complete() {
  local dir="$1" line at sum current
  evidence::check_file "${dir}/complete.txt" || exit 2
  line="$(cat -- "${dir}/complete.txt" 2>/dev/null)" ||
    die "완료 표지를 읽지 못함(${dir##*/})"
  at="${line%%$'\t'*}"
  sum="${line#*$'\t'}"
  [[ "${at}" =~ ${UTC_PATTERN} && "${sum}" =~ ${SHA256_PATTERN} ]] ||
    die "완료 표지의 꼴이 틀림(${dir##*/}/complete.txt)"
  evidence::check_file "${dir}/result.tsv" || exit 2
  current="$(file_sha256 "${dir}/result.tsv")" ||
    die "결과 파일의 해시를 계산하지 못함(${dir##*/})"
  [[ "${current}" == "${sum}" ]] ||
    die "판정 뒤 결과 파일이 바뀜(${dir##*/}/result.tsv — 해시 다름)"
  printf '%s\n' "${sum}"
}

#######################################
# 승인 기록(accepted.tsv — UTC 시각 · result.tsv sha256 · 이유 한 줄)을
# 검사하고, 그 해시가 판정 때의 해시(완료 표지)와 같은지 본다.
# Globals:
#   UTC_PATTERN, SHA256_PATTERN
# Arguments:
#   증거 디렉터리, 판정 때 result.tsv 의 sha256(require_complete 가 낸 것).
#######################################
check_acceptance() {
  local dir="$1" judged="$2" file="$1/accepted.tsv" line at sum reason
  evidence::check_file "${file}" || exit 2
  line="$(cat -- "${file}" 2>/dev/null)" ||
    die "승인 기록을 읽지 못함(${dir##*/})"
  if [[ "${line}" == *$'\n'* ]] || ! tsv_ok "${line}" 3; then
    die "승인 기록의 꼴이 틀림(${dir##*/}/accepted.tsv)"
  fi
  at="${line%%$'\t'*}"
  reason="${line#*$'\t'}"
  sum="${reason%%$'\t'*}"
  reason="${reason#*$'\t'}"
  if ! [[ "${at}" =~ ${UTC_PATTERN} && "${sum}" =~ ${SHA256_PATTERN} ]] ||
    [[ "${reason}" == *[[:cntrl:]]* ]]; then
    die "승인 기록의 꼴이 틀림(${dir##*/}/accepted.tsv)"
  fi
  [[ "${sum}" == "${judged}" ]] ||
    die "승인 기록의 해시가 판정 때와 다름(${dir##*/}/accepted.tsv)"
}

#######################################
# baseline.tsv(묶음 · 동작 · 리전 · 판정 · 13-1 판정 시각)에서 그 묶음 행을
# 앞 기준 줄로 옮긴다.
# Globals:
#   BASELINE, reference
# Arguments:
#   묶음 이름.
#######################################
read_baseline_reference() {
  local bundle="$1" line name action region decision rest
  [[ -f "${BASELINE}" && ! -L "${BASELINE}" ]] || die "baseline.tsv 가 없음"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" || "${line}" == '#'* ]] && continue
    tsv_ok "${line}" 5 || die "baseline.tsv 의 행 꼴이 틀림: ${line}"
    name="${line%%$'\t'*}"
    rest="${line#*$'\t'}"
    action="${rest%%$'\t'*}"
    rest="${rest#*$'\t'}"
    region="${rest%%$'\t'*}"
    rest="${rest#*$'\t'}"
    decision="${rest%%$'\t'*}"
    if [[ "${decision}" != allow && "${decision}" != deny ]]; then
      die "baseline.tsv 의 행 꼴이 틀림: ${line}"
    fi
    [[ "${name}" == "${bundle}" ]] || continue
    reference+="${action}"$'\t'"${region}"$'\t'"${decision}"$'\n'
  done <"${BASELINE}" || die "baseline.tsv 를 읽지 못함"
}

#######################################
# 앞 기준을 정한다 — 같은 묶음의 가장 최근 승인 결과, 없으면 baseline.tsv.
# 승인 결과는 증거 경로 검사 · 완료 표지(판정 때 해시와 결과 대조) · 승인
# 기록(같은 해시) · 결과 파일 검사를 모두 통과해야 한다. 계정 대조는 호출
# 주체를 확인한 뒤 check_reference_account 가 한다. 앞 기준 줄(reference)은
# 부르는 read_baseline_reference · read_result_reference 가 채운다.
# Globals:
#   reference_source, reference_dir
# Arguments:
#   묶음 이름.
#######################################
load_reference() {
  local bundle="$1" approved judged
  approved="$(latest_dir "${bundle}" accepted.tsv)" || exit 2
  if [[ -z "${approved}" ]]; then
    read_baseline_reference "${bundle}"
    reference_source='baseline.tsv'
    return
  fi
  evidence::check_dir "${approved}" || exit 2
  judged="$(require_complete "${approved}")" || exit 2
  check_acceptance "${approved}" "${judged}"
  read_result_reference "${approved}"
  reference_dir="${approved}"
  reference_source="승인 기준 ${approved##*/}"
}

#######################################
# 앞 기준(승인 결과)을 만든 판정의 호출 주체 계정이 이번 계정과 같은지
# 본다. 값은 출력하지 않는다.
# Globals:
#   reference_dir, account
#######################################
check_reference_account() {
  local file line
  [[ -n "${reference_dir}" ]] || return 0
  file="${reference_dir}/sts.txt"
  evidence::check_file "${file}" || exit 2
  line="$(cat -- "${file}" 2>/dev/null)" ||
    die "앞 기준의 호출 주체 기록을 읽지 못함"
  if [[ "${line}" == *$'\n'* ]] || ! tsv_ok "${line}" 2 ||
    [[ "${line#*$'\t'}" != "${account}" ]]; then
    die "앞 기준(${reference_dir##*/})의 호출 주체 계정이 이번과 다름"
  fi
}

#######################################
# 앞 기준에서 (동작, 리전)의 판정을 찾는다.
# Globals:
#   reference
# Arguments:
#   동작, 리전.
# Outputs:
#   allow · deny(없으면 빈 출력).
#######################################
reference_decision() {
  local key=$'\n'"$1"$'\t'"$2"$'\t' haystack=$'\n'"${reference}" rest
  [[ "${haystack}" == *"${key}"* ]] || return 0
  rest="${haystack#*"${key}"}"
  printf '%s\n' "${rest%%$'\n'*}"
}

#######################################
# 호출 주체를 확인한다. 값은 변수에만 두고 원본은 증거 디렉터리에 남긴다.
# IAM 사용자가 아니면 멈춘다(시뮬레이터는 역할 세션 ARN 을 받지 않는다).
# Globals:
#   caller_arn, account, USER_ARN_PATTERN
# Arguments:
#   증거 디렉터리.
#######################################
caller_identity() {
  local dir="$1" out
  out="$(aws sts get-caller-identity --query '[Arn,Account]' --output text \
    2>"${dir}/sts.err")" ||
    die "호출 주체를 확인하지 못함(증거 디렉터리의 sts.err)"
  printf '%s\n' "${out}" >"${dir}/sts.txt" || die "증거를 쓰지 못함"
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
# 행 하나의 시뮬레이터 요청 파일을 쓴다(값은 load_bundle · caller_identity
# 가 검사한 것만 — 큰따옴표 · 역슬래시 없음).
# Globals:
#   caller_arn
# Arguments:
#   요청 파일, 동작, 리전, 자원 ARN.
#######################################
write_request() {
  local file="$1" action="$2" region="$3" resource="$4" format
  format='{"PolicySourceArn":"%s","ActionNames":["%s"],"ResourceArns":["%s"],'
  format+='"ContextEntries":[{"ContextKeyName":"aws:RequestedRegion",'
  format+='"ContextKeyValues":["%s"],"ContextKeyType":"string"}]}\n'
  # shellcheck disable=SC2059 # 형식 문자열은 위의 고정 값이다.
  printf "${format}" "${caller_arn}" "${action}" "${resource}" "${region}" \
    >"${file}" || die "증거를 쓰지 못함"
}

#######################################
# 행 하나를 판정한다. 요청 · 응답 원본은 증거 디렉터리의 req-<n>.json ·
# res-<n>.txt · res-<n>.err 에 남는다.
# Globals:
#   ACCOUNT_PLACEHOLDER, account, SIM_QUERY, row_decision, row_line
# Arguments:
#   증거 디렉터리, 행 번호, 동작, 리전, 자원 패턴.
#######################################
judge_row() {
  local dir="$1" n="$2" action="$3" region="$4" resource="$5" out
  local got_action decision rest
  resource="${resource//${ACCOUNT_PLACEHOLDER}/${account}}"
  write_request "${dir}/req-${n}.json" "${action}" "${region}" "${resource}"
  if ! out="$(aws iam simulate-principal-policy \
    --cli-input-json "file://${dir}/req-${n}.json" --query "${SIM_QUERY}" \
    --output text 2>"${dir}/res-${n}.err")"; then
    if grep -qF 'AccessDenied' -- "${dir}/res-${n}.err"; then
      die "시뮬레이터 호출 자체가 거부됨(AccessDenied — res-${n}.err)"
    fi
    die "시뮬레이터 호출 실패(res-${n}.err)"
  fi
  printf '%s\n' "${out}" >"${dir}/res-${n}.txt" || die "증거를 쓰지 못함"
  if [[ "${out}" == *$'\n'* ]] || ! tsv_ok "${out}" 3; then
    die "시뮬레이터 응답의 꼴이 다름(res-${n}.txt)"
  fi
  got_action="${out%%$'\t'*}"
  rest="${out#*$'\t'}"
  decision="${rest%%$'\t'*}"
  rest="${rest#*$'\t'}"
  [[ "${got_action}" == "${action}" ]] ||
    die "시뮬레이터 응답의 꼴이 다름(res-${n}.txt)"
  case "${decision}" in
    allowed) row_decision=allow ;;
    implicitDeny | explicitDeny) row_decision=deny ;;
    *) die "시뮬레이터 판정을 모름(res-${n}.txt)" ;;
  esac
  row_line="${action}"$'\t'"${region}"$'\t'"${row_decision}"$'\t'
  row_line+="${decision}"$'\t'"${rest}"
}

#######################################
# allow · deny 를 13-1 표의 말로 옮긴다.
# Arguments:
#   allow · deny · 빈 값.
# Outputs:
#   허용 · 거부 · 없음.
#######################################
decision_word() {
  case "$1" in
    allow) printf '허용\n' ;;
    deny) printf '거부\n' ;;
    *) printf '없음\n' ;;
  esac
}

#######################################
# 판정 증거를 쓰고, 다 쓴 뒤에만 완료 표지(complete.txt — 「UTC 시각<탭>
# result.tsv sha256」)를 남긴다. 표지가 없는 판정은 승인 · 앞 기준이 될 수
# 없다. 표지의 시각 · 해시를 먼저 구하고 임시 이름에 다 쓴 뒤 옮기므로,
# 어디서 실패해도 빈 · 반쪽 표지가 남지 않는다.
# Globals:
#   reference_source, reference
# Arguments:
#   증거 디렉터리, 결과 줄들, 첫 줄, report.txt 에서 첫 줄 뒤의 줄들(13-1
#   덧붙임 줄 · 조직 판정 없음 줄).
#######################################
save_judgment() {
  local dir="$1" result="$2" first="$3" table="$4" at sum
  if ! printf '%s' "${result}" >"${dir}/result.tsv" ||
    ! printf '# 비교 기준: %s\n%s' "${reference_source}" "${reference}" \
      >"${dir}/reference.tsv" ||
    ! printf '%s\n%s' "${first}" "${table}" >"${dir}/report.txt"; then
    die "증거를 쓰지 못함(${dir##*/})"
  fi
  at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" || die "완료 표지의 시각을 읽지 못함"
  sum="$(file_sha256 "${dir}/result.tsv")" ||
    die "결과 파일의 해시를 계산하지 못함(${dir##*/})"
  if ! printf '%s\t%s\n' "${at}" "${sum}" >"${dir}/complete.txt.tmp" ||
    ! mv -- "${dir}/complete.txt.tmp" "${dir}/complete.txt" 2>/dev/null; then
    die "완료 표지를 쓰지 못함(${dir##*/})"
  fi
}

#######################################
# 묶음을 판정하고 앞 기준과 비교해 결과를 적는다.
# Globals:
#   DIR_PREFIX, EVIDENCE_ROOT_NAME, reference_source, rows, row_line,
#   row_decision
# Arguments:
#   묶음 이름.
# Returns:
#   0 OK · 1 DENIED · 3 CHANGED(멈출 일은 die 로 2).
#######################################
judge_bundle() {
  local bundle="$1" dir stamp row action region resource previous note
  local n=0 denied=0 changed=0 unorganized=0 result="" table="" first
  local orgs_note=""
  load_bundle "${bundle}"
  load_reference "${bundle}"
  dir="$(evidence::new_dir "${DIR_PREFIX}-${bundle}")" || exit 2
  printf 'scp-recheck: 증거 ~/%s/%s · 비교 기준 %s\n' "${EVIDENCE_ROOT_NAME}" \
    "${dir##*/}" "${reference_source}" >&2
  caller_identity "${dir}"
  check_reference_account
  stamp="$(TZ=KST-9 date '+%Y-%m-%d %H:%M KST')" || die "시각을 읽지 못함"
  for row in "${rows[@]}"; do
    n=$((n + 1))
    action="${row%%$'\t'*}"
    resource="${row#*$'\t'}"
    region="${resource%%$'\t'*}"
    resource="${resource#*$'\t'}"
    judge_row "${dir}" "${n}" "${action}" "${region}" "${resource}"
    result+="${row_line}"$'\n'
    [[ "${row_decision}" == deny ]] && denied=$((denied + 1))
    # 셋째 칸(AllowedByOrganizations)이 True · False 가 아닌 행은 판정을
    # 바꾸지 않고 건수만 드러낸다(장부 r3 9).
    [[ "${row_line##*$'\t'}" == True || "${row_line##*$'\t'}" == False ]] ||
      unorganized=$((unorganized + 1))
    previous="$(reference_decision "${action}" "${region}")"
    note=""
    if [[ "${previous}" != "${row_decision}" ]]; then
      changed=$((changed + 1))
      note=" · 바뀜(앞 기준 $(decision_word "${previous}"))"
    fi
    table+="| \`${action}\` | ${region} | $(decision_word "${row_decision}") |"
    table+=" ${stamp} | ${bundle} 재판정${note} |"$'\n'
  done
  first="SCP RECHECK OK ${n}"
  [[ "${changed}" -gt 0 ]] && first="SCP RECHECK CHANGED ${changed}"
  [[ "${denied}" -gt 0 ]] && first="SCP RECHECK DENIED ${denied}"
  if [[ "${unorganized}" -gt 0 ]]; then
    orgs_note="조직 판정 없음 ${unorganized} 행(AllowedByOrganizations 가"
    orgs_note+=" True · False 아님) — 판정은 EvalDecision 그대로"$'\n'
  fi
  save_judgment "${dir}" "${result}" "${first}" "${table}${orgs_note}"
  [[ -z "${orgs_note}" ]] || printf 'scp-recheck: %s' "${orgs_note}" >&2
  printf '%s\n%s' "${first}" "${table}"
  [[ "${denied}" -gt 0 ]] && return 1
  [[ "${changed}" -gt 0 ]] && return 3
  return 0
}

#######################################
# kty 가 보고받은 판정(증거 디렉터리 이름)을 그 묶음의 다음 비교 기준으로
# 승인한다. 완료 표지(결과가 판정 때 그대로인지) · 결과 파일 검사를 통과해야
# 하고, 이미 승인했거나 이미 승인된(또는 승인 여부를 볼 수 없는) 더 최근
# 판정보다 오래되면 승인하지 않는다. 승인 기록에는 판정 때의 해시(완료
# 표지의 것)를 남긴다.
# Globals:
#   DIR_PREFIX, EVIDENCE_ROOT_NAME, result_rows, result_denied
# Arguments:
#   묶음 이름, 증거 디렉터리 이름, 이유(kty 답 원문 — 한 줄 · 탭 없이).
#######################################
accept_bundle() {
  local bundle="$1" name="$2" reason="$3" root dir latest judged at
  load_bundle "${bundle}"
  if [[ -z "${reason}" || "${reason}" == *[[:cntrl:]]* ]]; then
    die "이유는 비어 있지 않은 한 줄(탭 없이)이어야 함"
  fi
  # 꼴 밖 이름(경로 등)은 되울리지 않는다.
  [[ "${name}" =~ ^${DIR_PREFIX}-${bundle}-[0-9]{8}T[0-9]{6}Z(-[2-9])?$ ]] ||
    die "${bundle} 묶음의 판정 디렉터리 이름이 아님(경로가 아니라" \
      "${DIR_PREFIX}-${bundle}-<UTC 시각> 꼴의 이름)"
  root="$(evidence::root)" || exit 2
  dir="${root}/${name}"
  [[ -e "${dir}" || -L "${dir}" ]] || die "판정이 없음: ${name}"
  evidence::check_dir "${dir}" || exit 2
  judged="$(require_complete "${dir}")" || exit 2
  read_result_reference "${dir}"
  [[ ! -e "${dir}/accepted.tsv" && ! -L "${dir}/accepted.tsv" ]] ||
    die "이미 승인됨: ${name}"
  latest="$(latest_dir "${bundle}" accepted.tsv)" || exit 2
  if [[ -n "${latest}" ]] && (( 10#$(dir_key "${bundle}" "${latest}") > \
    10#$(dir_key "${bundle}" "${dir}") )); then
    die "더 최근 판정(${latest##*/})이 이미 승인됨(또는 승인 여부를 볼 수" \
      "없음): ${name}"
  fi
  # 시각을 먼저 구한다 — 실패했을 때 시각 칸이 빈 승인 기록이 남지 않게.
  at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" || die "승인 시각을 읽지 못함"
  set -o noclobber
  printf '%s\t%s\t%s\n' "${at}" "${judged}" "${reason}" \
    >"${dir}/accepted.tsv" || die "승인 기록을 쓰지 못함(${name})"
  set +o noclobber
  printf 'scp-recheck: 승인 ~/%s/%s\n' "${EVIDENCE_ROOT_NAME}" "${name}" >&2
  printf 'SCP RECHECK ACCEPTED %s\n' "${result_rows}"
  if [[ "${result_denied}" -gt 0 ]]; then
    printf 'scp-recheck: 거부 %s행이 든 결과를 승인함 — 다음 판정도 종료 1\n' \
      "${result_denied}" >&2
  fi
}

main() {
  local usage='사용: scp-recheck.sh <묶음> |'
  usage+=' --accept <묶음> --dir <증거 디렉터리 이름> --reason <이유>'
  case "$#:${1:-}" in
    1:-*) die "${usage}" ;;
    1:*) judge_bundle "$1" ;;
    6:--accept)
      [[ "$3" == --dir && "$5" == --reason ]] || die "${usage}"
      accept_bundle "$2" "$4" "$6"
      ;;
    *) die "${usage}" ;;
  esac
}

main "$@"
