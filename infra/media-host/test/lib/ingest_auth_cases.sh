# shellcheck shell=bash
#
# 송출 인증 계약 시험(test/ingest_auth_contract.sh)의 부품 — 고정 가짜 값 · 망 칸
# 탐침(curl) · SRT 송출 · 읽기 · 가짜 응답기 관리 · 층 L · A · B 의 사례 함수.
#
# 사례 함수는 harness::run_case 로 돌린다(통과 0 · 실패는 harness::add_reason).
# 층마다 스택을 띄우는 일은 본 파일(ingest_auth_contract.sh)의 몫이고, 사례는
# ia::require_layer 로 그 층이 떴는지 먼저 본다.
#
# 이 파일은 source 해서 쓴다. harness.sh 를 먼저 source 해야 한다. bash 3.2
# 에서도 돈다(빈 배열 펼치기 · mapfile · 연관 배열을 쓰지 않는다).

# 고정 가짜 값 — 실제 비밀이 아니다. C11 이 로그 · inspect 에서 이 값들을 찾는다.
# 꼴은 prod 와 같다(CDN 비밀 · API 비밀번호 · Basic · 잠금 = 16진 64자, 4C 출입증
# = 머리에 실을 수 있는 글자, passphrase = 10–64자의 [A-Za-z0-9_-]).
readonly IA_CDN_SECRET=\
'530cf149b112158a005fcbe69de7812ff1232e8ee9703ad7c2713e2d35338d74'
readonly IA_API_PASS=\
'3df5d9361c7e7db4792e5efaa61c805226412055c967873ea43840e327fdb8c5'
readonly IA_BASIC_PASS=\
'95be291326ae1096ea4ab51711022a8532e557a9b2f56b81d7e397db8dc3f03a'
readonly IA_PUBLISH_LOCK=\
'7b331bb740b0fab72888aaac8aa9d071cb1402b2471d62eb63fb1f1dcc0d3468'
readonly IA_READ_LOCK=\
'368c92c34ca173e814ce5744e3f75c5bd4cc6422a5338eb15a57f53e97ae23aa'
readonly IA_INTERNAL_TOKEN=\
'8679b392aa47539446ae8fcbd592b77ac94faf2bb775d0942b6c84181fe932e5'
readonly IA_PASSPHRASE='m15FakePassphrase0000000000000001'
readonly IA_WRONG_PASSPHRASE='m15WrongPassphrase000000000000002'
# 스트림 토큰(공개 식별자 · 경로 이름) — 26자 Crockford 대문자.
readonly IA_STREAM_TOKEN='M15TEST0000000000000000001'
readonly IA_BASIC_USER='pokeclip-mediamtx'
readonly IA_API_USER='pokeclip-sidecar'
# 다른 CDN Bearer(C14c).
readonly IA_OTHER_BEARER='pcbench-m15-not-the-cdn-secret'

# 송출이 받아들여져 경로가 ready 가 될 때까지 · 거부로 송출기가 끝날 때까지
# 기다리는 상한(초).
readonly IA_ACCEPT_TIMEOUT=25
readonly IA_REJECT_TIMEOUT=15
# LL-HLS 요청 한 번의 상한(초). 경로의 첫 재생목록 요청은 먹서를 만들고 첫
# 조각들이 찰 때까지 기다리므로 몇 초가 걸린다(기본 5초로는 000).
readonly IA_HLS_TIMEOUT=20
# SRT 읽기 한 번의 상한(초) — 3초 읽기 + 접속 · 탐색.
readonly IA_READ_TIMEOUT=30
# 응답기 인증 본문 상한(media/internal/ingestauth/request.go — 16 KiB).
readonly IA_BODY_LIMIT=16384
# C19 의 큰 본문 입력. 쿼리는 `a=1` 쌍 2,000개 — 포크 hls/http_server.go 가
# 쿼리를 풀어 다시 인코딩해도 값이 있는 쌍은 남고, 구분자 `&` 가 JSON 에서
# 6바이트(&)로 늘어 본문이 16 KiB 를 넘는다(빈 `&` 만 이으면 풀 때 사라진다).
# 요청 줄은 nginx 기본 한 줄 상한(8 KiB) 안이다. User-Agent 는 `<` 3,000개
# (< — 6바이트씩).
readonly IA_BIG_QUERY_PAIRS=2000
readonly IA_BIG_UA_CHARS=3000

