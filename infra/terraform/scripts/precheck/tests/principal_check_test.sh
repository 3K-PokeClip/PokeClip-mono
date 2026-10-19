#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — 계획 M17b r3.1 3-1 사례 1 ~ 25(갈래 A ·
# SCP 제외 (가) · 알림 주체 없음)와 r1 검수 지적의 사례 26 ~ 31.
#
# 사례마다 점검 스크립트의 사본 트리를 만들고 data/principal_sim/ 에 고정
# 입력(주체 넷 · 양성 대조 · prod 대상 행)을 넣는다. 실제 데이터는
# data_files_test.sh 가 본다. 가짜 aws 의 읽기 답(FAKE_AWS_READS)과
# 시뮬레이터 답(FAKE_AWS_SIM_TABLE)은 사례 디렉터리의 in/ 에 두고, 사례가
# 바꿀 줄을 표 맨 위에 덧붙인다(맨 위에서 처음 맞는 줄을 쓴다).
#
# 유출 표식: 계정 ID · 호출 주체(testlib) 말고도 인스턴스 프로필 · 역할 ·
# 사용자 · 그룹 · 고객 관리형 정책 · 버킷 · 저장소 · 비밀 · 존 이름에 서로
# 다른 표식을 쓴다. 모든 사례가 끝에 표준 출력 · 표준 오류 · 가짜 aws argv ·
# 증거 루트 밖 파일(사례의 HOME · 출력 · 작업 디렉터리)에 표식이 0건인지,
# 표준 출력 · 표준 오류에 절대 경로가 0건인지 본다(사례 22). 래퍼는 사례의
# cwd/ 를 작업 디렉터리로 삼아 돈다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/principal_check_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly REJECTED='EVIDENCE PATH REJECTED'
# 판정 한 번의 제한 시간(초).
readonly RUN_LIMIT=30
# 이름 종류별 유출 표식(경로에 나올 수 없는 문자열).
readonly MARK_PROFILE='qxprof'
readonly MARK_ROLE='qxrole'
readonly MARK_USER='qxuser'
readonly MARK_GROUP='qxgroup'
readonly MARK_POLICY='qxpolicy'
readonly MARK_BUCKET='qxbucket'
readonly MARK_REPO='qxrepo'
readonly MARK_SECRET='qxsecret'
readonly MARK_ZONE='qxzone'
readonly ACCOUNT_ARN="arn:aws:iam::${FAKE_ACCOUNT}"
readonly ROLE_BOX="${ACCOUNT_ARN}:role/${MARK_ROLE}-box"
readonly ROLE_OIDC="${ACCOUNT_ARN}:role/${MARK_ROLE}-oidc"
readonly ROLE_PROD="${ACCOUNT_ARN}:role/pokeclip/prod/${MARK_ROLE}-prod"
readonly USER_DEPLOY="${ACCOUNT_ARN}:user/${MARK_USER}-deploy"
readonly GROUP_DEPLOY="${ACCOUNT_ARN}:group/${MARK_GROUP}-deploy"
readonly POLICY_BOX="${ACCOUNT_ARN}:policy/${MARK_POLICY}-box"
readonly STATE_BUCKET="arn:aws:s3:::${MARK_BUCKET}-state"
readonly SEOUL_ACCOUNT="ap-northeast-2:${FAKE_ACCOUNT}"
SECRET_ARN="arn:aws:secretsmanager:${SEOUL_ACCOUNT}:secret:pokeclip/prod/"
SECRET_ARN+="${MARK_SECRET}-db-AbCdEf"
readonly SECRET_ARN
REPO_ARN="arn:aws:ecr:${SEOUL_ACCOUNT}:repository/pokeclip-prod/"
REPO_ARN+="${MARK_REPO}-api"
readonly REPO_ARN
readonly RTB_ARN="arn:aws:ec2:${SEOUL_ACCOUNT}:route-table/rtb-0a1"
readonly ZONE_NAME="media.${MARK_ZONE}.test."
readonly DEV_RECORD="dev.${MARK_ZONE}.test."
# Route 53 레코드 이름 문맥 키(추가 문맥 칸의 「키:형」 · 요청의 ContextKeyName).
readonly RECORD_KEY='route53:ChangeResourceRecordSetsNormalizedRecordNames'
readonly DEV_BUCKET="arn:aws:s3:::${MARK_BUCKET}-dev"
# 래퍼 상수(PC0 · PC1 의 prod 상태 버킷, PC-A 의 없는 표본 버킷).
readonly PC_BUCKET='arn:aws:s3:::pokeclip-tfstate-prod-apne2'
readonly PCA_SAMPLE='arn:aws:s3:::pokeclip-prod-m17b-probe-nonexistent-9f3a'
# 고정 입력 정상 실행(O7a)의 판정 행 수: dev-box · media-dev(합침) 11 ·
# dev-deployer 11 · dev-oidc 10.
readonly NORMAL_ROWS=32
# 둘째 줄부터의 표: | 주체 키 | 자원 키 | 동작 | 리전 | 판정 | 사유 |
ROW_PATTERN='^\| ([a-z][a-z0-9,-]*|-) \| ([a-z][a-z0-9-]*|-) \| '
ROW_PATTERN+='([a-z0-9-]+:[A-Za-z0-9]+|-) \| ([a-z0-9-]+|-) \| '
ROW_PATTERN+='(멈춤|판정 불가) \| [A-Z_]+ \|$'
readonly ROW_PATTERN
# 고정 문맥(요청 파일에 그대로 있어야 하는 꼴).
SECURE_ENTRY='{"ContextKeyName":"aws:SecureTransport","ContextKeyValues":'
SECURE_ENTRY+='["true"],"ContextKeyType":"boolean"}'
readonly SECURE_ENTRY
TLS_ENTRY='{"ContextKeyName":"s3:TlsVersion","ContextKeyValues":["1.3"],'
TLS_ENTRY+='"ContextKeyType":"numeric"}'
readonly TLS_ENTRY
REGION_ENTRY='\{"ContextKeyName":"aws:RequestedRegion","ContextKeyValues":'
REGION_ENTRY+='\["([^"]*)"\],"ContextKeyType":"string"\}'
readonly REGION_ENTRY
readonly SCP_ONLY='"PolicyExclusionList":[{"PolicyType":"scp"}]'

#######################################
# 고정 입력 데이터 셋을 쓴다(정상 실행이 PASS 인 것).
#   주체: dev-box · media-dev 는 같은 인스턴스 프로필, dev-deployer 사용자,
#   dev-oidc 역할.
#   대상: 상태 버킷 · 객체(s3 정책 — Deny 만), 비밀 · ECR(tag:), 라우트
#   테이블(첫 단계 O7a), 존 두 갈래(prod · dev 표본 + prod), 권한 변경 행
#   다섯, 첫 단계가 9 인 버킷.
# Arguments:
#   사본 트리의 data/principal_sim 디렉터리.
#######################################
write_data() {
  local data="$1"
  {
    printf '# 출처: 시험 고정 입력\n'
    testlib::tsv dev-box instance-profile "${MARK_PROFILE}-dev"
    testlib::tsv media-dev instance-profile "${MARK_PROFILE}-dev"
    printf '\n'
    testlib::tsv dev-deployer user "${MARK_USER}-deploy"
    testlib::tsv dev-oidc role "${MARK_ROLE}-oidc"
  } >"${data}/dev_principals.tsv" || return 1
  {
    printf '# 출처: 시험 고정 입력\n'
    testlib::tsv dev-box s3:ListBucket ap-northeast-2 \
      "arn:aws:s3:::${MARK_BUCKET}-dev"
    testlib::tsv media-dev s3:ListBucket ap-northeast-2 \
      "arn:aws:s3:::${MARK_BUCKET}-dev"
    testlib::tsv dev-deployer ssm:SendCommand ap-northeast-2 \
      'arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript'
    testlib::tsv dev-oidc ssm:SendCommand ap-northeast-2 \
      'arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript'
  } >"${data}/positive_controls.tsv" || return 1
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 계정 자리다.
  {
    printf '# 출처: 시험 고정 입력\n'
    testlib::tsv state-bucket 5 "arn:${STATE_BUCKET}" bucket ap-northeast-2 \
      s3:ListBucket,s3:PutBucketPolicy s3 -
    testlib::tsv state-object 5 \
      "arn:${STATE_BUCKET}/prod/network/terraform.tfstate" object \
      ap-northeast-2 s3:GetObject s3 -
    testlib::tsv prod-secret 5 tag:secretsmanager:secret secret \
      ap-northeast-2 secretsmanager:GetSecretValue secretsmanager -
    testlib::tsv prod-ecr 5 tag:ecr:repository repository ap-northeast-2 \
      ecr:PutImage ecr -
    testlib::tsv prod-rtb O7a tag:ec2:route-table route-table \
      ap-northeast-2 ec2:CreateRoute none -
    testlib::tsv prod-zone 5 "zone:media.${MARK_ZONE}.test" hostedzone \
      us-east-1 route53:ChangeResourceRecordSets none \
      "${RECORD_KEY}:stringList:${ZONE_NAME}"
    testlib::tsv prod-zone-mix 5 "zone:media.${MARK_ZONE}.test" hostedzone \
      us-east-1 route53:ChangeResourceRecordSets none \
      "${RECORD_KEY}:stringList:${DEV_RECORD},${ZONE_NAME}"
    testlib::tsv self-role 5 self:role role us-east-1 iam:PutRolePolicy none -
    testlib::tsv self-user 5 self:user user us-east-1 iam:PutUserPolicy none -
    testlib::tsv self-group 5 self:group group us-east-1 iam:PutGroupPolicy \
      none -
    testlib::tsv prod-role 5 prodrole role us-east-1 iam:PutRolePolicy none -
    testlib::tsv attached 5 policy:attached policy us-east-1 \
      iam:CreatePolicyVersion none -
    testlib::tsv late-bucket 9 "arn:arn:aws:s3:::${MARK_BUCKET}-late" bucket \
      ap-northeast-2 s3:ListBucket s3 -
  } >"${data}/prod_targets.tsv"
}

