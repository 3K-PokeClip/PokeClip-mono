# shellcheck shell=bash
# 전역(scratch · health_port · mode_file · runtime_dir · unit_env 등)과
# require_installed · write_unit_env 는 unit_restart_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# 유닛 재기동 시험(unit_restart_test.sh)의 동작 사례 — 설치한 시험 유닛
# (pcbench-edge-proxy)을 systemctl 로 움직이고 판별 신호를 본다(사례 제목의
# 기호 ① – ④ · ③ 확장 · ⑥ · ⑦a · ⑦b · ⑧ 와 기동 중 정지 — 기호와 사례 번호의
# 대응은 unit_restart_test.sh 머리 주석).
#
# 부르는 쪽(unit_restart_test.sh)이 정하는 값을 읽는다: PROJECT · UNIT_NAME ·
# ECHO_ADMIN_PORT · UNIT_COMPOSE · SYSTEMCTL_TIMEOUT · UNIT_FILE · DROPIN_DIR ·
# INSTALL_ROOT · scratch · health_port · health_path · mode_file · runtime_dir ·
# unit_env 와 함수 require_installed · require_host_disruption · write_unit_env.
# harness.sh 를 먼저 source 한다.
#
# 판별 신호:
#   - 유닛 재기동 = MainPID 가 바뀌고 헬스(사례 9) 200 이 돌아온다.
#   - 사이드카만의 재시작 = MainPID · NRestarts 가 그대로이고, 태그 저널
#     (journalctl -t pokeclip-media-sidecar)에 종료 줄이 쌓이며 유닛 저널에는
#     사이드카 줄이 없다.
#   - 엔진이 혼자 띄운 비부착 컨테이너 = 창 안에서 create 없이 start 된 사이드카.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다).

# 사이드카 역할이 재시작하지 않게 할 때 · 되풀이해 끝나게 할 때의 종료 지연.
readonly UNIT_IDLE_DELAY='3600s'
readonly UNIT_FLAP_DELAY='4s'
readonly UNIT_SIDECAR_TAG='pokeclip-media-sidecar'
# compose 출력이 붙는 서비스(시작 스크립트 --attach) — 나머지는 비부착이다.
readonly UNIT_ATTACHED_SERVICES=' mediamtx nginx '
# 시간(초). 회복 상한 = prod RestartSec(20) + 기동 상한.
readonly UNIT_READY_TIMEOUT=60
readonly UNIT_RECOVER_TIMEOUT=120
readonly UNIT_FLAP_WINDOW=120
readonly UNIT_OUTAGE_WINDOW=90
readonly UNIT_MIN_OUTAGE_RESTARTS=3
readonly UNIT_STOP_HOLD=30
readonly UNIT_DOCKER_RECOVER=90
readonly UNIT_MARKER_TIMEOUT=30
readonly UNIT_POLL=5
readonly UNIT_DIE_TIMEOUT=10
# docker exec 가 SIGKILL 로 끝났을 때의 종료 코드(128 + 9).
readonly UNIT_EXEC_KILLED=137
# docker events 한 줄 = 「동작 서비스 컨테이너ID」.
readonly UNIT_EVENT_FORMAT='{{.Action}} '\
'{{index .Actor.Attributes "com.docker.compose.service"}} {{.Actor.ID}}'

unit_teardown_reason=""

#######################################
# 시험 유닛의 속성 하나를 낸다(없으면 빈 값).
# Arguments:
#   속성 이름.
#######################################
unit::prop() {
  systemctl show -p "$1" --value "${UNIT_NAME}" 2>/dev/null || true
}

# systemctl 을 sudo 로 상한 안에서 부른다.
unit::ctl() {
  harness::bounded "${SYSTEMCTL_TIMEOUT}" sudo -n systemctl "$@"
}

