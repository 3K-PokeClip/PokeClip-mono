# shellcheck shell=bash
# 전역(scratch · PROJECT · CONFIG_TIMEOUT · TEST_DIR · MEDIA_HOST_DIR ·
# BASIC_PASS_TOKEN 등)과 require_setup · vars_file · compose_render ·
# config_json · mtx_json · render_one 은 render_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 compose 응답기 서비스 사례(35 – 44). 응답기 갈래는 b · d
# (ingest_auth_image 채움), 옛 갈래는 a · c 다.
#   - 응답기 서비스 꼴: env 정확 집합 · `*_FILE` 은 읽기 전용 bind 대상 ·
#     거부 목록은 디렉터리 bind · healthcheck · 읽기 전용 루트 · env_file.
#   - 다른 파일과의 짝: mediamtx 응답기 모드(모드 일치) · 응답기 이미지의 고정
#     자리(media/cmd/ingest-auth/main.go) · 시작 스크립트의 기본 경로 · 망 칸
#     포트 · 계약 시험 compose(compose.ingest-auth.yml).
#   - 옛 갈래: develop 6c90c935 렌더 골든(test/golden/compose.legacy.*.json).
#
# env_file 은 compose 가 구성 JSON 에서 environment 로 풀어 버린다(5.5.0 실측 —
# --no-normalize 도 그렇다). 그래서 env 정확 집합 · env_file 은 풀지 않은 구성
# (--no-env-resolution)으로 본다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly AUTH_VARIANTS='b d'
readonly LEGACY_VARIANTS='a c'
readonly ENV_TEMPLATE="${MEDIA_HOST_DIR}/ingest-auth.env.tmpl"
readonly IP_LIMIT_ENV_NAME='ingest-auth.env'
# 옛 갈래 compose 골든(a · c) — develop 6c90c935 의 템플릿 · 변수로 렌더한
# compose 를 compose 5.5.0 `config --no-normalize --format json` 으로 받아
# `jq -S .` 로 정렬한 출력이다. 이 출력은 compose 판에 기대므로 판 고정을
# 올리면 다시 만든다. 만든 명령 = M15 c4 실험 기록 make-golden.sh.
readonly COMPOSE_GOLDEN_PREFIX="${TEST_DIR}/golden/compose.legacy"
readonly START_SCRIPT="${MEDIA_HOST_DIR}/bin/start-media.sh"
RESPONDER_MAIN="$(cd -- "${MEDIA_HOST_DIR}/../.." && pwd)"
RESPONDER_MAIN="${RESPONDER_MAIN}/media/cmd/ingest-auth/main.go"
readonly RESPONDER_MAIN
readonly INGEST_AUTH_COMPOSE="${TEST_DIR}/compose.ingest-auth.yml"
readonly UNIT_INGEST_AUTH_COMPOSE="${TEST_DIR}/compose.unit.ingest-auth.yml"
# 응답기 실행 계정의 정본 — 응답기 이미지 Dockerfile 의 최종 USER.
INGEST_AUTH_DOCKERFILE="$(cd -- "${MEDIA_HOST_DIR}/../.." && pwd)"
INGEST_AUTH_DOCKERFILE="${INGEST_AUTH_DOCKERFILE}/media/Dockerfile.ingest-auth"
readonly INGEST_AUTH_DOCKERFILE
# compose 렌더본의 응답기 서비스 키 줄(시작 스크립트의 모드 판정과 같은 줄).
readonly INGEST_AUTH_SERVICE_LINE='  ingest-auth:'

# 기대값 jq 정의(render_test 의 JQ_DEFS 에 붙는다). 입력은 갈래 변수 JSON.
#   ia_env   — 응답기 environment 정확 집합(계획 4-c4).
#   ia_binds — 응답기 bind 다섯 「원본 대상 ro|rw」.
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly AUTH_JQ_DEFS='
def ia_env:
  {INGEST_AUTH_LISTEN: "127.0.0.1:8080", INGEST_AUTH_CALLER_IP: "127.0.0.1",
   MTX_API_URL: "http://127.0.0.1:9997", MTX_API_USER: "pokeclip-sidecar",
   MTX_API_PASS_FILE: "/run/secrets/mtx_api_pass",
   STREAM_KEY_RESOLVE_URL: .stream_key_resolve_url,
   STREAM_KEY_RESOLVE_CHANNEL: "internal-token",
   STREAM_KEY_RESOLVE_TOKEN_FILE: "/run/secrets/stream_key_resolve_token",
   INGEST_AUTH_BASIC_USER: "pokeclip-mediamtx",
   INGEST_AUTH_BASIC_PASS_FILE: "/run/secrets/ingest_basic_pass"};
