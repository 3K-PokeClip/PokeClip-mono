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
# 판정 · 승인 한 번의 제한 시간(초). 정상 실행은 1초 안에 끝난다 — 넘기면
# 걸린 것으로 보고 끝낸다(FIFO 로 바꾼 증거를 읽다 걸리는 회귀).
readonly RUN_LIMIT=10
# 꼴은 맞지만 어느 결과의 해시도 아닌 sha256(승인 기록 변조용).
OTHER_SHA256="$(printf '%064d' 0)"
readonly OTHER_SHA256
# UTC 가 아닌 시각(완료 표지 · 승인 기록 변조용).
readonly NOT_UTC='2026-10-14T12:15:00+09:00'

#######################################
# 사본 트리와 고정 입력을 만든다.
#   alpha: 셋 다 baseline 허용. 빈 줄 · 주석 줄 · 계정 자리를 담는다.
#   beta: baseline 에 rds-db:connect 거부가 있다.
#   gamma: tag:GetTagKeys 가 baseline 에 없다.
#   alpha-stop: 이름이 alpha 로 시작한다(앞 기준을 섞어 찾지 않는지).
#   broken · dup · collapsed: 꼴이 틀린 묶음(열 수 · 동작 · 리전 중복 ·
#   빈 칸이 탭 IFS 로 접혀 세 칸처럼 읽히는 행).
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
  testlib::tsv tag:GetTagKeys '' ap-northeast-2 '*' >"${data}/collapsed.tsv" ||
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
# 사본 트리의 scp-recheck.sh 를 제한 시간(RUN_LIMIT) 안에서 돌린다. 「이름=값」
# 꼴 인자는 환경으로 넘긴다(FAKE_AWS_*).
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
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    env ${envs[@]+"${envs[@]}"} bash "${case_dir}/tree/scp-recheck.sh" "$@"
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
# 마지막 판정이 표준 오류로 알린 증거 디렉터리 이름(kty 가 보고받는 이름).
# Globals:
#   run_out
# Outputs:
#   디렉터리 이름.
# Returns:
#   알림 줄이 없으면 1.
#######################################
judged_name() {
  local line
  line="$(grep -m 1 '^scp-recheck: 증거 ' -- "${run_out}.stderr")" || return 1
  line="${line#*/.pokeclip-precheck/}"
  printf '%s\n' "${line%% *}"
}

#######################################
# 파일의 sha256(시험이 승인 기록을 고쳐 쓸 때).
# Arguments:
#   파일.
# Outputs:
#   64자리 16진수.
#######################################
sha256_of() {
  local out
  out="$(sha256sum -- "$1" 2>/dev/null || shasum -a 256 -- "$1")" || return 1
  printf '%s\n' "${out%% *}"
}

#######################################
# 승인 기록의 해시를 지금 result.tsv 로 다시 쓴다(시각 · 이유는 그대로) —
# 해시 대조를 지나서 꼴 검사만 보게 하려고.
# Arguments:
#   증거 디렉터리.
#######################################
rehash_acceptance() {
  local dir="$1" line sum
  line="$(cat -- "${dir}/accepted.tsv")" || return 1
  sum="$(sha256_of "${dir}/result.tsv")" || return 1
  testlib::tsv "${line%%$'\t'*}" "${sum}" "${line##*$'\t'}" \
    >"${dir}/accepted.tsv"
}

#######################################
# 완료 표지의 해시를 지금 result.tsv 로 다시 쓴다(시각은 그대로) — 판정이
# 처음부터 그 결과를 쓴 것처럼 만들려고.
# Arguments:
#   증거 디렉터리.
#######################################
rehash_marker() {
  local dir="$1" line sum
  line="$(cat -- "${dir}/complete.txt")" || return 1
  sum="$(sha256_of "${dir}/result.tsv")" || return 1
  testlib::tsv "${line%%$'\t'*}" "${sum}" >"${dir}/complete.txt"
}

#######################################
# 판정 하나를 돌리고 그 증거 디렉터리를 승인한다.
# Globals:
#   run_out, run_status
# Arguments:
#   사례 디렉터리, 묶음.
# Outputs:
#   승인한 증거 디렉터리 이름.
# Returns:
#   판정 · 승인이 안 되면 1.
#######################################
judge_and_accept() {
  local case_dir="$1" bundle="$2" name
  run_recheck "${case_dir}" "judge-${bundle}" "${bundle}"
  name="$(judged_name)" || return 1
  run_recheck "${case_dir}" "accept-${bundle}" --accept "${bundle}" \
    --dir "${name}" --reason 'kty 답: 진행'
  [[ "${run_status}" -eq 0 ]] || return 1
  printf '%s\n' "${name}"
}

