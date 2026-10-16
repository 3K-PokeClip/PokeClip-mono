#!/usr/bin/env bash
# mediamtx.demo.yml.tmpl → $DEMO_HOME/mediamtx.demo.yml 렌더 (dev-media 계정별 방송 서버)
#
# 사용:  sudo env DEMO_HOME=/opt/pokeclip-demo ./infra/dev-media/render.sh
#        (템플릿이 쓰는 값은 경로 잠금값 하나다. 렌더마다 openssl rand -hex 32 로
#         새로 만든다 — 인자로 받지 않는다.
#         .env 는 source 하지 않는다 — DEMO_HOME 한 줄만 읽는다.
#         .env 는 root:root 0600 이므로 sudo 없이 실행하면 .env 읽기 검사에서 중단된다.)
#
# 이 스크립트의 가드가 설정 파일의 자동 검사다. 실패는 전부 fail-closed(산출물을 남기지 않고, 기존 정상본은 그대로).
#   1) DEMO_HOME 이 절대경로가 아니면 거부 — 상대경로는 compose 가 딴 디렉토리나 named volume 으로 해석한다
#   2) DEMO_HOME 디렉토리 부재 시 거부 — 오타 경로에 설정을 조용히 떨구지 않는다
#   3) 잠금값이 64자 소문자 hex 가 아니면 거부(openssl 부재 · 실패 포함)
#   4) 렌더 결과에 플레이스홀더(__) 잔존 시 거부
#   5) 렌더 결과의 안전 전제 단언 — authMethod: http · read 한 줄뿐인
#      authHTTPExclude · 빈 apiTrustedProxies · 정규식 경로 · 두 칸(송출 ·
#      SRT 읽기)에 같은 잠금값. 하나라도 빠지거나 바뀌면 거부
#   6) .env 의 DEMO_HOME 줄이 하나가 아니거나 실행한 DEMO_HOME 과 다르면 거부 —
#      compose 가 .env 의 값으로 붙일 자리와 렌더 자리가 갈린다
set -euo pipefail

readonly LOCK_PLACEHOLDER='__PATH_LOCK_PASSPHRASE__'
readonly REGEX_PATH_LINE='  "~^[0-9A-HJKMNP-TV-Z]{26}$":'
readonly TMPL="$(cd "$(dirname "$0")" && pwd)/mediamtx.demo.yml.tmpl"

die() {
  echo "render.sh: $*" >&2
  exit 1
}

