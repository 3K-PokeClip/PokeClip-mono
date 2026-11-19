#!/usr/bin/env bash
#
# 일꾼 자동 조절(POK-605)의 조절 정지 · 원하는 수를 쓰는 런북 블록을 가짜 aws
# 로 시험한다. AWS 에 접속하지 않고 terraform 도 쓰지 않는다.
#
# 대상은 이 PR 이 쓰거나 고친 쓰기 블록 전부다. prod/workers README 의 「일꾼
# 쓰기 함수」(worker_now · worker_write · worker_stop · worker_wake_stop 등)와
# 그것을 부르는 걸음 블록(긴급 정지 · 다시 띄우기 세 갈래 · 자동 조절 끄기 ·
# 관문 ② 0대 갈래의 깨우기 정지와 되돌림 둘 · 「기동 확인」 1 · 2 · 3 · 6 ·
# 직접 맞추기), runbooks/runtime-1a.md 의 토큰 회전 「일꾼 멈춤」(auto ·
# pinned) · 확인 · 「일꾼 복원」(auto · pinned) · 1단-다 clip 올리기다.
# 블록을 문서 글자 그대로 뽑는다(사본 없음). 블록은 그 안의 한 줄(줄 머리 ·
# 들여쓰기를 뺀 꼴)로 찾고, 그 줄을 품은 shell 블록이 문서에 정확히 하나여야
# 한다. 런북의 `<키>` 는 render 로 바꾼다.
#
# 사례마다 뽑은 블록을 이어 붙인 스크립트를 bash 와 zsh(-f)로 돌린다. 스크립트
# 는 `env -i` 로 띄워 호출자의 환경(AWS_PROFILE 등)을 들이지 않고, AWS 설정 ·
# 자격 파일은 /dev/null · 인스턴스 메타데이터 끔 · 가짜 자격이다. PATH 맨 앞에
# 늘 실패하는 aws(부르면 표시 파일을 남기고 종료 99)를 둔다. 스크립트 첫머리는
# 가짜 aws(셸 함수)를 `. fake.sh || exit 97` 로 읽고, aws 가 함수가 아니면
# exit 97 이다 — 가짜를 읽지 못해도 진짜 aws 에 닿지 않는다(격리 사례 · 변이).
# 가짜 aws 는 받은 인자와 AWS_MAX_ATTEMPTS 를 calls 에 적고, 응답은
# resp/「<서비스>.<동작>」 에서 고른다. 같은 열쇠의 n 번째 호출은 「<열쇠>.<n>」
# 이 있으면 그것을 받는다. 「<열쇠>.fail」 이 있으면 종료 254, 응답이 없으면
# 종료 255 다. sleep 은 아무것도 하지 않는다.
#
# 사례는 CASE_TABLE 의 한 줄이다 — 이름 · 원하는 수 응답 · 세 값 응답 · 실패할
# 동작 · 기대 종료 코드 · 조절 정지 쓰기 수 · 원하는 수 쓰기 수 · 조절 정지
# 쓰기의 세 값 · 출력에 있어야 할 글자. 이름의 앞 조각(첫 `_` 앞)이 이어 붙일
# 블록을 정한다(group_blocks). 사례마다 보는 것:
#   - 종료 코드가 기대값(쓰기 실패면 그 쓰기의 종료 코드 254 그대로)이고,
#     종료 코드가 0 이 아닌 것과 `멈춤:` 줄이 있는 것이 같음.
#   - 쓰기 호출 수가 정확함 — 기대값이 아니면(그사이 긴급 정지 · 조회 실패)
#     쓰지 않고, 앞 쓰기가 실패하면 뒤 쓰기를 부르지 않음(정지 쪽의 원하는 수
#     0 정리 쓰기만 예외).
#   - 쓴 세 값 · 출력 글자 · `command not found` 없음.
# 회전 사례는 두 쓰기에 AWS_MAX_ATTEMPTS=1 · 멈춤 시각 기록(쓴 때만)도 본다.
#
# 변이(bash 만): 블록 글자 하나를 바꿔(조건 빼기 · 종료 코드 무시 · 확인 빼기)
# 지정한 사례가 실패해야 한다. 지울 글자가 블록에 정확히 한 번 있어야 한다.
# 격리 변이는 가드(`|| exit 97` · 함수 확인)를 뺀 스크립트를 가짜 없이 돌려
# PATH 앞의 실패 aws 가 받는지(진짜 aws 가 아님) 본다.
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

