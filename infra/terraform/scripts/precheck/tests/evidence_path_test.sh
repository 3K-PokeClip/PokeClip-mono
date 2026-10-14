#!/usr/bin/env bash
#
# 증거 경로 함수(lib/evidence.sh)를 시험한다 — 계획 M17a c1 사례 1.
#
# 고정 루트는 $HOME/.pokeclip-precheck 이다. 사례마다 임시 디렉터리를 HOME
# 으로 주고, 함수를 새 bash 에서 불러(source) 돌린다. 거부 사례는 종료 2 ·
# 표준 오류 「EVIDENCE PATH REJECTED」 · 출력 파일 0 을 함께 본다.
#
# 사용: bash infra/terraform/scripts/precheck/tests/evidence_path_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly EVIDENCE_LIB="${PRECHECK_DIR}/lib/evidence.sh"
readonly REJECTED='EVIDENCE PATH REJECTED'
# 증거 디렉터리 이름: <묶음>-<UTC 시각>[-<2 ~ 9>].
readonly DIR_PATTERN='^alpha-[0-9]{8}T[0-9]{6}Z(-[2-9])?$'

#######################################
# 증거 함수 하나를 새 bash 에서 돌린다.
# Globals:
#   run_out, run_status
# Arguments:
#   사례 디렉터리, 결과 이름, 함수 이름과 인자.
#######################################
call_evidence() {
  local case_dir="$1" name="$2"
  shift 2
  # shellcheck disable=SC2016 # 작은따옴표 안은 새 bash 가 펼친다.
  testlib::run "${case_dir}" "${name}" bash -c \
    'source "$1" || exit 99; shift; "$@"' _ "${EVIDENCE_LIB}" "$@"
}

#######################################
# 디렉터리 아래 항목 수를 센다(디렉터리 자신은 빼고, 없으면 0).
# Arguments:
#   디렉터리.
# Outputs:
#   항목 수.
#######################################
entry_count() {
  if [[ ! -d "$1" ]]; then
    printf '0\n'
    return 0
  fi
  find "$1" -mindepth 1 | wc -l | tr -d ' '
}

#######################################
# 디렉터리 권한이 정확히 0700 인지 본다.
# Arguments:
#   디렉터리.
# Returns:
#   0700 이면 0.
#######################################
is_0700() {
  [[ -n "$(find "$1" -maxdepth 0 -type d -perm 0700 -print)" ]]
}

#######################################
# 마지막 실행이 거부(종료 2 · 표지 문구)였는지 본다.
# Arguments:
#   사례 이름.
# Returns:
#   거부였으면 0(아니면 실패를 센다).
#######################################
expect_rejected() {
  local name="$1"
  if [[ "${run_status}" -ne 2 ]]; then
    testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status}"
    return 1
  fi
  if [[ "$(testlib::count "${run_out}.stderr" "${REJECTED}")" -lt 1 ]]; then
    testlib::fail "${name}: 표준 오류에 「${REJECTED}」 없음 —" \
      "$(cat -- "${run_out}.stderr")"
    return 1
  fi
}

