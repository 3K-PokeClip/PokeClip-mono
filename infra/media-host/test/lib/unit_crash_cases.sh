# shellcheck shell=bash
# 전역(scratch · runtime_dir · unit_env 등)과 require_installed ·
# require_host_disruption 은 unit_restart_test.sh 가, unit:: 함수는
# lib/unit_runtime_cases.sh 가 정의한다.
# shellcheck disable=SC2154
#
# 유닛 재기동 시험(unit_restart_test.sh)의 크래시 경로 사례 — 크래시 뒤 옛 비밀
# 컨테이너 잔존 재현(실험 루트 m1-repro). 주 프로세스가 실패로 끝나면 systemd 는 ExecStop 을
# 건너뛰고 ExecStopPost 로 간다. 그때 compose 를 내릴 주체가 없으면 컨테이너가
# 옛 렌더(옛 CDN 비밀 · 옛 API 비밀번호)를 붙든 채 남는다.
#
# 판별 신호:
#   - 회수: 유닛이 재시작을 기다리는(auto-restart) 동안 프로젝트 컨테이너 0 ·
#     렌더 파일 0.
#   - 새 기동: mediamtx 컨테이너가 다시 만들어졌고(id 바뀜), mediamtx · 사이드카가
#     보는 렌더가 호스트의 새 렌더와 바이트가 같다(비밀번호 짝 일치).
#   - 렌더 자리에 디렉터리 0 · 「MEDIA START FAIL」 · 「CLEANUP FAIL」 줄 0.
#
# MainPID(docker CLI) 만 SIGKILL 하는 사례는 넣지 않는다 — 남은 compose 플러그인이
# systemd 의 SIGTERM 을 받아 컨테이너를 멈추므로 이 결함을 놓친다(m1-repro 갈래 A).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다).

# 사이드카 종료 지연 — 10초를 넘겨 엔진 재시작 간격이 늘지 않게 하고, 방아쇠
# 뒤 곧 재시작해 렌더 자리를 다시 마운트하게 한다(m1-repro 와 같은 12초).
readonly CRASH_SIDECAR_DELAY='12s'
# 시간(초).
readonly CRASH_REAP_TIMEOUT=60
readonly CRASH_LEFTOVER_HOLD=20
readonly CRASH_KILL_TIMEOUT=5
# 렌더 파일과 그 파일을 마운트하는 자리(서비스 · 컨테이너 안 경로).
readonly CRASH_VIEWS='mediamtx mediamtx.yml /mediamtx.yml
sidecar mtx_api_pass /run/secrets/mtx_api_pass'

#######################################
# 프로젝트 서비스의 컨테이너 id 를 낸다(없으면 빈 값).
# Arguments:
#   서비스 이름.
#######################################
crash::cid() {
  docker ps -a -q --no-trunc \
    --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=$1" 2>/dev/null | head -n 1
}

#######################################
# 렌더 자리 둘 가운데 디렉터리인 것의 수를 낸다.
#######################################
crash::render_dirs() {
  local n=0 name
  while read -r _ name _; do
    if sudo -n test -d "${runtime_dir}/${name}"; then
      n=$((n + 1))
    fi
  done <<<"${CRASH_VIEWS}"
  printf '%s' "${n}"
}

#######################################
# 실행 중인 mediamtx · 사이드카가 보는 렌더가 호스트의 렌더(일반 파일)와
# 바이트가 같은지 본다. 같지 않으면 이유를 낸다.
# Outputs:
#   어긋난 이유(맞으면 빈 값).
#######################################
crash::views_mismatch() {
  local service name inner cid pid host
  while read -r service name inner; do
    host="${runtime_dir}/${name}"
    if ! sudo -n test -f "${host}" || sudo -n test -L "${host}"; then
      printf '호스트 %s 가 일반 파일이 아님' "${name}"
      return 0
    fi
    cid="$(crash::cid "${service}")"
    pid="$(docker inspect -f '{{.State.Pid}}' "${cid}" 2>/dev/null || true)"
    if [[ -z "${cid}" || -z "${pid}" || "${pid}" == 0 ]]; then
      printf '%s 가 실행 중이 아님' "${service}"
      return 0
    fi
    if ! sudo -n cmp -s -- "${host}" "/proc/${pid}/root${inner}"; then
      printf '%s 가 보는 %s ≠ 호스트 새 렌더' "${service}" "${inner}"
      return 0
    fi
  done <<<"${CRASH_VIEWS}"
}

