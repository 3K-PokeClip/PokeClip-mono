#!/usr/bin/env bash
#
# 단계 직전 점검 2-3 — 알려진 dev 자동 주체에 핵심 prod 자원 권한이 없는지
# AWS 정책 시뮬레이터로 읽기 전용 확인한다(계획 M17b r3.1 2절). 판정 엔진은
# 짜지 않고 AWS 쓰기도 없다. 실제 접근을 시도하지 않는다.
#
# 순서(고정): (0) 사전 조건(단계 · 데이터 · 증거 디렉터리 · 호출 주체) →
# (1) PC0 제외 대조 → (2) PC1 문맥 대조 → (3) 주체 · 대상 해석과 자원 정책
# 읽기 → (4) PC2 주체 대조 → (4') PC-A 갈래 A 대조 → (5) 실제 질문 →
# (6) 기록. PC0 · PC1 이 실패하면 (3) 이후의 호출은 0건이다.
#
# 모든 AWS 호출은 요청 본문을 증거의 req-<n>.json 에 쓰고
# --cli-input-json file://… 로 넘긴다 — argv 에는 ARN · 계정 ID · 이름이
# 없고 --query · --output · --region · --resource-policy 같은 옵션만 있다.
# 시뮬레이터 요청은 모두 SCP 를 뺀다(PolicyExclusionList 가 정확히 scp 하나
# — 결정 5 (가)). 제외가 실제로 먹는지는 PC0 이 확인한다: 호출 주체의 prod
# 상태 버킷 목록 읽기를 운영 측 SCP 가 막는 리전(ap-northeast-1) 문맥으로
# 묻고 allowed 가 아니면 종료 2. PC0 은 리전 값이 행의 리전이 아닌 유일한
# 요청이다. PC1 은 이번 실행이 쓰는 리전마다 같은 질문을 묻는다.
#
# 행 판정(위부터): 허용 → 멈춤(ALLOWED) · 정책에 세션 ARN →
# SESSION_PRINCIPAL_IN_POLICY · 읽기 방법 unread → RESOURCE_POLICY_UNREAD ·
# 갈래 B 에서 역할 주체 × Allow 를 가진 정책 → RESOURCE_POLICY_UNSIMULATED ·
# 거부 + 누락 문맥 → MISSING_CONTEXT · 그 주체의 PC2 실패 →
# POSITIVE_CONTROL_FAILED · 그 밖 → 통과. 주체 · 대상 단위 사유
# (PRINCIPAL_MISSING · TARGET_NONE)는 하나당 1건이다.
#
# 표준 출력(계약): 첫 줄 하나와, 통과가 아닌 행마다 마크다운 표 행
# 「| 주체 키 | 자원 키 | 동작 | 리전 | 판정 | 사유 |」. 계정 ID · ARN · 역할 ·
# 사용자 · 자원 이름은 표준 출력 · 표준 오류에 적지 않는다(증거 디렉터리에만).
#   PRINCIPAL CHECK PASS <판정한 행 수>        종료 0
#   PRINCIPAL CHECK STOP <멈춤 행 수>          종료 1
#   PRINCIPAL CHECK UNDECIDED <판정 불가 건수>  종료 3
#   (첫 줄 없음 — 표준 오류에 「검사 불가」)     종료 2
# 집계는 멈춤 > 판정 불가 > 통과. 종료 2 = 사용법 · 모르는 단계 · 데이터 꼴 ·
# 증거 경로 거부 · IAM 사용자 아닌 호출 주체 · 호출 실패(NoSuchEntity 를 뺀
# 주체 해석 오류 포함) · 호출 자체의 AccessDenied · 응답 꼴 · 정책 파일 꼴 ·
# 존 해석 2건 이상 · PC0 · PC1 실패 · CLI 가 제외를 받지 않음(2.36.12 이상
# 필요) · 조직 판정 False · 자원 정책 읽기의 「없음」 아닌 오류.
#
# 증거 디렉터리 ~/.pokeclip-precheck/principal-<단계>-<UTC 시각>: sts.txt ·
# principals.tsv · targets.tsv · policies.tsv · policy-<n>.json ·
# pca-policy.json(PC-A 합성 정책 — 요청에만 씀) · req-<n>.json · res-<n>.txt ·
# res-<n>.err · result.tsv · report.txt · complete.txt(마지막 — 「UTC
# 시각<탭>result.tsv sha256」). kty 는 종료 3 일 때 approvals.tsv 를 손으로
# 적는다(런북 2-3).
#
# 사용: principal-check.sh <단계>   (단계 = STAGE_ORDER 의 이름)

