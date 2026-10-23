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
# = 머리에 실을 수 있는 글자, passphrase = 10–64자의 [A-Za-z0-9_-]). 16진 값은
# 무작위가 아니라 무늬다 — 머리 8자 fa4e5ec7(가짜 표지) · 값 번호 두 자리 ·
# 0 여섯 · 0123456789abcdef 세 번. 비밀 훑기에서 진짜 값과 가른다.
readonly IA_CDN_SECRET=\
'fa4e5ec7010000000123456789abcdef0123456789abcdef0123456789abcdef'
readonly IA_API_PASS=\
'fa4e5ec7020000000123456789abcdef0123456789abcdef0123456789abcdef'
readonly IA_BASIC_PASS=\
'fa4e5ec7030000000123456789abcdef0123456789abcdef0123456789abcdef'
readonly IA_PUBLISH_LOCK=\
'fa4e5ec7040000000123456789abcdef0123456789abcdef0123456789abcdef'
readonly IA_READ_LOCK=\
'fa4e5ec7050000000123456789abcdef0123456789abcdef0123456789abcdef'
readonly IA_INTERNAL_TOKEN=\
'fa4e5ec7060000000123456789abcdef0123456789abcdef0123456789abcdef'
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
# 운영 스크립트(C13 · C15 · C18 — media-host 디렉터리 기준)와 가짜 SSM 거부
# 목록 파라미터 이름 · 실행 한 번의 상한(초 — 재생성 healthy 대기 120초를
# 품는다).
readonly IA_IP_LIMIT_SCRIPT='bin/ingest-auth-ip-limit.sh'
readonly IA_DENY_SYNC_SCRIPT='bin/ingest-auth-deny-sync.sh'
readonly IA_DENY_PARAM='pcbench-m15-deny-tokens'
readonly IA_OPS_TIMEOUT=200
# 보조 컨테이너 라벨(본 파일의 정리 대상과 같다).
readonly IA_BENCH_LABEL='com.pokeclip.bench=m15-ingest-auth'

ia_project=""
ia_compose_file=""
# 운영 스크립트 사례의 이음매 — ia::configure_ops 가 채운다.
ia_ops_media_dir=""
ia_ops_work=""
ia_ops_deny_dir=""
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

# 끝난 송출기가 메모리 부족으로 죽었는지(조회 실패도 참 — 도구 실패 쪽).
ia::oom_killed() {
  [[ "$(docker inspect -f '{{.State.OOMKilled}}' "$(ia::name "$1")" \
    2>/dev/null)" != false ]]
}

#######################################
# 송출 · 읽기 명령의 종료 코드가 거부(인증 · 잠금이 막음)로 셀 수 있는 것인지
# 본다. 조회 실패(?) · 0 · 상한 초과(124) · docker 가 명령을 못 돌림(125–127)은
# 거부가 아니다. ffmpeg 는 오류를 음수 errno 의 하위 바이트로 내므로(예: 251)
# 128 이상도 거부로 센다.
# Arguments:
#   종료 코드, 설명.
#######################################
ia::rejection_code() {
  case "$1" in
    0) harness::add_reason "$2: 0 으로 끝남(기대: 거부)" ;;
    124) harness::add_reason "$2: 시간 초과(거부로 세지 않음)" ;;
    125 | 126 | 127) harness::add_reason "$2: 명령 실행 실패(종료 $1)" ;;
    *)
      [[ "$1" =~ ^[0-9]+$ ]] && return 0
      harness::add_reason "$2: 종료 코드 조회 실패(${1:-빈 값})"
      ;;
  esac
  return 1
}

