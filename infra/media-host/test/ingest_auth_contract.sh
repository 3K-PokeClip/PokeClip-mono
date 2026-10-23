#!/usr/bin/env bash
#
# 송출 인증 계약 시험(M15 c1 — 로컬 실행 · CI 상설 아님) — C0 설정 수용 탐침과
# 세 층의 사례.
#
#   C0  실물 MediaMTX 가 `authMethod: http` + `authInternalUsers: []` 최소
#       설정을 받아 기동하고 설정 조회가 되는지.
#   L   옛 모드(회귀 기준선) — 운영 템플릿(mediamtx.yml.tmpl) 렌더 그대로.
#       L1 익명 송출 · L2 Bearer 없는 재생목록 401 / CDN Bearer 200 · L3
#       사이드카 자격 관리 API.
#   A   응답기 모드 설정 + 가짜 응답기(에코 authstub) — C1 ~ C8 · C10.
#   B   응답기 모드 설정 + 실물 응답기(media/Dockerfile.ingest-auth) + 가짜
#       4C(에코 resolvestub) — C9 · C11 ~ C19.
#   T1  두 모드 렌더본의 토큰이 각각 정확히 한 번이고 채운 뒤 `@@` 가 남지
#       않는지(시작 스크립트의 「토큰마다 정확히 한 번」 불변식). 준비 관문이
#       아니라 사례다 — 개수가 어긋나도 채워서 층을 띄운다.
#
# 이미지는 모두 이 트리에서 로컬 빌드한다(MediaMTX = media/Dockerfile.mtxhook ·
# 응답기 = media/Dockerfile.ingest-auth · 에코 = test/echo). 송출기는 images.json
# 의 loopback_sender 핀이다(로컬에 없으면 받는다). compose 는
# test/compose.ingest-auth.yml 이다.
#
# 두 모드의 MediaMTX 설정은 운영 템플릿(mediamtx.yml.tmpl) 렌더다 — 옛 모드는
# render_vars.json 그대로, 응답기 모드는 ingest_auth_image 만 채운 사본이다.
# 응답기 모드 렌더가 실패하면 층 A · B 만 준비 실패이고 C0 · 층 L 은 돈다(옛
# 모드 렌더 실패는 층 L 만 막는다). 시험 compose 의 응답기 서비스(env · 마운트
# 대상 · healthcheck 등)는 render_test 사례 44 가 운영 compose 렌더본과
# 대조한다. 운영 스크립트 사례(C13 · C15 · C18)는 bin/ 의 실물 스크립트를
# 시험 이음매(lib 의 ia::configure_ops — 가짜 SSM · 기록만 하는 chown · docker
# logs 저널)로 돌린다. compose 파일은 scratch 사본이다 — 응답기 env_file 이
# compose 파일 옆 ingest-auth.env(운영 스크립트의 규칙)이게. 거부 목록은 scratch
# 의 호스트 디렉터리를 bind 한다. 스크립트가 없으면 「없음」 으로 실패한다.
# 건너뛰는 갈래는 없다.
#
# 실험 식별: compose 프로젝트 pcbench-m15-ingest-auth · 라벨
# com.pokeclip.bench=m15-ingest-auth(컨테이너 · 망 · 볼륨). 호스트 포트 게시 0.
# 끝날 때와 중단 신호에 이 프로젝트를 down -v 하고 남은 일회성 컨테이너 · 임시
# 디렉터리를 지운다. 빌드한 이미지(pcbench-m15-ingest-auth/*:local)는 다음
# 실행이 다시 쓰도록 남긴다. 비밀은 고정 가짜 문자열이다(lib).
#
# INGEST_AUTH_CONTRACT_LOG_DIR 를 주면 층마다 compose 로그를 그 디렉터리에
# 남긴다(가짜 비밀은 가린다).
#
# 사용: bash infra/media-host/test/ingest_auth_contract.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# shellcheck source-path=SCRIPTDIR

set -euo pipefail

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly TEST_DIR
MEDIA_HOST_DIR="$(cd -- "${TEST_DIR}/.." && pwd)"
readonly MEDIA_HOST_DIR
MEDIA_DIR="$(cd -- "${MEDIA_HOST_DIR}/../../media" && pwd)"
readonly MEDIA_DIR
# shellcheck source=lib/render.sh
source "${TEST_DIR}/lib/render.sh"
# shellcheck source=lib/harness.sh
source "${TEST_DIR}/lib/harness.sh"
# shellcheck source=lib/ingest_auth_cases.sh
source "${TEST_DIR}/lib/ingest_auth_cases.sh"

