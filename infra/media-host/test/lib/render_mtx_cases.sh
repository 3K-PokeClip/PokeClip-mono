# shellcheck shell=bash
# 전역(scratch · setup_reason · TEST_DIR · *_TOKEN 등)과 require_setup ·
# mtx_render · mtx_json · config_json 은 render_test.sh 가 정의한다.
# shellcheck disable=SC2154
#
# render_test 의 MediaMTX 운영 템플릿 사례(18 – 24 · 30 – 34) — mediamtx.yml.tmpl
# 을 templatefile() 로 렌더하고 yamldecode() 로 읽은 값에 단언한다. 모드는
# 템플릿 변수 ingest_auth_image 하나가 고른다 — 갈래 a(빔)가 옛 모드, 갈래
# b(참)가 응답기 모드다.
#   - 옛 모드(18 – 24 · 30): 루프백 바인딩 · 믿는 프록시 없음 · 인증 항목 셋
#     (무인증 api 0 · 익명 read 0) · 고정 토큰 둘의 자리와 개수 · 녹화 · 훅 ·
#     사이드카 관측 자격의 짝(compose 쪽과 같은 값) · develop 골든과 같은 값.
#   - 응답기 모드(31 – 34): 인증 블록(http · Basic URL · exclude {playback} ·
#     내부 사용자 빔) · 고정 토큰 넷의 자리와 개수 · SRT 잠금 둘 ·
#     overridePublisher 끔 · 옛 모드와 다른 키가 그것들뿐임.
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). bash 3.2 에서도 돈다.

# 응답기 모드에서만 나오는 고정 토큰(시작 스크립트가 채운다 — 각 정확히 한 번).
readonly BASIC_PASS_TOKEN='@@INGEST_AUTH_BASIC_PASS@@'
readonly PUBLISH_LOCK_TOKEN='@@SRT_PUBLISH_LOCK@@'
readonly READ_LOCK_TOKEN='@@SRT_READ_LOCK@@'
# 응답기 모드 authHTTPAddress 의 렌더 줄 값. 사용자 이름은 응답기
# INGEST_AUTH_BASIC_USER 와 같은 설정값이고(비밀 아님), 주소는 응답기 듣기
# 주소다(ingest-auth CONTRACT 2절 후보값 표 1 · 4b).
readonly AUTH_HTTP_ADDRESS="http://pokeclip-mediamtx:${BASIC_PASS_TOKEN}@\
127.0.0.1:8080/mediamtx/auth"
# 옛 모드 골든 — develop 6c90c935 의 템플릿을 render_vars.json 으로 렌더한
# yamldecode JSON. CI checkout 은 이력이 얕아 시험이 git 으로 옛 판을 읽지
# 않는다(만든 명령은 M15 c2a 실험 기록).
readonly MTX_LEGACY_GOLDEN_REL='golden/mediamtx.legacy.json'
# 두 모드 JSON 의 차이 키(최상위 · pathDefaults 아래는 pathDefaults.<키>) 정본.
readonly MTX_MODE_DIFF_KEYS='["authHTTPAddress", "authHTTPExclude",
  "authInternalUsers", "authMethod", "pathDefaults.overridePublisher",
  "pathDefaults.srtPublishPassphrase", "pathDefaults.srtReadPassphrase"]'