ia_project=""
ia_compose_file=""
# 지금 떠 있는 층(C0 · L · A · B)과 준비 결과 — 본 파일의 stack_up 이 채운다.
ia_layer=""
ia_layer_ok=0
ia_layer_reason=""
# 층 A 에서 잰 C19 입력의 인증 본문 크기(바이트) — C19 의 자극 도달 확인.
ia_big_query_body=0
ia_big_ua_body=0

#######################################
# 이 lib 가 쓰는 compose 프로젝트 · 파일을 정한다.
# Globals:
#   ia_project, ia_compose_file
# Arguments:
#   프로젝트 이름, compose 파일.
#######################################
ia::configure() {
  ia_project="$1"
  ia_compose_file="$2"
}

#######################################
# 시험 compose 명령 하나를 상한 시간 안에서 돌린다.
# Arguments:
#   상한(초), compose 인자 …
#######################################
ia::compose_within() {
  local limit="$1"
  shift
  harness::bounded "${limit}" \
    docker compose -p "${ia_project}" -f "${ia_compose_file}" "$@"
}

ia::compose() { ia::compose_within "${HARNESS_DOCKER_TIMEOUT}" "$@"; }

#######################################
# 사례가 기대는 층이 떴는지 본다.
# Globals:
#   ia_layer, ia_layer_ok, ia_layer_reason
# Arguments:
#   층 이름.
#######################################
ia::require_layer() {
  if [[ "${ia_layer}" == "$1" ]] && (( ia_layer_ok )); then
    return 0
  fi
  harness::add_reason "층 $1 준비 실패 — ${ia_layer_reason:-원인 미기록}"
  return 1
}

# 망 칸 안에서 curl 하나를 돌린다(출력은 curl 의 것).
ia::probe_curl() {
  ia::compose run --rm --no-deps -T netprobe -s --max-time 5 "$@" \
    2>/dev/null
}

#######################################
# 망 칸 안 요청 하나의 상태 코드를 낸다(접속 실패 000).
# Arguments:
#   curl 인자 …
# Outputs:
#   세 자리 코드.
#######################################
ia::http_code() {
  local code
  code="$(ia::probe_curl -o /dev/null -w '%{http_code}' "$@")" || true
  [[ "${code}" =~ ^[0-9]{3}$ ]] || code=000
  printf '%s' "${code}"
}

#######################################
# 망 칸 안 요청 하나가 기대 코드인지 본다.
# Arguments:
#   기대 코드, 설명, curl 인자 …
#######################################
ia::expect_code() {
  local want="$1" what="$2" got
  shift 2
  got="$(ia::http_code "$@")"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "${what}: ${got}(기대 ${want})"
    return 1
  fi
}

# LL-HLS 요청 하나가 기대 코드인지 본다(상한 IA_HLS_TIMEOUT).
ia::expect_hls() {
  local want="$1" what="$2"
  shift 2
  ia::expect_code "${want}" "${what}" --max-time "${IA_HLS_TIMEOUT}" "$@"
}

# LL-HLS 재생목록 URL(경로는 시험 토큰).
ia::hls_url() {
  printf 'http://127.0.0.1:8888/%s/index.m3u8?%s' "${IA_STREAM_TOKEN}" "$1"
}

# 사이드카 자격으로 관리 API 에 GET 을 보낸다(출력은 응답 본문).
ia::api_get() {
  ia::probe_curl -u "${IA_API_USER}:${IA_API_PASS}" \
    "http://127.0.0.1:9997$1"
}

# 시험 토큰 경로에 송출이 붙어 ready 인지 본다(관리 API).
ia::path_ready() {
  local body
  body="$(ia::api_get "/v3/paths/get/${IA_STREAM_TOKEN}")" || return 1
  [[ "$(jq -r '.ready // false' <<<"${body}" 2>/dev/null)" == true ]]
}

