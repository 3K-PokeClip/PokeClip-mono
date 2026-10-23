# shellcheck shell=bash
# 상수 · 도우미 일부는 이 파일이 아니라 시험 파일(principal_check_*_test.sh)이
# 쓴다 — 이 파일만 검사하면 사용 쪽이 안 보인다.
# shellcheck disable=SC2034
#
# principal-check.sh 시험(principal_check_*_test.sh)이 함께 쓰는 부품 —
# 상수 · 고정 입력 · 사본 트리 · 실행 · 공용 단언(사례 22 유출 포함).
#
# 사례마다 점검 스크립트의 사본 트리를 만들고 data/principal_sim/ 에 고정
# 입력(주체 넷 · 양성 대조 · prod 대상 행)을 넣는다. 실제 데이터는
# data_files_test.sh 가 본다. 가짜 aws 의 읽기 답(FAKE_AWS_READS)과
# 시뮬레이터 답(FAKE_AWS_SIM_TABLE)은 사례 디렉터리의 in/ 에 두고, 사례가
# 바꿀 줄을 표 맨 위에 덧붙인다(맨 위에서 처음 맞는 줄을 쓴다).
#
# 유출 표식: 계정 ID · 호출 주체(testlib) 말고도 인스턴스 프로필 · 역할 ·
# 사용자 · 그룹 · 고객 관리형 정책 · 버킷 · 저장소 · 비밀 · 존 · 주제 · 큐
# 이름에 서로 다른 표식을 쓴다. 모든 사례가 끝에 표준 출력 · 표준 오류 · 가짜 aws argv ·
# 증거 루트 밖 파일(사례의 HOME · 출력 · 작업 디렉터리)에 표식이 0건인지,
# 표준 출력 · 표준 오류에 절대 경로가 0건인지 본다(사례 22). 래퍼는 사례의
# cwd/ 를 작업 디렉터리로 삼아 돈다.
#
# 이 파일이 testlib.sh 를 source 한다 — 시험 파일은 이 파일만 source 한다
# (testlib.sh 를 두 번 읽으면 readonly 가 겹쳐 실패한다).

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
readonly MARK_TOPIC='qxtopic'
readonly MARK_QUEUE='qxqueue'
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
readonly TOPIC_ARN="arn:aws:sns:${SEOUL_ACCOUNT}:${MARK_TOPIC}-events.fifo"
readonly QUEUE_ARN="arn:aws:sqs:${SEOUL_ACCOUNT}:${MARK_QUEUE}-clip.fifo"
QUEUE_URL="https://sqs.ap-northeast-2.amazonaws.com/${FAKE_ACCOUNT}/"
QUEUE_URL+="${MARK_QUEUE}-clip.fifo"
readonly QUEUE_URL
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
      -e "${MARK_SECRET}" -e "${MARK_ZONE}" -e "${MARK_TOPIC}" \
      -e "${MARK_QUEUE}" -e 'arn:aws' -- "${file}"; then
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
# 사본 트리의 lib/ 파일 하나를 sed 식으로 바꾼다(계약 어긋남을 만든다).
# Arguments:
#   사례 디렉터리, lib/ 안의 파일 이름, sed 식.
#######################################
edit_lib() {
  local file="$1/tree/lib/$2"
  sed -e "$3" -- "${file}" >"${file}.new" && mv -- "${file}.new" "${file}"
}
