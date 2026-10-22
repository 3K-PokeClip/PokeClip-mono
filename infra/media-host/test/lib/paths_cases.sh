# shellcheck shell=bash
# 상수는 source 하는 시험이, ops_dir · ops_env · paths_script 는
# ops_fixture.sh · 부르는 시험이 정의한다.
# shellcheck disable=SC2034,SC2154
#
# paths_count_test · paths_list_test 가 함께 쓰는 사례. 두 스크립트는 같은
# 조회 경로(사이드카 안 고정 sh -c → BusyBox nc → 9997)를 쓰므로 자격 · 응답
# 오류 사례가 같다. 판정 줄을 내는 자리만 다르다 — 부르는 시험이 아래 둘을
# 정의한다.
#
#   paths_script        bin 아래 스크립트 경로(infra/media-host 기준).
#   paths_expect_fail   실패 판정을 본다. 인자 = 단계 이름(credentials ·
#                       auth · …).
#
# 이 파일은 source 해서 쓴다. bash 3.2 에서도 돈다.

readonly PATHS_REQUEST_LINE='GET /v3/paths/list?itemsPerPage=1000 HTTP/1.0'
readonly PATHS_ID_A='01JABCDEFGHJKMNPQRSTVWXYZ0'
readonly PATHS_ID_B='probe-stream-B'
# state_test.go 픽스처 꼴 — 송출 중 둘(A · source 가 빈 객체인 B), online
# false · source null · source 없음 · 리더만, 비밀처럼 보이는 필드 섞임.
readonly PATHS_MIXED_JSON='{"itemCount":6,"pageCount":1,"items":[
{"name":"01JABCDEFGHJKMNPQRSTVWXYZ0","confName":"all_others","ready":true,
 "online":true,"onlineTime":"2026-09-01T16:25:40.78854881Z",
 "source":{"type":"srtConn","id":"7b2e8365-e080-4b34-b87f-ada79ac77866"},
 "tracks":["H264","MPEG-4 Audio"],
 "tracks2":[{"codec":"H264","codecProps":{"width":320,"level":"1.3"}}],
 "readers":[{"type":"hlsMuxer","id":"r1"}],"inboundBytes":262281,
 "bytesReceived":2.5e3,"hlsCDNSecretLike":"pcbench-should-not-print"},
{"name":"pcbench-offline","online":false,"onlineTime":null,
 "source":{"type":"srtConn","id":"x"}},
{"name":"pcbench-source-null","online":true,"source":null},
{"name":"pcbench-source-missing","online":true},
{"name":"probe-stream-B","online":true,"source":{},"readers":[]},
{"name":"pcbench-reader-only","online":false,"source":null,
 "readers":[{"type":"webRTCSession","id":"y"}]}
]}'
readonly PATHS_EMPTY_JSON='{"itemCount":0,"pageCount":0,"items":[]}'
readonly PATHS_MULTI_PAGE_JSON='{"itemCount":2,"pageCount":2,"items":[
{"name":"a","online":true,"source":{}}]}'
readonly PATHS_ITEM_FORMAT='{"name":"pcbench-%03d","online":true,
"source":{"type":"srtConn"}}'
# 응답 크기 상한(8 MiB)을 넘기는 본문의 채움 바이트 수 · 큰 응답을 끝내야 하는
# 시간(초).
readonly PATHS_OVERSIZE_PAD=8800000
readonly PATHS_LARGE_SECONDS=20

#######################################
# 송출 중 항목 n 개 · pageCount 로 응답 본문을 만든다.
# Arguments:
#   항목 수, pageCount.
# Outputs:
#   JSON 한 줄.
#######################################
paths::json_items() {
  local n="$1" pages="$2" i sep=""
  printf '{"itemCount":%d,"pageCount":%d,"items":[' "${n}" "${pages}"
  for (( i = 1; i <= n; i++ )); do
    # shellcheck disable=SC2059 # 형식은 위 상수다.
    printf "${sep}${PATHS_ITEM_FORMAT}" "${i}"
    sep=','
  done
  printf ']}'
}

#######################################
# 정상 자격 사이드카와 응답을 둔 사례를 만든다.
# Arguments:
#   응답 본문.
#######################################
paths::setup() {
  ops_fixture::new_case
  ops_fixture::sidecar "${OPS_FAKE_API_USER}" normal
  ops_fixture::response "$1"
}

#######################################
# 스크립트를 돌린다.
# Arguments:
#   가짜 설정(NAME=값 …)(없어도 됨).
#######################################
paths::run() {
  ops_fixture::run "${paths_script}" "$@" --
}