#######################################
# SRT URL 을 만든다. passphrase 가 비면 싣지 않는다.
# Arguments:
#   streamid 의 m 값(publish|request), passphrase(빈 값 가능).
#######################################
ia::srt_url() {
  local url="srt://mediamtx:8890?streamid=#!::r=${IA_STREAM_TOKEN},m=$1"
  if [[ -n "$2" ]]; then
    url="${url}&passphrase=$2&pbkeylen=32"
  fi
  printf '%s' "${url}"
}

# 컨테이너 이름(프로젝트 접두 + 이름).
ia::name() { printf '%s-%s' "${ia_project}" "$1"; }

#######################################
# 송출기(기본 망의 ffmpeg — GOP 2초)를 배경 컨테이너로 띄운다.
# Arguments:
#   이름(ia::name 의 인자), passphrase(빈 값 가능).
#######################################
ia::sender_start() {
  ia::compose run -d --no-deps --name "$(ia::name "$1")" sender \
    -hide_banner -loglevel error -re -f lavfi \
    -i 'testsrc=size=320x240:rate=30' -t 600 -c:v libx264 \
    -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 -f mpegts \
    "$(ia::srt_url publish "$2")" >/dev/null 2>&1
}

ia::sender_stop() { docker rm -f "$(ia::name "$1")" >/dev/null 2>&1 || true; }

ia::running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$(ia::name "$1")" \
    2>/dev/null)" == true ]]
}

ia::exit_code() {
  docker inspect -f '{{.State.ExitCode}}' "$(ia::name "$1")" 2>/dev/null \
    || printf '?'
}

#######################################
# 송출을 띄우고 경로가 ready 가 될 때까지 기다린다. 받아들여지면 송출기를 그대로
# 둔다(부르는 쪽이 ia::sender_stop).
# Arguments:
#   이름, passphrase(빈 값 가능), 설명.
#######################################
ia::expect_accepted() {
  local deadline=$((SECONDS + IA_ACCEPT_TIMEOUT))
  if ! ia::sender_start "$1" "$2"; then
    harness::add_reason "$3: 송출기 시작 실패"
    return 1
  fi
  while (( SECONDS < deadline )); do
    if ! ia::running "$1"; then
      harness::add_reason "$3: 송출기가 끝남(종료 $(ia::exit_code "$1")) — 거부됨"
      return 1
    fi
    if ia::path_ready; then
      return 0
    fi
    sleep 1
  done
  harness::add_reason "$3: ${IA_ACCEPT_TIMEOUT}초 안에 경로 ready 아님"
  return 1
}

#######################################
# 송출을 띄우고 거부로 끝나는지 본다(상한 안에 0 아닌 종료). 송출기는 지운다.
# Arguments:
#   이름, passphrase(빈 값 가능), 설명.
#######################################
ia::expect_rejected() {
  local deadline=$((SECONDS + IA_REJECT_TIMEOUT)) code
  if ! ia::sender_start "$1" "$2"; then
    harness::add_reason "$3: 송출기 시작 실패"
    return 1
  fi
  while (( SECONDS < deadline )) && ia::running "$1"; do
    sleep 0.5
  done
  if ia::running "$1"; then
    ia::sender_stop "$1"
    harness::add_reason "$3: ${IA_REJECT_TIMEOUT}초 뒤에도 송출 중(기대: 거부)"
    return 1
  fi
  code="$(ia::exit_code "$1")"
  ia::sender_stop "$1"
  if [[ "${code}" == 0 ]]; then
    harness::add_reason "$3: 송출기가 0 으로 끝남(기대: 거부)"
    return 1
  fi
}

#######################################
# SRT 읽기 한 번(3초)이 기대대로 되는지 본다.
# Arguments:
#   ok|fail, passphrase(빈 값 가능), 설명.
#######################################
ia::expect_read() {
  local got=fail
  if ia::compose_within "${IA_READ_TIMEOUT}" run --rm --no-deps -T sender \
    -hide_banner -loglevel error -t 3 -i "$(ia::srt_url request "$2")" \
    -f null - >/dev/null 2>&1; then
    got=ok
  fi
  if [[ "${got}" != "$1" ]]; then
    harness::add_reason "$3: 읽기 ${got}(기대 $1)"
    return 1
  fi
}

