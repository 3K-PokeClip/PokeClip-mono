#!/usr/bin/env bash
#
# 단계 묶음 · baseline · 이벤트 이름 데이터를 시험한다 — 계획 M17a c1
# 사례 3(묶음 분리 · 계정 ID 0) 과 사례 4 의 실제 데이터 몫(조회 리전 규칙 ·
# SNS · SQS 정책 이름).
#
# 데이터 규약(scp-recheck.sh · events.sh 와 같음): 탭 구분, 「#」 으로 시작하는
# 줄은 주석, 빈 줄은 건너뜀. 묶음 파일마다 출처 주석(「# 출처:」)을 둔다.
#   stage_actions/<묶음>.tsv   동작 · 리전 · 자원 패턴(계정 자리 ${ACCOUNT})
#   stage_actions/baseline.tsv 묶음 · 동작 · 리전 · 판정(allow · deny) ·
#                              13-1 판정 시각
#   event_names.txt            이벤트 출처 · 이벤트 이름 · 조회 리전
# 끝으로 실제 스크립트를 실제 데이터로 가짜 aws 에 돌려, 파서가 데이터를
# 받는지와 조회 argv 의 리전을 본다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/data_files_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly DATA_DIR="${PRECHECK_DIR}/data"
readonly STAGE_DIR="${DATA_DIR}/stage_actions"
readonly EVENT_NAMES="${DATA_DIR}/event_names.txt"
# 계획 M17a 「데이터」 의 묶음 이름 전부.
BUNDLES=(1b 5 O7a O7b O7c O7d O7e O7e-stop1 O7f O7f-stop2 testkey loopback 8
  8a 9 ci-followup lease-expiry)
readonly BUNDLES
# 2-3 판정을 하는 묶음과 하지 않는 묶음(계획 3절 · 5절 표).
JUDGED_BUNDLES=(5 O7a O7b O7c O7d O7e O7f 8 8a 9 ci-followup)
readonly JUDGED_BUNDLES
UNJUDGED_BUNDLES=(1b O7e-stop1 O7f-stop2 testkey loopback lease-expiry)
readonly UNJUDGED_BUNDLES
# 2-3 판정 · 이벤트 조회 자신의 읽기 동작(설계 11-5 의 2-3-7 · 2-3-8).
JUDGE_READS=(iam:GetAccountAuthorizationDetails
  iam:GetContextKeysForPrincipalPolicy iam:SimulateCustomPolicy
  iam:GetInstanceProfile iam:ListAccessKeys s3:GetBucketPolicy
  secretsmanager:GetResourcePolicy ecr:GetRepositoryPolicy
  sns:GetTopicAttributes sqs:GetQueueAttributes ec2:DescribeInstances
  lambda:ListFunctions ecs:ListTaskDefinitions ecs:DescribeTaskDefinition
  cloudtrail:LookupEvents tag:GetTagKeys tag:GetResources)
readonly JUDGE_READS
# 멈춤 자리 ② 의 KVS 값 동작(계획 M17a 「데이터」).
KVS_VALUE_ACTIONS=(cloudfront-keyvaluestore:PutKey
  cloudfront-keyvaluestore:GetKey cloudfront-keyvaluestore:ListKeys
  cloudfront-keyvaluestore:DescribeKeyValueStore
  cloudfront-keyvaluestore:UpdateKeys cloudfront-keyvaluestore:DeleteKey)
readonly KVS_VALUE_ACTIONS
readonly ACTION_PATTERN='^[a-z0-9-]+:[A-Za-z0-9]+$'
readonly REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]$'
readonly SOURCE_PATTERN='^[a-z0-9.-]+\.amazonaws\.com$'
# 13-1 판정 시각(KST).
readonly JUDGED_AT_PATTERN='^[0-9]{4}(-[0-9]{2}){2}T[0-9]{2}:[0-9]{2}\+09:00$'

#######################################
# 데이터 파일의 행(주석 · 빈 줄 뺌)을 낸다.
# Arguments:
#   파일.
# Outputs:
#   행(원문 그대로).
#######################################
data_rows() {
  local line
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" || "${line}" == '#'* ]] && continue
    printf '%s\n' "${line}"
  done <"$1"
}

#######################################
# 묶음 파일의 동작 열을 낸다.
# Arguments:
#   묶음 이름.
# Outputs:
#   동작(줄마다 하나).
#######################################
bundle_actions() {
  data_rows "${STAGE_DIR}/$1.tsv" | cut -f 1
}

