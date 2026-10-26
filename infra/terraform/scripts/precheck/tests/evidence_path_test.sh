#!/usr/bin/env bash
#
# 증거 경로 함수(lib/evidence.sh)를 시험한다 — 계획 M17a c1 사례 1.
#
# 고정 루트는 $HOME/.pokeclip-precheck 이다. 사례마다 임시 디렉터리를 HOME
# 으로 주고, 함수를 새 bash 에서 불러(source) 돌린다. 거부 사례는 종료 2 ·
# 표준 오류 「EVIDENCE PATH REJECTED」 · 출력 파일 0 을 함께 본다. 끝으로
# 점검 도구가 증거 디렉터리 밖에 임시 파일을 만드는 구문을 쓰지 않는지
# 소스에서 본다(원본은 증거 디렉터리에만 — 이 lib 의 계약).
#
# 사용: bash infra/terraform/scripts/precheck/tests/evidence_path_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

# shellcheck source=infra/terraform/scripts/precheck/tests/testlib.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/testlib.sh"

readonly EVIDENCE_LIB="${PRECHECK_DIR}/lib/evidence.sh"
readonly REJECTED='EVIDENCE PATH REJECTED'
# 거부 문구가 HOME 접두를 가리는 표시 글자.
readonly TILDE='~'
# 증거 디렉터리 이름: <evidence::new_dir 인자>-<UTC 시각>[-<2 ~ 9>]. 인자는
# 도구 접두 규약 <도구 접두>-<이름>(예 scp-O7a · collect-O7a)을 따르는데, 이
# 시험은 규약을 강제하지 않는 lib 만 보므로 alpha 를 준다.
readonly DIR_PATTERN='^alpha-[0-9]{8}T[0-9]{6}Z(-[2-9])?$'
# 이 lib 으로 원본을 쓰는 점검 도구(PRECHECK_DIR 기준).
CHECK_TOOLS=(
  scp-recheck.sh events.sh principal-check.sh
  lib/evidence.sh lib/precheck_common.sh
  lib/principal_data.sh lib/principal_simulate.sh
  lib/principal_resolve.sh lib/principal_policy.sh lib/principal_batch.sh
)
readonly CHECK_TOOLS
# 임시 파일을 만드는 구문 — bash 3.2 의 here-string · here-doc(둘 다 임시
# 파일에 내용을 쓴다) · 프로세스 치환 · mktemp.
readonly TEMP_FILE_SYNTAX='<<|<\(|>\(|mktemp'

#######################################
# 증거 함수 하나를 새 bash 에서 돌린다. 결과는 testlib::run 이 run_out ·
# run_status 에 남긴다.
# Globals:
#   EVIDENCE_LIB
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
# 마지막 실행이 거부(종료 2 · 표지 문구)였는지 본다. 거부 문구는 절대 경로를
# 담지 않는다 — HOME 아래는 「~」 로 가리고, HOME 밖(HOME 의
# 조상 · 다른 곳)은 적지 않는다. 절대 경로가 없으면 HOME · 조상 저장소 ·
# 로컬 계정 이름도 없다.
# Globals:
#   run_status, run_out, REJECTED
# Arguments:
#   사례 이름.
# Returns:
#   거부였으면 0(아니면 실패를 센다).
#######################################
expect_rejected() {
  local name="$1" paths
  if [[ "${run_status}" -ne 2 ]]; then
    testlib::fail "${name}: 종료 2 를 기대했으나 ${run_status}"
    return 1
  fi
  if [[ "$(testlib::count "${run_out}.stderr" "${REJECTED}")" -lt 1 ]]; then
    testlib::fail "${name}: 표준 오류에 「${REJECTED}」 없음 —" \
      "$(cat -- "${run_out}.stderr")"
    return 1
  fi
  paths="$(testlib::abs_path_lines "${run_out}.stderr")"
  if [[ -n "${paths}" ]]; then
    testlib::fail "${name}: 거부 문구에 절대 경로 — ${paths}"
    return 1
  fi
}

#######################################
# 정상: 0700 루트 아래 0700 하위 디렉터리를 만들어 돌려준다. 같은 초에 두 번
# 불러도 서로 다른 디렉터리다.
# Globals:
#   run_out, run_status, DIR_PATTERN
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
    testlib::fail "new-dir-ok: 루트 바로 아래 alpha-<UTC 시각> 이 아님 —" \
      "${first}"
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
# 조상에 표지(.git · .obsidian)가 있으면 루트를 만들지 않고 거부한다. HOME 이
# 저장소 · 볼트의 하위인 꼴이라, 거부 문구는 조상의 절대 경로(저장소 위치 ·
# 로컬 계정 이름)를 내지 않고 표지 종류만 적는다. 표지가 대상
# 없는 링크여도 거부한다 — 그런 링크는 있는지(-e) 물으면 없다고 나온다.
# Globals:
#   run_out
# Arguments:
#   사례 이름, 표지 이름, 표지 종류(dir · file · dangling-link).
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
  case "${kind}" in
    dir) mkdir -- "${base}/${marker}" ;;
    file) printf 'gitdir: elsewhere\n' >"${base}/${marker}" ;;
    *) ln -s -- "${base}/nowhere" "${base}/${marker}" ;;
  esac
  # HOME 을 표지 아래로 옮긴다(사례 디렉터리의 home 대신).
  if ! rmdir -- "${case_dir}/home" ||
    ! ln -s -- "${base}/home" "${case_dir}/home"; then
    testlib::fail "${name}: 시험 준비 실패"
    return
  fi
  call_evidence "${case_dir}" marker evidence::new_dir alpha
  expect_rejected "${name}" || return
  if [[ "$(testlib::count "${run_out}.stderr" "HOME 의 조상에")" -lt 1 ||
    "$(testlib::count "${run_out}.stderr" "(${marker})")" -lt 1 ]]; then
    testlib::fail "${name}: 거부 문구에 「HOME 의 조상에」 · 표지 종류" \
      "(${marker})가 없음 — $(cat -- "${run_out}.stderr")"
    return
  fi
  if [[ "$(entry_count "${base}/home")" -ne 0 ]]; then
    testlib::fail "${name}: 거부했는데 HOME 아래에 무언가 생김"
    return
  fi
  testlib::ok "${name} → 종료 2 · 출력 파일 0 · 조상 경로 없이 표지 종류만"
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
# Globals:
#   run_out, TILDE
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
  if [[ "$(testlib::count "${run_out}.stderr" \
    "${TILDE}/.pokeclip-precheck/alpha-20260101T000000Z")" -ne 1 ]]; then
    testlib::fail "mode: 거부 문구에 「~」 로 가린 경로가 없음 —" \
      "$(cat -- "${run_out}.stderr")"
    return
  fi
  testlib::ok "mode → 0700 이 아닌 루트 · 하위 디렉터리 모두 종료 2"
}