# 블록 이름 · 문서 · 찾는 줄을 같은 차례의 배열 셋에 둔다(문서 글자 그대로).
readonly BLOCK_NAMES=(
  defs stop wake start1 start2 start3 start6 unwake unwoke adjust resume
  repin reold pin unpin family auto pinned check restorea restorep clipup
)
readonly BLOCK_DOCS=(
  readme readme readme readme readme readme readme readme readme readme
  readme readme readme readme readme runbook runbook runbook runbook runbook
  runbook runbook
)
# shellcheck disable=SC2016 # 문서의 글자 그대로다.
readonly BLOCK_ANCHORS=(
  'worker_now() {'
  'worker_stop "${k}"'
  'worker_wake_stop "${k}"'
  'before="$(worker_now "${k}")" || before='"'조회 실패'"
  'case "${before}" in'
  "worker_write \"\${k}\" '0 0 True True False' - 1"
  "worker_write \"\${k}\" '1 1 True True False' 'False False False' -"
  "worker_write \"\${k}\" '0 0 False True False' 'False False False' -"
  "worker_write \"\${k}\" '* * False True False' 'False False False' -"
  "worker_write \"\${k}\" '* * False False False' - \"\${to}\""
  "worker_write \"\${k}\" '0 0 True True True' 'False False False' -"
  "worker_write \"\${k}\" '0 0 True True True' - 1"
  "worker_write \"\${k}\" '0 0 None' - 1"
  "worker_write \"\${k}\" '* * False False False' 'True True True' 1"
  "worker_write \"\${k}\" '1 1 True True True' 'False False False' -"
  'family_tasks() {'
  'rotation_stop_auto() {'
  'rotation_stop_pinned() {'
  'wait_zero() {'
  "worker_write \"<키>\" '0 0 True True True' 'False False False' -"
  "worker_write \"<키>\" '0 0 True True True' - 1"
  'clip_up() {'
)

readonly SHELLS=(bash zsh)
readonly REGISTER='application-autoscaling register-scalable-target'
readonly UPDATE='ecs update-service'