#######################################
# 가짜 응답기(authstub) 관리 포트를 부른다.
# Arguments:
#   경로, 메서드(없으면 GET).
#######################################
ia::stub_admin() {
  ia::compose exec -T authstub /echo fetch -method "${2:-GET}" \
    "http://127.0.0.1:28199$1" 2>/dev/null
}

ia::stub_mode() {
  if ! ia::stub_admin "/mode?m=$1" POST >/dev/null; then
    harness::add_reason "가짜 응답기 모드 $1 전환 실패"
    return 1
  fi
}

#######################################
# 가짜 응답기가 기억한 publish 허용 가운데 마지막 것을 낸다.
# Outputs:
#   기록 JSON 한 줄(없으면 빈 줄).
#######################################
ia::last_allowed_publish() {
  ia::stub_admin /records | jq -c --arg p "${IA_STREAM_TOKEN}" \
    '[.[] | select(.action == "publish" and .path == $p
      and .status == 200)] | last // empty'
}

# 서비스 로그(머리 없이).
ia::logs() { ia::compose logs --no-color --no-log-prefix "$@" 2>/dev/null; }

#######################################
# 응답기 로그에서 이벤트 줄 수를 낸다(slog JSON 의 msg).
# Arguments:
#   이벤트 이름.
# Outputs:
#   줄 수(로그를 읽었을 때만).
#######################################
ia::count_event() {
  local logs
  logs="$(ia::logs ingest-auth)" || return 1
  printf '%s\n' "${logs}" | grep -c -F "\"msg\":\"$1\"" || true
}

# C19 의 큰 쿼리(cookieCheck=1 + a=1 쌍).
ia::big_query() {
  local i query='cookieCheck=1'
  for (( i = 0; i < IA_BIG_QUERY_PAIRS; i++ )); do
    query="${query}&a=1"
  done
  printf '%s' "${query}"
}

# C19 의 큰 User-Agent(`<` 반복).
ia::big_user_agent() { printf "%${IA_BIG_UA_CHARS}s" '' | tr ' ' '<'; }

#######################################
# C19 의 두 요청(큰 쿼리 · 큰 User-Agent — Authorization 없음)을 보낸다.
# Outputs:
#   "<쿼리 요청 코드> <User-Agent 요청 코드>".
#######################################
ia::send_big_requests() {
  local query_code ua_code
  query_code="$(ia::http_code --max-time "${IA_HLS_TIMEOUT}" \
    "$(ia::hls_url "$(ia::big_query)")")"
  ua_code="$(ia::http_code --max-time "${IA_HLS_TIMEOUT}" \
    -A "$(ia::big_user_agent)" "$(ia::hls_url cookieCheck=1)")"
  printf '%s %s' "${query_code}" "${ua_code}"
}

# ---- C0 · 층 L(옛 모드 — 회귀 기준선) ----------------------------------------

check_c0() {
  local body ok=0
  ia::require_layer C0 || return 1
  if ! body="$(ia::probe_curl http://127.0.0.1:9997/v3/config/global/get)"; then
    harness::add_reason '설정 조회(/v3/config/global/get) 실패'
    return 1
  fi
  if [[ "$(jq -r '.authMethod' <<<"${body}" 2>/dev/null)" != http ]]; then
    harness::add_reason '조회한 authMethod 가 http 아님'
    ok=1
  fi
  if [[ "$(jq -c '.authInternalUsers' <<<"${body}" 2>/dev/null)" != '[]' ]]
  then
    harness::add_reason '조회한 authInternalUsers 가 빈 목록 아님'
    ok=1
  fi
  return "${ok}"
}

check_l1() {
  ia::require_layer L || return 1
  ia::expect_accepted l1 '' '익명 송출'
}

check_l2() {
  local ok=0
  ia::require_layer L || return 1
  ia::expect_hls 200 'CDN Bearer' -H "Authorization: Bearer ${IA_CDN_SECRET}" \
    "$(ia::hls_url cookieCheck=1)" || ok=1
  ia::expect_hls 401 'Bearer 없음' "$(ia::hls_url cookieCheck=1)" || ok=1
  return "${ok}"
}

