# shellcheck shell=bash
#
# infra/media-host 셸 시험의 공용 부품 — 사례 결과 집계 · 실패 원인 모으기 · 로그
# 끝 보기(홈 경로 가림) · 상한 시간이 있는 실행과 기다림.
#
# 사례 함수는 통과면 0, 실패면 0 아닌 값을 돌려주고 실패 원인은
# harness::add_reason 으로 남긴다. harness::run_case 가 결과 줄(PASS · FAIL)을
# 낸다. 판정이 아닌 기록 사례는 harness::run_record 로 돌리고 기록은
# harness_record_note 에 남긴다(RECORDED). 끝에 harness::summary 가 사례별
# 결과와 합계를 낸다 — 결과가 없는 사례(누락)도 FAIL 로 센다. 사례 번호는
# 계획의 정수 번호이고, 계획에 없는 덧붙임 사례는 「1b」 같은 이름으로 단다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다 — macOS 에
# timeout 이 없어 상한 시간은 SECONDS 로 직접 잰다.

harness_fail_reason=""
harness_record_note=""
# 부르는 쪽이 harness::wait_exit 뒤에 읽는다.
# shellcheck disable=SC2034
harness_waited_status=0
harness_case_status=()
# 덧붙임 사례(번호가 정수가 아님)의 이름과 결과 — 같은 칸끼리 짝이다.
harness_named_cases=()
harness_named_status=()
harness_pass_count=0
harness_fail_count=0
harness_recorded_count=0

#######################################
# 지금 사례의 실패 원인에 한 마디를 더한다.
# Globals:
#   harness_fail_reason
# Arguments:
#   원인.
#######################################
harness::add_reason() {
  harness_fail_reason="${harness_fail_reason:+${harness_fail_reason}; }$1"
}

#######################################
# 지금 사례의 실패 원인을 지운다(판정에 쓰지 않을 확인을 한 뒤에).
# Globals:
#   harness_fail_reason
#######################################
harness::clear_reason() {
  harness_fail_reason=""
}

#######################################
# 사례 결과 한 줄을 내고 집계한다.
# Globals:
#   harness_case_status, harness_pass_count, harness_fail_count,
#   harness_recorded_count
# Arguments:
#   사례 번호(정수) 또는 덧붙임 사례 이름, PASS|FAIL|RECORDED, 설명, 원인 ·
#   기록(없어도 됨).
# Outputs:
#   결과 한 줄을 표준 출력에 쓴다.
#######################################
harness::report() {
  local num="$1" status="$2" desc="$3" detail="${4:-}"
  if [[ "${num}" =~ ^[0-9]+$ ]]; then
    harness_case_status[num]="${status}"
  else
    harness_named_cases+=("${num}")
    harness_named_status+=("${status}")
  fi
  case "${status}" in
    PASS) harness_pass_count=$((harness_pass_count + 1)) ;;
    RECORDED) harness_recorded_count=$((harness_recorded_count + 1)) ;;
    *) harness_fail_count=$((harness_fail_count + 1)) ;;
  esac
  if [[ -n "${detail}" ]]; then
    printf '%s 사례 %s — %s: %s\n' "${status}" "${num}" "${desc}" "${detail}"
  else
    printf '%s 사례 %s — %s\n' "${status}" "${num}" "${desc}"
  fi
}

#######################################
# 판정 사례 하나를 돌린다.
# Globals:
#   harness_fail_reason
# Arguments:
#   사례 번호, 설명, 사례 함수 이름.
#######################################
harness::run_case() {
  local num="$1" desc="$2" fn="$3"
  harness_fail_reason=""
  if "${fn}"; then
    harness::report "${num}" PASS "${desc}"
  else
    harness::report "${num}" FAIL "${desc}" \
      "${harness_fail_reason:-원인 미기록}"
  fi
}

#######################################
# 기록 사례 하나를 돌린다. 기록을 못 하면 FAIL 이다.
# Globals:
#   harness_fail_reason, harness_record_note
# Arguments:
#   사례 번호, 설명, 사례 함수 이름.
#######################################
harness::run_record() {
  local num="$1" desc="$2" fn="$3"
  harness_fail_reason=""
  harness_record_note=""
  if "${fn}"; then
    harness::report "${num}" RECORDED "${desc}" "${harness_record_note}"
  else
    harness::report "${num}" FAIL "${desc}" \
      "기록 못 함 — ${harness_fail_reason:-원인 미기록}"
  fi
}