# 사례 표: 이름|원하는 수 응답|세 값 응답|실패할 동작|종료 코드|조절 정지
# 쓰기 수|원하는 수 쓰기 수|조절 정지 쓰기의 세 값|출력에 있어야 할 글자.
# 응답은 n 번째 호출마다 `;` 로 나누고 마지막 것이 그 뒤에도 쓰인다. 원하는 수
# 응답은 숫자를 칸마다(11 = 1 1), 세 값 응답은 T · F 셋 또는 None(N)이다.
# 실패할 동작: ecs(describe-services) · aas(describe-scalable-targets) ·
# reg(register) · upd(update-service). `-` 는 없음 · 보지 않음이다.
readonly CASE_TABLE=(
  'stop_ok|11;00|FFF;TTT|-|0|1|1|TTT|확인'
  'stop_stopped|00|TTT|-|0|1|1|TTT|확인'
  'stop_regfail|11;00|FFF|reg|254|1|1|TTT|(종료 254 · 걸렸을 수 있음)'
  'stop_updfail|11|FFF|upd|254|1|1|TTT|원하는 수 0 실패(종료 254)'
  'stop_readfail|11|FFF|ecs|1|0|0|-|쓰지 않음'
  'stop_none|11;00|N|-|1|0|1|-|확장 대상 없음'
  'stop_noneupdfail|11|N|upd|254|0|1|-|(종료 254)'
  'wake_set|00|FFF;FFF;FTF|-|0|1|0|FTF|확인'
  'wake_already|00|FTF|-|0|0|0|-|걸려 있음'
  'wake_stopped|00|TTT|-|0|0|0|-|기존 정지'
  'wake_rolling|11|FFF|-|0|0|0|-|롤링'
  'wake_draining|01|FFF|-|1|0|0|-|멈춤'
  'wake_none|00|N|-|1|0|0|-|멈춤'
  'wake_readfail|00|FFF|ecs|1|0|0|-|멈춤'
  'wake_between|00|FFF;TTT|-|1|0|0|-|쓰지 않음'
  'wake_writefail|00|FFF|reg|254|1|0|FTF|(종료 254)'
  'wake_unconfirmed|00|FFF|-|1|1|0|FTF|쓴 뒤'
  'start2_fff|00|FFF;FFF;TTF|-|0|1|0|TTF|확인'
  'start2_ftf|00|FTF;FTF;TTF|-|0|1|0|TTF|확인'
  'start2_between|00|FFF;TTT|-|1|0|0|-|쓰지 않음'
  'start2_stopped|00|TTT|-|1|0|0|-|기동 확인 대상 아님'
  'start2_changed|00|FFF;FTF|-|1|0|0|-|쓰지 않음'
  'start2_writefail|00|FFF|reg|254|1|0|TTF|(종료 254)'
  'chain_writefail|00|FFF|reg|1|1|0|TTF|(종료 254)'
  'start3_ok|00;10|TTF|-|0|0|1|-|확인'
  'start3_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'start3_unsuspended|00|FFF|-|1|0|0|-|쓰지 않음'
  'start3_writefail|00|TTF|upd|254|0|1|-|(종료 254)'
  'start3_readfail|00|TTF|aas|1|0|0|-|쓰지 않음'
  'start6_ok|11|TTF;FFF|-|0|1|0|FFF|확인'
  'start6_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'start6_writefail|11|TTF|reg|254|1|0|FFF|(종료 254)'
  'start6_readfail|11|TTF|ecs|1|0|0|-|쓰지 않음'
  'unwake_ok|00|FTF;FFF|-|0|1|0|FFF|확인'
  'unwake_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'unwake_writefail|00|FTF|reg|254|1|0|FFF|(종료 254)'
  'unwoke_up|11|FTF;FFF|-|0|1|0|FFF|확인'
  'unwoke_down|00|FTF;FFF|-|0|1|0|FFF|확인'
  'unwoke_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'unwoke_writefail|11|FTF|reg|254|1|0|FFF|(종료 254)'
  'adjust_ok|11;00|FFF|-|0|0|1|-|확인'
  'adjust_between|11|TTT|-|1|0|0|-|쓰지 않음'
  'adjust_writefail|11|FFF|upd|254|0|1|-|(종료 254)'
  'adjust_unconfirmed|11|FFF|-|1|0|1|-|쓴 뒤'
  'resume_ok|00|TTT;FFF|-|0|1|0|FFF|확인'
  'resume_changed|00|FFF|-|1|0|0|-|쓰지 않음'
  'resume_writefail|00|TTT|reg|254|1|0|FFF|(종료 254)'
  'repin_ok|00;10|TTT|-|0|0|1|-|확인'
  'repin_changed|11|TTT|-|1|0|0|-|쓰지 않음'
  'repin_writefail|00|TTT|upd|254|0|1|-|(종료 254)'
  'reold_ok|00;10|N|-|0|0|1|-|확인'
  'reold_changed|00|FFF|-|1|0|0|-|쓰지 않음'
  'reold_writefail|00|N|upd|254|0|1|-|(종료 254)'
  'pin_ok|11|FFF;TTT|-|0|1|1|TTT|확인'
  'pin_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'pin_regfail|11|FFF|reg|254|1|0|TTT|(종료 254)'
  'pin_updfail|11|FFF;TTT|upd|254|1|1|TTT|(종료 254)'
  'unpin_ok|11|TTT;FFF|-|0|1|0|FFF|확인'
  'unpin_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'unpin_writefail|11|TTT|reg|254|1|0|FFF|(종료 254)'
  'rotate_ok|11;00;000|FFF;TTT;TTT|-|-|1|1|TTT|값 넣기로'
  'rotate_regfail|11;000|FFF;FFF|reg|-|1|1|TTT|(종료 254 · 걸렸을 수 있음)'
  'rotate_updfail|11;000|FFF;TTT|upd|-|1|1|TTT|원하는 수 0 실패(종료 254)'
  'rotate_between|00;000|TTT|-|-|0|0|-|쓰지 않음'
  'rotate_false|11;00;000|FFF;TTT;FFF|-|-|1|1|TTT|회부'
  'rotate_readfail|11;000|FFF|aas|-|0|0|-|회부'
  'rotate_none|11;00;000|FFF;TTT;N|-|-|1|1|TTT|회부'
  'rotpin_ok|11;00|TTT|-|0|0|1|-|확인'
  'rotpin_between|00|TTT|-|1|0|0|-|쓰지 않음'
  'rotpin_writefail|11|TTT|upd|254|0|1|-|(종료 254)'
  'restorea_ok|00|TTT;FFF|-|0|1|0|FFF|확인'
  'restorea_between|00|FFF|-|1|0|0|-|쓰지 않음'
  'restorea_writefail|00|TTT|reg|254|1|0|FFF|(종료 254)'
  'restorep_ok|00;10|TTT|-|0|0|1|-|확인'
  'restorep_between|00|FFF|-|1|0|0|-|쓰지 않음'
  'restorep_writefail|00|TTT|upd|254|0|1|-|(종료 254)'
  'clipup_ok|00;1|-|-|0|0|1|-|확인'
  'clipup_changed|11|-|-|1|0|0|-|쓰지 않음'
  'clipup_writefail|00|-|upd|254|0|1|-|(종료 254)'
  'clipup_readfail|00|-|ecs|1|0|0|-|쓰지 않음'
  'clipup_unconfirmed|00;0|-|-|1|0|1|-|쓴 뒤'
)

