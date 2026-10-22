# shellcheck shell=bash
#
# 앞단 프록시에 시험 요청을 보내고 에코 응답(test/echo 가 돌려주는 JSON)을
# 단언하는 부품. proxy_contract.sh 와 그 사례 묶음(start_media_cases.sh)이 쓴다.
#
# proxy::configure 로 포트 넷 · 헬스 경로 · 응답을 둘 디렉터리를 먼저 정한다.
# 요청마다 Authorization 에 시험 토큰(고정 가짜 문자열 — 실제 비밀 아님)을
# 싣는다. nginx 로그에서 이 값을 세는 사례가 있어서다. 단언이 어긋나면
# harness::add_reason 으로 원인을 남긴다(harness.sh 를 먼저 source 한다). 원인
# 문구에 토큰 값은 싣지 않는다.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

readonly PROXY_FAKE_TOKEN='pcbench-edge-proxy-fake-bearer-token'
readonly PROXY_STREAM_ID='pcbench-edge-proxy-s1'

proxy_live_port=""
proxy_playback_port=""
proxy_control_port=""
proxy_health_port=""
proxy_health_path=""
proxy_dir=""
proxy_request_seq=0
proxy_resp_code=""
proxy_resp_body=""
proxy_resp_headers=""
proxy_resp_label=""

#######################################
# 포트 넷 · 헬스 경로 · 응답 디렉터리를 정한다.
# Globals:
#   proxy_live_port, proxy_playback_port, proxy_control_port,
#   proxy_health_port, proxy_health_path, proxy_dir
# Arguments:
#   live 포트, playback 포트, 제어면 포트, 헬스 포트, 헬스 경로, 디렉터리.
#######################################
proxy::configure() {
  proxy_live_port="$1"
  proxy_playback_port="$2"
  proxy_control_port="$3"
  proxy_health_port="$4"
  proxy_health_path="$5"
  proxy_dir="$6"
}

#######################################
# 프록시 포트 하나의 기준 URL 을 낸다(호스트 게시는 127.0.0.1 만).
# Globals:
#   proxy_live_port, proxy_playback_port, proxy_control_port
# Arguments:
#   live | playback | control.
# Outputs:
#   기준 URL.
#######################################
proxy::base_url() {
  case "$1" in
    live) printf 'http://127.0.0.1:%s' "${proxy_live_port}" ;;
    playback) printf 'http://127.0.0.1:%s' "${proxy_playback_port}" ;;
    control) printf 'http://127.0.0.1:%s' "${proxy_control_port}" ;;
    *) return 1 ;;
  esac
}

#######################################
# 헬스 URL 을 낸다.
# Globals:
#   proxy_health_port, proxy_health_path
# Outputs:
#   헬스 URL.
#######################################
proxy::health_url() {
  printf 'http://127.0.0.1:%s%s' "${proxy_health_port}" "${proxy_health_path}"
}

#######################################
# HTTP 요청 하나를 보낸다(Authorization = 시험 토큰). 응답 코드 · 본문 · 머리는
# 다음 요청 전까지 proxy_resp_* 에 남는다.
# Globals:
#   proxy_dir, proxy_request_seq, proxy_resp_code, proxy_resp_body,
#   proxy_resp_headers, proxy_resp_label
# Arguments:
#   메서드, URL, 본문(없어도 됨 — 있으면 JSON 으로 보낸다), 요청 대상(없어도
#   됨 — 있으면 요청 줄에 이 글자를 그대로 싣는다).
#######################################
proxy::request() {
  local method="$1" url="$2" body="${3:-}" target="${4:-}"
  local -a args
  proxy_request_seq=$((proxy_request_seq + 1))
  proxy_resp_body="${proxy_dir}/resp.${proxy_request_seq}.body"
  proxy_resp_headers="${proxy_dir}/resp.${proxy_request_seq}.headers"
  proxy_resp_label="${method} :${url#http://127.0.0.1:}${target}"
  if [[ "${target}" == http* ]]; then
    proxy_resp_label="${method} ${target}"
  fi
  args=(-sS -o "${proxy_resp_body}" -D "${proxy_resp_headers}"
    -w '%{http_code}' --max-time 5 -X "${method}"
    -H "Authorization: Bearer ${PROXY_FAKE_TOKEN}")
  if [[ -n "${body}" ]]; then
    args+=(-H 'Content-Type: application/json' --data-binary "${body}")
  fi
  if [[ -n "${target}" ]]; then
    args+=(--request-target "${target}")
  fi
  proxy_resp_code="$(curl "${args[@]}" "${url}" 2>/dev/null)" || true
  proxy_resp_code="${proxy_resp_code:-000}"
}

