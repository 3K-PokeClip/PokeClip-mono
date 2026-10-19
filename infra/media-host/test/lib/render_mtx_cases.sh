# shellcheck shell=bash
# 전역(scratch · setup_reason · *_TOKEN 등)과 require_setup · mtx_render ·
# mtx_json · config_json 은 render_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 MediaMTX 운영 템플릿 사례(18 – 24) — mediamtx.yml.tmpl 을
# templatefile() 로 렌더하고 yamldecode() 로 읽은 값에 단언한다: 루프백 바인딩 ·
# 믿는 프록시 없음 · 인증 항목 셋(무인증 api 0 · 익명 read 0) · 고정 토큰 둘의
# 자리와 개수 · 녹화 · 훅 · 사이드카 관측 자격의 짝(compose 쪽과 같은 값).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

#######################################
# MediaMTX 렌더의 YAML 값(JSON)에 jq 필터를 적용한 값이 기대 JSON 과 같은지
# 본다.
# Arguments:
#   jq 필터, 기대 JSON(jq 식), 이름.
#######################################
mtx_cases::expect() {
  local want got
  if ! want="$(jq -n -cS --arg user "${SIDECAR_API_USER}" \
    --arg origin "${APP_ORIGIN}" --arg cdn "${CDN_TOKEN}" \
    --arg pass "${API_PASS_TOKEN}" "$2" 2>&1)"; then
    harness::add_reason "시험 오류(기대값 jq): ${want}"
    return 1
  fi
  got="$(jq -cS "$1" "$(mtx_json)" 2>/dev/null)" \
    || got='(MediaMTX 렌더 JSON 을 읽지 못함)'
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "mediamtx: $3 기대 ${want} · 실제 ${got}"
    return 1
  fi
}

mtx_cases::check_18() {
  require_setup || return 1
  if ! jq -e 'type == "object"' "$(mtx_json)" >/dev/null 2>&1; then
    harness::add_reason 'mediamtx 렌더가 YAML 객체가 아님'
    return 1
  fi
}

mtx_cases::check_19() {
  require_setup || return 1
  mtx_cases::expect '{srt, srtAddress, rtmp, rtmpAddress, rtsp, webrtc, moq, hls,
      hlsAddress, api, apiAddress, playback, playbackAddress, metrics, pprof}' '
    {srt: true, srtAddress: ":8890", rtmp: false, rtmpAddress: null,
     rtsp: false, webrtc: false, moq: false, hls: true,
     hlsAddress: "127.0.0.1:8888", api: true, apiAddress: "127.0.0.1:9997",
     playback: true, playbackAddress: "127.0.0.1:9996",
     metrics: false, pprof: false}' '프로토콜 · 루프백 바인딩'
}

mtx_cases::check_20() {
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  mtx_cases::expect '{hlsAllowOrigins, playbackAllowOrigins, hlsTrustedProxies,
      playbackTrustedProxies, apiTrustedProxies}' '
    {hlsAllowOrigins: [$origin], playbackAllowOrigins: [$origin],
     hlsTrustedProxies: [], playbackTrustedProxies: [],
     apiTrustedProxies: []}' '허용 출처 · 믿는 프록시 없음'
}

