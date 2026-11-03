#!/usr/bin/env bash
#
# 사이드카의 새 꼴 쓰기 스위치 전환 — 상자에서 SSM 으로 돌린다(실행 직전 승인 ·
# 방송 없는 창 · 다른 운영 스크립트와 순차). 스위치는 compose 파일 옆
# sidecar.env 한 줄(PLAYBACK_ROUND_KEYS=true|false)이고 사이드카가 env_file 로
# 받는다. env_file 은 컨테이너를 만들 때만 읽히므로 판정은 파일이 아니라 새로
# 만든 컨테이너의 기동 신호 줄로 한다.
#
# 전제: 잠금 파일 · 스위치 파일 자리의 링크 검사와 그 뒤 사용(열기 · rename)
# 사이는 비어 있다. compose 파일 디렉터리가 root 소유(0755)라 root 가 아니면 그
# 사이에 끼울 수 없다는 데 기댄다.
#
# 차례(on):
#   1. 인자 · 설정을 본다. compose 파일 옆 잠금 파일에 flock 을 기다리지 않고
#      건다 — 다른 실행이 쥐고 있거나 걸지 못하면 lock 으로 멈춘다(아무것도
#      쓰지 않는다). 잠금은 그 기술자를 가진 프로세스(스크립트 · 상한 실행
#      중인 자식)가 모두 끝나면 커널이 푼다(파일은 남긴다).
#   2. 사이드카 컨테이너가 실행 중 하나가 아니면 멈춘다 — 아무것도 쓰지 않는다.
#   3. 지금 파일이 링크 아닌 일반 파일이고 허용 줄 하나인지 본다(아니면 write —
#      아무것도 쓰지 않는다).
#   4. 파일을 같은 디렉터리의 임시 파일(root:root 0644) → rename 으로 바꾼다.
#      자리가 링크 · 일반 파일 아님이면 쓰지 않는다.
#   5. 사이드카만 재생성한다(lib/ops.sh — --no-deps 라 mediamtx · nginx 와 유닛
#      주 프로세스는 그대로).
#   6. 새 컨테이너의 태그 저널(컨테이너 ID 로 거른다)에서 playback_key_layout
#      줄을 상한 안에서 기다리고, 그 줄의 layout 이 round 인지 본다. 줄이
#      여럿이면(같은 컨테이너의 재시작) 모두 맞아야 한다. 맞으면 그 컨테이너가
#      지금 실행 중인 사이드카 하나인지 다시 본다(아니면 verify 실패).
#   5 · 6 가운데 하나라도 실패하면 복원 묶음을 돈다 — 파일을 끔으로 되돌리고
#   사이드카를 다시 재생성해, 그 재생성이 만든 컨테이너의 저널이 layout=legacy
#   인지 본다(실행 중 재확인 포함). 파일만 되돌리고 끝내지 않는다: 5 가 만든
#   round 컨테이너가 남기 때문이다. 지금 파일이 이미 켬이었어도 복원은
#   끔으로 쓴다(안전 쪽).
#
# 차례(off): 잠금은 on 의 1 과 같다. 사이드카가 실행 중 하나면 on 의
# 2 · 4 · 5 · 6(layout=legacy)과 같다(복원 묶음은 없다 — 파일은 이미 끔). 실행
# 중 하나를 확인하지 못하면(없음 · 둘 이상 · 조회 실패) 파일만 끔으로 쓰고
# 재생성하지 않는다 — 뒤는 런북이 유닛을 다시 띄운다. 지금 파일의 꼴은 보지
# 않는다(끔으로 덮는다).
#
# 출력(표준 출력 한 줄):
#   SIDECAR ROUND KEYS OK on|off                         종료 0
#   SIDECAR ROUND KEYS FILE off                          종료 3
#   SIDECAR ROUND KEYS FAIL <단계> rolled-back            종료 1
#       on 의 recreate · journal · verify 실패 뒤 복원 묶음 성공(파일 끔 · 실행
#       중 새 컨테이너 layout=legacy).
#   SIDECAR ROUND KEYS FAIL <단계> restore-failed         종료 4
#       복원 묶음의 한 걸음이 실패 — 파일 · 실행 상태 미확정(런북 손 처리).
#   SIDECAR ROUND KEYS FAIL <단계>
#       usage(종료 2) · config · lock · not-running(on 만) · write — 아무것도
#       바꾸지 않았거나 파일 원본 그대로. off 의 recreate · journal · verify(파일은
#       끔). 그 밖 종료 1.
#
# 읽는 변수는 lib/ops.sh 머리 주석. 기동 신호 줄을 기다리는 상한은
# MEDIA_OPS_HEALTH_WAIT 다.

# shellcheck source-path=SCRIPTDIR

