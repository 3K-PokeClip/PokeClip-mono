# shellcheck shell=bash
#
# 송출 인증 응답기의 거부 목록 파일 — 꼴 점검과 원자 설치. 시작 스크립트(응답기
# 모드)와 거부 목록 동기화 스크립트가 함께 쓴다.
#
# 꼴 점검은 기동을 막을 「빔」 만 본다(kty 정의): 첫 줄이 정확히 머리 줄
# (DENY_TOKENS_HEADER)이면 토큰 0건도 정상이다. 공백뿐 · 머리 줄 없음 · NUL ·
# 크기 상한 초과는 꼴 밖이다. 토큰 줄은 읽지 않는다 — 줄 판독의 정본은 응답기
# 파서(media/internal/ingestauth/denylist.go) 하나다.
#
# 설치는 같은 디렉터리의 임시 파일을 소유 · 모드를 맞춘 뒤 rename 한다 — 응답기가
# 읽는 옛 파일을 제자리에서 고쳐 쓰지 않는다. 디렉터리는 root:<응답기 그룹>
# 0750, 파일은 root:<응답기 그룹> 0640 이다(응답기는 읽기만 한다).
#
# 이 파일은 source 해서 쓴다(실행하지 않는다). 상자(AL2023 · bash 5)와 kty
# 로컬(macOS · bash 3.2) 양쪽에서 돈다.

readonly DENY_TOKENS_HEADER='# pokeclip deny-tokens v1'
# 응답기 파서의 크기 상한(denyListMaxBytes = 64 KiB)과 같다.
readonly DENY_TOKENS_MAX_BYTES=65536
# shellcheck disable=SC2034 # 부르는 스크립트가 읽는다.
readonly DENY_TOKENS_FILE_NAME='deny-tokens'

#######################################
# 거부 목록 디렉터리를 root:<그룹> 0750 으로 맞춘다. 링크면 받지 않는다(가리키는
# 곳에 쓰지 않는다). 맞춘 뒤 링크 아닌 디렉터리인지 다시 본다.
# Arguments:
#   디렉터리, 응답기 그룹(숫자 gid).
# Returns:
#   0 / 1 실패.
#######################################
deny_tokens::prepare_dir() {
  [[ ! -L "$1" ]] || return 1
  mkdir -p -- "$1" && chown -h "0:$2" "$1" && chmod 0750 "$1" || return 1
  [[ -d "$1" && ! -L "$1" ]]
}

#######################################
# 파일 하나가 거부 목록 꼴인지 본다 — 원본 바이트로 크기 · NUL 을 보고(명령
# 치환은 NUL 을 지운다), 첫 줄이 정확히 머리 줄인지 본다. 공백뿐인 파일은 첫
# 줄이 머리 줄이 아니라 여기서 걸린다.
# Arguments:
#   파일.
# Returns:
#   0 꼴 맞음 / 1 꼴 밖 · 읽기 실패.
#######################################
deny_tokens::valid() {
  local size kept first LC_ALL=C
  [[ -f "$1" && ! -L "$1" ]] || return 1
  size="$(wc -c <"$1")" || return 1
  size=$((size + 0))
  (( size <= DENY_TOKENS_MAX_BYTES )) || return 1
  kept="$(tr -d '\000' <"$1" | wc -c)" || return 1
  (( kept + 0 == size )) || return 1
  first="$(head -n 1 -- "$1" && printf x)" || return 1
  first="${first%x}"
  first="${first%$'\n'}"
  [[ "${first}" == "${DENY_TOKENS_HEADER}" ]]
}

#######################################
# 꼴을 본 임시 파일(대상과 같은 디렉터리)을 root:<그룹> 0640 으로 맞춰 대상
# 이름으로 rename 한다. 대상이 링크 · 일반 파일 아님이면 받지 않는다(디렉터리면
# mv 가 그 안으로 옮기고 성공한다).
# Arguments:
#   임시 파일, 대상 경로, 응답기 그룹(숫자 gid).
# Returns:
#   0 둠 / 1 실패(임시 파일은 부르는 쪽이 지운다).
#######################################
deny_tokens::install() {
  chown "0:$3" "$1" && chmod 0640 "$1" || return 1
  [[ ! -L "$2" && ( ! -e "$2" || -f "$2" ) ]] || return 1
  mv -f -- "$1" "$2"
}
