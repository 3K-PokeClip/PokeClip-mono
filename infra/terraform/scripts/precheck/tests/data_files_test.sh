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
#   principal_sim/             2-3 판정 데이터 셋(계획 M17b 3-1 「데이터
#                              단언」 — 꼴은 lib/principal_data.sh 머리 주석)
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
# 계획 M17a 「데이터」 의 묶음 이름 전부 + prod 런타임 1단-가(runtime-1a).
BUNDLES=(1b 5 O7a O7b O7c O7d O7e O7e-stop1 O7f O7f-stop2 testkey loopback 8
  8a 9 ci-followup lease-expiry runtime-1a)
readonly BUNDLES
# 2-3 판정을 하는 묶음과 하지 않는 묶음(계획 3절 · 5절 표). runtime-1a 는
# POK-365 완료로 판정 묶음이다(kty 2026-10-23 — O7c 뒤 · O7d 앞).
JUDGED_BUNDLES=(5 O7a O7b O7c runtime-1a O7d O7e O7f 8 8a 9 ci-followup)
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
# 권한 상승 이벤트(kty 59차 — 계획 399행 범주 확장).
PRIVILEGE_EVENTS=(AddUserToGroup CreateRole UpdateLoginProfile UpdateAccessKey
  CreateOpenIDConnectProvider AddClientIDToOpenIDConnectProvider
  UpdateOpenIDConnectProviderThumbprint)
readonly PRIVILEGE_EVENTS
readonly ACTION_PATTERN='^[a-z0-9-]+:[A-Za-z0-9]+$'
readonly REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]$'
readonly SOURCE_PATTERN='^[a-z0-9.-]+\.amazonaws\.com$'
# 13-1 판정 시각(KST).
readonly JUDGED_AT_PATTERN='^[0-9]{4}(-[0-9]{2}){2}T[0-9]{2}:[0-9]{2}\+09:00$'
# 2-3 판정 래퍼와 그 데이터(계획 M17b).
readonly WRAPPER="${PRECHECK_DIR}/principal-check.sh"
readonly PRINCIPAL_DIR="${DATA_DIR}/principal_sim"
# 래퍼가 부르는 읽기 동작과 그 리전(「동작<공백>리전」 — 계획 M17b 1절 4).
# IAM · Route 53 은 us-east-1, 나머지는 서울이고, 태그 해석은 행의 리전(서울
# · us-east-1 인증서)에서 부른다. iam:SimulatePrincipalPolicy 는 도구 자신의
# 호출이라 넣지 않는다(거부되면 종료 2 로 드러남).
PRINCIPAL_READS=('iam:GetInstanceProfile us-east-1' 'iam:GetRole us-east-1'
  'iam:GetUser us-east-1' 'iam:ListRoles us-east-1'
  'iam:ListAttachedRolePolicies us-east-1'
  'iam:ListAttachedUserPolicies us-east-1' 'iam:ListGroupsForUser us-east-1'
  'iam:ListAttachedGroupPolicies us-east-1' 'tag:GetResources ap-northeast-2'
  'tag:GetResources us-east-1' 'route53:ListHostedZonesByName us-east-1'
  's3:GetBucketPolicy ap-northeast-2'
  'secretsmanager:GetResourcePolicy ap-northeast-2'
  'ecr:GetRepositoryPolicy ap-northeast-2'
  'sns:GetTopicAttributes ap-northeast-2'
  'sqs:GetQueueAttributes ap-northeast-2')
readonly PRINCIPAL_READS
# 2-3 판정이 없는 묶음이 자기 단계 동작으로 가진 판정 읽기 동작(JUDGE_READS
# · PRINCIPAL_READS 와 이름이 같은 「묶음 동작」). 「묶음 이름 + 동작」 정확
# 일치 쌍만 받는다 — 와일드카드 · 동작만 쓰는 예외는 없다(계획 rt1a H1).
# lease-expiry 는 공유 존 조회다. 쌍마다 묶음 · 동작 · 필요는
# expect_stage_own_reads_scoped 가 본다.
STAGE_OWN_READS=('lease-expiry route53:ListHostedZonesByName')
readonly STAGE_OWN_READS
# 자원 정책을 가지는 서비스(그 행은 정책 읽기 방법 none 을 쓸 수 없다).
readonly POLICY_SERVICES=' s3 secretsmanager ecr sns sqs kms lambda '
# 래퍼가 자원 정책을 읽는 서비스(그 행의 정책 읽기 방법은 서비스 이름 그대로
# 다 — unread 를 쓸 수 없다. lib/principal_policy.sh 머리 주석).
readonly READ_SERVICES=' s3 secretsmanager ecr sns sqs '
# 런타임 1단-가가 만드는 자원의 대상 행(kty 2026-10-24 — 2-3 판정 대상에
# 편입). 「자원 키 · 첫 단계 · 반드시 묻는 동작」 이고, 같은 색인의
# RUNTIME_ARN_TAILS 가 그 행 ARN 의 꼬리다(이름 = prod/runtime/*.tf ·
# CONTRACT-runtime.md 「런타임 자원 이름 패턴」). rewind 둘은 버킷이 생긴
# 뒤 단계(O7d)부터 묻는다(prod_targets.tsv 머리 주석).
RUNTIME_TARGETS=('rds-instance runtime-1a rds:DeleteDBInstance'
  'rds-parameter-group runtime-1a rds:ModifyDBParameterGroup'
  'rds-subnet-group runtime-1a rds:DeleteDBSubnetGroup'
  'rds-log-group runtime-1a logs:DeleteLogGroup'
  'broadcast-topic runtime-1a sns:Publish'
  'broadcast-queue-clip runtime-1a sqs:ReceiveMessage'
  'broadcast-queue-chat runtime-1a sqs:ReceiveMessage'
  'broadcast-dlq-clip runtime-1a sqs:ReceiveMessage'
  'broadcast-dlq-chat runtime-1a sqs:ReceiveMessage'
  'sql-runner-role runtime-1a iam:PassRole'
  'sql-runner-profile runtime-1a iam:AddRoleToInstanceProfile'
  'rewind-bucket O7d s3:PutBucketPolicy'
  'rewind-object O7d s3:PutObject')
