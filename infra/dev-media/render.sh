#!/usr/bin/env bash
# mediamtx.demo.yml.tmpl → $DEMO_HOME/mediamtx.demo.yml 렌더 (멘토 데모용 임시 서버, 2026-08-24 만료)
#
# 사용:  sudo env DEMO_HOME=/opt/pokeclip-demo ./infra/dev-media/render.sh
#        (DEMO_PATH·SRT_PASSPHRASE 는 인자로 넘기지 않는다 — $DEMO_HOME/.env 에서 읽는다.
#         .env 는 root:root 0600 이므로 sudo 없이 실행하면 .env 읽기 검사에서 중단된다.)
#
# 이 스크립트의 가드 6종이 이 구성의 **유일한 자동 검사**다. 실패는 전부 fail-closed(산출물을 남기지 않음).
#   1) 필수 변수 미설정·빈값 거부
#   2) 길이 검사 — DEMO_PATH >= 20자, SRT_PASSPHRASE 10~64자
#   3) 문자셋 검사 — 둘 다 소문자 hex 만(정규식 경로 승격·YAML 주입 차단)
#   4) 렌더 결과에 플레이스홀더(__) 잔존 시 산출물 삭제 후 실패
#   5) DEMO_HOME 이 절대경로가 아니면 거부 — 상대경로는 compose 가 딴 디렉토리나 named volume 으로 해석한다
#   6) DEMO_HOME 디렉토리 부재 시 거부 — 오타 경로에 설정을 조용히 떨구지 않는다
set -euo pipefail

readonly MIN_DEMO_PATH_LEN=20
readonly MIN_PASSPHRASE_LEN=10 # MediaMTX 하한 10 (FFmpeg 상한 64 와의 교집합)
readonly MAX_PASSPHRASE_LEN=64
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

load_env() {
  local envfile="$DEMO_HOME/.env"
  [ -r "$envfile" ] || die "$envfile 를 읽을 수 없습니다 — 파일이 없거나(root:root 0600) sudo 없이 실행했습니다"
  set -a
  # shellcheck disable=SC1090
  . "$envfile"
  set +a
}

require_values() {
  [ -n "${DEMO_PATH:-}" ] || die "DEMO_PATH 가 비어 있습니다"
  [ -n "${SRT_PASSPHRASE:-}" ] || die "SRT_PASSPHRASE 가 비어 있습니다"
  [ "${#DEMO_PATH}" -ge "$MIN_DEMO_PATH_LEN" ] ||
    die "DEMO_PATH 가 ${#DEMO_PATH}자입니다 — ${MIN_DEMO_PATH_LEN}자 이상이어야 합니다 (openssl rand -hex 12)"
  [ "${#SRT_PASSPHRASE}" -ge "$MIN_PASSPHRASE_LEN" ] && [ "${#SRT_PASSPHRASE}" -le "$MAX_PASSPHRASE_LEN" ] ||
    die "SRT_PASSPHRASE 가 ${#SRT_PASSPHRASE}자입니다 — ${MIN_PASSPHRASE_LEN}~${MAX_PASSPHRASE_LEN}자여야 합니다 (openssl rand -hex 16)"
  # 문자셋 검사 — openssl rand -hex 산출물은 소문자 hex 뿐이다. 이 밖의 문자는 거부한다:
  #  · DEMO_PATH 가 `~^`로 시작하면 MediaMTX 가 정규식 경로로 해석해 은닉이 무너진다
  #  · 개행·따옴표·콜론이 값에 들어오면 렌더 결과가 YAML 구조를 오염시킨다
  case "$DEMO_PATH" in *[!0-9a-f]*) die "DEMO_PATH 는 소문자 hex(0-9a-f)만 허용합니다 (openssl rand -hex 12)" ;; esac
  case "$SRT_PASSPHRASE" in *[!0-9a-f]*) die "SRT_PASSPHRASE 는 소문자 hex(0-9a-f)만 허용합니다 (openssl rand -hex 16)" ;; esac
}

# 치환은 셸 문자열 치환으로 한다 — 시크릿이 sed 스크립트나 프로세스 인자로 노출되지 않는다.
# 원자 교체: 임시파일에 렌더·검증한 뒤에만 mv -f 로 최종 경로에 얹는다. 도중 실패하면 trap 이
# 임시파일을 지우고 기존 정상본은 그대로 보존된다(가드 3 "실패 시 산출물 미잔존" 계약 준수).
render_to() {
  local out="$1" body tmp
  body="$(cat "$TMPL")"
  body="${body//__DEMO_PATH__/$DEMO_PATH}"
  body="${body//__SRT_PASSPHRASE__/$SRT_PASSPHRASE}"

  tmp="$out.tmp.$$"
  trap 'rm -f "$tmp"' EXIT
  (umask 0137 && : >"$tmp") # 시크릿이 들어가기 전에 0640 으로 만든다
  printf '%s\n' "$body" >"$tmp"

  if grep -q '__' "$tmp"; then
    die "렌더 결과에 플레이스홀더(__)가 남았습니다 — 산출물을 만들지 않았습니다. 템플릿을 확인하세요"
  fi

  mv -f "$tmp" "$out" # 검증 통과분만 원자적으로 교체
  trap - EXIT
}

main() {
  [ -r "$TMPL" ] || die "템플릿을 읽을 수 없습니다: $TMPL"
  require_absolute_home
  load_env
  require_absolute_home # .env 가 DEMO_HOME 을 덮어썼을 수 있으므로 다시 본다
  require_values

  local out="$DEMO_HOME/mediamtx.demo.yml"
  render_to "$out"
  echo "rendered: $out (0640) — 서버에서는 이어서 'sudo chown root:10002 $out && sudo chmod 0640 $out' 를 실행하세요"
}

main "$@"
