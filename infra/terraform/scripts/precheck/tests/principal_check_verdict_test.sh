#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — 판정 묶음(계획 M17b r3.1 3-1
# 사례 5 · 6 · 7 · 9 · 24 · 25).
#
# 고정 입력 · 사본 트리 · 공용 단언(사례 22 유출 — 모든 사례가 끝에
# 본다)은 principal_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/principal_check_verdict_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/principal_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/principal_testlib.sh"

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

main() {
  testlib::init principal_check_verdict_test
  expect_allowed_stops
  expect_missing_context
  expect_positive_control
  expect_counting
  expect_branch_check
  testlib::finish principal_check_verdict_test
}

main "$@"
