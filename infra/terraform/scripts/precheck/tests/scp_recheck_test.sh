#!/usr/bin/env bash
#
# scp-recheck.sh 를 시험한다 — 계획 M17a c1 사례 2(판정 · 비교 · 승인) ·
# 5(유출) · 6(검사 불가).
#
# 사례마다 점검 스크립트의 사본 트리를 만들고 data/ 에 고정 입력(시험 묶음 ·
# baseline.tsv)을 넣는다. 실제 묶음 데이터는 data_files_test.sh 가 본다.
# 가짜 aws 는 요청 파일이 계약(호출 주체 ARN · 계정 자리를 채운 자원 ARN ·
# 리전 문맥)과 다르면 입력 오류로 답하므로, 판정이 나온 사례는 요청 계약도
# 지킨 것이다. 모든 사례가 끝에 유출(사례 5)을 본다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/scp_recheck_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly REJECTED='EVIDENCE PATH REJECTED'
# 13-1 덧붙임 줄: | `동작` | 리전 | 허용·거부 | YYYY-MM-DD HH:MM KST | 묶음 … |
# shellcheck disable=SC2016 # 역따옴표는 마크다운 코드 표지 글자 그대로다.
ROW_PATTERN='^\| `[^`]+` \| [a-z0-9-]+ \| (허용|거부) \| '
ROW_PATTERN+='[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2} KST \| '
readonly ROW_PATTERN
# 13-1 판정 시각(고정 입력 baseline.tsv 의 다섯째 칸).
readonly JUDGED='2026-10-13T10:59+09:00'

#######################################
# 사본 트리와 고정 입력을 만든다.
#   alpha: 셋 다 baseline 허용. 빈 줄 · 주석 줄 · 계정 자리를 담는다.
#   beta: baseline 에 rds-db:connect 거부가 있다.
#   gamma: tag:GetTagKeys 가 baseline 에 없다.
#   alpha-stop: 이름이 alpha 로 시작한다(앞 기준을 섞어 찾지 않는지).
#   broken · dup: 꼴이 틀린 묶음(열 수 · 동작 · 리전 중복).
# Arguments:
#   사례 디렉터리.
# Returns:
#   만들지 못하면 1.
#######################################
setup_tree() {
  local data="$1/tree/data/stage_actions"
  testlib::make_tree "$1/tree" || return 1
  # shellcheck disable=SC2016 # ${ACCOUNT} 는 펼치지 않는 계정 자리다.
  {
    printf '# 출처: 시험 고정 입력\n'
    testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 '*'
    testlib::tsv rds-db:connect ap-northeast-2 \
      'arn:aws:rds-db:ap-northeast-2:${ACCOUNT}:dbuser:*/media_indexer'
    printf '\n'
    testlib::tsv cloudfront-keyvaluestore:PutKey us-east-1 '*'
  } >"${data}/alpha.tsv" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam:GetAccountAuthorizationDetails\tus-east-1\t*' \
    $'rds-db:connect\tap-northeast-2\t*' >"${data}/beta.tsv" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam:GetAccountAuthorizationDetails\tus-east-1\t*' \
    $'tag:GetTagKeys\tap-northeast-2\t*' >"${data}/gamma.tsv" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'ssm:SendCommand\tap-northeast-2\t*' >"${data}/alpha-stop.tsv" || return 1
  printf '%s\n' $'iam:GetAccountAuthorizationDetails\tus-east-1' \
    >"${data}/broken.tsv" || return 1
  printf '%s\n' $'tag:GetTagKeys\tap-northeast-2\t*' \
    $'tag:GetTagKeys\tap-northeast-2\tarn:aws:tag:::x' >"${data}/dup.tsv" ||
    return 1
  {
    testlib::tsv '# 묶음' 동작 리전 판정 '13-1 판정 시각'
    testlib::tsv alpha iam:GetAccountAuthorizationDetails us-east-1 allow \
      "${JUDGED}"
    testlib::tsv alpha rds-db:connect ap-northeast-2 allow "${JUDGED}"
    testlib::tsv alpha cloudfront-keyvaluestore:PutKey us-east-1 allow \
      "${JUDGED}"
    testlib::tsv beta iam:GetAccountAuthorizationDetails us-east-1 allow \
      "${JUDGED}"
    testlib::tsv beta rds-db:connect ap-northeast-2 deny \
      '2026-10-12T13:38+09:00'
    testlib::tsv gamma iam:GetAccountAuthorizationDetails us-east-1 allow \
      "${JUDGED}"
  } >"${data}/baseline.tsv"
}