def ia_binds:
  "/run/pokeclip-media/ingest-auth" as $run
  | (["mtx_api_pass", "stream_key_resolve_token", "ingest_basic_pass"]
     | map("\($run)/\(.) /run/secrets/\(.) ro"))
  + ["/var/lib/pokeclip-media/ingest-auth/deny /etc/ingest-auth ro",
     "\($run)/state /var/lib/ingest-auth rw"];
'

auth_setup_reason=""

# scratch 의 env 렌더본(ingest-auth.env) 경로를 낸다 — compose 렌더본 옆이다.
auth_cases::env_render() { printf '%s/%s' "${scratch}" "${IP_LIMIT_ENV_NAME}"; }

# 갈래 하나($1)의 env 를 풀지 않은 구성 JSON 경로를 낸다.
auth_cases::noenv_json() {
  printf '%s/compose-%s.noenv.json' "${scratch}" "$1"
}

#######################################
# env 템플릿을 compose 렌더본 옆(scratch)에 렌더한다 — 응답기 갈래의 env_file
# 이 compose 파일 기준 상대 경로라 config 가 그 파일을 읽는다. 실패는 응답기
# 사례만 막는다(옛 갈래 사례는 그 파일을 쓰지 않는다).
#######################################
auth_cases::prepare() {
  if [[ ! -f "${ENV_TEMPLATE}" ]]; then
    auth_setup_reason="${ENV_TEMPLATE#"${MEDIA_HOST_DIR}/"} 없음"
    return 1
  fi
  if ! render::template "${ENV_TEMPLATE}" "$(vars_file a)" \
    "$(auth_cases::env_render)" 2>"${scratch}/env-render.err"; then
    auth_setup_reason="env 템플릿 렌더 실패: \
$(harness::log_tail "${scratch}/env-render.err")"
    return 1
  fi
}

#######################################
# 응답기 사례의 준비 확인 — 공통 준비(require_setup)와 env 렌더(prepare)가
# 둘 다 됐는지 본다. 실패면 원인을 사례 원인에 더한다.
# Globals:
#   auth_setup_reason
# Returns:
#   0 준비됨 / 1 준비 실패.
#######################################
auth_cases::require() {
  require_setup || return 1
  if [[ -n "${auth_setup_reason}" ]]; then
    harness::add_reason "응답기 준비 실패 — ${auth_setup_reason}"
    return 1
  fi
}

#######################################
# compose 파일 하나의 env 를 풀지 않은 구성 JSON 을 쓴다.
# Arguments:
#   compose 파일, 출력 JSON, compose 앞 인자(없어도 됨 — 예: --profile x).
#######################################
auth_cases::noenv_config() {
  local file="$1" out="$2"
  shift 2
  harness::bounded "${CONFIG_TIMEOUT}" docker compose -p "${PROJECT}" "$@" \
    -f "${file}" config --no-normalize --no-env-resolution --format json \
    >"${out}.raw" 2>"${out}.err" || return 1
  jq -e . "${out}.raw" >"${out}" 2>>"${out}.err"
}

#######################################
# 응답기 갈래마다 env 를 풀지 않은 구성 JSON 에 jq 필터를 적용한 값이 기대값과
# 같은지 본다.
# Arguments:
#   실제값 jq 필터, 기대값 jq 프로그램(입력 = 갈래 변수 JSON · AUTH_JQ_DEFS
#   앞에 붙음), 이름, 기대값 jq 의 추가 인자.
#######################################
auth_cases::expect_noenv() {
  local actual="$1" program="$2" name="$3" ok=0 v want got json
  shift 3
  for v in ${AUTH_VARIANTS}; do
    json="$(auth_cases::noenv_json "${v}")"
    if [[ ! -s "${json}" ]] \
      && ! auth_cases::noenv_config "$(compose_render "${v}")" "${json}"; then
      harness::add_reason "갈래 ${v}: env 를 풀지 않은 config 실패: \
$(harness::log_tail "${json}.err")"
      ok=1
      continue
    fi
    want="$(jq -cS "$@" "${AUTH_JQ_DEFS} ${program}" "$(vars_file "${v}")")" \
      || { harness::add_reason '시험 오류(기대값 jq)'; return 1; }
    got="$(jq -cS "$@" "${actual}" "${json}" 2>/dev/null)" \
      || got='(구성 JSON 을 읽지 못함)'
    if [[ "${got}" != "${want}" ]]; then
      harness::add_reason "갈래 ${v}: ${name} 기대 ${want} · 실제 ${got}"
      ok=1
    fi
  done
  return "${ok}"
}

