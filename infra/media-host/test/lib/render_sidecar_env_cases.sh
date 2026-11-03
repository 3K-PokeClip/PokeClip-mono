# shellcheck shell=bash
# 전역(scratch · PROJECT · CONFIG_TIMEOUT · MEDIA_HOST_DIR · COMPOSE_VARIANTS 등)과
# require_setup · vars_file · compose_render · expect_config 는 render_test.sh 가,
# auth_cases::noenv_json · auth_cases::noenv_config 는 render_auth_cases.sh 가
# 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 상자 스위치 파일 사례(48 · 49). sidecar.env.tmpl 은 정확히 한 줄
# PLAYBACK_ROUND_KEYS=true|false 로 렌더되고, compose 사이드카는 그 파일(compose
# 파일 옆)을 required env_file 로 받는다. compose environment 에는 같은 키를
# 두지 않는다 — 둘이면 environment 가 이겨 상자 파일을 바꿔도 반영되지 않는다.
#
# env_file 은 compose 가 구성 JSON 에서 environment 로 풀어 버리므로(5.5.0 실측 —
# render_auth_cases.sh 머리) 키 자리는 풀지 않은 구성으로 본다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly SIDECAR_ENV_TEMPLATE="${MEDIA_HOST_DIR}/sidecar.env.tmpl"
readonly SIDECAR_ENV_NAME='sidecar.env'
readonly ROUND_KEYS_KEY='PLAYBACK_ROUND_KEYS'

sidecar_setup_reason=""

# scratch 의 스위치 파일 렌더본 경로를 낸다 — compose 렌더본 옆이다.
sidecar_cases::env_render() {
  printf '%s/%s' "${scratch}" "${SIDECAR_ENV_NAME}"
}

#######################################
# 스위치 파일 템플릿을 compose 렌더본 옆(scratch)에 렌더한다 — 사이드카는 모든
# 갈래에 있어 이 파일이 없으면 모든 compose 의 config 가 실패한다(사례 1).
# Globals:
#   sidecar_setup_reason
#######################################
sidecar_cases::prepare() {
  if [[ ! -f "${SIDECAR_ENV_TEMPLATE}" ]]; then
    sidecar_setup_reason="${SIDECAR_ENV_TEMPLATE#"${MEDIA_HOST_DIR}/"} 없음"
    return 1
  fi
  if ! render::template "${SIDECAR_ENV_TEMPLATE}" "$(vars_file a)" \
    "$(sidecar_cases::env_render)" 2>"${scratch}/sidecar-env-render.err"; then
    sidecar_setup_reason="스위치 파일 템플릿 렌더 실패: \
$(harness::log_tail "${scratch}/sidecar-env-render.err")"
    return 1
  fi
}

#######################################
# 스위치 파일 사례의 준비 확인 — 공통 준비와 스위치 파일 렌더가 둘 다 됐는지.
# Returns:
#   0 준비됨 / 1 준비 실패.
#######################################
sidecar_cases::require() {
  require_setup || return 1
  if [[ -n "${sidecar_setup_reason}" ]]; then
    harness::add_reason "스위치 파일 준비 실패 — ${sidecar_setup_reason}"
    return 1
  fi
}

# 사례 48 — 스위치 파일 템플릿 렌더가 true · false 각각 정확히
# `PLAYBACK_ROUND_KEYS=<값>` 한 줄이다(바이트 대조). 변수는 bool 이다.
sidecar_cases::check_48() {
  local ok=0 value vars out
  sidecar_cases::require || return 1
  for value in true false; do
    vars="${scratch}/vars-round-keys-${value}.json"
    out="${scratch}/sidecar-env-${value}"
    jq --argjson v "${value}" '.playback_round_keys = $v' "$(vars_file a)" \
      >"${vars}" || { harness::add_reason '시험 오류: 변수 파일'; return 1; }
    if ! render::template "${SIDECAR_ENV_TEMPLATE}" "${vars}" "${out}" \
      2>"${out}.err"; then
      harness::add_reason "스위치 파일 렌더 실패(${value}): \
$(harness::log_tail "${out}.err")"
      ok=1
    elif ! printf '%s=%s\n' "${ROUND_KEYS_KEY}" "${value}" \
      | cmp -s - "${out}"; then
      harness::add_reason "스위치 파일 렌더(${value})가 정확히 한 줄이 아님: \
$(od -c "${out}" | head -3 | tr '\n' ' ')"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 사례 49 — 모든 갈래의 사이드카 env_file 은 compose 파일 옆 sidecar.env 하나이고
# required 다. 풀지 않은 environment 에는 PLAYBACK_ROUND_KEYS 가 없고, 풀린
# env 에는 그 파일의 값이 들어간다. 그 파일이 없는 자리에서는 config 가 그
# 파일 이름을 대며 실패한다(옛 갈래 a — 응답기 env_file 이 없는 렌더).
# Returns:
#   0 통과 / 1 어긋남.
#######################################
sidecar_cases::check_49() {
  local ok=0 v json got dir="${scratch}/no-sidecar-env" err
  sidecar_cases::require || return 1
  for v in ${COMPOSE_VARIANTS}; do
    json="$(auth_cases::noenv_json "${v}")"
    if [[ ! -s "${json}" ]] \
      && ! auth_cases::noenv_config "$(compose_render "${v}")" "${json}"; then
      harness::add_reason "갈래 ${v}: env 를 풀지 않은 config 실패: \
$(harness::log_tail "${json}.err")"
      ok=1
      continue
    fi
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    got="$(jq -c --arg dir "${scratch##*/}" --arg name "${SIDECAR_ENV_NAME}" \
      --arg key "${ROUND_KEYS_KEY}" '.services.sidecar
      | {env_file: [.env_file // [] | .[]
          | {beside: (.path | endswith("/\($dir)/\($name)")),
             required: (if .required == false then false else true end)}],
         key_in_environment: ((.environment // {}) | has($key))}' \
      "${json}" 2>/dev/null)" || got='(구성 JSON 을 읽지 못함)'
    if [[ "${got}" != \
      '{"env_file":[{"beside":true,"required":true}],"key_in_environment":false}' ]]
    then
      harness::add_reason "갈래 ${v}: 사이드카 env_file · environment ${got}"
      ok=1
    fi
    expect_config "${v}" \
      ".services.sidecar.environment.${ROUND_KEYS_KEY} // null" \
      '.playback_round_keys | tostring' 'env_file 값이 풀린 사이드카 env' \
      || ok=1
  done
  mkdir -p -- "${dir}"
  cp -- "$(compose_render a)" "${dir}/compose.yml"
  err="${dir}/config.err"
  if harness::run_bounded "${CONFIG_TIMEOUT}" "${err}" docker compose \
    -p "${PROJECT}" -f "${dir}/compose.yml" config -q; then
    harness::add_reason '스위치 파일 없는 자리에서 config 가 성공함(required 아님)'
    ok=1
  elif ! grep -q -F "${SIDECAR_ENV_NAME}" "${err}"; then
    harness::add_reason "스위치 파일 없음 config 실패 원인이 그 파일이 아님: \
$(harness::log_tail "${err}")"
    ok=1
  fi
  return "${ok}"
}