check_l3() {
  local ok=0 url='http://127.0.0.1:9997/v3/paths/list'
  ia::require_layer L || return 1
  ia::expect_code 200 '사이드카 자격' -u "${IA_API_USER}:${IA_API_PASS}" \
    "${url}" || ok=1
  ia::expect_code 401 '무자격' "${url}" || ok=1
  return "${ok}"
}

# ---- 층 A(실물 MediaMTX + 가짜 응답기) --------------------------------------

check_c1() {
  ia::require_layer A || return 1
  ia::stub_mode allow || return 1
  ia::expect_accepted a-c1 "${IA_PASSPHRASE}" '응답기 passphrase 로 송출'
}

check_c4() {
  local record want got
  ia::require_layer A || return 1
  record="$(ia::last_allowed_publish)" || record=""
  if [[ -z "${record}" ]]; then
    harness::add_reason '허용한 publish 기록 없음(C1 선행)'
    return 1
  fi
  want="Basic $(printf '%s:%s' "${IA_BASIC_USER}" "${IA_BASIC_PASS}" \
    | base64 | tr -d '\n')"
  got="$(jq -r '.authorization' <<<"${record}")"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason '응답기가 받은 Authorization 이 렌더 자격의 Basic 이 아님'
    return 1
  fi
}

check_c5() {
  local record body_ip sender_ip
  ia::require_layer A || return 1
  record="$(ia::last_allowed_publish)" || record=""
  sender_ip="$(docker inspect -f \
    '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
    "$(ia::name a-c1)" 2>/dev/null)" || sender_ip=""
  [[ -n "${record}" ]] || record='{}'
  body_ip="$(jq -r '.ip // empty' <<<"${record}")"
  if [[ -z "${sender_ip}" || "${body_ip}" != "${sender_ip}" ]]; then
    harness::add_reason "본문 ip(${body_ip:-없음}) ≠ 송출자 IP(${sender_ip:-없음})"
    return 1
  fi
}

check_c8() {
  local ok=0
  ia::require_layer A || return 1
  ia::expect_read fail '' 'passphrase 없는 SRT 읽기' || ok=1
  # 양성 대조 — 읽기 잠금값이면 읽힌다(실패 까닭이 잠금임을 보인다).
  ia::expect_read ok "${IA_READ_LOCK}" '읽기 잠금값으로 읽기(양성 대조)' || ok=1
  return "${ok}"
}

check_c10() {
  local ok=0
  ia::require_layer A || return 1
  ia::expect_rejected a-c10 "${IA_PASSPHRASE}" '같은 경로 둘째 송출' || ok=1
  if ! ia::running a-c1 || ! ia::path_ready; then
    harness::add_reason '첫 송출이 살아 있지 않음(탈취)'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 층 A 에서 C19 입력의 인증 본문 크기를 잰다(가짜 응답기가 받은 길이).
# Globals:
#   ia_big_query_body, ia_big_ua_body
#######################################
measure_big_bodies() {
  local records
  ia::send_big_requests >/dev/null
  records="$(ia::stub_admin /records)" || return 1
  ia_big_query_body="$(jq '[.[] | select(.query_bytes > 7000)
    | .body_bytes] | max // 0' <<<"${records}")" || ia_big_query_body=0
  ia_big_ua_body="$(jq --argjson n "${IA_BIG_UA_CHARS}" '[.[]
    | select(.user_agent_bytes >= $n) | .body_bytes] | max // 0' \
    <<<"${records}")" || ia_big_ua_body=0
}

check_c2() {
  ia::require_layer A || return 1
  ia::expect_rejected a-c2 "${IA_WRONG_PASSPHRASE}" '틀린 passphrase'
}

check_c3() {
  ia::require_layer A || return 1
  ia::expect_rejected a-c3 '' 'passphrase 없는 송출'
}

check_c7() {
  local ok=0
  ia::require_layer A || return 1
  ia::stub_mode allow-empty || return 1
  ia::expect_rejected a-c7p "${IA_PASSPHRASE}" '빈 허용 + passphrase' || ok=1
  ia::expect_rejected a-c7n '' '빈 허용 + passphrase 없음' || ok=1
  ia::stub_mode allow || ok=1
  return "${ok}"
}