#######################################
# MediaMTX 가 살아 있는지 본다 — 컨테이너 실행 중 · 관리 API 가 답함(코드가
# 000 아님 — 응답기 정지 중이면 401 일 수 있다).
# Arguments:
#   설명.
#######################################
ia::expect_mtx_alive() {
  local id
  id="$(ia::compose ps -q mediamtx 2>/dev/null)" || id=""
  if [[ -z "${id}" || "$(docker inspect -f '{{.State.Running}}' "${id}" \
    2>/dev/null)" != true \
    || "$(ia::http_code http://127.0.0.1:9997/v3/paths/list)" == 000 ]]; then
    harness::add_reason "$1: 뒤에 MediaMTX 가 살아 있지 않음"
    return 1
  fi
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
# 송출을 띄우고 거부로 끝나는지 본다 — 상한 안에 끝나고, 종료 코드가 거부로 셀
# 수 있는 것이고(ia::rejection_code · 메모리 부족 아님), 그 뒤에도 MediaMTX 가
# 살아 있다. 송출기는 지운다.
# Arguments:
#   이름, passphrase(빈 값 가능), 설명.
#######################################
ia::expect_rejected() {
  local deadline=$((SECONDS + IA_REJECT_TIMEOUT)) code ok=0
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
  if ia::oom_killed "$1"; then
    harness::add_reason "$3: 송출기가 메모리 부족으로 죽음(또는 조회 실패)"
    ok=1
  fi
  ia::sender_stop "$1"
  ia::rejection_code "${code}" "$3 송출기" || ok=1
  ia::expect_mtx_alive "$3" || ok=1
  return "${ok}"
}

# 가짜 응답기가 기억한 시험 경로 publish 요청 수(조회 실패면 실패).
ia::stub_publish_count() {
  local records
  records="$(ia::stub_admin /records)" || return 1
  jq --arg p "${IA_STREAM_TOKEN}" \
    '[.[] | select(.action == "publish" and .path == $p)] | length' \
    <<<"${records}"
}

#######################################
# 송출 하나가 가짜 응답기에 닿았고(publish 요청이 늘었다) 응답기는 허용(200)
# 했는데 거부로 끝났는지 본다 — 거부가 MediaMTX 쪽(잠금 · 경로 점유) 판정에서
# 왔음을 가른다. 송출 자체는 ia::expect_rejected 가 본다.
# Arguments:
#   이름, passphrase(빈 값 가능), 설명.
#######################################
ia::expect_stub_rejected() {
  local before records seen ok=0
  if ! before="$(ia::stub_publish_count)"; then
    harness::add_reason "$3: 가짜 응답기 기록 조회 실패"
    return 1
  fi
  ia::expect_rejected "$@" || ok=1
  # 새 publish 기록의 「개수 모두200여부」(예: 「1 true」).
  if ! records="$(ia::stub_admin /records)" || ! seen="$(jq -r \
    --arg p "${IA_STREAM_TOKEN}" --argjson n "${before}" '[.[]
    | select(.action == "publish" and .path == $p)] | .[$n:]
    | "\(length) \(all(.status == 200))"' <<<"${records}")"; then
    harness::add_reason "$3: 가짜 응답기 기록 조회 실패"
    return 1
  fi
  if [[ "${seen}" == '0 '* ]]; then
    harness::add_reason "$3: 자극 미도달 — 가짜 응답기가 publish 요청을 못 받음"
    ok=1
  elif [[ "${seen}" != *' true' ]]; then
    harness::add_reason "$3: 가짜 응답기 답이 모두 200 이 아님(기록 ${seen})"
    ok=1
  fi
  return "${ok}"
}

#######################################
# SRT 읽기 한 번(3초)이 기대대로 되는지 본다.
# Arguments:
#   ok|fail, passphrase(빈 값 가능), 설명.
#######################################
ia::expect_read() {
  local got=fail status=0
  ia::compose_within "${IA_READ_TIMEOUT}" run --rm --no-deps -T sender \
    -hide_banner -loglevel error -t 3 -i "$(ia::srt_url request "$2")" \
    -f null - >/dev/null 2>&1 || status=$?
  (( status != 0 )) || got=ok
  if [[ "${got}" != "$1" ]]; then
    harness::add_reason "$3: 읽기 ${got}(기대 $1)"
    return 1
  fi
  # 실패를 기대한 읽기 — 시간 초과 · 명령 실행 실패는 잠금이 막은 것이 아니다.
  if [[ "$1" == fail ]]; then
    ia::rejection_code "${status}" "$3"
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
  ia::expect_stub_rejected a-c10 "${IA_PASSPHRASE}" '같은 경로 둘째 송출' \
    || ok=1
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
  ia::expect_stub_rejected a-c2 "${IA_WRONG_PASSPHRASE}" '틀린 passphrase'
}

