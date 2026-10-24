# shellcheck shell=bash
# 부품끼리 · principal-check.sh 와 전역 변수를 나눠 쓴다(각 함수 주석의
# Globals) — 파일 하나만 검사하면 정의 · 사용 쪽이 안 보인다.
# shellcheck disable=SC2034,SC2154
#
# 2-3 판정의 자원 정책 직접 읽기(계획 M17b 2절 (3)(c)). 정책 칸만
# --query <칸> --output text 로 뽑아 증거의 policy-<n>.json 에 두고, 그 파일을
# 세 곳이 쓴다 — 꼴 단언 · 세션 ARN 검사 · Allow 검사. principal-check.sh 가
# source 한다 — aws_call · fail_call · die 와 대상 배열은 부르는 쪽에 있다.
#
#   s3             s3api get-bucket-policy(Bucket) --query Policy
#   secretsmanager secretsmanager get-resource-policy(SecretId)
#                  --query SECRET_QUERY → 「비밀 ARN<탭>정책」(정책이 없으면
#                  None)
#   ecr            ecr get-repository-policy(repositoryName) --query policyText
#   sns            sns get-topic-attributes(TopicArn) --query Attributes.Policy
#   sqs            sqs get-queue-attributes(QueueUrl — 큐 ARN 에서 만듦 ·
#                  AttributeNames QueueArn · Policy) --query QUEUE_QUERY
#                  → 「큐 ARN<탭>정책」(정책이 없으면 None)
# 다섯 모두 행의 리전(--region)으로 부른다. 「정책 없음」 = 버킷 정책 없음 ·
# 저장소 정책 없음 오류 코드와 비밀 · 큐의 정책 None. None 을 「없음」 으로
# 받는 것은 비밀 · 큐뿐이다 — S3 · ECR · SNS 의 성공 응답 None(정책 칸 없음 ·
# 칸 이름 오타 — 주제에는 정책이 늘 있다)은 종료 2 다. 큐 · 비밀은 응답
# 전체가 None(속성 칸 없음 · 칸 이름 오타)이거나 응답의 ARN 이 요청한 ARN 과
# 글자까지 같지 않으면 종료 2 다 — 큐는 속성을 돌려받지 못한 것을 「정책
# 없음」 으로 읽지 않으려고 QueueArn 을, 비밀은 부분 ARN 을 서비스가 다른
# 비밀로 푼 것을 놓치지 않으려고 ARN 을 같이 묻는다(GetResourcePolicy 는
# 부분 ARN 도 받는다 —
# https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_GetResourcePolicy.html).
#
# 「자원 없음」(주제 NotFound · 큐 AWS.SimpleQueueService.NonExistentQueue ·
# QueueDoesNotExist)은 이번 실행이 그 행의 첫 단계일 때만 받는다 — 그
# 단계가 자원을 만드는 단계라 직전 점검 때는 아직 없다(런북 2-3 한계 A13).
# 정책 없이 판정하고 상태 absent 와 report.txt 한 줄로 남긴다. 첫 단계 뒤의
# 「자원 없음」 은 종료 2 다(이름 오타 · 지워진 자원). S3 · ECR · 비밀은
# 「자원 없음」 을 받지 않는다. 그 밖 오류도 종료 2 다. 「없음」 이 아닌데
# 정책이 「{」 로 시작하지 않거나 "Statement" 를 담지 않으면(봉투째 · escape
# 가 남은 저장) 종료 2 다. 같은 대상(버킷 · 비밀 · 저장소 · 주제 · 큐)은 한
# 번만 읽는다.
#
# 정책 상태: none(정책 없음) · absent(첫 단계의 자원 없음 — 정책 없이 판정)
# · deny(Allow 없음 — 정책 없이 판정) · allow(「"Allow"」 또는 「\u」 가
# 있음) · session(「assumed-role/」 이 있음 — 그 자원의 모든 주체 행이
# SESSION_PRINCIPAL_IN_POLICY). 따로, 원문에 JSON 이스케이프(「\/」 · 「\u」)가
# 있으면 문자열 검사(세션 ARN · Allow)를 믿을 수 없어 그 자원의 모든 주체
# 행이 POLICY_ESCAPED 다(pol_escaped).

# 큐 속성 응답 꼴의 계약(tests/fakebin/aws 의 QUEUE_QUERY 와 글자까지 같다)과
# 그 응답이 담을 속성.
readonly QUEUE_QUERY='Attributes.[QueueArn,Policy]'
readonly QUEUE_ATTRIBUTES='"AttributeNames":["QueueArn","Policy"]'
# 비밀 정책 응답 꼴의 계약(tests/fakebin/aws 의 SECRET_QUERY 와 글자까지
# 같다).
readonly SECRET_QUERY='[ARN,ResourcePolicy]'

