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
# Globals:
#   JUDGED
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
# 꼴 인자는 환경으로 넘긴다(FAKE_AWS_* · AWS_CLI_HISTORY_FILE).
# Globals:
#   RUN_LIMIT
# Arguments:
#   사례 디렉터리, 결과 이름, [이름=값 …] 스크립트 인자.
#######################################
run_recheck() {
  local case_dir="$1" name="$2"
  local -a envs=()
  shift 2
  while [[ "$#" -gt 0 && ( "$1" == FAKE_AWS_*=* ||
    "$1" == AWS_CLI_HISTORY_FILE=* ) ]]; do
    envs+=("$1")
    shift
  done
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    env ${envs[@]+"${envs[@]}"} bash "${case_dir}/tree/scp-recheck.sh" "$@"
}

#######################################
# 마지막 실행의 종료 코드 · 첫 줄 · 13-1 줄 수를 본다.
# Globals:
#   run_out, ROW_PATTERN, run_status
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
# Globals:
#   run_status, run_out
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
# 결과 파일을 「주어진 행 + 맞는 행(tag:GetTagKeys 허용)」 으로 바꿔 쓰고
# 완료 표지 · 승인 기록의 해시를 맞춘다 — 해시 대조를 지나 결과 파일의 값
# 꼴 검사에 닿게 하려고.
# Arguments:
#   증거 디렉터리, 주어진 행의 칸들.
# Returns:
#   쓰지 못하면 1.
#######################################
rewrite_result_row() {
  local dir="$1"
  shift
  {
    testlib::tsv "$@"
    testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
  } >"${dir}/result.tsv" && rehash_marker "${dir}" &&
    rehash_acceptance "${dir}"
}

#######################################
# 판정 하나를 돌리고 그 증거 디렉터리를 승인한다. 마지막 실행 결과는
# run_recheck 이 run_out · run_status 에 남긴다.
# Globals:
#   run_status
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
# 완료 표지 · 승인 기록은 다 쓴 뒤에만 생긴다(cc r2 지적 2): 기록에 넣을
# UTC 시각을 읽지 못하면(date 실패) 종료 2 이고 빈 complete.txt · 시각 칸이
# 빈 accepted.tsv 가 남지 않는다 — 남으면 끝나지 않은 판정이 끝난 것처럼,
# 하지 않은 승인이 한 것처럼 보인다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH
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
# 이상 I/O 때 명령 자체의 진단(절대 경로가 든)을 내지 않는다: 앞
# 기준의 완료 표지 · 승인 기록 · 호출 주체 기록을 읽는 cat 과 완료 표지를
# 옮기는 mv 가 실패하면 종료 2 이고, 원인은 그 자리의 die 문구가 말하며,
# 표준 출력 · 표준 오류에 절대 경로가 없다. 검사(링크 아닌 0600)는 지나고
# 읽기 · 옮기기만 실패하는 상태(읽기 거부 ACL 등 — chmod 000 은 검사가 먼저
# 거부한다)는 PATH 앞의 가짜 cat · mv 로 만든다. 가짜는 FAKE_FAIL 로 끝나는
# 인자를 받으면 진짜처럼 경로를 담은 진단을 내고 실패한다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH, run_out
#######################################
expect_io_failure_hides_paths() {
  local case_dir bin tool real target want paths
  case_dir="$(new_tree_case)" || {
    testlib::fail "io-failure: 시험 준비 실패"
    return
  }
  bin="${case_dir}/in/iobin"
  mkdir -p -- "${bin}" || {
    testlib::fail "io-failure: 시험 준비 실패"
    return
  }
  for tool in cat mv; do
    if ! real="$(command -v "${tool}")" ||
      ! {
        # shellcheck disable=SC2016 # 가짜 명령의 본문 글자 그대로다.
        printf '%s\n' '#!/bin/bash' 'for arg in "$@"; do' \
          '  if [[ "${arg}" == */"${FAKE_FAIL}" ]]; then' \
          '    printf "%s: %s: Permission denied\n" "${0##*/}" "${arg}" >&2' \
          '    exit 1' '  fi' 'done'
        printf 'exec %q "$@"\n' "${real}"
      } >"${bin}/${tool}" || ! chmod 700 "${bin}/${tool}"; then
      testlib::fail "io-failure: 시험 준비 실패"
      return
    fi
  done
  if ! judge_and_accept "${case_dir}" gamma >/dev/null; then
    testlib::fail "io-failure: 시험 준비 실패"
    return
  fi
  for target in complete.txt accepted.tsv sts.txt complete.txt.tmp; do
    case "${target}" in
      complete.txt) want='완료 표지를 읽지 못함' ;;
      accepted.tsv) want='승인 기록을 읽지 못함' ;;
      sts.txt) want='앞 기준의 호출 주체 기록을 읽지 못함' ;;
      *) want='완료 표지를 쓰지 못함' ;;
    esac
    testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "io-${target}" \
      env PATH="${bin}:${FAKEBIN}:${PATH}" FAKE_FAIL="${target}" \
      bash "${case_dir}/tree/scp-recheck.sh" gamma
    expect_unverifiable "io-${target}" || return
    paths="$(testlib::abs_path_lines "${run_out}.stdout")"
    paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
    if [[ -n "${paths}" ||
      "$(testlib::count "${run_out}.stderr" "${want}")" -ne 1 ]]; then
      testlib::fail "io-${target}: 「${want}」 만 기대했으나 —" \
        "$(cat -- "${run_out}.stderr")"
      return
    fi
  done
  testlib::ok "io-failure → cat · mv 실패는 종료 2 · 그 자리 die 문구 ·" \
    "명령 진단의 절대 경로 0"
}

