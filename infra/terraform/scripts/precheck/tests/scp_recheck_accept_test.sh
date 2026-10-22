#!/usr/bin/env bash
#
# scp-recheck.sh 를 시험한다 — 승인(--accept) · 앞 기준 고르기 묶음(계획
# M17a c1 사례 2 의 승인, 묶음 · 도구별 앞 기준 격리, 이름으로 고른 판정만
# 승인, 끝나지 않았거나 고친 판정의 승인 거부, --accept 의 잘못된 쓰임).
#
# 사본 트리 · 고정 입력 · 공용 단언(사례 5 유출 — 모든 사례가 끝에 본다)은
# scp_testlib.sh 에 있다.
#
# 사용(저장소 뿌리에서):
#   bash infra/terraform/scripts/precheck/tests/scp_recheck_accept_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/scp_testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/scp_testlib.sh"

#######################################
# 사례 2 --accept: CHANGED 결과를 그 증거 디렉터리 이름으로 승인하면 같은
# 결과의 다음 판정은 OK. 승인 기록(시각 · 해시 · 이유)은 그 디렉터리에
# 남고, 승인한 디렉터리 이름이 표준 오류에 나온다.
# Globals:
#   run_status, run_out
#######################################
expect_accept() {
  local case_dir name dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" first gamma
  expect_result accept-first 3 'SCP RECHECK CHANGED 1' 2 || return
  name="$(judged_name)" || {
    testlib::fail "accept: 판정이 증거 디렉터리 이름을 알리지 않음"
    return
  }
  dir="${case_dir}/home/.pokeclip-precheck/${name}"
  run_recheck "${case_dir}" accept --accept gamma --dir "${name}" \
    --reason 'kty 답: 진행'
  if [[ "${run_status}" -ne 0 ||
    "$(head -n 1 -- "${run_out}.stdout")" != 'SCP RECHECK ACCEPTED 2' ]] ||
    [[ "$(testlib::count "${run_out}.stderr" \
      "승인 ~/.pokeclip-precheck/${name}")" -ne 1 ]]; then
    testlib::fail "accept: 종료 0 · ACCEPTED 2 · 승인 디렉터리 알림을" \
      "기대했으나 ${run_status} — $(cat -- "${run_out}.stdout" \
      "${run_out}.stderr")"
    return
  fi
  if [[ "$(testlib::count "${dir}/accepted.tsv" 'kty 답: 진행')" -ne 1 ]]; then
    testlib::fail "accept: 그 증거 디렉터리에 승인 이유가 없음"
    return
  fi
  run_recheck "${case_dir}" second gamma
  expect_result accept-second 0 'SCP RECHECK OK 2' 2 || return
  expect_no_leak accept "${case_dir}" || return
  testlib::ok "accept → 이름으로 승인 · 같은 결과는 종료 0 · 승인 디렉터리 알림"
}

#######################################
# 사례 2: 거부가 든 결과는 승인해도 다음 판정이 종료 1.
# Globals:
#   run_status
#######################################
expect_accept_keeps_denied() {
  local case_dir name
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-denied: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" first \
    FAKE_AWS_DENY='tag:GetTagKeys@ap-northeast-2' gamma
  expect_result accept-denied-first 1 'SCP RECHECK DENIED 1' 2 || return
  name="$(judged_name)"
  run_recheck "${case_dir}" accept --accept gamma --dir "${name}" \
    --reason 'kty 답: 기록만'
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "accept-denied: 승인이 종료 0 이 아님 — ${run_status}"
    return
  fi
  run_recheck "${case_dir}" second \
    FAKE_AWS_DENY='tag:GetTagKeys@ap-northeast-2' gamma
  expect_result accept-denied-second 1 'SCP RECHECK DENIED 1' 2 || return
  testlib::ok "accept-denied → 승인해도 거부는 종료 1"
}