#######################################
# 덧붙임 사례 하나의 결과를 낸다(없으면 아무것도 내지 않는다).
# Globals:
#   harness_named_cases, harness_named_status
# Arguments:
#   사례 이름.
# Outputs:
#   결과(PASS|FAIL|RECORDED).
#######################################
harness::named_status() {
  local i
  for (( i = 0; i < ${#harness_named_cases[@]}; i++ )); do
    if [[ "${harness_named_cases[i]}" == "$1" ]]; then
      printf '%s' "${harness_named_status[i]}"
      return 0
    fi
  done
}

#######################################
# 사례별 결과와 합계를 낸다.
# Globals:
#   harness_case_status, harness_pass_count, harness_fail_count,
#   harness_recorded_count
# Arguments:
#   사례 수(1 부터 이 번호까지 본다), 덧붙임 사례 이름 …(없어도 됨).
# Outputs:
#   요약 두 줄을 표준 출력에 쓴다.
# Returns:
#   0 실패 0건 / 1 실패 있음.
#######################################
harness::summary() {
  local total="$1" num line="" missing=0 name status
  shift
  for (( num = 1; num <= total; num++ )); do
    if [[ -z "${harness_case_status[num]:-}" ]]; then
      missing=$((missing + 1))
    fi
    line="${line} ${num}=${harness_case_status[num]:-누락}"
  done
  for name in "$@"; do
    status="$(harness::named_status "${name}")"
    if [[ -z "${status}" ]]; then
      missing=$((missing + 1))
    fi
    line="${line} ${name}=${status:-누락}"
  done
  harness_fail_count=$((harness_fail_count + missing))
  printf '요약: PASS %d · FAIL %d · RECORDED %d\n' "${harness_pass_count}" \
    "${harness_fail_count}" "${harness_recorded_count}"
  printf '사례별:%s\n' "${line}"
  (( harness_fail_count == 0 ))
}

#######################################
# 로그 끝 몇 줄을 한 줄로 낸다. 제어 문자를 지우고, 홈 디렉터리 경로를 ~ 로
# 가린다 — 도구 오류문의 로컬 절대 경로(계정 이름)가 시험 출력 · 커밋 기록으로
# 새지 않게.
# Globals:
#   HOME
# Arguments:
#   로그 파일.
# Outputs:
#   한 줄을 표준 출력에 쓴다.
#######################################
harness::log_tail() {
  local home="${HOME:-}"
  if [[ ! -s "$1" ]]; then
    printf '(로그 비어 있음)'
    return 0
  fi
  [[ "${home}" != "/" ]] || home=""
  # index() 로 글자 그대로 찾는다 — 경로를 정규식으로 쓰면 . 같은 글자가 어긋난다.
  tail -n 4 "$1" \
    | sed "s/$(printf '\033')\[[0-9;]*[A-Za-z]//g" \
    | awk -v from="${home}" '
        {
          out = ""
          while (from != "" && (i = index($0, from)) > 0) {
            out = out substr($0, 1, i - 1) "~"
            $0 = substr($0, i + length(from))
          }
          printf "%s%s ", out, $0
        }'
}

#######################################
# 파일에서 고정 문자열이 든 줄 수를 낸다.
# Arguments:
#   문자열, 파일.
# Outputs:
#   줄 수.
# Returns:
#   0 셈 / 1 파일 없음(아무것도 내지 않는다).
#######################################
harness::count_fixed() {
  [[ -f "$2" ]] || return 1
  grep -c -F -- "$1" "$2" || true
}

#######################################
# 조회 명령(예: docker ps -q)을 돌려 출력의 비지 않은 줄 수를 낸다. 명령이
# 실패하면 아무것도 내지 않고 그 종료 코드를 돌려준다 — 조회 실패는 「확인
# 불가」 이지 0 이 아니다. 부르는 쪽이 종료 코드로 둘을 가른다.
# Arguments:
#   명령 …
# Outputs:
#   줄 수(명령이 성공했을 때만).
# Returns:
#   0 셈 / 0 아님 명령 실패(그 종료 코드).
#######################################
harness::count_output_lines() {
  local out
  out="$("$@")" || return
  printf '%s\n' "${out}" | awk 'length($0) > 0 { n++ } END { print n + 0 }'
}

#######################################
# URL 하나의 HTTP 상태 코드를 낸다(접속 실패면 000).
# Arguments:
#   URL.
# Outputs:
#   상태 코드.
#######################################
harness::http_code() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$1" || true
}

#######################################
# URL 이 200 을 낼 때까지 상한 시간만큼 기다린다.
# Arguments:
#   URL, 상한(초).
# Returns:
#   0 200 받음 / 1 상한 초과.
#######################################
harness::wait_http_200() {
  local url="$1" deadline=$((SECONDS + $2))
  while (( SECONDS < deadline )); do
    if [[ "$(harness::http_code "${url}")" == 200 ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

#######################################
# 명령을 상한 시간 안에서 돌린다. 넘기면 끝내고 124 를 돌려준다.
# Arguments:
#   상한(초), 로그 파일, 명령 …
# Returns:
#   명령의 종료 코드 / 124 상한 초과.
#######################################
harness::run_bounded() {
  local limit="$1" log="$2" pid deadline status=0
  shift 2
  "$@" >"${log}" 2>&1 &
  pid=$!
  deadline=$((SECONDS + limit))
  while kill -0 "${pid}" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      kill "${pid}" 2>/dev/null || true
      wait "${pid}" 2>/dev/null || true
      return 124
    fi
    sleep 1
  done
  wait "${pid}" || status=$?
  return "${status}"
}

#######################################
# 이 셸이 띄운 배경 프로세스가 끝나기를 상한 시간까지 기다린다.
# Globals:
#   harness_waited_status
# Arguments:
#   PID, 상한(초).
# Returns:
#   0 끝남(종료 코드는 harness_waited_status) / 124 상한 초과.
#######################################
harness::wait_exit() {
  local pid="$1" deadline=$((SECONDS + $2))
  while kill -0 "${pid}" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      return 124
    fi
    sleep 1
  done
  harness_waited_status=0
  # shellcheck disable=SC2034
  wait "${pid}" || harness_waited_status=$?
}