check_c6() {
  local ok=0
  ia::require_layer A || return 1
  if ! ia::compose stop authstub >/dev/null 2>&1; then
    harness::add_reason '가짜 응답기 정지 실패'
    return 1
  fi
  ia::expect_rejected a-c6 "${IA_PASSPHRASE}" '응답기 정지 중 송출' || ok=1
  if ! ia::compose start authstub >/dev/null 2>&1; then
    harness::add_reason '가짜 응답기 다시 시작 실패'
    ok=1
  fi
  return "${ok}"
}

# ---- 층 B(실물 MediaMTX + 실물 응답기 + 가짜 4C) ----------------------------

#######################################
# C16 — 기동 직후 poller 의 관리 API 폴 결과를 기록한다.
# Globals:
#   harness_record_note
#######################################
record_c16() {
  local logs n401 n000 total
  ia::require_layer B || return 1
  logs="$(ia::logs poller)" || {
    harness::add_reason 'poller 로그를 읽지 못함'
    return 1
  }
  total="$(printf '%s\n' "${logs}" | grep -c '^POLL ' || true)"
  n401="$(printf '%s\n' "${logs}" | grep -c '^POLL 401$' || true)"
  n000="$(printf '%s\n' "${logs}" | grep -c '^POLL 000$' || true)"
  # shellcheck disable=SC2034 # harness::run_record 가 읽는다.
  harness_record_note="폴 ${total}회 — 401 ${n401} · 접속 실패 ${n000} · \
마지막 $(printf '%s\n' "${logs}" | grep '^POLL ' | tail -n 1)"
}

check_c9() {
  local ok=0 started counts
  ia::require_layer B || return 1
  started="$(ia::logs ingest-auth | grep -F '"msg":"ingestauth_started"' \
    | tail -n 1)" || started=""
  [[ -n "${started}" ]] || started='{}'
  if [[ "$(jq -r '.caller_auth' <<<"${started}")" != basic \
    || "$(jq -r '.auth_channel' <<<"${started}")" != internal-token ]]; then
    harness::add_reason \
      'started 의 caller_auth=basic · auth_channel=internal-token 아님'
    ok=1
  fi
  ia::expect_accepted b-c9 "${IA_PASSPHRASE}" '실물 응답기 경유 송출' || ok=1
  counts="$(ia::compose exec -T resolvestub /echo fetch \
    http://127.0.0.1:28199/counts 2>/dev/null)" || counts='{}'
  if (( "$(jq -r '.valid // 0' <<<"${counts}")" < 1 )); then
    harness::add_reason '가짜 4C 의 valid 응답 0건(응답기가 4C 를 안 부름)'
    ok=1
  fi
  return "${ok}"
}

check_c12() {
  local ok=0 url='http://127.0.0.1:9997/v3/paths/list'
  ia::require_layer B || return 1
  ia::expect_code 200 '사이드카 자격' -u "${IA_API_USER}:${IA_API_PASS}" \
    "${url}" || ok=1
  ia::expect_code 401 '무자격' "${url}" || ok=1
  ia::expect_code 401 '틀린 비밀번호' -u "${IA_API_USER}:${IA_WRONG_PASSPHRASE}" \
    "${url}" || ok=1
  return "${ok}"
}

check_c14() {
  local ok=0 denied index_code
  ia::require_layer B || return 1
  # b 를 먼저 — 첫 요청이 먹서를 만드는 지연을 CDN 요청이 진다.
  ia::expect_hls 200 'b CDN Bearer' \
    -H "Authorization: Bearer ${IA_CDN_SECRET}" \
    "$(ia::hls_url cookieCheck=1)" || ok=1
  ia::expect_hls 401 'a Authorization 없음' "$(ia::hls_url cookieCheck=1)" \
    || ok=1
  ia::expect_hls 401 'c 다른 Bearer' \
    -H "Authorization: Bearer ${IA_OTHER_BEARER}" \
    "$(ia::hls_url cookieCheck=1)" || ok=1
  index_code="$(ia::http_code --max-time "${IA_HLS_TIMEOUT}" \
    "http://127.0.0.1:8888/${IA_STREAM_TOKEN}/")"
  printf '기록 C14d — index 페이지(Authorization 없음) %s\n' "${index_code}"
  if ! denied="$(ia::count_event ingestauth_read_denied)"; then
    harness::add_reason '응답기 로그를 읽지 못함'
    return 1
  fi
  if (( denied != 0 )); then
    harness::add_reason "info 로그에 ingestauth_read_denied ${denied}줄(기대 0)"
    ok=1
  fi
  return "${ok}"
}

