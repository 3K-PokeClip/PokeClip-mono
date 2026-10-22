# shellcheck shell=bash
#
# 앞단 프록시 계약 시험의 사례 13 · 14 · 16 · 17 — bin/start-media.sh 로 띄우고
# bin/stop-media.sh 로 내리는 사례 묶음. proxy_contract.sh 가 source 해서
# 돌린다. harness.sh · proxy_requests.sh 를 먼저 source 하고,
# start_media::configure 로 프로젝트 · 시험 compose · 준비 상태를 넘긴다.
#
# 스크립트가 읽는 값은 시험 이음매에서 사례 13 에 정한 값뿐이다 — 기대
# compose 판 · DOCKER_CONFIG(스크래치) · PATH(test/fakebin 을 앞에) ·
# MEDIA_COMPOSE_FILE(시험 compose) · MEDIA_MTX_TEMPLATE · 가짜 ARN · 가짜
# 레지스트리 호스트 · RUNTIME_DIR(스크래치) · 자기 uid/gid(MediaMTX · 사이드카) ·
# MEDIA_COMPOSE_PROJECT. 우회 플래그는 주지 않는다. 그 밖에 스크립트가 아니라
# docker CLI · 시험 compose 가 읽는 값 둘을 함께 준다:
#   - DOCKER_HOST: DOCKER_CONFIG 를 스크래치로 바꾸면 docker CLI 가 홈 아래의
#     compose 플러그인과 현재 context 를 잃는다(로컬 macOS 가 그렇다). 그래서
#     스크래치에 플러그인 링크를 두고 엔드포인트를 이 값으로 준다. prod 는
#     플러그인이 시스템 경로에 있어 필요 없는 값이다.
#   - PCBENCH_SIDECAR_EXIT_DELAY: 시험 compose 의 사이드카 역할 종료 지연.
# 시험 compose 가 읽는 나머지 PCBENCH_* 값(이미지 · 포트 · nginx 설정)은 띄울 때
# env 로 proxy_contract.sh 가 export 한 값을 그대로 물려받는다. 사례 15 가
# PCBENCH_NGINX_CONF 를 control_upstream 을 채운 nginx-b.conf 로 바꾸고 되돌리지
# 않으므로, 이 사례들의 nginx 는 그 설정으로 뜬다(사례 15 준비가 그 설정의
# 렌더에 닿기 전에 실패하면 nginx-a.conf 그대로다).
#
# 제품 스크립트 · 시작 스크립트 시험 자산(가짜 aws · 자격 도우미 · 시험 MediaMTX
# 템플릿)이 없으면 사례가 FAIL 이다(건너뛰지 않는다).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly START_MEDIA_SCRIPT_REL='bin/start-media.sh'
readonly STOP_MEDIA_SCRIPT_REL='bin/stop-media.sh'
readonly START_MEDIA_FAKE_AWS_REL='test/fakebin/aws'
readonly START_MEDIA_FAKE_HELPER_REL='test/fakebin/docker-credential-ecr-login'
readonly START_MEDIA_MTX_TEMPLATE_REL='test/mediamtx.test.yml.tmpl'
readonly START_MEDIA_FAKE_ARN='pcbench-edge-proxy-fake-cdn-secret-arn'
readonly START_MEDIA_FAKE_ECR_HOST='pcbench-edge-proxy.invalid'
# 사이드카 역할이 재시작하지 않게 할 때 주는 종료 지연.
readonly START_MEDIA_IDLE_DELAY='3600s'
# 시간 상한(초) · 사례 16 기준.
readonly START_MEDIA_READY_TIMEOUT=60
readonly START_MEDIA_STOP_TIMEOUT=60
readonly START_MEDIA_ABORT_TIMEOUT=30
readonly START_MEDIA_SETTLE_TIMEOUT=20
readonly START_MEDIA_OBSERVE_WINDOW=40
readonly START_MEDIA_MIN_RESTARTS=3
# 마지막 시작의 접속 결과 줄을 기다리는 상한(초).
readonly START_MEDIA_RESULT_TIMEOUT=15
# 짧은 docker 조회(info · context · version · ps) 한 번의 상한(초).
readonly START_MEDIA_QUERY_TIMEOUT=30
# docker info 에서 compose 플러그인 경로를 꺼내는 형식(한 줄로 둔다 — 쪼개면
# 템플릿 동작이 달라질 수 있다).
readonly START_MEDIA_PLUGIN_FORMAT='{{range .ClientInfo.Plugins}}{{if eq .Name "compose"}}{{.Path}}{{end}}{{end}}'

