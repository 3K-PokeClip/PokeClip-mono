#!/usr/bin/env bash
#
# Media 상자 템플릿 렌더 시험 — 렌더 결과의 구조 단언.
#
# prod 와 같은 렌더 함수(test/lib/render.sh — terraform console 의
# templatefile())로 compose.yml.tmpl · nginx/nginx.conf.tmpl · mediamtx.yml.tmpl
# 을 렌더하고(MediaMTX 설정은 yamldecode() 로 값을 읽어 단언한다), 렌더한
# compose 마다 `docker compose config -q` 로 검증한 뒤 선언 그대로의 구성 JSON
# (`config --no-normalize`)에 단언한다. 정규화한 구성은 쓰지 않는다 — compose 는
# 정규화 때 network_mode: service:<이름> 에 depends_on 을 스스로 채워 넣어(5.5.0
# 실측), 템플릿이 선언을 빠뜨려도 보이지 않는다. 컨테이너는 띄우지 않는다.
#
# 템플릿 변수 값은 공용 시험값 파일 test/render_vars.json 하나에서 온다. 갈래를
# 보려고 값을 바꾼 사본을 이 시험이 만든다(시험 쪽에 값을 따로 적지 않고 그
# 파일을 jq 로 고친다):
#   - a: render_vars.json 그대로(응답기 이미지 빔 · janitor 끔 · journald).
#   - b: 응답기만 채움. c: janitor 만 켬.
#   - d: 둘 다 채우고 · 켜고 detached_log_driver = json-file(로컬 실행 렌더 갈래).
#   - nginx: control_upstream 빔(a) · 채움(e).
#
# 드리프트: 시험 compose(test/compose.contract.yml)에서 템플릿과 이름이 같은
# 서비스는 restart · network_mode · depends_on · security_opt · cap_drop ·
# cap_add · ulimits · 볼륨 읽기 전용 여부 · bind 원본 자동 생성 여부가 렌더한
# 템플릿과 같아야 한다 — 실행 시험은
# 시험 compose 를 띄우므로, 거기서만 맞는 구조가 prod 에 새지 않게 한다.
# 유닛 재기동 시험 compose(test/compose.unit.yml)도 같은 항목에 logging(드라이버 ·
# 옵션)까지 비교한다(사례 25) — 그 파일만 prod 와 같은 journald 를 쓴다(CI 엔진에는
# journald 가 있고, 로컬 엔진에는 없어 계약 시험 compose 는 로그 설정을 두지 않는다).
# 구성 확인(config)만 하므로 로컬에서도 돈다.
#
# 제품 파일(템플릿)이 없으면 사례가 FAIL 이다. 건너뛰는 갈래는 없다.
#
# 사용: bash infra/media-host/test/render_test.sh
# 종료 코드: 0 전부 통과 / 1 하나라도 실패

# 아래 source= 경로를 이 파일 위치 기준으로 찾게 한다.
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
# shellcheck source=lib/render_mtx_cases.sh
source "${TEST_DIR}/lib/render_mtx_cases.sh"

readonly PROJECT='pcbench-edge-proxy'
readonly COMPOSE_TEMPLATE="${MEDIA_HOST_DIR}/compose.yml.tmpl"
readonly NGINX_TEMPLATE="${MEDIA_HOST_DIR}/nginx/nginx.conf.tmpl"
readonly MTX_TEMPLATE="${MEDIA_HOST_DIR}/mediamtx.yml.tmpl"
# 시작 스크립트가 채우는 고정 토큰(두 곳 — 각 정확히 한 번).
readonly CDN_TOKEN='@@HLS_CDN_SECRET@@'
readonly API_PASS_TOKEN='@@MTX_API_PASS@@'
# MediaMTX 의 사이드카 API 사용자 이름 = compose 사이드카 MTX_API_USER.
readonly SIDECAR_API_USER='pokeclip-sidecar'
readonly APP_ORIGIN='https://app.pokeclip.com'
readonly CONTRACT_COMPOSE="${TEST_DIR}/compose.contract.yml"
readonly UNIT_COMPOSE="${TEST_DIR}/compose.unit.yml"
readonly COMPOSE_VARIANTS='a b c d'
readonly SAMPLE_CONTROL_UPSTREAM='127.0.0.1:28190'
readonly MIN_READ_TIMEOUT_SECONDS=15
readonly CONFIG_TIMEOUT=60
readonly CASE_COUNT=29
# 녹화 디렉터리 소유자의 정본 — MediaMTX 이미지 Dockerfile 의 최종 USER.
MTXHOOK_DOCKERFILE="$(cd -- "${MEDIA_HOST_DIR}/../.." && pwd)/media/Dockerfile.mtxhook"
readonly MTXHOOK_DOCKERFILE
readonly JANITOR_ENTRYPOINT='/usr/local/bin/janitor'

# 기대값 jq 프로그램이 함께 쓰는 정의. 입력은 그 갈래의 변수 JSON 이다.
#   services — 렌더에 있어야 할 서비스(응답기는 이미지 변수가 차 있을 때만,
#              janitor 는 janitor_enabled 가 참일 때만).
#   attached — compose 출력이 연결되는 서비스(시작 스크립트의 --attach 집합.
#              start_test 가 같은 집합을 시작 스크립트 쪽에서 단언한다).
#   nginx_cap_add — cap_drop: [ALL] 뒤 nginx 에만 되살리는 cap. 공식 이미지는
#              master 가 root 로 떠 임시 디렉터리를 워커 계정(nginx)에 chown 하고
#              워커가 setgid · setuid 로 그 계정이 된다 — 셋 중 하나만 빠져도
#              워커가 뜨지 않는다(실측). 낮은 포트도 이 셋으로 듣는다(엔진이 망
#              칸의 비특권 포트 하한을 0 으로 둔다 — NET_BIND_SERVICE 불요).
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly JQ_DEFS='
def services:
  ["mediamtx", "nginx", "sidecar"]
  + (if .ingest_auth_image != "" then ["ingest-auth"] else [] end)
  + (if .janitor_enabled then ["janitor"] else [] end)
  | sort;