#######################################
# 가짜 aws 의 기본 읽기 답 · 시뮬레이터 답 표를 쓴다. 기본 시뮬레이터 표는
# PC0 · PC1 · PC2 · PC-A ⓑ 만 허용한다(나머지는 implicitDeny).
# Arguments:
#   사례 디렉터리.
#######################################
write_tables() {
  local in="$1/in" deny_only
  deny_only='{"Version":"2012-10-17","Statement":[{"Effect":"Deny",'
  deny_only+='"Principal":"*","Action":"s3:*","Resource":"'"${STATE_BUCKET}"'",'
  deny_only+='"Condition":{"Bool":{"aws:SecureTransport":"false"}}}]}'
  {
    testlib::tsv get-instance-profile "${MARK_PROFILE}-dev" "${ROLE_BOX}"
    testlib::tsv get-user "${MARK_USER}-deploy" "${USER_DEPLOY}"
    testlib::tsv get-role "${MARK_ROLE}-oidc" "${ROLE_OIDC}"
    testlib::tsv list-groups-for-user "${MARK_USER}-deploy" "${GROUP_DEPLOY}"
    testlib::tsv list-roles /pokeclip/prod/ "${ROLE_PROD}"
    testlib::tsv list-attached-role-policies "${MARK_ROLE}-box" \
      "${POLICY_BOX}\\tarn:aws:iam::aws:policy/ReadOnlyAccess"
    testlib::tsv get-resources secretsmanager:secret@ap-northeast-2 \
      "${SECRET_ARN}"
    testlib::tsv get-resources ecr:repository@ap-northeast-2 "${REPO_ARN}"
    testlib::tsv get-resources ec2:route-table@ap-northeast-2 "${RTB_ARN}"
    testlib::tsv list-hosted-zones-by-name "media.${MARK_ZONE}.test." \
      "${ZONE_NAME}\\t/hostedzone/ZQXZONE1\\tFalse"
    testlib::tsv get-bucket-policy "${MARK_BUCKET}-state" "${deny_only}"
  } >"${in}/reads.tsv" || return 1
  {
    testlib::tsv "${FAKE_USER}@s3:ListBucket@${PC_BUCKET}@*" allowed None - - -
    testlib::tsv "${MARK_ROLE}-box@s3:ListBucket@${DEV_BUCKET}@*" allowed \
      None - - -
    testlib::tsv "${MARK_USER}-deploy@ssm:SendCommand@*" allowed None - - -
    testlib::tsv "${MARK_ROLE}-oidc@ssm:SendCommand@*" allowed None - - -
    testlib::tsv "${MARK_ROLE}-oidc@s3:ListBucket@${PCA_SAMPLE}@*@policy" \
      allowed None - - -
  } >"${in}/sim.tsv"
}

#######################################
# 새 사례 디렉터리 · 사본 트리 · 고정 입력을 만든다. 명령 치환 안에서
# 불리므로 실패는 종료 코드로만 알린다(부르는 쪽이 센다).
# Outputs:
#   사례 디렉터리.
# Returns:
#   준비 실패면 1.
#######################################
new_tree_case() {
  local case_dir
  case_dir="$(testlib::new_case)" || return 1
  mkdir -- "${case_dir}/cwd" || return 1
  testlib::make_principal_tree "${case_dir}/tree" 2>/dev/null || return 1
  write_data "${case_dir}/tree/data/principal_sim" || return 1
  write_tables "${case_dir}" || return 1
  printf '%s\n' "${case_dir}"
}

#######################################
# 표 파일 맨 위에 줄들을 덧붙인다(맨 위에서 처음 맞는 줄을 쓴다).
# Arguments:
#   표 파일, 줄들(한 인자에 한 줄).
#######################################
prepend() {
  local file="$1" old
  shift
  old="$(cat -- "${file}")" || return 1
  printf '%s\n' "$@" "${old}" >"${file}"
}

#######################################
# 사본 트리의 principal-check.sh 를 사례의 cwd/ 에서 제한 시간 안에 돌린다.
# 「이름=값」 꼴 인자는 환경으로 넘긴다(FAKE_AWS_* · AWS_CLI_HISTORY_FILE).
# 읽기 · 시뮬레이터 표는 사례의 in/ 것.
# Globals:
#   RUN_LIMIT
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 단계.
#######################################
run_check() {
  local case_dir="$1" name="$2"
  local -a envs=("FAKE_AWS_READS=${case_dir}/in/reads.tsv"
    "FAKE_AWS_SIM_TABLE=${case_dir}/in/sim.tsv")
  shift 2
  while [[ "$#" -gt 0 && ( "$1" == FAKE_AWS_*=* ||
    "$1" == AWS_CLI_HISTORY_FILE=* ) ]]; do
    envs+=("$1")
    shift
  done
  # shellcheck disable=SC2016 # 안쪽 bash 가 펼친다.
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    env "${envs[@]}" bash -c 'cd -- "$1" && shift && exec bash "$@"' _ \
    "${case_dir}/cwd" "${case_dir}/tree/principal-check.sh" "$@"
}

#######################################
# 사례의 증거 디렉터리(가장 최근 principal-*)를 낸다.
# Arguments:
#   사례 디렉터리.
# Outputs:
#   경로(없으면 빈 출력).
#######################################
evidence_dir() {
  local dir found=""
  for dir in "$1"/home/.pokeclip-precheck/principal-*; do
    [[ -d "${dir}" ]] && found="${dir}"
  done
  printf '%s\n' "${found}"
}

#######################################
# 마지막 실행의 종료 코드 · 첫 줄을 보고, 둘째 줄부터가 모두 표 꼴인지 본다.
# Globals:
#   run_out, run_status, ROW_PATTERN
# Arguments:
#   사례 이름, 기대 종료 코드, 기대 첫 줄.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_result() {
  local name="$1" status="$2" first="$3" got bad
  got="$(head -n 1 -- "${run_out}.stdout")"
  bad="$(tail -n +2 -- "${run_out}.stdout" | grep -cvE -- "${ROW_PATTERN}")"
  if [[ "${run_status}" -ne "${status}" || "${got}" != "${first}" ]]; then
    testlib::fail "${name}: 종료 ${status} · 「${first}」 를 기대했으나" \
      "${run_status} · 「${got}」 — $(cat -- "${run_out}.stderr")"
    return 1
  fi
  if [[ "${bad}" -ne 0 ]]; then
    testlib::fail "${name}: 표 꼴 밖 줄 ${bad}개 — $(cat -- "${run_out}.stdout")"
    return 1
  fi
}

#######################################
# 마지막 실행이 검사 불가(종료 2 · 첫 줄 없음)였는지 본다.
# Globals:
#   run_status, run_out
# Arguments:
#   사례 이름.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_unverifiable() {
  local name="$1"
  if [[ "${run_status}" -ne 2 ]]; then
    testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status} —" \
      "$(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return 1
  fi
  if grep -q '^PRINCIPAL CHECK ' -- "${run_out}.stdout"; then
    testlib::fail "${name}: 검사 불가인데 판정 줄이 나옴"
    return 1
  fi
}

#######################################
# 마지막 실행의 표준 출력에 표 행이 몇 개 있는지 본다.
# Globals:
#   run_out
# Arguments:
#   사례 이름, 행에 든 고정 문자열, 기대 행 수.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_rows() {
  local name="$1" text="$2" want="$3" got
  got="$(testlib::count "${run_out}.stdout" "${text}")"
  if [[ "${got}" -ne "${want}" ]]; then
    testlib::fail "${name}: 「${text}」 행 ${want}개를 기대했으나 ${got}개 —" \
      "$(cat -- "${run_out}.stdout")"
    return 1
  fi
}