readonly RUNTIME_TARGETS
RUNTIME_ARN_TAILS=(':db:pokeclip-prod-db' ':pg:pokeclip-prod-pg17'
  ':subgrp:pokeclip-prod-db'
  ':log-group:/aws/rds/instance/pokeclip-prod-db/postgresql'
  ':pokeclip-prod-broadcast-lifecycle.fifo'
  ':pokeclip-prod-broadcast-lifecycle-clip.fifo'
  ':pokeclip-prod-broadcast-lifecycle-chat.fifo'
  ':pokeclip-prod-broadcast-lifecycle-clip-dlq.fifo'
  ':pokeclip-prod-broadcast-lifecycle-chat-dlq.fifo'
  ':role/pokeclip/prod/pokeclip-prod-sql-runner'
  ':instance-profile/pokeclip/prod/pokeclip-prod-sql-runner'
  ':::pokeclip-prod-rewind-apne2' ':::pokeclip-prod-rewind-apne2/?*')
readonly RUNTIME_ARN_TAILS
# 실행기 역할 넘기기는 EC2 에 넘기는 질문이다(추가 문맥).
readonly PASSED_TO_EC2='iam:PassedToService:string:ec2.amazonaws.com'
# 그 문맥이 시뮬레이터 요청에 실린 꼴.
PASSED_TO_EC2_ENTRY='{"ContextKeyName":"iam:PassedToService",'
PASSED_TO_EC2_ENTRY+='"ContextKeyValues":["ec2.amazonaws.com"],'
PASSED_TO_EC2_ENTRY+='"ContextKeyType":"string"}'
readonly PASSED_TO_EC2_ENTRY
# 켬 · apply 뒤에 정해지는 런타임 자원의 대상 행(rt1a 종결 r1 H2 · H4).
# 「자원 키 · 첫 단계 · 해석 방법 · 반드시 묻는 동작(쉼표 구분)」 이다. 실행기
# 인스턴스는 켤 때 ID 가 정해져 태그로 풀고, 관리형 마스터 비밀은 이름 끝이
# 생성 때 정해져 apply 뒤 kty 가 적는 입력 파일로 푼다. 인스턴스 행의 동작은
# 켜진 상자에서 코드를 돌리는 SSM 길 가운데 instance 유형을 받는 것이다
# (서비스 승인 참조 list_ssm.html — StartAutomationExecution 은 받지 않음).
LATE_TARGETS=(
  'prod-instance runtime-1a tag:ec2:instance '\
'ssm:SendCommand,ssm:StartSession,ssm:CreateAssociation'
  'rds-master-secret runtime-1a input:rds-master secretsmanager:GetSecretValue')
readonly LATE_TARGETS

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
# 줄 목록에 그 줄이 그대로 있는지 본다 — here-string 으로 grep 에 넘기지
# 않는다(임시 파일을 만들지 못해 읽기가 실패하면 「없음」 단언이 거짓으로
# 통과하므로).
# Arguments:
#   줄 목록(줄바꿈 구분), 줄.
# Returns:
#   있으면 0.
#######################################
has_line() {
  [[ $'\n'"$1"$'\n' == *$'\n'"$2"$'\n'* ]]
}

#######################################
# 묶음 파일의 동작 열을 낸다.
# Globals:
#   STAGE_DIR
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
# Globals:
#   BUNDLES, STAGE_DIR
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
  testlib::ok "bundle-set → 묶음 파일 18개 = 계획 M17a 묶음 + runtime-1a"
}

