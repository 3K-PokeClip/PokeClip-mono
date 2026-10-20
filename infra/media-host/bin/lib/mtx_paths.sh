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
# 응답 판정(호스트): 응답 크기 상한(8 MiB) · 상태 줄 · 머리글 끝 ·
# Content-Length(있으면 본문 바이트 수와 같아야 함)를 본 뒤 본문 JSON 을 awk 로
# 끝까지 훑는다(상자에 jq 가 있다는 보장이 없다 — 훑기도 상한 시간 안에서).
# 문법이 어긋나거나 쓰는 필드의 꼴이 다르면 실패다 — 조회 실패를 0 건으로
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
# 응답 원문 크기 상한(바이트) — 넘으면 훑지 않고 response 로 실패한다.
readonly MTX_PATHS_MAX_RESPONSE_BYTES=8388608

# 본문 JSON 을 훑어 「ITEM <송출 중 1|0> <이름>」 줄과 끝에 「PAGECOUNT <n>」 을
# 낸다. 어긋나면 종료 1. 최상위 객체에는 pageCount(음 아닌 정수) · items(객체
# 배열)가 한 번씩 있어야 하고, 항목마다 name(이스케이프 없는 문자열 — 경로 이름
# 글자만)이 있어야 한다. online 은 true · false · null, source 는 아무 값(null
# 이 아니면 있음).
#
# 속도: 레코드 구분자를 큰따옴표로 두어 문자열 안쪽은 정규식으로 한 번에 보고,
# 바깥 조각(구조 문자 · 숫자 · 리터럴)만 글자 단위로 토큰을 만든다 — 한 글자씩
# 긴 입력을 substr 하는 BWK awk(macOS)의 느림을 피한다. 그다음 토큰 배열을
# 재귀 하강으로 훑는다.
# shellcheck disable=SC2016 # awk 프로그램이다.
readonly MTX_PATHS_SCAN_AWK='
function fail() { bad = 1; exit 1 }
function push(kind, text) { nt++; tk[nt] = kind; tv[nt] = text }
function lex(seg,   i, n, c, rest) {
  n = length(seg)
  i = 1
  while (i <= n) {
    c = substr(seg, i, 1)
    if (c == " " || c == "\t" || c == "\n" || c == "\r") { i++; continue }
    if (index("{}[]:,", c) > 0) { push(c, ""); i++; continue }
    rest = substr(seg, i)
    if (match(rest, numre)) { push("d", substr(rest, 1, RLENGTH)) }
    else if (substr(rest, 1, 4) == "true") { push("t", ""); RLENGTH = 4 }
    else if (substr(rest, 1, 5) == "false") { push("f", ""); RLENGTH = 5 }
    else if (substr(rest, 1, 4) == "null") { push("n", ""); RLENGTH = 4 }
    else fail()
    i += RLENGTH
    # 숫자 · 리터럴 바로 뒤에 다른 숫자 · 글자가 붙으면(1x · truex) 어긋난다.
    c = substr(seg, i, 1)
    if (c != "" && index(" \t\n\r{}[]:,", c) == 0) fail()
  }
}
function addstr(raw,   t) {
  if (raw ~ ctrlre) fail()
  t = raw
  gsub(/\\["\\\/bfnrt]/, "", t)
  gsub(/\\u[0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]/, "", t)
  if (index(t, "\\") > 0) fail()
  push((index(raw, "\\") > 0) ? "e" : "s", raw)
}
function value(ctx,   k) {
  if (pos > nt) fail()
  k = tk[pos]
  if (k == "{") return object(ctx)
  if (k == "[") return array(ctx)
  if (k == "s" || k == "e" || k == "d" || k == "t" || k == "f" || k == "n") {
    pos++
    return k
  }
  fail()
}
function array(ctx,   k) {
  pos++
  if (tk[pos] == "]") { pos++; return "a" }
  while (1) {
    if (ctx == "items") {
      if (tk[pos] != "{") fail()
      value("item")
    } else {
      value("other")
    }
    k = tk[pos]
    if (k == ",") { pos++; continue }
    if (k == "]") { pos++; return "a" }
    fail()
  }
}
function object(ctx,   k, key, t, keys, got_pc, got_items, got_name, name,
    online, src) {
  pos++
  keys = SUBSEP
  if (tk[pos] == "}") {
    pos++
  } else {
    while (1) {
      k = tk[pos]
      if (k != "s" && k != "e") fail()
      key = (k == "s") ? tv[pos] : ""
      pos++
      if (ctx == "top" || ctx == "item") {
        if (key != "" && index(keys, SUBSEP key SUBSEP) > 0) fail()
        keys = keys key SUBSEP
      }
      if (tk[pos] != ":") fail()
      pos++
      if (ctx == "top" && key == "pageCount") {
        if (tk[pos] != "d" || tv[pos] !~ intre) fail()
        pc = tv[pos] + 0
        value("other")
        got_pc = 1
      } else if (ctx == "top" && key == "items") {
        if (value("items") != "a") fail()
        got_items = 1
      } else if (ctx == "item" && key == "name") {
        if (tk[pos] != "s" || tv[pos] !~ namere) fail()
        name = tv[pos]
        value("other")
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
      k = tk[pos]
      if (k == ",") { pos++; continue }
      if (k == "}") { pos++; break }
      fail()
    }
  }
  if (ctx == "top" && (!got_pc || !got_items)) fail()
  if (ctx == "item") {
    if (!got_name) fail()
    out[++nout] = "ITEM " ((online && src) ? 1 : 0) " " name
  }
  return "o"
}
BEGIN {
  RS = "\""
  for (i = 1; i < 32; i++) ctrl = ctrl sprintf("%c", i)
  ctrlre = "[" ctrl "]"
  numre = "^-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][-+]?[0-9]+)?"
  intre = "^(0|[1-9][0-9]*)$"
  namere = "^[-0-9A-Za-z_./~:]+$"
  instr = 0
  pend = ""
  pending = 0
}
{
  if (!instr) { lex($0); instr = 1; next }
  rec = pend $0
  # 끝의 백슬래시가 홀수 개면 이 큰따옴표는 문자열 안의 것이다(\").
  nb = 0
  if (match(rec, /\\+$/)) nb = RLENGTH
  if (nb % 2 == 1) { pend = rec "\""; pending = 1; next }
  pend = ""
  pending = 0
  addstr(rec)
  instr = 0
}
END {
  if (bad) exit 1
  # 마지막 레코드는 문자열 밖이어야 한다(최상위는 객체 — 닫는 } 로 끝난다).
  if (!instr || pending) exit 1
  pos = 1
  if (value("top") != "o") exit 1
  if (bad) exit 1
  if (pos <= nt) exit 1
  for (i = 1; i <= nout; i++) print out[i]
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
  if (( $(wc -c <"${raw}") > MTX_PATHS_MAX_RESPONSE_BYTES )); then
    mtx_paths_step=response
    return 1
  fi
  if ! code="$(mtx_paths::split_http "${raw}" "${body}")"; then
    mtx_paths_step=response
    return 1
  fi
  case "${code}" in
    200) ;;
    401) mtx_paths_step=auth; return 1 ;;
    *) mtx_paths_step=http; return 1 ;;
  esac
  # awk 는 큰따옴표를 레코드 구분자로 써서 본문 끝의 큰따옴표 하나를 보지 못한다
  # — 마지막 공백 아닌 글자가 } 인지 여기서 본다(최상위는 객체).
  if [[ "$(tail -c 256 "${body}" | LC_ALL=C tr -d ' \t\r\n' | tail -c 1)" \
    != '}' ]]; then
    mtx_paths_step=response
    return 1
  fi
  status=0
  ops::bounded "${limit}" env LC_ALL=C awk "${MTX_PATHS_SCAN_AWK}" "${body}" \
    </dev/null >"${ops_scratch}/scan" 2>/dev/null || status=$?
  case "${status}" in
    0) ;;
    124) mtx_paths_step=timeout; return 1 ;;
    *) mtx_paths_step=response; return 1 ;;
  esac
  scan="$(cat "${ops_scratch}/scan")"
  mtx_paths_page_count="${scan##*PAGECOUNT }"
  mtx_paths_item_count="$(printf '%s\n' "${scan}" \
    | LC_ALL=C grep -a -c '^ITEM ' || true)"
  mtx_paths_publishing="$(printf '%s\n' "${scan}" \
    | awk '$1 == "ITEM" && $2 == "1" { print $3 }')"
}
