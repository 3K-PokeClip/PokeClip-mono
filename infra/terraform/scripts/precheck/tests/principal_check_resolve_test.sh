#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — 해석 · 정책 묶음(계획 M17b
# r3.1 3-1 사례 10 부터 18 과 r1 검수 지적의 사례 28 · 29 · 30).
#
# 고정 입력 · 사본 트리 · 공용 단언(사례 22 유출 — 모든 사례가 끝에
# 본다)은 principal_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/principal_check_resolve_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/principal_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/principal_testlib.sh"

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

main() {
  testlib::init principal_check_resolve_test
  expect_principal_resolution
  expect_target_resolution
  expect_policy_absent
  expect_policy_shape
  expect_session_and_unread
  expect_permission_change
  expect_network_and_zone
  expect_resolution_responses
  expect_policy_reads
  expect_escaped_policy
  testlib::finish principal_check_resolve_test
}

main "$@"
