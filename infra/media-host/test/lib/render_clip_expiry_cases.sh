# shellcheck shell=bash
# 전역(scratch · COMPOSE_TEMPLATE · COMPOSE_VARIANTS · JQ_SAMPLE_IMAGE ·
# RENDER_VARS_FILE)과 require_setup · vars_file · compose_render · config_json ·
# compose_config · expect_config · expect_fixed_absent 는 render_test.sh 가,
# PUBLISH_* 는 render_publish_cases.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 60일 만료 창구 사례(58 · 59). 사이드카의 만료 설정 둘
# (CLIP_INTERNAL_URL · CLIP_EXPIRY_TOKEN_FILE)과 토큰 디렉터리 bind 는
# clip_internal_url 이 찰 때만 렌더해야 한다 — 빈 값이 끔(롤백 스위치)이고,
# 그 렌더의 구성 JSON 은 이 기능 전과 같아야 한다(사례 43 의 골든 · 사례 59).
# 글자(바이트)는 같지 않다 — 머리 주석 「계약9 발행 게이트」 를 렌더에 남지
# 않는 템플릿 주석으로 옮겼다(user_data 16 KiB 여유 — kty 결정 B).
# 토큰은 시작 스크립트가 tmpfs 디렉터리에 두는 파일이고, bind 는 디렉터리 하나다.
# 이 bind 만 원본이 없으면 엔진이 만든다(create_host_path: true) — 디렉터리를
# 만들지 않는 옛 설치 묶음과 만나도 사이드카가 뜨고 만료만 꺼진다.
#
# 갈래(render_vars.json 을 jq 로 고친 사본 — 주소는 모두 채운다):
#   x1 옛 모드   x2 응답기 · janitor · 발행 목록 둘(가장 큰 렌더)
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly CLIP_EXPIRY_VARIANTS='x1 x2'
# 가짜 주소다(실제 clip 이 아니다).
readonly CLIP_SAMPLE_URL='http://clip.pcbench-edge-proxy.invalid:28081'
readonly CLIP_EXPIRY_DIR_SOURCE='/run/pokeclip-media/clip-expiry'
readonly CLIP_EXPIRY_DIR_TARGET='/run/secrets/clip-expiry'
# 렌더에 남으면 안 되는 템플릿 주석 속 글자(compose.yml.tmpl 의 만료 주석).
readonly CLIP_COMMENT_MARK='옛 설치 묶음'

# 기대값 jq 정의(render_test 의 JQ_DEFS 에 붙는다). 입력은 갈래 변수 JSON.
#   sc_clip_env  — 사이드카의 만료 설정 둘. 토큰은 값이 아니라 디렉터리 bind
#                  안의 파일 경로다.
#   sc_clip_bind — 그 디렉터리 bind 「서비스 대상 원본 자동 생성」(사례 26 꼴).
# render_test 의 JQ_DEFS 가 읽는다. jq 프로그램이라 $ 는 jq 변수다.
# shellcheck disable=SC2016,SC2034
readonly CLIP_JQ_DEFS='
def sc_clip_env:
  {CLIP_INTERNAL_URL: .clip_internal_url,
   CLIP_EXPIRY_TOKEN_FILE: "/run/secrets/clip-expiry/token"};
def sc_clip_bind: "sidecar /run/secrets/clip-expiry true";
'

clip_setup_reason=""

#######################################
# 갈래별 변수 파일을 만들고 compose 템플릿을 렌더해 구성 JSON 을 만든다. 실패는
# 이 사례들과 이 갈래를 함께 보는 사례(10 · 26)만 막는다.
# Globals:
#   RENDER_VARS_FILE, JQ_SAMPLE_IMAGE, PUBLISH_*, clip_setup_reason
# Returns:
#   0 전부 준비 / 1 하나라도 실패.
#######################################
clip_cases::prepare() {
  local base="${RENDER_VARS_FILE}" v fill out json
  for v in ${CLIP_EXPIRY_VARIANTS}; do
    fill=''
    if [[ "${v}" == x2 ]]; then
      fill="fill_auth | fill_janitor | .broadcast_topic_arn = \$topic
        | .lifecycle_publish_streams = [\$a, \$b] |"
    fi
    if ! jq --arg url "${CLIP_SAMPLE_URL}" --arg topic "${PUBLISH_TOPIC_ARN}" \
      --arg a "${PUBLISH_STREAM_A}" --arg b "${PUBLISH_STREAM_B}" \
      "${JQ_SAMPLE_IMAGE} ${fill} .clip_internal_url = \$url" "${base}" \
      >"$(vars_file "${v}")"; then
      clip_setup_reason='만료 갈래 변수 파일을 만들지 못함(jq)'
      return 1
    fi
    out="$(compose_render "${v}")"
    json="$(config_json "${v}")"
    if ! render::template "${COMPOSE_TEMPLATE}" "$(vars_file "${v}")" \
      "${out}" 2>"${out}.err"; then
      clip_setup_reason="${clip_setup_reason:+${clip_setup_reason}; }갈래 \
${v} 렌더 실패: $(harness::log_tail "${out}.err")"
    elif ! compose_config "${out}" "${json}"; then
      clip_setup_reason="${clip_setup_reason:+${clip_setup_reason}; }갈래 \
${v} docker compose config 실패: $(harness::log_tail "${json}.err")"
    fi
  done
  [[ -z "${clip_setup_reason}" ]]
}