require_absolute_home() {
  [ -n "${DEMO_HOME:-}" ] || die "DEMO_HOME 이 설정되지 않았습니다 (예: DEMO_HOME=/opt/pokeclip-demo)"
  case "$DEMO_HOME" in
  /*) ;;
  *) die "DEMO_HOME 은 절대경로여야 합니다 (받은 값: $DEMO_HOME) — 상대경로는 compose bind 가 딴 곳을 가리킵니다" ;;
  esac
  [ -d "$DEMO_HOME" ] || die "DEMO_HOME 디렉토리가 없습니다: $DEMO_HOME"
}

#######################################
# $DEMO_HOME/.env 에서 DEMO_HOME 줄만 읽어 실행한 DEMO_HOME 과 같은지 본다(가드 6).
# 파일을 source 하지 않는다 — 템플릿이 쓰는 값이 없으므로 비밀(resolve 출입증 ·
# Control API 비번)을 이 스크립트와 자식 프로세스의 환경에 들이지 않는다. 값은
# 메시지에 싣지 않는다.
# Globals:
#   DEMO_HOME
#######################################
check_env_home() {
  local envfile="$DEMO_HOME/.env" line value='' n=0
  [ -r "$envfile" ] || die "$envfile 를 읽을 수 없습니다 — 파일이 없거나(root:root 0600) sudo 없이 실행했습니다"
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" == DEMO_HOME=* ]]; then
      n=$((n + 1))
      value="${line#DEMO_HOME=}"
    fi
  done <"$envfile"
  [[ "${n}" == 1 ]] || die "$envfile 에 DEMO_HOME= 줄이 하나가 아닙니다(${n}개)"
  [[ "${value}" == "${DEMO_HOME}" ]] ||
    die "$envfile 의 DEMO_HOME 이 실행한 DEMO_HOME 과 다릅니다 — compose 가 붙일 자리와 렌더 자리가 갈립니다"
}

#######################################
# 경로 잠금값을 새로 만든다. 값은 이 스크립트 밖에 남지 않는다 — 아무도 알 필요가 없다.
# Outputs:
#   64자 소문자 hex 를 stdout 에 쓴다(호출자가 명령 치환으로 받는다).
#######################################
new_path_lock() {
  command -v openssl >/dev/null || die "openssl 이 없습니다 — 잠금값을 만들 수 없습니다"
  local lock
  lock="$(openssl rand -hex 32)" || die "openssl rand 가 실패했습니다"
  # 값은 메시지에 싣지 않는다 — 길이만.
  [[ "${lock}" =~ ^[0-9a-f]{64}$ ]] ||
    die "잠금값이 64자 소문자 hex 가 아닙니다 (길이 ${#lock})"
  printf '%s' "${lock}"
}

#######################################
# 파일에서 줄 전체가 기대값과 같은 줄의 수를 센다. 셸 비교로 세어 값이 프로세스 인자에 실리지 않는다.
# Arguments:
#   파일 경로, 기대하는 줄
# Outputs:
#   개수를 stdout 에 쓴다.
#######################################
count_line() {
  local line n=0
  while IFS= read -r line; do
    [[ "${line}" == "$2" ]] && n=$((n + 1))
  done <"$1"
  printf '%d' "${n}"
}

#######################################
# 파일에서 「키:」로 시작하는 최상위 줄의 수를 센다(값이 무엇이든).
# Arguments:
#   파일 경로, 키
# Outputs:
#   개수를 stdout 에 쓴다.
#######################################
count_key() {
  local line n=0
  while IFS= read -r line; do
    [[ "${line}" == "$2:"* ]] && n=$((n + 1))
  done <"$1"
  printf '%d' "${n}"
}

#######################################
# authHTTPExclude 블록의 항목 줄(주석 · 빈 줄 제외)을 한 줄에 하나씩 낸다. 블록은 다음 최상위 키에서 끝난다.
# Arguments:
#   파일 경로
# Outputs:
#   항목 줄을 stdout 에 쓴다.
#######################################
exclude_items() {
  local line in_block=0
  while IFS= read -r line; do
    if [[ "${line}" == 'authHTTPExclude:'* ]]; then
      in_block=1
      continue
    fi
    ((in_block)) || continue
    [[ -z "${line}" || "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" =~ ^[^[:space:]] ]] && break
    printf '%s\n' "${line}"
  done <"$1"
}

#######################################
# 렌더 결과가 계정별 방송의 안전 전제를 담는지 단언한다(가드 5). 어긋나면 die.
#  · authMethod 가 http 가 아니면 기본 내부 사용자(any publish)가 살아난다.
#  · exclude 에 read 밖의 것(api 등)이 들면 그 행위가 무인증이 된다.
#  · apiTrustedProxies 가 비지 않으면 api 요청의 출발지를 프록시 헤더로 속일 수 있다(cut 의 출발지 판정).
#  · 정규식 경로가 없으면 모든 송출이 "path not configured" 다.
#  · 잠금값 두 칸이 없으면 값 없는 2xx 의 무암호 송출 · SRT 무암호 읽기가 열린다.
# Arguments:
#   렌더 결과 파일, 잠금값
#######################################
assert_rendered() {
  local file="$1" lock="$2" items
  [[ "$(count_key "${file}" authMethod)" == 1 &&
    "$(count_line "${file}" 'authMethod: http')" == 1 ]] ||
    die "렌더 결과에 'authMethod: http' 한 줄이 없습니다 — 템플릿을 확인하세요"
  items="$(exclude_items "${file}")"
  [[ "$(count_key "${file}" authHTTPExclude)" == 1 &&
    "${items}" == '  - action: read' ]] ||
    die "렌더 결과의 authHTTPExclude 가 read 한 항목이 아닙니다 — 템플릿을 확인하세요"
  [[ "$(count_key "${file}" apiTrustedProxies)" == 1 &&
    "$(count_line "${file}" 'apiTrustedProxies: []')" == 1 ]] ||
    die "렌더 결과의 apiTrustedProxies 가 빈 목록이 아닙니다 — 템플릿을 확인하세요"
  [[ "$(count_line "${file}" "${REGEX_PATH_LINE}")" == 1 ]] ||
    die "렌더 결과에 토큰 꼴 정규식 경로가 없습니다 — 템플릿을 확인하세요"
  [[ "$(count_line "${file}" "  srtPublishPassphrase: ${lock}")" == 1 &&
    "$(count_line "${file}" "  srtReadPassphrase: ${lock}")" == 1 ]] ||
    die "렌더 결과에 잠금값 두 칸(송출 · SRT 읽기)이 없습니다 — 템플릿을 확인하세요"
}

# 치환은 셸 문자열 치환으로 한다 — 잠금값이 sed 스크립트나 프로세스 인자로 노출되지 않는다.
# 원자 교체: 임시파일에 렌더·검증한 뒤에만 mv -f 로 최종 경로에 얹는다. 도중 실패하면 trap 이
# 임시파일을 지우고 기존 정상본은 그대로 보존된다(실패 시 산출물 미잔존).
render_to() {
  local out="$1" body tmp lock
  lock="$(new_path_lock)"
  body="$(cat "$TMPL")"
  # 같은 자리표시를 두 칸에 쓰므로 전역 치환 한 번으로 두 칸이 같은 값이 된다.
  body="${body//${LOCK_PLACEHOLDER}/${lock}}"

  tmp="$out.tmp.$$"
  trap 'rm -f "$tmp"' EXIT
  (umask 0137 && : >"$tmp") # 잠금값이 들어가기 전에 0640 으로 만든다
  printf '%s\n' "$body" >"$tmp"

  if grep -q '__' "$tmp"; then
    die "렌더 결과에 플레이스홀더(__)가 남았습니다 — 산출물을 만들지 않았습니다. 템플릿을 확인하세요"
  fi
  assert_rendered "${tmp}" "${lock}"

  mv -f "$tmp" "$out" # 검증 통과분만 원자적으로 교체
  trap - EXIT
}

main() {
  [ -r "$TMPL" ] || die "템플릿을 읽을 수 없습니다: $TMPL"
  require_absolute_home
  check_env_home

  local out="$DEMO_HOME/mediamtx.demo.yml"
  render_to "$out"
  echo "rendered: $out (0640) — 서버에서는 이어서 'sudo chown root:10002 $out && sudo chmod 0640 $out' 를 실행하세요"
}

main "$@"