#######################################
# 정상: 0700 루트 아래 0700 하위 디렉터리를 만들어 돌려준다. 같은 초에 두 번
# 불러도 서로 다른 디렉터리다.
#######################################
expect_new_dir_ok() {
  local case_dir first second root
  case_dir="$(testlib::new_case)" || {
    testlib::fail "new-dir-ok: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  call_evidence "${case_dir}" first evidence::new_dir alpha
  first="$(cat -- "${run_out}.stdout")"
  if [[ "${run_status}" -ne 0 || ! -d "${first}" ]]; then
    testlib::fail "new-dir-ok: 종료 0 · 디렉터리를 기대했으나 ${run_status}" \
      "— $(cat -- "${run_out}.stderr")"
    return
  fi
  if [[ "${first%/*}" != "${root}" ]] ||
    ! [[ "${first##*/}" =~ ${DIR_PATTERN} ]]; then
    testlib::fail "new-dir-ok: 루트 바로 아래 <묶음>-<UTC 시각> 이 아님 — ${first}"
    return
  fi
  if ! is_0700 "${root}" || ! is_0700 "${first}"; then
    testlib::fail "new-dir-ok: 루트 · 하위 디렉터리가 0700 이 아님"
    return
  fi
  call_evidence "${case_dir}" second evidence::new_dir alpha
  second="$(cat -- "${run_out}.stdout")"
  if [[ "${run_status}" -ne 0 || "${second}" == "${first}" ||
    ! -d "${second}" ]]; then
    testlib::fail "new-dir-ok: 두 번째 호출이 새 디렉터리를 만들지 않음 —" \
      "${run_status} ${second}"
    return
  fi
  testlib::ok "new-dir-ok → 0700 루트 아래 0700 하위 디렉터리 · 거듭 불러도 새것"
}

#######################################
# 루트 밖: 루트 밖 경로를 검사하면 거부하고, 이름으로 루트를 벗어나려 해도
# 거부한다(아무것도 만들지 않음).
#######################################
expect_outside_root_rejected() {
  local case_dir outside
  case_dir="$(testlib::new_case)" || {
    testlib::fail "outside-root: 시험 준비 실패"
    return
  }
  outside="${case_dir}/in/elsewhere"
  mkdir -m 700 -- "${outside}" || {
    testlib::fail "outside-root: 시험 준비 실패"
    return
  }
  call_evidence "${case_dir}" check evidence::check_dir "${outside}"
  expect_rejected "outside-root(check)" || return
  call_evidence "${case_dir}" escape evidence::new_dir ../escape
  expect_rejected "outside-root(name)" || return
  if [[ "$(entry_count "${case_dir}/home")" -ne 0 ]]; then
    testlib::fail "outside-root: 거부했는데 HOME 아래에 무언가 생김"
    return
  fi
  testlib::ok "outside-root → 종료 2 · 출력 파일 0"
}

#######################################
# 조상에 표지(.git · .obsidian)가 있으면 루트를 만들지 않고 거부한다.
# Arguments:
#   사례 이름, 표지 이름, 표지 종류(dir · file).
#######################################
expect_marker_rejected() {
  local name="$1" marker="$2" kind="$3"
  local case_dir base
  case_dir="$(testlib::new_case)" || {
    testlib::fail "${name}: 시험 준비 실패"
    return
  }
  base="${case_dir}/in/tree"
  mkdir -p -- "${base}/home" || {
    testlib::fail "${name}: 시험 준비 실패"
    return
  }
  if [[ "${kind}" == dir ]]; then
    mkdir -- "${base}/${marker}"
  else
    printf 'gitdir: elsewhere\n' >"${base}/${marker}"
  fi
  # HOME 을 표지 아래로 옮긴다(사례 디렉터리의 home 대신).
  if ! rmdir -- "${case_dir}/home" ||
    ! ln -s -- "${base}/home" "${case_dir}/home"; then
    testlib::fail "${name}: 시험 준비 실패"
    return
  fi
  call_evidence "${case_dir}" marker evidence::new_dir alpha
  expect_rejected "${name}" || return
  if [[ "$(entry_count "${base}/home")" -ne 0 ]]; then
    testlib::fail "${name}: 거부했는데 HOME 아래에 무언가 생김"
    return
  fi
  testlib::ok "${name} → 종료 2 · 출력 파일 0"
}

#######################################
# 루트 안의 링크가 저장소 안 디렉터리를 가리키면 그 디렉터리를 증거
# 디렉터리로 받지 않는다.
#######################################
expect_link_in_root_rejected() {
  local case_dir root repo
  case_dir="$(testlib::new_case)" || {
    testlib::fail "link-in-root: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  repo="${case_dir}/in/repo"
  if ! mkdir -p -- "${repo}/.git" "${repo}/evidence" ||
    ! chmod 700 "${repo}/evidence" || ! mkdir -m 700 -- "${root}" ||
    ! ln -s -- "${repo}/evidence" "${root}/alpha-20260101T000000Z"; then
    testlib::fail "link-in-root: 시험 준비 실패"
    return
  fi
  call_evidence "${case_dir}" link evidence::check_dir \
    "${root}/alpha-20260101T000000Z"
  expect_rejected "link-in-root" || return
  if [[ "$(entry_count "${repo}/evidence")" -ne 0 ]]; then
    testlib::fail "link-in-root: 링크 너머(저장소)에 무언가 생김"
    return
  fi
  testlib::ok "link-in-root → 종료 2 · 링크 너머 출력 0"
}

#######################################
# 루트 자체가 링크면 거부한다(링크 너머에 아무것도 만들지 않음).
#######################################
expect_root_link_rejected() {
  local case_dir target
  case_dir="$(testlib::new_case)" || {
    testlib::fail "root-link: 시험 준비 실패"
    return
  }
  target="${case_dir}/in/other"
  if ! mkdir -m 700 -- "${target}" ||
    ! ln -s -- "${target}" "${case_dir}/home/.pokeclip-precheck"; then
    testlib::fail "root-link: 시험 준비 실패"
    return
  fi
  call_evidence "${case_dir}" rootlink evidence::new_dir alpha
  expect_rejected "root-link" || return
  if [[ "$(entry_count "${target}")" -ne 0 ]]; then
    testlib::fail "root-link: 링크 너머에 무언가 생김"
    return
  fi
  testlib::ok "root-link → 종료 2 · 링크 너머 출력 0"
}

#######################################
# 0700 이 아닌 루트 · 증거 디렉터리는 거부한다.
#######################################
expect_mode_rejected() {
  local case_dir root
  case_dir="$(testlib::new_case)" || {
    testlib::fail "mode: 시험 준비 실패"
    return
  }
  root="${case_dir}/home/.pokeclip-precheck"
  mkdir -m 755 -- "${root}" || {
    testlib::fail "mode: 시험 준비 실패"
    return
  }
  call_evidence "${case_dir}" mode evidence::new_dir alpha
  expect_rejected "mode(root)" || return
  if [[ "$(entry_count "${root}")" -ne 0 ]]; then
    testlib::fail "mode: 거부했는데 루트 아래에 무언가 생김"
    return
  fi
  if ! chmod 700 "${root}" ||
    ! mkdir -m 755 -- "${root}/alpha-20260101T000000Z"; then
    testlib::fail "mode: 시험 준비 실패"
    return
  fi
  call_evidence "${case_dir}" child evidence::check_dir \
    "${root}/alpha-20260101T000000Z"
  expect_rejected "mode(child)" || return
  testlib::ok "mode → 0700 이 아닌 루트 · 하위 디렉터리 모두 종료 2"
}

#######################################
# HOME 이 절대 경로가 아니면 루트를 정할 수 없어 거부한다.
#######################################
expect_relative_home_rejected() {
  local case_dir
  case_dir="$(testlib::new_case)" || {
    testlib::fail "relative-home: 시험 준비 실패"
    return
  }
  # shellcheck disable=SC2016 # 작은따옴표 안은 새 bash 가 펼친다.
  (cd -- "${case_dir}" && HOME=home bash -c \
    'source "$1" || exit 99; evidence::new_dir alpha' _ "${EVIDENCE_LIB}" \
    >"${case_dir}/out/rel.stdout" 2>"${case_dir}/out/rel.stderr")
  run_status=$?
  run_out="${case_dir}/out/rel"
  expect_rejected "relative-home" || return
  if [[ "$(entry_count "${case_dir}/home")" -ne 0 ]]; then
    testlib::fail "relative-home: 거부했는데 무언가 생김"
    return
  fi
  testlib::ok "relative-home → 종료 2"
}

main() {
  testlib::init evidence_path_test
  expect_new_dir_ok
  expect_outside_root_rejected
  expect_marker_rejected repo-marker .git dir
  expect_marker_rejected repo-marker-file .git file
  expect_marker_rejected vault-marker .obsidian dir
  expect_link_in_root_rejected
  expect_root_link_rejected
  expect_mode_rejected
  expect_relative_home_rejected
  testlib::finish evidence_path_test
}

main "$@"
