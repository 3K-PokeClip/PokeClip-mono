#!/usr/bin/env bash
#
# principal-check.sh 를 시험한다 — 순서 · 요청 꼴 묶음(계획 M17b
# r3.1 3-1 사례 1 · 2 · 3 · 4 · 8 과 r1 검수 지적의 사례 26 · 27).
#
# 고정 입력 · 사본 트리 · 공용 단언(사례 22 유출 — 모든 사례가 끝에
# 본다)은 principal_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/principal_check_order_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/principal_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/principal_testlib.sh"

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

main() {
  testlib::init principal_check_order_test
  expect_normal
  expect_pc0_pc1_order
  expect_request_shape
  expect_scp_exclusion
  expect_cli_history
  expect_simulator_contract
  testlib::finish principal_check_order_test
}

main "$@"