#######################################
# 요청 대상을 손대지 않고 GET 한다 — curl 이 점 구간을 접거나 % 를 바꾸지
# 않는다. 절대 URI(http://…)도 그대로 싣는다. 정규화 우회 사례가 쓴다.
# Arguments:
#   기준 URL(경로 없이), 요청 대상.
#######################################
proxy::get_raw() {
  proxy::request GET "$1" '' "$2"
}

#######################################
# 마지막 응답 코드를 비교한다.
# Globals:
#   proxy_resp_code, proxy_resp_label
# Arguments:
#   기대 코드.
#######################################
proxy::expect_code() {
  if [[ "${proxy_resp_code}" != "$1" ]]; then
    harness::add_reason \
      "${proxy_resp_label}: HTTP $1 기대 · 실제 ${proxy_resp_code}"
    return 1
  fi
}

#######################################
# 마지막 에코 응답 JSON 의 한 필드를 비교한다.
# Globals:
#   proxy_resp_body, proxy_resp_label
# Arguments:
#   jq 필터, 기대값, 이름.
#######################################
proxy::expect_field() {
  local got
  got="$(jq -r "$1" "${proxy_resp_body}" 2>/dev/null)" \
    || got='(에코 JSON 아님)'
  if [[ "${got}" != "$2" ]]; then
    harness::add_reason "${proxy_resp_label}: $3 기대 '$2' · 실제 '${got}'"
    return 1
  fi
}

#######################################
# 상류가 받은 Authorization 이 보낸 값과 같은지 본다(값은 문구에 싣지 않는다).
# Globals:
#   proxy_resp_body, proxy_resp_label
#######################################
proxy::expect_auth() {
  local got
  got="$(jq -r '.headers.Authorization // [] | join(",")' \
    "${proxy_resp_body}" 2>/dev/null)" || got=""
  if [[ "${got}" != "Bearer ${PROXY_FAKE_TOKEN}" ]]; then
    harness::add_reason \
      "${proxy_resp_label}: 상류가 받은 Authorization 이 보낸 값과 다름"
    return 1
  fi
}

#######################################
# 마지막 응답에 Cache-Control 머리가 없는지 본다. 머리 파일이 없거나 읽지
# 못하면(grep 종료 2 이상) 「없음」 이 아니라 확인 불가로 실패한다.
# Globals:
#   proxy_resp_headers, proxy_resp_label
#######################################
proxy::expect_no_cache_control() {
  local n status=0
  if [[ ! -r "${proxy_resp_headers}" ]]; then
    harness::add_reason "${proxy_resp_label}: 응답 머리 파일 없음 — 확인 불가"
    return 1
  fi
  n="$(grep -i -c '^cache-control:' "${proxy_resp_headers}" 2>/dev/null)" \
    || status=$?
  if (( status > 1 )); then
    harness::add_reason "${proxy_resp_label}: 응답 머리를 읽지 못함 — 확인 불가"
    return 1
  fi
  if [[ "${n}" != 0 ]]; then
    harness::add_reason "${proxy_resp_label}: 응답에 Cache-Control 이 붙음"
    return 1
  fi
}

#######################################
# 마지막 응답의 Location 머리가 기대값과 같은지 본다.
# Globals:
#   proxy_resp_headers, proxy_resp_label
# Arguments:
#   기대 Location.
#######################################
proxy::expect_location() {
  local got
  got="$(awk 'tolower($1) == "location:" { sub(/\r$/, ""); print $2 }' \
    "${proxy_resp_headers}" 2>/dev/null)" || got=""
  if [[ "${got}" != "$1" ]]; then
    harness::add_reason \
      "${proxy_resp_label}: Location 기대 '$1' · 실제 '${got:-없음}'"
    return 1
  fi
}

#######################################
# 사례 1 의 본체: live 포트 GET 이 경로 · 쿼리 · 메서드 · Authorization 을
# 그대로 127.0.0.1:8888 에 넘기는지 본다(사례 13 이 재기동 전후로 다시 쓴다).
#######################################
proxy::check_live_passthrough() {
  local ok=0 path="/${PROXY_STREAM_ID}/index.m3u8"
  local query='_HLS_msn=12&_HLS_part=3'
  proxy::request GET "$(proxy::base_url live)${path}?${query}"
  proxy::expect_code 200 || return 1
  proxy::expect_field .listener '127.0.0.1:8888' '상류' || ok=1
  proxy::expect_field .path "${path}" '경로' || ok=1
  proxy::expect_field .raw_query "${query}" '쿼리' || ok=1
  proxy::expect_field .method GET '메서드' || ok=1
  proxy::expect_auth || ok=1
  return "${ok}"
}

#######################################
# 사례 9 의 본체: 헬스 포트가 200 인지 본다.
#######################################
proxy::check_health() {
  proxy::request GET "$(proxy::health_url)"
  proxy::expect_code 200
}