#######################################
# 호출 주체 증거(sts.err · sts.txt)를 쓰지 못하면 종료 2 이고, 원인은
# 「증거를 쓰지 못함(<파일 이름>)」 한 줄이며 bash 원시 진단(절대 경로)이
# 없다. sts.err 자리는 호출 전에 비운 파일을 먼저 만들므로 「호출 주체를
# 확인하지 못함」 으로 가지 않는다. 쓰기 막기는 PATH 앞의 가짜 mkdir 이 증거
# 디렉터리를 만들 때 그 이름의 디렉터리를 심어 만든다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH, run_out
#######################################
expect_identity_write_failure() {
  local case_dir bin target name paths
  case_dir="$(new_tree_case)" || {
    testlib::fail "identity-write: 시험 준비 실패"
    return
  }
  bin="${case_dir}/in/mkdirbin"
  if ! mkdir -p -- "${bin}" || ! testlib::make_blocking_mkdir "${bin}"; then
    testlib::fail "identity-write: 시험 준비 실패"
    return
  fi
  for target in sts.err sts.txt; do
    name="identity-write-${target}"
    testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
      env PATH="${bin}:${FAKEBIN}:${PATH}" FAKE_AWS_SIM=ok \
      FAKE_BLOCK_PREFIX=scp-alpha FAKE_BLOCK_NAME="${target}" \
      bash "${case_dir}/tree/scp-recheck.sh" alpha
    expect_unverifiable "${name}" || return
    testlib::expect_stderr_count "${name}" "증거를 쓰지 못함(${target})" 1 ||
      return
    if [[ "${target}" == sts.err ]]; then
      testlib::expect_stderr_count "${name}" '호출 주체를 확인하지 못함' 0 ||
        return
    fi
    paths="$(testlib::abs_path_lines "${run_out}.stdout")"
    paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
    if [[ -n "${paths}" ]]; then
      testlib::fail "${name}: 절대 경로 — ${paths}"
      return
    fi
  done
  expect_no_leak identity-write "${case_dir}" || return
  testlib::ok "identity-write → sts.err · sts.txt 쓰기 실패는 종료 2 ·" \
    "「증거를 쓰지 못함(<파일>)」 한 줄 · 절대 경로 0"
}