#######################################
# MediaMTX 렌더의 YAML 값(JSON)에 jq 필터를 적용한 값이 기대 JSON 과 같은지
# 본다.
# Arguments:
#   jq 필터, 기대 JSON(jq 식), 이름, 갈래(없으면 a).
#######################################
mtx_cases::expect() {
  local v="${4:-a}" want got
  if ! want="$(jq -n -cS --arg user "${SIDECAR_API_USER}" \
    --arg origin "${APP_ORIGIN}" --arg cdn "${CDN_TOKEN}" \
    --arg pass "${API_PASS_TOKEN}" --arg address "${AUTH_HTTP_ADDRESS}" \
    --arg publock "${PUBLISH_LOCK_TOKEN}" --arg readlock "${READ_LOCK_TOKEN}" \
    "$2" 2>&1)"; then
    harness::add_reason "시험 오류(기대값 jq): ${want}"
    return 1
  fi
  got="$(jq -cS "$1" "$(mtx_json "${v}")" 2>/dev/null)" \
    || got='(MediaMTX 렌더 JSON 을 읽지 못함)'
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "mediamtx 갈래 ${v}: $3 기대 ${want} · 실제 ${got}"
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
#   줄(앞 공백을 뺀 것), 갈래(없으면 a).
#######################################
mtx_cases::count_line() {
  awk -v want="$1" '{ line = $0; sub(/^[ ]+/, "", line) }
    line == want { n++ } END { print n + 0 }' "$(mtx_render "${2:-a}")"
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

#######################################
# 두 MediaMTX JSON 의 값이 다른 키를 낸다 — 최상위 키, 그리고 pathDefaults 가
# 다르면 그 대신 그 아래의 다른 키(pathDefaults.<키>). 한쪽에만 있는 키도 다른
# 키다.
# Arguments:
#   JSON 파일 둘.
# Outputs:
#   정렬한 키 목록(JSON 한 줄).
# Returns:
#   0 냄 / 그 밖 읽기 실패.
#######################################
mtx_cases::diff_keys() {
  jq -n -cS --slurpfile x "$1" --slurpfile y "$2" '
    def diff($a; $b): [($a + $b | keys[]) | select($a[.] != $b[.])];
    $x[0] as $a | $y[0] as $b | diff($a; $b) as $top
    | [$top[] | select(. != "pathDefaults")]
      + (if ($top | index("pathDefaults")) == null then []
         else [diff($a.pathDefaults // {}; $b.pathDefaults // {})[]
           | "pathDefaults.\(.)"] end)
    | sort'
}

#######################################
# 렌더 원문의 고정 토큰 꼴(@@ 로 감싼 이름)마다 나온 횟수를 낸다.
# Arguments:
#   갈래.
# Outputs:
#   「횟수 토큰」 줄들(토큰 순 — 토큰이 없으면 아무것도 내지 않는다).
# Returns:
#   0 셈 / 그 밖 읽기 실패(grep 의 종료 코드).
#######################################
mtx_cases::token_counts() {
  local found status=0
  found="$(grep -o -E -- '@@[A-Za-z0-9_]*@@' "$(mtx_render "$1")")" \
    || status=$?
  if (( status > 1 )); then
    return "${status}"
  fi
  [[ -n "${found}" ]] || return 0
  printf '%s\n' "${found}" | sort | uniq -c | awk '{ print $1, $2 }'
}

# 사례 30 — 옛 모드 렌더가 develop 의 렌더와 같은 값이다(결정 2 · 회귀 0).
mtx_cases::check_30() {
  local golden="${TEST_DIR}/${MTX_LEGACY_GOLDEN_REL}" want got keys
  require_setup || return 1
  if ! want="$(jq -cS . "${golden}" 2>/dev/null)"; then
    harness::add_reason "골든 ${MTX_LEGACY_GOLDEN_REL} 을 읽지 못함"
    return 1
  fi
  got="$(jq -cS . "$(mtx_json a)" 2>/dev/null)" || got=''
  if [[ "${got}" == "${want}" ]]; then
    return 0
  fi
  keys="$(mtx_cases::diff_keys "${golden}" "$(mtx_json a)" 2>/dev/null)" \
    || keys='(차이 키를 셈하지 못함)'
  harness::add_reason "옛 모드 렌더가 골든과 다름 — 차이 키 ${keys}"
  return 1
}

# 사례 31 — 응답기 모드 인증 블록. api 는 응답기를 거치고(K2) read 는 응답기가
# SRT 만 받는다(c0) — exclude 는 playback 하나다(결정 1). 내부 사용자 목록은
# 비어야 한다 — authMethod 가 바뀌어도 옛 익명 publish 가 살아나지 않게.
mtx_cases::check_31() {
  local ok=0
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  mtx_cases::expect '{authMethod, authHTTPAddress, authHTTPExclude}' '
    {authMethod: "http", authHTTPAddress: $address,
     authHTTPExclude: [{action: "playback"}]}' \
    '인증(http · Basic URL · exclude 정확 집합 {playback})' b || ok=1
  mtx_cases::expect '.authInternalUsers
      | if type == "array" then length else type end' '0' \
    'authInternalUsers 길이(없음 · 항목 있음이면 내부 사용자가 산다)' b \
    || ok=1
  if [[ "$(mtx_cases::count_line 'authMethod: http' b)" != 1 ]]; then
    harness::add_reason '응답기 모드 렌더에 authMethod: http 줄이 정확히 한 번이 아님'
    ok=1
  fi
  return "${ok}"
}

# 사례 32 — 응답기 모드의 고정 토큰 넷 각 한 번 · API 비밀번호 토큰 0 · 따옴표
# 자리(렌더 줄 정확).
mtx_cases::check_32() {
  local ok=0 want got line
  require_setup || return 1
  want="$(printf '1 %s\n' "${BASIC_PASS_TOKEN}" "${CDN_TOKEN}" \
    "${PUBLISH_LOCK_TOKEN}" "${READ_LOCK_TOKEN}" | sort | paste -s -d ',' -)"
  if got="$(mtx_cases::token_counts b)"; then
    got="$(printf '%s\n' "${got}" | paste -s -d ',' -)"
  else
    got='(렌더 원문을 읽지 못함)'
  fi
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "응답기 모드 토큰 집계 기대 「${want}」 · 실제 「${got}」"
    ok=1
  fi
  for line in "hlsCDNSecret: \"${CDN_TOKEN}\"" \
    "authHTTPAddress: '${AUTH_HTTP_ADDRESS}'" \
    "srtPublishPassphrase: '${PUBLISH_LOCK_TOKEN}'" \
    "srtReadPassphrase: '${READ_LOCK_TOKEN}'"; do
    if [[ "$(mtx_cases::count_line "${line}" b)" != 1 ]]; then
      harness::add_reason "응답기 모드 렌더 줄 「${line}」 이 정확히 한 번이 아님"
      ok=1
    fi
  done
  return "${ok}"
}

# 사례 33 — 응답기 모드 SRT 경로 잠금(D12): 송출 · 읽기 잠금 따로 · 송출 탈취
# 막음.
mtx_cases::check_33() {
  require_setup || return 1
  # shellcheck disable=SC2016 # jq 프로그램 — $ 는 jq 변수다.
  mtx_cases::expect '.pathDefaults
      | {srtPublishPassphrase, srtReadPassphrase, overridePublisher}' '
    {srtPublishPassphrase: $publock, srtReadPassphrase: $readlock,
     overridePublisher: false}' 'SRT 잠금 둘 · overridePublisher' b
}

# 사례 34 — 두 모드의 차이는 인증 키와 pathDefaults 잠금 키뿐이다(경로 · LL-HLS ·
# 녹화 · 훅 · hlsCDNSecret 은 같다).
mtx_cases::check_34() {
  local want got
  require_setup || return 1
  want="$(jq -cS . <<<"${MTX_MODE_DIFF_KEYS}")"
  got="$(mtx_cases::diff_keys "$(mtx_json a)" "$(mtx_json b)" 2>/dev/null)" \
    || got='(두 모드 JSON 을 읽지 못함)'
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "두 모드 차이 키 기대 ${want} · 실제 ${got}"
    return 1
  fi
}