check_c17() {
  local ok=0
  ia::require_layer B || return 1
  ia::expect_read fail "${IA_PASSPHRASE}" '응답기 passphrase 로 SRT 읽기' || ok=1
  ia::expect_read fail '' 'passphrase 없는 SRT 읽기' || ok=1
  ia::expect_read ok "${IA_READ_LOCK}" '읽기 잠금값으로 읽기(양성 대조)' || ok=1
  return "${ok}"
}

check_c19() {
  local ok=0 codes invalid
  ia::require_layer B || return 1
  if (( ia_big_query_body <= IA_BODY_LIMIT || ia_big_ua_body <= IA_BODY_LIMIT ))
  then
    harness::add_reason "자극 미도달 — 층 A 에서 잰 본문 쿼리 ${ia_big_query_body} · \
User-Agent ${ia_big_ua_body} 바이트(기대 > ${IA_BODY_LIMIT})"
    ok=1
  fi
  codes="$(ia::send_big_requests)"
  if [[ "${codes}" != '401 401' ]]; then
    harness::add_reason "큰 쿼리 · 큰 User-Agent 응답 ${codes}(기대 401 401)"
    ok=1
  fi
  if ! invalid="$(ia::count_event ingestauth_request_invalid)"; then
    harness::add_reason '응답기 로그를 읽지 못함'
    return 1
  fi
  if (( invalid != 0 )); then
    harness::add_reason "info 로그에 ingestauth_request_invalid ${invalid}줄(기대 0)"
    ok=1
  fi
  return "${ok}"
}

#######################################
# c5 운영 스크립트가 필요한 사례(C13 · C15 · C18). 스크립트가 없으면 「없음」
# 으로 실패한다 — 건너뛰지 않는다. 있으면 이음매(시험 compose · 경로)를 c5 가
# 정해 이 사례 본문을 채울 때까지 실패로 남는다.
# Arguments:
#   media-host 디렉터리, 스크립트 상대 경로 …
#######################################
require_ops_scripts() {
  local dir="$1" rel missing=""
  shift
  ia::require_layer B || return 1
  for rel in "$@"; do
    [[ -f "${dir}/${rel}" ]] || missing="${missing:+${missing} · }${rel}"
  done
  if [[ -n "${missing}" ]]; then
    harness::add_reason "없음(c5 전) — ${missing}"
    return 1
  fi
  harness::add_reason '스크립트는 있으나 시험 이음매가 없음 — c5 가 이 사례 본문을 채운다'
  return 1
}

check_c11() {
  local ok=0 id inspect logs secret
  ia::require_layer B || return 1
  id="$(ia::compose ps -q ingest-auth 2>/dev/null)" || id=""
  if [[ -z "${id}" ]] || ! inspect="$(docker inspect "${id}" 2>/dev/null)"; then
    harness::add_reason '응답기 inspect 실패'
    return 1
  fi
  if ! logs="$(ia::logs mediamtx ingest-auth)"; then
    harness::add_reason '로그를 읽지 못함'
    return 1
  fi
  for secret in "${IA_CDN_SECRET}" "${IA_API_PASS}" "${IA_BASIC_PASS}" \
    "${IA_PUBLISH_LOCK}" "${IA_READ_LOCK}" "${IA_INTERNAL_TOKEN}"; do
    if [[ "${inspect}" == *"${secret}"* ]]; then
      harness::add_reason "inspect 에 비밀(${secret:0:6}…)"
      ok=1
    fi
    if [[ "${logs}" == *"${secret}"* ]]; then
      harness::add_reason "로그에 비밀(${secret:0:6}…)"
      ok=1
    fi
  done
  if [[ "${logs}" == *"${IA_PASSPHRASE}"* ]]; then
    harness::add_reason '로그에 passphrase'
    ok=1
  fi
  return "${ok}"
}