readonly PROJECT='pcbench-m15-ingest-auth'
readonly BENCH_LABEL='com.pokeclip.bench=m15-ingest-auth'
readonly IA_COMPOSE="${TEST_DIR}/compose.ingest-auth.yml"
readonly MTX_IMAGE="${PROJECT}/mediamtx:local"
readonly INGEST_AUTH_IMAGE="${PROJECT}/ingest-auth:local"
readonly ECHO_IMAGE="${PROJECT}/echo:local"
readonly HELPER_IMAGE='alpine:3.22'
# 응답기 모드 렌더의 ingest_auth_image(MediaMTX 설정은 빈 값인지만 본다 — 실제
# 실행 이미지는 INGEST_AUTH_IMAGE).
readonly INGEST_AUTH_IMAGE_REF="registry.pcbench-m15.invalid/media-ingest-auth@\
sha256:4444444444444444444444444444444444444444444444444444444444444444"
readonly MTX_TEMPLATE_REL='mediamtx.yml.tmpl'
# 이미지 빌드 한 번의 상한(초) — 첫 빌드는 모듈 받기를 품는다.
readonly BUILD_TIMEOUT=600
# MediaMTX 관리 API 가 답할 때까지 · 응답기가 healthy 가 될 때까지(초).
readonly MTX_READY_TIMEOUT=30
readonly RESPONDER_READY_TIMEOUT=60

scratch=""
setup_reason=""
env_ready=0
mtx_built=0
ia_built=0
ia_build_reason=""
sender_image=""
# 시험 compose 의 scratch 사본(export_compose_env 가 만든다).
ia_compose_copy=""
# 응답기 /etc/ingest-auth 에 bind 하는 거부 목록 호스트 디렉터리.
deny_dir=""
# 층별 준비 실패 원인 — 옛 모드 렌더(층 L) · 응답기 모드 렌더(층 A · B).
legacy_reason=""
auth_reason=""
# 토큰 채우기 기록(T1) — 줄마다 「모드 토큰 개수」 · 「모드 @@남음 0|1」.
token_counts=""

add_setup_reason() {
  setup_reason="${setup_reason:+${setup_reason}; }$1"
}

# 모든 프로필의 서비스를 대상으로 compose 명령을 보낸다.
compose_all() {
  ia::compose --profile stub --profile real --profile tools "$@"
}

#######################################
# 이 프로젝트의 컨테이너(일회성 run 포함)를 모두 지우고 볼륨 · 망을 내린다.
#######################################
stack_down() {
  local ids
  compose_all down -v --remove-orphans >"${scratch}/down.log" 2>&1 \
    || printf '경고: compose down 실패: %s\n' \
      "$(harness::log_tail "${scratch}/down.log")" >&2
  ids="$(docker ps -aq --filter "label=com.docker.compose.project=${PROJECT}" \
    2>/dev/null)" || ids=""
  if [[ -n "${ids}" ]]; then
    # shellcheck disable=SC2086 # ID 목록을 낱낱의 인자로 넘긴다.
    docker rm -f ${ids} >/dev/null 2>&1 || true
    compose_all down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}

#######################################
# 정리(EXIT 트랩). 잔여를 조회하지 못하면 「확인 불가」 를 내고, 종료 코드가
# 0 이었으면 1 로 끝낸다.
# Globals:
#   scratch, env_ready
#######################################
cleanup() {
  local status=$? left
  harness::end_last_group
  if (( env_ready )); then
    stack_down
    if left="$(harness::count_output_lines docker ps -aq \
      --filter "label=${BENCH_LABEL}")"; then
      printf '정리: 라벨 %s 컨테이너 잔여 %s\n' "${BENCH_LABEL}" "${left}" >&2
    else
      printf '정리: 라벨 %s 컨테이너 잔여 확인 불가\n' "${BENCH_LABEL}" >&2
      (( status != 0 )) || status=1
    fi
  fi
  if [[ -n "${scratch}" ]]; then
    rm -rf -- "${scratch:?}"
  fi
  exit "${status}"
}

check_tools() {
  local ok=0 tool
  for tool in docker jq terraform base64 tr; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      add_setup_reason "도구 없음: ${tool}"
      ok=1
    fi
  done
  return "${ok}"
}

