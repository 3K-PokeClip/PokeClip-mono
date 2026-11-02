#!/usr/bin/env bash
#
# scp-recheck.sh 를 시험한다 — 판정 · baseline 비교 묶음(계획 M17a c1
# 사례 2 의 OK · DENIED · CHANGED, 기준에 없는 동작, 조직 판정 없음 건수,
# 빈 칸 든 baseline 행 거부).
#
# 사본 트리 · 고정 입력 · 공용 단언(사례 5 유출 — 모든 사례가 끝에 본다)은
# scp_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/scp_recheck_verdict_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/scp_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/scp_testlib.sh"

#######################################
# 사례 2 OK: 모두 허용이고 baseline 과 같으면 종료 0. 원본(요청 · 응답)은
# 증거 디렉터리에 남는다.
# Globals:
#   run_out, FAKE_ACCOUNT
#######################################
expect_ok() {
  local case_dir raw
  case_dir="$(new_tree_case)" || {
    testlib::fail "ok: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" ok alpha
  expect_result ok 0 'SCP RECHECK OK 3' 3 || return
  if [[ "$(grep -c -- '| 허용 |' "${run_out}.stdout")" -ne 3 ||
    "$(grep -c -- ' alpha ' "${run_out}.stdout")" -ne 3 ]]; then
    testlib::fail "ok: 13-1 줄에 묶음 · 판정이 없음 — $(cat -- "${run_out}.stdout")"
    return
  fi
  raw="$(grep -rlF -- "${FAKE_ACCOUNT}" "${case_dir}/home/.pokeclip-precheck")"
  if [[ -z "${raw}" ]]; then
    testlib::fail "ok: 증거 디렉터리에 원본(계정 ID 가 든 요청)이 없음"
    return
  fi
  expect_no_leak ok "${case_dir}" || return
  testlib::ok "ok → 종료 0 · OK 3 · 원본은 증거 디렉터리에만"
}

#######################################
# 사례 2 DENIED: 거부가 baseline 과 같아도(바뀜 0) 종료 1.
#######################################
expect_denied() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "denied: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" denied \
    FAKE_AWS_DENY='rds-db:connect@ap-northeast-2' beta
  expect_result denied 1 'SCP RECHECK DENIED 1' 2 || return
  expect_no_leak denied "${case_dir}" || return
  testlib::ok "denied → 종료 1 · DENIED 1"
}

#######################################
# 사례 2 CHANGED: baseline 의 거부가 허용으로 바뀌면(거부 없음) 종료 3.
#######################################
expect_changed() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "changed: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" changed beta
  expect_result changed 3 'SCP RECHECK CHANGED 1' 2 || return
  expect_no_leak changed "${case_dir}" || return
  testlib::ok "changed → 종료 3 · CHANGED 1"
}

#######################################
# 사례 2 DENIED + CHANGED: baseline 허용이 거부로 바뀌면 종료 1. 명시적
# 거부도 거부로 센다.
#######################################
expect_denied_and_changed() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "denied-changed: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" implicit \
    FAKE_AWS_DENY='rds-db:connect@ap-northeast-2' alpha
  expect_result denied-changed 1 'SCP RECHECK DENIED 1' 3 || return
  run_recheck "${case_dir}" explicit \
    FAKE_AWS_EXPLICIT_DENY='cloudfront-keyvaluestore:PutKey@us-east-1' alpha
  expect_result denied-changed-explicit 1 'SCP RECHECK DENIED 1' 3 || return
  expect_no_leak denied-changed "${case_dir}" || return
  testlib::ok "denied-changed → 종료 1(바뀜과 겹쳐도) · explicitDeny 도 거부"
}

#######################################
# 사례 2(kty 58차): 기준에 없는 동작은 CHANGED(종료 3), 그 동작이 거부면
# DENIED(종료 1).
#######################################
expect_not_in_baseline() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "not-in-baseline: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" allowed gamma
  expect_result not-in-baseline 3 'SCP RECHECK CHANGED 1' 2 || return
  run_recheck "${case_dir}" denied \
    FAKE_AWS_DENY='tag:GetTagKeys@ap-northeast-2' gamma
  expect_result not-in-baseline-denied 1 'SCP RECHECK DENIED 1' 2 || return
  expect_no_leak not-in-baseline "${case_dir}" || return
  testlib::ok "not-in-baseline → 허용이면 종료 3 · 거부면 종료 1"
}