#######################################
# 사례 22: 표준 출력 · 표준 오류 · 가짜 aws argv · 증거 루트 밖 파일(HOME ·
# 출력 · 작업 디렉터리)에 유출 표식이 0건이고, 표준 출력 · 표준 오류에 절대
# 경로가 0건인지 본다.
# Globals:
#   FAKE_ACCOUNT, FAKE_USER, MARK_*
# Arguments:
#   사례 이름, 사례 디렉터리.
# Returns:
#   0건이면 0(아니면 실패를 센다).
#######################################
expect_no_leak() {
  local name="$1" case_dir="$2" root file leaked="" paths
  root="${case_dir}/home/.pokeclip-precheck"
  if [[ ! -d "${case_dir}/home" || ! -d "${case_dir}/out" ||
    ! -d "${case_dir}/cwd" ]]; then
    testlib::fail "${name}: 훑을 사례 디렉터리가 없음"
    return 1
  fi
  while IFS= read -r -d '' file; do
    [[ "${file}" == "${root}"/* ]] && continue
    if grep -qF -e "${FAKE_ACCOUNT}" -e "${FAKE_USER}" -e "${MARK_PROFILE}" \
      -e "${MARK_ROLE}" -e "${MARK_USER}" -e "${MARK_GROUP}" \
      -e "${MARK_POLICY}" -e "${MARK_BUCKET}" -e "${MARK_REPO}" \
      -e "${MARK_SECRET}" -e "${MARK_ZONE}" -e 'arn:aws' -- "${file}"; then
      leaked+=" ${file##*/}"
    fi
  done < <(find "${case_dir}/home" "${case_dir}/out" "${case_dir}/cwd" \
    -type f -print0)
  if [[ -n "${leaked}" ]]; then
    testlib::fail "${name}: 증거 루트 밖에 표식 ·${leaked}"
    return 1
  fi
  for file in "${case_dir}"/out/*.stdout "${case_dir}"/out/*.stderr; do
    [[ -f "${file}" ]] || continue
    paths="$(testlib::abs_path_lines "${file}")"
    if [[ -n "${paths}" ]]; then
      testlib::fail "${name}: 절대 경로 — ${paths}"
      return 1
    fi
  done
}

#######################################
# 증거 디렉터리의 시뮬레이터 요청 파일(PolicySourceArn 이 든 req-*.json)을
# 번호 순서로 낸다.
# Arguments:
#   증거 디렉터리.
# Outputs:
#   파일 경로 줄들.
#######################################
sim_requests() {
  local n=1 file
  while [[ -e "$1/req-${n}.json" ]]; do
    file="$1/req-${n}.json"
    grep -qF '"PolicySourceArn"' -- "${file}" && printf '%s\n' "${file}"
    n=$((n + 1))
  done
}

#######################################
# 증거 디렉터리의 req-*.json 가운데 고정 문자열이 모두 든 것의 수를 낸다.
# Arguments:
#   증거 디렉터리, 문자열들.
# Outputs:
#   개수.
#######################################
count_requests() {
  local dir="$1" file text count=0 hit
  shift
  for file in "${dir}"/req-*.json; do
    [[ -f "${file}" ]] || continue
    hit=1
    for text in "$@"; do
      grep -qF -- "${text}" "${file}" || hit=0
    done
    count=$((count + hit))
  done
  printf '%s\n' "${count}"
}

#######################################
# 시뮬레이터 요청 하나의 계약 위반을 낸다(사례 4): 제외 목록이 정확히 scp
# 하나 · 동작 하나 · 자원 하나 · SecureTransport · 리전 문맥(PC0 은
# ap-northeast-1, 나머지는 그 밖) · s3 동작이면 TlsVersion.
# Globals:
#   SCP_ONLY, SECURE_ENTRY, TLS_ENTRY, REGION_ENTRY
# Arguments:
#   요청 파일, pc0(PC0 요청) · row(그 밖).
# Outputs:
#   위반 설명(없으면 빈 출력).
#######################################
request_problems() {
  local body kind="$2" rest region
  body="$(cat -- "$1")" || {
    printf '읽지 못함\n'
    return
  }
  rest="${body#*'"PolicyType"'}"
  if [[ "${body}" != *"${SCP_ONLY}"* || "${rest}" == *'"PolicyType"'* ]]; then
    printf '제외 목록이 scp 하나가 아님\n'
  fi
  [[ "${body}" =~ \"ActionNames\":\[\"[^\"]+\"\] ]] ||
    printf '동작이 하나가 아님\n'
  [[ "${body}" =~ \"ResourceArns\":\[\"[^\"]+\"\] ]] ||
    printf '자원이 하나가 아님\n'
  [[ "${body}" == *"${SECURE_ENTRY}"* ]] || printf 'SecureTransport 없음\n'
  if [[ "${body}" =~ ${REGION_ENTRY} ]]; then
    region="${BASH_REMATCH[1]}"
  else
    region=""
  fi
  if [[ "${kind}" == pc0 && "${region}" != ap-northeast-1 ]]; then
    printf 'PC0 리전 값이 ap-northeast-1 아님\n'
  elif [[ "${kind}" != pc0 && ( -z "${region}" ||
    "${region}" == ap-northeast-1 ) ]]; then
    printf '행의 리전 문맥이 없거나 PC0 리전\n'
  fi
  if [[ "${body}" == *'"ActionNames":["s3:'* &&
    "${body}" != *"${TLS_ENTRY}"* ]]; then
    printf 's3 동작에 TlsVersion 없음\n'
  fi
}

#######################################
# 사례 1: 정상 → 종료 0 · 첫 줄 · 표 없음 · complete.txt.
#######################################
expect_normal() {
  local case_dir dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "normal: 시험 준비 실패"
    return
  }
  run_check "${case_dir}" normal O7a
  expect_result normal 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
  if [[ "$(wc -l <"${run_out}.stdout")" -ne 1 ]]; then
    testlib::fail "normal: 통과인데 표 행이 나옴"
    return
  fi
  dir="$(evidence_dir "${case_dir}")"
  if [[ -z "${dir}" || ! -f "${dir}/complete.txt" ||
    ! -f "${dir}/result.tsv" || ! -f "${dir}/report.txt" ]]; then
    testlib::fail "normal: 증거 디렉터리 · complete.txt · result.tsv 없음"
    return
  fi
  expect_no_leak normal "${case_dir}" || return
  testlib::ok "1 normal → 종료 0 · PASS ${NORMAL_ROWS} · complete.txt"
}

#######################################
# 사례 2 · 3: PC0 · PC1 거부 → 종료 2, 그 뒤 해석 · 정책 읽기 호출 0건.
# Globals:
#   run_out
#######################################
expect_pc0_pc1_order() {
  local case_dir name region log sims others
  for name in pc0 pc1; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    region=ap-northeast-1
    [[ "${name}" == pc1 ]] && region=ap-northeast-2
    prepend "${case_dir}/in/sim.tsv" \
      "$(testlib::tsv "${FAKE_USER}@s3:ListBucket@${PC_BUCKET}@${region}@-" \
        implicitDeny None - - -)"
    run_check "${case_dir}" "${name}" O7a
    expect_unverifiable "${name}" || return
    log="${case_dir}/out/aws.log"
    sims="$(testlib::count "${log}" 'iam simulate-principal-policy')"
    others="$(grep -vE -- \
      '^(sts get-caller-identity |configure get cli_history$)' "${log}" |
      grep -cvE -- '^iam simulate-principal-policy ')"
    # PC1 은 리전 둘(ap-northeast-2 · us-east-1) — 거부가 첫 리전이면 2회.
    if [[ "${others}" -ne 0 ]] ||
      { [[ "${name}" == pc0 && "${sims}" -ne 1 ]]; } ||
      { [[ "${name}" == pc1 ]] && ((sims < 2 || sims > 3)); }; then
      testlib::fail "${name}: 거부 뒤 호출이 나감(시뮬레이터 ${sims} ·" \
        "그 밖 ${others}) — $(cat -- "${log}")"
      return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "2 · 3 pc0 · pc1 거부 → 종료 2 · 그 뒤 해석 · 정책 읽기 · 실제 질문 0건"
}

#######################################
# 사례 4: 요청 꼴 — 모든 시뮬레이터 요청이 계약대로(PC0 는 리전
# ap-northeast-1), 모든 AWS 호출이 --cli-input-json file:// 이고 argv 에
# 계정 ID · ARN · 이름 0건. 위반 판정기 자체도 섞인 제외 목록 · 서울 리전
# PC0 를 잡는지 본다.
#######################################
expect_request_shape() {
  local case_dir dir file first=pc0 problems="" bad
  case_dir="$(new_tree_case)" || {
    testlib::fail "request-shape: 시험 준비 실패"
    return
  }
  run_check "${case_dir}" request-shape O7a
  expect_result request-shape 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" ||
    return
  dir="$(evidence_dir "${case_dir}")"
  while IFS= read -r file; do
    if [[ "${first}" == pc0 ]] &&
      ! grep -qF -e "\"ResourceArns\":[\"${PC_BUCKET}\"]" -- "${file}"; then
      problems+=" ${file##*/}: 첫 시뮬레이터 요청이 PC0 아님"
    fi
    bad="$(request_problems "${file}" "${first}")"
    [[ -z "${bad}" ]] || problems+=" ${file##*/}: ${bad//$'\n'/ · }"
    first=row
  done < <(sim_requests "${dir}")
  if [[ "${first}" == pc0 || -n "${problems}" ]]; then
    testlib::fail "request-shape: 요청 계약 위반 —${problems:- 요청 없음}"
    return
  fi
  bad="$(grep -vE -- '^(sts get-caller-identity |configure get cli_history$)' \
    "${case_dir}/out/aws.log" | grep -cvF -- '--cli-input-json file://')"
  if [[ "${bad}" -ne 0 ]]; then
    testlib::fail "request-shape: --cli-input-json file:// 아닌 호출 ${bad}건"
    return
  fi
  printf '%s\n' '{"PolicySourceArn":"x","ActionNames":["s3:ListBucket"],' \
    >"${case_dir}/in/mixed.json"
  printf '%s%s%s\n' '"ResourceArns":["y"],"PolicyExclusionList":[' \
    '{"PolicyType":"scp"},{"PolicyType":"resource"}],"ContextEntries":[' \
    "${SECURE_ENTRY}]}" >>"${case_dir}/in/mixed.json"
  if [[ "$(request_problems "${case_dir}/in/mixed.json" row)" != \
    *'scp 하나가 아님'* ]] ||
    [[ "$(request_problems "$(sim_requests "${dir}" | sed -n 2p)" pc0)" != \
    *'PC0 리전'* ]]; then
    testlib::fail "request-shape: 판정기가 섞인 제외 목록 · 서울 PC0 를 못 잡음"
    return
  fi
  expect_no_leak request-shape "${case_dir}" || return
  testlib::ok "4 request-shape → 제외 scp 하나 · 고정 문맥 · PC0 리전" \
    "ap-northeast-1 · 요청 하나에 동작 · 자원 하나 · argv 에 값 0"
}