# 변이는 이름 · 지울 글자 · 넣을 글자 · 블록 · 사례를 같은 차례의 배열 다섯에
# 둔다.
readonly MUTATION_NAMES=(
  'worker_write: 기대값 조건 빼기'
  'worker_write: 조절 정지 쓰기의 종료 코드 무시'
  'worker_write: 원하는 수 쓰기의 종료 코드 무시'
  'worker_write: 쓴 뒤 확인 빼기'
  'worker_stop: 조절 정지 종료 코드 무시'
  'worker_stop: 대상 없음 갈래 빼기'
  '기동 확인 2: 기록 갈래 조건 빼기'
  '기동 확인 2: 1 의 기록과 같음 조건 빼기'
  '회전 auto: 기대값 조건 빼기'
  '회전 auto: 조절 정지 종료 코드 무시'
  '회전 auto: 원하는 수 쓰기의 AWS_MAX_ATTEMPTS=1 빼기'
  '회전 확인: 세 값 다시 읽기 빼기'
  '회전 pinned: 기대값 조건 빼기'
  'clip 올리기: 0 0 조건 빼기'
  'clip 올리기: 쓰기 종료 코드 무시'
)
# shellcheck disable=SC2016 # 문서의 글자 그대로다.
readonly MUTATION_FROM=(
  'if ! worker_match "${seen}" "${want}"; then'
  'worker_set_suspended "${k}" "${flags}"'
  'worker_set_desired "${k}" "${count}"'
  'worker_confirm "${k}" "${count} * ${flags}"'
  'flags_rc=$?'
  "if worker_match \"\${seen}\" '* * None'; then"
  "'0 0 False False False' | '0 0 False True False')"
  'worker_write "${k}" "${before}"'
  "if ! worker_match \"\${seen}\" '* * False False False'; then"
  'suspend_rc=$?'
  'AWS_MAX_ATTEMPTS=1 aws ecs update-service'
  'check_suspended "${k}"; }'
  "if ! worker_match \"\${seen}\" '1 * True True True'; then"
  "if [ \"\${now}\" != '0 0' ]; then"
  'up_rc=$?'
)
# shellcheck disable=SC2016 # 넣을 글자도 블록 글자다.
readonly MUTATION_TO=(
  'if false; then'
  'worker_set_suspended "${k}" "${flags}" || true'
  'worker_set_desired "${k}" "${count}" || true'
  'true'
  'flags_rc=0'
  'if false; then'
  '*)'
  "worker_write \"\${k}\" '0 0 False True False'"
  'if false; then'
  'suspend_rc=0'
  'aws ecs update-service'
  'true; }'
  'if false; then'
  'if false; then'
  'up_rc=0'
)
readonly MUTATION_BLOCK=(
  defs defs defs defs defs defs start2 start2 auto auto auto check pinned
  clipup clipup
)
readonly MUTATION_CASE=(
  start6_between pin_regfail adjust_writefail adjust_unconfirmed stop_regfail
  stop_none start2_stopped start2_changed rotate_between rotate_regfail
  rotate_ok rotate_false rotpin_between clipup_changed clipup_writefail
)