set -uo pipefail
# 증거 파일(요청 · 응답 원본)은 본인만 읽는다.
umask 077

SCRIPT_DIR="$(cd -P -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly DATA_DIR="${SCRIPT_DIR}/data/principal_sim"
# 2-3 판정이 있는 단계와 그 순서(data_files_test.sh 의 JUDGED_BUNDLES 와
# 순서까지 같다 — 첫 단계 비교는 이 색인으로 한다).
STAGE_ORDER=(5 O7a O7b O7c O7d O7e O7f 8 8a 9 ci-followup)
readonly STAGE_ORDER
readonly DIR_PREFIX='principal'
readonly STAGE_PATTERN='^[A-Za-z0-9][A-Za-z0-9-]*$'
readonly KEY_PATTERN='^[a-z][a-z0-9-]*$'
readonly ACTION_PATTERN='^[a-z0-9-]+:[A-Za-z0-9]+$'
readonly REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]$'
# shellcheck disable=SC2016 # 펼치지 않는 글자 그대로다.
readonly ACCOUNT_PLACEHOLDER='${ACCOUNT}'
readonly USER_ARN_PATTERN='^arn:aws:iam::[0-9]{12}:user/[A-Za-z0-9+=,.@_/-]+$'
readonly SHA256_PATTERN='^[0-9a-f]{64}$'
# PC0 · PC1: 호출 주체의 prod 상태 버킷 목록 읽기. PC0 은 운영 측 SCP 가
# 막는 리전 문맥으로 묻는다(포함이면 거부 · 제외면 허용 — 실측).
readonly PC_ACTION='s3:ListBucket'
readonly PC_BUCKET_ARN='arn:aws:s3:::pokeclip-tfstate-prod-apne2'
readonly PC0_REGION='ap-northeast-1'
# PC-A: 없는 표본 버킷에 대한 옛 OIDC 역할(종류 role)의 목록 읽기.
readonly PCA_SAMPLE_ARN='arn:aws:s3:::pokeclip-prod-m17b-probe-nonexistent-9f3a'
readonly PCA_REGION='ap-northeast-2'

# 단계 · 호출 주체 · 증거(값은 출력하지 않는다).
stage=""
stage_idx=0
caller_arn=""
account=""
evidence_dir=""
# aws_call 이 남기는 요청 번호 · 표준 출력 · 표준 오류 파일.
req_n=0
call_out=""
call_err=""
request_json=""
# 대상 행이 이번 단계에서 도는지(1).
t_active=()
# 단위의 PC2 실패(1) · 갈래(A · B)와 그 기록.
u_pc_failed=()
branch=B
branch_note=""
# 판정 기록과 셈.
result=""
table=""
notes=""
n_judged=0
n_stop=0
n_undecided=0
reason_names=()
reason_counts=()

#######################################
# 검사를 이어갈 수 없을 때 멈춘다(통과로 넘어가지 않는다).
# Arguments:
#   설명.
#######################################
die() {
  printf 'principal-check: 검사 불가: %s\n' "$*" >&2
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
# 탭 구분 줄의 칸 수가 정확하고 빈 칸이 없는지 본다.
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

# shellcheck source=infra/terraform/scripts/precheck/lib/evidence.sh
source "${SCRIPT_DIR}/lib/evidence.sh" || die "lib/evidence.sh 를 읽지 못함"
# shellcheck source=infra/terraform/scripts/precheck/lib/principal_data.sh
source "${SCRIPT_DIR}/lib/principal_data.sh" ||
  die "lib/principal_data.sh 를 읽지 못함"
# shellcheck source=infra/terraform/scripts/precheck/lib/principal_simulate.sh
source "${SCRIPT_DIR}/lib/principal_simulate.sh" ||
  die "lib/principal_simulate.sh 를 읽지 못함"
# shellcheck source=infra/terraform/scripts/precheck/lib/principal_resolve.sh
source "${SCRIPT_DIR}/lib/principal_resolve.sh" ||
  die "lib/principal_resolve.sh 를 읽지 못함"
# shellcheck source=infra/terraform/scripts/precheck/lib/principal_policy.sh
source "${SCRIPT_DIR}/lib/principal_policy.sh" ||
  die "lib/principal_policy.sh 를 읽지 못함"

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
# AWS 호출 하나 — 요청 본문을 req-<n>.json 에 쓰고 --cli-input-json
# file:// · --output text 로 부른다. 표준 출력은 call_out 과 res-<n>.txt,
# 표준 오류는 res-<n>.err 에 남는다.
# Globals:
#   evidence_dir, req_n, call_out, call_err
# Arguments:
#   요청 본문(JSON), aws 인자(서비스 · 동작 · 옵션).
# Returns:
#   aws 의 종료 코드.
#######################################
aws_call() {
  local body="$1" request status
  shift
  req_n=$((req_n + 1))
  request="${evidence_dir}/req-${req_n}.json"
  call_err="${evidence_dir}/res-${req_n}.err"
  printf '%s\n' "${body}" >"${request}" || die "증거를 쓰지 못함"
  call_out="$(aws "$@" --cli-input-json "file://${request}" --output text \
    2>"${call_err}")"
  status=$?
  printf '%s\n' "${call_out}" >"${evidence_dir}/res-${req_n}.txt" ||
    die "증거를 쓰지 못함"
  return "${status}"
}