#######################################
# CLI 기록 — 프로필의 cli_history 가 enabled 이거나 AWS_CLI_HISTORY_FILE 이
# 설정돼 있으면 첫 AWS 호출 전에 종료 2(값 · 경로를 되울리지 않고 증거
# 디렉터리도 만들지 않음), 설정 읽기 실패도 종료 2, disabled 면 정상이고
# 설정 조회가 호출 주체 확인보다 먼저다. --accept 는 AWS 를 부르지 않으므로
# 켜져 있어도 승인한다.
# Globals:
#   run_out
#######################################
expect_cli_history() {
  local case_dir spec name log want calls dirs name_judged
  for spec in enabled file fail disabled; do
    name="cli-history-${spec}"
    case_dir="$(new_tree_case)" || {
      testlib::fail "${name}: 시험 준비 실패"
      return
    }
    log="${case_dir}/out/aws.log"
    case "${spec}" in
      file)
        run_recheck "${case_dir}" "${name}" \
          "AWS_CLI_HISTORY_FILE=${case_dir}/in/qxhistory.db" alpha
        ;;
      *)
        run_recheck "${case_dir}" "${name}" "FAKE_AWS_CLI_HISTORY=${spec}" alpha
        ;;
    esac
    if [[ "${spec}" == disabled ]]; then
      expect_result "${name}" 0 'SCP RECHECK OK 3' 3 || return
      if [[ "$(sed -n 1p -- "${log}")" != 'configure get cli_history' ||
        "$(sed -n 2p -- "${log}")" != 'sts get-caller-identity'* ]]; then
        testlib::fail "${name}: 설정 조회 · 호출 주체 확인 순서가 아님 —" \
          "$(cat -- "${log}")"
        return
      fi
      continue
    fi
    expect_unverifiable "${name}" || return
    calls="$(testlib::count "${log}" ' ')"
    want=1
    [[ "${spec}" == file ]] && want=0
    if [[ "${calls}" -ne "${want}" || ( "${want}" -eq 1 &&
      "$(cat -- "${log}")" != 'configure get cli_history' ) ]] ||
      grep -qF -e qxhistory -e enabled -- "${run_out}.stderr"; then
      testlib::fail "${name}: AWS 호출 ${calls}건(기대 ${want})이거나 값 ·" \
        "경로를 되울림 — $(cat -- "${run_out}.stderr")"
      return
    fi
    if [[ "${spec}" == fail ]]; then
      testlib::expect_stderr_count "${name}" \
        'CLI 기록 설정(cli_history)을 읽지 못함' 1 || return
    else
      testlib::expect_stderr_count "${name}" \
        'CLI 기록이 켜져 있음 — 증거 밖에 원문이 남음' 1 || return
    fi
    dirs="$(find "${case_dir}/home" -name 'scp-*' -type d | wc -l)"
    if [[ "${dirs}" -ne 0 ]]; then
      testlib::fail "${name}: 멈췄는데 증거 디렉터리 ${dirs}개"
      return
    fi
    expect_no_leak "${name}" "${case_dir}" || return
  done
  run_recheck "${case_dir}" cli-history-judge alpha
  name_judged="$(judged_name)" || {
    testlib::fail "cli-history-accept: 시험 준비 실패"
    return
  }
  run_recheck "${case_dir}" cli-history-accept FAKE_AWS_CLI_HISTORY=enabled \
    --accept alpha --dir "${name_judged}" --reason 'kty 답'
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "cli-history-accept: 승인은 AWS 를 부르지 않는데 종료" \
      "${run_status} — $(cat -- "${run_out}.stderr")"
    return
  fi
  expect_no_leak cli-history "${case_dir}" || return
  testlib::ok "cli-history → enabled · 기록 파일 환경 변수 · 설정 읽기 실패는" \
    "첫 AWS 호출 전 종료 2 · disabled 는 설정 조회 뒤 정상 · --accept 는 승인"
}