#######################################
# 사례 5 · 6: 한 행 allowed → 종료 1(판정 불가와 섞여도), 최상위 거부 +
# 자원별 allowed → 종료 1.
#######################################
expect_allowed_stops() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "allowed: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_USER}-deploy@s3:ListBucket@${STATE_BUCKET}@*" \
      allowed None - - -)"
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 계정 자리다.
  testlib::tsv prod-sns 5 'arn:arn:aws:sns:ap-northeast-2:${ACCOUNT}:topic' \
    topic ap-northeast-2 sns:Publish unread - \
    >>"${case_dir}/tree/data/principal_sim/prod_targets.tsv"
  run_check "${case_dir}" allowed O7a
  expect_result allowed 1 'PRINCIPAL CHECK STOP 1' || return
  expect_rows allowed "| dev-deployer | state-bucket | s3:ListBucket |\
 ap-northeast-2 | 멈춤 | ALLOWED |" 1 || return
  expect_no_leak allowed "${case_dir}" || return
  case_dir="$(new_tree_case)" || {
    testlib::fail "resource-allowed: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-oidc@ecr:PutImage@*" implicitDeny None - \
      allowed -)"
  run_check "${case_dir}" resource-allowed O7a
  expect_result resource-allowed 1 'PRINCIPAL CHECK STOP 1' || return
  expect_rows resource-allowed '| dev-oidc | prod-ecr | ecr:PutImage |' 1 ||
    return
  expect_no_leak resource-allowed "${case_dir}" || return
  testlib::ok "5 · 6 allowed → 종료 1(판정 불가와 섞여도) · 자원별 allowed 도 멈춤"
}

#######################################
# 사례 7: 거부 + 누락 문맥(최상위에만 · 자원별에만) → 종료 3
# MISSING_CONTEXT, 허용 + 누락 문맥 → 종료 1.
#######################################
expect_missing_context() {
  local case_dir spec name line status first
  for spec in top resource allowed; do
    name="missing-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    case "${spec}" in
      top)
        line="$(testlib::tsv "${MARK_ROLE}-box@ec2:CreateRoute@*" \
          implicitDeny None aws:SourceIp - -)"
        ;;
      resource)
        line="$(testlib::tsv "${MARK_ROLE}-box@ec2:CreateRoute@*" \
          implicitDeny None - - ec2:Vpc)"
        ;;
      *)
        line="$(testlib::tsv "${MARK_ROLE}-box@ec2:CreateRoute@*" allowed \
          None aws:SourceIp - -)"
        ;;
    esac
    prepend "${case_dir}/in/sim.tsv" "${line}"
    run_check "${case_dir}" "${name}" O7a
    status=3
    first='PRINCIPAL CHECK UNDECIDED 1'
    if [[ "${spec}" == allowed ]]; then
      status=1
      first='PRINCIPAL CHECK STOP 1'
    fi
    expect_result "${name}" "${status}" "${first}" || return
    if [[ "${spec}" != allowed ]]; then
      expect_rows "${name}" "| dev-box,media-dev | prod-rtb |\
 ec2:CreateRoute | ap-northeast-2 | 판정 불가 | MISSING_CONTEXT |" 1 ||
        return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "7 missing-context → 최상위 · 자원별 누락은 종료 3, 허용 + 누락은 종료 1"
}

#######################################
# 사례 8(SCP 제외 (가)): 옛 CLI 가 제외를 거부 → 종료 2(CLI 판 안내), 응답에
# 조직 판정 False · 불리언 아님 → 종료 2. 제외 목록 단언은 사례 4.
#######################################
expect_scp_exclusion() {
  local case_dir name
  case_dir="$(new_tree_case)" || {
    testlib::fail "scp: 시험 준비 실패"
    return
  }
  run_check "${case_dir}" cli-old FAKE_AWS_CLI=old O7a
  expect_unverifiable cli-old || return
  testlib::expect_stderr_count cli-old \
    'CLI 가 PolicyExclusionList 를 받지 않음(2.36.12 이상 필요)' 1 || return
  for name in False Maybe; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "scp: 시험 준비 실패"
      return
    }
    prepend "${case_dir}/in/sim.tsv" \
      "$(testlib::tsv "${MARK_ROLE}-oidc@ecr:PutImage@*" implicitDeny \
        "${name}" - - -)"
    run_check "${case_dir}" "orgs-${name}" O7a
    expect_unverifiable "orgs-${name}" || return
    testlib::expect_stderr_count "orgs-${name}" '조직 판정' 1 || return
    expect_no_leak "orgs-${name}" "${case_dir}" || return
  done
  testlib::ok "8 scp → 옛 CLI · 조직 판정 False · 불리언 아님 모두 종료 2"
}

#######################################
# 사례 9: PC2 실패 · 불명 → 그 주체 행만 POSITIVE_CONTROL_FAILED.
#######################################
expect_positive_control() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "pc2: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_USER}-deploy@ssm:SendCommand@*" implicitDeny \
      None - - -)"
  run_check "${case_dir}" pc2-deny O7a
  expect_result pc2-deny 3 'PRINCIPAL CHECK UNDECIDED 11' || return
  expect_rows pc2-deny '| dev-deployer |' 11 || return
  expect_rows pc2-deny 'POSITIVE_CONTROL_FAILED' 11 || return
  expect_no_leak pc2-deny "${case_dir}" || return
  case_dir="$(new_tree_case)" || {
    testlib::fail "pc2: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-oidc@ssm:SendCommand@*" implicitDeny None \
      ssm:SessionDocumentAccessCheck - -)"
  run_check "${case_dir}" pc2-missing O7a
  expect_result pc2-missing 3 'PRINCIPAL CHECK UNDECIDED 10' || return
  expect_rows pc2-missing '| dev-oidc |' 10 || return
  expect_rows pc2-missing 'POSITIVE_CONTROL_FAILED' 10 || return
  expect_no_leak pc2-missing "${case_dir}" || return
  testlib::ok "9 pc2 → 거부 · 불명인 주체의 행만 POSITIVE_CONTROL_FAILED"
}

#######################################
# 사례 10: 주체 해석 — NoSuchEntity · 인스턴스 프로필의 역할 0개 →
# PRINCIPAL_MISSING, AccessDenied → 종료 2, 종류별로 맞는 호출.
#######################################
expect_principal_resolution() {
  local case_dir dir log
  case_dir="$(new_tree_case)" || {
    testlib::fail "principal: 시험 준비 실패"
    return
  }
  run_check "${case_dir}" principal-kinds O7a
  dir="$(evidence_dir "${case_dir}")"
  local profile="\"InstanceProfileName\":\"${MARK_PROFILE}-dev\""
  local role="\"RoleName\":\"${MARK_ROLE}-oidc\""
  local user="\"UserName\":\"${MARK_USER}-deploy\""
  local role_as_user="\"UserName\":\"${MARK_ROLE}-oidc\""
  local user_as_role="\"RoleName\":\"${MARK_USER}-deploy\""
  if [[ "$(count_requests "${dir}" "${profile}")" -lt 1 ||
    "$(count_requests "${dir}" "${role}")" -lt 1 ||
    "$(count_requests "${dir}" "${user}")" -lt 1 ||
    "$(count_requests "${dir}" "${role_as_user}")" -ne 0 ||
    "$(count_requests "${dir}" "${user_as_role}")" -ne 0 ]]; then
    testlib::fail "principal-kinds: 종류별 호출이 맞지 않음"
    return
  fi
  log="${case_dir}/out/aws.log"
  if [[ "$(grep -c '^iam get-instance-profile ' "${log}")" -lt 1 ||
    "$(grep -c '^iam get-role ' "${log}")" -lt 1 ||
    "$(grep -c '^iam get-user ' "${log}")" -lt 1 ]]; then
    testlib::fail "principal-kinds: get-instance-profile · get-role ·" \
      "get-user 가 다 나가지 않음"
    return
  fi
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-user "${MARK_USER}-deploy" '!NoSuchEntity')"
  run_check "${case_dir}" principal-missing O7a
  expect_result principal-missing 3 'PRINCIPAL CHECK UNDECIDED 1' || return
  expect_rows principal-missing \
    '| dev-deployer | - | - | - | 판정 불가 | PRINCIPAL_MISSING |' 1 || return
  expect_no_leak principal-missing "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-instance-profile "${MARK_PROFILE}-dev" '')"
  run_check "${case_dir}" profile-no-role O7a
  expect_result profile-no-role 3 'PRINCIPAL CHECK UNDECIDED 2' || return
  expect_rows profile-no-role 'PRINCIPAL_MISSING' 2 || return
  expect_no_leak profile-no-role "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-role "${MARK_ROLE}-oidc" '!AccessDenied')"
  run_check "${case_dir}" principal-denied O7a
  expect_unverifiable principal-denied || return
  expect_no_leak principal-denied "${case_dir}" || return
  testlib::ok "10 principal → 종류별 호출 · NoSuchEntity · 역할 0개는" \
    "PRINCIPAL_MISSING · AccessDenied 는 종료 2"
}

