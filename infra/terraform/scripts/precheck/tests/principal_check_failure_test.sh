#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — 실패 · 증거 묶음(계획 M17b
# r3.1 3-1 사례 19 · 20 · 21 · 23 과 r1 검수 지적의 사례 31).
#
# 고정 입력 · 사본 트리 · 공용 단언(사례 22 유출 — 모든 사례가 끝에
# 본다)은 principal_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/principal_check_failure_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/principal_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/principal_testlib.sh"

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
  testlib::init principal_check_failure_test
  expect_call_failures
  expect_evidence_rejected
  expect_data_shape
  expect_evidence_write_failure
  testlib::finish principal_check_failure_test
}

main "$@"