#######################################
# 승인 기준 하나를 고친다(종류별).
# Globals:
#   NOT_UTC, OTHER_SHA256
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
      # 칸이 모자란 행(AllowedByOrganizations 없음) — 칸을 나누면 마지막
      # 칸이 넷째 칸을 되풀이해 다섯 칸처럼 읽힌다. 표지 · 승인 기록의
      # 해시도 맞춰 해시 대조를 지나 칸 수 검사에 닿게 한다.
      {
        testlib::tsv iam:GetAccountAuthorizationDetails us-east-1 allow allowed
        testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True
      } >"${dir}/result.tsv" && rehash_marker "${dir}" &&
        rehash_acceptance "${dir}"
      ;;
    result-dup)
      # 표지 · 승인 기록의 해시도 맞춰 중복 검사에 닿게 한다.
      testlib::tsv tag:GetTagKeys ap-northeast-2 allow allowed True \
        >>"${dir}/result.tsv" && rehash_marker "${dir}" &&
        rehash_acceptance "${dir}"
      ;;
    result-pair)
      # 판정과 시뮬레이터 판정의 짝이 틀림(허용인데 implicitDeny).
      rewrite_result_row "${dir}" iam:GetAccountAuthorizationDetails \
        us-east-1 allow implicitDeny True
      ;;
    result-bad-action)
      rewrite_result_row "${dir}" iam-GetAccountAuthorizationDetails \
        us-east-1 allow allowed True
      ;;
    result-bad-region)
      rewrite_result_row "${dir}" iam:GetAccountAuthorizationDetails \
        us_east_1 allow allowed True
      ;;
    result-quote)
      # 다섯째 칸(AllowedByOrganizations)에 큰따옴표 — precheck::is_plain 이
      # 막는다.
      rewrite_result_row "${dir}" iam:GetAccountAuthorizationDetails \
        us-east-1 allow allowed '"True"'
      ;;
    result-empty)
      : >"${dir}/result.tsv" && rehash_marker "${dir}" &&
        rehash_acceptance "${dir}"
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
    # 승인 디렉터리를 검색할 수 없게 — 승인 기록이 있는지 볼 수 없다. 권한은
    # testlib 의 EXIT 트랩이 지우기 전에 되돌린다.
    approved-dir-0600) chmod 600 "${dir}" ;;
    approved-dir-0000) chmod 000 "${dir}" ;;
    approved-dir-file)
      # 디렉터리 자리에 실행 비트가 있는 일반 파일 — 검색 권한(-x)만 보면
      # 「검색할 수 있는데 승인 기록이 없음」 으로 읽힌다.
      mv -- "${dir}" "${case_dir}/in/approved" && : >"${dir}" &&
        chmod 700 "${dir}"
      ;;
    *) return 0 ;;
  esac
}