# 이전 실행 잔여(이 라벨)를 본다. 남의 것은 건드리지 않고 멈춘다.
check_prestate() {
  local leftover
  if ! leftover="$(harness::count_output_lines docker ps -aq \
    --filter "label=${BENCH_LABEL}")"; then
    add_setup_reason '이전 실행 잔여 조회 실패 — 확인 불가'
    return 1
  fi
  if (( leftover != 0 )); then
    add_setup_reason "이전 실행 잔여 컨테이너 ${leftover}개(라벨 ${BENCH_LABEL})"
    return 1
  fi
}

#######################################
# 이미지 하나를 빌드한다. 실패 원인은 setup 이 아니라 그 층의 원인으로 남긴다.
# Arguments:
#   태그, 컨텍스트, Dockerfile(컨텍스트 기준 — 빈 값이면 기본).
# Outputs:
#   실패하면 원인 한 줄.
#######################################
build_image() {
  local log="${scratch}/build-${1//[^a-z]/_}.log"
  local args=(build --label "${BENCH_LABEL}" -t "$1")
  if [[ -n "$3" ]]; then
    args+=(-f "$2/$3")
  fi
  if ! harness::run_bounded "${BUILD_TIMEOUT}" "${log}" docker "${args[@]}" \
    "$2"; then
    printf '%s 빌드 실패: %s' "$1" "$(harness::log_tail "${log}")"
    return 1
  fi
}

