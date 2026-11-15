# shellcheck shell=bash
# 전역(scratch · MEDIA_HOST_DIR · COMPOSE_TEMPLATE · COMPOSE_VARIANTS ·
# RENDER_VARS_FILE · JQ_DEFS · CRED_RELAY_ENTRYPOINT)과 require_setup ·
# vars_file · compose_render · config_json · compose_config · expect_config ·
# expect_fixed_absent 는
# render_test.sh 가, CRED_RELAY_ADDR · CRED_CALLERS · CRED_RELAY_DIR_NAME ·
# CRED_CONSUMER_DIR_PREFIX · CRED_TOKEN_NAME · CRED_JANITOR_OWNER 는
# bin/lib/cred_relay.sh(render_test 가 source)가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 IMDS 대리 창구 사례(60 – 67). 켬 키(cred_relay = true)의
# compose 렌더에만 중계 서비스(cred-relay — 호스트 망) · 소비자(사이드카 ·
# janitor) env 셋 · 토큰 디렉터리 bind · 중계 의존이 있어야 한다. 끔 렌더는 이
# 기능 전과 바이트까지 같다 — 새 설명은 모두 렌더에 남지 않는 템플릿 주석이다
# (구성 동일은 사례 43 의 골든, 바이트 동일은 PR 검증의 base 렌더 cmp).
# 켬 렌더의 일반 단언(서비스 집합 · 이미지 · 망 칸 · 볼륨 · env · 비밀 이름 ·
# 권한 · logging)은 render_test 의 갈래 e 가 사례 1 – 59 를 지나며 본다.
#
# 갈래(render_vars.json 을 jq 로 고친 사본):
#   e  켬 · 응답기 · janitor(render_test 가 만든다 — 일반 갈래)
#   r1 켬 · 옛 모드 · janitor 끔. 중계 호출자 목록은 그대로다 — 시작 스크립트는
#      janitor 토큰도 두지만 받는 소비자가 없다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly CRED_LIB_VARIANTS='r1'
readonly CRED_ON_VARIANTS='e r1'
readonly CRED_SERVICE='cred-relay'
# 중계 듣기 포트 — 모듈 variables.tf 의 proxy_ports 금지 목록에도 있어야 한다
# (호스트 망에서 Docker 공개 포트 DNAT 가 같은 포트를 가로채지 않게).
readonly CRED_RELAY_PORT=9911
readonly CRED_RUNTIME_DIR='/run/pokeclip-media'
readonly CRED_CONSUMER_TARGET='/run/secrets/aws-cred'
readonly CRED_RELAY_TARGET='/run/secrets/cred-relay'
CRED_MODULE_VARIABLES="$(cd -- "${MEDIA_HOST_DIR}/.." && pwd)"
CRED_MODULE_VARIABLES="${CRED_MODULE_VARIABLES}/terraform/modules/media_host"
CRED_MODULE_VARIABLES="${CRED_MODULE_VARIABLES}/variables.tf"
readonly CRED_MODULE_VARIABLES
# 렌더에 남으면 안 되는 템플릿 주석 속 글자(compose.yml.tmpl 의 창구 주석).
readonly CRED_COMMENT_MARK='대리 창구'

# 기대값 jq 정의(render_test 의 JQ_DEFS 에 붙는다). 입력은 갈래 변수 JSON.
#   cred_consumers  — 창구 env 셋 · 토큰 bind · 중계 의존을 받는 서비스.
#   cred_env        — 소비자 env 셋(SDK 컨테이너 자격 경로 · IMDS 끔).
#   cred_bind($c)   — 소비자 토큰 디렉터리 bind 「원본 대상 ro」.
#   cred_relay_bind · cred_relay_env — 중계의 bind 와 env 정확 집합.
# render_test 의 JQ_DEFS 가 읽는다. jq 프로그램이라 $ 는 jq 변수다.
# shellcheck disable=SC2016,SC2034
readonly CRED_JQ_DEFS='
def cred_consumers:
  if .cred_relay
  then ["sidecar"] + (if .janitor_enabled then ["janitor"] else [] end)
  else [] end;
def cred_env:
  {AWS_CONTAINER_CREDENTIALS_FULL_URI:
     "http://169.254.170.2:9911/v1/credentials",
   AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE: "/run/secrets/aws-cred/token",
   AWS_EC2_METADATA_DISABLED: "true"};