#######################################
# 새 사례 디렉터리와 사본 트리를 만든다. 명령 치환 안에서 불리므로 실패는
# 세지 않고 종료 코드로만 알린다(부르는 쪽이 센다).
# Outputs:
#   사례 디렉터리.
# Returns:
#   준비 실패면 1.
#######################################
new_tree_case() {
  local case_dir
  case_dir="$(testlib::new_case)" || return 1
  setup_tree "${case_dir}" || return 1
  printf '%s\n' "${case_dir}"
}

#######################################
# 사본 트리의 scp-recheck.sh 를 돌린다. 「이름=값」 꼴 인자는 환경으로
# 넘긴다(FAKE_AWS_*).
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 스크립트 인자.
#######################################
run_recheck() {
  local case_dir="$1" name="$2"
  local -a envs=()
  shift 2
  while [[ "$#" -gt 0 && "$1" == FAKE_AWS_*=* ]]; do
    envs+=("$1")
    shift
  done
  testlib::run "${case_dir}" "${name}" env ${envs[@]+"${envs[@]}"} \
    bash "${case_dir}/tree/scp-recheck.sh" "$@"
}

#######################################
# 마지막 실행의 종료 코드 · 첫 줄 · 13-1 줄 수를 본다.
# Arguments:
#   사례 이름, 기대 종료 코드, 기대 첫 줄, 기대 13-1 줄 수.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_result() {
  local name="$1" status="$2" first="$3" rows="$4"
  local got_first got_rows bad_rows
  got_first="$(head -n 1 -- "${run_out}.stdout")"
  got_rows="$(grep -cE -- "${ROW_PATTERN}" "${run_out}.stdout")"
  bad_rows="$(tail -n +2 -- "${run_out}.stdout" |
    grep -cvE -- "${ROW_PATTERN}")"
  if [[ "${run_status}" -ne "${status}" || "${got_first}" != "${first}" ]]; then
    testlib::fail "${name}: 종료 ${status} · 「${first}」 를 기대했으나" \
      "${run_status} · 「${got_first}」 — $(cat -- "${run_out}.stderr")"
    return 1
  fi
  if [[ "${got_rows}" -ne "${rows}" || "${bad_rows}" -ne 0 ]]; then
    testlib::fail "${name}: 13-1 줄 ${rows}개를 기대했으나 ${got_rows}개" \
      "(꼴 밖 ${bad_rows}줄) — $(cat -- "${run_out}.stdout")"
    return 1
  fi
}

#######################################
# 마지막 실행이 검사 불가(종료 2 · 첫 줄 없음)였는지 본다.
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
  if grep -q '^SCP RECHECK ' -- "${run_out}.stdout"; then
    testlib::fail "${name}: 검사 불가인데 판정 줄이 나옴"
    return 1
  fi
}

#######################################
# 사례 5: 표준 출력 · 표준 오류 · 가짜 aws argv · 증거 루트 밖 파일에 가짜
# 계정 ID · 사용자 이름이 0건인지 본다.
# Arguments:
#   사례 이름, 사례 디렉터리.
# Returns:
#   0건이면 0(아니면 실패를 센다).
#######################################
expect_no_leak() {
  local name="$1" case_dir="$2" leaked
  leaked="$(testlib::leaks_outside_evidence "${case_dir}")"
  if [[ -n "${leaked}" ]]; then
    testlib::fail "${name}: 증거 루트 밖에 계정 ID · 사용자 이름 — ${leaked}"
    return 1
  fi
}