# 응답기 갈래에만 expect_config 를 건다(인자는 expect_config 의 갈래 뒤 인자).
auth_cases::expect_auth() {
  local ok=0 v
  for v in ${AUTH_VARIANTS}; do
    expect_config "${v}" "$@" || ok=1
  done
  return "${ok}"
}

#######################################
# 응답기 이미지 main.go 의 문자열 상수 하나를 낸다(정확히 한 줄이어야 한다).
# Arguments:
#   상수 이름.
#######################################
auth_cases::go_const() {
  local got pattern
  pattern="s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p"
  got="$(sed -n "${pattern}" \
    "${RESPONDER_MAIN}" 2>/dev/null)" || return 1
  [[ -n "${got}" && "${got}" != *$'\n'* ]] || return 1
  printf '%s' "${got}"
}

#######################################
# 시작 스크립트의 `readonly 이름='값'` 하나를 낸다(정확히 한 줄).
# Arguments:
#   상수 이름.
#######################################
auth_cases::start_const() {
  local got
  got="$(sed -n "s/^readonly $1='\([^']*\)'\$/\1/p" "${START_SCRIPT}" \
    2>/dev/null)" || return 1
  [[ -n "${got}" && "${got}" != *$'\n'* ]] || return 1
  printf '%s' "${got}"
}

#######################################
# 시작 스크립트의 `${이름:-기본값}` 기본값을 낸다(서로 다른 값이 하나뿐이어야
# 한다).
# Arguments:
#   변수 이름.
#######################################
auth_cases::start_default() {
  local got
  got="$(grep -o -E "\\\$\\{$1:-[^}]+\\}" "${START_SCRIPT}" 2>/dev/null \
    | sort -u)" || return 1
  [[ -n "${got}" && "${got}" != *$'\n'* ]] || return 1
  got="${got#*:-}"
  printf '%s' "${got%\}}"
}

# 사례 35 — 응답기 environment 가 정확히 ia_env 다(env_file 은 풀지 않고 본다).
auth_cases::check_35() {
  auth_cases::require || return 1
  auth_cases::expect_noenv '.services["ingest-auth"].environment // null' \
    'ia_env' '응답기 env 정확 집합(env_file 풀지 않음)'
}