#######################################
# 마지막 읽기 호출의 실패로 멈춘다 — 호출 자체의 AccessDenied 와 그 밖을
# 가른다.
# Globals:
#   call_err, req_n
# Arguments:
#   무엇을 하던 호출인지.
#######################################
fail_call() {
  if grep -qF 'AccessDenied' -- "${call_err}"; then
    die "$1 호출 자체가 거부됨(AccessDenied — res-${req_n}.err)"
  fi
  die "$1 호출 실패(res-${req_n}.err)"
}

#######################################
# 호출 주체를 확인한다. IAM 사용자가 아니면 멈춘다(시뮬레이터 PC0 · PC1 의
# 주체가 될 수 없다). 값은 변수에만 두고 원본은 sts.txt 에 남긴다.
# Globals:
#   caller_arn, account, evidence_dir, USER_ARN_PATTERN
#######################################
caller_identity() {
  local out
  out="$(aws sts get-caller-identity --query '[Arn,Account]' --output text \
    2>"${evidence_dir}/sts.err")" ||
    die "호출 주체를 확인하지 못함(증거 디렉터리의 sts.err)"
  printf '%s\n' "${out}" >"${evidence_dir}/sts.txt" || die "증거를 쓰지 못함"
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
# 대상 행마다 이번 단계에서 도는지(첫 단계 색인 ≤ 이번 색인) 정한다.
# Globals:
#   t_stage, t_active, stage_idx
#######################################
mark_active() {
  local t
  for t in "${!t_stage[@]}"; do
    t_active[t]=0
    if [[ "$(stage_index "${t_stage[t]}")" -le "${stage_idx}" ]]; then
      t_active[t]=1
    fi
  done
}

#######################################
# (1) PC0 · (2) PC1 — 호출 주체의 prod 상태 버킷 목록 읽기가 SCP 제외
# 요청에서 allowed 인지 본다. PC0 은 ap-northeast-1, PC1 은 이번 실행이 쓰는
# 리전마다(대상 행 · 양성 대조 · PC-A 의 리전).
# Globals:
#   caller_arn, sim_class, t_region, t_active, c_region, PC_*, PCA_REGION
#######################################
ask_scope_controls() {
  local regions=$'\n' region t
  ask "${caller_arn}" "${PC_ACTION}" "${PC_BUCKET_ARN}" "${PC0_REGION}" ""
  [[ "${sim_class}" == allow ]] ||
    die "PC0(SCP 제외 대조)가 허용이 아님 — 제외가 먹지 않았을 수 있음" \
      "(res-${req_n}.txt)"
  for t in "${!t_region[@]}"; do
    [[ "${t_active[t]}" -eq 1 ]] && regions+="${t_region[t]}"$'\n'
  done
  for region in "${c_region[@]}" "${PCA_REGION}"; do
    regions+="${region}"$'\n'
  done
  regions="${regions#$'\n'}"
  local seen=$'\n'
  while [[ -n "${regions}" ]]; do
    region="${regions%%$'\n'*}"
    regions="${regions#*$'\n'}"
    [[ "${seen}" == *$'\n'"${region}"$'\n'* ]] && continue
    seen+="${region}"$'\n'
    ask "${caller_arn}" "${PC_ACTION}" "${PC_BUCKET_ARN}" "${region}" ""
    [[ "${sim_class}" == allow ]] ||
      die "PC1(문맥 대조)가 ${region} 에서 허용이 아님(res-${req_n}.txt)"
  done
}

#######################################
# 주체 키가 단위의 키 목록에 드는지 본다.
# Globals:
#   u_keys
# Arguments:
#   u_* 색인, 주체 키.
# Returns:
#   들면 0.
#######################################
unit_has_key() {
  [[ ",${u_keys[$1]}," == *",$2,"* ]]
}

#######################################
# (4) PC2 — 단위마다 그 주체 키들의 양성 대조 질문(같은 질문은 한 번)이
# 허용인지 본다. 허용이 아니면(불명 포함) 그 단위의 모든 행이
# POSITIVE_CONTROL_FAILED 다.
# Globals:
#   u_arn, u_pc_failed, p_key, c_action, c_region, c_arn, account, sim_class
#######################################
ask_positive_controls() {
  local j i arn asked
  for j in ${u_arn[@]+"${!u_arn[@]}"}; do
    u_pc_failed[j]=0
    asked=$'\n'
    for i in "${!p_key[@]}"; do
      unit_has_key "${j}" "${p_key[i]}" || continue
      arn="${c_arn[i]//${ACCOUNT_PLACEHOLDER}/${account}}"
      [[ "${asked}" == *$'\n'"${c_action[i]} ${c_region[i]} ${arn}"$'\n'* ]] &&
        continue
      asked+="${c_action[i]} ${c_region[i]} ${arn}"$'\n'
      ask "${u_arn[j]}" "${c_action[i]}" "${arn}" "${c_region[i]}" ""
      [[ "${sim_class}" == allow ]] || u_pc_failed[j]=1
    done
  done
}

#######################################
# (4') PC-A — 옛 OIDC 역할(첫 role 종류 주체)로 ⓐ 자원 정책 없음 · ⓑ 그
# 역할을 지명한 합성 정책 + CallerArn 을 묻는다. ⓐ 가 allowed 가 아니고 ⓑ 가
# allowed 일 때만 갈래 A, 아니면 이번 실행은 갈래 B 로 판정한다.
# Globals:
#   p_kind, p_key, u_arn, branch, branch_note, evidence_dir, sim_class
#######################################
ask_branch_control() {
  local i j unit="" policy first
  for i in "${!p_kind[@]}"; do
    [[ "${p_kind[i]}" == role ]] || continue
    for j in ${u_arn[@]+"${!u_arn[@]}"}; do
      unit_has_key "${j}" "${p_key[i]}" && unit="${j}"
    done
    break
  done
  branch=B
  if [[ -z "${unit}" ]]; then
    branch_note='갈래 A 대조 실패 — B 로 판정(대조할 role 주체가 없음)'
    return
  fi
  build_request "${u_arn[unit]}" "${PC_ACTION}" "${PCA_SAMPLE_ARN}" \
    "${PCA_REGION}" "" owner
  simulate ""
  first="${sim_class}"
  policy="${evidence_dir}/pca-policy.json"
  printf '%s%s%s\n' '{"Version":"2012-10-17","Statement":[{"Effect":"Allow",' \
    "\"Principal\":{\"AWS\":\"${u_arn[unit]}\"},\"Action\":\"${PC_ACTION}\"," \
    "\"Resource\":\"${PCA_SAMPLE_ARN}\"}]}" >"${policy}" ||
    die "증거를 쓰지 못함"
  ask "${u_arn[unit]}" "${PC_ACTION}" "${PCA_SAMPLE_ARN}" "${PCA_REGION}" "" \
    "${policy}"
  if [[ "${first}" != allow && "${sim_class}" == allow ]]; then
    branch=A
    branch_note='갈래 A'
  else
    branch_note='갈래 A 대조 실패 — B 로 판정'
  fi
}

#######################################
# 사유 하나를 센다.
# Globals:
#   reason_names, reason_counts
# Arguments:
#   사유.
#######################################
count_reason() {
  local i
  for i in ${reason_names[@]+"${!reason_names[@]}"}; do
    if [[ "${reason_names[i]}" == "$1" ]]; then
      reason_counts[i]=$((reason_counts[i] + 1))
      return
    fi
  done
  reason_names+=("$1")
  reason_counts+=(1)
}

#######################################
# 통과가 아닌 판정 하나를 표 · 셈에 더한다.
# Globals:
#   table, n_stop, n_undecided
# Arguments:
#   주체 키, 자원 키, 동작, 리전, STOP · UNDECIDED, 사유.
#######################################
add_finding() {
  local word='판정 불가'
  if [[ "$5" == STOP ]]; then
    word='멈춤'
    n_stop=$((n_stop + 1))
  else
    n_undecided=$((n_undecided + 1))
  fi
  count_reason "$6"
  table+="| $1 | $2 | $3 | $4 | ${word} | $6 |"$'\n'
}

#######################################
# 단위 · 대상 행 하나의 질문할 자원 ARN 들을 list_items 로 낸다. 주체
# 종류와 맞지 않는 해석이면 빈 값(호출 0)이다.
# Globals:
#   t_method, tr_arns, u_arn, u_kind, u_groups, u_attached, list_items
# Arguments:
#   u_* 색인, t_* 색인.
#######################################
row_arns() {
  local j="$1" t="$2"
  list_items=""
  case "${t_method[t]}:${u_kind[j]}" in
    self:role:role | self:user:user) list_items="${u_arn[j]}"$'\n' ;;
    self:group:user) list_items="${u_groups[j]}" ;;
    policy:attached:*) list_items="${u_attached[j]}" ;;
    self:*) ;;
    *) list_items="${tr_arns[t]}" ;;
  esac
}