#######################################
# 묶음 파일 집합이 계획의 묶음 이름과 같은지 본다.
#######################################
expect_bundle_set() {
  local file want got
  want="$(printf '%s\n' "${BUNDLES[@]}" | LC_ALL=C sort)"
  got=""
  for file in "${STAGE_DIR}"/*.tsv; do
    [[ -f "${file}" ]] || continue
    file="${file##*/}"
    [[ "${file}" == baseline.tsv ]] && continue
    got+="${file%.tsv}"$'\n'
  done
  got="$(printf '%s' "${got}" | LC_ALL=C sort)"
  if [[ "${got}" != "${want}" ]]; then
    testlib::fail "bundle-set: 묶음 파일이 계획의 묶음과 다름 — $(printf '%s' \
      "${got}" | tr '\n' ' ')"
    return
  fi
  testlib::ok "bundle-set → 묶음 파일 17개 = 계획 M17a 묶음"
}

#######################################
# 묶음 파일마다 출처 주석 · 열 수 · 동작 · 리전 꼴 · 동작과 리전 짝 중복
# 없음을 본다.
#######################################
expect_bundle_rows_wellformed() {
  local bundle file row action region resource rest seen count
  for bundle in "${BUNDLES[@]}"; do
    file="${STAGE_DIR}/${bundle}.tsv"
    if [[ ! -f "${file}" ]] || ! grep -q '^# 출처:' -- "${file}"; then
      testlib::fail "rows: ${bundle} 파일 또는 출처 주석이 없음"
      return
    fi
    seen=$'\n'
    count=0
    while IFS= read -r row; do
      IFS=$'\t' read -r action region resource rest <<<"${row}"
      if [[ -n "${rest}" || -z "${resource}" ]] ||
        ! [[ "${action}" =~ ${ACTION_PATTERN} ]] ||
        ! [[ "${region}" =~ ${REGION_PATTERN} ]] ||
        [[ "${resource}" == *[\"\\]* ]]; then
        testlib::fail "rows: ${bundle} 의 행 꼴이 틀림 — ${row}"
        return
      fi
      if [[ "${seen}" == *$'\n'"${action} ${region}"$'\n'* ]]; then
        testlib::fail "rows: ${bundle} 에 ${action} ${region} 중복"
        return
      fi
      seen+="${action} ${region}"$'\n'
      count=$((count + 1))
    done < <(data_rows "${file}")
    if [[ "${count}" -eq 0 ]]; then
      testlib::fail "rows: ${bundle} 에 행이 없음"
      return
    fi
  done
  testlib::ok "rows → 묶음마다 출처 주석 · 세 열 · 동작 · 리전 짝 중복 0"
}

#######################################
# 사례 3: 모든 데이터 파일에 12자리 숫자 0건 · 자리표시자는 ${ACCOUNT} 뿐.
#######################################
expect_no_account_ids() {
  local hits others
  if [[ ! -f "${EVENT_NAMES}" || ! -f "${STAGE_DIR}/baseline.tsv" ]]; then
    testlib::fail "account: 데이터 파일이 없음"
    return
  fi
  hits="$(grep -rlE -- '[0-9]{12}' "${DATA_DIR}")"
  if [[ "$?" -gt 1 ]]; then
    testlib::fail "account: 데이터 디렉터리를 읽지 못함"
    return
  fi
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 글자 그대로다.
  others="$(grep -rhoE -- '\$\{[^}]*\}' "${DATA_DIR}" |
    grep -vxF -- '${ACCOUNT}')"
  if [[ -n "${hits}" || -n "${others}" ]]; then
    testlib::fail "account: 12자리 숫자(${hits}) 또는 다른 자리표시자(${others})"
    return
  fi
  testlib::ok "account → 데이터 전부 12자리 숫자 0 · 자리표시자는 \${ACCOUNT} 뿐"
}

#######################################
# 사례 3: KVS 값 동작은 O7f-stop2 에만(O7f 0건), rds-db:connect 는
# O7e-stop1 에만(O7e 0건).
#######################################
expect_stop_point_split() {
  local bundle action actions
  for action in "${KVS_VALUE_ACTIONS[@]}"; do
    if ! bundle_actions O7f-stop2 | grep -qxF -- "${action}"; then
      testlib::fail "split: O7f-stop2 에 ${action} 없음"
      return
    fi
  done
  if ! bundle_actions O7e-stop1 | grep -qxF -- 'rds-db:connect'; then
    testlib::fail "split: O7e-stop1 에 rds-db:connect 없음"
    return
  fi
  for bundle in "${BUNDLES[@]}"; do
    actions="$(bundle_actions "${bundle}")"
    if [[ "${bundle}" != O7f-stop2 ]] &&
      grep -q '^cloudfront-keyvaluestore:' <<<"${actions}"; then
      testlib::fail "split: ${bundle} 에 cloudfront-keyvaluestore 값 동작"
      return
    fi
    if [[ "${bundle}" != O7e-stop1 ]] &&
      grep -qxF -- 'rds-db:connect' <<<"${actions}"; then
      testlib::fail "split: ${bundle} 에 rds-db:connect"
      return
    fi
  done
  testlib::ok "split → KVS 값 동작은 O7f-stop2 에만 · rds-db:connect 는" \
    "O7e-stop1 에만"
}

#######################################
# 2-3 판정 읽기 동작은 2-3 판정 묶음에만 있다. ec2:DescribeRegions 는
# 어디에도 없다(kty 45차).
#######################################
expect_judge_reads() {
  local bundle action actions
  for bundle in "${JUDGED_BUNDLES[@]}"; do
    actions="$(bundle_actions "${bundle}")"
    for action in "${JUDGE_READS[@]}"; do
      if ! grep -qxF -- "${action}" <<<"${actions}"; then
        testlib::fail "judge-reads: ${bundle} 에 ${action} 없음"
        return
      fi
    done
  done
  for bundle in "${UNJUDGED_BUNDLES[@]}"; do
    actions="$(bundle_actions "${bundle}")"
    for action in "${JUDGE_READS[@]}"; do
      if grep -qxF -- "${action}" <<<"${actions}"; then
        testlib::fail "judge-reads: 2-3 판정이 없는 ${bundle} 에 ${action}"
        return
      fi
    done
  done
  for bundle in "${BUNDLES[@]}"; do
    if bundle_actions "${bundle}" | grep -qxF -- 'ec2:DescribeRegions'; then
      testlib::fail "judge-reads: ${bundle} 에 ec2:DescribeRegions"
      return
    fi
  done
  testlib::ok "judge-reads → 2-3 판정 묶음 11개에만 · DescribeRegions 0"
}

#######################################
# baseline.tsv 의 행마다 꼴(다섯 열 · 판정 · 시각)과, 그 (동작, 리전)이 그
# 묶음 파일에 실제로 있는지 본다.
#######################################
expect_baseline_rows_exist() {
  local row bundle action region decision judged_at rest count=0
  while IFS= read -r row; do
    IFS=$'\t' read -r bundle action region decision judged_at rest <<<"${row}"
    if [[ -n "${rest}" || ! -f "${STAGE_DIR}/${bundle}.tsv" ]] ||
      [[ "${decision}" != allow && "${decision}" != deny ]] ||
      ! [[ "${judged_at}" =~ ${JUDGED_AT_PATTERN} ]]; then
      testlib::fail "baseline: 행 꼴이 틀림 — ${row}"
      return
    fi
    if ! data_rows "${STAGE_DIR}/${bundle}.tsv" | cut -f 1,2 |
      grep -qxF -- "${action}"$'\t'"${region}"; then
      testlib::fail "baseline: ${bundle} 묶음에 ${action} ${region} 이 없음"
      return
    fi
    count=$((count + 1))
  done < <(data_rows "${STAGE_DIR}/baseline.tsv")
  if [[ "${count}" -eq 0 ]]; then
    testlib::fail "baseline: 행이 없음"
    return
  fi
  testlib::ok "baseline → ${count}행 모두 그 묶음 파일에 있는 (동작, 리전)"
}

#######################################
# 이벤트 출처의 조회 리전 규칙(설계 2-3-10): IAM · CloudFront = us-east-1,
# STS = us-east-1 과 서울, 그 밖 = 서울.
# Arguments:
#   이벤트 출처.
# Outputs:
#   기대 리전(공백 구분).
#######################################
expected_regions() {
  case "$1" in
    iam.amazonaws.com | cloudfront.amazonaws.com) printf 'us-east-1\n' ;;
    sts.amazonaws.com) printf 'ap-northeast-2 us-east-1\n' ;;
    *) printf 'ap-northeast-2\n' ;;
  esac
}