#######################################
# 사례 36 — `*_FILE` env 값은 그 서비스의 읽기 전용 bind 대상이다(모든 갈래).
# 응답기의 거부 목록 · 상태 디렉터리 bind 대상은 응답기 이미지 고정 자리
# (main.go denyListFile 의 디렉터리 · connLogDirectory)와 같고, 거부 목록만
# 읽기 전용이다.
# Globals:
#   RESPONDER_MAIN, AUTH_VARIANTS
# Returns:
#   0 통과 / 1 어긋남 · 읽지 못함.
#######################################
auth_cases::check_36() {
  local ok=0 deny_file log_dir v got want
  auth_cases::require || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  expect_all '[.services | to_entries[] | .key as $s | .value as $c
      | ([($c.volumes // [])[] | select(.type == "bind" and .read_only == true)
          | .target]) as $ro
      | ($c.environment // {}) | to_entries[]
      | select(.key | endswith("_FILE"))
      | select(.value as $f | $ro | index($f) == null)
      | "\($s).\(.key)=\(.value)"]' '[]' \
    '_FILE env 값 ⊄ 그 서비스 읽기 전용 bind 대상' || ok=1
  if ! deny_file="$(auth_cases::go_const denyListFile)" \
    || ! log_dir="$(auth_cases::go_const connLogDirectory)"; then
    harness::add_reason 'media/cmd/ingest-auth/main.go 고정 자리를 읽지 못함'
    return 1
  fi
  for v in ${AUTH_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    got="$(jq -c --arg deny "${deny_file%/*}" --arg log "${log_dir}" '
      [.services["ingest-auth"].volumes[]
       | select(.target == $deny or .target == $log)
       | "\(.type) \(.target) \(.read_only // false)"] | sort' \
      "$(config_json "${v}")" 2>/dev/null)" || got='(구성 JSON 을 읽지 못함)'
    want="[\"bind ${deny_file%/*} true\",\"bind ${log_dir} false\"]"
    if [[ "${got}" != "${want}" ]]; then
      harness::add_reason "갈래 ${v}: 거부 목록 · 상태 디렉터리 bind(응답기 \
이미지 고정 자리) 기대 ${want} · 실제 ${got}"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 37 — 응답기 healthcheck(exec 꼴) · 읽기 전용 루트 · user 없음(실행 계정은
# 이미지 USER — 사례 45).
auth_cases::check_37() {
  auth_cases::require || return 1
  auth_cases::expect_auth '.services["ingest-auth"]
    | {read_only: (.read_only // null), user: (.user // null),
       healthcheck: (.healthcheck // null)}' '
    {read_only: true, user: null,
     healthcheck: {test: ["CMD", "/ingest-auth", "healthcheck"],
       interval: "30s", retries: 3, start_period: "10s"}}' \
    'healthcheck · 읽기 전용 루트 · user 없음'
}

#######################################
# 사례 38 — 응답기 env_file 은 compose 파일 옆 ingest-auth.env 하나이고 required
# 다. 풀린 env 에 그 파일의 IP 한도 값이 들어가고, 그 파일이 없는 자리에서는
# config 가 그 파일 이름을 대며 실패한다.
# Globals:
#   scratch, IP_LIMIT_ENV_NAME, PROJECT, CONFIG_TIMEOUT
# Returns:
#   0 통과 / 1 어긋남.
#######################################
auth_cases::check_38() {
  local ok=0 dir="${scratch}/no-env-file" err
  auth_cases::require || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  auth_cases::expect_noenv '[.services["ingest-auth"].env_file // [] | .[]
      | {beside: (.path | endswith("/\($dir)/\($name)")),
         required: (if .required == false then false else true end)}]' \
    '[{beside: true, required: true}]' 'env_file(compose 파일 옆 · required)' \
    --arg dir "${scratch##*/}" --arg name "${IP_LIMIT_ENV_NAME}" || ok=1
  auth_cases::expect_auth \
    '.services["ingest-auth"].environment.INGEST_AUTH_IP_LIMIT // null' \
    '.ingest_auth_ip_limit' 'env_file 값이 풀린 env' || ok=1
  mkdir -p -- "${dir}"
  cp -- "$(compose_render b)" "${dir}/compose.yml"
  err="${dir}/config.err"
  if harness::run_bounded "${CONFIG_TIMEOUT}" "${err}" docker compose \
    -p "${PROJECT}" -f "${dir}/compose.yml" config -q; then
    harness::add_reason "env 파일 없는 자리에서 config 가 성공함(required 아님)"
    ok=1
  elif ! grep -q -F "${IP_LIMIT_ENV_NAME}" "${err}"; then
    harness::add_reason "env 파일 없음 config 실패 원인이 그 파일이 아님: \
$(harness::log_tail "${err}")"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사례 39 — 모드 일치. 같은 변수 파일로 렌더한 compose 의 응답기 서비스(시작
# 스크립트가 보는 키 줄)와 mediamtx 의 응답기 모드(authMethod http · Basic
# 토큰)가 함께 있거나 함께 없다.
#######################################
auth_cases::check_39() {
  local ok=0 v want lines tokens method
  auth_cases::require || return 1
  for v in a b; do
    want=0
    [[ "${v}" == a ]] || want=1
    lines="$(harness::count_fixed "${INGEST_AUTH_SERVICE_LINE}" \
      "$(compose_render "${v}")")" || lines='?'
    tokens="$(harness::count_fixed "${BASIC_PASS_TOKEN}" \
      "$(mtx_render "${v}")")" || tokens='?'
    method="$(jq -r '.authMethod // "internal"' "$(mtx_json "${v}")" \
      2>/dev/null)" || method='?'
    if [[ "${lines}" != "${want}" || "${tokens}" != "${want}" ]] \
      || [[ "${want}" == 1 && "${method}" != http ]] \
      || [[ "${want}" == 0 && "${method}" == http ]]; then
      harness::add_reason "갈래 ${v}: compose 응답기 줄 ${lines} · mediamtx \
Basic 토큰 ${tokens} · authMethod ${method}(기대 ${want})"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 40 — 망 칸 포트가 서로 다르다: 응답기 듣기 · 헬스, MediaMTX LL-HLS ·
# 재생 서버 · 관리 API · SRT, 프록시 네 포트.
#######################################
auth_cases::check_40() {
  local ok=0 health listen ports p list dup
  auth_cases::require || return 1
  if ! health="$(auth_cases::go_const healthListen)"; then
    harness::add_reason 'main.go healthListen 을 읽지 못함'
    return 1
  fi
  listen="$(jq -r '.services["ingest-auth"].environment.INGEST_AUTH_LISTEN
    // empty' "$(config_json b)" 2>/dev/null)" || listen=''
  ports="$(jq -r '.hlsAddress, .playbackAddress, .apiAddress, .srtAddress' \
    "$(mtx_json b)" 2>/dev/null)" || ports=''
  ports="${ports}"$'\n'"$(jq -r '.proxy_live_port, .proxy_playback_port,
    .proxy_control_port, .proxy_health_port' "$(vars_file b)")"
  list=''
  for p in "${listen}" "${health}" ${ports}; do
    p="${p##*:}"
    if [[ ! "${p}" =~ ^[0-9]+$ ]]; then
      harness::add_reason "포트 판독 불가: '${p}'"
      ok=1
    fi
    list="${list}${p}"$'\n'
  done
  dup="$(printf '%s' "${list}" | sort | uniq -d | tr '\n' ' ')"
  if [[ -n "${dup}" ]] || (( $(printf '%s' "${list}" | grep -c .) != 10 )); then
    harness::add_reason "망 칸 포트 겹침 · 개수 어긋남: 겹침 '${dup}' · \
목록 $(printf '%s' "${list}" | tr '\n' ' ')"
    ok=1
  fi
  return "${ok}"
}

# 사례 41 — env 템플릿 렌더가 on · off 각각 정확히 `INGEST_AUTH_IP_LIMIT=<값>`
# 한 줄이다(바이트 대조).
auth_cases::check_41() {
  local ok=0 value vars out
  auth_cases::require || return 1
  for value in on off; do
    vars="${scratch}/vars-ip-${value}.json"
    out="${scratch}/env-${value}"
    jq --arg v "${value}" '.ingest_auth_ip_limit = $v' "$(vars_file a)" \
      >"${vars}" || { harness::add_reason '시험 오류: 변수 파일'; return 1; }
    if ! render::template "${ENV_TEMPLATE}" "${vars}" "${out}" \
      2>"${out}.err"; then
      harness::add_reason "env 템플릿 렌더 실패(${value}): \
$(harness::log_tail "${out}.err")"
      ok=1
    elif ! printf 'INGEST_AUTH_IP_LIMIT=%s\n' "${value}" | cmp -s - "${out}"
    then
      harness::add_reason "env 렌더(${value})가 정확히 한 줄이 아님: \
$(od -c "${out}" | head -3 | tr '\n' ' ')"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 42 — 응답기 bind 원본 = 시작 스크립트가 쓰는 자리: 거부 목록 디렉터리 =
# INGEST_AUTH_DENY_DIR 기본값, 비밀 셋 · 상태 = RUNTIME_DIR 기본값 아래
# 응답기 디렉터리의 그 이름.
#######################################
auth_cases::check_42() {
  local deny runtime dir api basic token state
  auth_cases::require || return 1
  if ! deny="$(auth_cases::start_default INGEST_AUTH_DENY_DIR)" \
    || ! runtime="$(auth_cases::start_default RUNTIME_DIR)" \
    || ! dir="$(auth_cases::start_const INGEST_AUTH_DIR_NAME)" \
    || ! api="$(auth_cases::start_const API_PASS_NAME)" \
    || ! basic="$(auth_cases::start_const BASIC_PASS_NAME)" \
    || ! token="$(auth_cases::start_const RESOLVE_TOKEN_NAME)" \
    || ! state="$(auth_cases::start_const STATE_DIR_NAME)"; then
    harness::add_reason 'bin/start-media.sh 기본 경로 · 이름을 읽지 못함'
    return 1
  fi
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  auth_cases::expect_auth '[.services["ingest-auth"].volumes[]
      | select(.type == "bind") | .source] | sort' '
    [$deny, "\($run)/\($api)", "\($run)/\($basic)", "\($run)/\($token)",
     "\($run)/\($state)"] | sort' 'bind 원본 = 시작 스크립트 자리' \
    --arg deny "${deny}" --arg run "${runtime}/${dir}" --arg api "${api}" \
    --arg basic "${basic}" --arg token "${token}" --arg state "${state}"
}

#######################################
# 사례 43 — 옛 갈래(a · c) 렌더 구성이 develop 골든과 같다(최상위 키 · 서비스
# 단위로 차이를 댄다).
# Globals:
#   COMPOSE_GOLDEN_PREFIX, LEGACY_VARIANTS, TEST_DIR
# Returns:
#   0 같음 / 1 다름 · 골든 없음.
#######################################
auth_cases::check_43() {
  local ok=0 v golden diffs
  require_setup || return 1
  for v in ${LEGACY_VARIANTS}; do
    golden="${COMPOSE_GOLDEN_PREFIX}.${v}.json"
    if [[ ! -f "${golden}" ]]; then
      harness::add_reason "${golden#"${TEST_DIR}/"} 없음"
      ok=1
      continue
    fi
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    if ! diffs="$(jq -n -r --slurpfile g "${golden}" \
      --slurpfile r "$(config_json "${v}")" '
      ($g[0] | keys) + ($r[0] | keys) | unique[] as $k
      | if $k == "services"
        then (($g[0].services | keys) + ($r[0].services | keys)) | unique[]
          | select($g[0].services[.] != $r[0].services[.]) | "services.\(.)"
        else select($g[0][$k] != $r[0][$k]) | $k end' 2>&1)"; then
      harness::add_reason "갈래 ${v}: 골든 비교 실패: ${diffs}"
      ok=1
    elif [[ -n "${diffs}" ]]; then
      harness::add_reason "갈래 ${v}: 골든과 다른 키 $(printf '%s' \
        "${diffs}" | tr '\n' ' ')"
      ok=1
    fi
  done
  return "${ok}"
}

# 계약 시험 compose 와 렌더(갈래 b)의 드리프트 — 응답기 · mediamtx 서비스.
# 마운트는 대상이 같으면 같은 자리다. 비밀 셋만 예외로, 시험 쪽 대상이 비밀
# 디렉터리(/run/secrets)이고 prod 대상이 그 아래 파일이면 덮는다고 친다(시험은
# 비밀 셋을 디렉터리 볼륨 하나로 준다). 거부 목록 · 상태 디렉터리는 대상이
# 정확히 같아야 한다 — 거부 목록을 파일 bind 로 바꾸면(원자 rename 이 반영되지
# 않는다) 여기서도 FAIL 이다. 4C 주소 값 · 시험 전용 depends_on(도우미 · 가짜
# 4C) · healthcheck start_interval 은 뺀다.
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly IA_DRIFT_PROGRAM='
def mounts: [(.volumes // [])[] | {t: .target, ro: (.read_only // false)}];
def secret_dir: "/run/secrets";
def covers($m; $n): $m.t == $n.t
  or ($n.t == secret_dir and ($m.t | startswith(secret_dir + "/")));
def core: {network_mode: (.network_mode // null),
  restart: (.restart // null), read_only: (.read_only // null),
  user: (.user // null), security_opt: ((.security_opt // []) | sort),
  cap_drop: ((.cap_drop // []) | sort), cap_add: (.cap_add // null),
  ulimits: (.ulimits // null),
  mediamtx_dep: (.depends_on.mediamtx // null
    | if . == null then null else {condition, restart} end),
  env: ((.environment // {}) | del(.STREAM_KEY_RESOLVE_URL)),
  env_keys: ((.environment // {}) | keys),
  env_file: [(.env_file // [])[] | {name: (.path | split("/") | last),
    required: (if .required == false then false else true end)}],
  healthcheck: ((.healthcheck // {}) | {test, interval, retries,
    start_period, timeout})};
def mtx_core: {restart: (.restart // null),
  security_opt: ((.security_opt // []) | sort),
  cap_drop: ((.cap_drop // []) | sort), ulimits: (.ulimits // null),
  env: (.environment // {}), mounts: (mounts | sort_by(.t))};
def diff($s; $pc; $tc):
  $pc | keys[] as $k | select($pc[$k] != $tc[$k])
  | "\($s).\($k): prod \($pc[$k] | tojson) · 시험 \($tc[$k] | tojson)";
$p[0].services as $ps | $t[0].services as $ts
| if $ps["ingest-auth"] == null or $ts["ingest-auth"] == null
  then "응답기 서비스 없음(prod \($ps["ingest-auth"] != null) · "
    + "시험 \($ts["ingest-auth"] != null))"
  else
    diff("ingest-auth"; $ps["ingest-auth"] | core; $ts["ingest-auth"] | core),
    diff("mediamtx"; $ps.mediamtx | mtx_core; $ts.mediamtx | mtx_core),
    (($ps["ingest-auth"] | mounts) as $pm | ($ts["ingest-auth"] | mounts) as $tm
     | ($pm[] as $m
        | select([$tm[] | select(covers($m; .) and .ro == $m.ro)] | length != 1)
        | "ingest-auth 마운트 \($m.t)(ro=\($m.ro)): 시험에서 덮는 마운트가 1개가 아님"),
       ($tm[] as $n | select([$pm[] | select(covers(.; $n))] | length == 0)
        | "ingest-auth 시험 마운트 \($n.t): prod 에 대응 없음"))
  end
'

#######################################
# 사례 44 — 계약 시험 compose(compose.ingest-auth.yml)의 응답기 · mediamtx
# 서비스가 렌더(갈래 b)와 구조가 같다(IA_DRIFT_PROGRAM). 그 파일의 변수는 구조
# 비교에 쓰지 않는 값으로 채운다.
# Globals:
#   scratch, INGEST_AUTH_COMPOSE, IA_DRIFT_PROGRAM
# Returns:
#   0 드리프트 없음 / 1 드리프트 · config 실패.
#######################################
auth_cases::check_44() {
  local prod trial diffs
  auth_cases::require || return 1
  prod="$(auth_cases::noenv_json b)"
  trial="${scratch}/ingest-auth-compose.noenv.json"
  if [[ ! -s "${prod}" ]] \
    && ! auth_cases::noenv_config "$(compose_render b)" "${prod}"; then
    harness::add_reason "갈래 b config 실패: $(harness::log_tail "${prod}.err")"
    return 1
  fi
  # 구조 비교에 쓰지 않는 값으로 채운다(빈 값이면 compose 가 멈춘다).
  printf '%s\n' PCBENCH_MTX_IMAGE=x PCBENCH_INGEST_AUTH_IMAGE=x \
    PCBENCH_ECHO_IMAGE=x PCBENCH_HELPER_IMAGE=x PCBENCH_SENDER_IMAGE=x \
    PCBENCH_PASSPHRASE=x PCBENCH_INTERNAL_TOKEN=x PCBENCH_STREAM_TOKEN=x \
    PCBENCH_API_PASS=x PCBENCH_RESOLVE_URL=http://resolvestub:28082 \
    "PCBENCH_MTX_CONFIG=${scratch}/mediamtx.yml" "RUNTIME_DIR=${scratch}" \
    >"${trial}.env"
  if ! auth_cases::noenv_config "${INGEST_AUTH_COMPOSE}" "${trial}" \
    --env-file "${trial}.env" --profile stub --profile real --profile tools
  then
    harness::add_reason "계약 시험 compose config 실패: \
$(harness::log_tail "${trial}.err")"
    return 1
  fi
  if ! diffs="$(jq -n -r --slurpfile p "${prod}" --slurpfile t "${trial}" \
    "${IA_DRIFT_PROGRAM}" 2>&1)"; then
    harness::add_reason "드리프트 비교 실패: ${diffs}"
    return 1
  fi
  if [[ -n "${diffs}" ]]; then
    harness::add_reason "드리프트: $(printf '%s' "${diffs}" | tr '\n' ';')"
    return 1
  fi
}

#######################################
# 사례 45 — 응답기 실행 계정. compose 는 응답기에 user 를 적지 않으므로(사례
# 37) 이미지 Dockerfile.ingest-auth 최종 스테이지의 USER 가 정본이다. 시작
# 스크립트는 응답기 비밀 셋을 INGEST_AUTH_UID · INGEST_AUTH_GID 기본값 소유로
# 둔다 — 둘이 갈리면 응답기가 비밀을 읽지 못한다. USER 는 사례 9(janitor ⇔
# Dockerfile.mtxhook)와 같은 규칙(마지막 FROM 뒤 마지막 USER)으로 읽는다.
# Globals:
#   INGEST_AUTH_DOCKERFILE, START_SCRIPT
# Returns:
#   0 같음 / 1 다름 · 읽지 못함.
#######################################
auth_cases::check_45() {
  local image_user uid gid
  if [[ ! -f "${INGEST_AUTH_DOCKERFILE}" ]]; then
    harness::add_reason 'media/Dockerfile.ingest-auth 없음(응답기 실행 계정 정본)'
    return 1
  fi
  if ! image_user="$(dockerfile_user "${INGEST_AUTH_DOCKERFILE}")" \
    || [[ -z "${image_user}" ]]; then
    harness::add_reason 'media/Dockerfile.ingest-auth 최종 USER 를 읽지 못함'
    return 1
  fi
  if ! uid="$(auth_cases::start_default INGEST_AUTH_UID)" \
    || ! gid="$(auth_cases::start_default INGEST_AUTH_GID)"; then
    harness::add_reason 'bin/start-media.sh 응답기 소유 기본값을 읽지 못함'
    return 1
  fi
  if [[ "${image_user}" != "${uid}:${gid}" ]]; then
    harness::add_reason "Dockerfile.ingest-auth 최종 USER ${image_user} · \
start-media 기본 소유 ${uid}:${gid}"
    return 1
  fi
}

# 응답기 모드 유닛 시험 compose 와 렌더(갈래 b)의 응답기 서비스 드리프트.
# 아래 제외 목록 밖의 키는 전부 같아야 한다(망 칸 · depends_on · restart ·
# read_only · security_opt · cap · ulimits · env_file · bind · logging tag 등 —
# prod 에 키가 늘어도 잡힌다). 제외 목록 = 의도된 차이(그 시험 compose 머리
# 주석): 이미지 · 명령(셸) · healthcheck(/bin/true · start_interval) ·
# environment(셸은 응답기 설정을 읽지 않는다) · user(prod 는 이미지 USER —
# 사례 45, 시험 이미지에는 그 계정이 없어 적는다) · labels(실험 식별). bind 는
# 원본(시험 env 가 정함)을 빼고, env_file 은 파일 이름 · required 로 본다.
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly UNIT_IA_DRIFT_PROGRAM='
def excluded: ["image", "entrypoint", "command", "healthcheck", "environment",
  "user", "labels"];
def facts: with_entries(select(.key as $k | excluded | index($k) == null))
  | if has("volumes") then .volumes |= (map(del(.source)) | sort_by(.target))
    else . end
  | if has("env_file") then .env_file |= map({name: (.path | split("/") | last),
      required: (if .required == false then false else true end)})
    else . end;
($p[0].services["ingest-auth"] // null) as $ps
| ($t[0].services["ingest-auth"] // null) as $ts
| if $ps == null or $ts == null
  then "응답기 서비스 없음(prod \($ps != null) · 시험 \($ts != null))"
  else ($ps | facts) as $pf | ($ts | facts) as $tf
    | (($pf | keys) + ($tf | keys) | unique[]) as $k
    | select($pf[$k] != $tf[$k])
    | "ingest-auth.\($k): prod \($pf[$k] | tojson) · 시험 \($tf[$k] | tojson)"
  end
'

#######################################
# 사례 46 — 응답기 모드 유닛 시험 compose(unit_restart_test 사례 22 · 23)의
# 응답기 서비스가 prod 렌더와 구조가 같다(UNIT_IA_DRIFT_PROGRAM). 그 파일은
# 옆의 ingest-auth.env 를 required 로 읽으므로, env 렌더본이 있는 scratch 로
# 복사해 config 를 돈다. include 하는 compose.unit.yml 쪽은 사례 25 가 본다.
# Globals:
#   scratch, UNIT_INGEST_AUTH_COMPOSE, UNIT_COMPOSE, UNIT_IA_DRIFT_PROGRAM
# Returns:
#   0 드리프트 없음 / 1 드리프트 · config 실패.
#######################################
auth_cases::check_46() {
  local prod trial diffs copy="${scratch}/compose.unit.ingest-auth.yml"
  auth_cases::require || return 1
  if [[ ! -f "${UNIT_INGEST_AUTH_COMPOSE}" ]]; then
    harness::add_reason 'test/compose.unit.ingest-auth.yml 없음'
    return 1
  fi
  prod="$(auth_cases::noenv_json b)"
  trial="${scratch}/unit-ingest-auth.noenv.json"
  if [[ ! -s "${prod}" ]] \
    && ! auth_cases::noenv_config "$(compose_render b)" "${prod}"; then
    harness::add_reason "갈래 b config 실패: $(harness::log_tail "${prod}.err")"
    return 1
  fi
  cp -- "${UNIT_INGEST_AUTH_COMPOSE}" "${copy}" || return 1
  { unit_env_lines
    printf '%s\n' "PCBENCH_UNIT_COMPOSE=${UNIT_COMPOSE}" \
      "INGEST_AUTH_DENY_DIR=${scratch}/deny"; } >"${trial}.env"
  if ! auth_cases::noenv_config "${copy}" "${trial}" --env-file "${trial}.env"
  then
    harness::add_reason "응답기 모드 유닛 시험 compose config 실패: \
$(harness::log_tail "${trial}.err")"
    return 1
  fi
  if ! diffs="$(jq -n -r --slurpfile p "${prod}" --slurpfile t "${trial}" \
    "${UNIT_IA_DRIFT_PROGRAM}" 2>&1)"; then
    harness::add_reason "드리프트 비교 실패: ${diffs}"
    return 1
  fi
  if [[ -n "${diffs}" ]]; then
    harness::add_reason "드리프트: $(printf '%s' "${diffs}" | tr '\n' ';')"
    return 1
  fi
}