# 셸마다 사례 · 셸마다 격리 사례 둘 · 키 검사 하나 · 블록 글자 하나 · 변이 ·
# 격리 변이 하나.
readonly EXPECTED_CASES=$(((${#CASE_TABLE[@]} + 3) * ${#SHELLS[@]} + 1 +
  ${#MUTATION_NAMES[@]} + 1))

failures=0
passes=0
tmp_root=""

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
# 가짜 aws 와 PATH 앞의 실패 aws 를 뿌리에 쓴다.
# Globals:
#   tmp_root
#######################################
write_fakes() {
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
  : >"${tmp_root}/empty-fake.sh"
  mkdir -p "${tmp_root}/failbin"
  # 실패 aws 의 글자다 — 그 aws 가 돌 때 풀린다.
  # shellcheck disable=SC2016
  {
    printf '#!/bin/sh\n'
    printf 'printf "config=%%s key=%%s\\n" "${AWS_CONFIG_FILE-}" '
    printf '"${AWS_ACCESS_KEY_ID-}" >>%q\n' "${tmp_root}/real-aws-reached"
    printf 'exit 99\n'
  } >"${tmp_root}/failbin/aws"
  chmod +x "${tmp_root}/failbin/aws"
}

#######################################
# 원하는 수 응답 부호(11)를 텍스트 출력(1<탭>1)으로 푼다.
# Arguments:
#   부호.
#######################################
decode_counts() {
  local code="$1" out="" i
  for ((i = 0; i < ${#code}; i++)); do
    out+="${code:i:1}"$'\t'
  done
  printf '%s\n' "${out%$'\t'}"
}

#######################################
# 세 값 응답 부호(FTF · N)를 텍스트 출력으로 푼다.
# Arguments:
#   부호.
#######################################
decode_flags() {
  local code="$1" out="" i
  if [[ "${code}" == N ]]; then
    printf 'None\n'
    return 0
  fi
  for ((i = 0; i < ${#code}; i++)); do
    if [[ "${code:i:1}" == T ]]; then
      out+=$'True\t'
    else
      out+=$'False\t'
    fi
  done
  printf '%s\n' "${out%$'\t'}"
}

#######################################
# 세 값 부호(FTF)를 register-scalable-target 의 --suspended-state 값으로 낸다.
# Arguments:
#   부호.
#######################################
state_arg() {
  local code="$1" names=(DynamicScalingInSuspended DynamicScalingOutSuspended
    ScheduledScalingSuspended) out="" i value
  for i in 0 1 2; do
    value=false
    [[ "${code:i:1}" == T ]] && value=true
    out+="${names[i]}=${value},"
  done
  printf '%s\n' "${out%,}"
}

#######################################
# 응답 차례를 파일로 쓴다 — n 번째는 <파일>.<n>, 마지막은 <파일>에도.
# Arguments:
#   응답 파일, 차례(`;` 로 나눔 · `-` 는 없음), 푸는 함수.
#######################################
write_seq() {
  local file="$1" spec="$2" decode="$3" parts i
  [[ "${spec}" == - ]] && return 0
  IFS=';' read -r -a parts <<<"${spec}"
  for i in "${!parts[@]}"; do
    "${decode}" "${parts[i]}" >"${file}.$((i + 1))"
  done
  "${decode}" "${parts[${#parts[@]} - 1]}" >"${file}"
}

#######################################
# 사례 디렉터리를 만들고 응답 파일을 쓴다.
# Arguments:
#   사례 이름, 원하는 수 응답, 세 값 응답, 실패할 동작.
# Outputs:
#   사례 디렉터리.
#######################################
setup_case() {
  local dir r op
  dir="$(mktemp -d "${tmp_root}/case.XXXXXX")" || return 1
  r="${dir}/resp"
  mkdir -p "${r}"
  : >"${dir}/calls"
  write_seq "${r}/ecs.describe-services" "$2" decode_counts
  write_seq "${r}/application-autoscaling.describe-scalable-targets" "$3" \
    decode_flags
  printf 'pokeclip-prod-render\n' >"${r}/ecs.update-service"
  : >"${r}/application-autoscaling.register-scalable-target"
  : >"${r}/ecs.list-tasks"
  for op in ${4//,/ }; do
    case "${op}" in
      ecs) touch "${r}/ecs.describe-services.fail" ;;
      aas)
        touch "${r}/application-autoscaling.describe-scalable-targets.fail"
        ;;
      reg) touch "${r}/application-autoscaling.register-scalable-target.fail" ;;
      upd) touch "${r}/ecs.update-service.fail" ;;
    esac
  done
  if [[ "$1" == rotate_* ]]; then
    printf 'render ACTIVE 1 False False False\n' >"${dir}/workers-before.txt"
    : >"${dir}/workers-tasks-render.txt"
  fi
  printf '%s' "${dir}"
}

#######################################
# 블록 파일들을 이어 붙인 스크립트를 env -i 로 셸 하나에서 돌린다.
# Globals:
#   tmp_root
# Arguments:
#   셸, 사례 디렉터리, 가짜 파일, 가드(on · off), 블록 파일들.
# Returns:
#   스크립트의 종료 코드.
#######################################
run_script() {
  local shell="$1" dir="$2" fake="$3" guard="$4"
  shift 4
  {
    printf 'FAKE_DIR=%q\nwork=%q\nk=render\n' "${dir}" "${dir}"
    if [[ "${guard}" == on ]]; then
      printf '. %q || exit 97\n' "${fake}"
      # shellcheck disable=SC2016 # 스크립트의 글자다.
      printf '%s\n' 'case "$(type aws 2>&1)" in' \
        "  'aws is a '*function*) ;;" '  *) exit 97 ;;' 'esac'
    else
      printf '. %q\n' "${fake}"
    fi
    cat -- "$@"
  } >"${dir}/run.sh"
  local env_cmd=(env -i "PATH=${tmp_root}/failbin:${PATH}" "HOME=${dir}"
    "TMPDIR=${dir}" "LANG=${LANG:-C.UTF-8}" AWS_CONFIG_FILE=/dev/null
    AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_EC2_METADATA_DISABLED=true
    AWS_ACCESS_KEY_ID=testing AWS_SECRET_ACCESS_KEY=testing)
  if [[ "${shell}" == zsh ]]; then
    "${env_cmd[@]}" zsh -f "${dir}/run.sh" >"${dir}/out" 2>"${dir}/err" \
      </dev/null
  else
    "${env_cmd[@]}" bash "${dir}/run.sh" >"${dir}/out" 2>"${dir}/err" \
      </dev/null
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
# 사례 이름의 앞 조각이 정하는 블록 이름들을 낸다.
# Arguments:
#   사례 이름.
#######################################
group_blocks() {
  case "${1%%_*}" in
    stop) echo 'defs stop' ;;
    wake) echo 'defs wake' ;;
    start2) echo 'defs start1 start2' ;;
    chain) echo 'defs start1 start2 start3' ;;
    start3) echo 'defs start3' ;;
    start6) echo 'defs start6' ;;
    unwake) echo 'defs unwake' ;;
    unwoke) echo 'defs unwoke' ;;
    adjust) echo 'defs adjust' ;;
    resume) echo 'defs resume' ;;
    repin) echo 'defs repin' ;;
    reold) echo 'defs reold' ;;
    pin) echo 'defs pin' ;;
    unpin) echo 'defs unpin' ;;
    rotate) echo 'defs family auto check' ;;
    rotpin) echo 'defs family pinned' ;;
    restorea) echo 'defs restorea' ;;
    restorep) echo 'defs restorep' ;;
    clipup) echo 'clipup' ;;
  esac
}