#######################################
# 증거 파일 검사(evidence::check_file — 앞 결과를 읽을 때): 검사를 통과한
# 디렉터리 안의 0600 일반 파일만 받고, 링크 · 0644 · 없는 파일 · 디렉터리는
# 거부한다.
# Globals:
#   run_status, run_out
#######################################
expect_check_file() {
  local case_dir dir spec name
  case_dir="$(testlib::new_case)" || {
    testlib::fail "check-file: 시험 준비 실패"
    return
  }
  dir="${case_dir}/home/.pokeclip-precheck/scp-alpha-20260101T000000Z"
  if ! mkdir -m 700 -- "${case_dir}/home/.pokeclip-precheck" ||
    ! mkdir -m 700 -- "${dir}" ||
    ! (umask 077 && printf 'x\n' >"${dir}/ok.tsv" &&
      printf 'x\n' >"${dir}/wide.tsv" && printf 'x\n' >"${dir}/target.tsv") ||
    ! chmod 644 "${dir}/wide.tsv" ||
    ! ln -s -- "${dir}/target.tsv" "${dir}/link.tsv" ||
    ! mkdir -m 700 -- "${dir}/sub.tsv"; then
    testlib::fail "check-file: 시험 준비 실패"
    return
  fi
  call_evidence "${case_dir}" ok evidence::check_file "${dir}/ok.tsv"
  if [[ "${run_status}" -ne 0 ]]; then
    testlib::fail "check-file: 0600 일반 파일을 거부함 — ${run_status}" \
      "$(cat -- "${run_out}.stderr")"
    return
  fi
  for spec in wide.tsv link.tsv missing.tsv sub.tsv; do
    name="check-file(${spec})"
    call_evidence "${case_dir}" "${spec}" evidence::check_file "${dir}/${spec}"
    expect_rejected "${name}" || return
  done
  testlib::ok "check-file → 0600 일반 파일만 · 링크 · 0644 · 없음 · 디렉터리 종료 2"
}

#######################################
# HOME 이 절대 경로가 아니면 루트를 정할 수 없어 거부한다.
# Globals:
#   EVIDENCE_LIB, run_status, run_out
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

#######################################
# 점검 도구는 증거 디렉터리 밖에 임시 파일을 만드는 구문을 쓰지
# 않는다. bash 3.2 의 here-string · here-doc 은 내용을 TMPDIR 의 임시 파일에
# 쓰고, 그 파일을 만들지 못하면 읽기가 조용히 실패해 건수가 0 으로 보일 수
# 있다(거짓 0). 프로세스 치환은 생산자의 실패가 빈 입력으로 보인다.
# Globals:
#   CHECK_TOOLS, TEMP_FILE_SYNTAX, PRECHECK_DIR
#######################################
expect_tools_make_no_temp_files() {
  local tool found status
  for tool in "${CHECK_TOOLS[@]}"; do
    found="$(grep -nE -- "${TEMP_FILE_SYNTAX}" "${PRECHECK_DIR}/${tool}")"
    status=$?
    if [[ "${status}" -gt 1 ]]; then
      testlib::fail "no-temp-files: ${tool} 을 읽지 못함"
      return
    fi
    if [[ -n "${found}" ]]; then
      testlib::fail "no-temp-files: ${tool} 에 임시 파일 구문 — ${found}"
      return
    fi
  done
  testlib::ok "no-temp-files → 점검 도구 셋에 here-string · here-doc ·" \
    "프로세스 치환 · mktemp 0"
}

main() {
  testlib::init evidence_path_test
  expect_new_dir_ok
  expect_outside_root_rejected
  expect_marker_rejected repo-marker .git dir
  expect_marker_rejected repo-marker-file .git file
  expect_marker_rejected vault-marker .obsidian dir
  expect_marker_rejected repo-marker-dangling-link .git dangling-link
  expect_marker_rejected vault-marker-dangling-link .obsidian dangling-link
  expect_link_in_root_rejected
  expect_root_link_rejected
  expect_mode_rejected
  expect_check_file
  expect_relative_home_rejected
  expect_tools_make_no_temp_files
  testlib::finish evidence_path_test
}

main "$@"
