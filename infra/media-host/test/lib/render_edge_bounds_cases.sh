# shellcheck shell=bash
# 전역(scratch · COMPOSE_TEMPLATE · JQ_SAMPLE_IMAGE · RENDER_VARS_FILE)과
# require_setup · vars_file · compose_render · config_json · compose_config 는
# render_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 /dvr 하한 Writer env 사례(56). 사이드카
# REWIND_EDGE_BOUNDS_KVS_ARN 은 두 모드 공통으로 늘 따옴표 친 한 줄이다. 빈
# 값(Writer 꺼짐 — M9 기본)은 갈래 a – d 가 사례 10 · 43 에서 본다. 여기서는 ARN
# 을 채운 두 갈래(옛 모드 k1 · 응답기 모드 k2)에서 값이 사이드카에만 가는지
# 본다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly EDGE_BOUNDS_VARIANTS='k1 k2'
# 가짜 계정 · id 다(실제 KVS 가 아니다).
readonly EDGE_BOUNDS_KVS_ARN='arn:aws:cloudfront::111122223333:'\
'key-value-store/00000000-0000-0000-0000-0000000000e1'
readonly EDGE_BOUNDS_KEY='REWIND_EDGE_BOUNDS_KVS_ARN'

edge_bounds_setup_reason=""

#######################################
# 갈래별 변수 파일을 만들고 compose 템플릿을 렌더한다. 실패는 이 사례만 막는다.
# Globals:
#   RENDER_VARS_FILE, JQ_SAMPLE_IMAGE, edge_bounds_setup_reason
# Returns:
#   0 전부 렌더 / 1 하나라도 실패.
#######################################
edge_bounds_cases::prepare() {
  local base="${RENDER_VARS_FILE}" v auth out
  for v in ${EDGE_BOUNDS_VARIANTS}; do
    auth=''
    [[ "${v}" == k2 ]] && auth='fill_auth |'
    if ! jq --arg arn "${EDGE_BOUNDS_KVS_ARN}" \
      "${JQ_SAMPLE_IMAGE} ${auth} .edge_bounds_kvs_arn = \$arn" "${base}" \
      >"$(vars_file "${v}")"; then
      edge_bounds_setup_reason='하한 갈래 변수 파일을 만들지 못함(jq)'
      return 1
    fi
    out="$(compose_render "${v}")"
    if ! render::template "${COMPOSE_TEMPLATE}" "$(vars_file "${v}")" \
      "${out}" 2>"${out}.err"; then
      edge_bounds_setup_reason="${edge_bounds_setup_reason:+\
${edge_bounds_setup_reason}; }갈래 ${v} 렌더 실패: \
$(harness::log_tail "${out}.err")"
    fi
  done
  [[ -z "${edge_bounds_setup_reason}" ]]
}

#######################################
# 사례 56 — ARN 을 채운 두 모드: 사이드카 env 만 그 ARN 이고, 렌더에 따옴표 친
# 한 줄로 정확히 한 번 있다.
# Globals:
#   edge_bounds_setup_reason
# Returns:
#   0 통과 / 1 어긋남 · 준비 실패.
#######################################
edge_bounds_cases::check_56() {
  local ok=0 v json got want count status
  require_setup || return 1
  if [[ -n "${edge_bounds_setup_reason}" ]]; then
    harness::add_reason "하한 준비 실패 — ${edge_bounds_setup_reason}"
    return 1
  fi
  want="$(jq -nc --arg arn "${EDGE_BOUNDS_KVS_ARN}" '{sidecar: $arn}')"
  for v in ${EDGE_BOUNDS_VARIANTS}; do
    status=0
    count="$(grep -c -x -F \
      "      ${EDGE_BOUNDS_KEY}: \"${EDGE_BOUNDS_KVS_ARN}\"" \
      "$(compose_render "${v}")")" || status=$?
    if (( status > 1 )); then
      harness::add_reason "갈래 ${v} 렌더를 읽지 못함"
      ok=1
    elif [[ "${count}" != 1 ]]; then
      harness::add_reason "갈래 ${v}: 따옴표 친 하한 env 줄 ${count}줄(기대 1)"
      ok=1
    fi
    json="$(config_json "${v}")"
    if ! compose_config "$(compose_render "${v}")" "${json}"; then
      harness::add_reason "갈래 ${v}: docker compose config 실패: \
$(harness::log_tail "${json}.err")"
      ok=1
      continue
    fi
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    got="$(jq -c --arg key "${EDGE_BOUNDS_KEY}" '.services
      | map_values(.environment // {} | .[$key])
      | with_entries(select(.value != null))' "${json}" 2>/dev/null)" \
      || got='(구성 JSON 을 읽지 못함)'
    if [[ "${got}" != "${want}" ]]; then
      harness::add_reason "갈래 ${v}: 하한 env 기대 ${want} · 실제 ${got}"
      ok=1
    fi
  done
  return "${ok}"
}
