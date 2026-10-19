# shellcheck shell=bash
# 결과 변수(mtx_paths_*)는 부르는 스크립트가 읽고, ops_scratch 는 ops.sh 가
# 정의한다.
# shellcheck disable=SC2034,SC2154
#
# MediaMTX 경로 목록(GET /v3/paths/list) 조회 — paths-count · paths-list 가
# 함께 쓴다. ops.sh 를 먼저 source 해야 한다.
#
# 조회 경로: 9997 은 MediaMTX 네트워크 네임스페이스의 127.0.0.1 에만 묶이고
# MediaMTX 이미지(scratch)에는 셸이 없다. 그래서 같은 네임스페이스의 사이드카
# 컨테이너 안에서 고정 sh -c 스크립트(MTX_PATHS_EXEC_SCRIPT)를 돌린다(kty 86차
# R1).
#   - 자격은 사이드카 자신의 환경(MTX_API_USER · MTX_API_PASS_FILE — 사이드카와
#     같은 계약)에서 읽는다. 호스트 쪽 스크립트는 자격을 만지지 않는다.
#   - 비밀번호는 셸 내장 read 로 파일에서 읽고, 내장 printf → base64(표준
#     입력)로 Basic 값을 만들어 HTTP/1.0 요청을 BusyBox nc 의 표준 입력으로
#     보낸다. 비밀번호 · Basic 값은 어떤 프로세스의 argv · 환경에도 오르지 않는다
#     — BusyBox wget 은 자격을 argv(--header · URL)로만 받아 쓰지 않는다.
#   - 자격이 하나라도 비거나 비밀번호 파일을 못 읽으면 요청하지 않고 종료 3
#     (kty 86차 R2 — 무인증 갈래 없음).
#
# 응답 판정(호스트): 상태 줄 · 머리글 끝 · Content-Length(있으면 본문 바이트 수와
# 같아야 함)를 본 뒤 본문 JSON 을 awk 로 끝까지 훑는다(상자에 jq 가 있다는 보장이
# 없다). 문법이 어긋나거나 쓰는 필드의 꼴이 다르면 실패다 — 조회 실패를 0 건으로
# 바꾸지 않는다. 송출 중 판정은 사이드카 폴러와 같다(online 이 true 이고 source
# 가 null 이 아님 — media/internal/mtxstate/poller.go publishing()).

# 사이드카 컨테이너 안에서 돈다(BusyBox ash — POSIX sh). 바깥 작은따옴표를
# 지키려고 안쪽 printf 형식은 큰따옴표로 둔다.
# shellcheck disable=SC2016 # 컨테이너 안에서 펼친다.
readonly MTX_PATHS_EXEC_SCRIPT='set -eu
u="${MTX_API_USER:-}"
f="${MTX_API_PASS_FILE:-}"
if [ -z "$u" ] || [ -z "$f" ]; then exit 3; fi
p=""
IFS= read -r p <"$f" || [ -n "$p" ] || exit 3
if [ -z "$p" ]; then exit 3; fi
a="$(printf "%s:%s" "$u" "$p" | base64 | tr -d "\n")"
p=""
{
  printf "GET /v3/paths/list?itemsPerPage=1000 HTTP/1.0\r\n"
  printf "Host: 127.0.0.1\r\nAuthorization: Basic %s\r\n\r\n" "$a"
} | nc -w 5 127.0.0.1 9997'
# 컨테이너 안 스크립트가 「자격 없음」 으로 끝날 때의 종료 코드.
readonly MTX_PATHS_NO_CREDENTIALS=3