set -euo pipefail
set +x +a
umask 077

BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/ops.sh
source "${BIN_DIR}/lib/ops.sh"

readonly LINE_PREFIX='SIDECAR ROUND KEYS'
readonly ENV_NAME='sidecar.env'
readonly ENV_KEY='PLAYBACK_ROUND_KEYS'
# 동시 실행 잠금 파일(compose 파일 옆). 기술자 번호는 리다이렉션에 글자로 적는다
# (bash 3.2 에는 {변수}> 꼴이 없다) — take_lock 의 9 와 같다.
readonly LOCK_NAME='sidecar-round-keys.lock'
readonly LOCK_FD=9
# 한 줄 파일의 크기 상한(바이트) — 시작 스크립트의 점검과 같다.
readonly ENV_MAX_BYTES=64
# 사이드카 컨테이너 로그의 journald tag(compose.yml.tmpl).
readonly JOURNAL_TAG='pokeclip-media-sidecar'
readonly LAYOUT_PATTERN='"msg":"playback_key_layout"'
readonly EXIT_FILE_ONLY=3
readonly EXIT_RESTORE_FAILED=4

env_tmp=""
# 가장 최근에 알게 된 사이드카 컨테이너 ID — 다음 재생성이 이와 달라야 한다.
current_id=""
# apply 가 실패한 단계(recreate · journal · verify).
failed_stage=""

# 끝날 때 임시 파일과 상한 실행 중인 자식을 정리한다(EXIT 트랩).
on_exit() {
  if [[ -n "${env_tmp}" ]]; then
    rm -f -- "${env_tmp}"
  fi
  ops::on_exit
}

# 실패 판정 줄을 내고 끝낸다.
fail() {
  ops::say "${LINE_PREFIX} FAIL $1"
  exit "${2:-1}"
}

#######################################
# 동시 실행 잠금을 기다리지 않고 건다. 자리가 링크면 걸지 않는다. 기술자는
# 스크립트가 끝날 때까지 연 채로 둔다.
# Arguments:
#   잠금 파일.
# Returns:
#   0 잡음 / 1 다른 실행이 쥠 · 열지 못함 · 링크.
#######################################
take_lock() {
  [[ ! -L "$1" ]] || return 1
  { exec 9>>"$1"; } 2>/dev/null || return 1
  flock -n "${LOCK_FD}" 2>/dev/null
}

#######################################
# 컨테이너가 지금 실행 중인 사이드카 하나인지 본다 — 기동 신호 줄을 낸 뒤
# 멈췄거나 재시작 중이면 아니다.
# Arguments:
#   컨테이너 ID, 명령 한 번의 상한(초).
# Returns:
#   0 맞음 / 1 아님 · 조회 실패.
#######################################
still_running() {
  local id
  id="$(ops::one_container "${OPS_SIDECAR_SERVICE}" "$2")" || return 1
  [[ "${id}" == "$1" ]]
}