#######################################
# 마지막 실행의 표준 오류에 셸 산술 오류 · 절대 경로가 든 줄이 없는지 본다.
# 앞 기준 후보에 「scp-<묶음>-<시각>」 꼴이 아닌 디렉터리가 섞이면 비교 키
# 계산이 bash 산술 오류(value too great for base — 스크립트 절대 경로가 든
# 문구)로 실패해 그 후보가 우연히 빠진다. 종료 코드 · 표준 출력만 보면 후보
# 찾기의 접두 · 시각 고정이 없어도 통과하므로 이 줄로 가른다.
# Globals:
#   run_out
# Arguments:
#   사례 이름.
# Returns:
#   없으면 0(아니면 실패를 센다).
#######################################
expect_clean_stderr() {
  local name="$1" paths
  paths="$(testlib::abs_path_lines "${run_out}.stderr")"
  if [[ -n "${paths}" ||
    "$(testlib::count "${run_out}.stderr" 'value too great')" -ne 0 ]]; then
    testlib::fail "${name}: 표준 오류에 산술 오류 · 절대 경로 —" \
      "$(cat -- "${run_out}.stderr")"
    return 1
  fi
}

#######################################
# 앞 기준은 같은 묶음 것만 찾는다 — alpha-stop 의 승인이 alpha 의 비교
# 기준이 되지 않고, 후보 찾기에서부터 빠진다(표준 오류에 산술 오류 없음).
# Globals:
#   run_status
#######################################
expect_prefix_isolated() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "prefix: 시험 준비 실패"
    return
  }
  if ! judge_and_accept "${case_dir}" alpha-stop >/dev/null; then
    testlib::fail "prefix: alpha-stop 판정 · 승인 실패 — ${run_status}"
    return
  fi
  run_recheck "${case_dir}" alpha alpha
  expect_result prefix-alpha 0 'SCP RECHECK OK 3' 3 || return
  expect_clean_stderr prefix-alpha || return
  testlib::ok "prefix → 이름이 겹치는 묶음의 승인을 섞지 않음"
}