check_c3() {
  ia::require_layer A || return 1
  ia::expect_stub_rejected a-c3 '' 'passphrase 없는 송출'
}

check_c7() {
  local ok=0
  ia::require_layer A || return 1
  ia::stub_mode allow-empty || return 1
  ia::expect_stub_rejected a-c7p "${IA_PASSPHRASE}" '빈 허용 + passphrase' \
    || ok=1
  ia::expect_stub_rejected a-c7n '' '빈 허용 + passphrase 없음' || ok=1
  ia::stub_mode allow || ok=1
  return "${ok}"
}

#######################################
# C6 — 응답기 정지 중 송출 거부. 자극(가짜 응답기가 정말 멈춤)을 확인하고,
# 다시 띄운 뒤 같은 송출이 받아들여지는지 본다(양성 대조 — 층 A 의 거부 사례가
# 스택 고장이 아니라 각 자극 때문임을 끝에서 한 번 보인다).
#######################################
check_c6() {
  local ok=0
  ia::require_layer A || return 1
  if ! ia::compose stop authstub >/dev/null 2>&1 \
    || [[ -n "$(ia::compose ps -q authstub 2>/dev/null)" ]]; then
    harness::add_reason '가짜 응답기 정지 실패(자극 미도달)'
    return 1
  fi
  ia::expect_rejected a-c6 "${IA_PASSPHRASE}" '응답기 정지 중 송출' || ok=1
  if ! ia::compose start authstub >/dev/null 2>&1 \
    || ! ia::wait_stub_admin; then
    harness::add_reason '가짜 응답기 다시 시작 실패'
    return 1
  fi
  ia::expect_accepted a-c6ok "${IA_PASSPHRASE}" '다시 띄운 뒤 송출(양성 대조)' \
    || ok=1
  ia::sender_stop a-c6ok
  return "${ok}"
}