#######################################
# 사례 2 --accept: CHANGED 결과를 그 증거 디렉터리 이름으로 승인하면 같은
# 결과의 다음 판정은 OK. 승인 기록(시각 · 해시 · 이유)은 그 디렉터리에
# 남고, 승인한 디렉터리 이름이 표준 오류에 나온다.
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
# 앞 기준은 같은 묶음 것만 찾는다 — alpha-stop 의 승인이 alpha 의 비교
# 기준이 되지 않는다.
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
  testlib::ok "prefix → 이름이 겹치는 묶음의 승인을 섞지 않음"
}

#######################################
# 다른 도구의 증거 디렉터리(M17b 꼴 「<묶음>-<시각>」 · 「collect-<묶음>-
# <시각>」)를 만든다 — 더 최근 시각이고, 모든 행 허용 결과 · 승인 표지를
# 담는다. scp-recheck 가 이것을 앞 기준으로 집으면 판정이 OK 로 바뀐다.
# Arguments:
#   증거 루트, 디렉터리 이름.
# Returns:
#   만들지 못하면 1.
#######################################
make_foreign_dir() {
  local dir="$1/$2"
  mkdir -m 700 -- "${dir}" || return 1
  {
    testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 allow allowed True
    testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
  } >"${dir}/result.tsv" || return 1
  printf '2999-12-31T00:00:00Z\n' >"${dir}/complete.txt" || return 1
  testlib::tsv 2999-12-31T00:00:00Z forged forged >"${dir}/accepted.tsv"
}

#######################################
# 장부 r1 #6: 증거 디렉터리 이름은 도구마다 겹치지 않는다. 같은 묶음 이름의
# 다른 도구 디렉터리가 더 최근이어도 앞 기준 찾기가 섞이지 않고, 그
# 이름으로는 승인할 수 없다.
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
  run_recheck "${case_dir}" foreign --accept gamma \
    --dir gamma-29991231T000000Z --reason 'kty 답'
  expect_unverifiable other-tool-accept || return
  testlib::ok "other-tool → 다른 도구의 최근 디렉터리를 기준 · 승인에 섞지 않음"
}