#######################################
# (5) 실제 질문 하나를 묻고 행 판정을 기록한다(머리 주석의 순서).
# Globals:
#   u_*, t_*, pol_state, pol_file, found_policy, branch, context_json,
#   sim_class, sim_decision, result, n_judged
# Arguments:
#   u_* 색인, t_* 색인, 자원 ARN, 동작.
#######################################
judge_question() {
  local j="$1" t="$2" arn="$3" action="$4" state="" file="" verdict=PASS
  local reason=-
  policy_for "${t_read[t]}" "${arn}"
  [[ -z "${found_policy}" ]] || state="${pol_state[found_policy]}"
  if [[ "${state}" == allow && ( "${u_kind[j]}" == user ||
    "${branch}" == A ) ]]; then
    file="${pol_file[found_policy]}"
  fi
  context_entries "${t_context[t]}" || die "추가 문맥 꼴이 틀림"
  ask "${u_arn[j]}" "${action}" "${arn}" "${t_region[t]}" "${context_json}" \
    "${file}"
  if [[ "${sim_class}" == allow ]]; then
    verdict=STOP reason=ALLOWED
  elif [[ "${state}" == session ]]; then
    verdict=UNDECIDED reason=SESSION_PRINCIPAL_IN_POLICY
  elif [[ "${t_read[t]}" == unread ]]; then
    verdict=UNDECIDED reason=RESOURCE_POLICY_UNREAD
  elif [[ "${state}" == allow && -z "${file}" ]]; then
    verdict=UNDECIDED reason=RESOURCE_POLICY_UNSIMULATED
  elif [[ "${sim_class}" == missing ]]; then
    verdict=UNDECIDED reason=MISSING_CONTEXT
  elif [[ "${u_pc_failed[j]}" -eq 1 ]]; then
    verdict=UNDECIDED reason=POSITIVE_CONTROL_FAILED
  fi
  n_judged=$((n_judged + 1))
  result+="${u_keys[j]}"$'\t'"${t_key[t]}"$'\t'"${action}"$'\t'
  result+="${t_region[t]}"$'\t'"${arn}"$'\t'"${verdict}"$'\t'"${reason}"
  result+=$'\t'"${sim_decision}"$'\n'
  [[ "${verdict}" == PASS ]] || add_finding "${u_keys[j]}" "${t_key[t]}" \
    "${action}" "${t_region[t]}" "${verdict}" "${reason}"
}