# 본문 JSON 을 훑어 「ITEM <송출 중 1|0> <이름>」 줄과 끝에 「PAGECOUNT <n>」 을
# 낸다. 어긋나면 종료 1. 최상위 객체에는 pageCount(음 아닌 정수) · items(객체
# 배열)가 한 번씩 있어야 하고, 항목마다 name(이스케이프 없는 문자열 — 경로 이름
# 글자만)이 있어야 한다. online 은 true · false · null, source 는 아무 값(null
# 이 아니면 있음).
# shellcheck disable=SC2016 # awk 프로그램이다.
readonly MTX_PATHS_SCAN_AWK='
function fail() { exit 1 }
function ws(   c) {
  while (pos <= len) {
    c = substr(s, pos, 1)
    if (c != " " && c != "\t" && c != "\n" && c != "\r") return
    pos++
  }
}
function str(   out, c, e) {
  str_esc = 0
  out = ""
  pos++
  while (pos <= len) {
    c = substr(s, pos, 1)
    if (c == "\"") { pos++; return out }
    if (c == "\\") {
      e = substr(s, pos + 1, 1)
      if (e == "") fail()
      if (e == "u") {
        if (substr(s, pos + 2, 4) !~ hex4) fail()
        pos += 6
      } else if (index("\"\\/bfnrt", e) > 0) {
        pos += 2
      } else {
        fail()
      }
      str_esc = 1
      out = out "?"
      continue
    }
    if (index(ctrl, c) > 0) fail()
    out = out c
    pos++
  }
  fail()
}
function num(   rest) {
  rest = substr(s, pos, 64)
  if (!match(rest, numre)) fail()
  numtxt = substr(rest, 1, RLENGTH)
  pos += RLENGTH
}
function value(ctx,   c) {
  ws()
  if (pos > len) fail()
  c = substr(s, pos, 1)
  if (c == "{") return object(ctx)
  if (c == "[") return array(ctx)
  if (c == "\"") { strval = str(); return "s" }
  if (substr(s, pos, 4) == "true") { pos += 4; return "t" }
  if (substr(s, pos, 5) == "false") { pos += 5; return "f" }
  if (substr(s, pos, 4) == "null") { pos += 4; return "n" }
  if (c == "-" || (c >= "0" && c <= "9")) { num(); return "d" }
  fail()
}
function array(ctx,   c) {
  pos++
  ws()
  if (substr(s, pos, 1) == "]") { pos++; return "a" }
  while (1) {
    if (ctx == "items") {
      ws()
      if (substr(s, pos, 1) != "{") fail()
      value("item")
    } else {
      value("other")
    }
    ws()
    c = substr(s, pos, 1)
    if (c == ",") { pos++; continue }
    if (c == "]") { pos++; return "a" }
    fail()
  }
}
function object(ctx,   c, key, t, keys, got_pc, got_items, got_name, name,
    online, src) {
  pos++
  keys = SUBSEP
  ws()
  if (substr(s, pos, 1) == "}") {
    pos++
  } else {
    while (1) {
      ws()
      if (substr(s, pos, 1) != "\"") fail()
      key = str()
      if (str_esc) key = ""
      if (ctx == "top" || ctx == "item") {
        if (index(keys, SUBSEP key SUBSEP) > 0) fail()
        keys = keys key SUBSEP
      }
      ws()
      if (substr(s, pos, 1) != ":") fail()
      pos++
      if (ctx == "top" && key == "pageCount") {
        t = value("other")
        if (t != "d" || numtxt !~ intre) fail()
        pc = numtxt + 0
        got_pc = 1
      } else if (ctx == "top" && key == "items") {
        if (value("items") != "a") fail()
        got_items = 1
      } else if (ctx == "item" && key == "name") {
        if (value("other") != "s" || str_esc || strval !~ namere) fail()
        name = strval
        got_name = 1
      } else if (ctx == "item" && key == "online") {
        t = value("other")
        if (t == "t") online = 1
        else if (t == "f" || t == "n") online = 0
        else fail()
      } else if (ctx == "item" && key == "source") {
        src = (value("other") != "n")
      } else {
        value("other")
      }
      ws()
      c = substr(s, pos, 1)
      if (c == ",") { pos++; continue }
      if (c == "}") { pos++; break }
      fail()
    }
  }
  if (ctx == "top" && (!got_pc || !got_items)) fail()
  if (ctx == "item") {
    if (!got_name) fail()
    print "ITEM " ((online && src) ? 1 : 0) " " name
  }
  return "o"
}
BEGIN {
  for (i = 1; i < 32; i++) ctrl = ctrl sprintf("%c", i)
  hex4 = "^[0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]$"
  numre = "^-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][-+]?[0-9]+)?"
  intre = "^(0|[1-9][0-9]*)$"
  namere = "^[-0-9A-Za-z_./~:]+$"
}
{ s = s $0 "\n" }
END {
  len = length(s)
  pos = 1
  if (value("top") != "o") fail()
  ws()
  if (pos <= len) fail()
  print "PAGECOUNT " pc
}'