#######################################
# 회전 사례의 덧붙은 판정(재시도 끔 · 멈춤 시각 · 확인 줄). 어긋나면 사유를
# 내고 1.
# Arguments:
#   사례 이름, 사례 디렉터리, 출력의 마지막 줄.
#######################################
judge_rotation() {
  local name="$1" dir="$2" last="$3" stopped="$2/workers-stopped-at.txt"
  case "${name}" in
    rotate_ok | rotate_regfail | rotate_updfail)
      grep -q "^${REGISTER} .*|max=1\$" "${dir}/calls" ||
        { echo '조절 정지에 AWS_MAX_ATTEMPTS=1 없음'; return 1; }
      grep -q "^${UPDATE} .*|max=1\$" "${dir}/calls" ||
        { echo '원하는 수 0 에 AWS_MAX_ATTEMPTS=1 없음'; return 1; }
      grep -q '^render [0-9TZ:-]* auto$' "${stopped}" ||
        { echo '멈춤 시각 기록(auto) 없음'; return 1; }
      ;;
    rotpin_ok)
      grep -q "^${UPDATE} .*|max=1\$" "${dir}/calls" ||
        { echo '원하는 수 0 에 AWS_MAX_ATTEMPTS=1 없음'; return 1; }
      grep -q '^render [0-9TZ:-]* pinned$' "${stopped}" ||
        { echo '멈춤 시각 기록(pinned) 없음'; return 1; }
      ;;
    rotate_between | rotpin_between)
      [[ ! -e "${stopped}" ]] || { echo '쓰지 않았는데 멈춤 시각을 남김'; return 1; }
      ;;
  esac
  case "${name}" in
    rotate_ok)
      [[ "${last}" == '일꾼 멈춤 확인 — 값 넣기로' ]] ||
        { echo "통과가 아님: ${last}"; return 1; }
      ;;
    rotate_regfail | rotate_false | rotate_readfail | rotate_none)
      [[ "${last}" == '멈춤: 일꾼 멈춤 확인 실패 — kty 회부' ]] ||
        { echo "확인이 회부가 아님: ${last}"; return 1; }
      ;;
  esac
}

#######################################
# 사례 결과를 판정한다. 어긋나면 사유를 내고 1.
# Arguments:
#   사례 표 줄, 사례 디렉터리, 종료 코드.
#######################################
judge_case() {
  local name ecs aas fails want_rc want_reg want_upd state text
  IFS='|' read -r name ecs aas fails want_rc want_reg want_upd state text \
    <<<"$1"
  local dir="$2" rc="$3" out reg upd
  out="$(cat -- "${dir}/out")"
  reg="$(count_calls "${dir}" "${REGISTER}")"
  upd="$(count_calls "${dir}" "${UPDATE}")"
  if grep -q 'command not found\|not found:' "${dir}/err"; then
    echo "없는 명령: $(head -n 1 "${dir}/err")"
    return 1
  fi
  if [[ "${want_rc}" != - ]]; then
    [[ "${rc}" == "${want_rc}" ]] ||
      { echo "종료 ${rc} ≠ ${want_rc}"; return 1; }
    if { [[ "${rc}" == 0 ]] && [[ "${out}" == *'멈춤:'* ]]; } ||
      { [[ "${rc}" != 0 ]] && [[ "${out}" != *'멈춤:'* ]]; }; then
      echo "종료 ${rc} 와 멈춤 줄이 어긋남"
      return 1
    fi
  fi
  [[ "${reg}" == "${want_reg}" ]] ||
    { echo "조절 정지 쓰기 ${reg}번 ≠ ${want_reg}"; return 1; }
  [[ "${upd}" == "${want_upd}" ]] ||
    { echo "원하는 수 쓰기 ${upd}번 ≠ ${want_upd}"; return 1; }
  if [[ "${state}" != - ]]; then
    grep -qF -- "--suspended-state $(state_arg "${state}")" "${dir}/calls" ||
      { echo "세 값 ${state} 를 쓰지 않음"; return 1; }
  fi
  [[ "${out}" == *"${text}"* ]] || { echo "「${text}」 없음"; return 1; }
  judge_rotation "${name}" "${dir}" "$(tail -n 1 -- "${dir}/out")"
}