#######################################
# 조회 요청이 하나이고, 요청 줄 · Basic 값이 맞으며, 비밀번호 · Basic 값이 그
# 요청(컨테이너 안 nc 의 표준 입력) 밖 어디에도 없는지 본다. docker exec argv
# 꼴도 본다.
#######################################
paths::expect_request_and_no_leak() {
  local ok=0 basic request exec_file
  basic="$(printf '%s:%s' "${OPS_FAKE_API_USER}" "${OPS_FAKE_API_PASS}" \
    | /usr/bin/base64 | tr -d '\n')"
  request="${ops_dir}/log/nc.1.request"
  if [[ "$(ops_fixture::count nc)" != 1 || ! -f "${request}" ]]; then
    harness::add_reason "nc 요청 $(ops_fixture::count nc)회(기대 1)"
    ok=1
  else
    if [[ "$(head -n 1 "${request}" | tr -d '\r')" != "${PATHS_REQUEST_LINE}" ]]
    then
      harness::add_reason "요청 줄 「$(head -n 1 "${request}" | tr -d '\r')」"
      ok=1
    fi
    if ! tr -d '\r' <"${request}" \
      | grep -q -x -F -- "Authorization: Basic ${basic}"; then
      harness::add_reason '요청에 맞는 Basic 머리글이 없음'
      ok=1
    fi
  fi
  ops_fixture::expect_no_leak "${OPS_FAKE_API_PASS}" 'API 비밀번호' || ok=1
  ops_fixture::expect_no_leak "${basic}" 'Basic 값' || ok=1
  exec_file="$(ops_fixture::calls docker | while IFS= read -r f; do
    [[ "$(head -n 1 "${f}")" == exec ]] && printf '%s\n' "${f}"; done)"
  if [[ "$(printf '%s' "${exec_file}" | awk 'END { print NR }')" != 1 ]] \
    || [[ "$(head -n 4 "${exec_file}" | paste -s -d ' ' -)" \
      != "exec ${OPS_SIDECAR_ID} sh -c" ]]; then
    harness::add_reason "docker exec 꼴이 「exec <사이드카> sh -c <스크립트>」 \
하나가 아님"
    ok=1
  fi
  return "${ok}"
}

#######################################
# 사이드카 자격이 하나라도 비면 요청 전에 credentials 로 실패한다.
#######################################
paths::case_credentials_missing() {
  local ok=0 mode user
  for mode in nouser unset empty missing; do
    ops_fixture::new_case
    user="${OPS_FAKE_API_USER}"
    case "${mode}" in
      nouser) ops_fixture::sidecar '' normal ;;
      *) ops_fixture::sidecar "${user}" "${mode}" ;;
    esac
    ops_fixture::response "${PATHS_EMPTY_JSON}"
    paths::run || return 1
    if ! paths_expect_fail credentials; then
      harness::add_reason "(${mode})"
      ok=1
    fi
    if [[ "$(ops_fixture::count nc)" != 0 ]]; then
      harness::add_reason "${mode}: 자격 없이 요청함"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 비밀번호 파일 끝 줄바꿈이 없어도 그 값으로 요청한다.
#######################################
paths::case_password_no_newline() {
  ops_fixture::new_case
  ops_fixture::sidecar "${OPS_FAKE_API_USER}" nonewline
  ops_fixture::response "${PATHS_EMPTY_JSON}"
  paths::run || return 1
  ops_fixture::expect_status 0 || return 1
  paths::expect_request_and_no_leak
}

#######################################
# 401 → auth(0 건으로 바꾸지 않음) · 그 밖의 상태 → http.
#######################################
paths::case_http_status() {
  local ok=0
  paths::setup '{"error":"authentication error"}'
  ops_fixture::response '{"error":"authentication error"}' \
    'HTTP/1.0 401 Unauthorized'
  paths::run || return 1
  paths_expect_fail auth || ok=1
  paths::setup 'oops'
  ops_fixture::response 'oops' 'HTTP/1.0 500 Internal Server Error'
  paths::run || return 1
  paths_expect_fail http || ok=1
  return "${ok}"
}

