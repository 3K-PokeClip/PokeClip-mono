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
#                  --query ResourcePolicy(칸이 없으면 None)
#   ecr            ecr get-repository-policy(repositoryName) --query policyText
# 셋 모두 행의 리전(--region)으로 부른다. 「정책 없음」 = 버킷 정책 없음 ·
# 저장소 정책 없음 오류 코드와 비밀의 None. None 을 「없음」 으로 받는 것은
# 비밀뿐이다 — S3 · ECR 의 성공 응답 None(정책 칸 없음 · 칸 이름 오타)은
# 종료 2 다. 그 밖 오류도 종료 2 다. 「없음」 이 아닌데 파일이 「{」 로
# 시작하지 않거나 "Statement" 를 담지 않으면(봉투째 · escape 가 남은 저장)
# 종료 2 다. 같은 대상(버킷 · 비밀 · 저장소)은 한 번만 읽는다.
#
# 정책 상태: none(정책 없음) · deny(Allow 없음 — 정책 없이 판정) ·
# allow(「"Allow"」 또는 「\u」 가 있음) · session(「assumed-role/」 이 있음 —
# 그 자원의 모든 주체 행이 SESSION_PRINCIPAL_IN_POLICY). 따로, 원문에 JSON
# 이스케이프(「\/」 · 「\u」)가 있으면 문자열 검사(세션 ARN · Allow)를 믿을 수
# 없어 그 자원의 모든 주체 행이 POLICY_ESCAPED 다(pol_escaped).

# 읽은 정책(같은 색인): 읽기 방법 · 대상 · 상태 · 파일 · 이스케이프 여부(1).
pol_read=()
pol_subject=()
pol_state=()
pol_file=()
pol_escaped=()
policy_n=0
# policy_for 가 남기는 정책 색인(정책을 읽지 않는 행이면 빈 값).
found_policy=""

#######################################
# 자원 ARN 에서 정책 읽기 대상(버킷 이름 · 비밀 ARN · 저장소 이름)을 낸다.
# Arguments:
#   읽기 방법, 자원 ARN.
# Outputs:
#   대상.
# Returns:
#   ARN 이 읽기 방법의 서비스가 아니면 1.
#######################################
policy_subject() {
  local arn="$2" bucket
  case "$1:${arn}" in
    s3:arn:aws:s3:::?*)
      bucket="${arn#arn:aws:s3:::}"
      printf '%s\n' "${bucket%%/*}"
      ;;
    secretsmanager:arn:aws:secretsmanager:*:secret:?*) printf '%s\n' "${arn}" ;;
    ecr:arn:aws:ecr:*:repository/?*) printf '%s\n' "${arn#*:repository/}" ;;
    *) return 1 ;;
  esac
}

#######################################
# 정책 하나를 읽어 pol_* 에 더한다.
# Globals:
#   pol_read, pol_subject, pol_state, pol_file, pol_escaped, policy_n,
#   call_out, call_err, evidence_dir, req_n
# Arguments:
#   읽기 방법, 대상, 리전.
#######################################
read_policy() {
  local read="$1" subject="$2" region="$3" file state=deny absent="" status=0
  local escaped=0
  policy_n=$((policy_n + 1))
  file="${evidence_dir}/policy-${policy_n}.json"
  case "${read}" in
    s3)
      absent='(NoSuchBucketPolicy)'
      aws_call "{\"Bucket\":\"${subject}\"}" s3api get-bucket-policy \
        --region "${region}" --query Policy || status=$?
      ;;
    secretsmanager)
      aws_call "{\"SecretId\":\"${subject}\"}" secretsmanager \
        get-resource-policy --region "${region}" --query ResourcePolicy ||
        status=$?
      ;;
    *)
      absent='(RepositoryPolicyNotFoundException)'
      aws_call "{\"repositoryName\":\"${subject}\"}" ecr \
        get-repository-policy --region "${region}" --query policyText ||
        status=$?
      ;;
  esac
  if [[ "${status}" -ne 0 ]]; then
    if [[ -z "${absent}" ]] || ! grep -qF -- "${absent}" "${call_err}"; then
      fail_call "자원 정책 읽기"
    fi
    state=none
  elif [[ "${call_out}" == None ]]; then
    [[ "${read}" == secretsmanager ]] ||
      die "자원 정책 응답이 None(res-${req_n}.txt — 정책 칸 없음)"
    state=none
  else
    precheck::write_evidence "${file}" '%s\n' "${call_out}"
    if [[ "${call_out}" != '{'* || "${call_out}" != *'"Statement"'* ]]; then
      die "정책 파일 꼴이 아님(policy-${policy_n}.json — 봉투째 · escape 저장)"
    fi
    if [[ "${call_out}" == *assumed-role/* ]]; then
      state=session
    elif [[ "${call_out}" == *'"Allow"'* || "${call_out}" == *'\u'* ]]; then
      state=allow
    fi
    if [[ "${call_out}" == *'\/'* || "${call_out}" == *'\u'* ]]; then
      escaped=1
    fi
  fi
  pol_read+=("${read}")
  pol_subject+=("${subject}")
  pol_state+=("${state}")
  pol_file+=("${file}")
  pol_escaped+=("${escaped}")
}

#######################################
# 행 · 자원 ARN 의 정책 색인을 found_policy 에 남긴다(읽은 정책만).
# Globals:
#   pol_read, pol_subject, found_policy
# Arguments:
#   읽기 방법, 자원 ARN.
#######################################
policy_for() {
  local subject i
  found_policy=""
  subject="$(policy_subject "$1" "$2")" || return 0
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
# Globals:
#   t_key, t_read, t_region, t_active, tr_arns, found_policy, pol_*,
#   evidence_dir
#######################################
read_policies() {
  local t rest arn subject i record=""
  for t in "${!t_key[@]}"; do
    [[ "${t_active[t]}" -eq 1 ]] || continue
    case "${t_read[t]}" in
      s3 | secretsmanager | ecr) ;;
      *) continue ;;
    esac
    rest="${tr_arns[t]}"
    while [[ -n "${rest}" ]]; do
      arn="${rest%%$'\n'*}"
      rest="${rest#*$'\n'}"
      subject="$(policy_subject "${t_read[t]}" "${arn}")" ||
        die "자원 키 ${t_key[t]} 의 ARN 이 정책 읽기 방법의 서비스가 아님"
      policy_for "${t_read[t]}" "${arn}"
      [[ -z "${found_policy}" ]] || continue
      read_policy "${t_read[t]}" "${subject}" "${t_region[t]}"
    done
  done
  for i in ${pol_read[@]+"${!pol_read[@]}"}; do
    record+="${pol_file[i]##*/}"$'\t'"${pol_read[i]}"$'\t'
    record+="${pol_subject[i]}"$'\t'"${pol_state[i]}"$'\n'
  done
  precheck::write_evidence "${evidence_dir}/policies.tsv" '%s' "${record}"
}
