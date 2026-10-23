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
#
# 이미지는 모두 이 트리에서 로컬 빌드한다(MediaMTX = media/Dockerfile.mtxhook ·
# 응답기 = media/Dockerfile.ingest-auth · 에코 = test/echo). 송출기는 images.json
# 의 loopback_sender 핀이다(로컬에 없으면 받는다). compose 는
# test/compose.ingest-auth.yml 이다.
#
# 응답기 모드의 MediaMTX 설정은 아직 운영 템플릿에 없다(계획 c2a 전). 그래서
# auth_mode_stage1 이 옛 갈래 렌더본에 계획 4-c2a 의 응답기 갈래 꼴을 얹어 시험
# 전용으로 만든다 — c2a 뒤에는 그 함수 하나를 운영 템플릿 렌더로 바꾼다. c5 운영
# 스크립트가 필요한 사례(C13 · C15 · C18)는 스크립트가 없으면 「없음」 으로
# 실패한다. 건너뛰는 갈래는 없다.
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
readonly MTX_TEMPLATE_REL='mediamtx.yml.tmpl'
readonly IP_LIMIT_SCRIPT_REL='bin/ingest-auth-ip-limit.sh'
readonly DENY_SYNC_SCRIPT_REL='bin/ingest-auth-deny-sync.sh'
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
# 응답기 원본 파일(가짜 비밀 · 거부 목록 머리 줄 · IP 한도 env 파일)을
# RUNTIME_DIR 에 쓴다. 소유 · 모드는 ingest-auth-prep 이 볼륨에서 맞춘다.
# Globals:
#   RUNTIME_DIR
#######################################
write_responder_inputs() {
  local src="${RUNTIME_DIR}/ingest-auth-src"
  mkdir -p -- "${src}"
  printf '%s\n' "${IA_BASIC_PASS}" >"${src}/ingest_basic_pass"
  printf '%s\n' "${IA_API_PASS}" >"${src}/mtx_api_pass"
  printf '%s\n' "${IA_INTERNAL_TOKEN}" >"${src}/stream_key_resolve_token"
  printf '# pokeclip deny-tokens v1\n' >"${src}/deny-tokens"
  printf 'INGEST_AUTH_IP_LIMIT=on\n' >"${RUNTIME_DIR}/ingest-auth.env"
}

#######################################
# 시험 compose 가 읽는 값을 export 한다.
# Globals:
#   PCBENCH_*, RUNTIME_DIR, scratch, sender_image
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
  mkdir -p -- "${RUNTIME_DIR}"
  write_responder_inputs
}

#######################################
# 1단계 렌더본의 토큰을 채운다. 각 토큰이 정확히 한 번 나와야 하고, 다 채운
# 뒤 `@@` 가 남으면 안 된다(시작 스크립트의 「토큰마다 정확히 한 번」 불변식).
# Arguments:
#   입력, 출력, 토큰 이름, 값, [토큰 이름, 값 …].
# Outputs:
#   실패하면 원인 한 줄.
#######################################
fill_tokens() {
  local in="$1" out="$2" content name count
  shift 2
  content="$(<"${in}")"
  while (( $# >= 2 )); do
    name="@@$1@@"
    count="$(grep -o -F -- "${name}" "${in}" | wc -l | tr -d ' ')"
    if [[ "${count}" != 1 ]]; then
      printf '토큰 %s 가 %s번(기대 1번)' "$1" "${count}"
      return 1
    fi
    content="${content//"${name}"/$2}"
    shift 2
  done
  if [[ "${content}" == *@@* ]]; then
    printf '채우지 않은 @@ 토큰이 남음'
    return 1
  fi
  printf '%s\n' "${content}" >"${out}"
}

# 응답기 모드 인증 블록(계획 4-c2a 의 꼴) — 1단계 토큰 그대로.
print_auth_block() {
  cat <<'EOF'
authMethod: http
authHTTPAddress: 'http://pokeclip-mediamtx:@@INGEST_AUTH_BASIC_PASS@@@127.0.0.1:8080/mediamtx/auth'
authHTTPExclude:
  - action: playback
authInternalUsers: []
EOF
}

# 응답기 모드 pathDefaults 의 잠금 둘 · overridePublisher(계획 4-c2a).
print_lock_lines() {
  cat <<'EOF'
  srtPublishPassphrase: '@@SRT_PUBLISH_LOCK@@'
  srtReadPassphrase: '@@SRT_READ_LOCK@@'
  overridePublisher: no
EOF
}

#######################################
# 응답기 모드의 1단계 렌더본(토큰 미치환)을 만든다 — c2a 뒤 바꿔 끼울 자리.
#
# 지금(c2a 전)은 옛 갈래 1단계 렌더본에서 authInternalUsers 블록을 계획 4-c2a
# 의 인증 블록으로 갈고 pathDefaults 에 잠금 둘 · overridePublisher 를 더한
# 시험 전용 고정 설정이다. c2a 가 운영 템플릿에 응답기 갈래를 넣으면 이 함수
# 본문을 「ingest_auth_image 를 채운 변수 파일로 MTX_TEMPLATE_REL 을
# render::template」 로 바꾼다(그러면 음성 대조에서 층 A · B 가 실패한다).
# Arguments:
#   옛 갈래 1단계 렌더본, 출력.
#######################################
auth_mode_stage1() {
  local line skipping=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if (( skipping )); then
      if [[ -z "${line}" || "${line}" == [[:space:]]* ]]; then
        continue
      fi
      skipping=0
    fi
    case "${line}" in
      authInternalUsers:*)
        print_auth_block
        skipping=1
        continue
        ;;
      pathDefaults:*)
        printf '%s\n' "${line}"
        print_lock_lines
        continue
        ;;
    esac
    printf '%s\n' "${line}"
  done <"$1" >"$2"
}