#######################################
# 송출기 이미지(images.json 의 loopback_sender 핀)를 정하고, 없으면 받는다.
# Globals:
#   sender_image
#######################################
load_sender_image() {
  local file="${MEDIA_HOST_DIR}/images.json" src digest
  src="$(jq -r '.loopback_sender.source // empty' "${file}" 2>/dev/null)" \
    || src=""
  digest="$(jq -r '.loopback_sender.digest // empty' "${file}" 2>/dev/null)" \
    || digest=""
  if [[ ! "${src}" =~ :[A-Za-z0-9._-]+$ \
    || ! "${digest}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    add_setup_reason 'images.json 의 loopback_sender 핀을 읽지 못함'
    return 1
  fi
  sender_image="${src%:*}@${digest}"
  if docker image inspect "${sender_image}" >/dev/null 2>&1; then
    return 0
  fi
  if ! harness::run_bounded "${BUILD_TIMEOUT}" "${scratch}/pull.log" \
    docker pull "${sender_image}"; then
    add_setup_reason "송출기 이미지 받기 실패: \
$(harness::log_tail "${scratch}/pull.log")"
    return 1
  fi
}

#######################################
# 이미지 셋을 빌드한다. MediaMTX · 에코가 없으면 모든 층이, 응답기가 없으면
# 층 B 만 준비 실패다.
# Globals:
#   mtx_built, ia_built, ia_build_reason
#######################################
build_images() {
  local why
  if why="$(build_image "${ECHO_IMAGE}" "${TEST_DIR}/echo" '')" \
    && why="$(build_image "${MTX_IMAGE}" "${MEDIA_DIR}" Dockerfile.mtxhook)"
  then
    mtx_built=1
  else
    add_setup_reason "${why}"
  fi
  if why="$(build_image "${INGEST_AUTH_IMAGE}" "${MEDIA_DIR}" \
    Dockerfile.ingest-auth)"; then
    ia_built=1
  else
    ia_build_reason="${why}"
  fi
}

#######################################
# 응답기 원본 파일을 쓴다 — 가짜 비밀은 RUNTIME_DIR(소유 · 모드는
# ingest-auth-prep 이 볼륨에서 맞춘다), 거부 목록(머리 줄)은 bind 하는 호스트
# 디렉터리(0750 · 0640 — 로컬 엔진 OrbStack 의 bind 는 파일을 읽는 컨테이너
# uid 의 것으로 보이게 해 응답기가 읽는다 · 2026-10-24 실측), IP 한도 env
# 파일은 compose 사본 옆.
# Globals:
#   RUNTIME_DIR, deny_dir, ia_compose_copy
#######################################
write_responder_inputs() {
  local src="${RUNTIME_DIR}/ingest-auth-src"
  mkdir -p -- "${src}" "${deny_dir}"
  printf '%s\n' "${IA_BASIC_PASS}" >"${src}/ingest_basic_pass"
  printf '%s\n' "${IA_API_PASS}" >"${src}/mtx_api_pass"
  printf '%s\n' "${IA_INTERNAL_TOKEN}" >"${src}/stream_key_resolve_token"
  printf '# pokeclip deny-tokens v1\n' >"${deny_dir}/deny-tokens"
  chmod 0750 "${deny_dir}"
  chmod 0640 "${deny_dir}/deny-tokens"
  printf 'INGEST_AUTH_IP_LIMIT=on\n' \
    >"$(dirname -- "${ia_compose_copy}")/ingest-auth.env"
}

#######################################
# 시험 compose 가 읽는 값을 export 한다.
# Globals:
#   PCBENCH_*, RUNTIME_DIR, scratch, sender_image, ia_compose_copy
#######################################
export_compose_env() {
  export PCBENCH_MTX_IMAGE="${MTX_IMAGE}"
  export PCBENCH_INGEST_AUTH_IMAGE="${INGEST_AUTH_IMAGE}"
  export PCBENCH_ECHO_IMAGE="${ECHO_IMAGE}"
  export PCBENCH_HELPER_IMAGE="${HELPER_IMAGE}"
  export PCBENCH_SENDER_IMAGE="${sender_image}"
  export PCBENCH_PASSPHRASE="${IA_PASSPHRASE}"
  export PCBENCH_INTERNAL_TOKEN="${IA_INTERNAL_TOKEN}"
  export PCBENCH_STREAM_TOKEN="${IA_STREAM_TOKEN}"
  export PCBENCH_API_PASS="${IA_API_PASS}"
  export PCBENCH_RESOLVE_URL='http://resolvestub:28082'
  export PCBENCH_MTX_CONFIG="${scratch}/mediamtx.c0.yml"
  export RUNTIME_DIR="${scratch}/runtime"
  # 시험 compose 의 거부 목록 bind 원본(RUNTIME_DIR 아래 고정 이름).
  deny_dir="${RUNTIME_DIR}/ingest-auth-deny"
  ia_compose_copy="${scratch}/compose/${IA_COMPOSE##*/}"
  mkdir -p -- "${RUNTIME_DIR}" "${ia_compose_copy%/*}"
  cp -- "${IA_COMPOSE}" "${ia_compose_copy}"
  write_responder_inputs
}

#######################################
# 1단계 렌더본의 토큰을 모두 채운다. 개수는 막지 않고 token_counts 에 남긴다
# (T1 이 본다).
# Globals:
#   token_counts
# Arguments:
#   입력, 출력, 모드 이름, 토큰 이름, 값, [토큰 이름, 값 …].
#######################################
fill_tokens() {
  local in="$1" out="$2" mode="$3" content name count left=0
  shift 3
  content="$(<"${in}")" || return 1
  while (( $# >= 2 )); do
    name="@@$1@@"
    count="$({ grep -o -F -- "${name}" "${in}" || true; } | wc -l \
      | tr -d ' ')"
    token_counts="${token_counts}${mode} $1 ${count}"$'\n'
    content="${content//"${name}"/$2}"
    shift 2
  done
  [[ "${content}" != *@@* ]] || left=1
  token_counts="${token_counts}${mode} @@남음 ${left}"$'\n'
  printf '%s\n' "${content}" >"${out}"
}

#######################################
# 운영 템플릿을 변수 파일 하나로 렌더한다.
# Arguments:
#   변수 파일, 출력.
# Outputs:
#   실패하면 원인 한 줄.
#######################################
render_mtx() {
  if [[ ! -f "${MEDIA_HOST_DIR}/${MTX_TEMPLATE_REL}" ]]; then
    printf '%s 없음' "${MTX_TEMPLATE_REL}"
    return 1
  fi
  if ! render::template "${MEDIA_HOST_DIR}/${MTX_TEMPLATE_REL}" "$1" "$2" \
    2>"$2.err"; then
    printf '렌더 실패: %s' "$(harness::log_tail "$2.err")"
    return 1
  fi
}

#######################################
# 층 L · A · B 의 MediaMTX 설정을 렌더한다(옛 · 응답기 두 벌). 실패 원인은 그
# 모드를 쓰는 층에만 남긴다.
# Globals:
#   legacy_reason, auth_reason
#######################################
render_configs() {
  local stage1="${scratch}/mediamtx.stage1.yml" why
  local auth_vars="${scratch}/vars-auth.json"
  local auth_stage1="${scratch}/mediamtx.auth.stage1.yml"
  if ! why="$(render_mtx "${RENDER_VARS_FILE}" "${stage1}")"; then
    legacy_reason="옛 모드 ${why}"
  else
    fill_tokens "${stage1}" "${scratch}/mediamtx.legacy.yml" legacy \
      HLS_CDN_SECRET "${IA_CDN_SECRET}" MTX_API_PASS "${IA_API_PASS}" \
      || legacy_reason='옛 모드 토큰 채우기 실패'
  fi
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  if ! jq --arg image "${INGEST_AUTH_IMAGE_REF}" \
    '.ingest_auth_image = $image' "${RENDER_VARS_FILE}" >"${auth_vars}"; then
    auth_reason='응답기 모드 변수 파일을 만들지 못함'
  elif ! why="$(render_mtx "${auth_vars}" "${auth_stage1}")"; then
    auth_reason="응답기 모드 ${why}"
  else
    fill_tokens "${auth_stage1}" "${scratch}/mediamtx.auth.yml" auth \
      HLS_CDN_SECRET "${IA_CDN_SECRET}" \
      INGEST_AUTH_BASIC_PASS "${IA_BASIC_PASS}" \
      SRT_PUBLISH_LOCK "${IA_PUBLISH_LOCK}" SRT_READ_LOCK "${IA_READ_LOCK}" \
      || auth_reason='응답기 모드 토큰 채우기 실패'
  fi
}

#######################################
# T1 — 채운 토큰이 각각 정확히 한 번이고 남은 `@@` 가 없다(두 모드).
# Globals:
#   token_counts, legacy_reason, auth_reason
#######################################
check_t1() {
  local ok=0 mode name count
  if [[ -n "${legacy_reason}" || -n "${auth_reason}" ]]; then
    harness::add_reason "렌더 실패 — ${legacy_reason}${legacy_reason:+ · }\
${auth_reason}"
    return 1
  fi
  if [[ -z "${token_counts}" ]]; then
    harness::add_reason '토큰 기록 없음(렌더 전 준비 실패)'
    return 1
  fi
  while read -r mode name count; do
    [[ -n "${mode}" ]] || continue
    if [[ "${name}" == @@남음 && "${count}" != 0 ]] \
      || [[ "${name}" != @@남음 && "${count}" != 1 ]]; then
      harness::add_reason "${mode} ${name} ${count}(기대 \
$([[ "${name}" == @@남음 ]] && echo 0 || echo 1))"
      ok=1
    fi
  done <<<"${token_counts}"
  return "${ok}"
}

# C0 최소 설정 — HTTP 인증 · 내부 사용자 빈 목록. 설정 조회만 하므로 api 를
# 인증에서 뺀다(응답기 없이 조회한다).
write_c0_config() {
  cat >"$1" <<'EOF'
api: yes
apiAddress: 127.0.0.1:9997
apiTrustedProxies: []
rtmp: no
rtsp: no
webrtc: no
moq: no
hls: no
playback: no
srt: yes
srtAddress: :8890
authMethod: http
authHTTPAddress: http://127.0.0.1:8080/mediamtx/auth
authHTTPExclude:
  - action: api
authInternalUsers: []
paths:
  all_others:
EOF
}

setup() {
  check_tools || return 1
  check_prestate || return 1
  load_sender_image || true
  build_images
  write_c0_config "${scratch}/mediamtx.c0.yml"
  env_ready=1
  export_compose_env
  ia::configure "${PROJECT}" "${ia_compose_copy}"
  ia::configure_ops "${MEDIA_HOST_DIR}" "${scratch}/ops" "${deny_dir}"
  render_configs
  (( mtx_built )) || return 1
}

#######################################
# MediaMTX 관리 API 가 답할 때까지(코드가 000 이 아닐 때까지) 기다린다.
# MediaMTX 가 끝나면 그 로그 끝을 원인으로 낸다.
# Outputs:
#   실패하면 원인 한 줄.
#######################################
wait_mediamtx() {
  local deadline=$((SECONDS + MTX_READY_TIMEOUT)) id state
  while (( SECONDS < deadline )); do
    id="$(ia::compose ps -aq mediamtx 2>/dev/null)" || id=""
    state="$(docker inspect -f '{{.State.Status}}' "${id:-none}" \
      2>/dev/null)" || state=""
    if [[ "${state}" == exited ]]; then
      ia::logs mediamtx >"${scratch}/mtx-exit.log" || true
      printf 'MediaMTX 가 끝남: %s' "$(harness::log_tail \
        "${scratch}/mtx-exit.log")"
      return 1
    fi
    if [[ "$(ia::http_code http://127.0.0.1:9997/v3/paths/list)" != 000 ]]
    then
      return 0
    fi
    sleep 1
  done
  printf 'MediaMTX 관리 API 가 %s초 안에 답하지 않음' "${MTX_READY_TIMEOUT}"
  return 1
}

# 실물 응답기가 healthy 가 될 때까지 기다린다(실패하면 원인 한 줄).
wait_responder() {
  local deadline=$((SECONDS + RESPONDER_READY_TIMEOUT)) id health
  id="$(ia::compose ps -q ingest-auth 2>/dev/null)" || id=""
  while (( SECONDS < deadline )); do
    health="$(docker inspect -f '{{.State.Health.Status}}' "${id:-none}" \
      2>/dev/null)" || health=""
    [[ "${health}" != healthy ]] || return 0
    sleep 1
  done
  ia::logs ingest-auth >"${scratch}/ia-boot.log" || true
  printf '응답기가 %s초 안에 healthy 아님(%s): %s' \
    "${RESPONDER_READY_TIMEOUT}" "${health:-상태 없음}" \
    "$(harness::log_tail "${scratch}/ia-boot.log")"
  return 1
}

# 가짜 응답기 관리 포트가 답할 때까지 기다린다.
wait_stub() {
  local deadline=$((SECONDS + MTX_READY_TIMEOUT))
  while (( SECONDS < deadline )); do
    ia::stub_admin /healthz >/dev/null && return 0
    sleep 1
  done
  printf '가짜 응답기 관리 포트가 답하지 않음'
  return 1
}

#######################################
# 층 하나를 띄운다. 결과는 ia_layer · ia_layer_ok · ia_layer_reason 이다.
# Globals:
#   ia_layer, ia_layer_ok, ia_layer_reason, PCBENCH_MTX_CONFIG, setup_reason
# Arguments:
#   층 이름, 설정 파일(층마다 다른 파일 — 덮어쓰지 않는다), 프로필(빈 값이면
#   mediamtx 하나), 그 층만의 준비 실패 원인(없어도 됨 — 렌더 실패 등).
#######################################
stack_up() {
  local why
  ia_layer="$1"
  ia_layer_ok=0
  ia_layer_reason=""
  if [[ -n "${setup_reason}" || -n "${4:-}" ]]; then
    ia_layer_reason="준비 실패 — ${setup_reason}${setup_reason:+${4:+; }}${4:-}"
    return 1
  fi
  export PCBENCH_MTX_CONFIG="$2"
  if [[ -n "$3" ]]; then
    ia::compose --profile "$3" up -d >"${scratch}/up.log" 2>&1 || why=up
  else
    ia::compose up -d mediamtx >"${scratch}/up.log" 2>&1 || why=up
  fi
  if [[ "${why:-}" == up ]]; then
    ia_layer_reason="compose up 실패: $(harness::log_tail "${scratch}/up.log")"
    return 1
  fi
  why="$(wait_mediamtx)" || { ia_layer_reason="${why}"; return 1; }
  case "$3" in
    stub) why="$(wait_stub)" || { ia_layer_reason="${why}"; return 1; } ;;
    real) why="$(wait_responder)" || { ia_layer_reason="${why}"; return 1; } ;;
  esac
  ia_layer_ok=1
}

#######################################
# 층 로그를 INGEST_AUTH_CONTRACT_LOG_DIR 에 남긴다(가짜 비밀은 가린다).
# Arguments:
#   층 이름.
#######################################
save_layer_logs() {
  local dir="${INGEST_AUTH_CONTRACT_LOG_DIR:-}" secret raw
  [[ -n "${dir}" ]] || return 0
  mkdir -p -- "${dir}"
  raw="$(compose_all logs --no-color --timestamps 2>&1)" || true
  for secret in "${IA_CDN_SECRET}" "${IA_API_PASS}" "${IA_BASIC_PASS}" \
    "${IA_PUBLISH_LOCK}" "${IA_READ_LOCK}" "${IA_INTERNAL_TOKEN}" \
    "${IA_PASSPHRASE}" "${IA_WRONG_PASSPHRASE}"; do
    raw="${raw//"${secret}"/<가림>}"
  done
  printf '%s\n' "${raw}" >"${dir}/layer-$1.log"
}

# 층을 내린다(로그를 먼저 남긴다).
layer_down() {
  (( env_ready )) || return 0
  save_layer_logs "$1"
  stack_down
}

run_layer_l() {
  stack_up L "${scratch}/mediamtx.legacy.yml" '' "${legacy_reason}" || true
  harness::run_case L1 '옛 모드 — 익명 송출 성공' check_l1
  harness::run_case L2 '옛 모드 — Bearer 없음 401 · CDN Bearer 200' check_l2
  harness::run_case L3 '옛 모드 — 사이드카 자격 관리 API 200 · 무자격 401' \
    check_l3
  layer_down L
}

run_layer_a() {
  stack_up A "${scratch}/mediamtx.auth.yml" stub "${auth_reason}" || true
  harness::run_case C1 '가짜 응답기 허용(P) — 송출 성공' check_c1
  harness::run_case C4 '응답기가 받은 Basic = 렌더 자격' check_c4
  harness::run_case C5 '본문 ip = 송출자 IP(기본 망)' check_c5
  harness::run_case C8 'passphrase 없는 SRT 읽기 실패(읽기 잠금)' check_c8
  harness::run_case C10 '같은 경로 둘째 송출자 거부' check_c10
  if (( ia_layer_ok )); then
    measure_big_bodies || true
    printf '기록 C19 자극 — 인증 본문 쿼리 %s · User-Agent %s 바이트\n' \
      "${ia_big_query_body}" "${ia_big_ua_body}"
  fi
  ia::sender_stop a-c1
  harness::run_case C2 '틀린 passphrase 거부' check_c2
  harness::run_case C3 'passphrase 없는 송출 거부' check_c3
  harness::run_case C7 '빈 허용(passphrase 없음) — 송출 거부(잠금)' check_c7
  harness::run_case C6 '응답기 정지 — 송출 거부' check_c6
  layer_down A
}

run_layer_b() {
  local why="${ia_build_reason}"
  if (( ia_built )); then
    stack_up B "${scratch}/mediamtx.auth.yml" real "${auth_reason}" || true
  else
    ia_layer=B
    ia_layer_ok=0
    ia_layer_reason="${why}"
  fi
  harness::run_record C16 '기동 직후 사이드카 자격 관리 API 폴(기록)' record_c16
  harness::run_case C9 '실물 응답기 경유 송출 · healthy · started 꼴' check_c9
  harness::run_case C12 '관리 API — 사이드카 자격 200 · 무자격 · 틀린 비밀번호 401' \
    check_c12
  harness::run_case C14 'HLS 재생목록 — Bearer 없음 401 · CDN 200 · 다른 Bearer 401' \
    check_c14
  harness::run_case C17 'SRT 읽기(응답기 200)도 읽기 잠금으로 실패' check_c17
  harness::run_case C19 '큰 인증 본문 → 401 · info 로그 request_invalid 0' \
    check_c19
  harness::run_case C13 '송출 중 IP 한도 끄기(c5 스크립트)' check_c13
  harness::run_case C15 '거부 목록 덧붙임 · deny-sync · --release(c5)' check_c15
  harness::run_case C18 '실물 cut(c5 deny-sync 뒤)' check_c18
  harness::run_case C11 'inspect · 로그에 비밀 여섯 · passphrase 0' check_c11
  ia::sender_stop b-c9
  layer_down B
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/ingest_auth_contract.sh\n' >&2
    exit 1
  fi
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/${PROJECT}.XXXXXX")"

  setup || true
  stack_up C0 "${scratch}/mediamtx.c0.yml" '' || true
  harness::run_case C0 'authMethod http + authInternalUsers [] 수용 · 설정 조회' \
    check_c0
  layer_down C0
  harness::run_case T1 '두 모드 렌더본 — 토큰 각 정확히 한 번 · 남은 @@ 0' \
    check_t1
  run_layer_l
  run_layer_a
  run_layer_b

  if ! harness::summary 0 C0 T1 L1 L2 L3 C1 C2 C3 C4 C5 C6 C7 C8 C9 C10 C11 \
    C12 C13 C14 C15 C16 C17 C18 C19; then
    exit 1
  fi
}

# 디버그 · 자체 점검으로 source 하면 함수만 싣고 돌지 않는다.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi
main "$@"