def attached: ["mediamtx", "nginx"];
def nginx_cap_add: ["CHOWN", "SETGID", "SETUID"];
def is_attached: . as $s | attached | index($s) != null;
def db_env:
  {POSTGRES_AUTH: "iam", POSTGRES_HOST: .rds_endpoint,
   POSTGRES_PORT: "5432", POSTGRES_DB: .rds_db_name,
   POSTGRES_SSLMODE: "verify-full",
   POSTGRES_SSLROOTCERT: "/etc/pokeclip/rds-ca/global-bundle.pem",
   TZ: "UTC", AWS_REGION: "ap-northeast-2",
   AWS_EC2_METADATA_V1_DISABLED: "true"};
'

scratch=""
setup_reason=""

cleanup() {
  local status=$?
  harness::end_last_group
  if [[ -n "${scratch}" ]]; then
    rm -rf -- "${scratch:?}"
  fi
  exit "${status}"
}

add_setup_reason() {
  setup_reason="${setup_reason:+${setup_reason}; }$1"
}

vars_file() { printf '%s/vars-%s.json' "${scratch}" "$1"; }
compose_render() { printf '%s/compose-%s.yml' "${scratch}" "$1"; }
config_json() { printf '%s/compose-%s.json' "${scratch}" "$1"; }
nginx_render() { printf '%s/nginx-%s.conf' "${scratch}" "$1"; }
mtx_render() { printf '%s/mediamtx-a.yml' "${scratch}"; }
mtx_json() { printf '%s/mediamtx-a.json' "${scratch}"; }

check_tools() {
  local ok=0 tool
  for tool in docker jq terraform base64 awk grep; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
      add_setup_reason "도구 없음: ${tool}"
      ok=1
    fi
  done
  return "${ok}"
}

# 응답기 시험 이미지 — 사이드카 시험값과 같은 저장소 꼴에 이름 · 다이제스트만
# 바꾼다(서로 다른 값이라 이미지 변수가 뒤바뀌면 사례 3 이 잡는다). janitor 는
# 이미지 변수 없이 켜기만 한다(사이드카 이미지를 쓴다).
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly JQ_SAMPLE_IMAGE='
def sample_image($name; $fill):
  .sidecar_image
  | sub("/[^/@]+@sha256:[0-9a-f]+$"; "/\($name)@sha256:\($fill * 64)");
def fill_auth: .ingest_auth_image = sample_image("media-ingest-auth"; "4");
def fill_janitor: .janitor_enabled = true;
'

#######################################
# 갈래별 변수 파일을 만든다(render_vars.json 을 jq 로 고친 사본).
#######################################
write_variant_vars() {
  local base="${RENDER_VARS_FILE}" defs="${JQ_SAMPLE_IMAGE}"
  cp -- "${base}" "$(vars_file a)" \
    && jq "${defs} fill_auth" "${base}" >"$(vars_file b)" \
    && jq "${defs} fill_janitor" "${base}" >"$(vars_file c)" \
    && jq "${defs} fill_auth | fill_janitor
      | .detached_log_driver = \"json-file\"" "${base}" >"$(vars_file d)" \
    && jq --arg u "${SAMPLE_CONTROL_UPSTREAM}" '.control_upstream = $u' \
      "${base}" >"$(vars_file e)"
}

#######################################
# 템플릿 하나를 렌더한다. 실패하면 준비 실패 원인에 남긴다.
# Arguments:
#   템플릿, 변수 파일, 출력 파일.
#######################################
render_one() {
  if ! render::template "$1" "$2" "$3" 2>"$3.err"; then
    add_setup_reason "${1#"${MEDIA_HOST_DIR}/"} 렌더 실패($(basename -- "$2")): \
$(harness::log_tail "$3.err")"
    return 1
  fi
}

#######################################
# 갈래마다 렌더한다(compose a ~ d · nginx a · e). 템플릿이 없으면 그 이름을
# 한 번만 원인에 남긴다. 하나라도 실패하면 1.
#######################################
render_all() {
  local ok=0 v template
  if ! write_variant_vars; then
    add_setup_reason '갈래 변수 파일을 만들지 못함(render_vars.json · jq)'
    return 1
  fi
  for template in "${COMPOSE_TEMPLATE}" "${NGINX_TEMPLATE}" \
    "${MTX_TEMPLATE}"; do
    if [[ ! -f "${template}" ]]; then
      add_setup_reason "${template#"${MEDIA_HOST_DIR}/"} 없음"
      ok=1
    fi
  done
  (( ok == 0 )) || return 1
  for v in ${COMPOSE_VARIANTS}; do
    render_one "${COMPOSE_TEMPLATE}" "$(vars_file "${v}")" \
      "$(compose_render "${v}")" || ok=1
  done
  for v in a e; do
    render_one "${NGINX_TEMPLATE}" "$(vars_file "${v}")" \
      "$(nginx_render "${v}")" || ok=1
  done
  if render_one "${MTX_TEMPLATE}" "$(vars_file a)" "$(mtx_render)"; then
    if ! render::yaml_json "$(mtx_render)" "$(vars_file a)" "$(mtx_json)" \
      2>"$(mtx_json).err"; then
      add_setup_reason "mediamtx 렌더 YAML 해석 실패: \
$(harness::log_tail "$(mtx_json).err")"
      ok=1
    fi
  else
    ok=1
  fi
  return "${ok}"
}

