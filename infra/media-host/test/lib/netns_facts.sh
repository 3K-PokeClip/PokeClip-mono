# shellcheck shell=bash
#
# 앞단 프록시 계약 시험의 망 칸 구조 읽기 — 사례 11 · 12 가 실행 결과에 더해,
# 그 결과가 기대는 구조(nginx 의 network_mode · 포트를 게시하는 서비스 · 게시된
# 8888 · 9996 · 9997)가 prod 템플릿(compose.yml.tmpl)과 시험 compose 에서
# 같은지 보는 몫.
#
# proxy_contract.sh 가 source 해서 돌린다. harness.sh · render.sh 를 먼저
# source 한다. proxy_contract.sh 의 전역 scratch · env_ready · setup_reason 과
# 상수 PROJECT · CONTRACT_COMPOSE · MEDIA_HOST_DIR · COMPOSE_TEMPLATE_REL 을
# 쓴다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 한 번만 읽어 둔 결과(load_netns_facts 가 채운다).
netns_loaded=0
netns_status=1
netns_reason=""
prod_netns=""
test_netns=""

#######################################
# compose 파일 하나의 망 칸 구조 요약(JSON 한 줄)을 낸다 — nginx 의
# network_mode · 포트를 게시하는 서비스 · 게시된 8888 · 9996 · 9997.
# Arguments:
#   compose 파일.
#######################################
netns_facts() {
  # scratch 는 proxy_contract.sh 가 정한다.
  # shellcheck disable=SC2154
  harness::bounded "${HARNESS_DOCKER_TIMEOUT}" \
    docker compose -p "${PROJECT}" -f "$1" config --format json \
    2>>"${scratch}/config.err" \
    | jq -c '{
        nginx: (.services.nginx.network_mode // null),
        publishers: ([.services | to_entries[]
          | select((.value.ports // []) | length > 0) | .key] | sort),
        loopback_published: [.services[] | (.ports // [])[] | .target
          | select(. == 8888 or . == 9996 or . == 9997)]
      }'
}

#######################################
# prod 템플릿과 시험 compose 의 망 칸 구조를 한 번만 읽어 둔다(사례 11 · 12).
# Globals:
#   netns_loaded, netns_status, netns_reason, prod_netns, test_netns
#######################################
load_netns_facts() {
  local rendered="${scratch}/compose.rendered.yml"
  if (( netns_loaded )); then
    return "${netns_status}"
  fi
  netns_loaded=1
  if [[ ! -f "${MEDIA_HOST_DIR}/${COMPOSE_TEMPLATE_REL}" ]]; then
    netns_reason="${COMPOSE_TEMPLATE_REL} 없음(prod 망 칸 구조 — 사례 11 · 12 의 대조 원본)"
    return 1
  fi
  if ! render::template "${MEDIA_HOST_DIR}/${COMPOSE_TEMPLATE_REL}" \
    "${RENDER_VARS_FILE}" "${rendered}" 2>"${rendered}.err"; then
    netns_reason="${COMPOSE_TEMPLATE_REL} 렌더 실패: \
$(harness::log_tail "${rendered}.err")"
    return 1
  fi
  if ! prod_netns="$(netns_facts "${rendered}")"; then
    netns_reason="렌더한 prod compose 를 읽지 못함: \
$(harness::log_tail "${scratch}/config.err")"
    return 1
  fi
  # env_ready 는 proxy_contract.sh 가 정한다.
  # shellcheck disable=SC2154
  if (( env_ready )); then
    test_netns="$(netns_facts "${CONTRACT_COMPOSE}")" || test_netns=""
  fi
  if [[ -z "${test_netns}" ]]; then
    netns_reason="시험 compose 를 읽지 못함 — ${setup_reason:-config 실패}"
    return 1
  fi
  netns_status=0
}

require_netns() {
  if load_netns_facts; then
    return 0
  fi
  harness::add_reason "구조: ${netns_reason}"
  return 1
}

#######################################
# 망 칸 구조 한 항목이 prod 템플릿 · 시험 compose 둘 다 기대값인지 본다
# (require_netns 뒤에 부른다).
# Arguments:
#   jq 필터, 기대값(jq -c 출력), 이름.
#######################################
expect_netns() {
  local ok=0 got kind facts
  for kind in prod test; do
    if [[ "${kind}" == prod ]]; then
      facts="${prod_netns}"
    else
      facts="${test_netns}"
    fi
    got="$(printf '%s' "${facts}" | jq -c "$1")" || got='(읽지 못함)'
    if [[ "${got}" != "$2" ]]; then
      harness::add_reason "구조(${kind}): $3 기대 $2 · 실제 ${got}"
      ok=1
    fi
  done
  return "${ok}"
}

