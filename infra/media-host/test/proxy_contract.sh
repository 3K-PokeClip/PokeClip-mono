#!/usr/bin/env bash
#
# 앞단 프록시(nginx) 계약 시험 — 계획 M2 c1 사례 1 ~ 15 와 착수 덧붙임 사례
# 16 · 17, live 정규화 우회를 겨누는 덧붙임 사례 1b.
#
# 시험 compose(test/compose.contract.yml)를 띄운다. mediamtx 자리에는
# 에코(test/echo)가 서서 망 칸 주인 노릇을 하고(127.0.0.1 의 8888 · 9996 · 9997
# · 제어면 상류 흉내), nginx 는 prod 와 같은 렌더 함수(test/lib/render.sh —
# templatefile())로 nginx/nginx.conf.tmpl 에서 만든 설정을 받아 그 망 칸에
# 붙는다. sidecar 자리는 에코의 사이드카 역할이다. 템플릿 변수 값은
# test/render_vars.json 하나에서 온다. nginx 이미지는 제품 쪽 이미지 핀 정본
# images.json 의 nginx 항목(저장소 · 멀티 아키텍처 인덱스 다이제스트)을 쓴다 —
# prod 미러링(O7c)이 pull 하는 것과 같은 출처다.
#
# 사례 묶음:
#   - 1 ~ 12 · 1b · 15 · 8: 시험이 compose 를 직접 띄운다(up -d). 15 는
#     control_upstream 을 채운 설정으로 nginx 만 다시 만든다. 11 · 12 는 실행
#     결과에 더해, 그 결과가 기대는 구조(nginx 의 망 칸 · 공개 포트)가 prod
#     템플릿(compose.yml.tmpl)에도 같은지 본다(망 칸 구조 읽기는
#     test/lib/netns_facts.sh). 1b 와 4 · 15 의 정규화 우회 확인은
#     test/lib/normalization_cases.sh, 8 은 test/lib/nginx_log_cases.sh.
#   - 13 · 14 · 16 · 17: bin/start-media.sh · bin/stop-media.sh 로 띄우고
#     내린다(test/lib/start_media_cases.sh). 14 는 판정이 아니라 기록이다.
#
# 제품 파일(템플릿 · 스크립트)이 없으면 그 사례는 FAIL 이다. 건너뛰는 갈래는
# 없다 — 제품을 지운 트리(음성 대조)에서 이 시험이 반드시 실패해야 한다.
#
# 실험 식별: compose 프로젝트 pcbench-edge-proxy · 라벨
# com.pokeclip.bench=edge-proxy. 호스트 게시는 127.0.0.1 만이다. 끝날 때와 중단
# 신호에 이 프로젝트를 down -v 하고 임시 디렉터리를 지운다. 빌드한 에코
# 이미지(pcbench-edge-proxy/echo:local)는 다음 실행이 다시 쓰도록 남긴다. 시험
# 토큰 · 비밀은 고정 가짜 문자열이다.
#
# 사용: bash infra/media-host/test/proxy_contract.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다 — 저장소 루트에서
# 「shellcheck -x」 를 돌려도 lib/ 를 따라간다.
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
# shellcheck source=lib/render.sh
source "${TEST_DIR}/lib/render.sh"
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"
# shellcheck source=lib/proxy_requests.sh
source "${TEST_DIR}/lib/proxy_requests.sh"
# shellcheck source=lib/start_media_cases.sh
source "${TEST_DIR}/lib/start_media_cases.sh"
# shellcheck source=lib/normalization_cases.sh
source "${TEST_DIR}/lib/normalization_cases.sh"
# shellcheck source=lib/nginx_log_cases.sh
source "${TEST_DIR}/lib/nginx_log_cases.sh"
# shellcheck source=lib/netns_facts.sh
source "${TEST_DIR}/lib/netns_facts.sh"