#######################################
# 사례 2 OK: 모두 허용이고 baseline 과 같으면 종료 0. 원본(요청 · 응답)은
# 증거 디렉터리에 남는다.
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
# 사례 2 --accept: CHANGED 결과를 승인하면 같은 결과의 다음 판정은 OK.
# 승인 기록(시각 · 이유)은 그 증거 디렉터리에 남는다.
#######################################
expect_accept() {
  local case_dir accepted
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" first gamma
  expect_result accept-first 3 'SCP RECHECK CHANGED 1' 2 || return
  run_recheck "${case_dir}" accept --accept gamma --reason 'kty 답: 진행'
  if [[ "${run_status}" -ne 0 ||
    "$(head -n 1 -- "${run_out}.stdout")" != 'SCP RECHECK ACCEPTED 2' ]]; then
    testlib::fail "accept: 종료 0 · ACCEPTED 2 를 기대했으나 ${run_status} —" \
      "$(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return
  fi
  accepted="$(find "${case_dir}/home/.pokeclip-precheck" -name accepted.tsv)"
  if [[ -z "${accepted}" ]] ||
    [[ "$(testlib::count "${accepted}" 'kty 답: 진행')" -ne 1 ]]; then
    testlib::fail "accept: 증거 디렉터리에 승인 이유가 없음"
    return
  fi
  run_recheck "${case_dir}" second gamma
  expect_result accept-second 0 'SCP RECHECK OK 2' 2 || return
  expect_no_leak accept "${case_dir}" || return
  testlib::ok "accept → 승인 뒤 같은 결과는 종료 0 · 이유는 증거 디렉터리에"
}

#######################################
# 사례 2: 거부가 든 결과는 승인해도 다음 판정이 종료 1.
#######################################
expect_accept_keeps_denied() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-denied: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" first \
    FAKE_AWS_DENY='tag:GetTagKeys@ap-northeast-2' gamma
  expect_result accept-denied-first 1 'SCP RECHECK DENIED 1' 2 || return
  run_recheck "${case_dir}" accept --accept gamma --reason 'kty 답: 기록만'
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
# 앞 기준은 같은 묶음 것만 찾는다 — alpha-stop 의 승인이 alpha 의 비교
# 기준이 되지 않는다.
#######################################
expect_prefix_isolated() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "prefix: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" stop alpha-stop
  expect_result prefix-stop 3 'SCP RECHECK CHANGED 1' 1 || return
  run_recheck "${case_dir}" accept --accept alpha-stop --reason 'kty 답'
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "prefix: 승인이 종료 0 이 아님 — ${run_status}"
    return
  fi
  run_recheck "${case_dir}" alpha alpha
  expect_result prefix-alpha 0 'SCP RECHECK OK 3' 3 || return
  testlib::ok "prefix → 이름이 겹치는 묶음의 승인을 섞지 않음"
}

#######################################
# --accept 의 잘못된 쓰임은 종료 2: 판정 전 · 이유에 줄바꿈 · 두 번 승인 ·
# 이유 없음.
#######################################
expect_accept_misuse() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-misuse: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" before --accept gamma --reason 'kty 답'
  expect_unverifiable accept-before-run || return
  run_recheck "${case_dir}" first gamma
  run_recheck "${case_dir}" newline --accept gamma --reason $'한 줄\n두 줄'
  expect_unverifiable accept-newline || return
  run_recheck "${case_dir}" noreason --accept gamma
  expect_unverifiable accept-no-reason || return
  run_recheck "${case_dir}" once --accept gamma --reason 'kty 답'
  run_recheck "${case_dir}" twice --accept gamma --reason 'kty 답'
  expect_unverifiable accept-twice || return
  testlib::ok "accept-misuse → 판정 전 · 줄바꿈 · 이유 없음 · 두 번 모두 종료 2"
}