#######################################
# 사례 11: 해석 0건(tag: · zone: · prodrole) → TARGET_NONE, self:group ·
# policy:attached 0건 → 판정 불가 아님, 첫 단계가 뒤인 행 → 호출 0건, 첫
# 단계 O7a 행은 단계 8 · ci-followup 에서 호출되고 5 에서는 안 됨.
#######################################
expect_target_resolution() {
  local case_dir dir stage hits
  case_dir="$(new_tree_case)" || {
    testlib::fail "target: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-resources secretsmanager:secret@ap-northeast-2 '')" \
    "$(testlib::tsv list-hosted-zones-by-name "media.${MARK_ZONE}.test." '')" \
    "$(testlib::tsv list-roles /pokeclip/prod/ '')"
  run_check "${case_dir}" target-none O7a
  expect_result target-none 3 'PRINCIPAL CHECK UNDECIDED 4' || return
  expect_rows target-none 'TARGET_NONE' 4 || return
  expect_rows target-none '| - | prod-secret | - | ap-northeast-2 |' 1 ||
    return
  expect_no_leak target-none "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv list-groups-for-user "${MARK_USER}-deploy" '')" \
    "$(testlib::tsv list-attached-role-policies "${MARK_ROLE}-box" '')"
  run_check "${case_dir}" zero-ok O7a
  expect_result zero-ok 0 "PRINCIPAL CHECK PASS $((NORMAL_ROWS - 2))" ||
    return
  dir="$(evidence_dir "${case_dir}")"
  if [[ "$(count_requests "${dir}" "${MARK_BUCKET}-late")" -ne 0 ]]; then
    testlib::fail "target: 첫 단계 9 행이 O7a 실행에서 호출됨"
    return
  fi
  for stage in 5 8 ci-followup; do
    case_dir="$(new_tree_case)" || return
    run_check "${case_dir}" "stage-${stage}" "${stage}"
    dir="$(evidence_dir "${case_dir}")"
    if [[ "${stage}" == 5 ]]; then
      [[ "$(count_requests "${dir}" "${RTB_ARN}")" -eq 0 ]] && continue
      testlib::fail "target: 첫 단계 O7a 행이 단계 5 에서 호출됨"
      return
    fi
    hits="$(count_requests "${dir}" "${RTB_ARN}" '"PolicySourceArn"')"
    if [[ "${hits}" -lt 1 ]]; then
      testlib::fail "target: 첫 단계 O7a 행이 단계 ${stage} 에서 호출되지 않음"
      return
    fi
  done
  expect_no_leak target-stage "${case_dir}" || return
  testlib::ok "11 target → tag: · zone: · prodrole 0건은 TARGET_NONE ·" \
    "self:group · policy:attached 0건은 정상 · 단계 색인 비교"
}

#######################################
# 사례 12: 정책 「없음」(버킷 오류 코드 · 비밀 None · 저장소 오류 코드) →
# 정책 없이 호출, 그 밖 오류 → 종료 2.
#######################################
expect_policy_absent() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "policy-none: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-bucket-policy "${MARK_BUCKET}-state" \
      '!NoSuchBucketPolicy')" \
    "$(testlib::tsv get-resource-policy "${SECRET_ARN}" None)"
  run_check "${case_dir}" policy-none O7a
  expect_result policy-none 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
  if [[ "$(grep -c -- "--resource-policy file://.*/policy-" \
    "${case_dir}/out/aws.log")" -ne 0 ]]; then
    testlib::fail "policy-none: 정책 없음인데 --resource-policy 가 붙음"
    return
  fi
  expect_no_leak policy-none "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-bucket-policy "${MARK_BUCKET}-state" '!AccessDenied')"
  run_check "${case_dir}" policy-error O7a
  expect_unverifiable policy-error || return
  expect_no_leak policy-error "${case_dir}" || return
  testlib::ok "12 policy → 「없음」 셋은 정책 없이 호출 · 그 밖 오류는 종료 2 · 유출 0"
}

#######################################
# 정책 원문을 버킷 정책 답으로 넣는다.
# Arguments:
#   사례 디렉터리, 정책 원문.
#######################################
set_bucket_policy() {
  prepend "$1/in/reads.tsv" \
    "$(testlib::tsv get-bucket-policy "${MARK_BUCKET}-state" "$2")"
}

#######################################
# Allow 를 가진 버킷 정책(사용자 주체를 지명)을 낸다.
# Outputs:
#   정책 원문.
#######################################
allow_policy() {
  printf '%s%s%s\n' '{"Version":"2012-10-17","Statement":[{"Effect":"Allow",' \
    "\"Principal\":{\"AWS\":\"${USER_DEPLOY}\"},\"Action\":\"s3:GetObject\"," \
    "\"Resource\":\"${STATE_BUCKET}/*\"}]}"
}

#######################################
# 사례 13: 정책 꼴 — 정책 파일은 { 로 시작하고 "Statement" 를 담음, 봉투째
# → 종료 2, Deny 만 → 역할 주체도 정상, Allow + 갈래 B → 역할 주체 행
# RESOURCE_POLICY_UNSIMULATED · 사용자 주체 요청에 --resource-policy ·
# CallerArn.
#######################################
expect_policy_shape() {
  local case_dir dir file found=0
  case_dir="$(new_tree_case)" || {
    testlib::fail "policy-shape: 시험 준비 실패"
    return
  }
  run_check "${case_dir}" policy-deny-only O7a
  expect_result policy-deny-only 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" ||
    return
  dir="$(evidence_dir "${case_dir}")"
  for file in "${dir}"/policy-*.json; do
    [[ -f "${file}" ]] || continue
    found=1
    if [[ "$(head -c 1 -- "${file}")" != '{' ]] ||
      ! grep -qF '"Statement"' -- "${file}"; then
      testlib::fail "policy-shape: 정책 파일 꼴이 아님(${file##*/})"
      return
    fi
  done
  if [[ "${found}" -eq 0 ]] ||
    [[ "$(grep -c -- "--resource-policy file://.*/policy-" \
      "${case_dir}/out/aws.log")" -ne 0 ]]; then
    testlib::fail "policy-shape: 정책 파일이 없거나 Deny 만인데 --resource-policy"
    return
  fi
  case_dir="$(new_tree_case)" || return
  run_check "${case_dir}" envelope FAKE_AWS_POLICY_MODE=envelope O7a
  expect_unverifiable envelope || return
  expect_no_leak envelope "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  set_bucket_policy "${case_dir}" "$(allow_policy)"
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-oidc@s3:ListBucket@${PCA_SAMPLE}@*@policy" \
      implicitDeny None - - -)"
  run_check "${case_dir}" allow-branch-b O7a
  expect_result allow-branch-b 3 'PRINCIPAL CHECK UNDECIDED 6' || return
  expect_rows allow-branch-b 'RESOURCE_POLICY_UNSIMULATED' 6 || return
  expect_rows allow-branch-b '| dev-deployer |' 0 || return
  dir="$(evidence_dir "${case_dir}")"
  if [[ "$(grep -c -- "--resource-policy file://.*/policy-" \
    "${case_dir}/out/aws.log")" -ne 3 ]] ||
    [[ "$(count_requests "${dir}" "\"CallerArn\":\"${USER_DEPLOY}\"" \
      "\"PolicySourceArn\":\"${USER_DEPLOY}\"")" -ne 3 ]] ||
    ! grep -qF '갈래 A 대조 실패 — B 로 판정' -- "${dir}/report.txt"; then
    testlib::fail "policy-shape: 갈래 B 에서 사용자 주체 요청에 정책 ·" \
      "CallerArn 이 없거나 report.txt 에 갈래 기록 없음"
    return
  fi
  expect_no_leak allow-branch-b "${case_dir}" || return
  testlib::ok "13 policy-shape → 정책 칸만 · 봉투째는 종료 2 · Deny 만은 정책" \
    "없이 · Allow + 갈래 B 는 역할 행 UNSIMULATED · 사용자 요청에 정책 · CallerArn"
}

#######################################
# 사례 14 · 15: 정책에 assumed-role/ → 그 자원의 모든 주체 행
# SESSION_PRINCIPAL_IN_POLICY, unread 행 → RESOURCE_POLICY_UNREAD.
#######################################
expect_session_and_unread() {
  local case_dir policy
  case_dir="$(new_tree_case)" || {
    testlib::fail "session: 시험 준비 실패"
    return
  }
  policy='{"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":'
  policy+="{\"AWS\":\"arn:aws:sts::${FAKE_ACCOUNT}:assumed-role/"
  policy+="${MARK_ROLE}-x/s\"},"
  policy+="\"Action\":\"s3:*\",\"Resource\":\"${STATE_BUCKET}\"}]}"
  set_bucket_policy "${case_dir}" "${policy}"
  run_check "${case_dir}" session O7a
  expect_result session 3 'PRINCIPAL CHECK UNDECIDED 9' || return
  expect_rows session 'SESSION_PRINCIPAL_IN_POLICY' 9 || return
  expect_no_leak session "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 계정 자리다.
  testlib::tsv prod-sns 5 'arn:arn:aws:sns:ap-northeast-2:${ACCOUNT}:topic' \
    topic ap-northeast-2 sns:Publish unread - \
    >>"${case_dir}/tree/data/principal_sim/prod_targets.tsv"
  run_check "${case_dir}" unread O7a
  expect_result unread 3 'PRINCIPAL CHECK UNDECIDED 3' || return
  expect_rows unread "| prod-sns | sns:Publish | ap-northeast-2 | 판정 불가 |\
 RESOURCE_POLICY_UNREAD |" 3 || return
  expect_no_leak unread "${case_dir}" || return
  testlib::ok "14 · 15 session · unread → SESSION_PRINCIPAL_IN_POLICY 9 ·" \
    "RESOURCE_POLICY_UNREAD 3"
}

#######################################
# 사례 16: 권한 변경 — 역할 자기 인라인 정책 · 자기 그룹 인라인 정책 · 붙은
# 고객 관리형 정책 새 버전 허용 → 종료 1, 주체 종류와 맞지 않는 호출 0건,
# AWS 관리형 정책은 대상 아님.
#######################################
expect_permission_change() {
  local case_dir dir line name
  for name in self-role self-group attached; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "permission: 시험 준비 실패"
      return
    }
    case "${name}" in
      self-role) line="${MARK_ROLE}-oidc@iam:PutRolePolicy@${ROLE_OIDC}@*" ;;
      self-group)
        line="${MARK_USER}-deploy@iam:PutGroupPolicy@${GROUP_DEPLOY}@*"
        ;;
      *) line="${MARK_ROLE}-box@iam:CreatePolicyVersion@${POLICY_BOX}@*" ;;
    esac
    prepend "${case_dir}/in/sim.tsv" \
      "$(testlib::tsv "${line}" allowed None - - -)"
    run_check "${case_dir}" "permission-${name}" O7a
    expect_result "permission-${name}" 1 'PRINCIPAL CHECK STOP 1' || return
    expect_rows "permission-${name}" "| ${name} |" 1 || return
    expect_no_leak "permission-${name}" "${case_dir}" || return
  done
  dir="$(evidence_dir "${case_dir}")"
  if [[ "$(count_requests "${dir}" '"ActionNames":["iam:PutRolePolicy"]' \
      "\"PolicySourceArn\":\"${USER_DEPLOY}\"" \
      "\"ResourceArns\":[\"${USER_DEPLOY}\"]")" -ne 0 ||
    "$(count_requests "${dir}" '"ActionNames":["iam:PutUserPolicy"]' \
      "\"PolicySourceArn\":\"${ROLE_OIDC}\"")" -ne 0 ||
    "$(count_requests "${dir}" '"ActionNames":["iam:PutGroupPolicy"]' \
      "\"PolicySourceArn\":\"${ROLE_BOX}\"")" -ne 0 ||
    "$(count_requests "${dir}" \
      '"ResourceArns":["arn:aws:iam::aws:policy/')" -ne 0 ||
    "$(count_requests "${dir}" '"ActionNames":["iam:PutRolePolicy"]' \
      "\"ResourceArns\":[\"${ROLE_PROD}\"]")" -ne 3 ]]; then
    testlib::fail "permission: 주체 종류와 맞지 않는 호출 · AWS 관리형 정책 호출이" \
      "있거나 prod 역할 질문이 주체마다 나가지 않음"
    return
  fi
  testlib::ok "16 permission → 자기 역할 · 자기 그룹 · 붙은 고객 관리형 정책 허용은" \
    "종료 1 · 종류 밖 호출 0 · AWS 관리형 제외"
}