#######################################
# 묶음 파일마다 출처 주석 · 열 수 · 동작 · 리전 꼴 · 동작과 리전 짝 중복
# 없음을 본다.
# Globals:
#   BUNDLES, STAGE_DIR, ACTION_PATTERN, REGION_PATTERN
#######################################
expect_bundle_rows_wellformed() {
  local bundle file action region resource rest seen count
  for bundle in "${BUNDLES[@]}"; do
    file="${STAGE_DIR}/${bundle}.tsv"
    if [[ ! -f "${file}" ]] || ! grep -q '^# 출처:' -- "${file}"; then
      testlib::fail "rows: ${bundle} 파일 또는 출처 주석이 없음"
      return
    fi
    seen=$'\n'
    count=0
    while IFS=$'\t' read -r action region resource rest; do
      if [[ -n "${rest}" || -z "${resource}" ]] ||
        ! [[ "${action}" =~ ${ACTION_PATTERN} ]] ||
        ! [[ "${region}" =~ ${REGION_PATTERN} ]] ||
        [[ "${resource}" == *[\"\\]* ]]; then
        testlib::fail "rows: ${bundle} 의 행 꼴이 틀림 — ${action} ${region}" \
          "${resource} ${rest}"
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
# Globals:
#   EVENT_NAMES, STAGE_DIR, DATA_DIR
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
# Globals:
#   KVS_VALUE_ACTIONS, BUNDLES
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
    if [[ "${bundle}" != O7f-stop2 &&
      $'\n'"${actions}" == *$'\n'cloudfront-keyvaluestore:* ]]; then
      testlib::fail "split: ${bundle} 에 cloudfront-keyvaluestore 값 동작"
      return
    fi
    if [[ "${bundle}" != O7e-stop1 ]] &&
      has_line "${actions}" 'rds-db:connect'; then
      testlib::fail "split: ${bundle} 에 rds-db:connect"
      return
    fi
  done
  testlib::ok "split → KVS 값 동작은 O7f-stop2 에만 · rds-db:connect 는" \
    "O7e-stop1 에만"
}

#######################################
# 2-3 판정 읽기 동작은 2-3 판정 묶음에만 있다 — 판정이 없는 묶음에서는
# STAGE_OWN_READS 의 「묶음 이름 + 동작」 쌍만 예외다(계획 rt1a H1).
# ec2:DescribeRegions 는 어디에도 없다(kty 45차).
# Globals:
#   JUDGED_BUNDLES, JUDGE_READS, UNJUDGED_BUNDLES, STAGE_OWN_READS, BUNDLES
#######################################
expect_judge_reads() {
  local bundle action actions own
  own="$(printf '%s\n' "${STAGE_OWN_READS[@]}")"
  for bundle in "${JUDGED_BUNDLES[@]}"; do
    actions="$(bundle_actions "${bundle}")"
    for action in "${JUDGE_READS[@]}"; do
      if ! has_line "${actions}" "${action}"; then
        testlib::fail "judge-reads: ${bundle} 에 ${action} 없음"
        return
      fi
    done
  done
  for bundle in "${UNJUDGED_BUNDLES[@]}"; do
    actions="$(bundle_actions "${bundle}")"
    for action in "${JUDGE_READS[@]}"; do
      has_line "${own}" "${bundle} ${action}" && continue
      if has_line "${actions}" "${action}"; then
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
  testlib::ok "judge-reads → 2-3 판정 묶음" \
    "${#JUDGED_BUNDLES[@]}개에만(묶음 자기 동작 쌍 뺌) · DescribeRegions 0"
}

#######################################
# STAGE_OWN_READS 의 쌍마다: 꼴이 「묶음 이름 + 동작」 하나(와일드카드 0)이고,
# 묶음이 UNJUDGED_BUNDLES 에 있고, 동작이 그 묶음 파일에 실제로 있으며(죽은
# 예외 0), 동작이 JUDGE_READS ∪ PRINCIPAL_READS(리전 뺌)에 든다(필요 없는
# 예외 0 — 계획 rt1a H1).
# Globals:
#   STAGE_OWN_READS, UNJUDGED_BUNDLES, JUDGE_READS, PRINCIPAL_READS,
#   ACTION_PATTERN, STAGE_DIR
#######################################
expect_stage_own_reads_scoped() {
  local pair bundle action reads read unjudged
  reads="$(printf '%s\n' "${JUDGE_READS[@]}")"
  for read in "${PRINCIPAL_READS[@]}"; do
    reads+=$'\n'"${read% *}"
  done
  unjudged="$(printf '%s\n' "${UNJUDGED_BUNDLES[@]}")"
  for pair in "${STAGE_OWN_READS[@]}"; do
    bundle="${pair%% *}"
    action="${pair#* }"
    if [[ "${pair}" != *' '* ]] ||
      ! [[ "${action}" =~ ${ACTION_PATTERN} ]]; then
      testlib::fail "own-reads: 「${pair}」 가 「묶음 이름 + 동작」 꼴이 아님"
      return
    fi
    if ! has_line "${unjudged}" "${bundle}"; then
      testlib::fail "own-reads: ${pair} — ${bundle} 은 판정이 없는 묶음이 아님"
      return
    fi
    if [[ ! -f "${STAGE_DIR}/${bundle}.tsv" ]] ||
      ! has_line "$(bundle_actions "${bundle}")" "${action}"; then
      testlib::fail "own-reads: ${pair} — 묶음 파일에 없는 동작(죽은 예외)"
      return
    fi
    if ! has_line "${reads}" "${action}"; then
      testlib::fail "own-reads: ${pair} — 판정 읽기 동작이 아님(필요 없는 예외)"
      return
    fi
  done
  testlib::ok "own-reads → 예외 쌍 ${#STAGE_OWN_READS[@]}개 모두 판정 없는 묶음" \
    "· 그 묶음 파일에 있음 · 판정 읽기 동작"
}

#######################################
# baseline.tsv 의 행마다 꼴(다섯 열 · 판정 · 시각)과, 그 (동작, 리전)이 그
# 묶음 파일에 실제로 있는지 본다.
# Globals:
#   STAGE_DIR, JUDGED_AT_PATTERN
#######################################
expect_baseline_rows_exist() {
  local bundle action region decision judged_at rest count=0
  while IFS=$'\t' read -r bundle action region decision judged_at rest; do
    if [[ -n "${rest}" || ! -f "${STAGE_DIR}/${bundle}.tsv" ]] ||
      [[ "${decision}" != allow && "${decision}" != deny ]] ||
      ! [[ "${judged_at}" =~ ${JUDGED_AT_PATTERN} ]]; then
      testlib::fail "baseline: 행 꼴이 틀림 — ${bundle} ${action} ${region}" \
        "${decision} ${judged_at} ${rest}"
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
  local rest="$1" source="$2" name="$3" line regions=""
  # 매개변수 확장으로 한 줄씩 떼어 낸다(here-string 은 임시 파일을 쓴다).
  while [[ -n "${rest}" ]]; do
    line="${rest%%$'\n'*}"
    rest="${rest#"${line}"}"
    rest="${rest#$'\n'}"
    if [[ "${line% *}" == "${source} ${name}" ]]; then
      regions+="${line##* }"$'\n'
    fi
  done
  printf '%s' "${regions}" | LC_ALL=C sort | tr '\n' ' '
}

#######################################
# 사례 4(데이터 몫): event_names.txt 의 꼴 · 리전 규칙 · 꼭 있어야 할 이름.
# Globals:
#   SOURCE_PATTERN, REGION_PATTERN, EVENT_NAMES, PRIVILEGE_EVENTS
#######################################
expect_event_names() {
  local source name region rest pair want got
  local all=""
  while IFS=$'\t' read -r source name region rest; do
    if [[ -n "${rest}" ]] || ! [[ "${source}" =~ ${SOURCE_PATTERN} ]] ||
      ! [[ "${name}" =~ ^[A-Za-z0-9]+$ ]] ||
      ! [[ "${region}" =~ ${REGION_PATTERN} ]]; then
      testlib::fail "event-names: 행 꼴이 틀림 — ${source} ${name} ${region}" \
        "${rest}"
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
  for name in "${PRIVILEGE_EVENTS[@]}"; do
    got="$(regions_of "${all}" iam.amazonaws.com "${name}")"
    if [[ "${got}" != 'us-east-1 ' ]]; then
      testlib::fail "event-names: 권한 상승 iam.amazonaws.com ${name}" \
        "us-east-1 없음(kty 59차)"
      return
    fi
  done
  testlib::ok "event-names → 리전 규칙 · SNS · SQS 정책 · STS 역할 받기 ·" \
    "권한 상승 7개"
}

#######################################
# event_names.txt 맨 아래 묶음(마지막 주석 덩어리 뒤의 행)을 낸다.
# Globals:
#   EVENT_NAMES
# Outputs:
#   행(원문 그대로).
#######################################
last_group_rows() {
  local line group="" after_rows=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == '#'* ]]; then
      [[ "${after_rows}" -eq 1 ]] && group=""
      after_rows=0
    elif [[ -n "${line}" ]]; then
      group+="${line}"$'\n'
      after_rows=1
    fi
  done <"${EVENT_NAMES}"
  printf '%s' "${group}"
}

#######################################
# 「역할 받기」 범주의 경계: event_names.txt 맨 아래 묶음의 행 =
# events.sh is_role_assumption 이 역할 받기로 세는 행. 실제 events.sh 를
# 실제 데이터로 돌려(행마다 그 행 출처 · 이름 · 리전의 이벤트 하나), 증거
# event_ids.txt 에서 맨 아래 묶음 행의 이벤트만 「역할 받기」 이고 나머지는
# 「권한 변경」 인지 본다 — 한쪽만 고치면(묶음에 행을 더하고 코드는 그대로
# 등) 실패한다.
# Globals:
#   EVENT_NAMES, PRECHECK_DIR, run_status, run_out
#######################################
expect_role_group_matches_code() {
  local case_dir group source name region rest n=0 line dir want got
  case_dir="$(testlib::new_case)" || {
    testlib::fail "role-group: 시험 준비 실패"
    return
  }
  group="$(last_group_rows)"
  while IFS=$'\t' read -r source name region rest; do
    n=$((n + 1))
    testlib::tsv "${region}" "${name}" "ev-${n}" "${source}" \
      2026-10-13T03:00:00Z tester
  done < <(data_rows "${EVENT_NAMES}") >"${case_dir}/in/events.tsv"
  testlib::run "${case_dir}" roles env \
    FAKE_AWS_EVENTS="${case_dir}/in/events.tsv" \
    bash "${PRECHECK_DIR}/events.sh" --since "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  dir="$(grep -m 1 '^events: 증거 ' -- "${run_out}.stderr")"
  dir="${case_dir}/home/.pokeclip-precheck/${dir##*/}"
  if [[ "${run_status}" -ne 0 || -z "${group}" ||
    ! -f "${dir}/event_ids.txt" ]]; then
    testlib::fail "role-group: events.sh 가 종료 ${run_status} 이거나 맨 아래" \
      "묶음 · event_ids.txt 가 없음"
    return
  fi
  n=0
  while IFS= read -r line; do
    n=$((n + 1))
    want='권한 변경'
    has_line "${group}" "${line}" && want='역할 받기'
    got="$(grep -m 1 "^ev-${n}"$'\t' -- "${dir}/event_ids.txt" | cut -f 2)"
    if [[ "${got}" != "${want}" ]]; then
      testlib::fail "role-group: ${line//$'\t'/ } → 기대 「${want}」 · 실제" \
        "「${got}」 — event_names.txt 맨 아래 묶음과 is_role_assumption 이 다름"
      return
    fi
  done < <(data_rows "${EVENT_NAMES}")
  testlib::ok "role-group → 맨 아래 묶음 행만 역할 받기 · 나머지는 권한 변경"
}

#######################################
# 실제 scp-recheck.sh 가 실제 묶음을 모두 받는다(가짜 aws 전부 허용 —
# 종료 0 또는 3, 검사 불가 0). 실제 events.sh 는 event_names.txt 의 행마다
# 그 리전으로 한 번씩 조회한다.
# Globals:
#   BUNDLES, PRECHECK_DIR, run_status, run_out, EVENT_NAMES
#######################################
expect_real_scripts_accept_data() {
  local case_dir bundle source name region calls rows
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
  while IFS=$'\t' read -r source name region; do
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
  testlib::ok "real-scripts → 묶음 18개 모두 판정됨 · 이벤트 ${rows}행을 그 리전에서 조회"
}

#######################################
# 2-3 판정 데이터 파일 하나의 행(주석 · 빈 줄 뺌)을 낸다.
# Globals:
#   PRINCIPAL_DIR
# Arguments:
#   파일 이름.
# Outputs:
#   행(원문 그대로).
#######################################
principal_rows() {
  data_rows "${PRINCIPAL_DIR}/$1"
}

#######################################
# 데이터 단언: 2-3 판정 데이터 셋이 있고, 열 수(3 · 4 · 8)가 맞고 빈 칸이
# 없으며, 계정 자리는 ${ACCOUNT} 뿐(12자리 숫자 0), 구체 ARN(arn: 해석 ·
# 양성 대조 자원)에 「*」 가 없고, 첫 단계가 JUDGED_BUNDLES 안이다.
# Globals:
#   PRINCIPAL_DIR, JUDGED_BUNDLES
#######################################
expect_principal_files_wellformed() {
  local entry name want bad others stage method arn
  for entry in dev_principals.tsv:3 positive_controls.tsv:4 \
    prod_targets.tsv:8; do
    name="${entry%:*}"
    want="${entry##*:}"
    if [[ ! -f "${PRINCIPAL_DIR}/${name}" ]] ||
      [[ -z "$(principal_rows "${name}")" ]]; then
      testlib::fail "principal-files: ${name} 가 없거나 행이 없음"
      return
    fi
    bad="$(principal_rows "${name}" | awk -F '\t' -v n="${want}" '{
      ok = NF == n
      for (i = 1; i <= NF; i++) if ($i == "") ok = 0
      if (!ok) print NR
    }')"
    if [[ -n "${bad}" ]]; then
      testlib::fail "principal-files: ${name} 의 ${want}열이 아닌 행 —" \
        "$(printf '%s' "${bad}" | tr '\n' ' ')"
      return
    fi
    # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 글자 그대로다.
    others="$(grep -oE -- '\$\{[^}]*\}' "${PRINCIPAL_DIR}/${name}" |
      grep -vxF -- '${ACCOUNT}')"
    if grep -qE -- '[0-9]{12}' "${PRINCIPAL_DIR}/${name}" ||
      [[ -n "${others}" ]]; then
      testlib::fail "principal-files: ${name} 에 12자리 숫자 또는 다른" \
        "자리표시자(${others})"
      return
    fi
  done
  while IFS=$'\t' read -r _ stage method _; do
    if [[ "${method}" == arn:* && "${method}" == *'*'* ]]; then
      testlib::fail "principal-files: arn: 해석에 「*」 — ${method}"
      return
    fi
    if ! has_line "$(printf '%s\n' "${JUDGED_BUNDLES[@]}")" "${stage}"; then
      testlib::fail "principal-files: 첫 단계 ${stage} 가 판정 묶음 밖"
      return
    fi
  done < <(principal_rows prod_targets.tsv)
  while IFS=$'\t' read -r _ _ _ arn; do
    if [[ "${arn}" == *'*'* ]]; then
      testlib::fail "principal-files: 양성 대조 자원에 「*」 — ${arn}"
      return
    fi
  done < <(principal_rows positive_controls.tsv)
  testlib::ok "principal-files → 데이터 셋 3 · 4 · 8열 · 계정 자리" \
    "\${ACCOUNT} 뿐 · 구체 ARN 에 * 0 · 첫 단계는 판정 묶음"
}

