# shellcheck shell=bash
# 전역(scratch · COMPOSE_TEMPLATE · JQ_SAMPLE_IMAGE · RENDER_VARS_FILE)과
# require_setup · vars_file · compose_render · config_json · compose_config ·
# expect_config · expect_fixed_absent 는 render_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 사이드카 계약9 발행 env 사례(48 – 53). 발행 env 둘
# (BROADCAST_TOPIC_ARN · LIFECYCLE_PUBLISH_STREAMS)은 응답기 모드이고 허용
# 목록이 찰 때만 렌더해야 한다 — 옛 모드는 4C resolve 설정이 없어 started 를
# 낼 수 없다. 두 줄이 없으면 주제가 비어 사이드카가 발행을 끈다
# (lifecycle_disabled reason=no_topic · media/README 「계약9 발행」). 목록
# 값은 늘 따옴표로 렌더해야 한다 — 맨 `*` 은 YAML 별칭이라 compose 해석이
# 실패한다.
#
# 갈래(render_vars.json 을 jq 로 고친 사본 — 주제 ARN 은 모두 채운다. env 가
# 빠지는 이유가 모드 · 목록이어야 한다):
#   p1 응답기 · 목록 하나   p2 응답기 · 목록 `*`   p3 응답기 · 빈 목록
#   p4 옛 모드 · 목록 하나   p6 응답기 · 목록 둘
# p5 는 없다 — 계획의 다섯째 사례(비밀 0 · 사례 52)는 갈래를 따로 두지 않고
# 위 갈래 전부를 본다. 번호는 계획의 사례 번호(첫째 – 여섯째)를 따른다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly PUBLISH_VARIANTS='p1 p2 p3 p4 p6'
# 가짜 계정 · 이름이다(실제 주제가 아니다).
readonly PUBLISH_TOPIC_ARN='arn:aws:sns:ap-northeast-2:111122223333:'\
'pcbench-edge-proxy-broadcast.fifo'
# 26자 Crockford base32 꼴의 가짜 ingestStreamId 둘.
readonly PUBLISH_STREAM_A='01ARZ3NDEKTSV4RRFFQ69G5FAV'
readonly PUBLISH_STREAM_B='01BX5ZZKBKACTAV9WEVGEMMVRZ'

# 기대값 jq 정의. publish_env — 서비스마다 발행 env 둘만 골라 낸다(없는
# 서비스는 빠진다).
# shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
readonly PUBLISH_JQ_PICK='
.services | map_values(.environment // {}
  | with_entries(select(.key == "BROADCAST_TOPIC_ARN"
      or .key == "LIFECYCLE_PUBLISH_STREAMS")))
| with_entries(select(.value != {}))'

publish_setup_reason=""

#######################################
# 갈래별 변수 파일을 만든다(render_vars.json 을 jq 로 고친 사본).
# Globals:
#   RENDER_VARS_FILE, JQ_SAMPLE_IMAGE, PUBLISH_*
# Returns:
#   0 만듦 / 그 밖 실패.
#######################################
publish_cases::write_vars() {
  local base="${RENDER_VARS_FILE}" v auth streams
  for v in ${PUBLISH_VARIANTS}; do
    auth='fill_auth |'
    case "${v}" in
      p1) streams="[\"${PUBLISH_STREAM_A}\"]" ;;
      p2) streams='["*"]' ;;
      p3) streams='[]' ;;
      p4) streams="[\"${PUBLISH_STREAM_A}\"]"; auth='' ;;
      p6) streams="[\"${PUBLISH_STREAM_A}\", \"${PUBLISH_STREAM_B}\"]" ;;
    esac
    jq --arg topic "${PUBLISH_TOPIC_ARN}" --argjson streams "${streams}" \
      "${JQ_SAMPLE_IMAGE} ${auth} .broadcast_topic_arn = \$topic
        | .lifecycle_publish_streams = \$streams" "${base}" \
      >"$(vars_file "${v}")" || return 1
  done
}

#######################################
# 발행 갈래마다 compose 템플릿을 렌더한다. 실패는 발행 사례만 막는다.
# Globals:
#   publish_setup_reason
# Returns:
#   0 전부 렌더 / 1 하나라도 실패.
#######################################
publish_cases::prepare() {
  local v out
  if ! publish_cases::write_vars; then
    publish_setup_reason='발행 갈래 변수 파일을 만들지 못함(jq)'
    return 1
  fi
  for v in ${PUBLISH_VARIANTS}; do
    out="$(compose_render "${v}")"
    if ! render::template "${COMPOSE_TEMPLATE}" "$(vars_file "${v}")" \
      "${out}" 2>"${out}.err"; then
      publish_setup_reason="${publish_setup_reason:+${publish_setup_reason}; }\
갈래 ${v} 렌더 실패: $(harness::log_tail "${out}.err")"
    fi
  done
  [[ -z "${publish_setup_reason}" ]]
}

#######################################
# 발행 사례의 준비 확인 — 공통 준비와 발행 갈래 렌더가 둘 다 됐는지 본다.
# Globals:
#   publish_setup_reason
# Returns:
#   0 준비됨 / 1 준비 실패.
#######################################
publish_cases::require() {
  require_setup || return 1
  if [[ -n "${publish_setup_reason}" ]]; then
    harness::add_reason "발행 준비 실패 — ${publish_setup_reason}"
    return 1
  fi
}