#######################################
# 사례 17 · 18: network 경로 추가 허용 → 종료 1. Route 53 레코드 이름
# 문맥은 stringList 이고 두 갈래 모두 호출, 존 해석은 다음 존이 맨 앞 →
# TARGET_NONE · 비공개 존이 섞임 → 공개 존만 · 같은 이름 공개 존 둘 → 종료 2.
#######################################
expect_network_and_zone() {
  local case_dir dir key prod mix zones
  case_dir="$(new_tree_case)" || {
    testlib::fail "network: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-box@ec2:CreateRoute@${RTB_ARN}@*" allowed \
      None - - -)"
  run_check "${case_dir}" network O7a
  expect_result network 1 'PRINCIPAL CHECK STOP 1' || return
  expect_rows network '| prod-rtb | ec2:CreateRoute |' 1 || return
  dir="$(evidence_dir "${case_dir}")"
  key="{\"ContextKeyName\":\"${RECORD_KEY}\",\"ContextKeyValues\":"
  prod="${key}[\"${ZONE_NAME}\"],\"ContextKeyType\":\"stringList\"}"
  mix="${key}[\"${DEV_RECORD}\",\"${ZONE_NAME}\"],"
  mix+='"ContextKeyType":"stringList"}'
  if [[ "$(count_requests "${dir}" "${prod}" hostedzone/ZQXZONE1)" -ne 3 ||
    "$(count_requests "${dir}" "${mix}" hostedzone/ZQXZONE1)" -ne 3 ]]; then
    testlib::fail "zone: 레코드 이름 문맥 두 갈래가 stringList 로 주체마다 나가지 않음"
    return
  fi
  expect_no_leak network "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv list-hosted-zones-by-name "${ZONE_NAME}" \
      "other.${MARK_ZONE}.test.\\t/hostedzone/ZOTHER\\tFalse")"
  run_check "${case_dir}" zone-next O7a
  expect_result zone-next 3 'PRINCIPAL CHECK UNDECIDED 2' || return
  expect_rows zone-next 'TARGET_NONE' 2 || return
  dir="$(evidence_dir "${case_dir}")"
  if [[ "$(count_requests "${dir}" ZOTHER)" -ne 0 ]]; then
    testlib::fail "zone-next: 엉뚱한 존 ARN 으로 호출함"
    return
  fi
  zones="${ZONE_NAME}\\t/hostedzone/ZQXZONE1\\tFalse"
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv list-hosted-zones-by-name "${ZONE_NAME}" \
      "${ZONE_NAME}\\t/hostedzone/ZPRIV\\tTrue\\n${zones}")"
  run_check "${case_dir}" zone-private O7a
  expect_result zone-private 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
  if [[ "$(count_requests "$(evidence_dir "${case_dir}")" ZPRIV)" -ne 0 ]]; then
    testlib::fail "zone-private: 비공개 존으로 호출함"
    return
  fi
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv list-hosted-zones-by-name "${ZONE_NAME}" \
      "${ZONE_NAME}\\t/hostedzone/ZONE2\\tFalse\\n${zones}")"
  run_check "${case_dir}" zone-two O7a
  expect_unverifiable zone-two || return
  expect_no_leak zone-two "${case_dir}" || return
  testlib::ok "17 · 18 network · zone → 경로 추가 허용은 종료 1 · stringList 두" \
    "갈래 · 다음 존 TARGET_NONE · 비공개 제외 · 공개 둘은 종료 2"
}

#######################################
# 사례 19 · 23: 호출 실패 · 호출 자체의 AccessDenied · 응답 꼴 · IAM 사용자
# 아닌 호출 주체 · 읽기 실패 → 종료 2, 중간 실패 → complete.txt 없음.
#######################################
expect_call_failures() {
  local case_dir spec name dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "call: 시험 준비 실패"
    return
  }
  for spec in sim-fail:FAKE_AWS_SIM=fail sim-denied:FAKE_AWS_SIM=denied-call \
    sim-short:FAKE_AWS_SIM=short identity-role:FAKE_AWS_IDENTITY=role \
    identity-fail:FAKE_AWS_IDENTITY=fail; do
    name="${spec%%:*}"
    run_check "${case_dir}" "${name}" "${spec#*:}" O7a
    expect_unverifiable "${name}" || return
    case "${name}" in
      sim-denied)
        testlib::expect_stderr_count "${name}" AccessDenied 1 || return
        ;;
      identity-role)
        testlib::expect_stderr_count "${name}" '호출 주체가 IAM 사용자가 아님' 1 ||
          return
        ;;
      *) ;;
    esac
  done
  expect_no_leak call "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-resources ecr:repository@ap-northeast-2 '!fail')"
  run_check "${case_dir}" read-fail O7a
  expect_unverifiable read-fail || return
  dir="$(evidence_dir "${case_dir}")"
  if [[ -z "${dir}" || -e "${dir}/complete.txt" ]]; then
    testlib::fail "read-fail: 중간 실패인데 complete.txt 가 있거나 증거가 없음"
    return
  fi
  expect_no_leak read-fail "${case_dir}" || return
  testlib::ok "19 · 23 call → 호출 실패 · AccessDenied · 응답 꼴 · 역할 세션 ·" \
    "읽기 실패는 종료 2 · 중간 실패에 complete.txt 없음"
}

#######################################
# 사례 20: 증거 경로 거부(링크 · 0700 아님 · 저장소 표지) → 종료 2.
#######################################
expect_evidence_rejected() {
  local case_dir name
  for name in link mode repo; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "evidence: 시험 준비 실패"
      return
    }
    case "${name}" in
      link)
        mkdir -m 700 "${case_dir}/in/elsewhere" &&
          ln -s "${case_dir}/in/elsewhere" \
            "${case_dir}/home/.pokeclip-precheck"
        ;;
      mode) mkdir -m 755 "${case_dir}/home/.pokeclip-precheck" ;;
      *) mkdir "${case_dir}/home/.git" ;;
    esac || {
      testlib::fail "evidence: 시험 준비 실패"
      return
    }
    run_check "${case_dir}" "evidence-${name}" O7a
    expect_unverifiable "evidence-${name}" || return
    testlib::expect_stderr_count "evidence-${name}" "${REJECTED}" 1 || return
    expect_no_leak "evidence-${name}" "${case_dir}" || return
  done
  testlib::ok "20 evidence → 링크 · 0700 아님 · 저장소 표지는 종료 2"
}

#######################################
# 데이터 파일 하나의 줄을 sed 식으로 바꾼다.
# Arguments:
#   사례 디렉터리, 파일 이름, sed 식.
#######################################
edit_data() {
  local file="$1/tree/data/principal_sim/$2"
  sed -e "$3" -- "${file}" >"${file}.new" && mv -- "${file}.new" "${file}"
}