#######################################
# 보고받은 판정만 승인한다(장부 r2 #3): 판정 A 를 보고한 뒤 B 가 생겼고 B 는
# 미승인일 때 A 를 이름으로 승인하면 A 에만 승인이 남고, 다음 판정의 비교
# 기준이 A 다 — 늘 최신 판정을 승인하는 회귀를 잡는다. 이미 승인된 더 최근
# 판정보다 오래된 판정은 승인하지 않는다(시계 역행 · 순서 뒤바뀜).
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
# 끝나지 않은 판정은 승인할 수 없다 — 호출 실패로 끝난 판정 · 완료 표지가
# 없는(지운) 판정 · 표지의 꼴이 틀린 판정(장부 r2 #8 — 빈 표지 · 「UTC
# 시각<탭>해시」 가 아닌 글 · 해시는 맞고 시각만 UTC 가 아님).
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
# 장부 r2 #9: 승인은 kty 가 본 판정 그대로만 한다 — 판정 뒤 승인 전에
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
# 완료 표지 · 승인 기록은 다 쓴 뒤에만 생긴다(cc r2 지적 2): 기록에 넣을
# UTC 시각을 읽지 못하면(date 실패) 종료 2 이고 빈 complete.txt · 시각 칸이
# 빈 accepted.tsv 가 남지 않는다 — 남으면 끝나지 않은 판정이 끝난 것처럼,
# 하지 않은 승인이 한 것처럼 보인다.
#######################################
expect_no_record_when_time_fails() {
  local case_dir bin real_date name
  case_dir="$(new_tree_case)" || {
    testlib::fail "time-fails: 시험 준비 실패"
    return
  }
  bin="${case_dir}/in/datebin"
  # 기록의 UTC 시각 꼴(+%Y-%m-%dT%H:%M:%SZ)일 때만 실패하는 date.
  if ! real_date="$(command -v date)" || ! mkdir -p -- "${bin}" ||
    ! {
      # shellcheck disable=SC2016 # 가짜 date 의 본문 글자 그대로다.
      printf '%s\n' '#!/bin/bash' 'for arg in "$@"; do' \
        '  [[ "${arg}" == "+%Y-%m-%dT%H:%M:%SZ" ]] && exit 1' 'done'
      printf 'exec %q "$@"\n' "${real_date}"
    } >"${bin}/date" || ! chmod 700 "${bin}/date"; then
    testlib::fail "time-fails: 시험 준비 실패"
    return
  fi
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" judged \
    env PATH="${bin}:${FAKEBIN}:${PATH}" \
    bash "${case_dir}/tree/scp-recheck.sh" gamma
  expect_unverifiable time-fails-judge || return
  name="$(judged_name)" || {
    testlib::fail "time-fails: 판정이 증거 디렉터리 이름을 알리지 않음"
    return
  }
  if [[ -e "${case_dir}/home/.pokeclip-precheck/${name}/complete.txt" ]]; then
    testlib::fail "time-fails: 표지를 쓰지 못했는데 complete.txt 가 남음"
    return
  fi
  run_recheck "${case_dir}" judged-ok gamma
  name="$(judged_name)"
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" accept \
    env PATH="${bin}:${FAKEBIN}:${PATH}" \
    bash "${case_dir}/tree/scp-recheck.sh" --accept gamma --dir "${name}" \
    --reason 'kty 답'
  expect_unverifiable time-fails-accept || return
  if [[ -e "${case_dir}/home/.pokeclip-precheck/${name}/accepted.tsv" ]]; then
    testlib::fail "time-fails: 승인 시각을 못 읽었는데 accepted.tsv 가 남음"
    return
  fi
  testlib::ok "time-fails → 기록 시각 실패는 종료 2 · complete.txt ·" \
    "accepted.tsv 남지 않음"
}

#######################################
# 승인 기준 하나를 고친다(종류별).
# Arguments:
#   사례 디렉터리, 승인한 증거 디렉터리, 종류.
# Returns:
#   고치지 못하면 1.
#######################################
tamper_reference() {
  local case_dir="$1" dir="$2" kind="$3" line
  case "${kind}" in
    accepted-empty) : >"${dir}/accepted.tsv" ;;
    accepted-link)
      mv -- "${dir}/accepted.tsv" "${case_dir}/in/accepted.tsv" &&
        ln -s -- "${case_dir}/in/accepted.tsv" "${dir}/accepted.tsv"
      ;;
    accepted-dangling-link)
      # 대상 없는 링크 — 후보 찾기에서 빠지면 앞 기준이 조용히 바뀐다.
      rm -f -- "${dir}/accepted.tsv" &&
        ln -s -- "${case_dir}/in/nowhere.tsv" "${dir}/accepted.tsv"
      ;;
    accepted-bad-time)
      line="$(cat -- "${dir}/accepted.tsv")" &&
        testlib::tsv "${NOT_UTC}" "${line#*$'\t'}" >"${dir}/accepted.tsv"
      ;;
    accepted-other-hash)
      line="$(cat -- "${dir}/accepted.tsv")" &&
        testlib::tsv "${line%%$'\t'*}" "${OTHER_SHA256}" "${line##*$'\t'}" \
          >"${dir}/accepted.tsv"
      ;;
    complete-link)
      mv -- "${dir}/complete.txt" "${case_dir}/in/complete.txt" &&
        ln -s -- "${case_dir}/in/complete.txt" "${dir}/complete.txt"
      ;;
    result-link)
      mv -- "${dir}/result.tsv" "${case_dir}/in/result.tsv" &&
        ln -s -- "${case_dir}/in/result.tsv" "${dir}/result.tsv"
      ;;
    result-0644) chmod 644 "${dir}/result.tsv" ;;
    result-fifo)
      rm -f -- "${dir}/result.tsv" && mkfifo -m 600 "${dir}/result.tsv"
      ;;
    result-fifo-link)
      mkfifo -m 600 "${case_dir}/in/result.fifo" &&
        rm -f -- "${dir}/result.tsv" &&
        ln -s -- "${case_dir}/in/result.fifo" "${dir}/result.tsv"
      ;;
    result-fields)
      # 빈 칸 하나가 탭 IFS 로 접히면 다섯 칸처럼 읽힌다.
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 allow '' \
          allowed True
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv" && rehash_acceptance "${dir}"
      ;;
    result-dup)
      testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True \
        >>"${dir}/result.tsv" && rehash_acceptance "${dir}"
      ;;
    result-changed)
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 deny \
          implicitDeny False
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv"
      ;;
    result-rehashed)
      # 결과를 꼴만 맞게 고치고 승인 기록의 해시도 맞춘다 — 판정 때의
      # 해시(완료 표지)만 다르다.
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 deny \
          implicitDeny False
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv" && rehash_acceptance "${dir}"
      ;;
    complete-missing) rm -f -- "${dir}/complete.txt" ;;
    sts-link)
      mv -- "${dir}/sts.txt" "${case_dir}/in/sts.txt" &&
        ln -s -- "${case_dir}/in/sts.txt" "${dir}/sts.txt"
      ;;
    sts-0644) chmod 644 "${dir}/sts.txt" ;;
    *) return 0 ;;
  esac
}

