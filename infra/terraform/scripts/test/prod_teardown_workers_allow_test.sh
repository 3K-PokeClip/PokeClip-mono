#!/usr/bin/env bash
#
# runbooks/prod-teardown.md 「0a. prod/workers 먼저 내리기」 의 「이 루트 밖
# 주소 수」 명령(삭제 허용 정규식 — POK-605)을 표본 요지 파일로 돌린다. AWS ·
# terraform 은 쓰지 않는다.
#
# 런북의 명령을 그대로 뽑는다(사본 없음 — 런북 글자를 바꾸면 이 시험이 바뀐
# 글자를 잰다). shell 블록 안에서 줄 머리가 ANCHOR 인 줄과 그 줄이 `\` 로 이어
# 붙인 줄들이 명령이고, 그 줄이 런북에 정확히 하나여야 한다. 사례마다 하위
# 셸에서 evidence 를 사례 디렉터리로 두고 명령을 eval 한다. 요지 파일
# (05-workers-plan.txt)은 plan_summary 의 꼴 「<동작> <주소>」 한 줄씩이다.
#
# 사례(표준 출력 = 이 루트 밖 주소 수 · 표준 오류는 비어야 함 — GNU grep
# 3.8 이후는 정규식의 쓸모없는 `\` 에 「stray \」 경고를 표준 오류로 낸다):
#   정상 — 조절 자원 열 줄(확장 대상 둘 · 정책 넷 · 경보 넷)과 기존 대조 셋
#     (module.render 자원 · clip_from_worker · runtime_contract) = 0.
#   무관 넷 — 하나씩 = 1: module.clip 서비스 · 남의 키(clip)의 경보 · 이름이
#     다른 정책(queue_other) · delete 가 아닌 동작(update)의 확장 대상.
#
# 변이: 새 선택지(조절 자원 둘)를 지운 명령에서 정상 사례가 조절 자원 열 줄을
# 세어(10) 실패해야 한다. 지울 글자가 명령에 정확히 한 번 있어야 한다.
#
# 임시 디렉터리는 한 뿌리($TMPDIR/prod_teardown_workers_allow_test.*) 아래에
# 두고, 끝에 지운 뒤 남지 않았는지 본다. 끝에 통과 건수가 사례와 변이를 더한
# 수(EXPECTED_CASES)와 같은지 본다. 틀은 같은 디렉터리의
# network_vpce_runbook_test 와 같다.
#
# 사용: bash infra/terraform/scripts/test/prod_teardown_workers_allow_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly RUNBOOK="${SCRIPT_DIR}/../../runbooks/prod-teardown.md"

# 명령의 첫 줄 머리(런북 글자 그대로).
readonly ANCHOR="grep -Evc '^delete (module"

# 변이로 지울 글자 — 조절 자원의 두 선택지(런북 글자 그대로).
# shellcheck disable=SC2016
readonly SCALING_ALTERNATIVES='|aws_appautoscaling_target\.worker[[]"(render|upload)"[]]$|aws_(appautoscaling_policy|cloudwatch_metric_alarm)\.queue_(wake|idle)[[]"(render|upload)"[]]$'

# 정상 표본 — 조절 자원 열 줄과 기존 대조 셋.
readonly ALLOWED_LINES=(
  'delete aws_appautoscaling_target.worker["render"]'
  'delete aws_appautoscaling_target.worker["upload"]'
  'delete aws_appautoscaling_policy.queue_wake["render"]'
  'delete aws_appautoscaling_policy.queue_wake["upload"]'
  'delete aws_appautoscaling_policy.queue_idle["render"]'
  'delete aws_appautoscaling_policy.queue_idle["upload"]'
  'delete aws_cloudwatch_metric_alarm.queue_wake["render"]'
  'delete aws_cloudwatch_metric_alarm.queue_wake["upload"]'
  'delete aws_cloudwatch_metric_alarm.queue_idle["render"]'
  'delete aws_cloudwatch_metric_alarm.queue_idle["upload"]'
  'delete module.render.aws_ecs_service.this'
  'delete aws_vpc_security_group_ingress_rule.clip_from_worker["render"]'
  'delete terraform_data.runtime_contract'
)
readonly SCALING_LINE_COUNT=10

# 무관 표본 — 하나씩 세어져야 한다.
readonly FOREIGN_LINES=(
  'delete module.clip.aws_ecs_service.this'
  'delete aws_cloudwatch_metric_alarm.queue_wake["clip"]'
  'delete aws_appautoscaling_policy.queue_other["render"]'
  'update aws_appautoscaling_target.worker["render"]'
)

# 정상 하나 · 무관 넷 · 변이 하나.
readonly EXPECTED_CASES=6