#######################################
# 사례 21: 데이터 꼴 — 칸 수 · 구체 ARN 에 * · 모르는 첫 단계 · 모르는 해석
# 방법 · 모르는 주체 종류(notify 포함) · 판정 없는 단계 이름 · PC2 행 빠짐 →
# 종료 2.
#######################################
expect_data_shape() {
  local case_dir spec name stage
  for spec in columns star stage method kind notify pc2 unjudged; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "data: 시험 준비 실패"
      return
    }
    name="data-${spec}"
    stage=O7a
    case "${spec}" in
      columns) edit_data "${case_dir}" prod_targets.tsv $'s/\tbucket\t/\t/' ;;
      star)
        edit_data "${case_dir}" prod_targets.tsv \
          "s|-state/prod/network/terraform.tfstate|-state/*|"
        ;;
      stage)
        edit_data "${case_dir}" prod_targets.tsv \
          $'s/^prod-ecr\t5/prod-ecr\tO7z/'
        ;;
      method)
        edit_data "${case_dir}" prod_targets.tsv \
          $'s/\tself:user\t/\tself:any\t/'
        ;;
      kind)
        edit_data "${case_dir}" dev_principals.tsv $'s/\trole\t/\tgroup\t/'
        ;;
      notify)
        testlib::tsv notify notify "${MARK_USER}-notify" \
          >>"${case_dir}/tree/data/principal_sim/dev_principals.tsv"
        ;;
      pc2) edit_data "${case_dir}" positive_controls.tsv '/^dev-oidc/d' ;;
      *) stage=1b ;;
    esac || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    run_check "${case_dir}" "${name}" "${stage}"
    expect_unverifiable "${name}" || return
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "21 data → 칸 수 · * · 모르는 단계 · 해석 방법 · 주체 종류 ·" \
    "notify · PC2 행 빠짐 · 판정 없는 단계는 종료 2"
}

#######################################
# 사례 24: 셈 — 주체 · 대상 단위 사유는 하나당 1건으로 UNDECIDED 에 들어가고
# report.txt 에 사유별 건수.
#######################################
expect_counting() {
  local case_dir dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "count: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/reads.tsv" \
    "$(testlib::tsv get-user "${MARK_USER}-deploy" '!NoSuchEntity')" \
    "$(testlib::tsv get-resources ecr:repository@ap-northeast-2 '')"
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 계정 자리다.
  testlib::tsv prod-sns 5 'arn:arn:aws:sns:ap-northeast-2:${ACCOUNT}:topic' \
    topic ap-northeast-2 sns:Publish unread - \
    >>"${case_dir}/tree/data/principal_sim/prod_targets.tsv"
  run_check "${case_dir}" count O7a
  expect_result count 3 'PRINCIPAL CHECK UNDECIDED 4' || return
  dir="$(evidence_dir "${case_dir}")"
  if ! grep -qx '사유 PRINCIPAL_MISSING 1' -- "${dir}/report.txt" ||
    ! grep -qx '사유 TARGET_NONE 1' -- "${dir}/report.txt" ||
    ! grep -qx '사유 RESOURCE_POLICY_UNREAD 2' -- "${dir}/report.txt"; then
    testlib::fail "count: report.txt 에 사유별 건수가 없음 —" \
      "$(cat -- "${dir}/report.txt")"
    return
  fi
  expect_no_leak count "${case_dir}" || return
  testlib::ok "24 count → 주체 · 대상 단위 사유 1건씩 · report.txt 사유별 건수"
}

#######################################
# 사례 25: PC-A — ⓐ · ⓑ 가 각 1회, ⓐ 허용이면 갈래 B, PC-A 통과(갈래 A)면
# 역할 주체 요청에도 --resource-policy · CallerArn, 실패면 B 로 판정 ·
# report.txt 기록.
#######################################
expect_branch_check() {
  local case_dir dir sample
  case_dir="$(new_tree_case)" || {
    testlib::fail "branch: 시험 준비 실패"
    return
  }
  set_bucket_policy "${case_dir}" "$(allow_policy)"
  run_check "${case_dir}" branch-a O7a
  expect_result branch-a 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
  dir="$(evidence_dir "${case_dir}")"
  sample="\"ResourceArns\":[\"${PCA_SAMPLE}\"]"
  if [[ "$(count_requests "${dir}" "${sample}")" -ne 2 ||
    "$(count_requests "${dir}" "${sample}" \
      "\"CallerArn\":\"${ROLE_OIDC}\"")" -ne 1 ||
    "$(count_requests "${dir}" "\"CallerArn\":\"${ROLE_BOX}\"" \
      "\"PolicySourceArn\":\"${ROLE_BOX}\"")" -ne 3 ||
    "$(grep -c -- "--resource-policy file://.*/policy-" \
      "${case_dir}/out/aws.log")" -ne 9 ]] ||
    ! grep -qx '갈래 A' -- "${dir}/report.txt"; then
    testlib::fail "branch-a: PC-A 두 질문 · 역할 주체의 정책 · CallerArn ·" \
      "report.txt 갈래 기록이 맞지 않음"
    return
  fi
  expect_no_leak branch-a "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  set_bucket_policy "${case_dir}" "$(allow_policy)"
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-oidc@s3:ListBucket@${PCA_SAMPLE}@*@-" \
      allowed None - - -)"
  run_check "${case_dir}" branch-a-open O7a
  expect_result branch-a-open 3 'PRINCIPAL CHECK UNDECIDED 6' || return
  expect_rows branch-a-open 'RESOURCE_POLICY_UNSIMULATED' 6 || return
  if ! grep -qF '갈래 A 대조 실패 — B 로 판정' -- \
    "$(evidence_dir "${case_dir}")/report.txt"; then
    testlib::fail "branch-a-open: report.txt 에 갈래 B 기록 없음"
    return
  fi
  expect_no_leak branch-a-open "${case_dir}" || return
  testlib::ok "25 branch → PC-A ⓐ · ⓑ 각 1회 · 통과면 역할 주체에도 정책 ·" \
    "ⓐ 허용 · ⓑ 거부면 갈래 B 로 판정 · report.txt 기록"
}

#######################################
# 사례 26: CLI 기록 — 프로필의 cli_history 가 enabled 이거나
# AWS_CLI_HISTORY_FILE 이 설정돼 있으면 AWS 호출 0건으로 종료 2(값 · 경로를
# 되울리지 않음), 설정 읽기 실패도 종료 2, disabled 면 정상.
# Globals:
#   run_out
#######################################
expect_cli_history() {
  local case_dir spec name calls
  for spec in enabled file fail disabled; do
    name="cli-history-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    case "${spec}" in
      file)
        run_check "${case_dir}" "${name}" \
          "AWS_CLI_HISTORY_FILE=${case_dir}/in/qxhistory.db" O7a
        ;;
      *) run_check "${case_dir}" "${name}" "FAKE_AWS_CLI_HISTORY=${spec}" O7a ;;
    esac
    if [[ "${spec}" == disabled ]]; then
      expect_result "${name}" 0 "PRINCIPAL CHECK PASS ${NORMAL_ROWS}" || return
      continue
    fi
    expect_unverifiable "${name}" || return
    calls="$(testlib::count "${case_dir}/out/aws.log" ' ')"
    if [[ "${calls}" -gt 1 ]] ||
      grep -qF -e qxhistory -e enabled -- "${run_out}.stderr"; then
      testlib::fail "${name}: AWS 호출 ${calls}건이거나 값 · 경로를 되울림 —" \
        "$(cat -- "${run_out}.stderr")"
      return
    fi
    if [[ "${spec}" != fail ]]; then
      testlib::expect_stderr_count "${name}" \
        'CLI 기록이 켜져 있음 — 증거 밖에 원문이 남음' 1 || return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "26 cli-history → enabled · 기록 파일 환경 변수 · 설정 읽기 실패는" \
    "AWS 호출 0건으로 종료 2 · disabled 는 정상"
}

#######################################
# 사본 트리의 lib/ 파일 하나를 sed 식으로 바꾼다(계약 어긋남을 만든다).
# Arguments:
#   사례 디렉터리, lib/ 안의 파일 이름, sed 식.
#######################################
edit_lib() {
  local file="$1/tree/lib/$2"
  sed -e "$3" -- "${file}" >"${file}.new" && mv -- "${file}.new" "${file}"
}

#######################################
# 사례 27: 시뮬레이터 응답 계약 — 최상위 판정이 목록 꼴이면 종료 2, 래퍼의
# 응답 질의(SIM_QUERY)가 가짜 aws 의 계약과 다르면(칸 경로 오타) 종료 2.
#######################################
expect_simulator_contract() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "top-decision: 시험 준비 실패"
    return
  }
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-oidc@ecr:PutImage@*" implicitDeny,allowed \
      None - implicitDeny -)"
  run_check "${case_dir}" top-decision-list O7a
  expect_unverifiable top-decision-list || return
  testlib::expect_stderr_count top-decision-list '시뮬레이터 판정을 모름' 1 ||
    return
  expect_no_leak top-decision-list "${case_dir}" || return
  case_dir="$(new_tree_case)" || return
  if ! edit_lib "${case_dir}" principal_simulate.sh \
    's/OrganizationsDecisionDetail\.AllowedByOrganizations/AllowedByOrgs/'; then
    testlib::fail "sim-query: 시험 준비 실패"
    return
  fi
  run_check "${case_dir}" sim-query O7a
  expect_unverifiable sim-query || return
  testlib::expect_stderr_count sim-query '시뮬레이터 호출 실패' 1 || return
  expect_no_leak sim-query "${case_dir}" || return
  testlib::ok "27 simulator-contract → 최상위 판정 목록 꼴 · 응답 질의가 계약과" \
    "다름은 종료 2"
}