def cred_bind($c): "/run/pokeclip-media/cred-\($c) /run/secrets/aws-cred ro";
def cred_relay_bind:
  "/run/pokeclip-media/cred-relay /run/secrets/cred-relay ro";
def cred_relay_env:
  {CRED_RELAY_LISTEN: "169.254.170.2:9911",
   CRED_RELAY_CALLER_DIR: "/run/secrets/cred-relay",
   CRED_RELAY_CALLERS: "indexer,janitor"};
'

cred_setup_reason=""

#######################################
# 이 파일의 갈래 변수 파일을 만들고 compose 템플릿을 렌더해 구성 JSON 을 만든다.
# 실패는 이 사례들만 막는다.
# Globals:
#   RENDER_VARS_FILE, cred_setup_reason
# Returns:
#   0 전부 준비 / 1 실패.
#######################################
cred_cases::prepare() {
  local v out json
  for v in ${CRED_LIB_VARIANTS}; do
    if ! jq '.cred_relay = true' "${RENDER_VARS_FILE}" \
      >"$(vars_file "${v}")"; then
      cred_setup_reason='창구 갈래 변수 파일을 만들지 못함(jq)'
      return 1
    fi
    out="$(compose_render "${v}")"
    json="$(config_json "${v}")"
    if ! render::template "${COMPOSE_TEMPLATE}" "$(vars_file "${v}")" \
      "${out}" 2>"${out}.err"; then
      cred_setup_reason="${cred_setup_reason:+${cred_setup_reason}; }갈래 \
${v} 렌더 실패: $(harness::log_tail "${out}.err")"
    elif ! compose_config "${out}" "${json}"; then
      cred_setup_reason="${cred_setup_reason:+${cred_setup_reason}; }갈래 \
${v} docker compose config 실패: $(harness::log_tail "${json}.err")"
    fi
  done
  [[ -z "${cred_setup_reason}" ]]
}

#######################################
# 창구 갈래의 준비 확인 — 공통 준비와 이 파일의 준비가 둘 다 됐는지.
# Globals:
#   cred_setup_reason
# Returns:
#   0 준비됨 / 1 준비 실패.
#######################################
cred_cases::require() {
  require_setup || return 1
  if [[ -n "${cred_setup_reason}" ]]; then
    harness::add_reason "창구 갈래 준비 실패 — ${cred_setup_reason}"
    return 1
  fi
}

#######################################
# 갈래 하나에 jq 점검 프로그램을 돌려, 나온 줄마다 실패 원인으로 남긴다. 점검
# 프로그램은 $v(갈래 변수 JSON) · $c(구성 JSON)를 읽고 어긋남마다 한 줄을 낸다
# (JQ_DEFS 를 앞에 붙인다).
# Arguments:
#   갈래, 점검 jq 프로그램, jq 추가 인자(없어도 됨).
# Returns:
#   0 어긋남 없음 / 1 어긋남 · 읽지 못함.
#######################################
cred_cases::expect_none() {
  local v="$1" program="$2" lines
  shift 2
  if ! lines="$(jq -n -r --slurpfile vv "$(vars_file "${v}")" \
    --slurpfile cc "$(config_json "${v}")" "$@" \
    "${JQ_DEFS} \$vv[0] as \$v | \$cc[0] as \$c | ${program}" 2>&1)"; then
    harness::add_reason "갈래 ${v}: 점검 실패(구성 JSON · jq): ${lines}"
    return 1
  fi
  if [[ -n "${lines}" ]]; then
    harness::add_reason "갈래 ${v}: $(printf '%s' "${lines}" | tr '\n' ';')"
    return 1
  fi
}

