#!/usr/bin/env bash
#
# runbooks/prod-artifacts.md 「CI 멈춤 절차」 의 2단계(끝나지 않은 실행 취소) ·
# 3단계(다시 조회) 블록을 가짜 gh 로 bash 와 대화형 zsh 에서 돌린다. gh 는 PATH
# 맨 앞의 가짜(gh_fakebin/gh)다. 진짜 gh 를 부르지 않게 둘을 막는다 — 시작할 때
# 가짜에 실행 비트가 없으면 멈추고(비트가 없으면 PATH 뒤의 진짜가 풀린다),
# 사례마다 블록 앞에서 그 셸의 `command -v gh` 가 가짜 경로가 아니면 블록을
# 돌리지 않고 실패한다. 환경은 env -i 에 사례 디렉터리 HOME 이지만, macOS
# 키체인의 gh 토큰처럼 HOME 밖 자격이 있을 수 있어 그것에는 기대지 않는다.
# jq 는 진짜를 쓴다.
#
# 런북 블록을 그대로 뽑는다(사본 없음). 블록은 그 안의 한 줄(줄 머리가 같아야
# 함)로 찾고, 그 줄을 품은 shell 블록이 런북에 정확히 하나여야 한다. 목록 안
# 블록이라 울타리의 들여쓰기만큼 줄 머리를 벗긴다.
#
# 사례(셸마다 — bash 는 `bash -s`, 대화형 zsh 는 `zsh -f -i -s` 에 표준
# 입력으로 블록과 「echo "rc=$?"」 를 넣는다).
#   취소 — 끝나지 않은 실행 있음(queued 1 · waiting 2) = 종료 0 · 조회 10번
#     (워크플로 둘 × 상태 다섯) · 취소 6번. 없음 = 종료 0 · 취소 0번. 상한
#     (pending 5000 → 1000 개만 옴) = 종료 0 아님 · 「잘렸을 수 있음」 · 취소
#     0번.
#   다시 조회 — in_progress 3 = 「끝나지 않은 실행 6 개」. 없음 = 「… 0 개」.
#     상한(requested 5000) = 종료 0 아님 · 「잘렸을 수 있음」.
#   음성 대조 — 반복 변수를 status 로 되돌린 취소 블록 사본은 bash 에서는
#     돌지만(종료 0) 대화형 zsh 에서는 종료 0 이 아니고 아무것도 취소하지
#     않는다(zsh 의 status 는 $? 의 읽기 전용 별칭). 줄 끝에 「# 0 이어야 함」
#     을 붙인 다시 조회 블록 사본은 대화형 zsh 에서 그 글자가 echo 의 인자가
#     되어 「… 0 개」 줄로 끝나지 않는다(interactivecomments 꺼짐).
#
# zsh 가 없으면(또는 PROD_ARTIFACTS_TEST_ZSH 가 빈 값이면) zsh 사례를 skip 으로
# 센다. infra-ci 는 zsh 를 깔고 skip 을 실패로 본다.
#
# 임시 디렉터리는 한 뿌리($TMPDIR/prod_artifacts_runbook_test.*) 아래에 두고,
# 끝에 지운 뒤 남지 않았는지 본다. 끝에 통과 · 실패 · skip 건수와 총 사례 수
# (EXPECTED_CASES)를 본다.
#
# 사용: bash infra/terraform/scripts/test/prod_artifacts_runbook_test.sh

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly RUNBOOK="${SCRIPT_DIR}/../../runbooks/prod-artifacts.md"
readonly FAKEBIN="${SCRIPT_DIR}/gh_fakebin"
# 블록을 찾는 줄의 머리(앞부분). 런북 글자 그대로다.
readonly CANCEL_ANCHOR='| while read -r run_id; do gh run cancel'
# shellcheck disable=SC2016
readonly RECHECK_ANCHOR='left=$((left + count))'
readonly ZSH_STDIN_FLAGS=(-f -i -s)
# 셸 둘 × 사례 여섯 + 음성 대조 셋(status 사본 bash · zsh, 주석 사본 zsh).
readonly EXPECTED_CASES=15