# 조회 결과(mtx_paths::query 가 채운다).
mtx_paths_step=""
mtx_paths_page_count=0
mtx_paths_item_count=0
mtx_paths_publishing=""

#######################################
# HTTP 응답 원문을 상태 · 본문으로 가른다. 본문은 파일로 쓴다.
# Arguments:
#   응답 원문 파일, 본문을 쓸 파일.
# Outputs:
#   상태 코드(세 자리).
# Returns:
#   0 가름 / 1 HTTP 틀이 아님 · 본문 길이가 Content-Length 와 다름.
#######################################
mtx_paths::split_http() {
  local raw="$1" body="$2" meta status_line length header_bytes total code
  # 머리글 끝(빈 줄)까지의 바이트 수 · 상태 줄 · Content-Length 를 낸다.
  meta="$(LC_ALL=C awk '
    { line = $0; sub(/\r$/, "", line); bytes += length($0) + 1 }
    NR == 1 { status = line }
    NR > 1 && tolower(substr(line, 1, 15)) == "content-length:" {
      cl = substr(line, 16)
      gsub(/[ \t]/, "", cl)
    }
    line == "" { printf "%d\t%s\t%s\n", bytes, cl, status; found = 1; exit }
    END { if (!found) exit 1 }' "${raw}")" || return 1
  header_bytes="${meta%%$'\t'*}"
  meta="${meta#*$'\t'}"
  length="${meta%%$'\t'*}"
  status_line="${meta#*$'\t'}"
  if [[ ! "${status_line}" =~ ^HTTP/1\.[01]\ ([0-9][0-9][0-9])(\ |$) ]]; then
    return 1
  fi
  code="${BASH_REMATCH[1]}"
  total="$(wc -c <"${raw}")" || return 1
  total=$((total + 0))
  (( total >= header_bytes )) || return 1
  if [[ -n "${length}" ]]; then
    [[ "${length}" =~ ^[0-9]+$ ]] || return 1
    (( total - header_bytes == 10#${length} )) || return 1
  fi
  tail -c "+$((header_bytes + 1))" "${raw}" >"${body}" || return 1
  printf '%s\n' "${code}"
}

#######################################
# 경로 목록을 받아 훑는다. 실패하면 단계 이름을 mtx_paths_step 에 둔다.
# 스크래치(ops::make_scratch)가 있어야 한다.
# Globals:
#   ops_scratch, mtx_paths_step, mtx_paths_page_count, mtx_paths_item_count,
#   mtx_paths_publishing
# Returns:
#   0 받음 / 1 실패.
#######################################
mtx_paths::query() {
  local limit id raw="${ops_scratch}/response" body="${ops_scratch}/body"
  local status=0 code scan
  if ! limit="$(ops::timeout)"; then
    mtx_paths_step=config
    return 1
  fi
  if ! id="$(ops::one_container sidecar "${limit}")"; then
    mtx_paths_step=sidecar
    return 1
  fi
  ops::bounded "${limit}" docker exec "${id}" sh -c "${MTX_PATHS_EXEC_SCRIPT}" \
    </dev/null >"${raw}" 2>/dev/null || status=$?
  case "${status}" in
    0) ;;
    124) mtx_paths_step=timeout; return 1 ;;
    "${MTX_PATHS_NO_CREDENTIALS}") mtx_paths_step=credentials; return 1 ;;
    *) mtx_paths_step=request; return 1 ;;
  esac
  if ! code="$(mtx_paths::split_http "${raw}" "${body}")"; then
    mtx_paths_step=response
    return 1
  fi
  case "${code}" in
    200) ;;
    401) mtx_paths_step=auth; return 1 ;;
    *) mtx_paths_step=http; return 1 ;;
  esac
  if ! scan="$(LC_ALL=C awk "${MTX_PATHS_SCAN_AWK}" "${body}")"; then
    mtx_paths_step=response
    return 1
  fi
  mtx_paths_page_count="${scan##*PAGECOUNT }"
  mtx_paths_item_count="$(printf '%s\n' "${scan}" | grep -c '^ITEM ' || true)"
  mtx_paths_publishing="$(printf '%s\n' "${scan}" \
    | awk '$1 == "ITEM" && $2 == "1" { print $3 }')"
}