#######################################
# 마지막 판정의 「조직 판정 없음」 줄을 본다 — 건수가 0 이 아니면 표준
# 오류 · report.txt 에 그 건수로 한 줄씩 있고 표준 출력에는 없다. 0 이면
# 어디에도 없다. 줄에는 동작 이름 · 계정 ID · ARN 이 없다.
# Arguments:
#   사례 이름, 사례 디렉터리, 기대 건수.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_orgs_note() {
  local name="$1" case_dir="$2" want="$3" report line got_err got_report
  report="${case_dir}/home/.pokeclip-precheck/$(judged_name)/report.txt"
  line="조직 판정 없음 ${want} 행(AllowedByOrganizations 가 True · False"
  line+=" 아님) — 판정은 EvalDecision 그대로"
  [[ "${want}" -eq 0 ]] && line='조직 판정 없음'
  got_err="$(testlib::count "${run_out}.stderr" "${line}")"
  got_report="$(testlib::count "${report}" "${line}")"
  if [[ ! -f "${report}" ]] ||
    [[ "${want}" -eq 0 && "${got_err}${got_report}" != 00 ]] ||
    [[ "${want}" -ne 0 && "${got_err}${got_report}" != 11 ]] ||
    [[ "$(testlib::count "${run_out}.stdout" '조직 판정 없음')" -ne 0 ]]; then
    testlib::fail "${name}: 조직 판정 없음 ${want} 행 줄을 표준 오류 ·" \
      "report.txt 에 기대했으나 ${got_err} · ${got_report} — $(cat -- \
      "${run_out}.stderr")"
    return 1
  fi
  if grep -hF -- '조직 판정 없음' "${run_out}.stderr" "${report}" |
    grep -qE -- '[a-z0-9-]+:[A-Za-z]|arn:|[0-9]{12}'; then
    testlib::fail "${name}: 조직 판정 없음 줄에 동작 이름 · ARN · 계정 ID"
    return 1
  fi
}

#######################################
# kty 60차 (다): 시뮬레이터 셋째 칸(AllowedByOrganizations)이
# True · False 가 아닌 행(None · 아무 글자)은 건수만 표준 오류 · report.txt
# 에 한 줄로 드러낸다. 판정 · 종료 코드 · 표준 출력은 그대로다(판정은
# EvalDecision 만) — None 1행이면 「1 행」 · 아무 글자 2행이면 「2 행」 ·
# True · False 만이면 줄이 없다.
#######################################
expect_orgs_unknown_reported() {
  local case_dir others
  case_dir="$(new_tree_case)" || {
    testlib::fail "orgs: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" none \
    FAKE_AWS_ORGS='rds-db:connect@ap-northeast-2=None' alpha
  expect_result orgs-none 0 'SCP RECHECK OK 3' 3 || return
  expect_orgs_note orgs-none "${case_dir}" 1 || return
  others='rds-db:connect@ap-northeast-2=x'
  others+=' cloudfront-keyvaluestore:PutKey@us-east-1=true'
  run_recheck "${case_dir}" other FAKE_AWS_ORGS="${others}" alpha
  expect_result orgs-other 0 'SCP RECHECK OK 3' 3 || return
  expect_orgs_note orgs-other "${case_dir}" 2 || return
  run_recheck "${case_dir}" known \
    FAKE_AWS_DENY='rds-db:connect@ap-northeast-2' alpha
  expect_result orgs-known 1 'SCP RECHECK DENIED 1' 3 || return
  expect_orgs_note orgs-known "${case_dir}" 0 || return
  expect_no_leak orgs "${case_dir}" || return
  testlib::ok "orgs → None · 아무 글자 행은 건수만 표준 오류 · report.txt 에" \
    "한 줄 · True · False 만이면 없음 · 종료 코드 그대로"
}

#######################################
# baseline.tsv 의 빈 칸 든 행은 받지 않는다(종료 2) — 받으면 그 묶음의 앞
# 기준이 조용히 바뀐다. 판정 칸 뒤에 빈 칸이 끼면 탭 IFS 로는 접혀 다섯
# 칸처럼 읽히고, 칸을 나눠도 판정까지는 맞게 읽혀 판정 칸 검사로는 못
# 잡는다 — 칸 수 검사가 잡는다.
# Globals:
#   JUDGED
#######################################
expect_collapsed_baseline_rejected() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "collapsed-baseline: 시험 준비 실패"
    return
  }
  testlib::tsv gamma tag:GetTagKeys ap-northeast-2 allow '' \
    "${JUDGED}" >>"${case_dir}/tree/data/stage_actions/baseline.tsv"
  run_recheck "${case_dir}" collapsed gamma
  expect_unverifiable collapsed-baseline || return
  testlib::ok "collapsed-baseline → 빈 칸이 접히는 baseline 행은 종료 2"
}

main() {
  testlib::init scp_recheck_verdict_test
  expect_ok
  expect_denied
  expect_changed
  expect_denied_and_changed
  expect_not_in_baseline
  expect_orgs_unknown_reported
  expect_collapsed_baseline_rejected
  testlib::finish scp_recheck_verdict_test
}

main "$@"
