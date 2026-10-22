#!/usr/bin/env bash
#
# 점검 도구(scp-recheck.sh · events.sh · principal-check.sh)가 실패할 때 bash
# 의 원시 진단(절대 경로가 든다)을 표준 오류에 내지 않는지 시험한다 — 계획
# M17d 묶음 A.
#
#   (가) lib 읽기 실패: 사본 트리에서 lib 하나를 지우면 종료 2 이고, 표준
#        오류는 그 도구의 정리 문구 한 줄뿐이며 절대 경로가 없다.
#   (나) 증거 쓰기 실패: PATH 앞의 가짜 mkdir(testlib::make_blocking_mkdir)이
#        증거 디렉터리를 만들 때 그 파일 이름의 디렉터리를 심어 쓰기를 막으면
#        종료 2 이고, 그 자리의 문구가 한 줄이며 절대 경로가 없다 — 자리마다.
#   (다) 승인 기록 쓰기 실패: 가짜 mkdir 로 막으면 「이미 승인됨」 에서 먼저
#        멈추므로, 쓰기 바로 앞의 시각 읽기(date)를 가짜로 바꿔 그때 증거
#        디렉터리의 쓰기 권한을 거둔다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/raw_diag_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

# 실행 한 번의 제한 시간(초). 정상 실행은 1초 안에 끝난다.
readonly RUN_LIMIT=10
# scp-recheck.sh 고정 묶음(alpha)의 판정 증거 디렉터리 이름 접두.
readonly SCP_PREFIX='scp-alpha'

#######################################
# 사본 트리(2-3 판정 래퍼까지)와 고정 입력을 만든다 — scp 묶음 alpha(한 행 ·
# baseline 허용) · 이벤트 이름 한 행 · 빈 이벤트 표.
# Arguments:
#   사례 디렉터리.
# Returns:
#   만들지 못하면 1.
#######################################
setup_tree() {
  local case_dir="$1" data="$1/tree/data"
  testlib::make_principal_tree "${case_dir}/tree" || return 1
  printf '%s\n' '# 출처: 시험 고정 입력' \
    $'iam:GetAccountAuthorizationDetails\tus-east-1\t*' \
    >"${data}/stage_actions/alpha.tsv" || return 1
  {
    testlib::tsv '# 묶음' 동작 리전 판정 '13-1 판정 시각'
    testlib::tsv alpha iam:GetAccountAuthorizationDetails us-east-1 allow \
      '2026-10-13T10:59+09:00'
  } >"${data}/stage_actions/baseline.tsv" || return 1
  printf '%s\n' $'iam.amazonaws.com\tAttachUserPolicy\tus-east-1' \
    >"${data}/event_names.txt" || return 1
  : >"${case_dir}/in/events.tsv"
}

#######################################
# 새 사례 디렉터리와 사본 트리를 만든다. 명령 치환 안에서 불리므로 실패는
# 종료 코드로만 알린다(부르는 쪽이 센다).
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
# 마지막 실행이 종료 2 이고, 표준 출력 · 표준 오류에 절대 경로가 없고, 표준
# 오류에 기대 문구가 든 줄이 하나인지 본다.
# Globals:
#   run_status, run_out
# Arguments:
#   사례 이름, 기대 문구.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_clean_stop() {
  local name="$1" want="$2" paths
  if [[ "${run_status}" -ne 2 ]]; then
    testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status} —" \
      "$(cat -- "${run_out}.stderr")"
    return 1
  fi
  paths="$(testlib::abs_path_lines "${run_out}.stdout")"
  paths+="$(testlib::abs_path_lines "${run_out}.stderr")"
  if [[ -n "${paths}" ]]; then
    testlib::fail "${name}: 절대 경로 — ${paths}"
    return 1
  fi
  testlib::expect_stderr_count "${name}" "${want}" 1
}