#######################################
# compose 파일 하나를 `docker compose config -q` 로 검증하고, 선언 그대로의
# 구성(정규화 없음 — 변수 · 앵커는 풀고 볼륨 · 포트는 긴 꼴)을 JSON 으로 쓴다.
# 표준 오류(경고)는 JSON 과 섞지 않고 .err 에 따로 받는다.
# Arguments:
#   compose 파일, 출력 JSON 파일, 변수 파일(없어도 됨 — --env-file).
# Returns:
#   0 config -q · JSON 모두 성공 / 1 실패(원인은 출력 JSON 경로 .err).
#######################################
compose_config() {
  local -a env_args=()
  [[ -z "${3:-}" ]] || env_args=(--env-file "$3")
  harness::run_bounded "${CONFIG_TIMEOUT}" "$2.err" \
    docker compose ${env_args[@]+"${env_args[@]}"} -p "${PROJECT}" -f "$1" \
    config -q || return 1
  harness::bounded "${CONFIG_TIMEOUT}" \
    docker compose ${env_args[@]+"${env_args[@]}"} -p "${PROJECT}" -f "$1" \
    config --no-normalize --format json >"$2.raw" 2>>"$2.err" || return 1
  jq -e . "$2.raw" >"$2" 2>"$2.err"
}

#######################################
# 시험 compose 의 구성 JSON 을 만든다. 그 파일의 변수는 실행하는 쪽이
# 넣으므로 config 만 돌 수 있게 시험값으로 채운다(구조 비교에 쓰지 않는
# 값이다).
#######################################
contract_config() {
  (
    export PCBENCH_ECHO_IMAGE='pcbench-edge-proxy/echo:local'
    export PCBENCH_NGINX_IMAGE='pcbench-edge-proxy/nginx:render-test'
    PCBENCH_NGINX_CONF="$(nginx_render a)"
    export PCBENCH_NGINX_CONF
    export PCBENCH_LIVE_PORT=28181 PCBENCH_PLAYBACK_PORT=28182
    export PCBENCH_CONTROL_PORT=28183 PCBENCH_HEALTH_PORT=28184
    export PCBENCH_ECHO_CONTROL_PORT=28190 PCBENCH_ECHO_ADMIN_PORT=28199
    export PCBENCH_SIDECAR_EXIT_DELAY=3600s RUNTIME_DIR="${scratch}"
    compose_config "${CONTRACT_COMPOSE}" "${scratch}/contract.json"
  )
}

#######################################
# 유닛 시험 compose 의 구성 JSON 을 만든다. 그 파일의 변수는 유닛 시험의 env
# 파일이 넣으므로 config 만 돌 수 있게 시험값으로 채운다(구조 비교에 쓰지 않는
# 값이다). 값은 셸 변수가 아니라 compose 의 --env-file 로 넘긴다.
#######################################
unit_config() {
  local env_file="${scratch}/unit.env"
  printf '%s\n' \
    'PCBENCH_ECHO_IMAGE=pcbench-edge-proxy/echo:local' \
    'PCBENCH_NGINX_IMAGE=pcbench-edge-proxy/nginx:render-test' \
    "PCBENCH_NGINX_CONF=${scratch}/unit-nginx.conf" \
    'PCBENCH_HEALTH_PORT=28184' 'PCBENCH_ECHO_ADMIN_PORT=28199' \
    'PCBENCH_SIDECAR_EXIT_DELAY=3600s' "RUNTIME_DIR=${scratch}" >"${env_file}"
  compose_config "${UNIT_COMPOSE}" "${scratch}/unit.json" "${env_file}"
}

require_setup() {
  if [[ -z "${setup_reason}" ]]; then
    return 0
  fi
  harness::add_reason "준비 실패 — ${setup_reason}"
  return 1
}

#######################################
# 갈래 하나의 구성 JSON 에 jq 필터를 적용한 값이 기대값과 같은지 본다.
# 기대값은 그 갈래의 변수 JSON 을 입력으로 도는 jq 프로그램이다(JQ_DEFS 를
# 앞에 붙인다).
# Arguments:
#   갈래, 실제값 jq 필터, 기대값 jq 프로그램, 이름, 기대값 jq 의 추가 인자
#   (예: --arg 이름 값 — 없어도 됨).
#######################################
expect_config() {
  local v="$1" actual="$2" program="$3" name="$4" want got
  shift 4
  if ! want="$(jq -cS "$@" "${JQ_DEFS} ${program}" "$(vars_file "${v}")" \
    2>&1)"; then
    harness::add_reason "시험 오류(기대값 jq): ${want}"
    return 1
  fi
  got="$(jq -cS "${actual}" "$(config_json "${v}")" 2>/dev/null)" \
    || got='(구성 JSON 을 읽지 못함)'
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "갈래 ${v}: ${name} 기대 ${want} · 실제 ${got}"
    return 1
  fi
}