#######################################
# 승인 기준을 읽을 때도 증거 경로 검사를 한다: 루트 안의 승인 디렉터리가
# 저장소를 가리키는 링크면 종료 2 이고, 링크 너머에 아무것도 쓰지 않는다.
#######################################
expect_approved_link_rejected() {
  local case_dir root repo before after
  case_dir="$(new_tree_case)" || {
    testlib::fail "approved-link: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  repo="${case_dir}/in/repo"
  if ! mkdir -p -- "${repo}/.git" "${repo}/evidence" ||
    ! chmod 700 "${repo}/evidence" ||
    ! printf '%s\n' \
      $'iam:GetAccountAuthorizationDetails\tus-east-1\tallow\tallowed\tTrue' \
      $'tag:GetTagKeys\tap-northeast-2\tallow\tallowed\tTrue' \
      >"${repo}/evidence/result.tsv" ||
    ! printf '2026-01-01T00:00:00Z\tkty 답\n' >"${repo}/evidence/accepted.tsv" ||
    ! mkdir -m 700 -- "${root}" ||
    ! ln -s -- "${repo}/evidence" "${root}/gamma-20260101T000000Z"; then
    testlib::fail "approved-link: 시험 준비 실패"
    return
  fi
  before="$(find "${repo}" | wc -l)"
  run_recheck "${case_dir}" link gamma
  after="$(find "${repo}" | wc -l)"
  expect_unverifiable approved-link || return
  if [[ "$(testlib::count "${run_out}.stderr" "${REJECTED}")" -lt 1 ||
    "${before}" -ne "${after}" ]]; then
    testlib::fail "approved-link: 증거 경로 거부가 아니거나 링크 너머가 바뀜"
    return
  fi
  testlib::ok "approved-link → 종료 2 · EVIDENCE PATH REJECTED · 링크 너머 그대로"
}

#######################################
# 사례 6: 재판정 호출 실패 · 호출 자체의 AccessDenied · 빈 응답 · 다른 동작의
# 응답 · 호출 주체 확인 실패 · IAM 사용자가 아닌 주체 · 묶음 없음 · 꼴이
# 틀린 묶음은 모두 종료 2 이고 유출이 없다.
#######################################
expect_unverifiable_cases() {
  local case_dir spec name env_arg bundle
  case_dir="$(new_tree_case)" || {
    testlib::fail "unverifiable: 시험 준비 실패"
    return
  }
  for spec in \
    'sim-fail FAKE_AWS_SIM=fail alpha' \
    'sim-denied-call FAKE_AWS_SIM=denied-call alpha' \
    'sim-empty FAKE_AWS_SIM=empty alpha' \
    'sim-bad-action FAKE_AWS_SIM=bad-action alpha' \
    'identity-fail FAKE_AWS_IDENTITY=fail alpha' \
    'identity-role FAKE_AWS_IDENTITY=role alpha' \
    'no-bundle FAKE_AWS_SIM=ok nope' \
    'baseline-name FAKE_AWS_SIM=ok baseline' \
    'broken-bundle FAKE_AWS_SIM=ok broken' \
    'dup-bundle FAKE_AWS_SIM=ok dup'; do
    read -r name env_arg bundle <<<"${spec}"
    run_recheck "${case_dir}" "${name}" "${env_arg}" "${bundle}"
    expect_unverifiable "${name}" || return
  done
  run_recheck "${case_dir}" no-args
  expect_unverifiable no-args || return
  expect_no_leak unverifiable "${case_dir}" || return
  testlib::ok "unverifiable → 호출 실패 · AccessDenied · 빈 응답 · 주체 · 묶음" \
    "모두 종료 2 · 유출 0"
}

main() {
  testlib::init scp_recheck_test
  expect_ok
  expect_denied
  expect_changed
  expect_denied_and_changed
  expect_not_in_baseline
  expect_accept
  expect_accept_keeps_denied
  expect_prefix_isolated
  expect_accept_misuse
  expect_approved_link_rejected
  expect_unverifiable_cases
  testlib::finish scp_recheck_test
}

main "$@"