#######################################
# 갈래 하나의 구성 JSON 을 만든다(이미 있으면 그대로 쓴다). config -q 가
# 실패하면 원인을 남긴다.
# Arguments:
#   갈래.
# Returns:
#   0 있음 / 1 config 실패.
#######################################
publish_cases::config() {
  local json
  json="$(config_json "$1")"
  [[ -s "${json}" ]] && return 0
  if ! compose_config "$(compose_render "$1")" "${json}"; then
    harness::add_reason "갈래 $1: docker compose config 실패: \
$(harness::log_tail "${json}.err")"
    rm -f -- "${json}"
    return 1
  fi
}

#######################################
# 갈래 하나에서 서비스별 발행 env 가 기대값과 같은지 본다.
# Arguments:
#   갈래, 기대값 jq 식(입력 없음 — --arg topic · a · b 를 쓸 수 있다), 이름.
#######################################
publish_cases::expect_env() {
  local v="$1" want got
  publish_cases::config "${v}" || return 1
  want="$(jq -ncS --arg topic "${PUBLISH_TOPIC_ARN}" \
    --arg a "${PUBLISH_STREAM_A}" --arg b "${PUBLISH_STREAM_B}" "$2")" \
    || { harness::add_reason '시험 오류(기대값 jq)'; return 1; }
  got="$(jq -cS "${PUBLISH_JQ_PICK}" "$(config_json "${v}")" 2>/dev/null)" \
    || got='(구성 JSON 을 읽지 못함)'
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "갈래 ${v}: $3 기대 ${want} · 실제 ${got}"
    return 1
  fi
}

# 사례 48 — 응답기 · 목록 하나: 사이드카에만 발행 env 두 줄.
publish_cases::check_48() {
  publish_cases::require || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  publish_cases::expect_env p1 \
    '{sidecar: {BROADCAST_TOPIC_ARN: $topic, LIFECYCLE_PUBLISH_STREAMS: $a}}' \
    '발행 env(사이드카만 · 둘)'
}

# 사례 49 — 응답기 · 목록 `*`: 값이 따옴표로 렌더되고 compose 가 해석한다.
publish_cases::check_49() {
  local ok=0 count status=0
  publish_cases::require || return 1
  count="$(grep -c -E '^[[:space:]]+LIFECYCLE_PUBLISH_STREAMS: "\*"$' \
    "$(compose_render p2)")" || status=$?
  if (( status > 1 )); then
    harness::add_reason '갈래 p2 렌더를 읽지 못함'
    ok=1
  elif [[ "${count}" != 1 ]]; then
    harness::add_reason "갈래 p2: 따옴표 친 목록 줄 ${count}줄(기대 1)"
    ok=1
  fi
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  publish_cases::expect_env p2 \
    '{sidecar: {BROADCAST_TOPIC_ARN: $topic, LIFECYCLE_PUBLISH_STREAMS: "*"}}' \
    '발행 env(목록 *)' || ok=1
  return "${ok}"
}

# 사례 50 — 응답기 · 빈 목록: 발행 env 0.
publish_cases::check_50() {
  publish_cases::require || return 1
  publish_cases::expect_env p3 '{}' '빈 목록의 발행 env'
}

# 사례 51 — 옛 모드 · 목록 하나: 발행 env 0(모듈 거부와 별개인 렌더 방어).
publish_cases::check_51() {
  publish_cases::require || return 1
  publish_cases::expect_env p4 '{}' '옛 모드의 발행 env'
}

# 사례 52 — 발행 갈래 렌더에 비밀 ARN · 비밀 계열 env 이름 0.
publish_cases::check_52() {
  local ok=0 v needle out
  publish_cases::require || return 1
  for v in ${PUBLISH_VARIANTS}; do
    out="$(compose_render "${v}")"
    for needle in secretsmanager ':secret:'; do
      expect_fixed_absent "갈래 ${v} 렌더" "${needle}" "${out}" || ok=1
    done
    if ! publish_cases::config "${v}"; then
      ok=1
      continue
    fi
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    expect_config "${v}" '[.services | to_entries[] | .key as $s | .value as $c
      | ([($c.volumes // [])[]
          | select(.type == "bind" and .read_only == true) | .target]) as $ro
      | ($c.environment // {}) | to_entries[]
      | select(.key | test("CDN|TOKEN|PASS|SECRET"; "i"))
      | select(((.key | endswith("_FILE"))
          and (.value as $v | $ro | index($v) != null)) | not)
      | "\($s).\(.key)"]' '[]' '비밀 계열 env 이름' || ok=1
  done
  return "${ok}"
}

# 사례 53 — 목록 둘: 쉼표 하나로 이은 한 값.
publish_cases::check_53() {
  publish_cases::require || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  publish_cases::expect_env p6 \
    '{sidecar: {BROADCAST_TOPIC_ARN: $topic,
       LIFECYCLE_PUBLISH_STREAMS: "\($a),\($b)"}}' '발행 env(목록 둘)'
}