start_media_project=""
start_media_compose_file=""
start_media_host_dir=""
start_media_work_dir=""
start_media_ready=0
start_media_setup_reason=""
start_media_run_dir=""
start_media_pid=""
start_media_env=()

#######################################
# 사례 묶음의 입력을 정한다.
# Globals:
#   start_media_project, start_media_compose_file, start_media_host_dir,
#   start_media_work_dir, start_media_ready, start_media_setup_reason
# Arguments:
#   compose 프로젝트, 시험 compose 파일, infra/media-host 경로, 스크래치,
#   준비됨(1|0), 준비 실패 원인.
#######################################
start_media::configure() {
  start_media_project="$1"
  start_media_compose_file="$2"
  start_media_host_dir="$3"
  start_media_work_dir="$4"
  start_media_ready="$5"
  start_media_setup_reason="$6"
}

#######################################
# 공통 선행 — 제품 스크립트 · 시작 스크립트 시험 자산 · 준비 상태.
# Globals:
#   start_media_host_dir, start_media_ready, start_media_setup_reason
#######################################
start_media::preflight() {
  local ok=0 rel
  for rel in "${START_MEDIA_SCRIPT_REL}" "${STOP_MEDIA_SCRIPT_REL}" \
    "${START_MEDIA_FAKE_AWS_REL}" "${START_MEDIA_FAKE_HELPER_REL}"; do
    if [[ ! -x "${start_media_host_dir}/${rel}" ]]; then
      harness::add_reason "${rel} 없음(c4 몫)"
      ok=1
    fi
  done
  if [[ ! -f "${start_media_host_dir}/${START_MEDIA_MTX_TEMPLATE_REL}" ]]; then
    harness::add_reason "${START_MEDIA_MTX_TEMPLATE_REL} 없음(c4 몫)"
    ok=1
  fi
  if (( ! start_media_ready )); then
    harness::add_reason "준비 실패 — ${start_media_setup_reason:-원인 미기록}"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 실행 한 번의 스크래치와 스크립트에 줄 값을 만든다.
# Globals:
#   start_media_run_dir, start_media_env, start_media_work_dir,
#   start_media_host_dir, start_media_compose_file, start_media_project,
#   DOCKER_HOST, PATH
# Arguments:
#   사이드카 역할의 종료 지연.
#######################################
start_media::prepare_env() {
  local plugin host version config_dir
  start_media_run_dir="$(mktemp -d "${start_media_work_dir}/c4.XXXXXX")" \
    || return 1
  config_dir="${start_media_run_dir}/docker-config"
  mkdir -p -- "${config_dir}/cli-plugins"
  printf '{"credHelpers":{"%s":"ecr-login"}}\n' "${START_MEDIA_FAKE_ECR_HOST}" \
    >"${config_dir}/config.json"
  plugin="$(harness::bounded "${START_MEDIA_QUERY_TIMEOUT}" docker info \
    --format "${START_MEDIA_PLUGIN_FORMAT}" 2>/dev/null)" || plugin=""
  if [[ ! -x "${plugin}" ]]; then
    harness::add_reason 'docker compose 플러그인 경로를 찾지 못함'
    return 1
  fi
  ln -s -- "${plugin}" "${config_dir}/cli-plugins/docker-compose"
  host="${DOCKER_HOST:-}"
  if [[ -z "${host}" ]]; then
    host="$(harness::bounded "${START_MEDIA_QUERY_TIMEOUT}" docker context \
      inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" || host=""
  fi
  version="$(harness::bounded "${START_MEDIA_QUERY_TIMEOUT}" docker compose \
    version --short 2>/dev/null)" || version=""
  if [[ -z "${host}" || -z "${version}" ]]; then
    harness::add_reason 'docker 엔드포인트 · compose 판을 읽지 못함'
    return 1
  fi
  start_media_env=(
    "MEDIA_EXPECTED_COMPOSE_VERSION=${version}"
    "DOCKER_CONFIG=${config_dir}"
    "DOCKER_HOST=${host}"
    "PATH=${start_media_host_dir}/test/fakebin:${PATH}"
    "MEDIA_COMPOSE_FILE=${start_media_compose_file}"
    "MEDIA_MTX_TEMPLATE=${start_media_host_dir}/${START_MEDIA_MTX_TEMPLATE_REL}"
    "CDN_SECRET_ARN=${START_MEDIA_FAKE_ARN}"
    "ECR_REGISTRY_HOST=${START_MEDIA_FAKE_ECR_HOST}"
    "RUNTIME_DIR=${start_media_run_dir}/runtime"
    "MTX_UID=$(id -u)"
    "MTX_GID=$(id -g)"
    "SIDECAR_UID=$(id -u)"
    "SIDECAR_GID=$(id -g)"
    "MEDIA_COMPOSE_PROJECT=${start_media_project}"
    "PCBENCH_SIDECAR_EXIT_DELAY=$1"
  )
}

#######################################
# 스크립트와 같은 값으로 시험 compose 에 명령을 보낸다(상한
# HARNESS_DOCKER_TIMEOUT — 넘기면 124).
# Globals:
#   start_media_env, start_media_project, start_media_compose_file
# Arguments:
#   docker compose 하위 명령과 인자.
#######################################
start_media::compose() {
  harness::bounded "${HARNESS_DOCKER_TIMEOUT}" env "${start_media_env[@]}" \
    docker compose -p "${start_media_project}" \
    -f "${start_media_compose_file}" "$@"
}

#######################################
# start-media.sh 를 배경 · 자기 프로세스 그룹으로 띄운다(전경 compose up 이
# 끝날 때 끝난다).
# Globals:
#   start_media_pid, start_media_env, start_media_host_dir
# Arguments:
#   로그 파일.
#######################################
start_media::start() {
  harness::spawn_group env "${start_media_env[@]}" \
    "${start_media_host_dir}/${START_MEDIA_SCRIPT_REL}" >"$1" 2>&1
  # harness_spawned_pid 는 harness.sh 가 채운다.
  # shellcheck disable=SC2154
  start_media_pid="${harness_spawned_pid}"
}

#######################################
# 띄운 스택이 헬스 200 을 낼 때까지 기다린다.
# Globals:
#   start_media_pid
# Arguments:
#   start-media.sh 로그 파일.
#######################################
start_media::wait_ready() {
  local deadline=$((SECONDS + START_MEDIA_READY_TIMEOUT))
  while (( SECONDS < deadline )); do
    if ! kill -0 "${start_media_pid}" 2>/dev/null; then
      harness::add_reason \
        "start-media.sh 가 먼저 끝남: $(harness::log_tail "$1")"
      return 1
    fi
    if [[ "$(harness::http_code "$(proxy::health_url)")" == 200 ]]; then
      return 0
    fi
    sleep 1
  done
  harness::add_reason \
    "기동 뒤 ${START_MEDIA_READY_TIMEOUT}초 안에 헬스 200 아님"
  return 1
}

#######################################
# stop-media.sh 를 돌린다.
# Globals:
#   start_media_env, start_media_host_dir, start_media_run_dir
# Arguments:
#   로그 파일.
#######################################
start_media::stop() {
  local status=0
  harness::run_bounded "${START_MEDIA_STOP_TIMEOUT}" "$1" \
    env "${start_media_env[@]}" \
    "${start_media_host_dir}/${STOP_MEDIA_SCRIPT_REL}" || status=$?
  if (( status != 0 )); then
    harness::add_reason \
      "stop-media.sh 종료 코드 ${status}: $(harness::log_tail "$1")"
    return 1
  fi
}

#######################################
# 실행 한 번을 정리한다 — 살아 있으면 stop-media.sh, 그래도 남으면 끝내고,
# 프로젝트를 down -v 한다.
# Globals:
#   start_media_pid, start_media_run_dir
#######################################
start_media::finish_run() {
  local log="${start_media_run_dir}/finish.log"
  if [[ -n "${start_media_pid}" ]]; then
    if kill -0 "${start_media_pid}" 2>/dev/null; then
      start_media::stop "${start_media_run_dir}/finish-stop.log" || true
      if ! harness::wait_exit "${start_media_pid}" 30; then
        harness::terminate "${start_media_pid}"
      fi
    fi
    # 이미 거둔 PID 에 신호를 보내지 않는다(번호가 다른 프로세스로 재사용될 수
    # 있다) — 남은 종료 상태만 거둔다.
    wait "${start_media_pid}" 2>/dev/null || true
    start_media_pid=""
  fi
  if ! start_media::compose down -v --remove-orphans >"${log}" 2>&1; then
    printf '경고: compose down 실패: %s\n' "$(harness::log_tail "${log}")" >&2
  fi
}

#######################################
# 중단 신호 · 비정상 종료 때 배경 start-media.sh 를 끝낸다(EXIT 트랩용).
# Globals:
#   start_media_pid
#######################################
start_media::abort() {
  [[ -n "${start_media_pid}" ]] || return 0
  if kill -0 "${start_media_pid}" 2>/dev/null; then
    harness::terminate "${start_media_pid}"
  fi
}

#######################################
# 사례 1 · 9 를 다시 본다.
# Arguments:
#   원인 앞머리(예: 재기동 뒤).
#######################################
start_media::check_live_and_health() {
  local ok=0
  if ! proxy::check_live_passthrough; then
    harness::add_reason "$1 사례 1 실패"
    ok=1
  fi
  if ! proxy::check_health; then
    harness::add_reason "$1 사례 9 실패"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 13 — stop-media.sh → start-media.sh 로 전체를 다시 띄운 뒤 사례 1 · 9 가
# 다시 통과한다.
#######################################
start_media::case_13() {
  local ok=0
  start_media::preflight || return 1
  start_media::prepare_env "${START_MEDIA_IDLE_DELAY}" || return 1
  start_media::start "${start_media_run_dir}/start-1.log"
  if ! start_media::wait_ready "${start_media_run_dir}/start-1.log"; then
    start_media::finish_run
    return 1
  fi
  start_media::check_live_and_health '재기동 전' || ok=1
  start_media::stop "${start_media_run_dir}/stop.log" || ok=1
  if ! harness::wait_exit "${start_media_pid}" 30; then
    harness::add_reason 'stop-media.sh 뒤 30초 안에 첫 start-media.sh 가 안 끝남'
    start_media::finish_run
    return 1
  fi
  start_media::start "${start_media_run_dir}/start-2.log"
  if start_media::wait_ready "${start_media_run_dir}/start-2.log"; then
    start_media::check_live_and_health '재기동 뒤' || ok=1
  else
    ok=1
  fi
  start_media::finish_run
  return "${ok}"
}

#######################################
# 사례 14(기록) — mediamtx 만 restart 한 뒤 사례 1 · 9 와 compose 상태를 적는다.
# 판정하지 않는다(README 운영 규칙 ① 의 근거 자료).
# Globals:
#   harness_record_note, harness_fail_reason, harness_waited_status
#######################################
start_media::record_14() {
  local log c1 c9 state rc=0 settle="${START_MEDIA_SETTLE_TIMEOUT}"
  start_media::preflight || return 1
  start_media::prepare_env "${START_MEDIA_IDLE_DELAY}" || return 1
  log="${start_media_run_dir}/start.log"
  start_media::start "${log}"
  if ! start_media::wait_ready "${log}"; then
    start_media::finish_run
    return 1
  fi
  start_media::compose restart mediamtx \
    >"${start_media_run_dir}/restart.log" 2>&1 || rc=$?
  sleep 5
  # proxy_resp_code 는 proxy_requests.sh 가 채운다.
  c1='통과'
  # shellcheck disable=SC2154
  proxy::check_live_passthrough || c1="실패(HTTP ${proxy_resp_code})"
  c9='통과'
  proxy::check_health || c9="실패(HTTP ${proxy_resp_code})"
  # 기록 사례라 위 두 확인의 원인 문구는 판정에 쓰지 않는다.
  harness::clear_reason
  # compose 가 중단 처리를 마칠 시간을 준 뒤 상태를 적는다.
  state="${settle}초 뒤에도 살아 있음"
  if harness::wait_exit "${start_media_pid}" "${settle}"; then
    # shellcheck disable=SC2154
    state="끝남(종료 코드 ${harness_waited_status})"
  fi
  harness_record_note="restart 종료 코드 ${rc} · 5초 뒤 사례 1 ${c1}"
  harness_record_note+=" · 사례 9 ${c9} · compose ${state} · 중단 줄"
  harness_record_note+=" $(harness::count_fixed 'Aborting on container exit' \
    "${log}" || printf '(읽기 실패) ')건"
  start_media::finish_run
}

#######################################
# 사이드카 로그를 받되, 시작마다 접속 결과 줄이 하나씩 있는 시점까지 다시
# 받는다(마지막 시작이 결과를 쓰는 중일 수 있다). 상한 안에 맞지 않으면 마지막
# 로그를 그대로 두고 판정에 넘긴다.
# Arguments:
#   로그 파일.
# Returns:
#   0 받음 / 1 조회 · 읽기 실패.
#######################################
start_media::fetch_settled_logs() {
  local deadline=$((SECONDS + START_MEDIA_RESULT_TIMEOUT)) starts results
  while :; do
    start_media::compose logs --no-color --no-log-prefix sidecar >"$1" 2>&1 \
      || return 1
    starts="$(harness::count_fixed 'SIDECAR START' "$1")" || return 1
    results="$(harness::count_fixed 'SIDECAR OWNER ' "$1")" || return 1
    if (( results >= starts || SECONDS >= deadline )); then
      return 0
    fi
    sleep 1
  done
}

#######################################
# 사례 16 한 갈래 — 사이드카 역할이 지연 뒤 종료 1 을 되풀이하는 동안 본다.
# Arguments:
#   종료 지연(예: 4s · 0s).
#######################################
start_media::observe_restarts() {
  local label="지연 $1" log logs end starts owner_ok owner_fail aborts
  local misses=0 ok=0
  start_media::prepare_env "$1" || return 1
  log="${start_media_run_dir}/start.log"
  start_media::start "${log}"
  if ! start_media::wait_ready "${log}"; then
    harness::add_reason "${label}: 기동 실패"
    start_media::finish_run
    return 1
  fi
  end=$((SECONDS + START_MEDIA_OBSERVE_WINDOW))
  while (( SECONDS < end )) && kill -0 "${start_media_pid}" 2>/dev/null; do
    if [[ "$(harness::http_code "$(proxy::health_url)")" != 200 ]]; then
      misses=$((misses + 1))
    fi
    sleep 2
  done
  logs="${start_media_run_dir}/sidecar.log"
  if ! start_media::fetch_settled_logs "${logs}"; then
    harness::add_reason "${label}: 사이드카 로그 조회 실패 — 확인 불가"
    start_media::finish_run
    return 1
  fi
  if ! starts="$(harness::count_fixed 'SIDECAR START' "${logs}")" \
    || ! owner_ok="$(harness::count_fixed 'SIDECAR OWNER OK' "${logs}")" \
    || ! owner_fail="$(harness::count_fixed 'SIDECAR OWNER FAIL' "${logs}")" \
    || ! aborts="$(harness::count_fixed 'Aborting on container exit' \
      "${log}")"; then
    harness::add_reason "${label}: 로그를 읽지 못함 — 확인 불가"
    start_media::finish_run
    return 1
  fi
  if (( starts - 1 < START_MEDIA_MIN_RESTARTS )); then
    harness::add_reason "${label}: 재시작 $((starts - 1))회(기대 \
${START_MEDIA_MIN_RESTARTS} 이상)"
    ok=1
  fi
  if (( owner_fail != 0 || owner_ok != starts )); then
    harness::add_reason "${label}: 주인 루프백 접속 성공 ${owner_ok} · 실패 \
${owner_fail}(시작 ${starts})"
    ok=1
  fi
  if (( aborts != 0 )) || ! kill -0 "${start_media_pid}" 2>/dev/null; then
    harness::add_reason "${label}: 관찰 창 안에 compose 중단(중단 줄 ${aborts}건)"
    ok=1
  fi
  if (( misses != 0 )); then
    harness::add_reason "${label}: 관찰 중 헬스 200 아님 ${misses}회"
    ok=1
  fi
  start_media::finish_run
  return "${ok}"
}

#######################################
# 사례 16 — 지연 4초형 · 즉시 종료형 두 갈래가 모두 통과해야 한다.
#######################################
start_media::case_16() {
  local ok=0
  start_media::preflight || return 1
  start_media::observe_restarts 4s || ok=1
  start_media::observe_restarts 0s || ok=1
  return "${ok}"
}

#######################################
# 사례 17 — mediamtx 역할에 SIGKILL 을 주면 30초 안에 compose 가 0 아닌 코드로
# 끝나고 프로젝트 컨테이너가 모두 선다.
# Globals:
#   harness_waited_status, start_media_project
#######################################
start_media::case_17() {
  local ok=0 status=0 running log
  start_media::preflight || return 1
  start_media::prepare_env "${START_MEDIA_IDLE_DELAY}" || return 1
  log="${start_media_run_dir}/start.log"
  start_media::start "${log}"
  if ! start_media::wait_ready "${log}"; then
    start_media::finish_run
    return 1
  fi
  if ! start_media::compose kill -s SIGKILL mediamtx \
    >"${start_media_run_dir}/kill.log" 2>&1; then
    harness::add_reason "mediamtx SIGKILL 실패: \
$(harness::log_tail "${start_media_run_dir}/kill.log")"
    start_media::finish_run
    return 1
  fi
  harness::wait_exit "${start_media_pid}" "${START_MEDIA_ABORT_TIMEOUT}" \
    || status=$?
  if (( status == 124 )); then
    harness::add_reason \
      "${START_MEDIA_ABORT_TIMEOUT}초 안에 compose 가 끝나지 않음"
    ok=1
  elif (( harness_waited_status == 0 )); then
    harness::add_reason 'compose 가 0 으로 끝남(기대: 0 아님)'
    ok=1
  fi
  if ! running="$(harness::count_output_lines harness::bounded \
    "${START_MEDIA_QUERY_TIMEOUT}" docker ps -q \
    --filter "label=com.docker.compose.project=${start_media_project}")"; then
    harness::add_reason '프로젝트 컨테이너 조회 실패 — 확인 불가'
    ok=1
  elif (( status != 124 && running != 0 )); then
    harness::add_reason "compose 가 끝난 뒤에도 컨테이너 ${running}개가 떠 있음"
    ok=1
  fi
  start_media::finish_run
  return "${ok}"
}
