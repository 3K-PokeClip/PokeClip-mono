#!/usr/bin/env bash
#
# 송출 인증 응답기의 IP 한도 전환(응답기 모드 전용) — 상자에서 SSM 으로
# 돌린다(실행 직전 승인 · 다른 응답기 운영 스크립트와 순차).
#
# 차례:
#   1. 인자(on | off)를 본다.
#   2. 응답기 컨테이너가 실행 중 하나가 아니면(옛 모드 · 정지) 멈춘다 — 아무것도
#      쓰거나 띄우지 않는다.
#   3. compose 파일 옆 ingest-auth.env 를 같은 디렉터리의 임시 파일
#      (root:root 0644) → rename 으로 바꾼다. 자리가 링크 · 일반 파일 아님이면
#      쓰지 않는다.
#   4. 응답기만 재생성하고 healthy 를 기다린다(lib/ops.sh — env_file 은 만들 때만
#      읽히므로 restart 로는 반영되지 않는다).
#   5. 새 컨테이너의 태그 저널(컨테이너 ID 로 거른다 — 옛 컨테이너 줄은 보지
#      않는다)에서 ingestauth_started 줄을 기다리고, 그 줄의 ip_limit 가 요청한
#      값인지 본다. 줄이 여럿이면(같은 컨테이너의 재시작) 모두 맞아야 한다.
#
# 출력(표준 출력 한 줄):
#   INGEST AUTH IP LIMIT OK on|off
#   INGEST AUTH IP LIMIT FAIL <단계>
#       usage(종료 2) · config · not-running · write · recreate · unhealthy ·
#       journal · verify(그 밖 종료 1).
#
# 읽는 변수는 lib/ops.sh 머리 주석.

# shellcheck source-path=SCRIPTDIR

set -euo pipefail
set +x +a
umask 077

BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BIN_DIR
# shellcheck source=lib/ops.sh
source "${BIN_DIR}/lib/ops.sh"

readonly LINE_PREFIX='INGEST AUTH IP LIMIT'
readonly ENV_NAME='ingest-auth.env'
# 응답기 컨테이너 로그의 journald tag(compose.yml.tmpl).
readonly JOURNAL_TAG='pokeclip-media-ingest-auth'
readonly STARTED_PATTERN='"msg":"ingestauth_started"'

env_tmp=""

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
# env 파일을 원자 교체한다. 실패하면 write 로 끝낸다.
# Globals:
#   env_tmp
# Arguments:
#   env 파일, on | off.
#######################################
write_env() {
  local target="$1"
  [[ ! -L "${target}" && ( ! -e "${target}" || -f "${target}" ) ]] \
    || fail write
  env_tmp="$(mktemp "${target%/*}/.${ENV_NAME}.XXXXXX" 2>/dev/null)" \
    || fail write
  { printf 'INGEST_AUTH_IP_LIMIT=%s\n' "$2" >"${env_tmp}" \
    && chown 0:0 "${env_tmp}" && chmod 0644 "${env_tmp}" \
    && mv -f -- "${env_tmp}" "${target}"; } 2>/dev/null || fail write
  env_tmp=""
}

#######################################
# 새 컨테이너의 started 줄을 상한 안에서 기다려 ip_limit 를 대조한다. 조회 한
# 번의 상한은 남은 시간과 명령 상한 가운데 작은 쪽이다(ops::remaining — 기한이
# 지나면 다음 조회를 시작하지 않는다). 실패 단계로 끝낸다.
# Arguments:
#   컨테이너 ID, on | off, 상한(초).
#######################################
verify_started() {
  local id="$1" want="$2" limit="$3" deadline lines started matched bound
  deadline=$((SECONDS + limit))
  while :; do
    bound="$(ops::remaining "${deadline}" "${limit}")" || fail journal
    lines="$(ops::bounded "${bound}" journalctl -t "${JOURNAL_TAG}" \
      "CONTAINER_ID_FULL=${id}" -q --no-pager -o cat </dev/null \
      2>/dev/null)" || fail journal
    started="$(printf '%s\n' "${lines}" | grep -c -F -- "${STARTED_PATTERN}")" \
      || started=0
    (( started == 0 )) || break
    sleep 1
  done
  matched="$(printf '%s\n' "${lines}" | grep -F -- "${STARTED_PATTERN}" \
    | grep -c -F -- "\"ip_limit\":\"${want}\"")" || matched=0
  (( matched == started )) || fail verify
}

main() {
  local want limit wait file id
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
  ops::one_container "${OPS_INGEST_AUTH_SERVICE}" "${limit}" >/dev/null \
    || fail not-running
  write_env "${file%/*}/${ENV_NAME}" "${want}"
  id="$(ops::recreate_ingest_auth "${file}" "${limit}" "${wait}")" \
    || { (( $? == 1 )) && fail recreate; fail unhealthy; }
  verify_started "${id}" "${want}" "${limit}"
  ops::say "${LINE_PREFIX} OK ${want}"
}

main "$@"