#######################################
# 만료 갈래의 준비 확인 — 공통 준비와 이 파일의 준비가 둘 다 됐는지.
# Globals:
#   clip_setup_reason
# Returns:
#   0 준비됨 / 1 준비 실패.
#######################################
clip_cases::require() {
  require_setup || return 1
  if [[ -n "${clip_setup_reason}" ]]; then
    harness::add_reason "만료 갈래 준비 실패 — ${clip_setup_reason}"
    return 1
  fi
}

#######################################
# 사례 58 — 주소가 찬 두 모드: 만료 설정 둘은 사이드카에만 있고, 토큰 파일
# 경로는 사이드카의 읽기 전용 디렉터리 bind 안이며, 그 bind 의 원본은 시작
# 스크립트의 기본 자리다.
# Returns:
#   0 통과 / 1 어긋남 · 준비 실패.
#######################################
clip_cases::check_58() {
  local ok=0 v
  clip_cases::require || return 1
  for v in ${CLIP_EXPIRY_VARIANTS}; do
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    expect_config "${v}" '.services | map_values(.environment // {}
        | with_entries(select(.key | startswith("CLIP_"))))
      | with_entries(select(.value != {}))' '{sidecar: sc_clip_env}' \
      '만료 설정 env 자리' || ok=1
    # 실제값 필터에는 jq 인자를 넘길 수 없어 대상 글자를 직접 넣는다.
    # shellcheck disable=SC2016 # 기대값 jq 프로그램 — $ 는 jq 변수다.
    expect_config "${v}" "[.services.sidecar.volumes[]
        | select(.target == \"${CLIP_EXPIRY_DIR_TARGET}\")
        | {type, source, read_only}]" \
      '[{type: "bind", source: $source, read_only: true}]' \
      '만료 토큰 디렉터리 bind' --arg source "${CLIP_EXPIRY_DIR_SOURCE}" \
      || ok=1
    # 구성 JSON 은 기본값(true)인 create_host_path 를 빼고 낸다 — 렌더 글자로
    # 본다. 참인 줄은 이 bind 하나뿐이다(나머지는 사례 26).
    clip_cases::expect_line_count "${v}" '          create_host_path: true' 1 \
      || ok=1
  done
  return "${ok}"
}

#######################################
# 갈래 렌더에서 고정 문자열 한 줄 전체가 몇 번 나오는지 본다.
# Arguments:
#   갈래, 줄, 기대 수.
# Returns:
#   0 같음 / 1 다름 · 읽기 실패.
#######################################
clip_cases::expect_line_count() {
  local count status=0
  count="$(grep -c -x -F -- "$2" "$(compose_render "$1")")" || status=$?
  if (( status > 1 )); then
    harness::add_reason "갈래 $1 렌더를 읽지 못함"
    return 1
  fi
  if [[ "${count}" != "$3" ]]; then
    harness::add_reason "갈래 $1: 「$2」 ${count}줄(기대 $3)"
    return 1
  fi
}

#######################################
# 사례 59 — 주소가 빈 갈래(a – d)의 렌더에는 만료 글자가 없고, 어느 갈래의
# 렌더에도 만료 템플릿 주석이 남지 않는다(user_data 16 KiB 여유).
# Returns:
#   0 통과 / 1 어긋남 · 준비 실패.
#######################################
clip_cases::check_59() {
  local ok=0 v needle
  clip_cases::require || return 1
  for v in ${COMPOSE_VARIANTS}; do
    for needle in CLIP_ clip-expiry; do
      expect_fixed_absent "갈래 ${v} 렌더" "${needle}" \
        "$(compose_render "${v}")" || ok=1
    done
  done
  for v in ${COMPOSE_VARIANTS} ${CLIP_EXPIRY_VARIANTS}; do
    expect_fixed_absent "갈래 ${v} 렌더" "${CLIP_COMMENT_MARK}" \
      "$(compose_render "${v}")" || ok=1
  done
  return "${ok}"
}
