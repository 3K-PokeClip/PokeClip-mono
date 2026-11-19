#!/usr/bin/env bash
#
# 일꾼 자동 조절(POK-605)의 조절 정지를 쓰는 런북 블록을 가짜 aws 로 시험한다.
# AWS 에 접속하지 않고 terraform 도 쓰지 않는다.
#
# 대상은 둘이다 — runbooks/runtime-1a.md 의 토큰 회전 「일꾼 멈춤」(auto 갈래의
# 조절 정지 실패 보존 · 값 넣기 전 세 값 다시 읽기)과 prod/workers README 의
# 쓰기 직전 재읽기(관문 ② 0대 갈래의 깨우기 정지 · 「기동 확인」 1 · 2).
# 블록을 문서 글자 그대로 뽑는다(사본 없음). 블록은 그 안의 한 줄(줄 머리 ·
# 들여쓰기를 뺀 꼴)로 찾고, 그 줄을 품은 shell 블록이 문서에 정확히 하나여야
# 한다. 사례마다 뽑은 블록을 이어 붙인 스크립트를 bash 와 zsh(-f)로 돌린다.
# aws 는 셸 함수로 덮는다 — 받은 인자와 AWS_MAX_ATTEMPTS 를 사례의 calls 에
# 적고, 응답은 사례의 resp/「<서비스>.<동작>」 에서 고른다. 같은 열쇠의 n 번째
# 호출은 「<열쇠>.<n>」 이 있으면 그것을 받는다. 「<열쇠>.fail」 이 있으면
# 종료 254, 응답이 없으면 종료 255 다. sleep 은 아무것도 하지 않는다.
#
# 사례(셸마다):
#   회전 — 조절 정지 실패(254)면 원하는 수 0 은 내리고 멈춤 줄 · 확인도 회부 ·
#     조절 정지는 AWS_MAX_ATTEMPTS=1 · 정상(세 값 True)이면 값 넣기로 · 성공
#     응답 뒤 세 값 False · 세 값 조회 실패 · None 이면 회부.
#   깨우기 정지 — 0 0 False False False 만 쓴다(깨우기만 true) · 0 0 False
#     True False 는 이미 걸림 · 0 0 True True True 는 기존 정지 · 원하는 수 1
#     은 롤링 · 0 1(내려가는 중) · None · 서비스 조회 실패 · 쓰기 실패는 멈춤.
#   기동 확인 — 1 의 기록과 2 직전 재읽기가 같고 False False False 또는 False
#     True False 일 때만 쓴다(재우기 · 깨우기 true) · 그사이 긴급 정지 · 기록이
#     기존 정지 · 값이 바뀜은 쓰지 않음.
#   블록 글자 — 뽑은 블록에 `#` 주석 줄이 없음(대화형 zsh 는
#     interactivecomments 가 꺼져 있음 — 런북의 같은 규칙).
#
# 변이(bash 만): 조절 정지 실패 분기 · 세 값 다시 읽기 호출 · 깨우기 정지의
# 기대값 · 기동 확인 2 의 같음 조건을 하나씩 지운 블록에서 해당 사례가 실패해야
# 한다. 지울 글자가 블록에 정확히 한 번 있어야 한다.
#
# 임시 디렉터리는 한 뿌리($TMPDIR/workers_suspend_runbook_test.*) 아래에 두고,
# 끝에 지운 뒤 남지 않았는지 본다. 통과 건수가 EXPECTED_CASES 와 같은지 본다.
# zsh 가 없으면 실패로 센다(CI 의 shell 잡은 zsh 를 깖).
#
# 사용: bash infra/terraform/scripts/test/workers_suspend_runbook_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly RUNBOOK="${SCRIPT_DIR}/../../runbooks/runtime-1a.md"
readonly README="${SCRIPT_DIR}/../../prod/workers/README.md"