#######################################
# 승인 기준이 손대진 채로는 기준이 되지 않는다(종료 2): 승인 기록이 빈 ·
# 링크 · 대상 없는 링크 · UTC 아닌 시각 · 판정 때와 다른 해시, 완료 표지가
# 링크 · 없음, 결과가 링크 · 0644 · FIFO · FIFO 를 가리키는 링크 · 칸 수 틀림
# (빈 칸 접힘) · 중복 행 · 승인 뒤 바뀜 · 승인 기록 해시까지 맞춰 바뀜, 호출
# 주체 기록이 링크 · 0644, 다른 계정에서 승인한 기준. 기준 파일은 내용 ·
# 해시를 읽기 전에 검사하므로 FIFO 에서 걸리지 않는다(RUN_LIMIT 안에 끝남).
#######################################
expect_tampered_reference_rejected() {
  local kind case_dir name env_arg
  for kind in accepted-empty accepted-link accepted-dangling-link \
    accepted-bad-time accepted-other-hash complete-link complete-missing \
    result-link result-0644 result-fifo result-fifo-link result-fields \
    result-dup result-changed result-rehashed sts-link sts-0644 \
    other-account; do
    if ! case_dir="$(new_tree_case)" ||
      ! name="$(judge_and_accept "${case_dir}" gamma)" ||
      ! tamper_reference "${case_dir}" \
        "${case_dir}/home/.pokeclip-precheck/${name}" "${kind}"; then
      testlib::fail "tamper-${kind}: 시험 준비 실패"
      return
    fi
    env_arg=FAKE_AWS_SIM=ok
    [[ "${kind}" == other-account ]] && env_arg=FAKE_AWS_ACCOUNT=842093557711
    run_recheck "${case_dir}" after "${env_arg}" gamma
    expect_unverifiable "tamper-${kind}" || return
    if grep -q -- '842093557711' "${run_out}.stdout" "${run_out}.stderr"; then
      testlib::fail "tamper-${kind}: 다른 계정 ID 가 출력에 나옴"
      return
    fi
  done
  testlib::ok "tamper → 승인 기록 · 완료 표지 · 결과 · 호출 주체 기록 변조와" \
    "다른 계정 기준 모두 종료 2(FIFO 에서 걸리지 않음)"
}

#######################################
# baseline.tsv 의 빈 칸이 탭 IFS 로 접혀 다섯 칸처럼 읽히는 행은 받지 않는다
# (종료 2) — 접히면 그 묶음의 앞 기준이 조용히 바뀐다.
#######################################
expect_collapsed_baseline_rejected() {
  local case_dir
  case_dir="$(new_tree_case)" || {
    testlib::fail "collapsed-baseline: 시험 준비 실패"
    return
  }
  testlib::tsv gamma tag:GetTagKeys ap-northeast-2 '' allow \
    "${JUDGED}" >>"${case_dir}/tree/data/stage_actions/baseline.tsv"
  run_recheck "${case_dir}" collapsed gamma
  expect_unverifiable collapsed-baseline || return
  testlib::ok "collapsed-baseline → 빈 칸이 접히는 baseline 행은 종료 2"
}