# 가짜 응답기 관리 포트가 답할 때까지 기다린다(상한 IA_REJECT_TIMEOUT).
ia::wait_stub_admin() {
  local deadline=$((SECONDS + IA_REJECT_TIMEOUT))
  while (( SECONDS < deadline )); do
    ia::stub_admin /healthz >/dev/null && return 0
    sleep 1
  done
  return 1
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
# 운영 스크립트 사례(C13 · C15 · C18)의 이음매를 정한다. 본 파일이 층 B 를
# 띄우기 전에 부른다.
#   - 스크립트는 media-host 디렉터리의 bin/ 에서 돌린다(compose 프로젝트 ·
#     파일은 ia::configure 의 것 — env 파일은 그 compose 파일 옆
#     ingest-auth.env 여야 응답기가 읽는다).
#   - 거부 목록 디렉터리는 응답기 /etc/ingest-auth 에 묶인 호스트 디렉터리다.
#   - PATH 앞에 둘 이음매: aws(test/ops_fakebin/aws — 거부 목록 SSM 값은
#     <작업 디렉터리>/state/ssm.param.<이름>) · chown(기록만 — root 아님) ·
#     journalctl(컨테이너 ID 로 거른 docker logs).
# Globals:
#   ia_ops_media_dir, ia_ops_work, ia_ops_deny_dir
# Arguments:
#   media-host 디렉터리, 작업 디렉터리(비어 있는 새 디렉터리), 거부 목록
#   디렉터리.
#######################################
ia::configure_ops() {
  local fake="$1/test/ops_fakebin"
  ia_ops_media_dir="$1"
  ia_ops_work="$2"
  ia_ops_deny_dir="$3"
  mkdir -p -- "$2/bin" "$2/state" "$2/log"
  ln -sf -- "${fake}/aws" "$2/bin/aws"
  ln -sf -- "${fake}/chown" "$2/bin/chown"
  ln -sf -- "${fake}/journalctl_docker" "$2/bin/journalctl"
}

#######################################
# 운영 스크립트 사례의 선행 조건 — 층 B · 이음매(ia::configure_ops) · 스크립트
# 실행 파일 · env 파일 자리. 옛 호출 꼴(첫 인자가 디렉터리)은 실패로 남긴다 —
# 본 파일이 check_c13 · c15 · c18 을 따로 정의해 이 lib 의 본문을 가리면
# 본문 없이 통과하지 않게.
# Arguments:
#   스크립트 상대 경로 ….
#######################################
require_ops_scripts() {
  local rel missing=""
  ia::require_layer B || return 1
  if [[ "${1:-}" == /* ]]; then
    harness::add_reason "옛 호출 꼴 — ingest_auth_contract.sh 의 check_c13 · \
c15 · c18 정의를 지워야 lib 본문이 돈다"
    return 1
  fi
  if [[ -z "${ia_ops_work}" || ! -d "${ia_ops_deny_dir}" ]]; then
    harness::add_reason '운영 스크립트 이음매 없음(ia::configure_ops 미호출)'
    return 1
  fi
  for rel in "$@"; do
    [[ -x "${ia_ops_media_dir}/${rel}" ]] \
      || missing="${missing:+${missing} · }${rel}"
  done
  if [[ -n "${missing}" ]]; then
    harness::add_reason "없음 — ${missing}"
    return 1
  fi
  if [[ ! -f "$(dirname -- "${ia_compose_file}")/ingest-auth.env" ]]; then
    harness::add_reason 'compose 파일 옆 ingest-auth.env 없음(응답기 env_file 자리)'
    return 1
  fi
}

#######################################
# 운영 스크립트를 시험 이음매로 돌리고 판정 줄을 낸다.
# Globals:
#   ia_ops_media_dir, ia_ops_work, ia_ops_deny_dir, ia_project, ia_compose_file
# Arguments:
#   스크립트 상대 경로, 인자 ….
# Outputs:
#   표준 출력(판정 줄).
#######################################
ia::run_ops() {
  local script="${ia_ops_media_dir}/$1"
  shift
  PATH="${ia_ops_work}/bin:${PATH}" \
    PCBENCH_FAKE_LIB="${ia_ops_media_dir}/test/ops_fakebin/record.sh" \
    PCBENCH_FAKE_LOG_DIR="${ia_ops_work}/log" \
    PCBENCH_FAKE_STATE_DIR="${ia_ops_work}/state" \
    MEDIA_COMPOSE_PROJECT="${ia_project}" \
    MEDIA_COMPOSE_FILE="${ia_compose_file}" \
    INGEST_AUTH_DENY_DIR="${ia_ops_deny_dir}" \
    INGEST_AUTH_DENYLIST_PARAM="${IA_DENY_PARAM}" INGEST_AUTH_GID=10003 \
    harness::bounded "${IA_OPS_TIMEOUT}" "${script}" "$@" \
    2>"${ia_ops_work}/ops.err" || true
}

#######################################
# 운영 스크립트의 판정 줄이 기대와 같은지 본다.
# Arguments:
#   기대 줄, 스크립트 상대 경로, 인자 ….
#######################################
ia::expect_ops() {
  local want="$1" got
  shift
  got="$(ia::run_ops "$@")"
  if [[ "${got}" != "${want}" ]]; then
    harness::add_reason "$* → 「${got}」(기대 「${want}」): $(harness::log_tail \
"${ia_ops_work}/ops.err")"
    return 1
  fi
}

# 가짜 SSM 거부 목록 값(머리 줄 + 토큰 줄 …)을 둔다.
ia::ssm_deny() {
  printf '%s\n' '# pokeclip deny-tokens v1' "$@" \
    >"${ia_ops_work}/state/ssm.param.${IA_DENY_PARAM}"
}

# 서비스 컨테이너 ID(실행 중 하나).
ia::service_id() { ia::compose ps -q "$1" 2>/dev/null; }

# 컨테이너 시작 시각.
ia::started_at() {
  docker inspect -f '{{.State.StartedAt}}' "$1" 2>/dev/null
}

# 시험 경로의 받은 바이트 수(관리 API — 못 읽으면 빈 값).
ia::bytes_received() {
  ia::api_get "/v3/paths/get/${IA_STREAM_TOKEN}" \
    | jq -r '.bytesReceived // empty' 2>/dev/null
}

#######################################
# 응답기 상태 디렉터리의 connlog.jsonl 줄 수(보조 컨테이너로 읽는다 — 응답기
# 이미지에는 셸이 없다).
# Outputs:
#   줄 수(못 읽으면 실패).
#######################################
ia::connlog_lines() {
  local id
  id="$(ia::service_id ingest-auth)" || return 1
  [[ -n "${id}" ]] || return 1
  docker run --rm --label "${IA_BENCH_LABEL}" --volumes-from "${id}:ro" \
    --entrypoint /bin/sh "${PCBENCH_HELPER_IMAGE:-alpine:3.22}" -c \
    'wc -l </var/lib/ingest-auth/connlog.jsonl' 2>/dev/null | tr -d ' '
}

#######################################
# 응답기 인증 수신 자리(망 칸 127.0.0.1:8080)를 찔러 코드와 시각을 파일에
# 남긴다 — 멈춤 표식이 생길 때까지(배경 실행). 탐침 한 번이 보조 컨테이너
# (netprobe) 하나를 띄우므로 한 번에 수 초가 걸리고, 그 사이 1초를 쉰다 — 1초
# 미만의 창은 놓칠 수 있다(기록 전용).
# Arguments:
#   기록 파일, 멈춤 표식.
#######################################
ia::probe_window() {
  local code
  while [[ ! -e "$2" ]]; do
    code="$(ia::http_code -X POST http://127.0.0.1:8080/mediamtx/auth)"
    printf '%s %s\n' "${SECONDS}" "${code}" >>"$1"
    sleep 1
  done
}

#######################################
# C13 — 송출 중 IP 한도 끄기. 기존 송출은 끊기지 않고(받은 바이트 증가 · 송출기
# 생존), mediamtx 는 그대로(ID · 시작 시각), 응답기만 바뀌고, connlog 가
# 남고, 재생성 창(인증 자리 000)을 기록하고, 끝난 뒤 새 송출이 받아들여진다.
# 계획 7-1 편차: 창의 길이 · 창 동안의 거부는 판정하지 않고 기록만 한다(탐침
# 간격이 수 초라 창을 놓칠 수 있다 — ia::probe_window). 송출 연속성은 스크립트
# 전후의 받은 바이트 증가와 송출기 생존으로만 본다 — 창 동안 잠시 멎었다
# 이어진 송출은 가르지 못한다.
#######################################
check_c13() {
  local ok=0 mtx_id mtx_at ia_id bytes0 bytes1 log0 log1 probe stop pid
  require_ops_scripts "${IA_IP_LIMIT_SCRIPT}" || return 1
  if ! ia::running b-c9 || ! ia::path_ready; then
    harness::add_reason '기존 송출(b-c9)이 없음(C9 선행)'
    return 1
  fi
  mtx_id="$(ia::service_id mediamtx)"
  mtx_at="$(ia::started_at "${mtx_id}")"
  ia_id="$(ia::service_id ingest-auth)"
  bytes0="$(ia::bytes_received)"
  log0="$(ia::connlog_lines)" || log0=""
  probe="${ia_ops_work}/c13-window.txt"
  stop="${ia_ops_work}/c13-stop"
  ia::probe_window "${probe}" "${stop}" &
  pid=$!
  ia::expect_ops 'INGEST AUTH IP LIMIT OK off' "${IA_IP_LIMIT_SCRIPT}" off \
    || ok=1
  : >"${stop}"
  wait "${pid}" || true
  printf '기록 C13 창 — 인증 자리 000 %s회(탐침 %s회 · 한 번에 수 초)\n' \
    "$(grep -c ' 000$' "${probe}" || true)" \
    "$(awk 'END { print NR }' "${probe}")"
  if [[ "$(ia::http_code -X POST http://127.0.0.1:8080/mediamtx/auth)" == 000 ]]
  then
    harness::add_reason '스크립트 성공 뒤에도 인증 자리 접속 실패'
    ok=1
  fi
  if [[ -z "${mtx_id}" || "$(ia::service_id mediamtx)" != "${mtx_id}" \
    || "$(ia::started_at "${mtx_id}")" != "${mtx_at}" ]]; then
    harness::add_reason 'mediamtx 컨테이너 ID · 시작 시각이 바뀜'
    ok=1
  fi
  if [[ "$(ia::service_id ingest-auth)" == "${ia_id}" ]]; then
    harness::add_reason '응답기 컨테이너 ID 가 그대로(재생성 안 됨)'
    ok=1
  fi
  sleep 2
  bytes1="$(ia::bytes_received)"
  if ! ia::running b-c9 || [[ -z "${bytes0}" || -z "${bytes1}" ]] \
    || (( bytes1 <= bytes0 )); then
    harness::add_reason "기존 송출이 끊김(바이트 ${bytes0:-?} → ${bytes1:-?})"
    ok=1
  fi
  log1="$(ia::connlog_lines)" || log1=""
  if [[ -z "${log0}" || -z "${log1}" ]] || (( log1 < log0 )); then
    harness::add_reason "connlog.jsonl 줄 ${log0:-?} → ${log1:-?}(유지 아님)"
    ok=1
  fi
  ia::sender_stop b-c9
  ia::expect_accepted b-c13 "${IA_PASSPHRASE}" '재생성 뒤 새 송출' || ok=1
  return "${ok}"
}

#######################################
# C15 — 거부 목록 덧붙임 → deny-sync → 재시작 없이 거부 → 목록에서 빼도
# (재생성 없이는) 거부 유지 → --release 뒤 풀림.
#######################################
check_c15() {
  local ok=0 ia_id
  require_ops_scripts "${IA_DENY_SYNC_SCRIPT}" || return 1
  ia::sender_stop b-c13
  ia_id="$(ia::service_id ingest-auth)"
  ia::ssm_deny "${IA_STREAM_TOKEN}"
  ia::expect_ops 'INGEST AUTH DENY SYNC OK tokens=1' "${IA_DENY_SYNC_SCRIPT}" \
    || ok=1
  if [[ -z "${ia_id}" || "$(ia::service_id ingest-auth)" != "${ia_id}" ]]; then
    harness::add_reason 'deny-sync(--release 없음)가 응답기를 바꿈'
    ok=1
  fi
  ia::expect_rejected b-c15a "${IA_PASSPHRASE}" '덧붙인 토큰 송출' || ok=1
  ia::ssm_deny
  ia::expect_ops 'INGEST AUTH DENY SYNC OK tokens=0' "${IA_DENY_SYNC_SCRIPT}" \
    || ok=1
  ia::expect_rejected b-c15b "${IA_PASSPHRASE}" '목록에서 뺀 뒤(재생성 전)' \
    || ok=1
  ia::expect_ops 'INGEST AUTH DENY SYNC OK release tokens=0' \
    "${IA_DENY_SYNC_SCRIPT}" --release || ok=1
  ia::expect_accepted b-c15c "${IA_PASSPHRASE}" '--release 뒤 송출' || ok=1
  return "${ok}"
}

#######################################
# 시험 경로의 상태를 낸다 — 관리 API 조회가 성공했을 때만 ready · idle(404 ·
# ready=false)을 가르고, 그 밖(접속 실패 · 다른 코드 · JSON 아님)은 error 다.
# Outputs:
#   ready | idle | error.
#######################################
ia::path_state() {
  local out code body
  out="$(ia::probe_curl -u "${IA_API_USER}:${IA_API_PASS}" -w '\n%{http_code}' \
    "http://127.0.0.1:9997/v3/paths/get/${IA_STREAM_TOKEN}")" || out=""
  code="${out##*$'\n'}"
  body="${out%$'\n'*}"
  case "${code}:$(jq -r '.ready' <<<"${body}" 2>/dev/null)" in
    200:true) printf 'ready' ;;
    200:false | 404:*) printf 'idle' ;;
    *) printf 'error' ;;
  esac
}

#######################################
# C18 — 허용된 송출 중 토큰을 덧붙여 deny-sync 한 뒤 실물 cut: 종료 0 ·
# ingestauth_cut_completed · kicked=1 · 성공한 조회로 그 연결이 사라짐을
# 확인(조회 실패는 「사라짐」 이 아니다) · 같은 토큰 재송출 거부.
#######################################
check_c18() {
  local ok=0 out status=0 deadline state
  require_ops_scripts "${IA_DENY_SYNC_SCRIPT}" || return 1
  if ! ia::running b-c15c || ! ia::path_ready; then
    harness::add_reason '허용된 송출(b-c15c)이 없음(C15 선행)'
    return 1
  fi
  ia::ssm_deny "${IA_STREAM_TOKEN}"
  ia::expect_ops 'INGEST AUTH DENY SYNC OK tokens=1' "${IA_DENY_SYNC_SCRIPT}" \
    || ok=1
  out="$(ia::compose exec -T ingest-auth /ingest-auth cut \
    "${IA_STREAM_TOKEN}" 2>&1)" || status=$?
  if (( status != 0 )) \
    || ! grep -q -F '"msg":"ingestauth_cut_completed"' <<<"${out}" \
    || ! grep -q -E '"kicked":1[,}]' <<<"${out}"; then
    harness::add_reason "cut 종료 ${status} · completed · kicked=1 아님: \
${out:0:200}"
    ok=1
  fi
  deadline=$((SECONDS + IA_REJECT_TIMEOUT))
  state="$(ia::path_state)"
  while (( SECONDS < deadline )) && [[ "${state}" != idle ]]; do
    sleep 0.5
    state="$(ia::path_state)"
  done
  if [[ "${state}" != idle ]]; then
    harness::add_reason "cut 뒤 경로 상태 ${state}(기대 idle — error 는 조회 실패)"
    ok=1
  fi
  ia::sender_stop b-c15c
  ia::expect_rejected b-c18 "${IA_PASSPHRASE}" 'cut 뒤 같은 토큰 재송출' || ok=1
  return "${ok}"
}

# 사용자 · 비밀번호의 Basic 자격 표현(base64 — 줄바꿈 없이).
ia::basic_b64() { printf '%s:%s' "$1" "$2" | base64 | tr -d '\n'; }

#######################################
# 비밀 여섯 원문과 Basic 자격 표현 둘(호출자 · 사이드카 API)을 「이름 값」 한
# 줄씩 낸다 — C11 이 찾고 층 로그 저장이 가리는 목록(보고에는 이름만 싣는다).
#######################################
ia::secret_forms() {
  printf '%s %s\n' cdn "${IA_CDN_SECRET}" api "${IA_API_PASS}" \
    basic "${IA_BASIC_PASS}" publish-lock "${IA_PUBLISH_LOCK}" \
    read-lock "${IA_READ_LOCK}" internal-token "${IA_INTERNAL_TOKEN}" \
    basic-b64 "$(ia::basic_b64 "${IA_BASIC_USER}" "${IA_BASIC_PASS}")" \
    api-b64 "$(ia::basic_b64 "${IA_API_USER}" "${IA_API_PASS}")"
}

#######################################
# C11 — 응답기 inspect 와 mediamtx · 응답기 로그에 비밀 여섯 · Basic 표현 둘 ·
# passphrase 가 없다. 로그가 비었으면(읽은 것이 없으면) 통과로 치지 않는다 —
# 두 서비스 모두 줄이 있고 응답기 로그에 started 줄이 있어야 한다.
#######################################
check_c11() {
  local ok=0 id inspect logs name secret mtx_lines
  ia::require_layer B || return 1
  id="$(ia::compose ps -q ingest-auth 2>/dev/null)" || id=""
  if [[ -z "${id}" ]] || ! inspect="$(docker inspect "${id}" 2>/dev/null)"; then
    harness::add_reason '응답기 inspect 실패'
    return 1
  fi
  if ! logs="$(ia::logs mediamtx ingest-auth)" \
    || ! mtx_lines="$(harness::count_output_lines ia::logs mediamtx)"; then
    harness::add_reason '로그를 읽지 못함'
    return 1
  fi
  if (( mtx_lines == 0 )) \
    || [[ "${logs}" != *'"msg":"ingestauth_started"'* ]]; then
    harness::add_reason "로그가 비었거나 응답기 started 줄이 없음(mediamtx \
${mtx_lines}줄)"
    return 1
  fi
  while read -r name secret; do
    if [[ "${inspect}" == *"${secret}"* ]]; then
      harness::add_reason "inspect 에 비밀(${name})"
      ok=1
    fi
    if [[ "${logs}" == *"${secret}"* ]]; then
      harness::add_reason "로그에 비밀(${name})"
      ok=1
    fi
  done < <(ia::secret_forms)
  if [[ "${logs}" == *"${IA_PASSPHRASE}"* ]]; then
    harness::add_reason '로그에 passphrase'
    ok=1
  fi
  return "${ok}"
}