failures=0
passes=0
tmp_root=""
command_text=""

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
# 런북의 shell 블록에서 줄 머리가 ANCHOR 인 줄과 `\` 로 이어진 줄들을 낸다.
# Outputs:
#   명령(줄바꿈 포함).
# Returns:
#   0 / 1 그 줄이 shell 블록 안에 정확히 하나가 아니거나 블록 밖에도 있음.
#######################################
runbook_command() {
  awk -v anchor="${ANCHOR}" '
    index($0, anchor) == 1 { total++ }
    /^```shell$/ { inside = 1; next }
    inside && /^```$/ { inside = 0; cont = 0; next }
    inside && (cont || index($0, anchor) == 1) {
      if (!cont) { found++ }
      out = out $0 "\n"
      cont = /\\$/
    }
    END {
      if (found != 1 || total != 1) { exit 1 }
      printf "%s", out
    }' "${RUNBOOK}"
}

#######################################
# 요지 파일 하나로 명령을 돌린다.
# Arguments:
#   명령, 요지 줄들.
# Outputs:
#   표준 출력 · 표준 오류를 사례 디렉터리의 out · err 에 쓰고, 그 디렉터리를
#   낸다.
#######################################
run_command() {
  local cmd="$1" dir
  shift
  dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" || return 1
  printf '%s\n' "$@" >"${dir}/05-workers-plan.txt"
  (
    # shellcheck disable=SC2034 # eval 한 런북 명령이 읽는다.
    evidence="${dir}"
    eval "${cmd}"
  ) >"${dir}/out" 2>"${dir}/err"
  printf '%s' "${dir}"
}

#######################################
# 출력이 기대 수 한 줄이고 표준 오류가 비었는지 본다.
# Arguments:
#   사례 이름, 사례 디렉터리, 기대 수.
#######################################
expect_count() {
  local name="$1" dir="$2" want="$3" got
  got="$(cat -- "${dir}/out")"
  if [[ -s "${dir}/err" ]]; then
    fail "${name}: 표준 오류가 비지 않음 — $(head -n 1 -- "${dir}/err")"
  elif [[ "${got}" != "${want}" ]]; then
    fail "${name}: 이 루트 밖 주소 수 ${got} ≠ ${want}"
  else
    pass "${name}"
  fi
}

#######################################
# 정상 · 무관 사례를 돌린다.
# Globals:
#   command_text
#######################################
run_cases() {
  local dir line
  dir="$(run_command "${command_text}" "${ALLOWED_LINES[@]}")" || return 1
  expect_count '정상 — 조절 자원 열 줄 · 기존 대조 셋 = 0' "${dir}" 0
  for line in "${FOREIGN_LINES[@]}"; do
    dir="$(run_command "${command_text}" "${line}")" || return 1
    expect_count "무관 — ${line} = 1" "${dir}" 1
  done
}

#######################################
# 조절 자원 선택지를 지운 명령에서 정상 사례가 그 열 줄을 세는지 본다.
# Globals:
#   command_text
#######################################
test_mutation() {
  local name='변이: 조절 자원 선택지 빼기' replaced count mutated dir got
  replaced="${command_text//"${SCALING_ALTERNATIVES}"/}"
  count=$(((${#command_text} - ${#replaced}) / ${#SCALING_ALTERNATIVES}))
  if [[ "${count}" -ne 1 ]]; then
    fail "${name}: 지울 글자가 ${count}번 있음"
    return 0
  fi
  mutated="${command_text%%"${SCALING_ALTERNATIVES}"*}"
  mutated+="${command_text#*"${SCALING_ALTERNATIVES}"}"
  dir="$(run_command "${mutated}" "${ALLOWED_LINES[@]}")" || return 1
  got="$(cat -- "${dir}/out")"
  if [[ "${got}" != "${SCALING_LINE_COUNT}" ]]; then
    fail "${name}: 정상 사례의 수 ${got} ≠ ${SCALING_LINE_COUNT}"
  else
    pass "${name} 잡힘(${got})"
  fi
}

main() {
  tmp_root="$(mktemp -d \
    "${TMPDIR:-/tmp}/prod_teardown_workers_allow_test.XXXXXX")" || exit 1
  trap remove_tmp_root EXIT
  trap 'exit 1' INT TERM

  if ! command_text="$(runbook_command)"; then
    fail "런북에 「${ANCHOR}」 줄이 shell 블록 안에 정확히 하나가 아님"
  else
    run_cases
    test_mutation
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
    printf 'prod_teardown_workers_allow_test: %s건 실패(통과 %s건)\n' \
      "${failures}" "${passes}" >&2
    exit 1
  fi
  printf 'prod_teardown_workers_allow_test: 전부 통과(%s건 · 임시 디렉터리 잔여 0)\n' \
    "${passes}"
}

main "$@"