#######################################
# 유닛이 새로 떠 새 렌더를 쓰는 스택이 될 때까지 기다린다 — active · MainPID
# 바뀜 · 헬스 200 · mediamtx 컨테이너 id 바뀜 · 두 컨테이너가 보는 렌더 =
# 호스트 렌더.
# Arguments:
#   상한(초), 옛 MainPID(없으면 빈 값), 옛 mediamtx 컨테이너 id.
#######################################
crash::wait_fresh() {
  local deadline=$((SECONDS + $1)) old_pid="$2" old_cid="$3"
  local state pid cid why='관찰 전'
  while (( SECONDS < deadline )); do
    state="$(unit::prop ActiveState)"
    pid="$(unit::prop MainPID)"
    cid="$(crash::cid mediamtx)"
    if [[ "${state}" != active || -z "${pid}" || "${pid}" == 0 \
      || "${pid}" == "${old_pid}" ]]; then
      why="유닛 ${state} · MainPID ${pid}(옛 ${old_pid:-없음})"
    elif ! unit::health_ok; then
      why='헬스 200 아님'
    elif [[ -z "${cid}" || "${cid}" == "${old_cid}" ]]; then
      why="mediamtx 컨테이너가 다시 만들어지지 않음(${cid:0:12})"
    else
      why="$(crash::views_mismatch)"
      [[ -n "${why}" ]] || return 0
    fi
    sleep 1
  done
  harness::add_reason "$1초 안에 새 렌더로 기동 안 됨 — ${why}"
  return 1
}