#######################################
# 깨진 응답(HTTP · JSON)은 response 로 실패한다 — 0 건 · 빈 목록이 아니다.
#######################################
paths::case_malformed() {
  local ok=0 body i=0
  # shellcheck disable=SC1003 # 아래 '\' 는 백슬래시 한 글자다.
  local bodies=(
    '{"itemCount":1,"pageCount":1,"items":[{"name":"x","online":true'
    '{"itemCount":1,"pageCount":1,"items":[
{"name":"x","online":"true","source":{}}]}'
    # 백슬래시 하나를 %s 자리에 넣는다(JSON 유니코드 이스케이프).
    "$(printf '{"itemCount":1,"pageCount":1,"items":[
{"name":"a%su0041","online":true,"source":{}}]}' '\')"
    '{"itemCount":1,"pageCount":1,"items":[
{"name":"a b","online":true,"source":{}}]}'
    '{"itemCount":1,"pageCount":1,"items":[
{"name":null,"online":true,"source":{}}]}'
    '{"itemCount":1,"pageCount":1,"items":[{"online":true,"source":{}}]}'
    '{"itemCount":0,"items":[]}'
    '{"itemCount":0,"pageCount":0}'
    '{"itemCount":0,"pageCount":0,"pageCount":0,"items":[]}'
    '{"itemCount":0,"pageCount":-1,"items":[]}'
    '{"itemCount":0,"pageCount":1.5,"items":[]}'
    '[]'
    '{"itemCount":0,"pageCount":0,"items":[]} x'
    '{"itemCount":0,"pageCount":0,"items":[1]}'
    '{"itemCount":0,"pageCount":0,"items":[],}'
    '{"itemCount":01,"pageCount":0,"items":[]}'
    '{"itemCount":0,"pageCount":0,"items":[],"x":"\q"}'
    '{"itemCount":0,"pageCount":0,"items":[],"x":"\u12\b34"}'
    '{"itemCount":0,"pageCount":0,"items":[]}"'
    '<html>404</html>'
    ''
  )
  for body in "${bodies[@]}"; do
    i=$((i + 1))
    paths::setup "${body}"
    paths::run || return 1
    if ! paths_expect_fail response; then
      harness::add_reason "(본문 ${i})"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 훑기 결과 파일(스크래치의 scan)을 못 읽으면 0 건이 아니라 response 로
# 실패한다 — 그 파일만 못 읽는 cat 을 PATH 맨 앞에 둔다.
#######################################
paths::case_scan_read_failure() {
  paths::setup "${PATHS_MIXED_JSON}"
  # shellcheck disable=SC2016 # 가짜 cat 의 본문이다 — 그 셸이 펼친다.
  printf '%s\n' '#!/bin/sh' \
    'for a in "$@"; do case "$a" in */scan) exit 1 ;; esac; done' \
    'exec /bin/cat "$@"' >"${ops_dir}/bin/cat"
  chmod +x "${ops_dir}/bin/cat"
  paths::run || return 1
  paths_expect_fail response
}

#######################################
# 깨진 HTTP 틀 — 머리글 끝 없음 · Content-Length 와 본문 길이 다름 · 상태 줄
# 아님.
#######################################
paths::case_malformed_http() {
  local ok=0 crlf=$'\r\n'
  paths::setup "${PATHS_EMPTY_JSON}"
  ops_fixture::raw_response "HTTP/1.0 200 OK${crlf}Content-Length: 40${crlf}"
  paths::run || return 1
  paths_expect_fail response || { harness::add_reason '(머리글 끝 없음)'; ok=1; }
  paths::setup "${PATHS_EMPTY_JSON}"
  ops_fixture::raw_response "HTTP/1.0 200 OK${crlf}Content-Length: 99${crlf}\
${crlf}${PATHS_EMPTY_JSON}"
  paths::run || return 1
  paths_expect_fail response || { harness::add_reason '(길이 다름)'; ok=1; }
  paths::setup "${PATHS_EMPTY_JSON}"
  ops_fixture::raw_response "garbage${crlf}${crlf}${PATHS_EMPTY_JSON}"
  paths::run || return 1
  paths_expect_fail response || { harness::add_reason '(상태 줄 아님)'; ok=1; }
  return "${ok}"
}

#######################################
# Content-Length 가 없는 응답(HTTP/1.0 연결 닫힘으로 끝)도 본문을 읽는다.
#######################################
paths::case_no_content_length() {
  paths::setup "${PATHS_EMPTY_JSON}"
  ops_fixture::response "${PATHS_EMPTY_JSON}" 'HTTP/1.0 200 OK' 0
  paths::run || return 1
  ops_fixture::expect_status 0
}