unit::health_ok() {
  [[ "$(harness::http_code \
    "http://127.0.0.1:${health_port}${health_path}")" == 200 ]]
}

unit::now() {
  date +%s
}

#######################################
# 유닛이 active · MainPID 있음(옛 PID 와 다름) · 헬스 200 이 될 때까지
# 기다린다.
# Arguments:
#   상한(초), 옛 MainPID(없어도 됨).
#######################################
unit::wait_ready() {
  local deadline=$((SECONDS + $1)) old="${2:-}" pid state
  while (( SECONDS < deadline )); do
    state="$(unit::prop ActiveState)"
    pid="$(unit::prop MainPID)"
    if [[ "${state}" == active && "${pid}" != 0 && -n "${pid}" \
      && "${pid}" != "${old}" ]] && unit::health_ok; then
      return 0
    fi
    sleep 1
  done
  harness::add_reason "$1초 안에 기동 안 됨(상태 ${state} · MainPID ${pid} \
· 옛 ${old:-없음} · 헬스 $(harness::http_code \
    "http://127.0.0.1:${health_port}${health_path}"))"
  return 1
}

#######################################
# 사이드카 종료 지연을 정하고(가짜 aws 는 ok) 유닛을 새로 띄운다.
# Arguments:
#   사이드카 역할 종료 지연.
#######################################
unit::fresh_start() {
  write_unit_env "$1"
  printf 'ok\n' >"${mode_file}"
  if ! unit::ctl restart "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl restart 실패'
    return 1
  fi
  unit::wait_ready "${UNIT_READY_TIMEOUT}"
}

#######################################
# 렌더 자리(runtime_dir 바로 아래)에서 find 식에 맞는 항목 수를 낸다. 자리가
# 아예 없으면 0 이다. find 가 실패하면 아무것도 내지 않고 1 을 돌려준다 — 조회
# 실패는 「확인 불가」 이지 0 건이 아니다. 부르는 쪽이 실패로 다룬다.
# Globals:
#   runtime_dir
# Arguments:
#   find 식(-name … -o -name …).
# Outputs:
#   항목 수(조회했을 때만).
# Returns:
#   0 셈 / 1 조회 실패.
#######################################
unit::render_count() {
  local found
  if sudo -n test ! -e "${runtime_dir}" 2>/dev/null; then
    printf '0\n'
    return 0
  fi
  found="$(sudo -n find "${runtime_dir}" -maxdepth 1 \( "$@" \) 2>/dev/null)" \
    || return 1
  printf '%s\n' "${found}" | awk 'NF { n++ } END { print n + 0 }'
}

#######################################
# 저널 줄 가운데 고정 문자열이 든 줄 수를 낸다. 저널 조회가 실패하면 아무것도
# 내지 않고 1 을 돌려준다 — 조회 실패는 「확인 불가」 이지 0 건이 아니다(grep 의
# 「맞는 줄 없음」 종료 1 과 가른다). 부르는 쪽이 실패로 다룬다.
# Arguments:
#   -t (태그) 또는 -u (유닛), 그 이름, 시작 시각(epoch), 문자열.
# Outputs:
#   줄 수(조회했을 때만).
# Returns:
#   0 셈 / 1 조회 · grep 실패.
#######################################
unit::journal_count() {
  local journal status=0
  sudo -n journalctl --sync >/dev/null 2>&1 || true
  journal="$(sudo -n journalctl "$1" "$2" --since "@$3" -o cat --no-pager \
    2>/dev/null)" || return 1
  printf '%s\n' "${journal}" | grep -c -F -- "$4" || status=$?
  (( status <= 1 ))
}

#######################################
# 컨테이너가 끝난(die) 종료 코드를 기다려 낸다.
# Arguments:
#   컨테이너 id, 찾기 시작 시각(epoch).
# Outputs:
#   die 이벤트의 종료 코드(못 찾으면 빈 값).
#######################################
unit::wait_die_code() {
  local deadline=$((SECONDS + UNIT_DIE_TIMEOUT)) code=""
  while (( SECONDS < deadline )); do
    code="$(docker events --since "$2" --until "$(unit::now)" \
      --filter "container=$1" --filter event=die \
      --format '{{index .Actor.Attributes "exitCode"}}' 2>/dev/null \
      | head -n 1)" || code=""
    if [[ -n "${code}" ]]; then
      break
    fi
    sleep 0.5
  done
  printf '%s' "${code}"
}

#######################################
# mediamtx 역할(출력 연결 · 망 칸 주인)을 지정한 코드로 끝낸다.
#
# 종료 요청(docker exec … fetch)이 137 로 끝나는 경합이 있다 — 에코가 응답을 쓰고
# 끝나면 컨테이너의 PID 공간이 사라지며 아직 안 끝난 fetch 가 SIGKILL 된다(탐침
# 상자 실측 약 3%). 그때는 그 컨테이너가 요청한 코드로 끝났는지(die 이벤트)로
# 전달을 확인한다 — 137 만으로는 받아들이지 않는다.
# Arguments:
#   종료 코드.
#######################################
unit::mediamtx_exit() {
  local cid since status=0 died
  cid="$(docker ps -q --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter 'label=com.docker.compose.service=mediamtx')" || cid=""
  if [[ -z "${cid}" ]]; then
    harness::add_reason 'mediamtx 역할 컨테이너를 찾지 못함'
    return 1
  fi
  since="$(( $(unit::now) - 1 ))"
  docker exec "${cid}" /echo fetch -method POST \
    "http://127.0.0.1:${ECHO_ADMIN_PORT}/exit?code=$1" >/dev/null 2>&1 \
    || status=$?
  if (( status == 0 )); then
    return 0
  fi
  if (( status == UNIT_EXEC_KILLED )); then
    died="$(unit::wait_die_code "${cid}" "${since}")"
    if [[ "${died}" == "$1" ]]; then
      return 0
    fi
  fi
  harness::add_reason "종료 요청(${1}) 실패(docker exec 종료 ${status} · die \
종료 코드 ${died:-없음})"
  return 1
}

#######################################
# 정지 상태를 본다 — inactive · 프로젝트 컨테이너 0 · 렌더 파일 0.
# Arguments:
#   원인 앞머리.
#######################################
unit::expect_stopped() {
  local ok=0 state n
  state="$(unit::prop ActiveState)"
  if [[ "${state}" != inactive ]]; then
    harness::add_reason "$1: 상태 ${state}($(unit::prop Result))"
    ok=1
  fi
  n="$(harness::count_output_lines docker ps -a -q --filter \
    "label=com.docker.compose.project=${PROJECT}")" || n='?'
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "$1: 프로젝트 컨테이너 ${n}"
    ok=1
  fi
  if ! n="$(unit::render_count -name mediamtx.yml -o -name mtx_api_pass \
    -o -name '.mediamtx.yml.*' -o -name '.mtx_api_pass.*')"; then
    harness::add_reason "$1: 렌더 자리 조회 실패"
    ok=1
  elif [[ "${n}" != 0 ]]; then
    harness::add_reason "$1: 렌더 파일 ${n}"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 유닛 주 프로세스가 마지막으로 시작한 시각(epoch · 소수)을 낸다.
#######################################
unit::main_start_epoch() {
  local ts
  ts="$(systemctl show -p ExecMainStartTimestamp --value \
    --timestamp=us+utc "${UNIT_NAME}" 2>/dev/null)" || return 1
  [[ -n "${ts}" && "${ts}" != n/a ]] || return 1
  date -u -d "${ts}" +%s.%N
}

#######################################
# 창 안에서 start 된 비부착 컨테이너 수를 낸다. 창의 끝은 유닛이 다시 뜬
# 시각이다 — 그 전에는 compose up 이 없으므로 그 사이의 start 는 엔진이 혼자
# 띄운 것이다(그 뒤의 start 는 새 compose up 몫 — 남아 있던 컨테이너를 다시
# 쓰면 create 없이 start 만 난다).
# Arguments:
#   창 시작(epoch), 창 끝(epoch · 소수 허용).
#######################################
unit::detached_autostarts() {
  local out
  out="$(docker events --since "$1" --until "$2" \
    --filter type=container \
    --filter "label=com.docker.compose.project=${PROJECT}" \
    --format "${UNIT_EVENT_FORMAT}")" || return 1
  printf '%s\n' "${out}" | awk -v attached="${UNIT_ATTACHED_SERVICES}" '
    $1 == "start" && index(attached, " " $2 " ") == 0 { n++ }
    END { print n + 0 }'
}

#######################################
# ① · ② — mediamtx 역할을 지정 코드로 끝내면 유닛이 전체를 다시 띄운다.
# Arguments:
#   종료 코드.
#######################################
unit::case_exit() {
  local pid0 n0 n1
  require_installed || return 1
  unit::fresh_start "${UNIT_IDLE_DELAY}" || return 1
  pid0="$(unit::prop MainPID)"
  n0="$(unit::prop NRestarts)"
  unit::mediamtx_exit "$1" || return 1
  unit::wait_ready "${UNIT_RECOVER_TIMEOUT}" "${pid0}" || return 1
  n1="$(unit::prop NRestarts)"
  if [[ "${n1}" != "$((n0 + 1))" ]]; then
    harness::add_reason "NRestarts ${n0} → ${n1}(+1 기대)"
    return 1
  fi
}

unit::case_exit_0() { unit::case_exit 0; }
unit::case_exit_1() { unit::case_exit 1; }

#######################################
# ⑥ — 사이드카 역할이 120초 동안 종료 1 을 되풀이해도 유닛은 그대로다.
#######################################
unit::case_sidecar_flap() {
  local t0 pid0 n0 misses=0 deadline ok=0 exits lines
  require_installed || return 1
  t0="$(unit::now)"
  unit::fresh_start "${UNIT_FLAP_DELAY}" || return 1
  pid0="$(unit::prop MainPID)"
  n0="$(unit::prop NRestarts)"
  deadline=$((SECONDS + UNIT_FLAP_WINDOW))
  while (( SECONDS < deadline )); do
    unit::health_ok || misses=$((misses + 1))
    sleep "${UNIT_POLL}"
  done
  if [[ "$(unit::prop MainPID)" != "${pid0}" \
    || "$(unit::prop NRestarts)" != "${n0}" ]]; then
    harness::add_reason "유닛이 다시 떴다(MainPID ${pid0} → \
$(unit::prop MainPID) · NRestarts ${n0} → $(unit::prop NRestarts))"
    ok=1
  fi
  if (( misses != 0 )) || ! unit::health_ok; then
    harness::add_reason "창 안 헬스 실패 ${misses}회"
    ok=1
  fi
  if ! exits="$(unit::journal_count -t "${UNIT_SIDECAR_TAG}" "${t0}" \
    'SIDECAR EXIT 1')"; then
    harness::add_reason '태그 저널 조회 실패'
    ok=1
  elif (( exits < 2 )); then
    harness::add_reason "태그 저널 종료 줄 ${exits}(≥2 기대)"
    ok=1
  fi
  if ! lines="$(unit::journal_count -u "${UNIT_NAME}" "${t0}" 'SIDECAR')"; then
    harness::add_reason '유닛 저널 조회 실패'
    ok=1
  elif [[ "${lines}" != 0 ]]; then
    harness::add_reason "유닛 저널에 사이드카 줄 ${lines}(0 기대)"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 태그 저널에서 첫 시작 표식 줄을 기다려 낸다.
# Arguments:
#   시작 시각(epoch), 최소 표식 수.
#######################################
unit::wait_marker() {
  local deadline=$((SECONDS + UNIT_MARKER_TIMEOUT)) lines
  while (( SECONDS < deadline )); do
    sudo -n journalctl --sync >/dev/null 2>&1 || true
    lines="$(sudo -n journalctl -t "${UNIT_SIDECAR_TAG}" --since "@$1" \
      -o cat --no-pager 2>/dev/null | grep -F 'SIDECAR START at=' || true)"
    if (( $(printf '%s' "${lines}" | grep -c . || true) >= $2 )); then
      printf '%s\n' "${lines}" | head -n 1
      return 0
    fi
    sleep 1
  done
  return 1
}

#######################################
# ⑧ — 유닛을 재기동해도 재기동 전 사이드카 표식이 태그 저널에 남는다.
#######################################
unit::case_journal_kept() {
  local t0 marker pid0 kept
  require_installed || return 1
  t0="$(unit::now)"
  unit::fresh_start "${UNIT_IDLE_DELAY}" || return 1
  if ! marker="$(unit::wait_marker "${t0}" 1)"; then
    harness::add_reason '태그 저널에 시작 표식이 없다(비부착 로그 자리)'
    return 1
  fi
  pid0="$(unit::prop MainPID)"
  if ! unit::ctl restart "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl restart 실패'
    return 1
  fi
  unit::wait_ready "${UNIT_READY_TIMEOUT}" "${pid0}" || return 1
  if ! unit::wait_marker "${t0}" 2 >/dev/null; then
    harness::add_reason '재기동 뒤 새 표식이 없다'
    return 1
  fi
  kept="$(sudo -n journalctl -t "${UNIT_SIDECAR_TAG}" --since "@${t0}" \
    -o cat --no-pager 2>/dev/null | grep -c -x -F -- "${marker}" || true)"
  if (( kept < 1 )); then
    harness::add_reason '재기동 전 표식이 태그 저널에서 사라졌다'
    return 1
  fi
}

#######################################
# ③ — systemctl stop 뒤 정지가 유지된다.
#######################################
unit::case_stop() {
  require_installed || return 1
  unit::fresh_start "${UNIT_IDLE_DELAY}" || return 1
  if ! unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl stop 실패'
    return 1
  fi
  sleep "${UNIT_STOP_HOLD}"
  unit::expect_stopped "stop ${UNIT_STOP_HOLD}초 뒤"
}

#######################################
# ③ 확장 — 사이드카가 재시작을 되풀이하는 동안 stop 해도 30 · 60초 뒤
# 컨테이너 · 렌더가 남지 않는다(엔진이 사이드카를 다시 살리지 않는다).
#######################################
unit::case_stop_flapping() {
  local t0 ok=0
  require_installed || return 1
  t0="$(unit::now)"
  unit::fresh_start "${UNIT_FLAP_DELAY}" || return 1
  local deadline=$((SECONDS + UNIT_MARKER_TIMEOUT)) exits=0
  # 조회가 실패한 바퀴는 0 으로 두고 다시 본다 — 끝까지 못 세면 아래에서 실패다.
  while (( SECONDS < deadline )); do
    exits="$(unit::journal_count -t "${UNIT_SIDECAR_TAG}" "${t0}" \
      'SIDECAR EXIT 1')" || exits=0
    (( exits < 1 )) || break
    sleep 1
  done
  # 사이드카가 한 번도 끝나지 않았으면 「재시작 중 stop」 을 시험하지 못한 것이다.
  if (( exits < 1 )); then
    harness::add_reason "${UNIT_MARKER_TIMEOUT}초 안에 사이드카 종료 줄 0(재시작 \
중 상태에 닿지 못함)"
    return 1
  fi
  if ! unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl stop 실패'
    return 1
  fi
  sleep "${UNIT_STOP_HOLD}"
  unit::expect_stopped "stop ${UNIT_STOP_HOLD}초 뒤" || ok=1
  sleep "${UNIT_STOP_HOLD}"
  unit::expect_stopped "stop $((UNIT_STOP_HOLD * 2))초 뒤" || ok=1
  return "${ok}"
}

#######################################
# 기동 중(시작 스크립트가 CDN 비밀을 조회하는 중) systemctl stop 해도 유닛은
# failed 가 아니라 inactive 로 끝나고, 컨테이너 · 렌더 파일이 남지 않는다.
# 가짜 aws 의 hang 모드가 조회에 머무는 동안 표식 파일을 남긴다. 유닛 수준에서
# 멈추는 자리는 비밀 조회 하나다 — 그 뒤 렌더 중 신호(130 · 임시 파일 0 · up
# 0)는 start_test 사례 28 이 시작 스크립트 단독으로 덮는다.
#######################################
unit::case_stop_while_starting() {
  local marker="${mode_file}.hang" deadline result ok=0
  require_installed || return 1
  unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1 || true
  write_unit_env "${UNIT_IDLE_DELAY}"
  sudo -n rm -f -- "${marker}"
  printf 'hang\n' >"${mode_file}"
  if ! unit::ctl start "${UNIT_NAME}" >/dev/null 2>&1; then
    printf 'ok\n' >"${mode_file}"
    harness::add_reason 'systemctl start 실패'
    return 1
  fi
  deadline=$((SECONDS + UNIT_MARKER_TIMEOUT))
  while [[ ! -e "${marker}" ]] && (( SECONDS < deadline )); do
    sleep 0.2
  done
  printf 'ok\n' >"${mode_file}"
  if [[ ! -e "${marker}" ]]; then
    harness::add_reason "${UNIT_MARKER_TIMEOUT}초 안에 비밀 조회 중 상태에 닿지 못함"
    unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1 || true
    return 1
  fi
  if ! unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl stop 실패'
    return 1
  fi
  result="$(unit::prop Result)"
  if [[ "${result}" != success ]]; then
    harness::add_reason "stop 뒤 Result ${result}(success 기대 · 주 프로세스 종료 \
$(unit::prop ExecMainStatus))"
    ok=1
  fi
  unit::expect_stopped '기동 중 stop 뒤' || ok=1
  return "${ok}"
}

#######################################
# ④ — CDN 비밀 조회가 90초 동안 빈 값이어도 유닛은 failed 로 굳지 않고
# 재시도하며, 값이 돌아오면 사람 손 없이 뜬다.
#######################################
unit::case_secret_outage() {
  local t0 n0 n1 state failed=0 blocked deadline ok=0
  require_installed || return 1
  write_unit_env "${UNIT_IDLE_DELAY}"
  printf 'empty\n' >"${mode_file}"
  t0="$(unit::now)"
  if ! unit::ctl restart "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl restart 실패'
    return 1
  fi
  n0="$(unit::prop NRestarts)"
  deadline=$((SECONDS + UNIT_OUTAGE_WINDOW))
  while (( SECONDS < deadline )); do
    state="$(unit::prop ActiveState)"
    [[ "${state}" != failed ]] || failed=$((failed + 1))
    sleep "${UNIT_POLL}"
  done
  n1="$(unit::prop NRestarts)"
  if (( failed != 0 )); then
    harness::add_reason "failed 상태 관찰 ${failed}회"
    ok=1
  fi
  if (( n1 - n0 < UNIT_MIN_OUTAGE_RESTARTS )); then
    harness::add_reason "NRestarts ${n0} → ${n1}(+${UNIT_MIN_OUTAGE_RESTARTS} \
이상 기대)"
    ok=1
  fi
  if ! blocked="$(unit::journal_count -u "${UNIT_NAME}" "${t0}" \
    'MEDIA START BLOCKED: CDN secret unavailable')"; then
    harness::add_reason '유닛 저널 조회 실패'
    ok=1
  elif (( blocked < UNIT_MIN_OUTAGE_RESTARTS )); then
    harness::add_reason "유닛 저널 막힘 줄 ${blocked}"
    ok=1
  fi
  printf 'ok\n' >"${mode_file}"
  unit::wait_ready "${UNIT_RECOVER_TIMEOUT}" || ok=1
  return "${ok}"
}

#######################################
# ⑦ — docker 데몬을 흔든 뒤 유닛이 다시 뜨고(MainPID 바뀜 · 사례 9), 그 사이
# 엔진이 비부착 컨테이너를 혼자 띄우지 않는다.
# Arguments:
#   흔드는 방식(restart · kill).
#######################################
unit::case_docker() {
  local pid0 t0 t1 dpid n ok=0
  require_host_disruption || return 1
  require_installed || return 1
  unit::fresh_start "${UNIT_IDLE_DELAY}" || return 1
  pid0="$(unit::prop MainPID)"
  t0="$(unit::now)"
  if [[ "$1" == restart ]]; then
    if ! unit::ctl restart docker >/dev/null 2>&1; then
      harness::add_reason 'systemctl restart docker 실패'
      return 1
    fi
  else
    dpid="$(systemctl show -p MainPID --value docker 2>/dev/null || true)"
    if [[ -z "${dpid}" || "${dpid}" == 0 ]] \
      || ! sudo -n kill -KILL "${dpid}"; then
      harness::add_reason "dockerd 를 끝내지 못함(PID ${dpid:-없음})"
      return 1
    fi
  fi
  unit::wait_ready "${UNIT_DOCKER_RECOVER}" "${pid0}" || return 1
  if ! t1="$(unit::main_start_epoch)" \
    || ! n="$(unit::detached_autostarts "${t0}" "${t1}")"; then
    harness::add_reason 'docker events · 유닛 시작 시각 조회 실패'
    return 1
  fi
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "엔진이 혼자 띄운 비부착 컨테이너 ${n}"
    ok=1
  fi
  return "${ok}"
}

unit::case_docker_restart() { unit::case_docker restart; }
unit::case_docker_kill() { unit::case_docker kill; }

#######################################
# ⑤ — 시험 유닛 · drop-in · 설치본을 지우고 프로젝트를 down -v 한다.
# Globals:
#   unit_teardown_reason
# Returns:
#   0 모두 됨 / 1 실패(원인은 unit_teardown_reason).
#######################################
unit::teardown() {
  local log="${scratch}/teardown.log" ok=0
  unit_teardown_reason=""
  : >"${log}"
  if [[ "$(unit::prop LoadState)" == loaded ]] \
    && ! unit::ctl stop "${UNIT_NAME}" >>"${log}" 2>&1; then
    unit_teardown_reason+='stop; '
    ok=1
  fi
  if ! sudo -n rm -rf -- "${UNIT_FILE}" "${DROPIN_DIR}" "${INSTALL_ROOT}" \
    || ! sudo -n systemctl daemon-reload; then
    unit_teardown_reason+='rm · daemon-reload; '
    ok=1
  fi
  sudo -n systemctl reset-failed "${UNIT_NAME}" >/dev/null 2>&1 || true
  if ! harness::bounded "${HARNESS_DOCKER_TIMEOUT}" env "${unit_env[@]}" \
    RUNTIME_DIR="${runtime_dir}" docker compose -p "${PROJECT}" \
    -f "${UNIT_COMPOSE}" down -v --remove-orphans >>"${log}" 2>&1; then
    unit_teardown_reason+="down -v($(harness::log_tail "${log}")); "
    ok=1
  fi
  sudo -n rm -rf -- "${runtime_dir}" || ok=1
  return "${ok}"
}