#######################################
# 유닛이 재시작 대기(auto-restart)에 들어가면 프로젝트 컨테이너 0 · 렌더
# 파일 0 인지 본다.
#######################################
crash::expect_reaped() {
  local deadline=$((SECONDS + CRASH_REAP_TIMEOUT)) n ok=0
  while [[ "$(unit::prop SubState)" != auto-restart ]]; do
    if (( SECONDS >= deadline )); then
      harness::add_reason "${CRASH_REAP_TIMEOUT}초 안에 재시작 대기 안 됨\
($(unit::prop ActiveState)/$(unit::prop SubState))"
      return 1
    fi
    sleep 0.2
  done
  n="$(harness::count_output_lines docker ps -a -q --filter \
    "label=com.docker.compose.project=${PROJECT}")" || n='?'
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "재시작 대기 중 프로젝트 컨테이너 ${n}(옛 렌더를 붙든 채)"
    ok=1
  fi
  n="$(sudo -n find "${runtime_dir}" -maxdepth 1 \( -name mediamtx.yml \
    -o -name mtx_api_pass \) 2>/dev/null | grep -c . || true)"
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "재시작 대기 중 렌더 자리 ${n}"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 방아쇠 뒤 유닛 저널에 실패 줄이 없고 렌더 자리에 디렉터리가 없는지 본다.
# Arguments:
#   방아쇠 시각(epoch).
#######################################
crash::expect_no_failure_lines() {
  local ok=0 line n
  for line in 'MEDIA START FAIL' 'CLEANUP FAIL'; do
    n="$(unit::journal_count -u "${UNIT_NAME}" "$1" "${line}")"
    if [[ "${n}" != 0 ]]; then
      harness::add_reason "유닛 저널 「${line}」 ${n}줄"
      ok=1
    fi
  done
  n="$(crash::render_dirs)"
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "렌더 자리 디렉터리 ${n}"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 사이를 끊는다 — 유닛을 멈추고 프로젝트를 내리고, 앞 사례가 렌더 자리에
# 남긴 빈 디렉터리를 지운다(제품이 아니라 시험의 원상 복구). 한 사례의 결함이
# 다음 사례의 판정을 오염시키지 않게 한다.
#######################################
crash::reset() {
  local name
  unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1 || true
  harness::bounded "${HARNESS_DOCKER_TIMEOUT}" env "${unit_env[@]}" \
    RUNTIME_DIR="${runtime_dir}" docker compose -p "${PROJECT}" \
    -f "${UNIT_COMPOSE}" down >/dev/null 2>&1 || true
  while read -r _ name _; do
    if sudo -n test -d "${runtime_dir}/${name}"; then
      sudo -n rmdir -- "${runtime_dir}/${name}" || true
    fi
  done <<<"${CRASH_VIEWS}"
}

#######################################
# 주어진 프로세스가 모두 끝날 때까지 기다린다(상한 CRASH_KILL_TIMEOUT 초).
# Arguments:
#   PID 들.
#######################################
crash::wait_pids_gone() {
  local deadline=$((SECONDS + CRASH_KILL_TIMEOUT)) pid alive
  while (( SECONDS < deadline )); do
    alive=0
    for pid in "$@"; do
      if sudo -n kill -0 "${pid}" 2>/dev/null; then
        alive=1
      fi
    done
    (( alive )) || return 0
    sleep 0.2
  done
  return 1
}

#######################################
# 유닛 주 프로세스를 크래시시킨다.
# Arguments:
#   plugin(compose 플러그인 — MainPID 의 자식만 SIGKILL) · cgroup(유닛 cgroup
#   전체 SIGKILL).
#######################################
crash::trigger() {
  local main child log="${scratch}/crash-kill.log"
  main="$(unit::prop MainPID)"
  child="$(pgrep -P "${main}" || true)"
  if [[ -z "${child}" || "${child}" == *$'\n'* ]]; then
    harness::add_reason "compose 플러그인을 하나로 찾지 못함(MainPID ${main} \
자식 「$(printf '%s' "${child}" | tr '\n' ' ')」)"
    return 1
  fi
  if [[ "$1" == cgroup ]]; then
    # systemd 252 는 주 프로세스가 먼저 죽으면 나머지에 보낸 신호를 EIO 로
    # 보고하며 0 아닌 코드로 끝나기도 한다(c9 탐침 12회 중 5회 — 신호는 갔다).
    # 그래서 종료 코드가 아니라 두 프로세스가 끝났는지로 본다.
    unit::ctl kill -s KILL "${UNIT_NAME}" >"${log}" 2>&1 || true
    crash::wait_pids_gone "${main}" "${child}" && return 0
    harness::add_reason "systemctl kill -s KILL 뒤에도 유닛 프로세스가 남음: \
$(harness::log_tail "${log}")"
    return 1
  fi
  if ! sudo -n kill -KILL "${child}"; then
    harness::add_reason "compose 플러그인(PID ${child}) SIGKILL 실패"
    return 1
  fi
}

#######################################
# 크래시 뒤 회수 · 새 기동 · 실패 줄 0.
# Arguments:
#   crash::trigger 의 방식.
#######################################
crash::case_kill() {
  local pid0 cid0 t0 ok=0
  require_host_disruption || return 1
  require_installed || return 1
  crash::reset
  unit::fresh_start "${CRASH_SIDECAR_DELAY}" || return 1
  pid0="$(unit::prop MainPID)"
  cid0="$(crash::cid mediamtx)"
  t0="$(unit::now)"
  crash::trigger "$1" || return 1
  crash::expect_reaped || ok=1
  crash::wait_fresh "${UNIT_RECOVER_TIMEOUT}" "${pid0}" "${cid0}" || ok=1
  crash::expect_no_failure_lines "${t0}" || ok=1
  return "${ok}"
}

crash::case_plugin_kill() { crash::case_kill plugin; }
crash::case_cgroup_kill() { crash::case_kill cgroup; }

#######################################
# 정지한 유닛 밖에 옛 렌더로 띄운 스택을 남긴다(ExecStopPost 의 down 이 실패한
# 경우 · 손으로 띄운 경우의 대역).
#######################################
crash::leave_stack() {
  local log="${scratch}/leftover.log" name
  while read -r _ name _; do
    printf 'pcbench-edge-proxy-stale-%s\n' "${name}" >"${scratch}/stale"
    sudo -n install -m 0444 -- "${scratch}/stale" "${runtime_dir}/${name}" \
      || return 1
  done <<<"${CRASH_VIEWS}"
  harness::run_bounded "${HARNESS_DOCKER_TIMEOUT}" "${log}" env \
    "${unit_env[@]}" RUNTIME_DIR="${runtime_dir}" docker compose \
    -p "${PROJECT}" -f "${UNIT_COMPOSE}" up -d
}

#######################################
# 남은 스택 · 지워진 렌더 — 사이드카가 다시 떠도 렌더 자리에 디렉터리가 생기지
# 않고, 유닛을 시작하면 남은 컨테이너를 다시 만들어 새 렌더를 쓴다.
#######################################
crash::case_leftover_stack() {
  local cid0 t0 deaths ok=0
  require_host_disruption || return 1
  require_installed || return 1
  crash::reset
  unit::fresh_start "${CRASH_SIDECAR_DELAY}" || return 1
  if ! unit::ctl stop "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl stop 실패'
    return 1
  fi
  if ! crash::leave_stack; then
    harness::add_reason "옛 스택을 띄우지 못함: \
$(harness::log_tail "${scratch}/leftover.log")"
    return 1
  fi
  cid0="$(crash::cid mediamtx)"
  t0="$(unit::now)"
  sudo -n rm -f -- "${runtime_dir}/mediamtx.yml" "${runtime_dir}/mtx_api_pass"
  sleep "${CRASH_LEFTOVER_HOLD}"
  deaths="$(docker events --since "${t0}" --until "$(unit::now)" \
    --filter type=container --filter event=die \
    --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter 'label=com.docker.compose.service=sidecar' \
    --format '{{.Actor.ID}}' | grep -c . || true)"
  if (( deaths < 1 )); then
    harness::add_reason "렌더를 지운 뒤 사이드카 종료 ${deaths}(재시작 관찰 안 됨)"
    ok=1
  fi
  if [[ "$(crash::render_dirs)" != 0 ]]; then
    harness::add_reason "사이드카 재시작 뒤 렌더 자리 디렉터리 $(crash::render_dirs)"
    ok=1
  fi
  if ! unit::ctl start "${UNIT_NAME}" >/dev/null 2>&1; then
    harness::add_reason 'systemctl start 실패'
    return 1
  fi
  crash::wait_fresh "${UNIT_READY_TIMEOUT}" '' "${cid0}" || ok=1
  crash::expect_no_failure_lines "${t0}" || ok=1
  return "${ok}"
}