# 읽은 정책(같은 색인): 읽기 방법 · 대상 · 상태 · 파일 · 이스케이프 여부(1).
pol_read=()
pol_subject=()
pol_state=()
pol_file=()
pol_escaped=()
policy_n=0
# principal::policy_for 가 남기는 정책 색인(정책을 읽지 않는 행이면 빈 값).
found_policy=""
# principal::policy_call 이 남기는 「정책 없음」 · 「자원 없음」 오류 코드(grep
# -F 패턴 — 줄마다 하나, 없으면 빈 값).
absent_codes=""
missing_codes=""
# principal::classify_policy 가 남기는 상태 · 이스케이프 여부.
read_state=""
read_escaped=0

#######################################
# 자원 ARN 에서 정책 읽기 대상(버킷 이름 · 비밀 ARN · 저장소 이름 · 주제
# ARN · 큐 ARN)을 낸다.
# Arguments:
#   읽기 방법, 자원 ARN.
# Outputs:
#   대상.
# Returns:
#   ARN 이 읽기 방법의 서비스가 아니면 1.
#######################################
principal::policy_subject() {
  local arn="$2" bucket
  case "$1:${arn}" in
    s3:arn:aws:s3:::?*)
      bucket="${arn#arn:aws:s3:::}"
      printf '%s\n' "${bucket%%/*}"
      ;;
    secretsmanager:arn:aws:secretsmanager:*:secret:?*) printf '%s\n' "${arn}" ;;
    ecr:arn:aws:ecr:*:repository/?*) printf '%s\n' "${arn#*:repository/}" ;;
    sns:arn:aws:sns:?*:?*:?* | sqs:arn:aws:sqs:?*:?*:?*)
      printf '%s\n' "${arn}"
      ;;
    *) return 1 ;;
  esac
}

#######################################
# 큐 ARN(arn:aws:sqs:<리전>:<계정>:<이름>)에서 큐 URL 을 낸다.
# Arguments:
#   큐 ARN.
# Outputs:
#   큐 URL.
#######################################
principal::queue_url() {
  local rest="${1#arn:aws:sqs:}" region account
  region="${rest%%:*}"
  rest="${rest#*:}"
  account="${rest%%:*}"
  printf 'https://sqs.%s.amazonaws.com/%s/%s\n' "${region}" "${account}" \
    "${rest#*:}"
}

#######################################
# 정책 읽기 호출 하나를 부르고, 그 읽기의 「정책 없음」 · 「자원 없음」 오류
# 코드를 남긴다.
# Globals:
#   absent_codes, missing_codes, QUEUE_ATTRIBUTES, QUEUE_QUERY
# Arguments:
#   읽기 방법, 대상, 리전.
# Returns:
#   aws 의 종료 코드.
#######################################
principal::policy_call() {
  local subject="$2" region="$3" body
  absent_codes=""
  missing_codes=""
  case "$1" in
    s3)
      absent_codes='(NoSuchBucketPolicy)'
      aws_call "{\"Bucket\":\"${subject}\"}" s3api get-bucket-policy \
        --region "${region}" --query Policy
      ;;
    secretsmanager)
      aws_call "{\"SecretId\":\"${subject}\"}" secretsmanager \
        get-resource-policy --region "${region}" --query "${SECRET_QUERY}"
      ;;
    ecr)
      absent_codes='(RepositoryPolicyNotFoundException)'
      aws_call "{\"repositoryName\":\"${subject}\"}" ecr \
        get-repository-policy --region "${region}" --query policyText
      ;;
    sns)
      missing_codes='(NotFound)'
      aws_call "{\"TopicArn\":\"${subject}\"}" sns get-topic-attributes \
        --region "${region}" --query Attributes.Policy
      ;;
    *)
      missing_codes='(AWS.SimpleQueueService.NonExistentQueue)'$'\n'
      missing_codes+='(QueueDoesNotExist)'
      body="{\"QueueUrl\":\"$(principal::queue_url "${subject}")\","
      aws_call "${body}${QUEUE_ATTRIBUTES}}" sqs get-queue-attributes \
        --region "${region}" --query "${QUEUE_QUERY}"
      ;;
  esac
}

#######################################
# 큐 · 비밀 응답 「ARN<탭>정책」 을 검사하고 call_out 에 정책만 남긴다.
# Globals:
#   call_out, req_n
# Arguments:
#   요청한 ARN, 응답 이름(큐 속성 · 비밀).
#######################################
principal::arn_policy() {
  [[ "${call_out}" != None ]] ||
    die "$2 응답이 None(res-${req_n}.txt — 칸 없음)"
  [[ "${call_out}" == *$'\t'* ]] || die "$2 응답의 꼴이 다름(res-${req_n}.txt)"
  [[ "${call_out%%$'\t'*}" == "$1" ]] ||
    die "$2 응답의 ARN 이 요청과 다름(res-${req_n}.txt)"
  call_out="${call_out#*$'\t'}"
}