#######################################
# 지금 파일이 링크 아닌 일반 파일이고 내용이 정확히 허용 줄 하나(끝 줄바꿈은
# 있어도 없어도)인지 본다. 크기는 원본 바이트로 대조한다(명령 치환은 NUL 을
# 지운다).
# Arguments:
#   스위치 파일.
# Returns:
#   0 맞음 / 1 없음 · 꼴 밖.
#######################################
check_current() {
  local size content LC_ALL=C
  [[ -f "$1" && ! -L "$1" ]] || return 1
  size="$(wc -c <"$1")" || return 1
  size=$((size + 0))
  (( size <= ENV_MAX_BYTES )) || return 1
  content="$(cat -- "$1" && printf x)" || return 1
  content="${content%x}"
  (( ${#content} == size )) || return 1
  content="${content%$'\n'}"
  [[ "${content}" == "${ENV_KEY}=true" || "${content}" == "${ENV_KEY}=false" ]]
}

#######################################
# 스위치 파일을 원자 교체한다.
# Globals:
#   env_tmp
# Arguments:
#   스위치 파일, true | false.
# Returns:
#   0 바꿈 / 1 쓰지 못함(원본 그대로).
#######################################
write_env() {
  local target="$1"
  [[ ! -L "${target}" && ( ! -e "${target}" || -f "${target}" ) ]] || return 1
  env_tmp="$(mktemp "${target%/*}/.${ENV_NAME}.XXXXXX" 2>/dev/null)" \
    || return 1
  { printf '%s=%s\n' "${ENV_KEY}" "$2" >"${env_tmp}" \
    && chown 0:0 "${env_tmp}" && chmod 0644 "${env_tmp}" \
    && mv -f -- "${env_tmp}" "${target}"; } 2>/dev/null || return 1
  env_tmp=""
}

#######################################
# 컨테이너의 playback_key_layout 줄을 상한 안에서 기다려 layout 을 대조한다.
# 조회 한 번의 상한은 남은 시간과 명령 상한 가운데 작은 쪽이다(ops::remaining —
# 기한이 지나면 다음 조회를 시작하지 않는다).
# Arguments:
#   컨테이너 ID, round | legacy, 기다리는 상한(초), 명령 한 번의 상한(초).
# Returns:
#   0 맞음 / 1 저널 조회 실패 · 줄 없음 / 2 다른 layout 이 있음.
#######################################
verify_layout() {
  local id="$1" want="$2" deadline lines started matched bound
  deadline=$((SECONDS + $3))
  while :; do
    bound="$(ops::remaining "${deadline}" "$4")" || return 1
    lines="$(ops::bounded "${bound}" journalctl -t "${JOURNAL_TAG}" \
      "CONTAINER_ID_FULL=${id}" -q --no-pager -o cat </dev/null \
      2>/dev/null)" || return 1
    started="$(printf '%s\n' "${lines}" | grep -c -F -- "${LAYOUT_PATTERN}")" \
      || started=0
    (( started == 0 )) || break
    sleep 1
  done
  matched="$(printf '%s\n' "${lines}" | grep -F -- "${LAYOUT_PATTERN}" \
    | grep -c -F -- "\"layout\":\"${want}\"")" || matched=0
  (( matched == started )) || return 2
}

#######################################
# 사이드카를 재생성하고 새 컨테이너의 layout 을 대조한 뒤 그 컨테이너가 실행
# 중인지 다시 본다. 실패하면 단계를 failed_stage 에 둔다.
# Globals:
#   current_id, failed_stage
# Arguments:
#   compose 파일, round | legacy, 명령 한 번의 상한(초), 기다리는 상한(초).
# Returns:
#   0 맞음 / 1 실패.
#######################################
apply() {
  local id status=0
  if ! id="$(ops::recreate_sidecar "$1" "$3" "${current_id}")"; then
    failed_stage=recreate
    return 1
  fi
  current_id="${id}"
  verify_layout "${id}" "$2" "$4" "$3" || status=$?
  if (( status == 0 )) && ! still_running "${id}" "$3"; then
    status=2
  fi
  case "${status}" in
    0) return 0 ;;
    2) failed_stage=verify ;;
    *) failed_stage=journal ;;
  esac
  return 1
}

#######################################
# on — 켜고, 실패하면 복원 묶음(파일 끔 → 재생성 → 그 컨테이너 legacy 대조).
# Globals:
#   current_id, failed_stage
# Arguments:
#   compose 파일, 스위치 파일, 명령 한 번의 상한(초), 기다리는 상한(초).
#######################################
switch_on() {
  local stage
  current_id="$(ops::one_container "${OPS_SIDECAR_SERVICE}" "$3")" \
    || fail not-running
  check_current "$2" || fail write
  write_env "$2" true || fail write
  apply "$1" round "$3" "$4" && return 0
  stage="${failed_stage}"
  if write_env "$2" false && apply "$1" legacy "$3" "$4"; then
    fail "${stage} rolled-back"
  fi
  fail "${stage} restore-failed" "${EXIT_RESTORE_FAILED}"
}

#######################################
# off — 끄고 재생성한다. 실행 중 사이드카 하나를 확인하지 못하면 파일만 쓴다.
# Globals:
#   current_id, failed_stage
# Arguments:
#   compose 파일, 스위치 파일, 명령 한 번의 상한(초), 기다리는 상한(초).
#######################################
switch_off() {
  if ! current_id="$(ops::one_container "${OPS_SIDECAR_SERVICE}" "$3")"; then
    write_env "$2" false || fail write
    ops::say "${LINE_PREFIX} FILE off"
    exit "${EXIT_FILE_ONLY}"
  fi
  write_env "$2" false || fail write
  apply "$1" legacy "$3" "$4" || fail "${failed_stage}"
}

main() {
  local want limit wait file
  if (( $# != 1 )) || [[ "$1" != on && "$1" != off ]]; then
    fail usage 2
  fi
  want="$1"
  trap on_exit EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if ! limit="$(ops::timeout)" || ! wait="$(ops::health_wait)" \
    || ! file="$(ops::compose_file)"; then
    fail config
  fi
  take_lock "${file%/*}/${LOCK_NAME}" || fail lock
  if [[ "${want}" == on ]]; then
    switch_on "${file}" "${file%/*}/${ENV_NAME}" "${limit}" "${wait}"
  else
    switch_off "${file}" "${file%/*}/${ENV_NAME}" "${limit}" "${wait}"
  fi
  ops::say "${LINE_PREFIX} OK ${want}"
}

main "$@"