#######################################
# 데이터 단언: 래퍼의 STAGE_ORDER 가 JUDGED_BUNDLES 와 순서까지 같다
# (cc348-2 — 래퍼 파일의 그 줄을 읽어 대조).
# Globals:
#   WRAPPER, JUDGED_BUNDLES
#######################################
expect_stage_order_matches_wrapper() {
  local line
  line="$(grep -m 1 -- '^STAGE_ORDER=(' "${WRAPPER}" 2>/dev/null)"
  if [[ "${line}" != "STAGE_ORDER=(${JUDGED_BUNDLES[*]})" ]]; then
    testlib::fail "stage-order: 래퍼의 STAGE_ORDER 가 JUDGED_BUNDLES 와" \
      "다름 — 「${line}」"
    return
  fi
  testlib::ok "stage-order → 래퍼 STAGE_ORDER = JUDGED_BUNDLES(순서까지)"
}

#######################################
# 서비스마다 쓸 수 있는 자원 유형(공백 구분 · 앞뒤 공백)을 낸다.
# Arguments:
#   서비스 접두.
# Outputs:
#   유형 목록(모르는 서비스면 빈 출력).
#######################################
service_types() {
  case "$1" in
    s3) printf ' bucket object \n' ;;
    secretsmanager) printf ' secret \n' ;;
    ecr) printf ' repository \n' ;;
    ec2)
      printf ' %s %s %s %s %s %s %s %s \n' security-group vpc subnet \
        route-table internet-gateway natgateway elastic-ip vpc-endpoint
      ;;
    acm) printf ' certificate \n' ;;
    route53) printf ' hostedzone \n' ;;
    iam) printf ' role user group policy instance-profile \n' ;;
    rds) printf ' db pg subgrp \n' ;;
    logs) printf ' log-group \n' ;;
    sns) printf ' topic \n' ;;
    sqs) printf ' queue \n' ;;
    ssm) printf ' instance \n' ;;
    *) ;;
  esac
}