readonly PROJECT='pcbench-edge-proxy'
readonly CONTRACT_COMPOSE="${TEST_DIR}/compose.contract.yml"
readonly ECHO_IMAGE='pcbench-edge-proxy/echo:local'
# 제품 파일(MEDIA_HOST_DIR 기준). 없으면 그 사례가 FAIL 이다.
readonly NGINX_TEMPLATE_REL='nginx/nginx.conf.tmpl'
readonly COMPOSE_TEMPLATE_REL='compose.yml.tmpl'
readonly IMAGES_FILE_REL='images.json'
# 에코(mediamtx 자리)가 망 칸 안 127.0.0.1 에 더 여는 포트 — 호스트에 게시하지
# 않는다.
readonly ECHO_CONTROL_PORT=28190
readonly ECHO_ADMIN_PORT=28199
readonly OTHER_STREAM_ID='pcbench-edge-proxy-s2'
readonly LOOPBACK_STREAM_ID='pcbench-edge-proxy-loopback'
readonly END_SIGNAL_BODY='{"ingestStreamId":"pcbench-edge-proxy-s1"}'
readonly READY_TIMEOUT=60
readonly CASE_COUNT=17

scratch=""
setup_reason=""
env_ready=0
stack_ready=0
phase_b_ready=0
phase_b_reason=""
live_port=""
playback_port=""
control_port=""
health_port=""
health_path=""
nginx_image=""

#######################################
# 준비 실패 원인에 한 마디를 더한다(사례 1 ~ 17 이 이 원인을 함께 낸다).
# Globals:
#   setup_reason
# Arguments:
#   원인.
#######################################
add_setup_reason() {
  setup_reason="${setup_reason:+${setup_reason}; }$1"
}

# 시험 compose 에 명령을 보낸다(상한 HARNESS_DOCKER_TIMEOUT — 넘기면 124).
bench_compose() {
  harness::bounded "${HARNESS_DOCKER_TIMEOUT}" \
    docker compose -p "${PROJECT}" -f "${CONTRACT_COMPOSE}" "$@"
}

compose_down() {
  local log="${scratch}/down.log"
  if ! bench_compose down -v --remove-orphans >"${log}" 2>&1; then
    printf '경고: compose down 실패: %s\n' "$(harness::log_tail "${log}")" >&2
  fi
}

#######################################
# 정리(EXIT 트랩 — INT · TERM 도 exit 로 여기 온다). 잔여 컨테이너를 조회하지
# 못하면 「잔여 확인 불가」 를 내고, 종료 코드가 0 이었으면 1 로 끝낸다.
# Globals:
#   scratch, env_ready
#######################################
cleanup() {
  local status=$? left
  start_media::abort
  harness::end_last_group
  if (( env_ready )); then
    compose_down
    if left="$(harness::count_output_lines harness::bounded \
      "${HARNESS_DOCKER_TIMEOUT}" docker ps -aq \
      --filter "label=com.docker.compose.project=${PROJECT}")"; then
      printf '정리: 프로젝트 %s 컨테이너 잔여 %s\n' "${PROJECT}" "${left}" >&2
    else
      printf '정리: 프로젝트 %s 컨테이너 잔여 확인 불가(docker 조회 실패)\n' \
        "${PROJECT}" >&2
      if (( status == 0 )); then
        status=1
      fi
    fi
  fi
  if [[ -n "${scratch}" ]]; then
    rm -rf -- "${scratch:?}"
  fi
  exit "${status}"
}

#######################################
# render_vars.json 에서 포트 하나를 읽는다(1024..65535 정수).
# Arguments:
#   키.
# Outputs:
#   포트.
#######################################
read_port_var() {
  local value
  value="$(jq -r --arg k "$1" '.[$k] // empty' "${RENDER_VARS_FILE}")" \
    || return 1
  [[ "${value}" =~ ^[0-9]+$ ]] || return 1
  (( value >= 1024 && value <= 65535 )) || return 1
  printf '%s' "${value}"
}