# 모든 compose 갈래에 같은 단언을 건다. 인자는 expect_config 의 갈래 뒤 인자.
expect_all() {
  local ok=0 v
  for v in ${COMPOSE_VARIANTS}; do
    expect_config "${v}" "$@" || ok=1
  done
  return "${ok}"
}

#######################################
# 파일에 고정 문자열이 든 줄이 없는지 본다. 읽기 실패(grep 종료 2 이상 —
# 「0」 을 내도)와 파일 없음은 0 건이 아니라 확인 불가로 FAIL 이다.
# Arguments:
#   원인에 붙일 이름, 고정 문자열, 파일.
#######################################
expect_fixed_absent() {
  local count status=0
  count="$(harness::count_fixed "$2" "$3")" || status=$?
  if (( status != 0 )); then
    harness::add_reason "$1: 파일을 읽지 못함 — $2 확인 불가"
    return 1
  fi
  if [[ "${count}" != 0 ]]; then
    harness::add_reason "$1: $2 ${count}줄(기대 0)"
    return 1
  fi
}

check_case_1() {
  local ok=0 v json
  require_setup || return 1
  for v in ${COMPOSE_VARIANTS}; do
    json="$(config_json "${v}")"
    if ! compose_config "$(compose_render "${v}")" "${json}"; then
      harness::add_reason "갈래 ${v}: docker compose config 실패: \
$(harness::log_tail "${json}.err")"
      ok=1
    fi
  done
  return "${ok}"
}

check_case_2() {
  require_setup || return 1
  expect_all '.services | keys' 'services' '서비스 집합'
}

check_case_3() {
  require_setup || return 1
  expect_all '.services | map_values(.image)' '
    {mediamtx: .mediamtx_image, nginx: .nginx_image, sidecar: .sidecar_image}
    + (if .ingest_auth_image != ""
       then {"ingest-auth": .ingest_auth_image} else {} end)
    + (if .janitor_enabled then {janitor: .sidecar_image} else {} end)' \
    '이미지(janitor = 사이드카 이미지)'
}

check_case_4() {
  local ok=0
  require_setup || return 1
  expect_all '.services | map_values(.restart // null)' '
    services | map({(.): (if is_attached then "no" else "on-failure" end)})
    | add' 'restart' || ok=1
  expect_all '[.services | to_entries[] | select(.value.restart == "no")
    | .key] | sort' 'attached' 'restart "no" 집합 = 출력 연결 집합' || ok=1
  return "${ok}"
}

check_case_5() {
  require_setup || return 1
  expect_all '.services | map_values({
      network_mode: (.network_mode // null),
      mediamtx_dep: (.depends_on.mediamtx // null
        | if . == null then null else {condition, restart} end)})' '
    services | map({(.): (if . == "mediamtx"
      then {network_mode: null, mediamtx_dep: null}
      else {network_mode: "service:mediamtx",
            mediamtx_dep: {condition: "service_started", restart: true}}
      end)}) | add' '망 칸 · depends_on'
}

check_case_6() {
  require_setup || return 1
  expect_all '{
      publishers: [.services | to_entries[]
        | select((.value.ports // []) | length > 0) | .key],
      mediamtx: [(.services.mediamtx.ports // [])[]
        | "\(.protocol // "tcp") \(.host_ip // "*") \(.published) \(.target)"]
        | sort}' '
    {publishers: ["mediamtx"],
     mediamtx: (([.proxy_live_port, .proxy_playback_port,
                  .proxy_control_port, .proxy_health_port]
                 | map("tcp * \(.) \(.)")) + ["udp * 8890 8890"] | sort)}' \
    '공개 포트(모든 주소에 게시 — 경계는 SG · 8888 · 9996 · 9997 은 열지 않음)'
}

check_case_7() {
  local ok=0 v
  require_setup || return 1
  expect_all '.services | map_values([(.volumes // [])[]
      | "\(.source) \(.target) \(if .read_only then "ro" else "rw" end)"]
      | sort)' '
    {mediamtx: ["recordings /recordings rw", "hooks /hooks rw",
                "dvr /dvr rw",
                "/run/pokeclip-media/mediamtx.yml /mediamtx.yml ro"],
     nginx: ["/etc/pokeclip-media/nginx.conf /etc/nginx/nginx.conf ro"],
     sidecar: ["recordings /recordings ro", "hooks /hooks ro",
               "/run/pokeclip-media/mtx_api_pass /run/secrets/mtx_api_pass ro"]}
    + (if .ingest_auth_image != "" then {"ingest-auth": []} else {} end)
    + (if .janitor_enabled
       then {janitor: ["recordings /recordings rw"]} else {} end)
    | map_values(sort)' '볼륨 · 읽기 전용' || ok=1
  expect_all '[.services[] | (.volumes // [])[]
    | select(.volume.nocopy == true)] | length' '0' 'volume.nocopy' || ok=1
  for v in ${COMPOSE_VARIANTS}; do
    expect_fixed_absent "갈래 ${v} 렌더" nocopy "$(compose_render "${v}")" \
      || ok=1
  done
  return "${ok}"
}