#######################################
# 승인 기준이 손대진 채로는 기준이 되지 않는다(종료 2): 승인 기록이 빈 ·
# 링크 · 대상 없는 링크 · UTC 아닌 시각 · 판정 때와 다른 해시, 완료 표지가
# 링크 · 없음, 결과가 링크 · 0644 · FIFO · FIFO 를 가리키는 링크 · 칸 수 틀림
# (칸 모자람) · 중복 행 · 판정과 시뮬레이터 판정의 짝 틀림 · 동작 꼴 밖 ·
# 리전 꼴 밖 · 큰따옴표 든 칸 · 빈 결과 · 승인 뒤 바뀜 · 승인 기록 해시까지
# 맞춰 바뀜, 호출 주체 기록이 링크 · 0644, 승인 디렉터리가 0600 · 0000(검색
# 불가 — 건너뛰면 앞 기준이 조용히 이전 승인 · baseline 으로 바뀐다) ·
# 디렉터리 자리의 실행 가능한 일반 파일, 다른 계정에서 승인한 기준. 기준
# 파일은 내용 · 해시를 읽기 전에 검사하므로 FIFO 에서 걸리지 않는다
# (RUN_LIMIT 안에 끝남). 사례마다 그 사례가 겨누는 검사에 닿게 한다 — 내용을
# 바꾼 사례는 표지 · 승인 기록의 해시를 맞춰 앞의 해시 대조에서 먼저 끝나지
# 않게 한다(해시 대조 자체를 겨누는 result-changed · result-rehashed 는
# 빼고).
# Globals:
#   run_out
#######################################
expect_tampered_reference_rejected() {
  local kind case_dir name env_arg
  for kind in accepted-empty accepted-link accepted-dangling-link \
    accepted-bad-time accepted-other-hash complete-link complete-missing \
    result-link result-0644 result-fifo result-fifo-link result-fields \
    result-dup result-pair result-bad-action result-bad-region result-quote \
    result-empty result-changed result-rehashed sts-link sts-0644 \
    approved-dir-0600 approved-dir-0000 approved-dir-file other-account; do
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
  testlib::ok "tamper → 승인 기록 · 완료 표지 · 결과 · 호출 주체 기록 변조 ·" \
    "검색 불가 승인 디렉터리와 다른 계정 기준 모두 종료 2(FIFO 에서 걸리지" \
    "않음)"
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
# 사용자가 준 입력을 그대로 되울리지 않는다 — 묶음 자리 · --dir
# 자리에 절대 경로(셸이 ~ 를 펼친 HOME 경로 등)를 주면 종료 2 이고, 표준
# 출력 · 표준 오류에 절대 경로(HOME · 로컬 계정 이름)가 없다.
# Globals:
#   run_out
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
# Globals:
#   run_out, REJECTED
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
# 응답 · 여러 줄 · 칸이 모자란 응답 · 호출 주체 확인 실패 · IAM 사용자가
# 아닌 주체 · 묶음 없음 · 꼴이 틀린 묶음은 모두 종료 2 이고 유출이 없다.
# 원인 문구로 가르는 셋: 호출 실패와 호출 자체의 AccessDenied 는 다른
# 문구다(「AccessDenied」 든 줄은 후자에만 하나). IAM 사용자가 아닌 주체는
# 그 문구로 멈춘다 — 가짜 aws 가 역할 세션 ARN 요청을 입력 오류로 끝내므로
# 그 검사가 없어도 종료 2 라, 문구로만 가를 수 있다.
# Globals:
#   run_out
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
    case "${name}" in
      sim-fail)
        testlib::expect_stderr_count "${name}" '시뮬레이터 호출 실패(' 1 &&
          testlib::expect_stderr_count "${name}" AccessDenied 0
        ;;
      sim-denied-call)
        testlib::expect_stderr_count "${name}" '시뮬레이터 호출 실패(' 0 &&
          testlib::expect_stderr_count "${name}" AccessDenied 1
        ;;
      identity-role)
        testlib::expect_stderr_count "${name}" \
          '호출 주체가 IAM 사용자가 아님' 1
        ;;
      *) ;;
    esac || return
  done
  run_recheck "${case_dir}" no-args
  expect_unverifiable no-args || return
  expect_no_leak unverifiable "${case_dir}" || return
  testlib::ok "unverifiable → 호출 실패 · AccessDenied · 빈 응답 · 주체 · 묶음" \
    "모두 종료 2 · 유출 0 · 호출 실패 · AccessDenied · 주체는 원인 문구로 가름"
}

main() {
  testlib::init scp_recheck_test
  expect_ok
  expect_denied
  expect_changed
  expect_denied_and_changed
  expect_not_in_baseline
  expect_orgs_unknown_reported
  expect_accept
  expect_accept_keeps_denied
  expect_prefix_isolated
  expect_other_tool_dirs_ignored
  expect_accept_named_judgment
  expect_accept_hidden_newer_rejected
  expect_renamed_acceptance_falls_back
  expect_accept_incomplete_rejected
  expect_accept_changed_result_rejected
  expect_no_record_when_time_fails
  expect_io_failure_hides_paths
  expect_identity_write_failure
  expect_cli_history
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