#######################################
# 「출처 이름 리전」 줄 목록에서 한 (출처, 이름)의 리전을 정렬해 낸다.
# Arguments:
#   줄 목록, 이벤트 출처, 이벤트 이름.
# Outputs:
#   리전(공백 구분 · 끝에 공백, 없으면 빈 출력).
#######################################
regions_of() {
  local all="$1" source="$2" name="$3" line regions=""
  while IFS= read -r line; do
    if [[ "${line% *}" == "${source} ${name}" ]]; then
      regions+="${line##* }"$'\n'
    fi
  done <<<"${all}"
  printf '%s' "${regions}" | LC_ALL=C sort | tr '\n' ' '
}

#######################################
# 사례 4(데이터 몫): event_names.txt 의 꼴 · 리전 규칙 · 꼭 있어야 할 이름.
#######################################
expect_event_names() {
  local row source name region rest pair want got
  local all=""
  while IFS= read -r row; do
    IFS=$'\t' read -r source name region rest <<<"${row}"
    if [[ -n "${rest}" ]] || ! [[ "${source}" =~ ${SOURCE_PATTERN} ]] ||
      ! [[ "${name}" =~ ^[A-Za-z0-9]+$ ]] ||
      ! [[ "${region}" =~ ${REGION_PATTERN} ]]; then
      testlib::fail "event-names: 행 꼴이 틀림 — ${row}"
      return
    fi
    all+="${source} ${name} ${region}"$'\n'
  done < <(data_rows "${EVENT_NAMES}")
  if [[ -n "$(printf '%s' "${all}" | LC_ALL=C sort | uniq -d)" ]]; then
    testlib::fail "event-names: 중복 행"
    return
  fi
  for pair in $(printf '%s' "${all}" | cut -d ' ' -f 1,2 | tr ' ' '|' |
    LC_ALL=C sort -u); do
    source="${pair%%|*}"
    name="${pair#*|}"
    want="$(expected_regions "${source}")"
    got="$(regions_of "${all}" "${source}" "${name}")"
    if [[ "${got}" != "${want} " ]]; then
      testlib::fail "event-names: ${source} ${name} 의 조회 리전이" \
        "「${want}」 가 아니라 「${got}」"
      return
    fi
  done
  for pair in 'sns.amazonaws.com SetTopicAttributes' \
    'sqs.amazonaws.com SetQueueAttributes' 'sts.amazonaws.com AssumeRole' \
    'sts.amazonaws.com AssumeRoleWithWebIdentity'; do
    if [[ -z "$(regions_of "${all}" "${pair% *}" "${pair#* }")" ]]; then
      testlib::fail "event-names: ${pair} 없음"
      return
    fi
  done
  testlib::ok "event-names → 리전 규칙 · SNS · SQS 정책 · STS 역할 받기 이름"
}

#######################################
# 실제 scp-recheck.sh 가 실제 묶음을 모두 받는다(가짜 aws 전부 허용 —
# 종료 0 또는 3, 검사 불가 0). 실제 events.sh 는 event_names.txt 의 행마다
# 그 리전으로 한 번씩 조회한다.
#######################################
expect_real_scripts_accept_data() {
  local case_dir bundle row source name region calls rows
  case_dir="$(testlib::new_case)" || {
    testlib::fail "real-scripts: 시험 준비 실패"
    return
  }
  for bundle in "${BUNDLES[@]}"; do
    testlib::run "${case_dir}" "recheck-${bundle}" bash \
      "${PRECHECK_DIR}/scp-recheck.sh" "${bundle}"
    if [[ "${run_status}" -ne 0 && "${run_status}" -ne 3 ]]; then
      testlib::fail "real-scripts: scp-recheck.sh ${bundle} 이 종료" \
        "${run_status} — $(cat -- "${run_out}.stderr")"
      return
    fi
  done
  : >"${case_dir}/out/aws.log"
  testlib::run "${case_dir}" events bash "${PRECHECK_DIR}/events.sh" --since \
    "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "real-scripts: events.sh 가 종료 ${run_status}"
    return
  fi
  rows=0
  while IFS= read -r row; do
    IFS=$'\t' read -r source name region <<<"${row}"
    calls="$(grep -E -- "AttributeValue=${name}( |$)" \
      "${case_dir}/out/aws.log" | grep -cE -- "--region ${region}( |$)")"
    if [[ "${calls}" -lt 1 ]]; then
      testlib::fail "real-scripts: ${source} ${name} 을 ${region} 에서 조회하지 않음"
      return
    fi
    rows=$((rows + 1))
  done < <(data_rows "${EVENT_NAMES}")
  if [[ "$(grep -c 'cloudtrail lookup-events' "${case_dir}/out/aws.log")" -ne \
    "${rows}" ]]; then
    testlib::fail "real-scripts: 조회 수가 event_names.txt 행 수(${rows})와 다름"
    return
  fi
  if [[ -n "$(testlib::leaks_outside_evidence "${case_dir}")" ]]; then
    testlib::fail "real-scripts: 증거 루트 밖 유출"
    return
  fi
  testlib::ok "real-scripts → 묶음 17개 모두 판정됨 · 이벤트 ${rows}행을 그 리전에서 조회"
}

main() {
  testlib::init data_files_test
  expect_bundle_set
  expect_bundle_rows_wellformed
  expect_no_account_ids
  expect_stop_point_split
  expect_judge_reads
  expect_baseline_rows_exist
  expect_event_names
  expect_real_scripts_accept_data
  testlib::finish data_files_test
}

main "$@"