#######################################
# 주체 · 대상 단위 사유(PRINCIPAL_MISSING · TARGET_NONE)를 하나당 1건으로
# 기록한다.
# Globals:
#   missing_keys, t_key, t_region, t_active, tr_none
#######################################
record_unit_reasons() {
  local key t
  for key in ${missing_keys[@]+"${missing_keys[@]}"}; do
    add_finding "${key}" - - - UNDECIDED PRINCIPAL_MISSING
  done
  for t in "${!t_key[@]}"; do
    [[ "${t_active[t]}" -eq 1 && "${tr_none[t]}" -eq 1 ]] || continue
    add_finding - "${t_key[t]}" - "${t_region[t]}" UNDECIDED TARGET_NONE
  done
}

#######################################
# (5) 단위 × 이번 단계 대상 행 × 자원 × 동작마다 묻는다. self:group ·
# policy:attached 가 0건인 단위는 report.txt 에 건수만 남긴다.
# Globals:
#   u_arn, u_kind, u_keys, t_key, t_active, t_method, t_actions, list_items,
#   notes
#######################################
judge_rows() {
  local j t rest arn actions action
  for j in ${u_arn[@]+"${!u_arn[@]}"}; do
    for t in "${!t_key[@]}"; do
      [[ "${t_active[t]}" -eq 1 ]] || continue
      row_arns "${j}" "${t}"
      if [[ -z "${list_items}" ]]; then
        case "${t_method[t]}:${u_kind[j]}" in
          self:group:user | policy:attached:*)
            notes+="대상 0건: ${t_key[t]} (${u_keys[j]})"$'\n'
            ;;
          *) ;;
        esac
        continue
      fi
      rest="${list_items}"
      while [[ -n "${rest}" ]]; do
        arn="${rest%%$'\n'*}"
        rest="${rest#*$'\n'}"
        actions="${t_actions[t]},"
        while [[ -n "${actions}" ]]; do
          action="${actions%%,*}"
          actions="${actions#*,}"
          judge_question "${j}" "${t}" "${arn}" "${action}"
        done
      done
    done
  done
}