#######################################
# 정책 원문을 증거 파일에 쓰고 꼴을 단언한 뒤 상태를 정한다.
# Globals:
#   call_out, policy_n, read_state, read_escaped
# Arguments:
#   정책 파일.
#######################################
principal::classify_policy() {
  precheck::write_evidence "$1" '%s\n' "${call_out}"
  if [[ "${call_out}" != '{'* || "${call_out}" != *'"Statement"'* ]]; then
    die "정책 파일 꼴이 아님(policy-${policy_n}.json — 봉투째 · escape 저장)"
  fi
  read_state=deny
  read_escaped=0
  if [[ "${call_out}" == *assumed-role/* ]]; then
    read_state=session
  elif [[ "${call_out}" == *'"Allow"'* || "${call_out}" == *'\u'* ]]; then
    read_state=allow
  fi
  if [[ "${call_out}" == *'\/'* || "${call_out}" == *'\u'* ]]; then
    read_escaped=1
  fi
}

#######################################
# 정책 하나를 읽어 pol_* 에 더한다.
# Globals:
#   pol_read, pol_subject, pol_state, pol_file, pol_escaped, policy_n,
#   call_out, call_err, evidence_dir, req_n, absent_codes, missing_codes,
#   read_state, read_escaped
# Arguments:
#   읽기 방법, 대상, 리전, 이번 실행이 그 행의 첫 단계면 1.
#######################################
principal::read_policy() {
  local read="$1" subject="$2" file status=0
  policy_n=$((policy_n + 1))
  file="${evidence_dir}/policy-${policy_n}.json"
  read_state=none
  read_escaped=0
  principal::policy_call "${read}" "${subject}" "$3" || status=$?
  if [[ "${status}" -ne 0 ]]; then
    if [[ -n "${missing_codes}" ]] &&
      grep -qF -- "${missing_codes}" "${call_err}"; then
      [[ "$4" -eq 1 ]] ||
        die "자원 정책 읽기 대상이 없음(res-${req_n}.err — 첫 단계 뒤)"
      read_state=absent
    elif [[ -z "${absent_codes}" ]] ||
      ! grep -qF -- "${absent_codes}" "${call_err}"; then
      fail_call "자원 정책 읽기"
    fi
  else
    case "${read}" in
      sqs) principal::arn_policy "${subject}" '큐 속성' ;;
      secretsmanager) principal::arn_policy "${subject}" 비밀 ;;
      *) ;;
    esac
    if [[ "${call_out}" == None ]]; then
      [[ "${read}" == secretsmanager || "${read}" == sqs ]] ||
        die "자원 정책 응답이 None(res-${req_n}.txt — 정책 칸 없음)"
    else
      principal::classify_policy "${file}"
    fi
  fi
  pol_read+=("${read}")
  pol_subject+=("${subject}")
  pol_state+=("${read_state}")
  pol_file+=("${file}")
  pol_escaped+=("${read_escaped}")
}

#######################################
# 행 · 자원 ARN 의 정책 색인을 found_policy 에 남긴다(읽은 정책만).
# Globals:
#   pol_read, pol_subject, found_policy
# Arguments:
#   읽기 방법, 자원 ARN.
#######################################
principal::policy_for() {
  local subject i
  found_policy=""
  subject="$(principal::policy_subject "$1" "$2")" || return 0
  for i in ${pol_read[@]+"${!pol_read[@]}"}; do
    [[ "${pol_read[i]}" == "$1" ]] || continue
    if [[ "${pol_subject[i]}" == "${subject}" ]]; then
      found_policy="${i}"
      return 0
    fi
  done
}

#######################################
# 이번 단계에서 도는 대상 행의 자원 정책을 읽고 policies.tsv 에 남긴다.
# 첫 단계의 「자원 없음」 은 report.txt 에 남길 줄(notes)로도 더한다.
# Globals:
#   t_key, t_stage, t_read, t_region, t_active, tr_arns, stage, found_policy,
#   pol_*, policy_n, notes, evidence_dir
#######################################
principal::read_policies() {
  local t rest arn subject i first record=""
  for t in "${!t_key[@]}"; do
    [[ "${t_active[t]}" -eq 1 ]] || continue
    case "${t_read[t]}" in
      s3 | secretsmanager | ecr | sns | sqs) ;;
      *) continue ;;
    esac
    first=0
    [[ "${t_stage[t]}" != "${stage}" ]] || first=1
    rest="${tr_arns[t]}"
    while [[ -n "${rest}" ]]; do
      arn="${rest%%$'\n'*}"
      rest="${rest#*$'\n'}"
      subject="$(principal::policy_subject "${t_read[t]}" "${arn}")" ||
        die "자원 키 ${t_key[t]} 의 ARN 이 정책 읽기 방법의 서비스가 아님"
      principal::policy_for "${t_read[t]}" "${arn}"
      [[ -z "${found_policy}" ]] || continue
      principal::read_policy "${t_read[t]}" "${subject}" "${t_region[t]}" \
        "${first}"
      [[ "${pol_state[policy_n - 1]}" != absent ]] ||
        notes+="자원 없음(첫 단계 — 정책 없이 판정): ${t_key[t]}"$'\n'
    done
  done
  for i in ${pol_read[@]+"${!pol_read[@]}"}; do
    record+="${pol_file[i]##*/}"$'\t'"${pol_read[i]}"$'\t'
    record+="${pol_subject[i]}"$'\t'"${pol_state[i]}"$'\n'
  done
  precheck::write_evidence "${evidence_dir}/policies.tsv" '%s' "${record}"
}