# 블록을 찾는 줄(들여쓰기를 뺀 줄 머리 — 문서 글자 그대로).
readonly ANCHOR_AUTO="printf '%s %s auto\\n'"
readonly ANCHOR_FAMILY='family_tasks() {'
readonly ANCHOR_CHECK='wait_zero() {'
# shellcheck disable=SC2016 # 문서의 글자 그대로다.
readonly ANCHOR_WAKE='case "${seen}" in'
# shellcheck disable=SC2016 # 문서의 글자 그대로다.
readonly ANCHOR_START1='before="$(aws application-autoscaling'
# shellcheck disable=SC2016 # 문서의 글자 그대로다.
readonly ANCHOR_START2='now="$(aws application-autoscaling'

readonly SHELLS=(bash zsh)
# 조절 정지 세 값 인자 — 깨우기만 멈춤(0대 갈래) · 재우기와 깨우기(기동 확인 2).
readonly WAKE_ONLY='DynamicScalingInSuspended=false,'\
'DynamicScalingOutSuspended=true,ScheduledScalingSuspended=false'
readonly SLEEP_AND_WAKE='DynamicScalingInSuspended=true,'\
'DynamicScalingOutSuspended=true,ScheduledScalingSuspended=false'
readonly REGISTER='application-autoscaling register-scalable-target'
readonly CASES=(
  rotate_fail rotate_ok rotate_false rotate_read_fail rotate_none
  wake_set wake_already wake_stopped wake_rolling wake_draining wake_none
  wake_read_fail wake_write_fail
  start_fff start_ftf start_stopped_between start_stopped start_changed
)
# 변이는 이름 · 지울 글자 · 넣을 글자 · 블록 · 사례를 같은 차례의 배열 다섯에
# 둔다.
readonly MUTATION_NAMES=(
  '회전: 조절 정지 실패 분기 빼기'
  '회전: 세 값 다시 읽기 빼기'
  '깨우기 정지: 기대값 대신 아무 값'
  '기동 확인 2: 1 의 기록과 같음 조건 빼기'
)
# shellcheck disable=SC2016 # 문서의 글자 그대로다.
readonly MUTATION_FROM=(
  '[ "${suspend_rc}" = 0 ] ||'
  'check_suspended "${k}"; }'
  "'0 0 False False False')"
  '[ "${now}" = "${before}" ] &&'
)
readonly MUTATION_TO=(
  'true ||'
  'true; }'
  '*)'
  ''
)
readonly MUTATION_BLOCK=(auto check wake start2)
readonly MUTATION_CASE=(rotate_fail rotate_false wake_stopped start_changed)