#######################################
# 사례 28: 해석 응답 — get-role · get-user 가 성공인데 ARN 이 없으면 종료 2
# (PRINCIPAL_MISSING 아님), 존 목록이 잘렸으면(IsTruncated · NextDNSName)
# 종료 2.
#######################################
expect_resolution_responses() {
  local case_dir spec name
  for spec in get-role get-user True:None False:zz-next.test.; do
    name="empty-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    case "${spec}" in
      get-role)
        prepend "${case_dir}/in/reads.tsv" \
          "$(testlib::tsv get-role "${MARK_ROLE}-oidc" '')"
        run_check "${case_dir}" "${name}" O7a
        ;;
      get-user)
        prepend "${case_dir}/in/reads.tsv" \
          "$(testlib::tsv get-user "${MARK_USER}-deploy" '')"
        run_check "${case_dir}" "${name}" O7a
        ;;
      *)
        name="zone-truncated-${spec%%:*}"
        run_check "${case_dir}" "${name}" \
          "FAKE_AWS_ZONE_PAGE=${spec%%:*}\\t${spec#*:}" O7a
        ;;
    esac
    expect_unverifiable "${name}" || return
    if [[ "${name}" == empty-* ]]; then
      testlib::expect_stderr_count "${name}" '주체 응답에 ARN 이 하나가 아님' \
        1 || return
    else
      testlib::expect_stderr_count "${name}" '존 목록이 잘림' 1 || return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "28 resolution → 역할 · 사용자 빈 성공 응답 · 잘린 존 목록은 종료 2"
}

#######################################
# ECR 저장소 정책 답을 넣는다.
# Arguments:
#   사례 디렉터리, 정책 원문.
#######################################
set_repo_policy() {
  prepend "$1/in/reads.tsv" \
    "$(testlib::tsv get-repository-policy "${REPO_ARN#*:repository/}" "$2")"
}

#######################################
# 사례 29: 정책 읽기 — S3 · ECR 성공 응답의 None(칸 없음) · 정책 칸 이름
# 오타(진짜 CLI 처럼 None)는 종료 2(None 은 비밀만 「정책 없음」), 버킷 정책
# 읽기에 --region, ECR Allow 정책 + 갈래 B 는 역할 행 UNSIMULATED · 사용자
# 요청에 정책 · CallerArn.
#######################################
expect_policy_reads() {
  local case_dir spec policy log
  for spec in s3-none ecr-none s3-query; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "${spec}: 시험 준비 실패"
      return
    }
    case "${spec}" in
      s3-none) set_bucket_policy "${case_dir}" None ;;
      ecr-none) set_repo_policy "${case_dir}" None ;;
      *)
        edit_lib "${case_dir}" principal_policy.sh \
          's/--query Policy /--query Polciy /'
        ;;
    esac || return
    run_check "${case_dir}" "${spec}" O7a
    expect_unverifiable "${spec}" || return
    testlib::expect_stderr_count "${spec}" '자원 정책 응답이 None' 1 || return
    expect_no_leak "${spec}" "${case_dir}" || return
  done
  case_dir="$(new_tree_case)" || return
  policy='{"Version":"2012-10-17","Statement":[{"Effect":"Allow",'
  policy+="\"Principal\":{\"AWS\":\"${USER_DEPLOY}\"},"
  policy+='"Action":"ecr:PutImage"}]}'
  set_repo_policy "${case_dir}" "${policy}"
  prepend "${case_dir}/in/sim.tsv" \
    "$(testlib::tsv "${MARK_ROLE}-oidc@s3:ListBucket@${PCA_SAMPLE}@*@policy" \
      implicitDeny None - - -)"
  run_check "${case_dir}" ecr-allow-branch-b O7a
  expect_result ecr-allow-branch-b 3 'PRINCIPAL CHECK UNDECIDED 2' || return
  expect_rows ecr-allow-branch-b \
    '| prod-ecr | ecr:PutImage | ap-northeast-2 | 판정 불가 |' 2 || return
  expect_rows ecr-allow-branch-b 'RESOURCE_POLICY_UNSIMULATED' 2 || return
  log="${case_dir}/out/aws.log"
  if [[ "$(count_requests "$(evidence_dir "${case_dir}")" "${REPO_ARN}" \
    "\"CallerArn\":\"${USER_DEPLOY}\"")" -ne 1 ]] ||
    [[ "$(grep -c -- "--resource-policy file://.*/policy-" "${log}")" -ne 1 ]] ||
    [[ "$(grep -c '^s3api get-bucket-policy ' "${log}")" -lt 1 ||
    "$(grep '^s3api get-bucket-policy ' "${log}" |
      grep -cvF -- '--region ap-northeast-2')" -ne 0 ]]; then
    testlib::fail "ecr-allow-branch-b: 사용자 요청에 정책 · CallerArn 이 없거나" \
      "버킷 정책 읽기에 --region 이 없음"
    return
  fi
  expect_no_leak ecr-allow-branch-b "${case_dir}" || return
  testlib::ok "29 policy-reads → S3 · ECR 의 None · 칸 이름 오타는 종료 2 ·" \
    "버킷 정책 --region · ECR Allow + 갈래 B 는 역할 UNSIMULATED · 사용자에 정책"
}

#######################################
# 사례 30: 이스케이프된 정책 — 「\/」(역할 세션 ARN 을 숨김) · 「\u」 만 든
# 정책은 그 자원의 모든 주체 행이 POLICY_ESCAPED. 「\u」 Allow 정책은 갈래
# B 에서도 사용자 요청에 정책 · CallerArn 을 붙인다(Allow 검사의 「\u」 갈래).
#######################################
expect_escaped_policy() {
  local case_dir spec policy
  for spec in slash unicode; do
    case_dir="$(new_tree_case)" || {
      testlib::fail "escaped-${spec}: 시험 준비 실패"
      return
    }
    if [[ "${spec}" == slash ]]; then
      policy='{"Version":"2012-10-17","Statement":[{"Effect":"Deny",'
      policy+="\"Principal\":{\"AWS\":\"arn:aws:sts::${FAKE_ACCOUNT}:"
      policy+="assumed-role\\/${MARK_ROLE}-x\\/s\"},\"Action\":\"s3:*\","
    else
      policy='{"Version":"2012-10-17","Statement":[{"Effect":"\u0041llow",'
      policy+="\"Principal\":{\"AWS\":\"${USER_DEPLOY}\"},"
      policy+='"Action":"s3:GetObject",'
      prepend "${case_dir}/in/sim.tsv" \
        "$(testlib::tsv \
          "${MARK_ROLE}-oidc@s3:ListBucket@${PCA_SAMPLE}@*@policy" \
          implicitDeny None - - -)"
    fi
    policy+="\"Resource\":\"${STATE_BUCKET}\"}]}"
    set_bucket_policy "${case_dir}" "${policy}"
    run_check "${case_dir}" "escaped-${spec}" O7a
    expect_result "escaped-${spec}" 3 'PRINCIPAL CHECK UNDECIDED 9' || return
    expect_rows "escaped-${spec}" 'POLICY_ESCAPED' 9 || return
    if [[ "${spec}" == unicode ]] &&
      [[ "$(count_requests "$(evidence_dir "${case_dir}")" \
        "\"CallerArn\":\"${USER_DEPLOY}\"" \
        "\"PolicySourceArn\":\"${USER_DEPLOY}\"")" -ne 3 ]]; then
      testlib::fail "escaped-unicode: 「\\u」 Allow 정책이 사용자 요청에 붙지 않음"
      return
    fi
    expect_no_leak "escaped-${spec}" "${case_dir}" || return
  done
  testlib::ok "30 escaped → 「\\/」 · 「\\u」 정책은 POLICY_ESCAPED 9 · 「\\u」" \
    "Allow 는 사용자 요청에 정책 · CallerArn"
}

#######################################
# 사례 31: 증거 쓰기 실패 — 증거 디렉터리를 쓸 수 없게 되거나 증거 파일
# 자리마다 쓰기가 실패하면 종료 2 이고, 표준 오류에 bash 원시 진단(절대
# 경로)이 없다.
#######################################
expect_evidence_write_failure() {
  local case_dir spec name
  for spec in lock sts.txt req-1.json res-1.err res-1.txt principals.tsv \
    targets.tsv policy-1.json policies.tsv pca-policy.json result.tsv \
    report.txt complete.txt.tmp; do
    name="write-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    if [[ "${spec}" == lock ]]; then
      run_check "${case_dir}" "${name}" FAKE_AWS_LOCK_EVIDENCE=1 O7a
    else
      run_check "${case_dir}" "${name}" "FAKE_AWS_BLOCK_FILE=${spec}" O7a
    fi
    expect_unverifiable "${name}" || return
    testlib::expect_stderr_count "${name}" '증거를 쓰지 못함' 1 || return
    expect_no_leak "${name}" "${case_dir}" || return
  done
  testlib::ok "31 evidence-write → 증거 디렉터리 잠김 · 파일 자리 12곳의 쓰기" \
    "실패는 종료 2 · 표준 오류에 절대 경로 0"
}

main() {
  testlib::init principal_check_test
  expect_normal
  expect_pc0_pc1_order
  expect_request_shape
  expect_allowed_stops
  expect_missing_context
  expect_scp_exclusion
  expect_positive_control
  expect_principal_resolution
  expect_target_resolution
  expect_policy_absent
  expect_policy_shape
  expect_session_and_unread
  expect_permission_change
  expect_network_and_zone
  expect_call_failures
  expect_evidence_rejected
  expect_data_shape
  expect_counting
  expect_branch_check
  expect_cli_history
  expect_simulator_contract
  expect_resolution_responses
  expect_policy_reads
  expect_escaped_policy
  expect_evidence_write_failure
  testlib::finish principal_check_test
}

main "$@"