#######################################
# (가) 도구 하나의 lib 하나를 지운 사본 트리에서 도구를 돌린다. 표준 출력은
# 비고, 표준 오류는 정리 문구 한 줄뿐이어야 한다.
# Globals:
#   RUN_LIMIT, run_out
# Arguments:
#   도구 파일, lib 파일 이름, 기대 문구, 도구 인자.
# Returns:
#   맞으면 0(아니면 실패를 센다).
#######################################
expect_lib_missing() {
  local tool="$1" lib="$2" want="$3" case_dir name="lib-${1%.sh}-${2%.sh}"
  shift 3
  case_dir="$(new_tree_case)" || {
    testlib::fail "${name}: 시험 준비 실패"
    return 1
  }
  rm -f -- "${case_dir}/tree/lib/${lib}" || {
    testlib::fail "${name}: 시험 준비 실패"
    return 1
  }
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    bash "${case_dir}/tree/${tool}" "$@"
  expect_clean_stop "${name}" "${want}" || return 1
  if [[ -s "${run_out}.stdout" ||
    "$(grep -c '' -- "${run_out}.stderr")" -ne 1 ]]; then
    testlib::fail "${name}: 표준 오류 한 줄만 기대했으나 —" \
      "$(cat -- "${run_out}.stdout" "${run_out}.stderr")"
    return 1
  fi
}

#######################################
# (가) 세 도구의 lib source 자리 전부.
#######################################
expect_lib_failures() {
  local lib
  for lib in evidence.sh:'증거 경로 함수를 읽지 못함' \
    precheck_common.sh:'공용 도우미를 읽지 못함'; do
    expect_lib_missing scp-recheck.sh "${lib%%:*}" \
      "scp-recheck: 검사 불가: ${lib#*:}" alpha || return
    expect_lib_missing events.sh "${lib%%:*}" "events: 검사 불가: ${lib#*:}" \
      --since 2026-10-13T00:00:00Z || return
  done
  for lib in evidence.sh precheck_common.sh principal_data.sh \
    principal_simulate.sh principal_resolve.sh principal_policy.sh; do
    expect_lib_missing principal-check.sh "${lib}" \
      "principal-check: 검사 불가: lib/${lib} 를 읽지 못함" O7a || return
  done
  testlib::ok "lib-missing → 세 도구의 lib 열 자리 · 종료 2 · 정리 문구" \
    "한 줄 · 절대 경로 0"
}

#######################################
# 증거 파일 하나의 쓰기를 막고 도구를 돌린다(가짜 mkdir 은 사례의
# in/mkdirbin 에 둔다).
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH
# Arguments:
#   사례 디렉터리, 결과 이름, 증거 디렉터리 이름 접두, 막을 파일 이름,
#   도구 파일, 도구 인자.
# Returns:
#   가짜 mkdir 을 만들지 못하면 1.
#######################################
run_blocked() {
  local case_dir="$1" name="$2" prefix="$3" target="$4" tool="$5"
  local bin="$1/in/mkdirbin"
  shift 5
  if [[ ! -x "${bin}/mkdir" ]]; then
    mkdir -p -- "${bin}" && testlib::make_blocking_mkdir "${bin}" || return 1
  fi
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" "${name}" \
    env PATH="${bin}:${FAKEBIN}:${PATH}" FAKE_AWS_SIM=ok \
    "FAKE_AWS_EVENTS=${case_dir}/in/events.tsv" \
    FAKE_BLOCK_PREFIX="${prefix}" FAKE_BLOCK_NAME="${target}" \
    bash "${case_dir}/tree/${tool}" "$@"
}

#######################################
# (나) scp-recheck.sh 판정의 증거 쓰기 자리마다. 판정 파일 셋(result.tsv ·
# reference.tsv · report.txt)과 완료 표지는 그 자리의 고유 문구(증거
# 디렉터리 이름)를 유지한다.
# Globals:
#   SCP_PREFIX
#######################################
expect_scp_write_failures() {
  local case_dir target want
  case_dir="$(new_tree_case)" || {
    testlib::fail "scp-write: 시험 준비 실패"
    return
  }
  for target in req-1.json res-1.err res-1.txt result.tsv reference.tsv \
    report.txt complete.txt.tmp; do
    case "${target}" in
      result.tsv | reference.tsv | report.txt)
        want="증거를 쓰지 못함(${SCP_PREFIX}-"
        ;;
      complete.txt.tmp) want="완료 표지를 쓰지 못함(${SCP_PREFIX}-" ;;
      *) want="증거를 쓰지 못함(${target})" ;;
    esac
    run_blocked "${case_dir}" "scp-write-${target}" "${SCP_PREFIX}" \
      "${target}" scp-recheck.sh alpha || {
      testlib::fail "scp-write: 시험 준비 실패"
      return
    }
    expect_clean_stop "scp-write-${target}" "${want}" || return
  done
  testlib::ok "scp-write → 판정 증거 쓰기 일곱 자리 · 종료 2 · 그 자리" \
    "문구 한 줄 · 절대 경로 0"
}