#######################################
# 동작마다 글롭에 맞는지 본다.
# Arguments:
#   동작(쉼표 구분), 글롭.
# Returns:
#   모두 맞으면 0.
#######################################
all_actions_match() {
  local rest="$1," action
  while [[ -n "${rest}" ]]; do
    action="${rest%%,*}"
    rest="${rest#*,}"
    # shellcheck disable=SC2053 # 둘째 인자는 글롭이다.
    [[ "${action}" == $2 ]] || return 1
  done
}

#######################################
# 권한 변경 행의 유형과 동작 계열(이름에 그 낱말이 든 IAM 동작)을 본다.
# Arguments:
#   자원 유형, 동작(쉼표 구분), 기대 유형, 계열 낱말(Role · User · Group).
# Outputs:
#   틀린 까닭(맞으면 빈 출력).
#######################################
family_problem() {
  if [[ "$1" != "$3" ]] || ! all_actions_match "$2" "iam:*$4*"; then
    printf '%s 계열(*%s*) 동작 · %s 유형이 아님\n' "$4" "$4" "$3"
  fi
}

#######################################
# 동작이 모두 정책 버전 동작(새 버전 · 기본 버전 지정)인지 본다.
# Arguments:
#   동작(쉼표 구분).
# Returns:
#   모두 그러면 0.
#######################################
policy_version_actions() {
  local rest="$1," action
  while [[ -n "${rest}" ]]; do
    action="${rest%%,*}"
    rest="${rest#*,}"
    case "${action}" in
      iam:CreatePolicyVersion | iam:SetDefaultPolicyVersion) ;;
      *) return 1 ;;
    esac
  done
}