mtx_cases::check_21() {
  local ok=0
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  mtx_cases::expect '{authMethod: (.authMethod // "internal"),
      users: [.authInternalUsers[] | {user, pass: (.pass // ""),
        ips: (.ips // []), actions: [.permissions[].action],
        paths: [.permissions[] | .path // ""]}]}' '
    {authMethod: "internal", users: [
      {user: "any", pass: "", ips: [], actions: ["publish"], paths: [""]},
      {user: "any", pass: "", ips: ["127.0.0.1", "::1"],
       actions: ["playback"], paths: [""]},
      {user: $user, pass: $pass, ips: [], actions: ["api"], paths: [""]}]}' \
    '인증 항목(익명 publish · 루프백 playback · 사이드카 api)' || ok=1
  mtx_cases::expect '[.authInternalUsers[] | select(.user == "any")
      | .permissions[].action
      | select(. == "api" or . == "metrics" or . == "pprof" or . == "read")]' \
    '[]' 'user any 의 api · metrics · pprof · read 권한' || ok=1
  return "${ok}"
}

#######################################
# 렌더 원문에서 고정 문자열 줄(앞 공백 허용) 수를 센다.
# Arguments:
#   줄(앞 공백을 뺀 것).
#######################################
mtx_cases::count_line() {
  awk -v want="$1" '{ line = $0; sub(/^[ ]+/, "", line) }
    line == want { n++ } END { print n + 0 }' "$(mtx_render)"
}

mtx_cases::check_22() {
  local ok=0 token count
  require_setup || return 1
  for token in "${CDN_TOKEN}" "${API_PASS_TOKEN}"; do
    count="$(grep -o -F -- "${token}" "$(mtx_render)" | awk 'END { print NR }')"
    if [[ "${count}" != 1 ]]; then
      harness::add_reason "토큰 ${token} ${count}번(기대 1)"
      ok=1
    fi
  done
  if [[ "$(mtx_cases::count_line "hlsCDNSecret: \"${CDN_TOKEN}\"")" != 1 ]]; then
    harness::add_reason 'CDN 토큰이 hlsCDNSecret 큰따옴표 자리가 아님'
    ok=1
  fi
  if [[ "$(mtx_cases::count_line "pass: '${API_PASS_TOKEN}'")" != 1 \
    || "$(mtx_cases::count_line "- user: '${SIDECAR_API_USER}'")" != 1 ]]; then
    harness::add_reason '사이드카 user · pass 가 작은따옴표 자리가 아님'
    ok=1
  fi
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  mtx_cases::expect '.hlsCDNSecret' '$cdn' 'hlsCDNSecret 값' || ok=1
  return "${ok}"
}

mtx_cases::check_23() {
  require_setup || return 1
  mtx_cases::expect '{paths: (.paths | keys), hlsVariant, hlsSegmentDuration,
      hlsPartDuration, hlsSegmentCount, hlsDirectory,
      defaults: (.pathDefaults | {record, recordFormat, recordSegmentDuration,
        recordPath, runOnOnline, runOnOffline, runOnRecordSegmentComplete})}' '
    {paths: ["all_others"], hlsVariant: "lowLatency",
     hlsSegmentDuration: "4s", hlsPartDuration: "500ms",
     hlsSegmentCount: 900, hlsDirectory: "/dvr",
     defaults: {record: true, recordFormat: "fmp4",
       recordSegmentDuration: "4s",
       recordPath: "/recordings/%path/%Y-%m-%d_%H-%M-%S-%f",
       runOnOnline: "/hooks-bin/mtxhookwrite -kind online",
       runOnOffline: "/hooks-bin/mtxhookwrite -kind offline",
       runOnRecordSegmentComplete:
         "/hooks-bin/mtxhookwrite -kind segcomplete"}}' \
    '경로 · LL-HLS · 녹화 · 훅(사이드카 계약)'
}

#######################################
# 사례 24 — 사이드카 관측 자격의 짝: compose 사이드카 MTX_API_USER = MediaMTX
# api 항목의 user, MTX_API_PASS_FILE = 시작 스크립트가 tmpfs 에 둔 파일을
# 읽기 전용으로 붙인 자리, MTX_API_URL 포트 = apiAddress 포트.
#######################################
mtx_cases::check_24() {
  local ok=0 api_user env_user env_file mounts url_port api_port
  require_setup || return 1
  api_user="$(jq -r '[.authInternalUsers[]
    | select(any(.permissions[]; .action == "api")) | .user] | join(",")' \
    "$(mtx_json)" 2>/dev/null)" || api_user=""
  env_user="$(jq -r '.services.sidecar.environment.MTX_API_USER // ""' \
    "$(config_json a)" 2>/dev/null)" || env_user=""
  if [[ -z "${env_user}" || "${env_user}" != "${api_user}" ]]; then
    harness::add_reason "사이드카 MTX_API_USER 「${env_user}」 ≠ MediaMTX api \
항목 user 「${api_user}」"
    ok=1
  fi
  env_file="$(jq -r '.services.sidecar.environment.MTX_API_PASS_FILE // ""' \
    "$(config_json a)" 2>/dev/null)" || env_file=""
  mounts="$(jq -r --arg t "${env_file}" '[.services.sidecar.volumes[]
    | select(.target == $t and .read_only == true) | .source] | join(",")' \
    "$(config_json a)" 2>/dev/null)" || mounts=""
  if [[ "${env_file}" != /* \
    || "${mounts}" != /run/pokeclip-media/mtx_api_pass ]]; then
    harness::add_reason "MTX_API_PASS_FILE 「${env_file}」 자리의 읽기 전용 \
마운트 원본 「${mounts}」(기대 /run/pokeclip-media/mtx_api_pass)"
    ok=1
  fi
  url_port="$(jq -r '.services.sidecar.environment.MTX_API_URL // ""' \
    "$(config_json a)" 2>/dev/null)" || url_port=""
  url_port="${url_port##*:}"
  api_port="$(jq -r '.apiAddress // ""' "$(mtx_json)" 2>/dev/null)" \
    || api_port=""
  if [[ -z "${url_port}" || "${url_port}" != "${api_port##*:}" ]]; then
    harness::add_reason "MTX_API_URL 포트 「${url_port}」 ≠ apiAddress 포트"
    ok=1
  fi
  return "${ok}"
}