#######################################
# (나) events.sh 의 증거 쓰기 자리마다.
#######################################
expect_events_write_failures() {
  local case_dir target since
  case_dir="$(new_tree_case)" || {
    testlib::fail "events-write: 시험 준비 실패"
    return
  }
  since="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" || {
    testlib::fail "events-write: 시험 준비 실패"
    return
  }
  for target in lookup-1.txt lookup-1.err summary.tsv event_ids.txt; do
    run_blocked "${case_dir}" "events-write-${target}" events "${target}" \
      events.sh --since "${since}" || {
      testlib::fail "events-write: 시험 준비 실패"
      return
    }
    expect_clean_stop "events-write-${target}" \
      "증거를 쓰지 못함(${target})" || return
  done
  testlib::ok "events-write → 증거 쓰기 네 자리 · 종료 2 ·" \
    "「증거를 쓰지 못함(<파일>)」 한 줄 · 절대 경로 0"
}

#######################################
# (다) 승인 기록(accepted.tsv) 쓰기 실패. 정상 판정 하나를 만든 뒤, 승인
# 때 PATH 앞의 가짜 date 가 FAKE_LOCK_DIR(그 판정 디렉터리)을 0500 으로
# 바꾸고 진짜 date 를 부른다 — 승인 검사는 모두 지나고 쓰기만 실패한다.
# Globals:
#   RUN_LIMIT, FAKEBIN, PATH, SCP_PREFIX, run_status, run_out
#######################################
expect_accept_write_failure() {
  local case_dir bin real dir name
  case_dir="$(new_tree_case)" || {
    testlib::fail "accept-write: 시험 준비 실패"
    return
  }
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" judge \
    env FAKE_AWS_SIM=ok bash "${case_dir}/tree/scp-recheck.sh" alpha
  dir=""
  for name in "${case_dir}/home/.pokeclip-precheck/${SCP_PREFIX}"-*; do
    [[ -d "${name}" ]] && dir="${name}"
  done
  bin="${case_dir}/in/datebin"
  if [[ "${run_status}" -ne 0 || -z "${dir}" ]] ||
    ! real="$(command -v date)" || ! mkdir -p -- "${bin}" ||
    ! {
      # shellcheck disable=SC2016 # 가짜 명령의 본문 글자 그대로다.
      printf '%s\n' '#!/bin/bash' 'chmod 500 "${FAKE_LOCK_DIR}" || exit 1'
      printf 'exec %q "$@"\n' "${real}"
    } >"${bin}/date" || ! chmod 700 "${bin}/date"; then
    testlib::fail "accept-write: 시험 준비 실패 — $(cat -- "${run_out}.stderr")"
    return
  fi
  name="${dir##*/}"
  testlib::run_limited "${RUN_LIMIT}" "${case_dir}" accept-write \
    env PATH="${bin}:${FAKEBIN}:${PATH}" FAKE_LOCK_DIR="${dir}" \
    bash "${case_dir}/tree/scp-recheck.sh" --accept alpha --dir "${name}" \
    --reason 'kty 답'
  expect_clean_stop accept-write "승인 기록을 쓰지 못함(${name})" || return
  if [[ -e "${dir}/accepted.tsv" ]]; then
    testlib::fail "accept-write: 쓰기가 실패했는데 accepted.tsv 가 남음"
    return
  fi
  testlib::ok "accept-write → 승인 기록 쓰기 실패는 종료 2 ·" \
    "「승인 기록을 쓰지 못함(<이름>)」 한 줄 · 절대 경로 0"
}

main() {
  testlib::init raw_diag_test
  expect_lib_failures
  expect_scp_write_failures
  expect_events_write_failures
  expect_accept_write_failure
  testlib::finish raw_diag_test
}

main "$@"