#######################################
# 층 L · A · B 의 MediaMTX 설정을 렌더한다(옛 · 응답기 두 벌).
# Outputs:
#   실패하면 원인 한 줄.
#######################################
render_configs() {
  local stage1="${scratch}/mediamtx.stage1.yml" why
  if [[ ! -f "${MEDIA_HOST_DIR}/${MTX_TEMPLATE_REL}" ]]; then
    printf '%s 없음' "${MTX_TEMPLATE_REL}"
    return 1
  fi
  if ! render::template "${MEDIA_HOST_DIR}/${MTX_TEMPLATE_REL}" \
    "${RENDER_VARS_FILE}" "${stage1}" 2>"${scratch}/render.err"; then
    printf '렌더 실패: %s' "$(harness::log_tail "${scratch}/render.err")"
    return 1
  fi
  if ! why="$(fill_tokens "${stage1}" "${scratch}/mediamtx.legacy.yml" \
    HLS_CDN_SECRET "${IA_CDN_SECRET}" MTX_API_PASS "${IA_API_PASS}")"; then
    printf '옛 모드 %s' "${why}"
    return 1
  fi
  auth_mode_stage1 "${stage1}" "${scratch}/mediamtx.auth.stage1.yml"
  if ! why="$(fill_tokens "${scratch}/mediamtx.auth.stage1.yml" \
    "${scratch}/mediamtx.auth.yml" HLS_CDN_SECRET "${IA_CDN_SECRET}" \
    INGEST_AUTH_BASIC_PASS "${IA_BASIC_PASS}" \
    SRT_PUBLISH_LOCK "${IA_PUBLISH_LOCK}" SRT_READ_LOCK "${IA_READ_LOCK}")"
  then
    printf '응답기 모드 %s' "${why}"
    return 1
  fi
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
  local why
  check_tools || return 1
  check_prestate || return 1
  load_sender_image || true
  build_images
  write_c0_config "${scratch}/mediamtx.c0.yml"
  env_ready=1
  export_compose_env
  ia::configure "${PROJECT}" "${IA_COMPOSE}"
  if ! why="$(render_configs)"; then
    add_setup_reason "${why}"
  fi
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
#   mediamtx 하나).
#######################################
stack_up() {
  local why
  ia_layer="$1"
  ia_layer_ok=0
  ia_layer_reason=""
  if [[ -n "${setup_reason}" ]]; then
    ia_layer_reason="준비 실패 — ${setup_reason}"
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
  stack_up L "${scratch}/mediamtx.legacy.yml" '' || true
  harness::run_case L1 '옛 모드 — 익명 송출 성공' check_l1
  harness::run_case L2 '옛 모드 — Bearer 없음 401 · CDN Bearer 200' check_l2
  harness::run_case L3 '옛 모드 — 사이드카 자격 관리 API 200 · 무자격 401' \
    check_l3
  layer_down L
}

run_layer_a() {
  stack_up A "${scratch}/mediamtx.auth.yml" stub || true
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
    stack_up B "${scratch}/mediamtx.auth.yml" real || true
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

check_c13() {
  require_ops_scripts "${MEDIA_HOST_DIR}" "${IP_LIMIT_SCRIPT_REL}"
}

check_c15() {
  require_ops_scripts "${MEDIA_HOST_DIR}" "${DENY_SYNC_SCRIPT_REL}"
}

check_c18() {
  require_ops_scripts "${MEDIA_HOST_DIR}" "${DENY_SYNC_SCRIPT_REL}"
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
  run_layer_l
  run_layer_a
  run_layer_b

  if ! harness::summary 0 C0 L1 L2 L3 C1 C2 C3 C4 C5 C6 C7 C8 C9 C10 C11 \
    C12 C13 C14 C15 C16 C17 C18 C19; then
    exit 1
  fi
}

# 디버그 · 자체 점검으로 source 하면 함수만 싣고 돌지 않는다.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi
main "$@"
