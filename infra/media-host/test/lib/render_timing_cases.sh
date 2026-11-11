# shellcheck shell=bash
# 전역(scratch · PROJECT · CONFIG_TIMEOUT · COMPOSE_VARIANTS 등)과 require_setup ·
# vars_file · compose_render · config_json 은 render_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 기동 전 검사 서비스 사례(57 — POK-196 PDT 설계 r7 9.4).
# timing-preflight 는 compose profiles(preflight)로만 있다 — 기본 up · config 에는
# 없고(그래서 사례 1 – 56 의 서비스 집합이 그대로다), 프로필을 켠 구성에서만 보인다.
# 이미지는 검사 핀 timing_guard_image, entrypoint 는 검사 명령, 망은 compose 기본
# 망(mediamtx 앞에서 돈다), 볼륨 · 의존 · 비밀 env 는 없고 DB env 는 사이드카와
# 같다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly TIMING_SERVICE='timing-preflight'
readonly TIMING_PROFILE='preflight'
readonly TIMING_ENTRYPOINT='["/usr/local/bin/segment-indexer",'\
'"timing-gate-check"]'

# 갈래 하나의 프로필 켠 구성 JSON 경로를 낸다.
timing_cases::config_json() {
  printf '%s/compose-%s-preflight.json' "${scratch}" "$1"
}

#######################################
# 갈래 하나를 프로필 preflight 를 켜고 `docker compose config` 로 풀어 JSON 으로
# 쓴다(선언 그대로 — 정규화 없음).
# Arguments:
#   갈래.
# Returns:
#   0 / 1 실패(원인은 출력 JSON 경로 .err).
#######################################
timing_cases::profile_config() {
  local out
  out="$(timing_cases::config_json "$1")"
  harness::bounded "${CONFIG_TIMEOUT}" docker compose -p "${PROJECT}" \
    -f "$(compose_render "$1")" --profile "${TIMING_PROFILE}" \
    config --no-normalize --format json >"${out}.raw" 2>"${out}.err" \
    || return 1
  jq -e . "${out}.raw" >"${out}" 2>>"${out}.err"
}

#######################################
# 사례 57 — 기동 전 검사 서비스는 프로필로만 있다. 모든 갈래에서 기본 구성에는
# 없고, 프로필 구성에는 하나 있으며, 그 꼴이 위 머리 주석과 같다. 기본 구성의
# 나머지 서비스는 프로필 구성에서도 그대로다(프로필이 다른 서비스를 바꾸지
# 않는다).
#######################################
timing_cases::check_57() {
  local ok=0 v want got base prof sidecar_env
  require_setup || return 1
  for v in ${COMPOSE_VARIANTS}; do
    base="$(config_json "${v}")"
    prof="$(timing_cases::config_json "${v}")"
    if ! timing_cases::profile_config "${v}"; then
      harness::add_reason "갈래 ${v}: 프로필 구성 실패: \
$(harness::log_tail "${prof}.err")"
      ok=1
      continue
    fi
    if [[ "$(jq --arg s "${TIMING_SERVICE}" '.services | has($s)' \
      "${base}")" != false ]]; then
      harness::add_reason "갈래 ${v}: 기본 구성에 ${TIMING_SERVICE} 가 있음"
      ok=1
    fi
    if [[ "$(jq -cS --arg s "${TIMING_SERVICE}" '.services | del(.[$s])' \
      "${prof}")" != "$(jq -cS '.services' "${base}")" ]]; then
      harness::add_reason "갈래 ${v}: 프로필이 다른 서비스를 바꿈"
      ok=1
    fi
    # ulimits core: 0 은 config JSON 에서 {} 다(사례 8 과 같은 읽기).
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    want="$(jq -cS --argjson entry "${TIMING_ENTRYPOINT}" \
      --arg p "${TIMING_PROFILE}" '{image: .timing_guard_image,
        entrypoint: $entry, profiles: [$p], restart: "no", read_only: true,
        cap_drop: ["ALL"], security_opt: ["no-new-privileges:true"],
        core: {}, network_mode: null, volumes: null, depends_on: null,
        env_file: null, user: null,
        tag: (if .detached_log_driver == "json-file" then null
          else "pokeclip-media-timing-preflight" end)}' "$(vars_file "${v}")")"
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    got="$(jq -cS --arg s "${TIMING_SERVICE}" '.services[$s] | {image,
        entrypoint, profiles, restart, read_only, cap_drop, security_opt,
        core: (.ulimits.core // null), network_mode, volumes, depends_on,
        env_file, user, tag: .logging.options.tag}' "${prof}" 2>/dev/null)" \
      || got='(구성 JSON 을 읽지 못함)'
    if [[ "${got}" != "${want}" ]]; then
      harness::add_reason "갈래 ${v}: ${TIMING_SERVICE} 기대 ${want} · \
실제 ${got}"
      ok=1
    fi
    # DB env 는 사이드카와 같은 값이다(사이드카 env 가운데 POSTGRES_* · TZ ·
    # AWS_* 만 — 나머지 사이드카 env 는 검사가 쓰지 않는다).
    # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
    sidecar_env="$(jq -cS '.services.sidecar.environment | with_entries(
        select(.key | test("^(POSTGRES_|AWS_|TZ$)")))' "${prof}")"
    if [[ "$(jq -cS --arg s "${TIMING_SERVICE}" '.services[$s].environment' \
      "${prof}")" != "${sidecar_env}" ]]; then
      harness::add_reason "갈래 ${v}: ${TIMING_SERVICE} env 가 사이드카 DB env 와 다름"
      ok=1
    fi
  done
  return "${ok}"
}