#######################################
# 결과 파일의 마지막 줄 뒤에 줄바꿈이 없어도 그 행을 빠뜨리지 않는다(줄
# 단위로 읽는 모든 루프의 규칙). 판정이 그렇게 쓴 것처럼 완료 표지의
# 해시도 맞춘다.
#######################################
expect_result_without_final_newline() {
  local case_dir name dir content
  case_dir="$(new_tree_case)" || {
    testlib::fail "final-newline: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" first gamma
  name="$(judged_name)"
  dir="${case_dir}/home/.pokeclip-precheck/${name}"
  if ! content="$(cat -- "${dir}/result.tsv")" ||
    ! printf '%s' "${content}" >"${dir}/result.tsv" ||
    ! rehash_marker "${dir}"; then
    testlib::fail "final-newline: 시험 준비 실패"
    return
  fi
  run_recheck "${case_dir}" accept --accept gamma --dir "${name}" \
    --reason 'kty 답'
  run_recheck "${case_dir}" second gamma
  expect_result final-newline 0 'SCP RECHECK OK 2' 2 || return
  testlib::ok "final-newline → 줄바꿈 없는 마지막 행도 앞 기준에 듦"
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

#######################################
# 장부 r2 #5: 사용자가 준 입력을 그대로 되울리지 않는다 — 묶음 자리 · --dir
# 자리에 절대 경로(셸이 ~ 를 펼친 HOME 경로 등)를 주면 종료 2 이고, 표준
# 출력 · 표준 오류에 절대 경로(HOME · 로컬 계정 이름)가 없다.
#######################################
expect_input_paths_not_echoed() {
  local case_dir name spec paths
  case_dir="$(new_tree_case)" || {
    testlib::fail "input-paths: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" judged gamma
  name="$(judged_name)"
  for spec in judge-bundle accept-bundle accept-dir; do
    case "${spec}" in
      judge-bundle)
        run_recheck "${case_dir}" "${spec}" "${case_dir}/home/gamma"
        ;;
      accept-bundle)
        run_recheck "${case_dir}" "${spec}" --accept "${case_dir}/home/gamma" \
          --dir "${name}" --reason 'kty 답'
        ;;
      *)
        run_recheck "${case_dir}" "${spec}" --accept gamma \
          --dir "${case_dir}/home/.pokeclip-precheck/${name}" --reason 'kty 답'
        ;;
    esac
    expect_unverifiable "input-paths-${spec}" || return
    paths="$(testlib::abs_path_lines "${run_out}.stdout")"
    paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
    if [[ -n "${paths}" ]]; then
      testlib::fail "input-paths-${spec}: 입력의 절대 경로를 되울림 — ${paths}"
      return
    fi
  done
  testlib::ok "input-paths → 묶음 · --dir 자리의 절대 경로는 종료 2 · 되울리지" \
    "않음"
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
    ! ln -s -- "${repo}/evidence" "${root}/scp-gamma-20260101T000000Z"; then
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
# 응답 · 여러 줄 · 칸이 모자란 응답 · 호출 주체 확인 실패 · IAM 사용자가 아닌 주체 · 묶음 없음 · 꼴이
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
    'sim-two-lines FAKE_AWS_SIM=two-lines alpha' \
    'sim-short FAKE_AWS_SIM=short alpha' \
    'identity-fail FAKE_AWS_IDENTITY=fail alpha' \
    'identity-role FAKE_AWS_IDENTITY=role alpha' \
    'no-bundle FAKE_AWS_SIM=ok nope' \
    'baseline-name FAKE_AWS_SIM=ok baseline' \
    'broken-bundle FAKE_AWS_SIM=ok broken' \
    'dup-bundle FAKE_AWS_SIM=ok dup' \
    'collapsed-bundle FAKE_AWS_SIM=ok collapsed'; do
    # 「이름 환경 묶음」 세 낱말 — here-string 대신 매개변수 확장으로 나눈다.
    name="${spec%% *}"
    bundle="${spec##* }"
    env_arg="${spec#* }"
    env_arg="${env_arg%% *}"
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
  expect_other_tool_dirs_ignored
  expect_accept_named_judgment
  expect_accept_incomplete_rejected
  expect_accept_changed_result_rejected
  expect_no_record_when_time_fails
  expect_tampered_reference_rejected
  expect_result_without_final_newline
  expect_collapsed_baseline_rejected
  expect_accept_misuse
  expect_input_paths_not_echoed
  expect_approved_link_rejected
  expect_unverifiable_cases
  testlib::finish scp_recheck_test
}

main "$@"