#######################################
# 대상 행 하나의 해석 방법이 유형 · 서비스와 맞는지 본다(동작 × 유형 짝
# 자체는 보지 않는다 — A9, 사람이 서비스 승인 참조로 대조).
# Arguments:
#   서비스 접두, 해석 방법, 자원 유형, 동작(쉼표 구분).
# Outputs:
#   틀린 까닭(맞으면 빈 출력).
#######################################
method_problem() {
  local service="$1" method="$2" type="$3" actions="$4" path
  case "${method}" in
    arn:*)
      [[ "${method#arn:}" == "arn:aws:${service}:"* ]] ||
        printf 'ARN 서비스가 동작과 다름\n'
      path="${method#arn:arn:aws:s3:::}"
      if [[ "${type}" == object && "${path}" != */?* ]]; then
        printf 'object 행 ARN 에 키가 없음\n'
      elif [[ "${type}" == bucket && "${path}" == */* ]]; then
        printf 'bucket 행 ARN 에 키가 있음\n'
      fi
      ;;
    tag:*)
      # SSM 의 instance 유형은 EC2 인스턴스 ARN 이다(서비스 승인 참조
      # list_ssm.html 「Resource types」) — 태그 해석도 ec2:instance 로 한다.
      if [[ "${service}:${type}" == ssm:instance ]]; then
        [[ "${method}" == tag:ec2:instance ]] ||
          printf 'ssm instance 행의 tag: 유형 조각이 ec2:instance 가 아님\n'
      elif [[ "${method}" != "tag:${service}:${type}" ]]; then
        printf 'tag: 유형 조각이 서비스 · 유형과 다름\n'
      fi
      ;;
    input:*)
      [[ "${method#input:}" =~ ^[a-z][a-z0-9-]*$ ]] ||
        printf 'input: 이름 꼴이 틀림\n'
      [[ "${service}:${type}" == secretsmanager:secret ]] ||
        printf 'input: 행이 secretsmanager secret 이 아님\n'
      ;;
    zone:*)
      [[ "${service}:${type}" == route53:hostedzone ]] ||
        printf 'zone: 행이 route53 hostedzone 이 아님\n'
      ;;
    self:role | prodrole) family_problem "${type}" "${actions}" role Role ;;
    self:user) family_problem "${type}" "${actions}" user User ;;
    self:group) family_problem "${type}" "${actions}" group Group ;;
    policy:attached)
      if [[ "${type}" != policy ]] ||
        ! policy_version_actions "${actions}"; then
        printf '정책 버전 동작 · policy 유형이 아님\n'
      fi
      ;;
    *) printf '모르는 해석 방법\n' ;;
  esac
}

#######################################
# 데이터 단언: 대상 행마다 동작이 한 서비스이고, IAM · Route 53 행의 리전은
# us-east-1, 유형은 그 서비스의 것, 해석 방법은 서비스 · 유형 · 계열과
# 맞고, 자원 정책을 가지는 서비스 행에 정책 읽기 none 이 없고, 래퍼가 정책을
# 읽는 서비스 행의 정책 읽기는 그 서비스다.
# Globals:
#   POLICY_SERVICES, READ_SERVICES
#######################################
expect_target_rows_consistent() {
  local key method type region actions read service problem count=0
  while IFS=$'\t' read -r key _ method type region actions read _; do
    count=$((count + 1))
    service="${actions%%:*}"
    problem=""
    if ! all_actions_match "${actions}" "${service}:*"; then
      problem='동작의 서비스가 둘 이상'
    elif [[ "${service}" == iam || "${service}" == route53 ]] &&
      [[ "${region}" != us-east-1 ]]; then
      problem="${service} 행의 리전이 us-east-1 이 아님"
    elif [[ "$(service_types "${service}")" != *" ${type} "* ]]; then
      problem="유형 ${type} 이 ${service} 의 것이 아님"
    elif [[ "${read}" == none &&
      "${POLICY_SERVICES}" == *" ${service} "* ]]; then
      problem='자원 정책 서비스인데 정책 읽기 none'
    elif [[ "${READ_SERVICES}" == *" ${service} "* &&
      "${read}" != "${service}" ]]; then
      problem="정책을 읽는 서비스인데 정책 읽기가 ${read}"
    else
      problem="$(method_problem "${service}" "${method}" "${type}" \
        "${actions}")"
    fi
    if [[ -n "${problem}" ]]; then
      testlib::fail "targets: ${key} — ${problem}"
      return
    fi
  done < <(principal_rows prod_targets.tsv)
  if [[ "${count}" -eq 0 ]]; then
    testlib::fail "targets: prod_targets.tsv 에 행이 없음"
    return
  fi
  testlib::ok "targets → 서비스 · 리전 · 유형 · 해석 방법 · 계열 일치 ·" \
    "자원 정책 서비스에 none 0 · 읽는 서비스는 그 읽기"
}

#######################################
# 데이터 단언: dev_principals.tsv 는 정확히 넷(instance-profile 둘 · role
# 하나 · user 하나)이고, 넷마다 양성 대조 행이 정확히 하나다.
#######################################
expect_dev_principals() {
  local kinds keys controls
  kinds="$(principal_rows dev_principals.tsv | cut -f 2 | LC_ALL=C sort |
    tr '\n' ' ')"
  if [[ "${kinds}" != 'instance-profile instance-profile role user ' ]]; then
    testlib::fail "principals: 종류가 instance-profile 둘 · role · user 가" \
      "아님 — ${kinds}"
    return
  fi
  keys="$(principal_rows dev_principals.tsv | cut -f 1 | LC_ALL=C sort)"
  controls="$(principal_rows positive_controls.tsv | cut -f 1 | LC_ALL=C sort)"
  if [[ "${controls}" != "${keys}" ]]; then
    testlib::fail "principals: 양성 대조 행이 주체마다 하나가 아님 —" \
      "$(printf '%s' "${controls}" | tr '\n' ' ')"
    return
  fi
  testlib::ok "principals → 고정 넷 · 주체마다 양성 대조 한 행"
}

#######################################
# 데이터 단언: PRINCIPAL_READS(동작 · 리전)가 판정 묶음 모두에 자원
# 「*」 로 있고(래퍼가 읽는 dev 주체 · 대상은 단계 자원 경로 밖이다 — 예 dev
# 역할의 get-role), 판정이 없는 묶음에는 그 동작이 없다(STAGE_OWN_READS 의
# 묶음 동작은 뺌).
# Globals:
#   JUDGED_BUNDLES, UNJUDGED_BUNDLES, PRINCIPAL_READS, STAGE_OWN_READS,
#   STAGE_DIR
#######################################
expect_principal_reads() {
  local bundle pairs actions read own
  own="$(printf '%s\n' "${STAGE_OWN_READS[@]}")"
  for bundle in "${JUDGED_BUNDLES[@]}"; do
    pairs="$(data_rows "${STAGE_DIR}/${bundle}.tsv" | cut -f 1,2,3 |
      tr '\t' ' ')"
    for read in "${PRINCIPAL_READS[@]}"; do
      if ! has_line "${pairs}" "${read} *"; then
        testlib::fail "principal-reads: ${bundle} 에 ${read}(자원 *) 없음"
        return
      fi
    done
  done
  for bundle in "${UNJUDGED_BUNDLES[@]}"; do
    actions="$(bundle_actions "${bundle}")"
    for read in "${PRINCIPAL_READS[@]}"; do
      read="${read% *}"
      has_line "${own}" "${bundle} ${read}" && continue
      if has_line "${actions}" "${read}"; then
        testlib::fail "principal-reads: 2-3 판정이 없는 ${bundle} 에 ${read}"
        return
      fi
    done
  done
  testlib::ok "principal-reads → 래퍼 읽기 동작 · 리전이 자원 * 로" \
    "판정 묶음 ${#JUDGED_BUNDLES[@]}개에만"
}

#######################################
# 실제 principal-check.sh 가 실제 데이터를 판정 단계마다 받는다(종료 2 —
# 데이터 꼴 등 검사 불가 — 가 아님). 가짜 aws 는 호출 주체의 질문만 허용하고
# 주체 해석은 NoSuchEntity 라, 데이터 꼴 · 첫 단계 · 해석 방법 · 추가 문맥을
# 래퍼의 파서가 그대로 읽는지를 본다.
# Globals:
#   JUDGED_BUNDLES, WRAPPER, FAKE_USER, run_status, run_out
#######################################
expect_wrapper_accepts_data() {
  local case_dir stage
  case_dir="$(testlib::new_case)" || {
    testlib::fail "wrapper-data: 시험 준비 실패"
    return
  }
  testlib::tsv "${FAKE_USER}@*" allowed None - - - >"${case_dir}/in/sim.tsv"
  for stage in "${JUDGED_BUNDLES[@]}"; do
    testlib::run "${case_dir}" "principal-${stage}" env \
      FAKE_AWS_SIM_TABLE="${case_dir}/in/sim.tsv" bash "${WRAPPER}" \
      "${stage}"
    if [[ "${run_status}" -ne 0 && "${run_status}" -ne 1 &&
      "${run_status}" -ne 3 ]]; then
      testlib::fail "wrapper-data: principal-check.sh ${stage} 가 종료" \
        "${run_status} — $(cat -- "${run_out}.stderr")"
      return
    fi
  done
  testlib::ok "wrapper-data → 실제 래퍼가 실제 데이터를 판정 단계" \
    "${#JUDGED_BUNDLES[@]}개 모두 받음"
}

#######################################
# 대상 행 하나(자원 키)를 「첫 단계<탭>해석 방법<탭>동작<탭>추가 문맥」 으로
# 낸다.
# Arguments:
#   자원 키.
# Outputs:
#   그 행의 네 칸(없으면 빈 출력).
#######################################
target_row() {
  principal_rows prod_targets.tsv | awk -F '\t' -v k="$1" \
    '$1 == k { print $2 "\t" $3 "\t" $6 "\t" $8 }'
}

#######################################
# 데이터 단언: 런타임 1단-가 자원 행(RUNTIME_TARGETS)이 그 첫 단계 ·
# 구체 ARN(이름 꼬리)으로 있고 반드시 묻는 동작을 담는다. 실행기 역할 행은
# EC2 로 넘기는 문맥을 단다.
# Globals:
#   RUNTIME_TARGETS, RUNTIME_ARN_TAILS, PASSED_TO_EC2
#######################################
expect_runtime_targets() {
  local i key want_stage action tail stage method actions context
  if [[ "${#RUNTIME_TARGETS[@]}" -ne "${#RUNTIME_ARN_TAILS[@]}" ]]; then
    testlib::fail "runtime-targets: 시험 표 둘의 길이가 다름"
    return
  fi
  for i in "${!RUNTIME_TARGETS[@]}"; do
    read -r key want_stage action <<<"${RUNTIME_TARGETS[i]}"
    tail="${RUNTIME_ARN_TAILS[i]}"
    IFS=$'\t' read -r stage method actions context <<<"$(target_row "${key}")"
    if [[ "${stage}" != "${want_stage}" ]]; then
      testlib::fail "runtime-targets: ${key} 의 첫 단계가 ${want_stage} 이" \
        "아님 — 「${stage}」"
      return
    fi
    # 꼬리는 글롭(rewind 객체의 ?*)이라 따옴표로 감싸지 않는다.
    if [[ "${method}" != arn:* || "${method}" != *${tail} ]]; then
      testlib::fail "runtime-targets: ${key} 의 ARN 이 ${tail} 로 끝나지 않음"
      return
    fi
    if [[ ",${actions}," != *",${action},"* ]]; then
      testlib::fail "runtime-targets: ${key} 에 ${action} 이 없음"
      return
    fi
    if [[ "${key}" == sql-runner-role && "${context}" != "${PASSED_TO_EC2}" ]]
    then
      testlib::fail "runtime-targets: ${key} 의 추가 문맥이 EC2 넘기기가 아님"
      return
    fi
  done
  testlib::ok "runtime-targets → 런타임 자원 ${#RUNTIME_TARGETS[@]}행이" \
    "첫 단계 · 이름 · 핵심 동작대로"
}

#######################################
# 데이터 단언: 켬 · apply 뒤에 정해지는 런타임 자원 행(LATE_TARGETS)이 그
# 첫 단계 · 해석 방법으로 있고 반드시 묻는 동작을 모두 담는다(rt1a 종결 r1
# H2 · H4).
# Globals:
#   LATE_TARGETS
#######################################
expect_late_targets() {
  local entry key want_stage want_method want_actions action stage method
  local actions
  for entry in "${LATE_TARGETS[@]}"; do
    read -r key want_stage want_method want_actions <<<"${entry}"
    IFS=$'\t' read -r stage method actions _ <<<"$(target_row "${key}")"
    if [[ "${stage}" != "${want_stage}" || "${method}" != "${want_method}" ]]
    then
      testlib::fail "late-targets: ${key} 의 첫 단계 · 해석 방법이" \
        "${want_stage} · ${want_method} 가 아님 — 「${stage} · ${method}」"
      return
    fi
    for action in ${want_actions//,/ }; do
      if [[ ",${actions}," != *",${action},"* ]]; then
        testlib::fail "late-targets: ${key} 에 ${action} 이 없음"
        return
      fi
    done
  done
  testlib::ok "late-targets → 실행기 인스턴스(태그) · 관리형 마스터 비밀" \
    "(입력 파일) 행이 첫 단계 · 해석 방법 · 핵심 동작대로"
}

#######################################
# 실제 dev 주체 이름을 가짜 aws 의 주체 해석 답(FAKE_AWS_READS 표)으로 낸다
# (인스턴스 프로필은 같은 이름의 역할).
# Outputs:
#   표 줄들.
#######################################
principal_reads_table() {
  local key kind name arn="arn:aws:iam::${FAKE_ACCOUNT}"
  while IFS=$'\t' read -r key kind name; do
    case "${kind}" in
      instance-profile)
        testlib::tsv get-instance-profile "${name}" "${arn}:role/${name}"
        ;;
      role) testlib::tsv get-role "${name}" "${arn}:role/${name}" ;;
      *) testlib::tsv get-user "${name}" "${arn}:user/${name}" ;;
    esac
  done < <(principal_rows dev_principals.tsv)
}

#######################################
# 런타임 자원 행 하나가 그 단계 실행의 요청(req-*.json)에 동작마다 실렸는지
# (또는 첫 단계 전이라 하나도 안 실렸는지) 본다.
# Globals:
#   FAKE_ACCOUNT, PASSED_TO_EC2, PASSED_TO_EC2_ENTRY
# Arguments:
#   증거 디렉터리, 자원 키, 실려야 하면 1.
# Outputs:
#   틀린 까닭(맞으면 빈 출력).
#######################################
runtime_row_problem() {
  local dir="$1" key="$2" want="$3" stage method actions context arn action
  local request
  IFS=$'\t' read -r stage method actions context <<<"$(target_row "${key}")"
  if [[ -z "${actions}" ]]; then
    printf '%s 행이 없음\n' "${key}"
    return
  fi
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 글자 그대로다.
  arn="$(printf '%s' "${method#arn:}" | sed "s/\${ACCOUNT}/${FAKE_ACCOUNT}/")"
  if [[ "${want}" -eq 0 ]]; then
    if grep -qF -- "\"ResourceArns\":[\"${arn}\"]" "${dir}"/req-*.json; then
      printf '%s 가 첫 단계 %s 전에 물어짐\n' "${key}" "${stage}"
    fi
    return
  fi
  for action in ${actions//,/ }; do
    request="\"ActionNames\":[\"${action}\"],\"ResourceArns\":[\"${arn}\"]"
    if ! grep -qF -- "${request}" "${dir}"/req-*.json; then
      printf '%s 의 %s 가 요청에 없음\n' "${key}" "${action}"
      return
    fi
  done
  if [[ "${context}" == "${PASSED_TO_EC2}" ]] &&
    ! grep -qF -- "${PASSED_TO_EC2_ENTRY}" "${dir}"/req-*.json; then
    printf '%s 의 EC2 넘기기 문맥이 요청에 없음\n' "${key}"
  fi
}

#######################################
# 실제 principal-check.sh 를 실제 데이터로 runtime-1a · O7d 에 돌려(주체 넷이
# 풀리는 가짜 aws), 런타임 자원 행이 첫 단계부터 시뮬레이터 요청에 실리고
# 그 전에는 실리지 않는지 본다.
# Globals:
#   RUNTIME_TARGETS, FAKE_USER, run_status, run_out
#######################################
expect_runtime_rows_asked() {
  local case_dir stage dir entry key first want problem
  case_dir="$(testlib::new_case)" || {
    testlib::fail "runtime-asked: 시험 준비 실패"
    return
  }
  principal_reads_table >"${case_dir}/in/reads.tsv"
  testlib::tsv "${FAKE_USER}@*" allowed None - - - >"${case_dir}/in/sim.tsv"
  for stage in runtime-1a O7d; do
    testlib::run "${case_dir}" "principal-${stage}" env \
      FAKE_AWS_READS="${case_dir}/in/reads.tsv" \
      FAKE_AWS_SIM_TABLE="${case_dir}/in/sim.tsv" bash "${WRAPPER}" "${stage}"
    dir="$(compgen -G \
      "${case_dir}/home/.pokeclip-precheck/principal-${stage}-*")"
    if [[ "${run_status}" -ne 3 || ! -d "${dir}" ]]; then
      testlib::fail "runtime-asked: ${stage} 실행이 종료 ${run_status} —" \
        "$(cat -- "${run_out}.stderr")"
      return
    fi
    for entry in "${RUNTIME_TARGETS[@]}"; do
      read -r key first _ <<<"${entry}"
      want=1
      [[ "${stage}" == runtime-1a && "${first}" != runtime-1a ]] && want=0
      problem="$(runtime_row_problem "${dir}" "${key}" "${want}")"
      if [[ -n "${problem}" ]]; then
        testlib::fail "runtime-asked: ${stage} — ${problem}"
        return
      fi
    done
  done
  testlib::ok "runtime-asked → 런타임 자원 행이 첫 단계부터 동작마다" \
    "시뮬레이터 요청에 실림(그 전 단계 0)"
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
  expect_role_group_matches_code
  expect_real_scripts_accept_data
  expect_principal_files_wellformed
  expect_stage_order_matches_wrapper
  expect_target_rows_consistent
  expect_runtime_targets
  expect_late_targets
  expect_runtime_rows_asked
  expect_dev_principals
  expect_principal_reads
  expect_stage_own_reads_scoped
  expect_wrapper_accepts_data
  testlib::finish data_files_test
}

main "$@"