#######################################
# 사례 하나를 셸 하나로 돌려 판정한다.
# Globals:
#   tmp_root
# Arguments:
#   셸, 사례 표 줄, 블록 디렉터리.
# Outputs:
#   어긋남 사유(통과면 없음).
# Returns:
#   0 통과 / 1 어긋남.
#######################################
try_case() {
  local shell="$1" row="$2" blockdir="$3" name ecs aas fails dir rc b
  local files=()
  IFS='|' read -r name ecs aas fails _ <<<"${row}"
  dir="$(setup_case "${name}" "${ecs}" "${aas}" "${fails}")" || return 1
  for b in $(group_blocks "${name}"); do
    files+=("${blockdir}/${b}")
  done
  run_script "${shell}" "${dir}" "${tmp_root}/fake.sh" on "${files[@]}"
  rc=$?
  judge_case "${row}" "${dir}" "${rc}"
}

#######################################
# 셸마다 모든 사례를 돌린다.
# Globals:
#   tmp_root
#######################################
run_cases() {
  local shell row why
  for shell in "${SHELLS[@]}"; do
    if ! command -v "${shell}" >/dev/null 2>&1; then
      fail "${shell} 이 없음 — 사례 $((${#CASE_TABLE[@]} + 3))개를 돌리지 못함"
      continue
    fi
    for row in "${CASE_TABLE[@]}"; do
      if why="$(try_case "${shell}" "${row}" "${tmp_root}/blocks")"; then
        pass "${shell}: ${row%%|*}"
      else
        fail "${shell}: ${row%%|*} — ${why}"
      fi
    done
    test_isolation "${shell}"
    test_key_guard "${shell}"
  done
}

#######################################
# 키 검사를 본다 — 일꾼이 아닌 키(clip)로 긴급 정지를 부르면 aws 호출 0 ·
# 종료 ≠ 0 · 표준 오류에 키 멈춤 줄이다.
# Globals:
#   tmp_root
# Arguments:
#   셸.
#######################################
test_key_guard() {
  local shell="$1" dir rc
  dir="$(setup_case keyguard 11 N -)" || return 1
  printf '%s\n' 'worker_stop clip' >"${dir}/clip-stop"
  run_script "${shell}" "${dir}" "${tmp_root}/fake.sh" on \
    "${tmp_root}/blocks/defs" "${dir}/clip-stop"
  rc=$?
  if [[ "${rc}" != 0 && ! -s "${dir}/calls" ]] &&
    grep -q '일꾼 키는 render · upload 만' "${dir}/err"; then
    pass "${shell}: 키 검사 — clip 은 aws 호출 0 · 멈춤"
  else
    fail "${shell}: 키 검사 — 종료 ${rc} · 가짜 호출 $(wc -l \
      <"${dir}/calls" | tr -d ' ')"
  fi
}

#######################################
# 격리를 본다 — 가짜를 읽지 못하거나 aws 를 정의하지 않은 가짜면 블록 전에
# exit 97 이고, 가짜 aws · PATH 앞의 실패 aws 어느 쪽도 불리지 않는다.
# Globals:
#   tmp_root
# Arguments:
#   셸.
#######################################
test_isolation() {
  local shell="$1" fake dir rc label
  for fake in "${tmp_root}/no-such-fake.sh" "${tmp_root}/empty-fake.sh"; do
    label='가짜를 읽지 못하면'
    [[ "${fake}" == *empty* ]] && label='가짜에 aws 가 없으면'
    dir="$(setup_case iso 11 FFF -)" || return 1
    run_script "${shell}" "${dir}" "${fake}" on "${tmp_root}/blocks/defs" \
      "${tmp_root}/blocks/stop"
    rc=$?
    if [[ "${rc}" == 97 && ! -s "${dir}/calls" &&
      ! -e "${tmp_root}/real-aws-reached" ]]; then
      pass "${shell}: 격리 — ${label} exit 97 · aws 호출 0"
    else
      fail "${shell}: 격리 — ${label}: 종료 ${rc} · 가짜 호출 $(wc -l \
        <"${dir}/calls" | tr -d ' ') · 실패 aws $([[ -e \
        "${tmp_root}/real-aws-reached" ]] && echo 불림 || echo 안 불림)"
    fi
  done
}