#######################################
# (6) 증거를 쓰고, 다 쓴 뒤에만 complete.txt 를 남기고, 첫 줄과 표를 낸다.
# Globals:
#   evidence_dir, result, table, notes, branch_note, reason_*, n_*
# Returns:
#   0 PASS · 1 STOP · 3 UNDECIDED.
#######################################
record_and_report() {
  local first="PRINCIPAL CHECK PASS ${n_judged}" status=0 reasons="" i at sum
  if [[ "${n_stop}" -gt 0 ]]; then
    first="PRINCIPAL CHECK STOP ${n_stop}" status=1
  elif [[ "${n_undecided}" -gt 0 ]]; then
    first="PRINCIPAL CHECK UNDECIDED ${n_undecided}" status=3
  fi
  for i in ${reason_names[@]+"${!reason_names[@]}"}; do
    reasons+="사유 ${reason_names[i]} ${reason_counts[i]}"$'\n'
  done
  if ! printf '%s' "${result}" >"${evidence_dir}/result.tsv" ||
    ! printf '%s\n%s%s%s\n%s' "${first}" "${table}" "${reasons}" \
      "${branch_note}" "${notes}" >"${evidence_dir}/report.txt"; then
    die "증거를 쓰지 못함(${evidence_dir##*/})"
  fi
  at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" || die "완료 표지의 시각을 읽지 못함"
  sum="$(file_sha256 "${evidence_dir}/result.tsv")" ||
    die "결과 파일의 해시를 계산하지 못함(${evidence_dir##*/})"
  if ! printf '%s\t%s\n' "${at}" "${sum}" \
    >"${evidence_dir}/complete.txt.tmp" ||
    ! mv -- "${evidence_dir}/complete.txt.tmp" \
      "${evidence_dir}/complete.txt" 2>/dev/null; then
    die "완료 표지를 쓰지 못함(${evidence_dir##*/})"
  fi
  printf '%s\n%s' "${first}" "${table}"
  return "${status}"
}

main() {
  [[ "$#" -eq 1 ]] || die "사용: principal-check.sh <단계>"
  # 꼴 밖 인자(경로 등)는 되울리지 않는다.
  [[ "$1" =~ ${STAGE_PATTERN} ]] || die "단계 이름이 아님(영문 · 숫자 · 「-」)"
  stage="$1"
  stage_idx="$(stage_index "${stage}")" ||
    die "2-3 판정이 없는 단계: ${stage}"
  load_principals
  load_controls
  load_targets
  mark_active
  evidence_dir="$(evidence::new_dir "${DIR_PREFIX}-${stage}")" || exit 2
  printf 'principal-check: 증거 ~/%s/%s\n' "${EVIDENCE_ROOT_NAME}" \
    "${evidence_dir##*/}" >&2
  caller_identity
  ask_scope_controls
  resolve_principals
  resolve_targets
  resolve_unit_targets
  read_policies
  ask_positive_controls
  ask_branch_control
  judge_rows
  record_unit_reasons
  record_and_report
}

main "$@"