# 셸마다 사례 · 블록 글자 하나 · 변이.
readonly EXPECTED_CASES=$((${#CASES[@]} * ${#SHELLS[@]} + 1 +
  ${#MUTATION_NAMES[@]}))

failures=0
passes=0
tmp_root=""
block_auto=""
block_family=""
block_check=""
block_wake=""
block_start1=""
block_start2=""

#######################################
# 임시 디렉터리 뿌리를 지운다(EXIT 트랩).
# Globals:
#   tmp_root
#######################################
remove_tmp_root() {
  if [[ -n "${tmp_root}" ]]; then
    rm -rf -- "${tmp_root}"
  fi
}

#######################################
# 실패를 알리고 센다.
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
# 통과를 적고 센다.
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
# 문서에서 줄 머리가 anchor 인 줄을 품은 shell 블록을 들여쓰기를 빼고 낸다.
# Arguments:
#   문서 경로, anchor.
# Outputs:
#   블록 본문.
# Returns:
#   0 / 1 그 블록이 정확히 하나가 아님.
#######################################
md_block() {
  # -v 는 백슬래시를 풀므로(\n → 줄바꿈) 글자 그대로 넘기려고 환경을 쓴다.
  BLOCK_ANCHOR="$2" awk '
    BEGIN { anchor = ENVIRON["BLOCK_ANCHOR"] }
    function flush() {
      if (hit) { found++; out = body }
      body = ""; hit = 0
    }
    !inside && /^ *```shell$/ {
      inside = 1; indent = index($0, "`") - 1; body = ""; hit = 0; next
    }
    inside && /^ *```$/ { inside = 0; flush(); next }
    inside {
      line = substr($0, 1, indent) ~ /^ *$/ ? substr($0, indent + 1) : $0
      if (index(line, anchor) == 1) { hit = 1 }
      body = body line "\n"
    }
    END {
      if (found != 1) { exit 1 }
      printf "%s", out
    }' "$1"
}

#######################################
# 가짜 aws 를 사례 뿌리에 쓴다(bash · zsh 공통).
# Globals:
#   tmp_root
#######################################
write_fake() {
  cat >"${tmp_root}/fake.sh" <<'EOF'
aws() {
  fake_key="$1.$2"
  printf '%s|max=%s\n' "$*" "${AWS_MAX_ATTEMPTS-}" >>"${FAKE_DIR}/calls"
  fake_n=0
  if [ -f "${FAKE_DIR}/${fake_key}.n" ]; then
    fake_n="$(cat "${FAKE_DIR}/${fake_key}.n")"
  fi
  fake_n=$((fake_n + 1))
  printf '%s\n' "${fake_n}" >"${FAKE_DIR}/${fake_key}.n"
  fake_resp="${FAKE_DIR}/resp/${fake_key}"
  if [ -f "${fake_resp}.fail" ]; then
    printf 'fake: %s 실패\n' "${fake_key}" >&2
    return 254
  fi
  if [ -f "${fake_resp}.${fake_n}" ]; then
    fake_resp="${fake_resp}.${fake_n}"
  fi
  if [ ! -f "${fake_resp}" ]; then
    printf 'fake: 응답 없음 %s\n' "${fake_key}" >&2
    return 255
  fi
  cat "${fake_resp}"
}
sleep() { :; }
EOF
}

#######################################
# 사례 디렉터리를 만들고 응답 파일을 쓴다.
# Arguments:
#   사례 이름.
# Outputs:
#   사례 디렉터리.
#######################################
setup_case() {
  local dir
  dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" || return 1
  mkdir -p "${dir}/resp"
  : >"${dir}/calls"
  local r="${dir}/resp"
  case "$1" in
    rotate_*)
      printf 'render ACTIVE 0 False False False\n' >"${dir}/workers-before.txt"
      : >"${dir}/workers-tasks-render.txt"
      : >"${r}/ecs.list-tasks"
      printf 'pokeclip-prod-render\n' >"${r}/ecs.update-service"
      printf '0\t0\t0\n' >"${r}/ecs.describe-services"
      : >"${r}/application-autoscaling.register-scalable-target"
      ;;
    wake_*)
      : >"${r}/application-autoscaling.register-scalable-target"
      printf '0\t0\n' >"${r}/ecs.describe-services"
      ;;
    start_*)
      : >"${r}/application-autoscaling.register-scalable-target"
      printf '0\t0\t0\n' >"${r}/ecs.describe-services"
      ;;
  esac
  local s="${r}/application-autoscaling.describe-scalable-targets"
  case "$1" in
    rotate_fail)
      touch "${r}/application-autoscaling.register-scalable-target.fail"
      printf 'False\tFalse\tFalse\n' >"${s}"
      ;;
    rotate_ok) printf 'True\tTrue\tTrue\n' >"${s}" ;;
    rotate_false) printf 'False\tFalse\tFalse\n' >"${s}" ;;
    rotate_read_fail) touch "${s}.fail" ;;
    rotate_none) printf 'None\n' >"${s}" ;;
    wake_set) printf 'False\tFalse\tFalse\n' >"${s}" ;;
    wake_already) printf 'False\tTrue\tFalse\n' >"${s}" ;;
    wake_stopped) printf 'True\tTrue\tTrue\n' >"${s}" ;;
    wake_rolling)
      printf '1\t1\n' >"${r}/ecs.describe-services"
      printf 'False\tFalse\tFalse\n' >"${s}"
      ;;
    wake_draining)
      printf '0\t1\n' >"${r}/ecs.describe-services"
      printf 'False\tFalse\tFalse\n' >"${s}"
      ;;
    wake_none) printf 'None\n' >"${s}" ;;
    wake_read_fail)
      touch "${r}/ecs.describe-services.fail"
      printf 'False\tFalse\tFalse\n' >"${s}"
      ;;
    wake_write_fail)
      touch "${r}/application-autoscaling.register-scalable-target.fail"
      printf 'False\tFalse\tFalse\n' >"${s}"
      ;;
    start_fff) printf 'False\tFalse\tFalse\n' >"${s}" ;;
    start_ftf) printf 'False\tTrue\tFalse\n' >"${s}" ;;
    start_stopped_between)
      printf 'False\tFalse\tFalse\n' >"${s}.1"
      printf 'True\tTrue\tTrue\n' >"${s}.2"
      ;;
    start_stopped) printf 'True\tTrue\tTrue\n' >"${s}" ;;
    start_changed)
      printf 'False\tFalse\tFalse\n' >"${s}.1"
      printf 'False\tTrue\tFalse\n' >"${s}.2"
      ;;
  esac
  printf '%s' "${dir}"
}

#######################################
# 사례의 블록들을 이어 붙인 스크립트를 셸 하나로 돌린다.
# Arguments:
#   셸(bash · zsh), 사례 디렉터리, 블록 본문들.
#######################################
run_script() {
  local shell="$1" dir="$2"
  shift 2
  {
    printf 'FAKE_DIR=%q\nwork=%q\n' "${dir}" "${dir}"
    printf '. %q\n' "${tmp_root}/fake.sh"
    printf '%s\n' "$@"
  } >"${dir}/run.sh"
  if [[ "${shell}" == zsh ]]; then
    zsh -f "${dir}/run.sh" >"${dir}/out" 2>"${dir}/err" </dev/null
  else
    bash "${dir}/run.sh" >"${dir}/out" 2>"${dir}/err" </dev/null
  fi
}

#######################################
# calls 에서 동작의 호출 수를 낸다.
# Arguments:
#   사례 디렉터리, 동작(「<서비스> <동작>」).
#######################################
count_calls() {
  grep -c -- "^$2 " "$1/calls"
}

#######################################
# 사례 결과를 판정한다. 어긋나면 사유를 내고 1.
# Arguments:
#   사례 이름, 사례 디렉터리.
#######################################
judge_case() {
  local name="$1" dir="$2" out last reg
  out="$(cat -- "${dir}/out")"
  last="$(tail -n 1 -- "${dir}/out")"
  reg="$(count_calls "${dir}" "${REGISTER}")"
  case "${name}" in
    rotate_fail)
      [[ "${out}" == *'멈춤: render 의 조절 정지 실패(종료 254)'* ]] ||
        { echo '조절 정지 실패 줄 없음'; return 1; }
      [[ "$(count_calls "${dir}" 'ecs update-service')" == 1 ]] ||
        { echo '원하는 수 0 을 내리지 않음'; return 1; }
      grep -q "^${REGISTER} .*|max=1\$" "${dir}/calls" ||
        { echo '조절 정지에 AWS_MAX_ATTEMPTS=1 없음'; return 1; }
      [[ "${last}" == '멈춤: 일꾼 멈춤 확인 실패 — kty 회부' ]] ||
        { echo "확인이 회부가 아님: ${last}"; return 1; }
      ;;
    rotate_ok)
      [[ "${out}" != *'조절 정지 실패'* ]] || { echo '실패 줄이 나옴'; return 1; }
      [[ "${last}" == '일꾼 멈춤 확인 — 값 넣기로' ]] ||
        { echo "통과가 아님: ${last}"; return 1; }
      ;;
    rotate_false | rotate_read_fail | rotate_none)
      [[ "${last}" == '멈춤: 일꾼 멈춤 확인 실패 — kty 회부' ]] ||
        { echo "회부가 아님: ${last}"; return 1; }
      ;;
    wake_set)
      [[ "${reg}" == 1 ]] || { echo "쓰기 ${reg}번"; return 1; }
      grep -qF -- "${WAKE_ONLY}" "${dir}/calls" ||
        { echo '깨우기만 true 가 아님'; return 1; }
      [[ "${last}" == *'깨우기 정지 걸림'* ]] || { echo "줄: ${last}"; return 1; }
      ;;
    wake_already | wake_stopped | wake_rolling)
      [[ "${reg}" == 0 ]] || { echo "쓰기 ${reg}번"; return 1; }
      local want='깨우기 정지 걸려 있음'
      [[ "${name}" == wake_stopped ]] && want='기존 정지'
      [[ "${name}" == wake_rolling ]] && want='롤링'
      [[ "${last}" == *"${want}"* && "${last}" != 멈춤* ]] ||
        { echo "줄: ${last}"; return 1; }
      ;;
    wake_draining | wake_none | wake_read_fail)
      [[ "${reg}" == 0 ]] || { echo "쓰기 ${reg}번"; return 1; }
      [[ "${last}" == 멈춤:* ]] || { echo "멈춤이 아님: ${last}"; return 1; }
      ;;
    wake_write_fail)
      [[ "${last}" == 멈춤:* ]] || { echo "멈춤이 아님: ${last}"; return 1; }
      ;;
    start_fff | start_ftf)
      [[ "${reg}" == 1 ]] || { echo "쓰기 ${reg}번"; return 1; }
      grep -qF -- "${SLEEP_AND_WAKE}" "${dir}/calls" ||
        { echo '재우기 · 깨우기 true 가 아님'; return 1; }
      [[ "${out}" != *멈춤* ]] || { echo '멈춤 줄이 나옴'; return 1; }
      ;;
    start_stopped_between | start_stopped | start_changed)
      [[ "${reg}" == 0 ]] || { echo "쓰기 ${reg}번"; return 1; }
      [[ "${last}" == 멈춤:* ]] || { echo "멈춤이 아님: ${last}"; return 1; }
      ;;
  esac
}

#######################################
# 사례의 블록 목록을 낸다(변이 블록이 있으면 그것으로 바꿈).
# Globals:
#   block_*
# Arguments:
#   사례 이름, 바꿀 블록 이름(없으면 빈 값), 바꾼 본문.
#######################################
case_blocks() {
  local auto="${block_auto}" check="${block_check}" wake="${block_wake}"
  local start2="${block_start2}"
  case "$2" in
    auto) auto="$3" ;;
    check) check="$3" ;;
    wake) wake="$3" ;;
    start2) start2="$3" ;;
  esac
  case "$1" in
    rotate_*) printf '%s\0' "${block_family}" "${auto}" "${check}" ;;
    wake_*) printf '%s\0' "${wake}" ;;
    start_*) printf '%s\0' "${block_start1}" "${start2}" ;;
  esac
}

#######################################
# 사례 하나를 셸 하나로 돌려 판정한다.
# Arguments:
#   셸, 사례 이름, 바꿀 블록 이름, 바꾼 본문.
# Outputs:
#   어긋남 사유(통과면 없음).
# Returns:
#   0 통과 / 1 어긋남.
#######################################
try_case() {
  local shell="$1" name="$2" dir blocks=()
  dir="$(setup_case "${name}")" || return 1
  while IFS= read -r -d '' b; do
    blocks+=("${b}")
  done < <(case_blocks "${name}" "${3-}" "${4-}")
  run_script "${shell}" "${dir}" "${blocks[@]}"
  judge_case "${name}" "${dir}"
}

#######################################
# 셸마다 모든 사례를 돌린다.
#######################################
run_cases() {
  local shell name why
  for shell in "${SHELLS[@]}"; do
    if ! command -v "${shell}" >/dev/null 2>&1; then
      fail "${shell} 이 없음 — 사례 ${#CASES[@]}개를 돌리지 못함"
      continue
    fi
    for name in "${CASES[@]}"; do
      if why="$(try_case "${shell}" "${name}")"; then
        pass "${shell}: ${name}"
      else
        fail "${shell}: ${name} — ${why}"
      fi
    done
  done
}

#######################################
# 뽑은 블록에 `#` 주석 줄이 없는지 본다.
#######################################
test_no_comments() {
  local all
  all="$(printf '%s\n' "${block_auto}" "${block_family}" "${block_check}" \
    "${block_wake}" "${block_start1}" "${block_start2}")"
  if grep -qE '^[[:space:]]*#' <<<"${all}"; then
    fail "블록에 # 주석 줄이 있음: $(grep -E '^[[:space:]]*#' <<<"${all}" | head -n 1)"
  else
    pass '블록 글자: # 주석 줄 없음'
  fi
}

#######################################
# 변이마다 블록 글자를 바꿔 해당 사례가 실패하는지 본다.
#######################################
test_mutations() {
  local i name from body replaced count mutated
  for i in "${!MUTATION_NAMES[@]}"; do
    name="${MUTATION_NAMES[i]}"
    from="${MUTATION_FROM[i]}"
    case "${MUTATION_BLOCK[i]}" in
      auto) body="${block_auto}" ;;
      check) body="${block_check}" ;;
      wake) body="${block_wake}" ;;
      start2) body="${block_start2}" ;;
    esac
    replaced="${body//"${from}"/}"
    count=$(((${#body} - ${#replaced}) / ${#from}))
    if [[ "${count}" -ne 1 ]]; then
      fail "변이 「${name}」: 지울 글자가 ${count}번 있음"
      continue
    fi
    mutated="${body%%"${from}"*}${MUTATION_TO[i]}${body#*"${from}"}"
    if try_case bash "${MUTATION_CASE[i]}" "${MUTATION_BLOCK[i]}" \
      "${mutated}" >/dev/null; then
      fail "변이 「${name}」: ${MUTATION_CASE[i]} 가 여전히 통과"
    else
      pass "변이 「${name}」 잡힘"
    fi
  done
}

#######################################
# 문서에서 블록 여섯을 뽑는다.
# Globals:
#   block_*
# Returns:
#   0 / 1 하나라도 정확히 하나가 아님.
#######################################
load_blocks() {
  local ok=0
  block_auto="$(md_block "${RUNBOOK}" "${ANCHOR_AUTO}")" || {
    fail "런북에 「${ANCHOR_AUTO}」 블록이 하나가 아님"
    ok=1
  }
  block_auto="${block_auto//<키>/render}"
  block_family="$(md_block "${RUNBOOK}" "${ANCHOR_FAMILY}")" || {
    fail "런북에 「${ANCHOR_FAMILY}」 블록이 하나가 아님"
    ok=1
  }
  block_check="$(md_block "${RUNBOOK}" "${ANCHOR_CHECK}")" || {
    fail "런북에 「${ANCHOR_CHECK}」 블록이 하나가 아님"
    ok=1
  }
  block_wake="$(md_block "${README}" "${ANCHOR_WAKE}")" || {
    fail "README 에 「${ANCHOR_WAKE}」 블록이 하나가 아님"
    ok=1
  }
  block_start1="$(md_block "${README}" "${ANCHOR_START1}")" || {
    fail "README 에 「${ANCHOR_START1}」 블록이 하나가 아님"
    ok=1
  }
  block_start2="$(md_block "${README}" "${ANCHOR_START2}")" || {
    fail "README 에 「${ANCHOR_START2}」 블록이 하나가 아님"
    ok=1
  }
  return "${ok}"
}

main() {
  tmp_root="$(mktemp -d \
    "${TMPDIR:-/tmp}/workers_suspend_runbook_test.XXXXXX")" || exit 1
  trap remove_tmp_root EXIT
  trap 'exit 1' INT TERM

  if load_blocks; then
    write_fake
    run_cases
    test_no_comments
    test_mutations
  fi

  remove_tmp_root
  if [[ -e "${tmp_root}" ]]; then
    fail "임시 디렉터리가 남음: ${tmp_root}"
  fi
  tmp_root=""
  if [[ "${passes}" -ne "${EXPECTED_CASES}" ]]; then
    fail "사례 수가 다름 — 통과 ${passes} ≠ ${EXPECTED_CASES}"
  fi
  if [[ "${failures}" -ne 0 ]]; then
    printf 'workers_suspend_runbook_test: %s건 실패(통과 %s건)\n' \
      "${failures}" "${passes}" >&2
    exit 1
  fi
  printf 'workers_suspend_runbook_test: 전부 통과(%s건 · 임시 디렉터리 잔여 0)\n' \
    "${passes}"
}

main "$@"