#######################################
# 사례 60(RC1) — 중계 서비스의 꼴이 정확하다. 사이드카 이미지 · 중계 진입점 ·
# 호스트 망 · restart on-failure · 읽기 전용 루트 · cap 없음 · user 없음(이미지
# USER) · depends_on 없음 · 공개 포트 없음 · env 정확 집합 · 토큰 디렉터리 bind
# 하나(읽기 전용 · 원본 자동 생성 없음) · healthcheck · journald 고정 tag.
#######################################
cred_cases::check_60() {
  local ok=0 v
  cred_cases::require || return 1
  for v in ${CRED_ON_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    expect_config "${v}" '.services["cred-relay"] | {image,
        entrypoint, network_mode, restart, read_only,
        security_opt, cap_drop, cap_add: (.cap_add // null),
        user: (.user // null), depends_on: (.depends_on // null),
        ports: (.ports // null), env_file: (.env_file // null),
        core: (.ulimits.core // null), environment,
        volumes: [(.volumes // [])[] | {type, source, target, read_only,
          create_host_path: .bind.create_host_path}],
        healthcheck: (.healthcheck.test // null),
        tag: (.logging.options.tag // null)}' '
      {image: .sidecar_image, entrypoint: [$entry],
       network_mode: "host", restart: "on-failure", read_only: true,
       security_opt: ["no-new-privileges:true"], cap_drop: ["ALL"],
       cap_add: null, user: null, depends_on: null, ports: null,
       env_file: null, core: {}, environment: cred_relay_env,
       volumes: [{type: "bind", source: "/run/pokeclip-media/cred-relay",
         target: "/run/secrets/cred-relay", read_only: true,
         create_host_path: false}],
       healthcheck: ["CMD", $entry, "healthcheck"],
       tag: (if .detached_log_driver == "json-file" then null
         else "pokeclip-media-cred-relay" end)}' \
      '중계 서비스 꼴(정확값)' --arg entry "${CRED_RELAY_ENTRYPOINT}" \
      || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 61(RC2) — 소비자 env 셋이 완전하고(소비자마다 셋 다 · 정확값), 소비자가
# 아닌 서비스에는 그 셋의 이름이 하나도 없다. janitor 의 user: 는 시작 스크립트가
# 두는 janitor 토큰 파일 소유(bin/lib/cred_relay.sh)와 같다.
#######################################
cred_cases::check_61() {
  local ok=0 v
  cred_cases::require || return 1
  for v in ${CRED_ON_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    cred_cases::expect_none "${v}" '
      ($v | cred_env | keys) as $keys
      | ($v | cred_consumers) as $consumers
      | ($c.services | to_entries[]
         | .key as $s | ((.value.environment // {})
           | with_entries(select(.key as $k | $keys | index($k) != null)))
         | if ($consumers | index($s)) != null
           then select(. != ($v | cred_env))
             | "소비자 env 셋 불완전: \($s)"
           else select(. != {}) | "소비자 아닌 서비스에 창구 env: \($s)"
           end),
        ($c.services.janitor // empty | select(.user != $owner)
         | "janitor user \(.user) ≠ 창구 토큰 소유 \($owner)")' \
      --arg owner "${CRED_JANITOR_OWNER}" || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 62(RC3 · RC3b) — 창구 토큰 디렉터리(RUNTIME_DIR 아래 cred-*) bind 의 서비스 ·
# 디렉터리 쌍이 {사이드카: cred-indexer, janitor: cred-janitor, 중계: cred-relay}
# 와 같고(이름은 bin/lib/cred_relay.sh 의 상수), 소비자 토큰 파일 env 는 그
# bind 대상 안의 토큰 이름이며, 중계의 호출자 목록 · 디렉터리 env 는 시작
# 스크립트의 호출자 차례 · 중계 bind 대상과 같다. 어느 갈래에서도 RUNTIME_DIR
# 이나 그 조상을 bind 원본으로 쓰는 서비스가 없다(토큰 디렉터리를 통째로 보는
# 길 — 설계 정정 E12).
#######################################
cred_cases::check_62() {
  local ok=0 v callers caller service want=""
  cred_cases::require || return 1
  callers="$(IFS=,; printf '%s' "${CRED_CALLERS[*]}")"
  # 호출자 → 소비자 서비스(indexer 는 사이드카) · 소비자 bind 「서비스 원본 대상」.
  for caller in "${CRED_CALLERS[@]}"; do
    service="${caller}"
    [[ "${caller}" != indexer ]] || service=sidecar
    want+="${service} ${CRED_RUNTIME_DIR}/${CRED_CONSUMER_DIR_PREFIX}${caller}"
    want+=" ${CRED_CONSUMER_TARGET}"$'\n'
  done
  want+="${CRED_SERVICE} ${CRED_RUNTIME_DIR}/${CRED_RELAY_DIR_NAME}"
  want+=" ${CRED_RELAY_TARGET}"
  for v in ${CRED_ON_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    cred_cases::expect_none "${v}" '
      ($v | cred_consumers + ["cred-relay"]) as $binders
      | ([$want | split("\n")[] | select(. != "")
          | select(split(" ")[0] as $s | $binders | index($s) != null)]
         | sort) as $want_pairs
      | ([$c.services | to_entries[] | .key as $s
          | (.value.volumes // [])[] | select(.type == "bind"
            and (.source | startswith("\($run)/cred-")))
          | "\($s) \(.source) \(.target)"] | sort) as $got
      | (select($got != $want_pairs)
         | "cred-* bind 서비스 · 디렉터리 쌍이 다르다 — 기대 \($want_pairs) · 실제 \($got)"),
        (($v | cred_consumers)[] as $s
         | $c.services[$s].environment.AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE
         | select(. != "\($target)/\($token)")
         | "\($s) 토큰 파일 env \(.) ≠ \($target)/\($token)"),
        ($c.services["cred-relay"].environment
         | (select(.CRED_RELAY_CALLERS != $callers)
            | "중계 호출자 \(.CRED_RELAY_CALLERS) ≠ 시작 스크립트 \($callers)"),
           (select(.CRED_RELAY_CALLER_DIR != $relay_target)
            | "중계 디렉터리 env \(.CRED_RELAY_CALLER_DIR) ≠ bind 대상"))' \
      --arg want "${want}" --arg run "${CRED_RUNTIME_DIR}" \
      --arg target "${CRED_CONSUMER_TARGET}" \
      --arg relay_target "${CRED_RELAY_TARGET}" \
      --arg token "${CRED_TOKEN_NAME}" --arg callers "${callers}" || ok=1
  done
  for v in ${COMPOSE_VARIANTS} ${CRED_LIB_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    cred_cases::expect_none "${v}" '
      $c.services | to_entries[] | .key as $s
      | (.value.volumes // [])[] | select(.type == "bind")
      | (.source | rtrimstr("/") | if . == "" then "/" else . end) as $src
      | select($src == "/" or $src == $run
          or ($run | startswith("\($src)/")))
      | "RUNTIME_DIR 조상 bind: \($s) \(.source)"' \
      --arg run "${CRED_RUNTIME_DIR}" || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 63(RC4) — 소비자(사이드카 · janitor)는 중계에 depends_on
# (service_started)이고, 소비자가 아닌 서비스는 중계에 기대지 않는다.
#######################################
cred_cases::check_63() {
  local ok=0 v
  cred_cases::require || return 1
  for v in ${CRED_ON_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    cred_cases::expect_none "${v}" '
      ($v | cred_consumers) as $consumers
      | ([$c.services | to_entries[]
          | select(.value.depends_on["cred-relay"].condition
            == "service_started") | .key]) as $deps
      | ([$c.services | to_entries[]
          | select(.value.depends_on["cred-relay"] != null) | .key]) as $any
      | (($consumers - $deps)[] | "depends_on cred-relay 없음: \(.)"),
        (($any - $consumers)[]
         | "소비자 아닌 서비스의 depends_on cred-relay: \(.)")' || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 64(RC5) — 끔 갈래(a – d)의 렌더에는 창구 글자(서비스 · 소비자 env · 토큰
# bind · 주소 · 포트)가 없고, 어느 갈래의 렌더에도 창구 템플릿 주석이 남지
# 않는다(끔 렌더 바이트 동일 · user_data 16 KiB 여유).
#######################################
cred_cases::check_64() {
  local ok=0 v needle
  cred_cases::require || return 1
  for v in ${COMPOSE_VARIANTS}; do
    if [[ "$(jq -r '.cred_relay' "$(vars_file "${v}")")" == true ]]; then
      continue
    fi
    for needle in cred-relay AWS_CONTAINER_ AWS_EC2_METADATA_DISABLED \
      "${CRED_RUNTIME_DIR}/cred-" "${CRED_RELAY_ADDR}" "${CRED_RELAY_PORT}"; do
      expect_fixed_absent "갈래 ${v} 렌더" "${needle}" \
        "$(compose_render "${v}")" || ok=1
    done
  done
  for v in ${COMPOSE_VARIANTS} ${CRED_LIB_VARIANTS}; do
    expect_fixed_absent "갈래 ${v} 렌더" "${CRED_COMMENT_MARK}" \
      "$(compose_render "${v}")" || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 65(RC6) — 망 칸(mediamtx 와 그 네임스페이스를 함께 쓰는 서비스)에서
# 되살리는 cap 은 nginx 의 셋(CHOWN · SETGID · SETUID) 안이다. 망 칸 이웃은
# 창구에 닿아도 토큰이 없어 401 이고, 패킷을 엿볼 cap 도 없어야 한다.
#######################################
cred_cases::check_65() {
  local ok=0 v
  cred_cases::require || return 1
  for v in ${COMPOSE_VARIANTS} ${CRED_LIB_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    cred_cases::expect_none "${v}" '
      $c.services | to_entries[]
      | select(.key == "mediamtx"
          or .value.network_mode == "service:mediamtx")
      | .key as $s | ((.value.cap_add // []) - nginx_cap_add)[]
      | "망 칸 서비스 cap_add 가 \(nginx_cap_add) 밖: \($s):\(.)"' || ok=1
  done
  return "${ok}"
}

#######################################
# 모듈 variables.tf 의 proxy_ports 금지 목록(포트 정수)을 한 줄에 하나씩 낸다.
# Globals:
#   CRED_MODULE_VARIABLES
# Outputs:
#   포트 줄.
# Returns:
#   0 / 1 목록을 정확히 한 군데에서 찾지 못함.
#######################################
cred_cases::proxy_denylist() {
  local found
  found="$(grep -o -E '!contains\(\[[0-9, ]+\], port\)' \
    "${CRED_MODULE_VARIABLES}" 2>/dev/null)" || return 1
  [[ -n "${found}" && "${found}" != *$'\n'* ]] || return 1
  found="${found#*[}"
  printf '%s\n' "${found%%]*}" | tr ',' '\n' | tr -d ' '
}

#######################################
# 사례 66(RC7) — 중계 주소가 세 곳에서 같다: 렌더의 중계 듣기 주소 · 소비자 자격
# URI = 시작 스크립트가 더미 링크에 붙이는 주소(bin/lib/cred_relay.sh) + 포트,
# 그 포트는 모듈 variables.tf 의 proxy_ports 금지 목록에 있다.
#######################################
cred_cases::check_66() {
  local ok=0 v ports listen
  cred_cases::require || return 1
  listen="${CRED_RELAY_ADDR}:${CRED_RELAY_PORT}"
  if ! ports="$(cred_cases::proxy_denylist)"; then
    harness::add_reason \
      'modules/media_host/variables.tf proxy_ports 금지 목록을 읽지 못함'
    ok=1
  elif ! printf '%s\n' "${ports}" | grep -q -x -F -- "${CRED_RELAY_PORT}"; then
    harness::add_reason "proxy_ports 금지 목록에 중계 포트 ${CRED_RELAY_PORT} 없음"
    ok=1
  fi
  for v in ${CRED_ON_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    cred_cases::expect_none "${v}" '
      ($c.services["cred-relay"].environment.CRED_RELAY_LISTEN
       | select(. != $listen) | "중계 듣기 \(.) ≠ \($listen)"),
      (($v | cred_consumers)[] as $s
       | $c.services[$s].environment.AWS_CONTAINER_CREDENTIALS_FULL_URI
       | select(. != "http://\($listen)/v1/credentials")
       | "\($s) 자격 URI \(.) ≠ http://\($listen)/v1/credentials")' \
      --arg listen "${listen}" || ok=1
  done
  return "${ok}"
}

#######################################
# 사례 67(RC8) — 켬 · janitor 끔(갈래 r1): janitor 서비스 · cred-janitor bind 가
# 없고, 사이드카는 여전히 소비자이며(사례 61 – 63), 중계 호출자 목록은 시작
# 스크립트의 호출자 차례 그대로다(janitor 토큰은 두지만 받는 소비자가 없다 —
# 시작 스크립트는 janitor 켬 · 끔을 보지 않는다).
#######################################
cred_cases::check_67() {
  local ok=0 callers
  cred_cases::require || return 1
  callers="$(IFS=,; printf '%s' "${CRED_CALLERS[*]}")"
  expect_config r1 '.services | keys' \
    '["cred-relay", "mediamtx", "nginx", "sidecar"]' '서비스 집합' || ok=1
  # shellcheck disable=SC2016 # 기대값 jq 프로그램 — $ 는 jq 변수다.
  expect_config r1 '.services["cred-relay"].environment.CRED_RELAY_CALLERS' \
    '$callers' '중계 호출자 목록' --arg callers "${callers}" || ok=1
  expect_fixed_absent '갈래 r1 렌더' "${CRED_CONSUMER_DIR_PREFIX}janitor" \
    "$(compose_render r1)" || ok=1
  return "${ok}"
}