#######################################
# 시험이 쓰는 템플릿 변수(포트 넷 · 헬스 경로)를 읽는다.
# Globals:
#   live_port, playback_port, control_port, health_port, health_path
#######################################
load_render_vars() {
  local ok=0
  live_port="$(read_port_var proxy_live_port)" || ok=1
  playback_port="$(read_port_var proxy_playback_port)" || ok=1
  control_port="$(read_port_var proxy_control_port)" || ok=1
  health_port="$(read_port_var proxy_health_port)" || ok=1
  if (( ok )); then
    add_setup_reason \
      'render_vars.json 의 proxy_*_port 가 1024..65535 정수가 아님'
  fi
  health_path="$(jq -r '.media_health_path // empty' "${RENDER_VARS_FILE}")" \
    || health_path=""
  if [[ "${health_path}" != /* ]]; then
    add_setup_reason 'render_vars.json 의 media_health_path 가 / 로 시작 안 함'
    ok=1
  fi
  proxy::configure "${live_port}" "${playback_port}" "${control_port}" \
    "${health_port}" "${health_path}" "${scratch}"
  return "${ok}"
}

#######################################
# nginx 이미지 참조(<저장소>@<다이제스트>)를 images.json 의 nginx 항목에서
# 만든다. 항목의 source 는 <저장소>:<태그>, digest 는 그 태그의 멀티 아키텍처
# 인덱스 다이제스트다.
# Globals:
#   nginx_image
#######################################
load_nginx_image() {
  local file="${MEDIA_HOST_DIR}/${IMAGES_FILE_REL}" src digest
  local src_re='^[a-z0-9.-]+(/[a-z0-9._-]+)+:[A-Za-z0-9._-]+$'
  local digest_re='^sha256:[0-9a-f]{64}$'
  if [[ ! -f "${file}" ]]; then
    add_setup_reason "${IMAGES_FILE_REL} 없음(c2 몫)"
    return 1
  fi
  src="$(jq -r '.nginx.source // empty' "${file}")" || src=""
  digest="$(jq -r '.nginx.digest // empty' "${file}")" || digest=""
  if [[ ! "${src}" =~ ${src_re} ]]; then
    add_setup_reason \
      "${IMAGES_FILE_REL} 의 nginx.source 가 <저장소>:<태그> 꼴이 아님"
    return 1
  fi
  if [[ ! "${digest}" =~ ${digest_re} ]]; then
    add_setup_reason \
      "${IMAGES_FILE_REL} 의 nginx.digest 가 sha256 다이제스트가 아님"
    return 1
  fi
  nginx_image="${src%:*}@${digest}"
}

check_tools() {
  local ok=0 tool
  for tool in docker jq curl terraform base64 awk; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      add_setup_reason "도구 없음: ${tool}"
      ok=1
    fi
  done
  if (( ! ok )) && ! harness::bounded "${HARNESS_DOCKER_TIMEOUT}" \
    docker compose version --short >/dev/null 2>&1; then
    add_setup_reason 'docker compose 를 쓸 수 없음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 이전 실행 잔여 · 포트 점유를 본다. 남의 것은 건드리지 않고 멈춘다. 잔여를
# 조회하지 못해도 멈춘다(확인 불가).
#######################################
check_prestate() {
  local ok=0 port leftover
  if ! leftover="$(harness::count_output_lines harness::bounded \
    "${HARNESS_DOCKER_TIMEOUT}" docker ps -aq \
    --filter "label=com.docker.compose.project=${PROJECT}")"; then
    add_setup_reason '이전 실행 잔여 컨테이너 조회 실패 — 확인 불가'
    ok=1
  elif (( leftover != 0 )); then
    add_setup_reason "이전 실행 잔여 컨테이너 있음(프로젝트 ${PROJECT} — \
down -v 로 정리 뒤 다시)"
    ok=1
  fi
  for port in "${live_port}" "${playback_port}" "${control_port}" \
    "${health_port}"; do
    if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
      add_setup_reason "호스트 127.0.0.1:${port} 를 이미 누가 듣고 있음"
      ok=1
    fi
  done
  return "${ok}"
}

build_echo_image() {
  local log="${scratch}/echo-build.log"
  if ! harness::run_bounded "${HARNESS_DOCKER_TIMEOUT}" "${log}" \
    docker build -t "${ECHO_IMAGE}" "${TEST_DIR}/echo"; then
    add_setup_reason "에코 이미지 빌드 실패: $(harness::log_tail "${log}")"
    return 1
  fi
}

#######################################
# nginx 설정을 렌더한다. 실패 원인은 표준 출력에 낸다.
# Arguments:
#   변수 JSON 파일, 출력 파일.
#######################################
render_nginx_conf() {
  if [[ ! -f "${MEDIA_HOST_DIR}/${NGINX_TEMPLATE_REL}" ]]; then
    printf '%s 없음(c2 몫)' "${NGINX_TEMPLATE_REL}"
    return 1
  fi
  if ! render::template "${MEDIA_HOST_DIR}/${NGINX_TEMPLATE_REL}" "$1" "$2" \
    2>"$2.err"; then
    printf '%s 렌더 실패: %s' "${NGINX_TEMPLATE_REL}" \
      "$(harness::log_tail "$2.err")"
    return 1
  fi
}

#######################################
# 시험 compose 가 읽는 값(이미지 · nginx 설정 · 포트 · 사이드카 종료 지연)을
# export 하고, 직접 기동 때 mediamtx · sidecar 자리가 bind 하는 비밀 없는 자리
# 파일을 만든다. 사례 13 · 14 · 16 · 17 의 start-media.sh 도 이 값을 그대로
# 물려받는다.
# Globals:
#   PCBENCH_*, RUNTIME_DIR, scratch, nginx_image, live_port, playback_port,
#   control_port, health_port
#######################################
export_compose_env() {
  export PCBENCH_ECHO_IMAGE="${ECHO_IMAGE}"
  export PCBENCH_NGINX_IMAGE="${nginx_image}"
  export PCBENCH_NGINX_CONF="${scratch}/nginx-a.conf"
  export PCBENCH_LIVE_PORT="${live_port}"
  export PCBENCH_PLAYBACK_PORT="${playback_port}"
  export PCBENCH_CONTROL_PORT="${control_port}"
  export PCBENCH_HEALTH_PORT="${health_port}"
  export PCBENCH_ECHO_CONTROL_PORT="${ECHO_CONTROL_PORT}"
  export PCBENCH_ECHO_ADMIN_PORT="${ECHO_ADMIN_PORT}"
  export PCBENCH_SIDECAR_EXIT_DELAY="${START_MEDIA_IDLE_DELAY}"
  # 직접 띄울 때 mediamtx · sidecar 자리가 받는 비밀 없는 자리 파일(에코는
  # 읽지 않는다).
  export RUNTIME_DIR="${scratch}/runtime-direct"
  mkdir -p -- "${RUNTIME_DIR}"
  printf '# proxy_contract 직접 기동용 자리 파일\n' \
    >"${RUNTIME_DIR}/mediamtx.yml"
  printf 'proxy-contract-placeholder\n' >"${RUNTIME_DIR}/mtx_api_pass"
}

#######################################
# 사례 1 ~ 12 의 스택을 시험이 직접(up -d) 띄우고 헬스가 200 을 낼 때까지
# 기다린다. 실패 원인은 setup_reason 에 더한다.
# Globals:
#   scratch, setup_reason
#######################################
start_direct_stack() {
  local up_log="${scratch}/up-direct.log" nginx_log="${scratch}/nginx-boot.log"
  if ! bench_compose up -d >"${up_log}" 2>&1; then
    add_setup_reason "compose up 실패: $(harness::log_tail "${up_log}")"
    return 1
  fi
  if ! harness::wait_http_200 "$(proxy::health_url)" "${READY_TIMEOUT}"; then
    save_nginx_logs "${nginx_log}" || true
    add_setup_reason "헬스가 ${READY_TIMEOUT}초 안에 200 아님 — nginx: \
$(harness::log_tail "${nginx_log}")"
    return 1
  fi
}

#######################################
# 사례 1 ~ 12 의 스택을 준비한다. 실패 원인은 setup_reason 에 모은다.
# Globals:
#   setup_reason, env_ready, stack_ready
#######################################
setup_stack() {
  local why
  check_tools || return 1
  load_render_vars || true
  load_nginx_image || true
  build_echo_image || true
  if ! why="$(render_nginx_conf "${RENDER_VARS_FILE}" \
    "${scratch}/nginx-a.conf")"; then
    add_setup_reason "${why}"
  fi
  [[ -z "${setup_reason}" ]] || return 1
  check_prestate || return 1
  export_compose_env
  env_ready=1
  start_direct_stack || return 1
  stack_ready=1
}

require_stack() {
  if (( stack_ready )); then
    return 0
  fi
  harness::add_reason "준비 실패 — ${setup_reason:-원인 미기록}"
  return 1
}

save_nginx_logs() {
  bench_compose logs --no-color --no-log-prefix nginx >"$1" 2>&1
}

#######################################
# 에코의 모든 포트 호출 수 합을 낸다.
# Outputs:
#   합(정수).
#######################################
echo_calls_total() {
  bench_compose exec -T mediamtx /echo fetch \
    "http://127.0.0.1:${ECHO_ADMIN_PORT}/counts" 2>/dev/null \
    | jq -e '[.[]] | add // 0'
}

#######################################
# GET 요청 묶음이 모두 기대 코드이고 에코 호출 수가 늘지 않았는지 본다. 요청
# 대상은 손대지 않고 그대로 보낸다(proxy::get_raw).
# Arguments:
#   기대 코드, 기준 URL, 요청 대상(경로 · 쿼리 또는 절대 URI) …
#######################################
expect_blocked_without_upstream() {
  local want="$1" base="$2" before after ok=0 target
  shift 2
  if ! before="$(echo_calls_total)"; then
    harness::add_reason '에코 호출 수를 읽지 못함'
    return 1
  fi
  for target in "$@"; do
    proxy::get_raw "${base}" "${target}"
    proxy::expect_code "${want}" || ok=1
  done
  if ! after="$(echo_calls_total)"; then
    harness::add_reason '에코 호출 수를 읽지 못함'
    return 1
  fi
  if (( after != before )); then
    harness::add_reason "상류 호출 $((after - before))건(기대 0)"
    ok=1
  fi
  return "${ok}"
}

check_case_1() { require_stack && proxy::check_live_passthrough; }

check_case_2() {
  local ok=0 path="/live/${PROXY_STREAM_ID}/index.m3u8"
  require_stack || return 1
  proxy::request GET "$(proxy::base_url live)${path}"
  proxy::expect_code 200 || return 1
  proxy::expect_field .listener '127.0.0.1:8888' '상류' || ok=1
  proxy::expect_field .path "${path}" '경로' || ok=1
  return "${ok}"
}

check_case_3() {
  local ok=0 id="${PROXY_STREAM_ID}"
  require_stack || return 1
  proxy::request GET "$(proxy::base_url playback)/${id}/list?path=${id}"
  proxy::expect_code 200 || return 1
  proxy::expect_field .listener '127.0.0.1:9996' '상류' || ok=1
  proxy::expect_field .path '/list' '경로' || ok=1
  proxy::expect_field .raw_query "path=${id}" '쿼리' || ok=1
  return "${ok}"
}

check_case_4() {
  local id="${PROXY_STREAM_ID}" other="${OTHER_STREAM_ID}" get_query ok=0 base
  require_stack || return 1
  base="$(proxy::base_url playback)"
  get_query="path=${other}&start=2026-10-17T00%3A00%3A00Z&duration=1"
  expect_blocked_without_upstream 403 "${base}" \
    "/${id}/list?path=${other}" "/${id}/get?${get_query}&format=mp4" || ok=1
  normalization::check_playback "${base}" "${id}" "${other}" || ok=1
  return "${ok}"
}

check_case_5() {
  local id="${PROXY_STREAM_ID}"
  require_stack || return 1
  # 인코딩한 키(pa%74h)도 중복이다 — MediaMTX 는 키를 풀어 읽으므로 pa%74h 도
  # path 이고, 첫 값(다른 번호)을 쓴다. 값 없는 path 도 빈 값의 path 다.
  expect_blocked_without_upstream 403 "$(proxy::base_url playback)" \
    "/${id}/list" "/${id}/list?path=" "/${id}/list?path=${id}&path=${id}" \
    "/${id}/list?path=${id}&path=${OTHER_STREAM_ID}" \
    "/${id}/list?pa%74h=${OTHER_STREAM_ID}&path=${id}" \
    "/${id}/list?path&path=${id}" "/${id}/list?path=${id}&path"
}

check_case_6() {
  local ok=0 id="${PROXY_STREAM_ID}" query
  require_stack || return 1
  # %3A · %2B 를 그대로 넘겨야 MediaMTX 가 시각의 + 를 공백으로 읽지 않는다.
  query="path=${id}&start=2026-10-17T09%3A00%3A00%2B09%3A00"
  query="${query}&duration=60&format=mp4"
  proxy::request GET "$(proxy::base_url playback)/${id}/get?${query}"
  proxy::expect_code 200 || return 1
  proxy::expect_field .listener '127.0.0.1:9996' '상류' || ok=1
  proxy::expect_field .path '/get' '경로' || ok=1
  proxy::expect_field .raw_query "${query}" '쿼리' || ok=1
  return "${ok}"
}

check_case_7() {
  local ok=0 before after target base
  require_stack || return 1
  base="$(proxy::base_url control)"
  if ! before="$(echo_calls_total)"; then
    harness::add_reason '에코 호출 수를 읽지 못함'
    return 1
  fi
  proxy::request POST "${base}/api/ingest/end-signal" "${END_SIGNAL_BODY}"
  proxy::expect_code 404 || ok=1
  proxy::expect_no_cache_control || ok=1
  proxy::request GET "${base}/api/streams/${PROXY_STREAM_ID}/rewind"
  proxy::expect_code 404 || ok=1
  proxy::expect_no_cache_control || ok=1
  for target in / /api/other /v3/paths/list; do
    proxy::request GET "${base}${target}"
    proxy::expect_code 404 || ok=1
  done
  if ! after="$(echo_calls_total)"; then
    harness::add_reason '에코 호출 수를 읽지 못함'
    return 1
  fi
  if (( after != before )); then
    harness::add_reason "상류 호출 $((after - before))건(기대 0)"
    ok=1
  fi
  return "${ok}"
}

check_case_1b() {
  require_stack || return 1
  normalization::check_live "$(proxy::base_url live)" "${PROXY_STREAM_ID}" \
    "${OTHER_STREAM_ID}"
}

check_case_9() { require_stack && proxy::check_health; }

check_case_10() {
  local ok=0
  require_stack || return 1
  proxy::request GET \
    "$(proxy::base_url live)/${LOOPBACK_STREAM_ID}/index.m3u8"
  proxy::expect_code 200 || return 1
  proxy::expect_field .listener '127.0.0.1:8888' '상류' || ok=1
  return "${ok}"
}

check_case_11() {
  local ok=0 remote
  if require_netns; then
    expect_netns .nginx '"service:mediamtx"' 'nginx network_mode' || ok=1
  else
    ok=1
  fi
  require_stack || return 1
  proxy::request GET "$(proxy::base_url live)/${PROXY_STREAM_ID}/index.m3u8"
  proxy::expect_code 200 || return 1
  proxy::expect_field .listener '127.0.0.1:8888' '상류' || ok=1
  remote="$(jq -r '.remote_addr' "${proxy_resp_body}" 2>/dev/null)" \
    || remote=""
  if [[ "${remote}" != 127.0.0.1:* ]]; then
    harness::add_reason "상류가 본 nginx 출발지가 루프백이 아님(${remote:-없음})"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 탐침 출력에 한 줄이 정확히 한 번 나오는지 본다.
# Arguments:
#   탐침 출력, 찾을 줄 머리(예: "OPEN 127.0.0.1:8888").
#######################################
probe_line_once() {
  [[ "$(printf '%s\n' "$1" | grep -c -F -- "$2" || true)" == 1 ]]
}

check_case_12() {
  local ok=0 out port
  if require_netns; then
    expect_netns .publishers '["mediamtx"]' '포트를 게시하는 서비스' || ok=1
    expect_netns .loopback_published '[]' '게시된 8888 · 9996 · 9997' \
      || ok=1
  else
    ok=1
  fi
  require_stack || return 1
  # 양성 대조 1: 같은 망 칸 안에서는 세 포트가 열려 있다 — 닫힌 까닭이
  # 바인딩임을 보인다.
  # 탐침은 주소마다 OPEN · CLOSED 를 내고 0 으로 끝난다 — 0 아닌 종료는 탐침
  # 실행 자체의 실패라 빈 출력을 「닫힘」 으로 읽지 않는다.
  if ! out="$(bench_compose exec -T mediamtx /echo probe 127.0.0.1:8888 \
    127.0.0.1:9996 127.0.0.1:9997 2>/dev/null)"; then
    harness::add_reason '망 칸 안 탐침 실행 실패 — 확인 불가'
    return 1
  fi
  for port in 8888 9996 9997; do
    if ! probe_line_once "${out}" "OPEN 127.0.0.1:${port}"; then
      harness::add_reason "망 칸 안에서 127.0.0.1:${port} 가 닫힘(양성 대조)"
      ok=1
    fi
  done
  # 다른 bridge 컨테이너. 양성 대조 2: 같은 망에서 nginx live 포트는 닿는다.
  if ! out="$(bench_compose run --rm --no-deps -T probe probe -timeout=2s \
    mediamtx:8888 mediamtx:9996 mediamtx:9997 "mediamtx:${live_port}" \
    2>/dev/null)"; then
    harness::add_reason 'bridge 컨테이너 탐침 실행 실패 — 확인 불가'
    return 1
  fi
  if ! probe_line_once "${out}" "OPEN mediamtx:${live_port}"; then
    harness::add_reason \
      "bridge 컨테이너에서 mediamtx:${live_port} 에 닿지 않음(양성 대조)"
    ok=1
  fi
  for port in 8888 9996 9997; do
    if ! probe_line_once "${out}" "CLOSED mediamtx:${port}"; then
      harness::add_reason "bridge 컨테이너에서 mediamtx:${port} 가 열려 있음"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# control_upstream 을 에코로 채운 설정으로 nginx 만 다시 만든다(사례 15 준비).
# 그 전에 지금 nginx 의 로그를 모은다 — 다시 만들면 컨테이너와 함께 사라진다.
# Globals:
#   phase_b_ready, phase_b_reason, PCBENCH_NGINX_CONF
#######################################
switch_control_upstream() {
  local vars_b="${scratch}/render_vars.b.json" why
  if (( ! stack_ready )); then
    phase_b_reason="준비 실패 — ${setup_reason:-원인 미기록}"
    return 1
  fi
  collect_nginx_logs "${scratch}/nginx-a.log" '사례 1 · 7 단계'
  jq --arg u "127.0.0.1:${ECHO_CONTROL_PORT}" '.control_upstream = $u' \
    "${RENDER_VARS_FILE}" >"${vars_b}"
  if ! why="$(render_nginx_conf "${vars_b}" "${scratch}/nginx-b.conf")"; then
    phase_b_reason="control_upstream 을 채운 렌더 — ${why}"
    return 1
  fi
  # 이 값은 되돌리지 않는다 — 뒤의 사례 13 · 14 · 16 · 17 의 nginx 도 이
  # 설정(control_upstream 채움)으로 뜬다.
  export PCBENCH_NGINX_CONF="${scratch}/nginx-b.conf"
  if ! bench_compose up -d --no-deps --force-recreate nginx \
    >"${scratch}/up-b.log" 2>&1; then
    phase_b_reason="nginx 다시 만들기 실패: \
$(harness::log_tail "${scratch}/up-b.log")"
    return 1
  fi
  if ! harness::wait_http_200 "$(proxy::health_url)" "${READY_TIMEOUT}"; then
    save_nginx_logs "${scratch}/nginx-b-boot.log" || true
    phase_b_reason="다시 만든 nginx 가 200 아님: \
$(harness::log_tail "${scratch}/nginx-b-boot.log")"
    return 1
  fi
  phase_b_ready=1
}

check_case_15() {
  local ok=0 base path
  if (( ! phase_b_ready )); then
    harness::add_reason "${phase_b_reason:-준비 안 됨}"
    return 1
  fi
  base="$(proxy::base_url control)"
  proxy::request POST "${base}/api/ingest/end-signal" "${END_SIGNAL_BODY}"
  if proxy::expect_code 200; then
    proxy::expect_field .listener "127.0.0.1:${ECHO_CONTROL_PORT}" '상류' \
      || ok=1
    proxy::expect_field .path '/api/ingest/end-signal' '경로' || ok=1
    proxy::expect_field .method POST '메서드' || ok=1
    proxy::expect_field .body "${END_SIGNAL_BODY}" '본문' || ok=1
    proxy::expect_auth || ok=1
  else
    ok=1
  fi
  path="/api/streams/${PROXY_STREAM_ID}/rewind"
  proxy::request GET "${base}${path}"
  if proxy::expect_code 200; then
    proxy::expect_field .listener "127.0.0.1:${ECHO_CONTROL_PORT}" '상류' \
      || ok=1
    proxy::expect_field .path "${path}" '경로' || ok=1
    proxy::expect_field .method GET '메서드' || ok=1
    proxy::expect_auth || ok=1
  else
    ok=1
  fi
  # 상류를 채워도 제어면 포트의 그 밖 경로는 404 다(관리 API 를 열지 않는다).
  proxy::request GET "${base}/v3/paths/list"
  proxy::expect_code 404 || ok=1
  normalization::check_control "${base}" || ok=1
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/proxy_contract.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy.XXXXXX")"

  setup_stack || true
  harness::run_case 9 '헬스 포트 200' check_case_9
  harness::run_case 1 'live — 경로 · 쿼리 · Authorization 그대로 8888 로' \
    check_case_1
  harness::run_case 2 'live — /live 접두사를 지우지 않음' check_case_2
  harness::run_case 1b \
    'live 정규화 우회 원문 → 400 · 상류 0, LL-HLS 요청은 원문 그대로' \
    check_case_1b
  harness::run_case 3 'playback — /{id}/list?path={id} 가 9996 /list 로' \
    check_case_3
  harness::run_case 4 \
    'playback 번호 불일치 · 정규화 우회 원문 → 403 · 상류 호출 0' check_case_4
  harness::run_case 5 'path 없음 · 빈 값 · 중복 → 403 · 상류 호출 0' check_case_5
  harness::run_case 6 'playback /get — 쿼리 원문 보존' check_case_6
  harness::run_case 7 \
    '제어면(상류 빔) → 404 · 상류 호출 0 · Cache-Control 없음' check_case_7
  harness::run_case 10 '루프백 점검 경로 200' check_case_10
  harness::run_case 11 'nginx 가 127.0.0.1:8888 에 닿음(망 칸 공유)' \
    check_case_11
  harness::run_case 12 '다른 bridge 컨테이너에서 8888 · 9996 · 9997 닫힘' \
    check_case_12
  if (( stack_ready )); then
    send_error_log_probe || true
  fi
  switch_control_upstream || true
  harness::run_case 15 \
    '제어면(상류 = 에코) → 그대로 전달 · 정규화 우회 원문은 400' check_case_15
  if (( phase_b_ready )); then
    collect_nginx_logs "${scratch}/nginx-b.log" '사례 15 단계'
  fi
  harness::run_case 8 'nginx 로그에 시험 토큰 0건 · 접근 로그 경로는 원문' \
    check_case_8
  if (( env_ready )); then
    compose_down
  fi

  start_media::configure "${PROJECT}" "${CONTRACT_COMPOSE}" \
    "${MEDIA_HOST_DIR}" "${scratch}" "${env_ready}" "${setup_reason}"
  harness::run_case 13 \
    'stop-media.sh → start-media.sh 전체 재기동 뒤 사례 1 · 9 재통과' \
    start_media::case_13
  harness::run_record 14 'mediamtx 단독 재시작 관찰(기록)' \
    start_media::record_14
  harness::run_case 16 \
    '사이드카 종료 1 반복 — 엔진 재시작 · 망 칸 재합류 · compose 유지' \
    start_media::case_16
  harness::run_case 17 'mediamtx SIGKILL → compose 0 아닌 종료 · 전체 정지' \
    start_media::case_17

  if ! harness::summary "${CASE_COUNT}" 1b; then
    exit 1
  fi
}

# 자체 시험(query_guard_selftest.sh)이 source 하면 함수만 싣고 돌지 않는다.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi
main "$@"