failures=0
passes=0
skips=0
tmp_root=""
cancel_block=""
recheck_block=""
zsh_bin=""
# run_block 의 결과.
run_rc=""
run_dir=""

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩).
# Globals:
#   tmp_root
#######################################
remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    rm -rf -- "${tmp_root:?}"
  fi
}

#######################################
# 사례 하나의 실패를 알리고 센다.
# Globals:
#   failures
# Arguments:
#   실패 설명.
#######################################
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  failures=$((failures + 1))
}

#######################################
# 사례 하나의 통과를 적고 센다.
# Globals:
#   passes
# Arguments:
#   사례 이름.
#######################################
pass() {
  printf 'ok: %s\n' "$1"
  passes=$((passes + 1))
}

#######################################
# 사례 하나를 건너뛰었다고 적고 센다.
# Globals:
#   skips
# Arguments:
#   사례 이름(건너뛴 이유 포함).
#######################################
skip() {
  printf 'skip: %s\n' "$1"
  skips=$((skips + 1))
}

#######################################
# 런북에서 줄 머리가 같은 줄을 품은 shell 블록 하나의 본문을 낸다. 울타리
# 들여쓰기만큼 줄 머리를 벗긴다.
# Arguments:
#   줄 머리.
# Returns:
#   0 / 1 그 블록이 하나가 아님.
#######################################
runbook_block() {
  awk -v anchor="$1" '
    !inside && /^ *```shell$/ {
      inside = 1; indent = index($0, "`") - 1; body = ""; hit = 0; next
    }
    inside && /^ *```$/ {
      inside = 0
      if (hit) { found++; out = body }
      next
    }
    inside {
      line = substr($0, indent + 1)
      body = body line "\n"
      head = line
      sub(/^ +/, "", head)
      if (index(head, anchor) == 1) { hit = 1 }
    }
    END {
      if (found != 1) { exit 1 }
      printf "%s", out
    }
  ' "${RUNBOOK}"
}

#######################################
# 블록 하나를 셸 하나에서 돌린다. 결과는 run_dir 의 out · err · gh.log 이고
# 종료 코드는 run_rc 다(출력의 마지막 rc= 줄 · 없으면 빈 값). 블록 앞에서 그
# 셸의 gh 가 가짜(FAKEBIN/gh)인지 보고, 아니면 블록을 돌리지 않고 셸을
# 끝낸 뒤 실패로 센다(블록 본문은 그대로).
# Globals:
#   tmp_root, run_rc, run_dir, zsh_bin, FAKEBIN, ZSH_STDIN_FLAGS
# Arguments:
#   셸(bash · zsh), 블록 글자, 가짜 gh 응답(FAKE_GH_RUNS_<상태>=<수>)...
# Returns:
#   0 / 1 gh 가 가짜가 아님.
#######################################
run_block() {
  local shell_kind="$1" block="$2"
  shift 2
  local shell_cmd=()
  run_dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" || return 1
  mkdir -p -- "${run_dir}/home" "${run_dir}/tmp"
  # shellcheck disable=SC2016
  printf '%s\n' '[ "$(command -v gh)" = "${FAKE_GH_EXPECTED}" ] || exit 97' \
    'echo fake-gh-ok' >"${run_dir}/stdin"
  printf '%s\necho "rc=$?"\n' "${block}" >>"${run_dir}/stdin"
  : >"${run_dir}/gh.log"
  case "${shell_kind}" in
    bash) shell_cmd=(bash -s) ;;
    zsh) shell_cmd=("${zsh_bin}" "${ZSH_STDIN_FLAGS[@]}") ;;
  esac
  env -i "PATH=${FAKEBIN}:${PATH}" "HOME=${run_dir}/home" \
    "TMPDIR=${run_dir}/tmp" "LANG=${LANG:-C.UTF-8}" "TERM=dumb" \
    "FAKE_GH_LOG=${run_dir}/gh.log" "FAKE_GH_EXPECTED=${FAKEBIN}/gh" "$@" \
    "${shell_cmd[@]}" <"${run_dir}/stdin" >"${run_dir}/out" \
    2>"${run_dir}/err"
  if ! grep -aq 'fake-gh-ok$' "${run_dir}/out"; then
    run_rc=""
    fail "${shell_kind}: 블록 셸의 gh 가 가짜(${FAKEBIN}/gh)가 아님 —" \
      "블록을 돌리지 않음"
    return 1
  fi
  run_rc="$(grep -aoE 'rc=[0-9]+$' "${run_dir}/out" | tail -n 1)"
  run_rc="${run_rc#rc=}"
}

#######################################
# 가짜 gh 기록에서 한 종류의 호출 수를 낸다.
# Globals:
#   run_dir
# Arguments:
#   종류(list · cancel).
#######################################
gh_calls() {
  grep -c "^$1 " "${run_dir}/gh.log"
}

#######################################
# 최근 실행의 출력 · 오류를 한 줄로 낸다(실패 설명용).
# Globals:
#   run_dir
#######################################
run_output() {
  LC_ALL=C tr '\n' '|' <"${run_dir}/out"
  printf ' / '
  LC_ALL=C tr '\n' '|' <"${run_dir}/err"
}

#######################################
# 셸 하나에서 취소 블록 세 사례를 돌린다.
# Arguments:
#   셸(bash · zsh).
#######################################
test_cancel() {
  local shell_kind="$1" name
  name="${shell_kind}: 취소 — 끝나지 않은 실행 있음"
  run_block "${shell_kind}" "${cancel_block}" FAKE_GH_RUNS_queued=1 \
    FAKE_GH_RUNS_waiting=2 || return 1
  if [[ "${run_rc}" == 0 && "$(gh_calls list)" == 10 \
    && "$(gh_calls cancel)" == 6 ]]; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} 조회 $(gh_calls list) 취소 $(gh_calls cancel)" \
      "— $(run_output)"
  fi

  name="${shell_kind}: 취소 — 없음"
  run_block "${shell_kind}" "${cancel_block}" || return 1
  if [[ "${run_rc}" == 0 && "$(gh_calls list)" == 10 \
    && "$(gh_calls cancel)" == 0 ]]; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} 조회 $(gh_calls list) 취소 $(gh_calls cancel)" \
      "— $(run_output)"
  fi

  name="${shell_kind}: 취소 — 1000 상한이면 멈춤"
  run_block "${shell_kind}" "${cancel_block}" FAKE_GH_RUNS_pending=5000 ||
    return 1
  if [[ -n "${run_rc}" && "${run_rc}" != 0 && "$(gh_calls cancel)" == 0 ]] &&
    grep -aq '잘렸을 수 있음' "${run_dir}/out"; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} 취소 $(gh_calls cancel) — $(run_output)"
  fi
}

#######################################
# 셸 하나에서 다시 조회 블록 세 사례를 돌린다.
# Arguments:
#   셸(bash · zsh).
#######################################
test_recheck() {
  local shell_kind="$1" name
  name="${shell_kind}: 다시 조회 — in_progress 3"
  run_block "${shell_kind}" "${recheck_block}" FAKE_GH_RUNS_in_progress=3 ||
    return 1
  if [[ "${run_rc}" == 0 ]] &&
    grep -aqE '끝나지 않은 실행 6 개$' "${run_dir}/out"; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} — $(run_output)"
  fi

  name="${shell_kind}: 다시 조회 — 없음"
  run_block "${shell_kind}" "${recheck_block}" || return 1
  if [[ "${run_rc}" == 0 ]] &&
    grep -aqE '끝나지 않은 실행 0 개$' "${run_dir}/out"; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} — $(run_output)"
  fi

  name="${shell_kind}: 다시 조회 — 1000 상한이면 멈춤"
  run_block "${shell_kind}" "${recheck_block}" FAKE_GH_RUNS_requested=5000 ||
    return 1
  if [[ -n "${run_rc}" && "${run_rc}" != 0 ]] &&
    grep -aq '잘렸을 수 있음' "${run_dir}/out"; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} — $(run_output)"
  fi
}

#######################################
# 음성 대조 — status 이름 사본(bash 는 돌고 zsh 는 실패) · 줄 끝 주석
# 사본(zsh 에서 기대 줄로 끝나지 않음).
# Globals:
#   cancel_block, recheck_block, zsh_bin, run_rc, run_dir
#######################################
test_negative_controls() {
  local status_copy comment_copy name
  status_copy="${cancel_block//run_status/status}"
  comment_copy="$(printf '%s' "${recheck_block}" | sed -E \
    's/^( *echo "끝나지 않은 실행 \$\{left\} 개")$/\1   # 0 이어야 함/')"
  if [[ "${status_copy}" == "${cancel_block}" ]] ||
    [[ "${comment_copy}" == "${recheck_block}" ]]; then
    fail '음성 대조: 사본을 만들지 못함(바꿀 글자가 블록에 없음)'
    return 1
  fi

  name='bash: 음성 대조 — status 이름 사본은 bash 에서 돔(종료 0)'
  run_block bash "${status_copy}" FAKE_GH_RUNS_queued=1 || return 1
  if [[ "${run_rc}" == 0 && "$(gh_calls cancel)" == 2 ]]; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} 취소 $(gh_calls cancel) — $(run_output)"
  fi

  if [[ -z "${zsh_bin}" ]]; then
    skip 'zsh: 음성 대조 — status 이름 사본(zsh 없음)'
    skip 'zsh: 음성 대조 — 줄 끝 주석 사본(zsh 없음)'
    return 0
  fi
  name='zsh: 음성 대조 — status 이름 사본은 실패 · 취소 0번'
  run_block zsh "${status_copy}" FAKE_GH_RUNS_queued=1 || return 1
  if [[ -n "${run_rc}" && "${run_rc}" != 0 \
    && "$(gh_calls cancel)" == 0 ]]; then
    pass "${name}"
  else
    fail "${name}: rc=${run_rc} 취소 $(gh_calls cancel) — $(run_output)"
  fi

  name='zsh: 음성 대조 — 줄 끝 주석 사본은 기대 줄로 끝나지 않음'
  run_block zsh "${comment_copy}" || return 1
  if [[ -n "${run_rc}" ]] &&
    ! grep -aqE '끝나지 않은 실행 0 개$' "${run_dir}/out"; then
    pass "${name}"
  else
    fail "${name}: 주석이 막히지 않음 — $(run_output)"
  fi
}

main() {
  local total root_pattern
  local template="${TMPDIR:-/tmp}/prod_artifacts_runbook_test.XXXXXX"
  # 실행 비트가 없으면 PATH 의 가짜 gh 가 풀리지 않고 뒤의 진짜 gh 가 풀린다 —
  # 시작하지 않는다.
  if [[ ! -x "${FAKEBIN}/gh" ]]; then
    printf '%s\n' 'prod_artifacts_runbook_test: 가짜 gh 에 실행 비트가 없음' \
      "  ${FAKEBIN}/gh" >&2
    exit 1
  fi
  tmp_root="$(mktemp -d "${template}")" || exit 1
  trap remove_tmp_root EXIT
  root_pattern="${tmp_root}"
  if ! cancel_block="$(runbook_block "${CANCEL_ANCHOR}")" ||
    ! recheck_block="$(runbook_block "${RECHECK_ANCHOR}")"; then
    fail '런북에서 2 · 3단계 블록을 하나씩 찾지 못함'
    exit 1
  fi
  zsh_bin="$(command -v "${PROD_ARTIFACTS_TEST_ZSH-zsh}" 2>/dev/null)" ||
    zsh_bin=""

  test_cancel bash
  test_recheck bash
  if [[ -n "${zsh_bin}" ]]; then
    test_cancel zsh
    test_recheck zsh
  else
    local i
    for ((i = 0; i < 6; i++)); do
      skip "zsh: 취소 · 다시 조회 사례 $((i + 1))(zsh 없음)"
    done
  fi
  test_negative_controls

  remove_tmp_root
  trap - EXIT
  if [[ -e "${root_pattern}" ]]; then
    fail "임시 디렉터리가 남음: ${root_pattern}"
  fi
  total=$((passes + failures + skips))
  printf '통과 %d · 실패 %d · skip %d · 사례 %d(기대 %d)\n' "${passes}" \
    "${failures}" "${skips}" "${total}" "${EXPECTED_CASES}"
  if [[ "${total}" -ne "${EXPECTED_CASES}" || "${failures}" -ne 0 ]]; then
    exit 1
  fi
}

main "$@"