#######################################
# scp 가 아닌 증거 디렉터리(도구 접두가 없던 옛 꼴 「<묶음>-<시각>」 · M17b
# 꼴 「collect-<단계>-<시각>」)를 만든다 — 더 최근 시각이고, 모든 행 허용
# 결과와 새 꼴의 유효한 완료 표지 · 승인 기록 · 호출 주체 기록(모두 0600)을
# 담는다. scp-recheck 가 이것을 앞 기준으로 집으면 판정이 OK(종료 0)로
# 바뀌어 기대(종료 3)와 달라 잡힌다.
# Globals:
#   FAKE_ACCOUNT, FAKE_USER
# Arguments:
#   증거 루트, 디렉터리 이름.
# Returns:
#   만들지 못하면 1.
#######################################
make_foreign_dir() {
  local dir="$1/$2" at='2999-12-31T00:00:00Z' sum
  mkdir -m 700 -- "${dir}" || return 1
  {
    testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 allow allowed True
    testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
  } >"${dir}/result.tsv" || return 1
  sum="$(sha256_of "${dir}/result.tsv")" || return 1
  testlib::tsv "${at}" "${sum}" >"${dir}/complete.txt" &&
    testlib::tsv "${at}" "${sum}" 'kty 답' >"${dir}/accepted.tsv" &&
    testlib::tsv "arn:aws:iam::${FAKE_ACCOUNT}:user/${FAKE_USER}" \
      "${FAKE_ACCOUNT}" >"${dir}/sts.txt" &&
    chmod 600 "${dir}"/*
}

#######################################
# 증거 디렉터리 이름은 도구마다 겹치지 않는다. 같은 묶음 이름의
# 다른 도구 디렉터리가 더 최근이어도 앞 기준 찾기가 섞이지 않고(후보
# 찾기에서부터 빠짐 — 표준 오류에 산술 오류 · 절대 경로 없음), 그 이름으로는
# 승인할 수 없다.
#######################################
expect_other_tool_dirs_ignored() {
  local case_dir root name
  case_dir="$(new_tree_case other-tool)" || {
    testlib::fail "other-tool: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  run_recheck "${case_dir}" first gamma
  expect_result other-tool-first 3 'SCP RECHECK CHANGED 1' 2 || return
  for name in gamma-29991231T000000Z collect-gamma-29991231T000000Z; do
    make_foreign_dir "${root}" "${name}" || {
      testlib::fail "other-tool: 시험 준비 실패"
      return
    }
  done
  run_recheck "${case_dir}" second gamma
  expect_result other-tool-second 3 'SCP RECHECK CHANGED 1' 2 || return
  expect_clean_stderr other-tool-second || return
  run_recheck "${case_dir}" foreign --accept gamma \
    --dir gamma-29991231T000000Z --reason 'kty 답'
  expect_unverifiable other-tool-accept || return
  testlib::ok "other-tool → 다른 도구의 최근 디렉터리를 기준 · 승인에 섞지 않음"
}

#######################################
# 보고받은 판정만 승인한다: 판정 A 를 보고한 뒤 B 가 생겼고 B 는
# 미승인일 때 A 를 이름으로 승인하면 A 에만 승인이 남고, 다음 판정의 비교
# 기준이 A 다 — 늘 최신 판정을 승인하는 회귀를 잡는다. 이미 승인된 더 최근
# 판정보다 오래된 판정은 승인하지 않는다(시계 역행 · 순서 뒤바뀜).
# Globals:
#   run_status, run_out
#######################################
expect_accept_named_judgment() {
  local case_dir root first second third
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-named: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  run_recheck "${case_dir}" a gamma
  first="$(judged_name)"
  run_recheck "${case_dir}" b gamma
  second="$(judged_name)"
  run_recheck "${case_dir}" accept-a --accept gamma --dir "${first}" \
    --reason 'kty 답'
  if [[ "${run_status}" -ne 0 || ! -f "${root}/${first}/accepted.tsv" ||
    -e "${root}/${second}/accepted.tsv" ]]; then
    testlib::fail "accept-named: 보고한 판정(A)에만 승인이 남지 않음 —" \
      "${run_status} $(cat -- "${run_out}.stderr")"
    return
  fi
  run_recheck "${case_dir}" c gamma
  third="$(judged_name)"
  if [[ "$(testlib::count "${run_out}.stderr" \
    "비교 기준 승인 기준 ${first}")" -ne 1 ]]; then
    testlib::fail "accept-named: 다음 판정의 비교 기준이 A 가 아님 —" \
      "$(cat -- "${run_out}.stderr")"
    return
  fi
  run_recheck "${case_dir}" accept-c --accept gamma --dir "${third}" \
    --reason 'kty 답'
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "accept-named: 최근 판정(C) 승인이 종료 0 이 아님 —" \
      "${run_status}"
    return
  fi
  run_recheck "${case_dir}" accept-b --accept gamma --dir "${second}" \
    --reason 'kty 답'
  expect_unverifiable accept-older-than-approved || return
  testlib::ok "accept-named → B 가 미승인이어도 A 에만 승인 · 다음 비교 기준 A ·" \
    "더 오래된 판정 승인은 종료 2"
}

#######################################
# 승인 여부를 볼 수 없는 더 최근 판정이 있으면 그보다 오래된 판정을 승인하지
# 않는다(승인 대상 고르기도 같은 부류): A 를 판정하고 B 를 판정 ·
# 승인한 뒤 B 의 디렉터리를 검색할 수 없게(0600) 하고 A 를 승인하면 종료 2
# 이고 A 에 승인 기록이 없다. B 를 건너뛰면 이미 승인된 더 최근 판정보다
# 오래된 판정이 승인된다.
#######################################
expect_accept_hidden_newer_rejected() {
  local case_dir root first second
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-hidden-newer: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  run_recheck "${case_dir}" a gamma
  first="$(judged_name)"
  if ! second="$(judge_and_accept "${case_dir}" gamma)" ||
    ! chmod 600 "${root}/${second}"; then
    testlib::fail "accept-hidden-newer: 시험 준비 실패"
    return
  fi
  run_recheck "${case_dir}" accept-a --accept gamma --dir "${first}" \
    --reason 'kty 답'
  expect_unverifiable accept-hidden-newer || return
  if [[ -e "${root}/${first}/accepted.tsv" ]]; then
    testlib::fail "accept-hidden-newer: 거부했는데 A 에 승인 기록이 생김"
    return
  fi
  testlib::ok "accept-hidden-newer → 승인 여부를 볼 수 없는 더 최근 판정보다" \
    "오래된 판정의 승인은 종료 2"
}

#######################################
# 런북의 복구 절차: 승인 기준이 손상돼 종료 2 가 계속될 때 그 디렉터리를
# 지우지 않고 accepted.tsv 만 같은 디렉터리 안에서 다른 이름으로 바꾸면, 그
# 판정은 승인하지 않은 것과 같아 앞 기준이 baseline 으로 돌아간다(종료 3 ·
# 표준 오류의 「비교 기준 baseline.tsv」). 새 판정을 승인하면 다음 판정은
# 종료 0 이고, 이름을 바꾼 승인 기록은 그대로 남는다.
# Globals:
#   run_status, run_out
#######################################
expect_renamed_acceptance_falls_back() {
  local case_dir dir name moved
  case_dir="$(new_tree_case)" || {
    testlib::fail "renamed-acceptance: 시험 준비 실패"
    return
  }
  if ! name="$(judge_and_accept "${case_dir}" gamma)"; then
    testlib::fail "renamed-acceptance: 시험 준비 실패"
    return
  fi
  dir="${case_dir}/home/.pokeclip-precheck/${name}"
  moved="${dir}/accepted.tsv.damaged-20261017T000000Z"
  printf 'x\n' >>"${dir}/result.tsv"
  run_recheck "${case_dir}" damaged gamma
  expect_unverifiable renamed-acceptance-damaged || return
  mv -- "${dir}/accepted.tsv" "${moved}" || {
    testlib::fail "renamed-acceptance: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" renamed gamma
  expect_result renamed-acceptance 3 'SCP RECHECK CHANGED 1' 2 || return
  testlib::expect_stderr_count renamed-acceptance \
    '비교 기준 baseline.tsv' 1 || return
  run_recheck "${case_dir}" accept-new --accept gamma --dir "$(judged_name)" \
    --reason 'kty 답'
  run_recheck "${case_dir}" after gamma
  expect_result renamed-acceptance-after 0 'SCP RECHECK OK 2' 2 || return
  if [[ ! -f "${moved}" ]]; then
    testlib::fail "renamed-acceptance: 이름을 바꾼 승인 기록이 남지 않음"
    return
  fi
  testlib::ok "renamed-acceptance → 손상된 기준의 승인 기록 이름을 바꾸면" \
    "baseline 으로 돌아가 종료 3 · 새 판정 승인 뒤 종료 0 · 기록은 남음"
}

#######################################
# 끝나지 않은 판정은 승인할 수 없다 — 호출 실패로 끝난 판정 · 완료 표지가
# 없는(지운) 판정 · 표지의 꼴이 틀린 판정(빈 표지 · 「UTC
# 시각<탭>해시」 가 아닌 글 · 해시는 맞고 시각만 UTC 가 아님).
# Globals:
#   NOT_UTC
#######################################
expect_accept_incomplete_rejected() {
  local case_dir name marker kind
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-incomplete: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" failed FAKE_AWS_SIM=fail gamma
  name="$(judged_name)"
  run_recheck "${case_dir}" accept-failed --accept gamma --dir "${name}" \
    --reason 'kty 답'
  expect_unverifiable accept-failed-judgment || return
  for kind in missing empty garbage not-utc; do
    run_recheck "${case_dir}" "judged-${kind}" gamma
    name="$(judged_name)"
    marker="${case_dir}/home/.pokeclip-precheck/${name}/complete.txt"
    case "${kind}" in
      missing) rm -f -- "${marker}" ;;
      empty) : >"${marker}" ;;
      garbage) printf 'garbage\n' >"${marker}" ;;
      *)
        testlib::tsv "${NOT_UTC}" "$(sha256_of "${marker%/*}/result.tsv")" \
          >"${marker}"
        ;;
    esac
    run_recheck "${case_dir}" "accept-${kind}" --accept gamma --dir "${name}" \
      --reason 'kty 답'
    expect_unverifiable "accept-marker-${kind}" || return
  done
  testlib::ok "accept-incomplete → 실패한 판정 · 완료 표지 없음 · 빈 표지 ·" \
    "꼴 밖 표지 · UTC 아닌 표지 승인 모두 종료 2"
}

#######################################
# 승인은 kty 가 본 판정 그대로만 한다 — 판정 뒤 승인 전에
# result.tsv 를 꼴만 맞게 고치면 완료 표지의 해시(판정 때)와 달라 승인하지
# 않는다(종료 2 · 승인 기록 없음).
#######################################
expect_accept_changed_result_rejected() {
  local case_dir name dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-changed-result: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" judged gamma
  name="$(judged_name)"
  dir="${case_dir}/home/.pokeclip-precheck/${name}"
  {
    testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 allow allowed True
    testlib::tsv tag:GetTagKeys ap-northeast-2 deny implicitDeny False
  } >"${dir}/result.tsv"
  run_recheck "${case_dir}" accept --accept gamma --dir "${name}" \
    --reason 'kty 답'
  expect_unverifiable accept-changed-result || return
  if [[ -e "${dir}/accepted.tsv" || -L "${dir}/accepted.tsv" ]]; then
    testlib::fail "accept-changed-result: 거부했는데 승인 기록이 생김"
    return
  fi
  testlib::ok "accept-changed-result → 판정 뒤 바뀐 결과는 승인 종료 2"
}

#######################################
# --accept 의 잘못된 쓰임은 종료 2: 없는 판정 · 이유에 줄바꿈 · 이유 없음 ·
# 디렉터리 이름 없는 옛 꼴 · 다른 묶음의 판정 · 두 번 승인.
#######################################
expect_accept_misuse() {
  local case_dir name other
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-misuse: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" before --accept gamma \
    --dir scp-gamma-20260101T000000Z --reason 'kty 답'
  expect_unverifiable accept-before-run || return
  run_recheck "${case_dir}" alpha alpha
  other="$(judged_name)"
  run_recheck "${case_dir}" first gamma
  name="$(judged_name)"
  run_recheck "${case_dir}" newline --accept gamma --dir "${name}" \
    --reason $'한 줄\n두 줄'
  expect_unverifiable accept-newline || return
  run_recheck "${case_dir}" noreason --accept gamma --dir "${name}"
  expect_unverifiable accept-no-reason || return
  run_recheck "${case_dir}" oldform --accept gamma --reason 'kty 답'
  expect_unverifiable accept-without-dir || return
  run_recheck "${case_dir}" otherbundle --accept gamma --dir "${other}" \
    --reason 'kty 답'
  expect_unverifiable accept-other-bundle || return
  run_recheck "${case_dir}" once --accept gamma --dir "${name}" --reason 'kty 답'
  run_recheck "${case_dir}" twice --accept gamma --dir "${name}" \
    --reason 'kty 답'
  expect_unverifiable accept-twice || return
  testlib::ok "accept-misuse → 없는 판정 · 줄바꿈 · 이유 없음 · 옛 꼴 · 다른" \
    "묶음 · 두 번 모두 종료 2"
}

main() {
  testlib::init scp_recheck_accept_test
  expect_accept
  expect_accept_keeps_denied
  expect_prefix_isolated
  expect_other_tool_dirs_ignored
  expect_accept_named_judgment
  expect_accept_hidden_newer_rejected
  expect_renamed_acceptance_falls_back
  expect_accept_incomplete_rejected
  expect_accept_changed_result_rejected
  expect_accept_misuse
  testlib::finish scp_recheck_accept_test
}

main "$@"