#######################################
# 사이드카 컨테이너가 0 · 2 개이거나 docker ps 가 실패하면 sidecar 로 실패하고
# exec 는 0회다.
#######################################
paths::case_sidecar_lookup() {
  local ok=0 mode
  for mode in none two exited psfail; do
    ops_fixture::new_case
    ops_fixture::response "${PATHS_EMPTY_JSON}"
    case "${mode}" in
      none) ;;
      two)
        ops_fixture::sidecar "${OPS_FAKE_API_USER}" normal
        ops_fixture::container pcbenchsidecar02 sidecar running
        ;;
      exited)
        ops_fixture::container "${OPS_SIDECAR_ID}" sidecar exited
        ;;
      psfail)
        ops_fixture::sidecar "${OPS_FAKE_API_USER}" normal
        : >"${ops_dir}/state/ps.fail"
        ;;
    esac
    paths::run || return 1
    if ! paths_expect_fail sidecar; then
      harness::add_reason "(${mode})"
      ok=1
    fi
    if [[ "$(ops_fixture::docker_count exec)" != 0 ]]; then
      harness::add_reason "${mode}: exec 를 부름"
      ok=1
    fi
  done
  return "${ok}"
}

#######################################
# 프로젝트 이름이 다르면 다른 프로젝트의 사이드카를 쓰지 않는다(기본값 =
# pokeclip-media).
#######################################
paths::case_project_default() {
  local ok=0 f
  paths::setup "${PATHS_EMPTY_JSON}"
  ops_env=()
  paths::run || return 1
  paths_expect_fail sidecar || ok=1
  f="$(ops_fixture::calls docker | head -n 1)"
  if [[ -z "${f}" ]] \
    || ! grep -q -x -F 'label=com.docker.compose.project=pokeclip-media' "${f}"
  then
    harness::add_reason 'docker ps 에 기본 프로젝트 라벨이 없음'
    ok=1
  fi
  return "${ok}"
}

#######################################
# 큰 응답 — 8 MiB 를 넘는 응답은 훑지 않고 곧 response 로 실패하고, 항목
# 1000 개(itemsPerPage 상한)는 상한 시간 안에 훑어 성공한다.
#######################################
paths::case_large_response() {
  local ok=0 start pad
  pad="$(head -c "${PATHS_OVERSIZE_PAD}" /dev/zero | tr '\0' x)"
  paths::setup \
    "{\"itemCount\":0,\"pageCount\":0,\"items\":[],\"pad\":\"${pad}\"}"
  start="${SECONDS}"
  paths::run || return 1
  paths_expect_fail response || ok=1
  if (( SECONDS - start > PATHS_LARGE_SECONDS )); then
    harness::add_reason "8 MiB 넘는 응답에 $((SECONDS - start))초 걸림"
    ok=1
  fi
  paths::setup "$(paths::json_items 1000 1)"
  start="${SECONDS}"
  paths::run || return 1
  ops_fixture::expect_status 0 || ok=1
  if (( SECONDS - start > PATHS_LARGE_SECONDS )); then
    harness::add_reason "항목 1000 개에 $((SECONDS - start))초 걸림"
    ok=1
  fi
  return "${ok}"
}

#######################################
# nc 연결 실패 → request.
#######################################
paths::case_request_failure() {
  paths::setup "${PATHS_EMPTY_JSON}"
  paths::run PCBENCH_FAKE_NC_MODE=refused || return 1
  paths_expect_fail request
}

#######################################
# docker exec 가 끝나지 않으면 상한(MEDIA_OPS_TIMEOUT) 안에 timeout 으로 끝낸다.
#######################################
paths::case_timeout() {
  local start
  paths::setup "${PATHS_EMPTY_JSON}"
  : >"${ops_dir}/state/exec.hang"
  start="${SECONDS}"
  paths::run MEDIA_OPS_TIMEOUT=2 || return 1
  paths_expect_fail timeout || return 1
  if (( SECONDS - start > 15 )); then
    harness::add_reason "상한 2초인데 $((SECONDS - start))초 걸림"
    return 1
  fi
}

#######################################
# 인자 · 상한 값이 틀리면 docker 를 부르지 않는다.
#######################################
paths::case_usage_and_config() {
  local ok=0 value
  paths::setup "${PATHS_EMPTY_JSON}"
  ops_fixture::run "${paths_script}" -- extra || return 1
  paths_expect_fail usage 2 || ok=1
  for value in 0 abc 1000 601 -5; do
    paths::setup "${PATHS_EMPTY_JSON}"
    paths::run "MEDIA_OPS_TIMEOUT=${value}" || return 1
    paths_expect_fail config || { harness::add_reason "(${value:-빈 값})"; ok=1; }
    if [[ "$(ops_fixture::count docker)" != 0 ]]; then
      harness::add_reason "상한 ${value:-빈 값}: docker 를 부름"
      ok=1
    fi
  done
  return "${ok}"
}