check_case_8() {
  require_setup || return 1
  # ulimits core: 0 은 config JSON 에서 {} 다(0 인 칸을 빼고 낸다 — compose
  # 5.5.0 실측. 0 이 아니면 그 칸이 나온다).
  expect_all '.services | map_values({
      no_new_privileges: ((.security_opt // [])
        | index("no-new-privileges:true") != null),
      privileged: (.privileged // null), pid: (.pid // null),
      ipc: (.ipc // null), cap_drop: (.cap_drop // null),
      cap_add: (.cap_add // null | if . == null then null else sort end),
      core: (.ulimits.core // null)})' '
    services | map({(.): {no_new_privileges: true, privileged: null,
      pid: null, ipc: null, cap_drop: ["ALL"],
      cap_add: (if . == "nginx" then nginx_cap_add else null end),
      core: {}}}) | add' \
    '권한 · core 덤프 0'
}

#######################################
# 녹화 디렉터리 소유자 — MediaMTX 이미지 Dockerfile 최종 스테이지의 USER(마지막
# FROM 뒤 마지막 USER). media/cmd/janitor 의 compose 계약 시험과 같은 규칙이다.
# Outputs:
#   USER 값(없으면 빈 값).
# Returns:
#   0 읽음 / 그 밖 파일을 읽지 못함.
#######################################
recording_owner() {
  awk 'toupper($1) == "FROM" { user = "" }
    toupper($1) == "USER" { user = $2 }
    END { print user }' "${MTXHOOK_DOCKERFILE}"
}

# 실행 계정은 이미지 USER 가 정본이라 user: 를 적지 않는다. janitor 만 예외 —
# 사이드카 이미지(USER 10001)를 쓰지만 녹화 소유자로 돌아야 지울 수 있다.
check_case_9() {
  local owner
  require_setup || return 1
  if [[ ! -f "${MTXHOOK_DOCKERFILE}" ]]; then
    harness::add_reason 'media/Dockerfile.mtxhook 없음(녹화 소유자 정본)'
    return 1
  fi
  if ! owner="$(recording_owner)" || [[ -z "${owner}" ]]; then
    harness::add_reason 'media/Dockerfile.mtxhook 최종 USER 를 읽지 못함'
    return 1
  fi
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  expect_all '.services | map_values(.user // null)' '
    services | map({(.): (if . == "janitor" then $owner else null end)})
    | add' '실행 계정(user)' --arg owner "${owner}"
}

check_case_10() {
  require_setup || return 1
  expect_all '.services | map_values(.environment // {})
    | with_entries(select(.key == "sidecar" or .key == "janitor"))' '
    {sidecar: (db_env + {POSTGRES_USER: "media_indexer",
       ENSURE_SCHEMA: "false", SEGMENT_ROOT: "/recordings",
       HOOK_SPOOL_PATH: "/hooks/events.jsonl",
       MTX_API_URL: "http://127.0.0.1:9997",
       MTX_API_USER: "pokeclip-sidecar",
       MTX_API_PASS_FILE: "/run/secrets/mtx_api_pass",
       S3_BUCKET: .rewind_bucket_name,
       REWIND_PUBLIC_BASE_URL: .rewind_public_base_url})}
    + (if .janitor_enabled
       then {janitor: (db_env + {POSTGRES_USER: "media_janitor",
         JANITOR_ENABLED: "true", SEGMENT_ROOT: "/recordings"})}
       else {} end)' '사이드카 · janitor env'
}

check_case_11() {
  local ok=0 v
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  expect_all '[.services | to_entries[] | .key as $s
    | (.value.environment // {}) | keys[]
    | select(test("CDN|TOKEN|PASSWORD|SECRET"; "i")) | "\($s).\(.)"]' \
    '[]' '비밀 계열 env 이름' || ok=1
  for v in ${COMPOSE_VARIANTS}; do
    expect_fixed_absent "갈래 ${v} 렌더" POSTGRES_PASSWORD \
      "$(compose_render "${v}")" || ok=1
  done
  return "${ok}"
}

check_case_12() {
  require_setup || return 1
  expect_all '.services.mediamtx.environment // {}' '{TZ: "UTC"}' \
    'mediamtx env'
}

check_case_13() {
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  expect_all '.services | map_values({driver: (.logging.driver // null),
      options: (.logging.options // null)})' '
    . as $v | services | map(. as $s | {($s):
      (if ($s | is_attached) or $v.detached_log_driver == "json-file"
       then {driver: "json-file",
             options: {"max-size": "10m", "max-file": "3"}}
       else {driver: "journald",
             options: {tag: "pokeclip-media-\($s)"}} end)}) | add' \
    'logging'
}

#######################################
# nginx 렌더에서 location 블록 첫 문장을 낸다(블록마다 한 줄).
# Arguments:
#   렌더 파일, location 경로.
#######################################
location_first_statement() {
  awk -v loc="location $2 {" '
    index($0, loc) { grab = 1; next }
    grab && NF { sub(/^[ \t]+/, ""); print; grab = 0 }' "$1"
}

#######################################
# 제어면 location 둘의 첫 문장과 그 밖 상류 proxy_pass 수를 본다.
# Arguments:
#   갈래, 기대 첫 문장, 기대 그 밖 proxy_pass 수.
#######################################
expect_control_plane() {
  local file ok=0 loc got others
  file="$(nginx_render "$1")"
  for loc in /api/ingest/ /api/streams/; do
    got="$(location_first_statement "${file}" "${loc}")"
    if [[ "${got}" != "$2" ]]; then
      harness::add_reason "nginx ${1}: ${loc} 기대 '$2' · 실제 '${got}'"
      ok=1
    fi
  done
  # 한 단계로 센다 — 앞 단계(proxy_pass 줄 고르기)의 실패가 뒤 집계를 0 으로
  # 만들지 않게. awk 에는 「맞는 줄 없음」 종료 코드가 없어 0 아닌 종료는 모두
  # 조회 실패다.
  if ! others="$(awk '
      /^[[:space:]]*proxy_pass[[:space:]]/ &&
        !/http:\/\/127\.0\.0\.1:(8888|9996)[\/;]/ { n++ }
      END { print n + 0 }' "${file}" 2>/dev/null)" \
    || [[ ! "${others}" =~ ^[0-9]+$ ]]; then
    harness::add_reason "nginx ${1}: proxy_pass 조회 실패"
    ok=1
  elif [[ "${others}" != "$3" ]]; then
    harness::add_reason "nginx ${1}: 8888 · 9996 밖 proxy_pass ${others}건 \
(기대 $3)"
    ok=1
  fi
  return "${ok}"
}

check_case_14() {
  local ok=0
  require_setup || return 1
  expect_control_plane a 'return 404;' 0 || ok=1
  expect_control_plane e "proxy_pass http://${SAMPLE_CONTROL_UPSTREAM};" 2 \
    || ok=1
  return "${ok}"
}

check_case_15() {
  local ok=0 v
  require_setup || return 1
  for v in a e; do
    expect_fixed_absent "nginx ${v}" 9997 "$(nginx_render "${v}")" || ok=1
  done
  return "${ok}"
}

#######################################
# nginx 시간 값 하나를 초로 바꾼다(ms · s · m · h · 단위 없음 = 초). 조합
# 꼴(1m30s)은 받지 않는다.
# Arguments:
#   값.
# Outputs:
#   초(정수 — ms 는 내림).
#######################################
nginx_time_seconds() {
  local n unit
  [[ "$1" =~ ^([0-9]+)(ms|s|m|h)?$ ]] || return 1
  n=$((10#${BASH_REMATCH[1]}))
  unit="${BASH_REMATCH[2]}"
  case "${unit}" in
    ms) printf '%s' "$((n / 1000))" ;;
    m) printf '%s' "$((n * 60))" ;;
    h) printf '%s' "$((n * 3600))" ;;
    *) printf '%s' "${n}" ;;
  esac
}

check_case_16() {
  local ok=0 v value seconds
  require_setup || return 1
  for v in a e; do
    while read -r value; do
      if ! seconds="$(nginx_time_seconds "${value}")"; then
        harness::add_reason "nginx ${v}: proxy_read_timeout '${value}' 판독 불가"
        ok=1
      elif (( seconds < MIN_READ_TIMEOUT_SECONDS )); then
        harness::add_reason "nginx ${v}: proxy_read_timeout ${value} \
(${MIN_READ_TIMEOUT_SECONDS}s 미만)"
        ok=1
      fi
    done < <(grep -o -E 'proxy_read_timeout[[:space:]]+[^;]*;' \
      "$(nginx_render "${v}")" | awk '{ sub(/;$/, "", $2); print $2 }')
  done
  return "${ok}"
}

# 드리프트 비교 — 서비스마다 비교 항목을 뽑고(facts), 이름이 같은 서비스끼리
# 다른 항목을 한 줄씩 낸다. 역할 서비스 셋이 양쪽에 다 없어도 한 줄 낸다.
# 입력: $p = 렌더한 템플릿, $t = 시험 compose(둘 다 선언 그대로의 구성 JSON),
# $logging = logging(드라이버 · 옵션)도 비교할지(true · false).
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly DRIFT_PROGRAM='
def facts: .services | map_values({
  restart: (.restart // null),
  network_mode: (.network_mode // null),
  depends_on: ((.depends_on // {}) | map_values({condition, restart})),
  security_opt: ((.security_opt // []) | sort),
  cap_drop: ((.cap_drop // []) | sort),
  cap_add: ((.cap_add // []) | sort),
  ulimits: (.ulimits // null),
  read_only: ((.volumes // []) | map({(.target): (.read_only // false)})
    | add // {}),
  create_host_path: ((.volumes // []) | map(select(.type == "bind")
    | {(.target): (.bind.create_host_path != false)}) | add // {})}
  + (if $logging then {logging: (.logging // null)} else {} end));
($p[0] | facts) as $pf | ($t[0] | facts) as $tf
| [$pf | keys[] | select(. as $s | $tf | has($s))] as $shared
| (["mediamtx", "nginx", "sidecar"] - $shared | .[]
   | "공통 서비스 없음: \(.)"),
  ($shared[] as $s | $pf[$s] | keys[] as $k
   | select($pf[$s][$k] != $tf[$s][$k])
   | "\($s).\($k): prod \($pf[$s][$k] | tojson) · 시험 \($tf[$s][$k] | tojson)")
'

#######################################
# 시험 compose 하나와 렌더한 템플릿(갈래 a)의 드리프트를 본다.
# Arguments:
#   시험 compose 구성 JSON, logging 도 비교할지(true|false).
#######################################
expect_no_drift() {
  local diffs
  if ! diffs="$(jq -n -r --slurpfile p "$(config_json a)" \
    --slurpfile t "$1" --argjson logging "$2" "${DRIFT_PROGRAM}" 2>&1)"; then
    harness::add_reason "드리프트 비교 실패: ${diffs}"
    return 1
  fi
  if [[ -n "${diffs}" ]]; then
    harness::add_reason "드리프트: $(printf '%s' "${diffs}" | tr '\n' ';')"
    return 1
  fi
}

check_case_17() {
  require_setup || return 1
  if ! contract_config; then
    harness::add_reason "시험 compose config 실패: \
$(harness::log_tail "${scratch}/contract.json.err")"
    return 1
  fi
  expect_no_drift "${scratch}/contract.json" false
}

check_case_25() {
  require_setup || return 1
  if [[ ! -f "${UNIT_COMPOSE}" ]]; then
    harness::add_reason 'test/compose.unit.yml 없음'
    return 1
  fi
  if ! unit_config; then
    harness::add_reason "유닛 시험 compose config 실패: \
$(harness::log_tail "${scratch}/unit.json.err")"
    return 1
  fi
  expect_no_drift "${scratch}/unit.json" true
}

#######################################
# 사례 26 — 렌더 파일 bind 셋(시작 스크립트가 tmpfs 에 렌더하는 두 파일 · M9
# user_data 가 렌더하는 nginx 설정)은 원본이 없으면 만들지 않는다
# (create_host_path: false). 원본이 없을 때(정지가 렌더를 지운 뒤 남은
# 컨테이너가 다시 뜰 때 등) 엔진이 그 자리에 디렉터리를 만들면 다음 렌더가
# 영영 막힌다.
#######################################
check_case_26() {
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  expect_all '[.services | to_entries[] | .key as $s | (.value.volumes // [])[]
      | select(.type == "bind")
      | "\($s) \(.target) \(.bind.create_host_path != false)"] | sort' '
    ["mediamtx /mediamtx.yml false",
     "nginx /etc/nginx/nginx.conf false",
     "sidecar /run/secrets/mtx_api_pass false"]' \
    '렌더 파일 bind 의 원본 자동 생성'
}

check_case_27() {
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  expect_all '.services | map_values({entrypoint: (.entrypoint // null),
      read_only: (.read_only // null)})' '
    services | map({(.): (if . == "janitor"
      then {entrypoint: [$entry], read_only: true}
      else {entrypoint: null, read_only: null} end)}) | add' \
    'entrypoint · 읽기 전용 루트' --arg entry "${JANITOR_ENTRYPOINT}"
}

#######################################
# 사례 28 — janitor_enabled 가 bool 로 바꿀 수 없는 값이면 렌더가 실패한다
# (일부 문자열 — "true" · "false" 등 — 은 bool 로 바뀌어 통과한다). 그 키 하나만 "yes" ·
# 빈 문자열 · null · 숫자 1 로 바꾼 변수 파일은 각각 렌더가 실패하고, 원래 변수
# 파일(같은 시험 안에서 다시 렌더)은 성공한다 — 렌더 실패가 다른 원인이면 짝의
# 성공 쪽이 FAIL 한다.
#######################################
check_case_28() {
  local ok=0 value bad="${scratch}/vars-bad-enabled.json"
  local out="${scratch}/compose-bad-enabled.yml"
  require_setup || return 1
  for value in '"yes"' '""' 'null' '1'; do
    if ! jq ".janitor_enabled = ${value}" "${RENDER_VARS_FILE}" >"${bad}"; then
      harness::add_reason '시험 오류: 변수 파일을 만들지 못함'
      return 1
    fi
    if render::template "${COMPOSE_TEMPLATE}" "${bad}" "${out}" \
      2>"${out}.err"; then
      harness::add_reason "janitor_enabled = ${value} 인데 렌더가 성공함"
      ok=1
    fi
  done
  if ! render::template "${COMPOSE_TEMPLATE}" "${RENDER_VARS_FILE}" \
    "${scratch}/compose-good-enabled.yml" 2>"${out}.good.err"; then
    harness::add_reason "원래 변수 파일 렌더 실패: \
$(harness::log_tail "${out}.good.err")"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 가짜 명령 하나를 만든다 — 인자가 꼴에 맞으면 주어진 본문을 돌리고, 아니면
# 진짜 명령으로 넘긴다. 진짜 명령 경로는 가짜를 PATH 에 두기 전에 잡는다
# (가짜가 자기 자신을 부르는 고리를 막는다).
# Arguments:
#   bin 디렉터리, 명령 이름, 인자 꼴(sh case 패턴 — 앞뒤 공백을 붙인 "$*" 에
#   맞춘다), 맞을 때의 본문.
#######################################
write_fake_command() {
  local real
  real="$(command -v "$2")" || return 1
  mkdir -p -- "$1" || return 1
  printf '#!/bin/sh\ncase " $* " in %s) %s ;; esac\nexec %s "$@"\n' \
    "$3" "$4" "${real}" >"$1/$2" && chmod +x "$1/$2"
}

#######################################
# PATH 앞에 가짜 명령 디렉터리를 둔 채(그 호출 동안만) 사례 함수를 부르고,
# 종료 코드와 실패 원인을 「코드:원인」 한 줄로 낸다. $(…) 안에서 부른다 —
# 사례 함수가 바꾼 전역은 그 하위 셸에만 남는다.
# Arguments:
#   bin 디렉터리, 사례 함수 이름.
#######################################
run_with_fake() {
  local status=0
  harness_fail_reason=''
  PATH="$1:${PATH}" "$2" >/dev/null 2>&1 || status=$?
  printf '%s:%s' "${status}" "${harness_fail_reason}"
}

#######################################
# 사례 29 — 렌더 · nginx 설정을 세는 사례(7 · 11 · 15 · 14)는 읽기 실패를 0 건
# 으로 읽지 않는다. 실제 사례 함수를 가짜 명령 아래에서 불러 FAIL 과 원인을
# 본다: grep -c -F 가 「0」 을 내고 종료 2(읽기 실패의 꼴)면 사례 7 · 11 · 15
# 가 「확인 불가」, proxy_pass 를 세는 단계(grep · awk 어느 쪽이든)가 출력 없이
# 종료 2 면 사례 14 가 「조회 실패」 다.
#######################################
check_case_29() {
  local ok=0 dir="${scratch}/fake-count" c got want
  require_setup || return 1
  if ! write_fake_command "${dir}/count" grep '*" -c -F "*' \
    'echo 0; exit 2' \
    || ! write_fake_command "${dir}/proxy" grep '*proxy_pass*' 'exit 2' \
    || ! write_fake_command "${dir}/proxy" awk '*proxy_pass*' 'exit 2'; then
    harness::add_reason '시험 오류: 가짜 명령을 만들지 못함'
    return 1
  fi
  for c in 7 11 15 14; do
    if [[ "${c}" == 14 ]]; then
      got="$(run_with_fake "${dir}/proxy" "check_case_${c}")"
      want='조회 실패'
    else
      got="$(run_with_fake "${dir}/count" "check_case_${c}")"
      want='확인 불가'
    fi
    if [[ "${got%%:*}" == 0 || "${got#*:}" != *"${want}"* ]]; then
      harness::add_reason "사례 ${c}: 「${got}」(기대 FAIL · ${want})"
      ok=1
    fi
  done
  return "${ok}"
}

main() {
  if (( $# != 0 )); then
    printf '사용: bash infra/media-host/test/render_test.sh (인자 없음)\n' >&2
    exit 1
  fi
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  scratch="$(mktemp -d "${TMPDIR:-/tmp}/pcbench-edge-proxy-render-test.XXXXXX")"

  if check_tools; then
    render_all || true
  fi
  harness::run_case 1 '렌더한 compose 마다 docker compose config 종료 0' \
    check_case_1
  harness::run_case 2 \
    '응답기는 이미지 변수가 찰 때만 · janitor 는 janitor_enabled 일 때만 렌더' \
    check_case_2
  harness::run_case 3 '서비스 이미지 = 템플릿 변수' check_case_3
  harness::run_case 4 \
    'restart — mediamtx · nginx "no" · 나머지 on-failure(상한 없음)' \
    check_case_4
  harness::run_case 5 '같은 망 칸 · depends_on mediamtx(재시작 전파)' \
    check_case_5
  harness::run_case 6 '공개 포트는 mediamtx 에서만 · 루프백 포트 비공개' \
    check_case_6
  harness::run_case 7 '공유 볼륨 · 읽기 전용 · nocopy 0' check_case_7
  harness::run_case 8 \
    '권한 — no-new-privileges · cap_drop ALL · nginx cap 셋 · 특권 0 · core 0' \
    check_case_8
  harness::run_case 9 \
    '실행 계정 — janitor 만 녹화 소유자(Dockerfile.mtxhook USER) · 나머지 user 없음' \
    check_case_9
  harness::run_case 10 '사이드카 · janitor env(IAM · 비밀 없음)' check_case_10
  harness::run_case 11 'env 에 비밀 계열 이름 · POSTGRES_PASSWORD 0' \
    check_case_11
  harness::run_case 12 'mediamtx env TZ=UTC' check_case_12
  harness::run_case 13 \
    'logging — 출력 연결 json-file 회전(10m × 3) · 비부착 journald 고정 tag' \
    check_case_13
  harness::run_case 14 'nginx 제어면 — 상류 빔 404 · 채움 proxy_pass' \
    check_case_14
  harness::run_case 15 'nginx 렌더에 관리 API 포트 9997 0건' check_case_15
  harness::run_case 16 'nginx proxy_read_timeout 15초 이상' check_case_16
  harness::run_case 17 '드리프트 — 시험 compose 와 템플릿 구조 일치' \
    check_case_17
  harness::run_case 18 'mediamtx 렌더(templatefile) · YAML 해석' mtx_cases::check_18
  harness::run_case 19 'mediamtx — 프로토콜 · 루프백 바인딩 · metrics/pprof 끔' \
    mtx_cases::check_19
  harness::run_case 20 'mediamtx — 허용 출처 app · 믿는 프록시 없음' \
    mtx_cases::check_20
  harness::run_case 21 'mediamtx — 인증 항목 셋(무인증 api 0 · 익명 read 0)' \
    mtx_cases::check_21
  harness::run_case 22 'mediamtx — 토큰 둘 각 1번 · 따옴표 자리' mtx_cases::check_22
  harness::run_case 23 'mediamtx — 경로 · LL-HLS · 녹화 · 훅 유지' mtx_cases::check_23
  harness::run_case 24 '사이드카 관측 자격 짝(user · 파일 · 포트)' mtx_cases::check_24
  harness::run_case 25 \
    '드리프트 — 유닛 시험 compose 와 템플릿 구조 · logging 일치' check_case_25
  harness::run_case 26 '렌더 파일 bind — 원본 없으면 만들지 않음' check_case_26
  harness::run_case 27 'janitor — entrypoint 로 명령 고름 · 읽기 전용 루트' \
    check_case_27
  harness::run_case 28 \
    'janitor_enabled 가 bool 로 바꿀 수 없는 값이면 렌더 실패(일부 문자열은 통과)' \
    check_case_28
  harness::run_case 29 '집계 — 읽기 실패는 0 건이 아님(정적)' check_case_29

  if ! harness::summary "${CASE_COUNT}"; then
    exit 1
  fi
}

main "$@"