#######################################
# 격리 변이 — 가드를 뺀 스크립트를 가짜 없이 돌리면 PATH 앞의 실패 aws 가
# 격리 환경(설정 /dev/null · 가짜 자격)으로 받는지 본다.
# Globals:
#   tmp_root
#######################################
test_isolation_mutation() {
  local dir reached="${tmp_root}/real-aws-reached"
  dir="$(setup_case iso 11 FFF -)" || return 1
  run_script bash "${dir}" "${tmp_root}/no-such-fake.sh" off \
    "${tmp_root}/blocks/defs" "${tmp_root}/blocks/stop"
  if grep -qx 'config=/dev/null key=testing' "${reached}" 2>/dev/null; then
    pass '격리 변이 「가드 빼기」: PATH 앞의 실패 aws 가 받음(진짜 aws 아님)'
  else
    fail '격리 변이 「가드 빼기」: PATH 앞의 실패 aws 가 격리 환경으로 불리지 않음'
  fi
  rm -f -- "${reached}"
}

#######################################
# 뽑은 블록에 `#` 주석 줄이 없는지 본다.
# Globals:
#   tmp_root
#######################################
test_no_comments() {
  local hit
  hit="$(cat -- "${tmp_root}"/blocks/* | grep -E '^[[:space:]]*#' | head -n 1)"
  if [[ -n "${hit}" ]]; then
    fail "블록에 # 주석 줄이 있음: ${hit}"
  else
    pass '블록 글자: # 주석 줄 없음'
  fi
}

#######################################
# 사례 이름으로 사례 표 줄을 낸다.
# Arguments:
#   사례 이름.
#######################################
case_row() {
  local row
  for row in "${CASE_TABLE[@]}"; do
    if [[ "${row%%|*}" == "$1" ]]; then
      printf '%s\n' "${row}"
      return 0
    fi
  done
  return 1
}

#######################################
# 변이마다 블록 글자를 바꿔 해당 사례가 실패하는지 본다.
# Globals:
#   tmp_root
#######################################
test_mutations() {
  local i name from body replaced count mutdir row why
  for i in "${!MUTATION_NAMES[@]}"; do
    name="${MUTATION_NAMES[i]}"
    from="${MUTATION_FROM[i]}"
    body="$(cat -- "${tmp_root}/blocks/${MUTATION_BLOCK[i]}")"
    replaced="${body//"${from}"/}"
    count=$(((${#body} - ${#replaced}) / ${#from}))
    if [[ "${count}" -ne 1 ]]; then
      fail "변이 「${name}」: 지울 글자가 ${count}번 있음"
      continue
    fi
    mutdir="${tmp_root}/mut.${i}"
    mkdir -p "${mutdir}"
    cp -- "${tmp_root}"/blocks/* "${mutdir}/"
    printf '%s\n' "${body%%"${from}"*}${MUTATION_TO[i]}${body#*"${from}"}" \
      >"${mutdir}/${MUTATION_BLOCK[i]}"
    row="$(case_row "${MUTATION_CASE[i]}")" ||
      { fail "변이 「${name}」: 사례 ${MUTATION_CASE[i]} 없음"; continue; }
    if why="$(try_case bash "${row}" "${mutdir}")"; then
      fail "변이 「${name}」: ${MUTATION_CASE[i]} 가 여전히 통과"
    else
      pass "변이 「${name}」 잡힘 — ${MUTATION_CASE[i]}: ${why}"
    fi
  done
}

#######################################
# 문서에서 블록을 뽑아 blocks/<이름> 에 쓴다.
# Globals:
#   tmp_root
# Returns:
#   0 / 1 하나라도 정확히 하나가 아님.
#######################################
load_blocks() {
  local ok=0 i doc body
  mkdir -p "${tmp_root}/blocks"
  for i in "${!BLOCK_NAMES[@]}"; do
    doc="${README}"
    [[ "${BLOCK_DOCS[i]}" == runbook ]] && doc="${RUNBOOK}"
    if ! body="$(md_block "${doc}" "${BLOCK_ANCHORS[i]}")"; then
      fail "${doc##*/} 에 「${BLOCK_ANCHORS[i]}」 블록이 하나가 아님"
      ok=1
      continue
    fi
    printf '%s\n' "${body//<키>/render}" >"${tmp_root}/blocks/${BLOCK_NAMES[i]}"
  done
  return "${ok}"
}

main() {
  tmp_root="$(mktemp -d \
    "${TMPDIR:-/tmp}/workers_suspend_runbook_test.XXXXXX")" || exit 1
  trap remove_tmp_root EXIT
  trap 'exit 1' INT TERM

  if load_blocks; then
    write_fakes
    run_cases
    test_no_comments
    test_mutations
    test_isolation_mutation
    if [[ -e "${tmp_root}/real-aws-reached" ]]; then
      fail "PATH 앞의 실패 aws 가 불림: $(head -n 1 \
        "${tmp_root}/real-aws-reached")"
    fi
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
